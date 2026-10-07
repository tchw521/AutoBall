package com.autoball.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.autoball.AB
import com.autoball.core.engine.JsEngines
import com.autoball.core.util.Display

/**
 * 设置页（v3 #p-set）：分组卡 + 行列表。
 *
 * 一比一对齐：
 * - .topbar：返回按钮（34dp .bk）+ h1 26px + 右上图标按钮
 * - .sgh：分组标题，12px/800 主色，padding 16×18×7
 * - .scard：卡片，左右 12 外边距，圆角 14，内部行不留外边距
 * - .srow：29dp 图标（圆角 9）+ 主副标题（.st1 13.5px / .st2 10.5px）+ 右侧开关/值
 */
class SetPage(context: Context, private val host: PageHost) : FrameLayout(context) {

    private val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init { build() }

    private fun build() {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(topbar())
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        wrap.setPadding(0, 0, 0, Display.dpInt(context, 92f))
        scroll.addView(wrap)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        render()
    }

    private fun topbar(): LinearLayout {
        val b = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 6f),
                Display.dpInt(context, 18f), Display.dpInt(context, 12f))
            gravity = Gravity.BOTTOM or Gravity.CENTER_VERTICAL
        }
        b.addView(TextView(context).apply {
            text = "‹"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textSec())
            gravity = Gravity.CENTER
            background = Theme.rect(Theme.surface(), 11f, context, Theme.line())
            val s = Display.dpInt(context, 34f)
            layoutParams = LinearLayout.LayoutParams(s, s)
            setOnClickListener { host.showPage(4) }
        })
        b.addView(TextView(context).apply {
            text = "设置"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textPri())
            includeFontPadding = false
            setPadding(Display.dpInt(context, 10f), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        b.addView(TextView(context).apply {
            text = if (Theme.isDark()) "☾" else "☀"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(if (Theme.isDark()) Color.parseColor("#A78BFA")
            else Color.parseColor("#F79009"))
            background = Theme.rect(Theme.surface(), 12f, context, Theme.line())
            val s = Display.dpInt(context, 36f)
            layoutParams = LinearLayout.LayoutParams(s, s)
            setOnClickListener { host.toggleTheme() }
        })
        return b
    }

    private fun render() {
        wrap.removeAllViews()

        // ---- 通用 ----
        wrap.addView(groupHead("通用"))
        val g1 = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        g1.addView(switchRow("深色主题", "跟随设计稿双主题", Theme.isDark(), Theme.pri()) {
            host.toggleTheme()
        })
        g1.addView(valueRow("悬浮设置", "悬浮球 / 悬浮窗 / 手势", Theme.pri2()) {
            host.openSubPage("float")
        })
        g1.addView(valueRow("运行日志", "查看每一步的执行结果", Theme.ok()) {
            host.openSubPage("log")
        })
        wrap.addView(card(g1))

        // ---- 执行 ----
        wrap.addView(groupHead("执行"))
        val g2 = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        g2.addView(valueRow("脚本引擎",
            JsEngines.engineName() + if (JsEngines.engineName() == "quickjs") "（原生）" else "（纯 Java）",
            Theme.pri2()) {})
        g2.addView(switchRow("运行前体检", "缺少能力时提前提示",
            AB.store.getBool("preflight", true), Theme.ok()) {
            AB.store.putBool("preflight", it)
        })
        g2.addView(switchRow("失败自动切换通道",
            "无障碍与 Shizuku 之间自动回退", AB.store.getBool("auto_fallback", true),
            Theme.warn()) {
            AB.store.putBool("auto_fallback", it)
        })
        wrap.addView(card(g2))

        // ---- 坐标 ----
        wrap.addView(groupHead("坐标"))
        val g3 = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        g3.addView(valueRow("坐标基准", "百分比（换机型不偏移）", Theme.pri()) {})
        g3.addView(switchRow("转屏自动适配", "旋转后按新宽高换算",
            AB.store.getBool("auto_rotate", true), Theme.pri2()) {
            AB.store.putBool("auto_rotate", it)
        })
        wrap.addView(card(g3))
    }

    /** .sgh：12px/800 主色 */
    private fun groupHead(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Theme.pri())
        letterSpacing = 0.02f
        setPadding(Display.dpInt(context, 18f), Display.dpInt(context, 16f),
            Display.dpInt(context, 18f), Display.dpInt(context, 7f))
    }

    /** .scard：左右 12 外边距，圆角 14 */
    private fun card(inner: LinearLayout): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = Theme.rect(Theme.surface(), 14f, context, Theme.line())
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(Display.dpInt(context, 12f), 0,
            Display.dpInt(context, 12f), Display.dpInt(context, 12f))
        layoutParams = lp
        addView(inner)
        clipToOutline = true
    }

    /** .srow：图标 + 主副标题 + 右侧开关 */
    private fun switchRow(title: String, sub: String, init: Boolean, color: Int,
                          onChange: (Boolean) -> Unit): LinearLayout {
        var on = init
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 12f),
                Display.dpInt(context, 14f), Display.dpInt(context, 12f))
        }
        row.addView(iconBox(title.take(1), color))
        row.addView(twoLine(title, sub))
        val track = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Theme.rect(if (on) Theme.pri2() else Theme.line2(), 12f, context)
            layoutParams = LinearLayout.LayoutParams(
                Display.dpInt(context, Theme.SW_W), Display.dpInt(context, Theme.SW_H))
        }
        track.addView(android.view.View(context).apply {
            background = Theme.oval(Color.WHITE)
            val lp = LinearLayout.LayoutParams(Display.dpInt(context, Theme.SW_KNOB),
                Display.dpInt(context, Theme.SW_KNOB))
            lp.leftMargin = if (on) Display.dpInt(context, 21f) else Display.dpInt(context, 3f)
            lp.topMargin = Display.dpInt(context, 3f)
            layoutParams = lp
        })
        row.addView(track)
        row.setOnClickListener {
            on = !on
            track.background = Theme.rect(if (on) Theme.pri2() else Theme.line2(), 12f, context)
            val knob = (track.getChildAt(0).layoutParams as LinearLayout.LayoutParams)
            knob.leftMargin = if (on) Display.dpInt(context, 21f) else Display.dpInt(context, 3f)
            track.getChildAt(0).requestLayout()
            onChange(on)
        }
        return row
    }

    /** .srow：图标 + 主副标题 + 右侧值 */
    private fun valueRow(title: String, sub: String, color: Int,
                         onClick: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 12f),
                Display.dpInt(context, 14f), Display.dpInt(context, 12f))
            setOnClickListener { onClick() }
            addView(iconBox(title.take(1), color))
            addView(twoLine(title, sub))
            addView(TextView(context).apply {
                text = "›"
                textSize = 17f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.textTer())
            })
        }

    /** .si：29dp 圆角 9 */
    private fun iconBox(glyph: String, color: Int): TextView = TextView(context).apply {
        text = glyph
        textSize = 13f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = Theme.rect(color, 9f, context)
        layoutParams = LinearLayout.LayoutParams(Display.dpInt(context, 29f),
            Display.dpInt(context, 29f))
    }

    /** .sm + .st1 + .st2 */
    private fun twoLine(title: String, sub: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Display.dpInt(context, 11f)
                marginEnd = Display.dpInt(context, 6f)
            }
            addView(TextView(context).apply {
                text = title
                textSize = 13.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.textPri())
            })
            addView(TextView(context).apply {
                text = sub
                textSize = 10.5f
                setTextColor(Theme.textSec())
                setLineSpacing(Display.dp(context, 1f), 1.45f)
                setPadding(0, Display.dpInt(context, 3f), 0, 0)
            })
        }
}
