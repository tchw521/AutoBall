package com.autoball.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.autoball.AB
import com.autoball.core.engine.JsEngines
import com.autoball.core.model.Script
import com.autoball.core.model.ScriptKind
import com.autoball.core.util.Display

/**
 * JS 脚本页（v3 #p-js）：状态栏 + 子栏 + 等宽代码编辑器 + 运行。
 *
 * 一比一对齐：
 * - 自带 .statusbar（整页沉浸式，与悬浮设置页一致）
 * - .subbar：34dp 返回 .bk + h2 + 右侧 .pill
 * - .field：脚本名（圆角 13，padding 11×14）
 * - .editor：等宽、圆角 14、行高 1.9、左右 18 外边距
 * - .btnline：.btn.ghost（保存）+ .btn.pri（运行）
 * - .tip：可用 API 速查
 */
class JsPage(context: Context, private val host: PageHost) : FrameLayout(context) {

    companion object {
        val SAMPLE = """
// 点击屏幕中央，等 1 秒后返回
click(50, 50);
wait(1000);
key(4);
""".trimIndent()
    }

    private var script: Script? = null
    private val nameEt = EditText(context)
    private val codeEt = EditText(context)
    private val infoTv = TextView(context)

    init { build() }

    private fun build() {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(Kit.statusBar(context))

        val samplePill = Kit.pill(context, "示例") { codeEt.setText(SAMPLE) }
        // 导入分享码：与「编写 JS 代码」同处一屏
        val importPill = Kit.pill(context, "导入分享码") {
            val act = context as? android.app.Activity ?: return@pill
            ShareImportDialog.show(act, host)
        }
        val trailing = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(samplePill)
            addView(importPill)
        }
        root.addView(Kit.subbar(context, "JS 脚本", { host.showPage(0) }, trailing))

        val pad = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Display.dpInt(context, 18f), 0,
                Display.dpInt(context, 18f), Display.dpInt(context, 16f))
        }

        // 脚本名
        val nameRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.rect(Theme.surface(), 13f, context, Theme.line())
            setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 11f),
                Display.dpInt(context, 14f), Display.dpInt(context, 11f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, Display.dpInt(context, 9f))
            layoutParams = lp
        }
        nameRow.addView(TextView(context).apply {
            text = "名称"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textSec())
            layoutParams = LinearLayout.LayoutParams(
                Display.dpInt(context, 52f), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        nameEt.apply {
            setTextColor(Theme.textPri())
            setHintTextColor(Theme.textTer())
            hint = "脚本名称"
            background = null
            setSingleLine(true)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
        }
        nameRow.addView(nameEt, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        pad.addView(nameRow)

        // 引擎提示
        pad.addView(infoTv.apply {
            textSize = 11f
            setTextColor(Theme.textTer())
            setPadding(Display.dpInt(context, 2f), 0, 0, Display.dpInt(context, 8f))
        })

        // .editor
        val edBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.rect(Theme.surface(), 14f, context, Theme.line())
            setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 12f),
                Display.dpInt(context, 14f), Display.dpInt(context, 12f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        edBox.addView(TextView(context).apply {
            text = "// 在此编写脚本，可用 API 见下方速查"
            textSize = 11.5f
            setTextColor(Theme.textTer())
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 0, 0, Display.dpInt(context, 8f))
        })
        codeEt.apply {
            textSize = 11.5f
            setTextColor(Theme.textSec())
            setHintTextColor(Theme.textTer())
            typeface = android.graphics.Typeface.MONOSPACE
            background = null
            gravity = android.view.Gravity.TOP
            setLineSpacing(Display.dp(context, 3f), 1.9f)
            setHorizontallyScrolling(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        edBox.addView(codeEt)
        pad.addView(edBox)

        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(pad)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // .btnline
        val btnLine = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Display.dpInt(context, 18f), Display.dpInt(context, 14f),
                Display.dpInt(context, 18f), Display.dpInt(context, 14f))
        }
        btnLine.addView(Kit.button(context, "保存", false) { save() }.apply {
            layoutParams = LinearLayout.LayoutParams(0,
                Display.dpInt(context, Theme.BTN_H), 1f)
        })
        btnLine.addView(Kit.button(context, "运行", true) {
            save()
            script?.let { host.runScript(it) }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0,
                Display.dpInt(context, Theme.BTN_H), 1f).apply {
                marginStart = Display.dpInt(context, 9f)
            }
        })
        root.addView(btnLine)

        root.addView(Kit.tip(context,
            "可用 API：click(x,y) / swipe(x1,y1,x2,y2,ms) / longPress(x,y,ms) / " +
                "wait(ms) / key(code) / text(s) / log(s) / screenshot() / findText(s)").apply {
            (layoutParams as LinearLayout.LayoutParams).setMargins(
                Display.dpInt(context, 18f), 0,
                Display.dpInt(context, 18f), Display.dpInt(context, 18f))
        })

        addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        infoTv.text = "引擎：${JsEngines.engineName()}"
    }

    fun bind(s: Script) {
        script = s
        nameEt.setText(s.name)
        codeEt.setText(s.jsCode.ifBlank { SAMPLE })
    }

    private fun save() {
        val s = script ?: Script.blank(nameEt.text.toString().ifBlank { "JS 脚本" }).also {
            it.kind = ScriptKind.JS
        }
        s.kind = ScriptKind.JS
        s.name = nameEt.text.toString().ifBlank { "JS 脚本" }
        s.jsCode = codeEt.text.toString()
        s.updatedAt = System.currentTimeMillis()
        AB.store.save(s)
        script = s
        Ui.toast(context, "已保存「${s.name}」")
    }

}
