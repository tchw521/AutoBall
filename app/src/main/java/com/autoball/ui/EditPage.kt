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
import com.autoball.core.recorder.GlobalSettingsDialog

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
    /**
     * 脚本全局设置：复用统一组件 [GlobalSettingsDialog]，
     * 与工作台弹窗右上角「⚙」是同一个弹窗，参数口径一致。
     * （下方 legacyGlobalSettings 保留旧的明细表单，暂未被调用）
     */
    private fun globalSettings() {
        val act = context as? Activity ?: return
        val flow = script?.flow
        if (flow == null) {
            Ui.toast(act, "请先选择一个脚本")
            return
        }
        GlobalSettingsDialog.showFloat(act, flow) { save(); syncGlobal() }
    }


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
            // 1 次是默认值，不值得占一行摘要；0 且 loop 开 = 无限
            if (f.loopCount > 1) append(" · 重复 ${f.loopCount} 次")
            else if (f.loop && f.loopCount == 0) append(" · 无限循环")
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

    /**
     * 复制的动作（进程内剪贴板）。
     *
     * 用深拷贝而非引用：直接存引用的话，粘贴后再编辑其中一个，
     * 另一个会跟着变——用户以为是独立的两步。
     */
    private var clipboard: com.autoball.core.model.Action? = null

    /**
     * 步骤多选状态（R-108）：批量改等待 / 重复 / 启用 / 删除。
     *
     * 长脚本逐个改参数很痛——20 步都要把等待从 500ms 改成 1s，
     * 得点 20 次编辑框。多选后一次改完。
     */
    private val selSteps = LinkedHashSet<Int>()
    private var multiMode = false

    init { build() }

    private fun build() {
        val root = Kit.root(context)
        root.addView(Kit.topbar(context, "编辑", "未选择脚本", listOf(
            Kit.actionBtn(context, "⚙", Theme.pri2()) { globalSettings() },
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
            background = Theme.rect(Theme.chipOn(), 13f, context)
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
            if (loop) Theme.pri2() else Theme.chipOn(), 13f, context)
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

    /** 多选工具条：进入/退出 + 全选 + 批量操作 */
    private fun multiBar(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(Display.dpInt(context, 10f), Display.dpInt(context, 6f),
                Display.dpInt(context, 10f), Display.dpInt(context, 6f))
            background = Theme.rect(Theme.surface2(), 12f, context, Theme.pri())
        }
        row.addView(TextView(context).apply {
            text = if (multiMode) "退出多选" else "☑ 多选"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.pri())
            setPadding(Display.dpInt(context, 10f), Display.dpInt(context, 6f),
                Display.dpInt(context, 10f), Display.dpInt(context, 6f))
            setOnClickListener {
                multiMode = !multiMode
                if (!multiMode) selSteps.clear()
                renderSteps()
            }
        })
        if (!multiMode) return row

        val acts = script?.flow?.actions ?: return row
        row.addView(TextView(context).apply {
            text = if (selSteps.size == acts.size) "取消全选" else "全选"
            textSize = 12f
            setTextColor(Theme.textSec())
            setPadding(Display.dpInt(context, 10f), Display.dpInt(context, 6f),
                Display.dpInt(context, 10f), Display.dpInt(context, 6f))
            setOnClickListener {
                if (selSteps.size == acts.size) selSteps.clear()
                else selSteps.addAll(acts.indices)
                renderSteps()
            }
        })
        row.addView(TextView(context).apply {
            text = "已选 ${selSteps.size}"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.pri())
            setPadding(Display.dpInt(context, 6f), Display.dpInt(context, 6f),
                Display.dpInt(context, 6f), Display.dpInt(context, 6f))
        })
        return row
    }

    /** 选中步骤的操作按钮：改等待 / 改重复 / 启用 / 禁用 / 删除 */
    private fun multiActions(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(Display.dpInt(context, 8f), Display.dpInt(context, 4f),
                Display.dpInt(context, 8f), Display.dpInt(context, 4f))
        }
        fun btn(t: String, danger: Boolean = false, cb: () -> Unit) {
            row.addView(TextView(context).apply {
                text = t
                textSize = 11.5f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(if (danger) Theme.danger() else Theme.pri())
                background = Theme.rect(Theme.surface(), 10f, context,
                    if (danger) Theme.danger() else Theme.pri())
                setPadding(Display.dpInt(context, 10f), Display.dpInt(context, 6f),
                    Display.dpInt(context, 10f), Display.dpInt(context, 6f))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(Display.dpInt(context, 3f), 0, Display.dpInt(context, 3f), 0)
                layoutParams = lp
                setOnClickListener { cb() }
            })
        }
        btn("等待") { batchWait() }
        btn("重复") { batchRepeat() }
        btn("启用") { batchEnabled(true) }
        btn("禁用") { batchEnabled(false) }
        btn("删除", true) { batchDelete() }
        return row
    }

    private fun selected(): List<com.autoball.core.model.Action> {
        val acts = script?.flow?.actions ?: return emptyList()
        return selSteps.sorted().mapNotNull { acts.getOrNull(it) }
    }

    private fun batchWait() {
        val act = context as? android.app.Activity ?: return
        val et = android.widget.EditText(act).apply {
            hint = "毫秒"; setSingleLine(true); textSize = 13f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        box.addView(et)
        Ui.dialog(act, "统一设置等待（${selSteps.size} 步）").body(box)
            .width(Theme.DIALOG_W)
            .negative("取消") { }
            .positive("确定") {
                val v = et.text.toString().trim().toLongOrNull()
                if (v == null) { Ui.toast(act, "请输入毫秒数"); false }
                else {
                    selected().forEach { it.waitMs = v }
                    save(); renderSteps()
                    Ui.toast(act, "已设置 ${selSteps.size} 步等待 ${v}ms"); true
                }
            }.show()
    }

    private fun batchRepeat() {
        val act = context as? android.app.Activity ?: return
        val et = android.widget.EditText(act).apply {
            hint = "次数"; setSingleLine(true); textSize = 13f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        box.addView(et)
        Ui.dialog(act, "统一设置重复（${selSteps.size} 步）").body(box)
            .width(Theme.DIALOG_W)
            .negative("取消") { }
            .positive("确定") {
                val v = et.text.toString().trim().toIntOrNull()
                if (v == null || v < 1) { Ui.toast(act, "请输入 ≥1 的次数"); false }
                else {
                    selected().forEach { it.repeat = v }
                    save(); renderSteps()
                    Ui.toast(act, "已设置 ${selSteps.size} 步重复 $v 次"); true
                }
            }.show()
    }

    private fun batchEnabled(on: Boolean) {
        selected().forEach { it.enabled = on }
        save(); renderSteps()
        Ui.toast(context, "已${if (on) "启用" else "禁用"} ${selSteps.size} 步")
    }

    private fun batchDelete() {
        val act = context as? android.app.Activity ?: return
        val n = selSteps.size
        Ui.dialog(act, "删除 $n 步").body(LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(Kit.note(act, "删除后无法撤销（可用脚本快照回滚）。"))
        }).width(Theme.DIALOG_W)
            .negative("取消") { }
            .positive("删除") {
                val acts = script?.flow?.actions ?: return@positive false
                selSteps.sortedDescending().forEach { acts.removeAt(it) }
                selSteps.clear()
                multiMode = false
                save(); renderSteps()
                Ui.toast(act, "已删除 $n 步"); true
            }.show()
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
        if (script?.flow?.actions?.isNotEmpty() == true) {
            listBox.addView(multiBar())
            if (multiMode && selSteps.isNotEmpty()) listBox.addView(multiActions())
        }
        listBox.addView(addStepBtn())
        listBox.addView(templateRow())
        listBox.addView(snapshotRow())
        if (clipboard != null) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            row.addView(pasteButton())
            listBox.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, Display.dpInt(context, 8f))
            })
        }
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

    /** 模板入口行：与「＋添加动作」并列 */
    private fun templateRow(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(TextView(context).apply {
            text = "⊞ 动作模板"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Theme.pri())
            background = Theme.rect(Theme.surface2(), 12f, context, Theme.pri())
            setPadding(Display.dpInt(context, 12f), Display.dpInt(context, 9f),
                Display.dpInt(context, 12f), Display.dpInt(context, 9f))
            setOnClickListener { showTemplates() }
        })
        return row
    }

    /** 底部粘贴按钮：剪贴板为空时置灰，避免点了没反应 */
    private fun pasteButton(): TextView {
        val has = clipboard != null
        return TextView(context).apply {
            text = "⧉ 粘贴"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (has) Theme.pri() else Theme.textTer())
            background = Theme.rect(
                if (has) Theme.surface2() else Theme.surface(), 12f, context,
                if (has) Theme.pri() else Theme.line())
            setPadding(Display.dpInt(context, 12f), Display.dpInt(context, 9f),
                Display.dpInt(context, 12f), Display.dpInt(context, 9f))
            isEnabled = has
            alpha = if (has) 1f else 0.5f
            setOnClickListener { if (has) pasteAt(-1) }
        }
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
        if (multiMode) {
            row.addView(TextView(context).apply {
                text = if (i in selSteps) "☑" else "☐"
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(if (i in selSteps) Theme.pri() else Theme.textTer())
                setPadding(Display.dpInt(context, 6f), 0, Display.dpInt(context, 6f), 0)
                setOnClickListener {
                    if (i in selSteps) selSteps.remove(i) else selSteps.add(i)
                    renderSteps()
                }
            })
        }
        row.addView(Kit.miniBtn(context, "⧉") { copyAt(i) })
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
        // 持有副本，绝不共享 ScriptStore 缓存里的对象：
        // 原地修改共享对象会污染缓存，并让 save() 的"保存前快照"拍到
        // 已经改过的新状态（list[idx] === s），回滚形同虚设。
        script = s.copy()
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

    /** 复制第 i 步到剪贴板 */
    private fun copyAt(i: Int) {
        val acts = script?.flow?.actions ?: return
        if (i !in acts.indices) return
        val src = acts[i]
        // 用 Action.copy(newId=true)：此前手工逐字段复制，漏掉了
        // colorHex / nodeSpec / imageRef / failOp / jitterDp 等十余项，
        // 且嵌套对象是浅拷贝——粘贴出来的"点击节点"会丢掉节点选择器。
        clipboard = src.copy(newId = true)
        Ui.toast(context, "已复制第 ${i + 1} 步")
    }

    /**
     * 粘贴：插在 i 之后；i < 0 时追加到末尾。
     * 复用悬浮窗「更多工具」的 newAction 思路——都走同一个预设复制路径。
     */
    private fun pasteAt(i: Int) {
        val c = clipboard
        if (c == null) { Ui.toast(context, "剪贴板为空，请先复制一步"); return }
        val s = script ?: return
        if (s.flow == null) s.flow = com.autoball.core.model.Flow()
        val acts = s.flow!!.actions
        val copy = c.copy(newId = true)
        val at = if (i < 0) acts.size else (i + 1).coerceAtMost(acts.size)
        acts.add(at, copy)
        save(); renderSteps()
        Ui.toast(context, "已粘贴为第 ${at + 1} 步")
    }

    /**
     * 动作模板库（R-103）：插入一组预置或自建的多步序列。
     *
     * 单步复制粘贴已解决「重复配置一个动作」，但「点+等+点」这类
     * 多步组合仍要一个个加。模板库补上这一层。
     */
    /** 历史版本入口：误删动作后的后悔药 */
    private fun snapshotRow(): LinearLayout {
        val n = script?.let { com.autoball.core.store.SnapshotStore.count(it.id) } ?: 0
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(TextView(context).apply {
            text = if (n > 0) "🕘 历史版本（$n）" else "🕘 历史版本"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (n > 0) Theme.pri() else Theme.textTer())
            background = Theme.rect(Theme.surface2(), 12f, context,
                if (n > 0) Theme.pri() else Theme.line())
            setPadding(Display.dpInt(context, 12f), Display.dpInt(context, 9f),
                Display.dpInt(context, 12f), Display.dpInt(context, 9f))
            setOnClickListener {
                val act = context as? android.app.Activity ?: return@setOnClickListener
                val s = script ?: return@setOnClickListener
                SnapshotDialog.show(act, s) { bind(it) }
            }
        })
        return row
    }

    private fun showTemplates() {
        val act = context as? android.app.Activity ?: return
        val list = com.autoball.core.store.ActionTemplateStore.all()
        if (list.isEmpty()) { Ui.toast(context, "还没有模板"); return }

        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        list.forEach { t ->
            box.addView(Kit.rowCard(context).apply {
                addView(Kit.twoLine(context, t.name,
                    "${t.actions.size} 步 · ${t.desc.ifEmpty { "无说明" }}"))
                addView(Kit.miniBtn(context, "插入") {
                    val s = script ?: return@miniBtn
                    if (s.flow == null) s.flow = com.autoball.core.model.Flow()
                    t.actions.forEach { a ->
                        s.flow!!.actions.add(
                            com.autoball.core.store.ActionTemplateStore.clone(a))
                    }
                    save(); renderSteps()
                    Ui.toast(context, "已插入「${t.name}」${t.actions.size} 步")
                })
            })
        }

        // 把当前脚本的**全部动作**存为模板
        box.addView(Kit.button(context, "＋ 把当前脚本存为模板", true) {
            val s = script
            val acts = s?.flow?.actions
            if (acts.isNullOrEmpty()) {
                Ui.toast(context, "当前脚本还没有动作")
                return@button
            }
            saveAsTemplate(act, acts)
        })

        Ui.dialog(act, "动作模板").body(box)
            .width(Theme.DIALOG_W + 20f).maxHeight(0.78f)
            .negative("关闭") { }.show()
    }

    /** 存为模板：需要输入名称 */
    private fun saveAsTemplate(act: android.app.Activity,
                               acts: List<com.autoball.core.model.Action>) {
        val et = android.widget.EditText(act).apply {
            hint = "模板名称"
            setSingleLine(true)
            textSize = 13f
            setText(script?.name ?: "")
        }
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        box.addView(et)
        Ui.dialog(act, "存为模板").body(box).width(Theme.DIALOG_W)
            .negative("取消") { }
            .positive("保存") {
                val n = et.text.toString().trim()
                if (n.isEmpty()) { Ui.toast(act, "请输入名称"); false }
                else {
                    com.autoball.core.store.ActionTemplateStore.add(n, "", acts)
                    Ui.toast(act, "已存为模板「$n」")
                    true
                }
            }.show()
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
        // 单步（R-131）：每个动作前暂停，配合悬浮条放行。
        // 调试"到底哪一步点错了"最有效的手段——比事后翻日志直观得多。
        box.addView(Ui.sheetOption(act, "⏯", Theme.pri(), "单步运行",
            "每步暂停，用悬浮条逐步放行（仅动作流）") {
            script?.let { com.autoball.core.engine.ScriptLauncher.launch(act, it, emptyMap(), true) }
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
