package com.autoball.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.autoball.AB
import com.autoball.core.engine.ScriptLauncher
import com.autoball.core.model.Action
import com.autoball.core.model.Script
import com.autoball.core.recorder.GestureCompiler
import com.autoball.core.util.Display

/**
 * 编辑页（v3 #p-edit）：顶栏 + 字段区 + 动作步骤列表 + JS 预览。
 *
 * 一比一对齐：
 * - .topbar：h1 26px/800 + 副标题 12px + 两个 36dp 图标按钮（强调色 / 更多）
 * - .field：label + input，圆角 13，padding 11×14
 * - .secrow：分区标题 + .addbtn（右侧渐变胶囊，30dp 高）
 * - .step：序号 .no（22dp）+ 类型 .ty（13.5px/700）+ 说明 .ds；末尾 .step.add 虚线
 * - .codebox：等宽预览，圆角 13，行高 1.85
 * - .btnline：.btn.ghost + .btn.pri，44dp 高 / 圆角 13
 * - .tip：左侧 3px 主色竖条
 */
class EditPage(context: Context, private val host: PageHost) : FrameLayout(context) {

    private var script: Script? = null
    private val nameEd = EditText(context)
    private val loopBox = TextView(context)
    private val speedEd = EditText(context)
    private val listBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val preview = TextView(context)
    private val subTv = TextView(context)
    private val codeBox = TextView(context)

    private var loop = false

    init { build() }

    private fun build() {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(topbar())

        val pad = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        pad.setPadding(Display.dpInt(context, 18f), 0,
            Display.dpInt(context, 18f), Display.dpInt(context, 96f))

        // ---- 名称（.field）----
        pad.addView(field("名称", nameEd.apply {
            setTextColor(Theme.textPri()); setHintTextColor(Theme.textSec())
            hint = "脚本名称"
            background = null
            setSingleLine(true)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
        }))

        // ---- 循环 / 倍速（两列）----
        val two = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        loopBox.apply {
            text = "循环：关"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Theme.rect(Color.parseColor("#3A2E6B"), 13f, context)
            setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 11f),
                Display.dpInt(context, 14f), Display.dpInt(context, 11f))
            setOnClickListener {
                loop = !loop
                text = "循环：" + if (loop) "开" else "关"
                background = Theme.rect(
                    if (loop) Theme.pri2() else Color.parseColor("#3A2E6B"), 13f, context)
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
            val lp = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = Display.dpInt(context, 9f)
            layoutParams = lp
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
        pad.addView(two)

        // ---- 动作步骤（.secrow + .addbtn）----
        val secRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, Display.dpInt(context, 20f), 0, Display.dpInt(context, 9f))
            layoutParams = lp
        }
        secRow.addView(TextView(context).apply {
            text = "动作步骤"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textTer())
            letterSpacing = 0.03f
        })
        secRow.addView(TextView(context).apply {
            text = "＋ 添加动作"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Theme.grad(context, 10f)
            setPadding(Display.dpInt(context, 12f), 0, Display.dpInt(context, 12f), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Display.dpInt(context, 30f)).apply {
                marginStart = Display.dpInt(context, 10f)
            }
            // margin-left:auto 的效果：左侧放一个占位
            setOnClickListener { addAction() }
        })
        pad.addView(secRow)

        pad.addView(listBox)

        // ---- JS 预览（.sec + .codebox）----
        pad.addView(section("JS 预览"))
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
        pad.addView(codeBox)

        // ---- .btnline ----
        val btnLine = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, Display.dpInt(context, 14f), 0, 0)
            layoutParams = lp
        }
        btnLine.addView(button("保存草稿", false) { save(); host.refreshAll() }.apply {
            layoutParams = LinearLayout.LayoutParams(0,
                Display.dpInt(context, Theme.BTN_H), 1f)
        })
        btnLine.addView(button("保存并运行", true) {
            save(); host.refreshAll()
            script?.let { host.runScript(it) }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0,
                Display.dpInt(context, Theme.BTN_H), 1f).apply {
                marginStart = Display.dpInt(context, 9f)
            }
        })
        pad.addView(btnLine)

        // ---- .tip ----
        pad.addView(tip("动作按顺序执行。长按步骤可上下移动；点「＋」从 20 类动作里挑选。"))

        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(pad)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        renderSteps()
    }

    // ---------- 组件 ----------

    private fun topbar(): LinearLayout {
        val b = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Display.dpInt(context, 18f), Display.dpInt(context, 6f),
                Display.dpInt(context, 18f), Display.dpInt(context, 12f))
            gravity = Gravity.BOTTOM
        }
        val l = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        l.addView(TextView(context).apply {
            text = "编辑"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textPri())
            includeFontPadding = false
        })
        l.addView(subTv.apply {
            text = "未选择脚本"
            textSize = 12f
            setTextColor(Theme.textSec())
            setPadding(0, Display.dpInt(context, 3f), 0, 0)
        })
        b.addView(l, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // 强调色按钮：保存
        b.addView(iconBtn("✓", Theme.pri()) { save(); host.refreshAll() })
        b.addView(iconBtn("⋯", 0) { showMore() })
        return b
    }

    private fun iconBtn(glyph: String, color: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = glyph
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (color != 0) Color.WHITE else Theme.textSec())
            gravity = Gravity.CENTER
            background = if (color != 0) Theme.rect(color, 12f, context)
            else Theme.rect(Theme.surface(), 12f, context, Theme.line())
            val s = Display.dpInt(context, 36f)
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                marginStart = Display.dpInt(context, 6f)
            }
            setOnClickListener { onClick() }
        }

    /** .field：label + 内容 */
    private fun field(label: String, content: View): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.rect(Theme.surface(), 13f, context, Theme.line())
            setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 11f),
                Display.dpInt(context, 14f), Display.dpInt(context, 11f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, Display.dpInt(context, 9f))
            layoutParams = lp
            addView(TextView(context).apply {
                text = label
                textSize = 12.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.textSec())
                layoutParams = LinearLayout.LayoutParams(
                    Display.dpInt(context, 56f),
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            addView(content, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun section(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 11f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Theme.textTer())
        letterSpacing = 0.03f
        setPadding(Display.dpInt(context, 2f), Display.dpInt(context, 14f),
            Display.dpInt(context, 2f), Display.dpInt(context, 8f))
    }

    /** .btn / .btn.pri / .btn.ghost */
    private fun button(text: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (primary) Color.WHITE else Theme.textPri())
            gravity = Gravity.CENTER
            background = if (primary) Theme.grad(context, Theme.BTN_R)
            else Theme.rect(Theme.surface(), Theme.BTN_R, context, Theme.line())
            setOnClickListener { onClick() }
        }

    /** .tip：左侧 3px 主色竖条 */
    private fun tip(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(Theme.textSec())
        setLineSpacing(Display.dp(context, 2f), 1.7f)
        setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 11f),
            Display.dpInt(context, 14f), Display.dpInt(context, 11f))
        background = Theme.tipBg(context)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, Display.dpInt(context, 12f), 0, Display.dpInt(context, 12f))
        layoutParams = lp
    }

    // ---------- 步骤列表 ----------

    private fun renderSteps() {
        listBox.removeAllViews()
        val acts = script?.flow?.actions ?: emptyList<Action>().toMutableList()
        if (acts.isEmpty()) {
            listBox.addView(hintBox("还没有动作。点右上「＋ 添加动作」开始，或用「录制」自动生成。"))
        } else {
            acts.forEachIndexed { i, a -> listBox.addView(stepRow(i, a)) }
        }
        // .step.add：虚线「添加」
        listBox.addView(TextView(context).apply {
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
        })
        refreshPreview()
    }

    /** .step：序号 + 类型 + 说明 + 上移/下移/删除 */
    private fun stepRow(i: Int, a: Action): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.rect(Theme.surface(), 13f, context, Theme.line())
            setPadding(Display.dpInt(context, 12f), Display.dpInt(context, 11f),
                Display.dpInt(context, 12f), Display.dpInt(context, 11f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, Display.dpInt(context, 9f))
            layoutParams = lp
        }
        // .no
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
        val ds = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        ds.addView(TextView(context).apply {
            text = a.type.label
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textPri())
        })
        ds.addView(TextView(context).apply {
            text = ActionEditor.describe(a)
            textSize = 11.5f
            setTextColor(Theme.textSec())
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(ds, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = Display.dpInt(context, 10f)
        })
        // 上移 / 下移 / 删除
        row.addView(miniBtn("↑") { move(i, -1) })
        row.addView(miniBtn("↓") { move(i, 1) })
        row.addView(miniBtn("✕") { removeAt(i) })
        row.setOnClickListener { ActionEditor.show(context as? Activity ?: return@setOnClickListener, a) {
            save(); renderSteps() } }
        return row
    }

    /** .mini：38dp 圆形按钮 */
    private fun miniBtn(glyph: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = glyph
        textSize = 13f
        setTypeface(null, Typeface.BOLD)
        setTextColor(if (Theme.isDark()) Color.parseColor("#B9B2D6")
        else Color.parseColor("#5B5570"))
        gravity = Gravity.CENTER
        background = Theme.oval(if (Theme.isDark()) Color.parseColor("#2A2340")
        else Color.parseColor("#FFFFFF"))
        val s = Display.dpInt(context, 34f)
        layoutParams = LinearLayout.LayoutParams(s, s).apply {
            marginStart = Display.dpInt(context, 6f)
        }
        setOnClickListener { onClick() }
    }

    private fun hintBox(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(Theme.textSec())
        setLineSpacing(Display.dp(context, 2f), 1.7f)
        setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 13f),
            Display.dpInt(context, 14f), Display.dpInt(context, 13f))
        background = Theme.dashed(context, 13f)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, Display.dpInt(context, 9f))
        layoutParams = lp
    }

    // ---------- 数据操作 ----------

    fun bind(s: Script) {
        script = s
        nameEd.setText(s.name)
        loop = s.flow?.loop == true
        loopBox.text = "循环：" + if (loop) "开" else "关"
        loopBox.background = Theme.rect(
            if (loop) Theme.pri2() else Color.parseColor("#3A2E6B"), 13f, context)
        val n = s.flow?.actions?.size ?: 0
        subTv.text = "${if (s.kind == com.autoball.core.model.ScriptKind.JS) "JS 脚本" else "动作流"} · $n 个动作"
        renderSteps()
    }

    private fun move(i: Int, delta: Int) {
        val acts = script?.flow?.actions ?: return
        val j = i + delta
        if (j < 0 || j >= acts.size) return
        val t = acts[i]; acts[i] = acts[j]; acts[j] = t
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
            if (s.flow == null) s.flow = com.autoball.core.model.Flow()
            s.flow!!.actions.add(a)
            save(); renderSteps()
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
        refreshPreview()
        val n = s.flow?.actions?.size ?: 0
        subTv.text = "${if (s.kind == com.autoball.core.model.ScriptKind.JS) "JS 脚本" else "动作流"} · $n 个动作"
    }

    private fun refreshPreview() {
        val s = script
        codeBox.text = if (s == null) "// 未选择脚本"
        else runCatching { GestureCompiler.toJs(s.flow ?: com.autoball.core.model.Flow()) }
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
            val code = com.autoball.core.store.ShareCode.encode(s)
            ShareImportDialog.showCopy(act, s.name, code)
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
