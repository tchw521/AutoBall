package com.autoball.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.AB
import com.autoball.core.model.Action
import com.autoball.core.model.Flow
import com.autoball.core.model.Script
import com.autoball.core.model.ScriptKind
import com.autoball.core.recorder.GestureCompiler
import com.autoball.core.util.Display
import com.autoball.core.engine.Morph

/**
 * 编辑页（v3 #p-edit）：顶栏 + 字段区 + 动作步骤列表 + JS 预览。
 *
 * 布局组件全部走 Kit；本文件只负责数据绑定与步骤操作。
 *
 * 修复（v1.3）：
 * - 拖动排序原来用 `handler.removeCallbacksAndMessages(null)` 收尾，会连带清掉
 *   该 Handler 上排队的其它消息；改为持有 Runnable 引用精确移除。
 * - 表单取值原来靠「按出现顺序数第几个输入框」，字段一增删就错位；
 *   改为给每个输入控件打 key，按 key 精确回读。
 */
class EditPage(context: Context, private val host: PageHost) : FrameLayout(context) {

    private var script: Script? = null
    private val nameEd = EditText(context)
    private val loopBox = TextView(context)
    private val speedEd = EditText(context)
    private val listBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val subTv = TextView(context)
    private val codeBox = TextView(context)

    private var loop = false
    private var targetRef: TextView? = null
    private var globalTv: TextView? = null

    /** 脚本全局设置（v3 gdlg：等待 / 重复 / 失败策略 / morph / 监听钩子） */
    private fun globalSettings() {
        val act = context as? Activity ?: return
        val flow = script?.flow ?: return
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

        // 默认等待
        box.addView(Ui.adRow(act, "默认等待",
            "${flow.defaultWaitMs}${if (flow.waitUnit == "s") " 秒" else if (flow.waitUnit == "min") " 分" else " 毫秒"}",
            flow.defaultWaitMs > 0, "每个动作之间额外等待的时间") {
            Ui.popMenu(act, box, listOf("0 毫秒", "200 毫秒", "500 毫秒", "1 秒", "3 秒"),
                WAITS.indexOf(flow.defaultWaitMs).coerceAtLeast(0)) { k ->
                flow.defaultWaitMs = WAITS[k]
                save(); syncGlobal()
            }
        })
        // 重复次数
        box.addView(Ui.adRow(act, "重复次数",
            if (flow.loopCount == 0) "1 次" else "${flow.loopCount} 次",
            false, "填 0 代表无限循环，配合循环开关使用") {
            Ui.popMenu(act, box, listOf("1 次", "3 次", "5 次", "10 次", "无限"),
                if (flow.loopCount == 0) 4
                else listOf(1L, 3L, 5L, 10L).indexOf(flow.loopCount.toLong()).coerceAtLeast(0)) { k ->
                flow.loopCount = if (k == 4) 0 else listOf(1, 3, 5, 10)[k]
                save(); syncGlobal()
            }
        })
        box.addView(Ui.adSec(act))
        box.addView(Ui.switchRow(act, "有动作失败立即暂停", flow.failStop) {
            flow.failStop = it; save(); syncGlobal()
        })
        box.addView(Ui.switchRow(act, "失败自动重试一次", flow.retryOnce) {
            flow.retryOnce = it; save(); syncGlobal()
        })
        box.addView(Ui.adSec(act))
        // morph
        box.addView(Ui.adRow(act, "手势矩阵变形",
            if (flow.morph.isBlank()) "未设置" else flow.morph,
            flow.morph.isNotBlank(),
            "让坐标带上随机抖动，更接近真人。留空表示不变换") {
            morphDialog(act, flow)
        })
        // 监听钩子
        val (stages, n) = flow.hookSummary()
        box.addView(Ui.adRow(act, "全局监听动作",
            if (stages == 0) "未设置" else "已设置 $stages 项 · $n 个动作",
            stages > 0, "9 个时机可挂多个动作，用于截图、日志、兜底") {
            ListenerDialog.show(act, flow) { save(); syncGlobal() }
        })

        Ui.dialog(act, "脚本全局设置").body(box)
            .width(Theme.DIALOG_W + 30f).maxHeight(0.76f)
            .negative("关闭").show()
    }

    private val WAITS = longArrayOf(0L, 200L, 500L, 1000L, 3000L)

    /** morph 预设选择 + 自定义输入 */
    private fun morphDialog(act: Activity, flow: com.autoball.core.model.Flow) {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val customRaw = com.autoball.AB.store.getString("morph_presets", "")
        val custom = ArrayList<String>()
        customRaw.split("|").forEach { if (it.contains("#")) custom.add(it) }
        val options = ArrayList<String>()
        Morph.BUILTIN.forEach { (n, _) -> options.add(n) }
        custom.forEach { options.add(it.substringBefore("#")) }
        options.add("不变换")
        options.add("自定义…")

        box.addView(Ui.note(act,
            "预设会对坐标做仿射变换；自定义可填 a,b,c,d,e,f（css matrix），" +
                "e/f 写 ±N 表示随机抖动，另可加「· 时长±20%」。"))
        options.forEach { name ->
            val v = when {
                name == "不变换" -> ""
                name == "自定义…" -> null
                Morph.BUILTIN.any { (n, _) -> n == name } ->
                    Morph.BUILTIN.first { (n, _) -> n == name }.second
                else -> custom.firstOrNull { c -> c.substringBefore("#") == name }
                    ?.substringAfter("#") ?: ""
            }
            val on = v != null && v == flow.morph
            box.addView(Ui.adRow(act, name,
                if (v == null) "手动输入" else if (v.isEmpty()) "关闭" else "已选",
                on, "选择该变形预设") {
                if (v == null) morphInputDialog(act, flow)
                else { flow.morph = v; save(); syncGlobal() }
            })
        }
        Ui.dialog(act, "手势矩阵变形").body(box)
            .width(Theme.DIALOG_W + 30f).maxHeight(0.76f)
            .negative("关闭").show()
    }

    private fun morphInputDialog(act: Activity, flow: com.autoball.core.model.Flow) {
        val et = android.widget.EditText(act).apply {
            setText(flow.morph)
            hint = "如 1,0,0,1,±6,±6"
            setHintTextColor(Theme.textTer())
            setTextColor(Theme.textPri())
            textSize = 12.5f
            setSingleLine(true)
        }
        val nameEt = android.widget.EditText(act).apply {
            hint = "预设名（保存后可复用）"
            setHintTextColor(Theme.textTer())
            setTextColor(Theme.textPri())
            textSize = 12.5f
            setSingleLine(true)
        }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Display.dpInt(act, 14f), Display.dpInt(act, 8f),
                Display.dpInt(act, 14f), 0)
            addView(et); addView(nameEt)
        }
        Ui.dialog(act, "自定义变形").body(box)
            .negative("取消")
            .positive("确定") {
                val v = et.text.toString().trim()
                if (v.isNotEmpty() && !Morph.valid(v)) {
                    Ui.toast(act, "格式不正确，应为 a,b,c,d,e,f")
                    false
                } else {
                    flow.morph = v
                    val nm = nameEt.text.toString().trim()
                    if (nm.isNotEmpty() && v.isNotEmpty()) {
                        val old = com.autoball.AB.store.getString("morph_presets", "")
                        com.autoball.AB.store.putString("morph_presets",
                            (old.split("|").filter { it.isNotBlank() && it.substringBefore("#") != nm }
                                    + listOf("$nm#$v")).joinToString("|"))
                    }
                    save(); syncGlobal()
                    true
                }
            }.show()
    }

    private fun syncGlobal() {
        val f = script?.flow
        globalTv?.text = if (f == null) "" else buildString {
            append("等待 ${f.defaultWaitMs}ms")
            if (f.loopCount > 0) append(" · 重复 ${f.loopCount} 次")
            else if (f.loop) append(" · 无限循环")
            if (f.failStop) append(" · 失败暂停")
            if (f.retryOnce) append(" · 失败重试")
            if (f.morph.isNotBlank()) append(" · 已变形")
            val (st, n) = f.hookSummary()
            if (st > 0) append(" · 监听 $st 项")
        }
    }

    private fun syncTarget(tv: TextView) {
        val pkg = script?.targetPkg
        tv.text = if (pkg.isNullOrBlank()) "不限 · 保持当前界面"
        else Display.appLabel(context, pkg)
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var longPressTask: Runnable? = null
    private var dragIndex = -1

    init { build() }

    private fun build() {
        val root = Kit.root(context)
        root.addView(Kit.topbar(context, "编辑", "未选择脚本", listOf(
            Kit.actionBtn(context, "✓", Theme.pri()) { save(); host.refreshAll() },
            Kit.actionBtn(context, "⋯") { showMore() }
        ), subtitleView = subTv))

        val col = Kit.column(context)
        col.addView(Kit.field(context, "名称", nameEd.apply {
            setTextColor(Theme.textPri()); setHintTextColor(Theme.textSec())
            hint = "脚本名称"
            background = null
            setSingleLine(true)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
        }))

        // ---- 目标应用 ----
        val targetTv = TextView(context).apply {
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textSec())
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val targetRow = Kit.field(context, "目标应用",
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(targetTv)
                addView(TextView(context).apply {
                    text = "›"
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Theme.textTer())
                })
            })
        targetRow.setOnClickListener {
            val act = context as? Activity ?: return@setOnClickListener
            val sc = script ?: return@setOnClickListener
            TargetAppDialog.show(act, sc) {
                save()
                syncTarget(targetTv)
            }
        }
        targetRef = targetTv
        col.addView(targetRow)

        // ---- 循环 / 倍速 ----
        val two = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        loopBox.apply {
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Theme.rect(Color.parseColor("#3A2E6B"), 13f, context)
            setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 11f),
                Display.dpInt(context, 14f), Display.dpInt(context, 11f))
            setOnClickListener {
                loop = !loop
                syncLoop()
                save()
            }
        }
        two.addView(loopBox, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sp = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.rect(Theme.surface(), 13f, context, Theme.line())
            setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 11f),
                Display.dpInt(context, 14f), Display.dpInt(context, 11f))
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Display.dpInt(context, 9f)
            }
        }
        sp.addView(TextView(context).apply {
            text = "倍速"
            textSize = 12.5f
            setTextColor(Theme.textSec())
        })
        speedEd.apply {
            setText("1.0")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(Theme.textPri())
            background = null
            gravity = Gravity.END
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        sp.addView(speedEd)
        two.addView(sp)
        col.addView(two)

        // ---- 脚本全局设置（v3 GS）----
        col.addView(Kit.secRow(context, "脚本设置", "全局设置") { globalSettings() })
        globalTv = TextView(context).apply {
            textSize = 11.5f
            setTextColor(Theme.textSec())
            setPadding(Display.dpInt(context, 2f), 0, Display.dpInt(context, 2f),
                Display.dpInt(context, 6f))
        }
        col.addView(globalTv)

        // ---- 动作步骤 ----
        col.addView(Kit.secRow(context, "动作步骤", "＋ 添加动作") { addAction() })
        col.addView(listBox)

        col.addView(Kit.section(context, "JS 预览"))
        codeBox.apply {
            textSize = 11.5f
            setTextColor(Theme.textSec())
            typeface = android.graphics.Typeface.MONOSPACE
            setLineSpacing(Display.dp(context, 3f), 1.85f)
            background = Theme.rect(Theme.surface(), 13f, context, Theme.line())
            setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 12f),
                Display.dpInt(context, 14f), Display.dpInt(context, 12f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, Display.dpInt(context, 9f))
            layoutParams = lp
            setHorizontallyScrolling(true)
        }
        col.addView(codeBox)

        col.addView(Kit.btnLine(context,
            "保存草稿" to { save(); host.refreshAll() },
            "保存并运行" to {
                save(); host.refreshAll()
                script?.let { host.runScript(it) }
            }))

        col.addView(Kit.tip(context,
            "动作按顺序执行。长按步骤可拖动排序，或用 ↑↓ 微调；点「＋」从 20 类动作里挑选。"))

        val sc = android.widget.ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        sc.addView(col)
        root.addView(sc, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        syncLoop()
        renderSteps()
    }

    private fun syncLoop() {
        loopBox.text = "循环：" + if (loop) "开" else "关"
        loopBox.background = Theme.rect(
            if (loop) Theme.pri2() else Color.parseColor("#3A2E6B"), 13f, context)
    }

    // ---------- 步骤列表 ----------

    /**
     * 定位并高亮某个动作（运行日志点失败步骤时调用）。
     * 高亮 1.6 秒后自动恢复，避免一直挂着醒目底色。
     */
    fun focusStep(actionId: String) {
        val acts = script?.flow?.actions ?: return
        val idx = acts.indexOfFirst { it.id == actionId }
        if (idx < 0) {
            Ui.toast(context, "该动作已不在脚本中")
            return
        }
        renderSteps()
        val row = listBox.getChildAt(idx) as? LinearLayout ?: return
        row.background = Theme.rect(Theme.surface2(), 13f, context, Theme.danger())
        post { (parent as? android.widget.ScrollView)?.let { sv ->
            sv.smoothScrollTo(0, row.top)
        } ?: run {
            // 外层可能是 ScrollView 的父级，逐级向上找
            var v: android.view.ViewParent? = parent
            while (v != null) {
                if (v is android.widget.ScrollView) { v.smoothScrollTo(0, row.top); break }
                v = v.parent
            }
        } }
        postDelayed({
            row.background = Theme.rect(Theme.surface(), 13f, context, Theme.line())
        }, 1600)
    }

    private fun renderSteps() {
        listBox.removeAllViews()
        val acts = script?.flow?.actions ?: emptyList<Action>().toMutableList()
        if (acts.isEmpty()) {
            listBox.addView(Kit.hintBox(context,
                "还没有动作。点右上「＋ 添加动作」开始，或用「录制」自动生成。"))
        } else {
            acts.forEachIndexed { i, a -> listBox.addView(stepRow(i, a)) }
        }
        listBox.addView(addStepBtn())
        refreshPreview()
    }

    /** 虚线「＋ 添加动作」（v3 .step.add） */
    private fun addStepBtn(): TextView = TextView(context).apply {
        text = "＋ 添加动作"
        textSize = 13f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Theme.pri())
        gravity = Gravity.CENTER
        background = Theme.dashed(context, 13f)
        setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 13f),
            Display.dpInt(context, 14f), Display.dpInt(context, 13f))
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, Display.dpInt(context, 9f))
        layoutParams = lp
        setOnClickListener { addAction() }
    }

    private fun stepRow(i: Int, a: Action): LinearLayout {
        val row = Kit.rowCard(context)
        row.addView(TextView(context).apply {
            text = (i + 1).toString()
            textSize = 10.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textTer())
            gravity = Gravity.CENTER
            background = Theme.rect(Theme.surface2(), 7f, context)
            layoutParams = LinearLayout.LayoutParams(Display.dpInt(context, 22f),
                Display.dpInt(context, 22f))
        })
        row.addView(Kit.twoLine(context, a.type.label, ActionEditor.describe(a)))
        row.addView(Kit.miniBtn(context, "↑") { move(i, -1) })
        row.addView(Kit.miniBtn(context, "↓") { move(i, 1) })
        row.addView(Kit.miniBtn(context, "✕") { removeAt(i) })
        row.setOnClickListener {
            val act = context as? Activity ?: return@setOnClickListener
            ActionEditor.show(act, a, script?.flow) { save(); renderSteps() }
        }
        attachDragSort(row, i)
        return row
    }

    /**
     * 拖动排序（需求 2.6）：长按 320ms 抬起，跟随手指纵向移动，
     * 松手按落点换算目标位置。用 Runnable 引用精确取消，不误伤其它消息。
     */
    private fun attachDragSort(row: LinearLayout, index: Int) {
        var startY = 0f
        var moved = false
        var dragging = false
        row.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = e.rawY
                    moved = false
                    dragging = false
                    val task = Runnable {
                        if (moved) return@Runnable
                        dragging = true
                        dragIndex = index
                        v.animate().scaleX(1.03f).scaleY(1.03f)
                            .translationZ(Display.dp(context, 6f)).setDuration(140).start()
                        v.background = Theme.rect(Theme.surface2(), 13f, context, Theme.pri())
                        Ui.toast(context, "拖动到目标位置后松手")
                    }
                    longPressTask = task
                    handler.postDelayed(task, 320)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - startY
                    if (kotlin.math.abs(dy) > Display.dp(context, 6f)) moved = true
                    if (dragging) v.translationY = dy
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressTask?.let { handler.removeCallbacks(it) }
                    longPressTask = null
                    if (dragging) {
                        val rowH = (v.height.takeIf { it > 0 }
                            ?: Display.dpInt(context, 56f)).toFloat()
                        val delta = (e.rawY - startY) / rowH
                        val maxIdx = ((script?.flow?.actions?.size ?: 1) - 1).coerceAtLeast(0)
                        val target = (index + kotlin.math.round(delta)).toInt().coerceIn(0, maxIdx)
                        v.animate().scaleX(1f).scaleY(1f).translationY(0f)
                            .translationZ(0f).setDuration(140).start()
                        v.background = Theme.rect(Theme.surface(), 13f, context, Theme.line())
                        dragging = false
                        dragIndex = -1
                        if (target != index) moveTo(index, target)
                    }
                    false
                }
                else -> false
            }
        }
    }

    // ---------- 数据操作 ----------

    fun bind(s: Script) {
        script = s
        nameEd.setText(s.name)
        loop = s.flow?.loop == true
        syncLoop()
        speedEd.setText((s.flow?.speed ?: 1f).toString())
        updateSub()
        targetRef?.let { syncTarget(it) }
        syncGlobal()
        renderSteps()
    }

    private fun updateSub() {
        val n = script?.flow?.actions?.size ?: 0
        val kind = if (script?.kind == ScriptKind.JS) "JS 脚本" else "动作流"
        subTv.text = "$kind · $n 个动作"
    }

    private fun move(i: Int, delta: Int) {
        val acts = script?.flow?.actions ?: return
        val j = i + delta
        if (j < 0 || j >= acts.size) return
        val t = acts[i]; acts[i] = acts[j]; acts[j] = t
        save(); renderSteps()
    }

    private fun moveTo(from: Int, to: Int) {
        val acts = script?.flow?.actions ?: return
        if (from !in acts.indices) return
        val target = to.coerceIn(0, acts.size - 1)
        if (target == from) return
        acts.add(target, acts.removeAt(from))
        save(); renderSteps()
    }

    private fun removeAt(i: Int) {
        val acts = script?.flow?.actions ?: return
        if (i !in acts.indices) return
        acts.removeAt(i)
        save(); renderSteps()
    }

    private fun addAction() {
        val act = context as? Activity ?: return
        ActionEditor.show(act, null) { a ->
            val s = script
            if (s == null) {
                Ui.toast(act, "请先选择或新建脚本")
                return@show
            }
            if (s.flow == null) s.flow = Flow()
            s.flow!!.actions.add(a)
            save()
            renderSteps()
        }
    }

    private fun save() {
        val s = script ?: return
        s.name = nameEd.text.toString().ifBlank { "未命名脚本" }
        s.flow?.loop = loop
        val sp = speedEd.text.toString().toFloatOrNull() ?: 1f
        s.flow?.speed = sp.coerceIn(0.1f, 10f)
        s.updatedAt = System.currentTimeMillis()
        AB.store.save(s)
        updateSub()
        refreshPreview()
    }

    private fun refreshPreview() {
        val s = script
        codeBox.text = if (s == null) "// 未选择脚本"
        else runCatching { GestureCompiler.toJs(s.flow ?: Flow()) }
            .getOrElse { "// 预览生成失败：${it.message}" }
    }

    private fun showMore() {
        val act = context as? Activity ?: return
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        box.addView(Ui.sheetOption(act, "▶", Theme.pri2(), "运行脚本", "立即执行一次") {
            script?.let { host.runScript(it) }
        })
        box.addView(Ui.sheetOption(act, "●", Theme.ok(), "从此录制", "在当前脚本后追加录制的动作") {
            host.startRecording()
        })
        box.addView(Ui.sheetOption(act, "🔗", Theme.pri(), "生成分享码", "把脚本打包成一串码") {
            val s = script ?: return@sheetOption
            ShareImportDialog.showCopy(act, s.name, com.autoball.core.store.ShareCode.encode(s))
        })
        box.addView(Ui.sheetOption(act, "✕", Theme.danger(), "删除脚本", "不可恢复") {
            val s = script ?: return@sheetOption
            AB.store.delete(setOf(s.id))
            script = null
            host.refreshAll()
        })
        Ui.sheet(act, "更多操作").body(box).show()
    }
}
