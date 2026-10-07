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
            ActionEditor.show(act, a) { save(); renderSteps() }
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
