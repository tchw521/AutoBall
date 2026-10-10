package com.autoball.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.autoball.AB
import com.autoball.core.util.Display
import com.autoball.service.AutoBallAccessibilityService
import com.autoball.service.ShizukuClient

/**
 * 我的页：**只做分类入口**。
 *
 * 原先二十多项全堆在这一页，找一项要滚很久。现在按语义分成四类，
 * 每一类是一个按钮，点进去才是具体条目（见 [MineSections]）：
 * 执行授权 / 悬浮与显示 / 数据与日志 / 关于与合规。
 *
 * 顶部保留两处高频操作：齿轮（设置）与主题切换。
 */
class MinePage(context: Context, private val host: PageHost) : FrameLayout(context) {

    private val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(topbar())

        val sc = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        sc.addView(box, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(sc, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT))
        rebuild()
    }

    private fun rebuild() {
        box.removeAllViews()
        box.setPadding(Display.dpInt(context, 16f), 0,
            Display.dpInt(context, 16f), Display.dpInt(context, 96f))

        // ---- 用户信息头 ----
        box.addView(Kit.card(context).apply {
            val head = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(Kit.iconBox(context, "A", Theme.pri()))
            head.addView(Kit.twoLine(context, "AutoBall",
                "本地运行 · 不联网 · 不统计"), LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Display.dpInt(context, 12f)
            })
            addView(head)
            // 授权状态一览：不用进二级页就能看到三条通道是否就绪
            addView(TextView(context).apply {
                text = statusLine()
                textSize = 11f
                setTextColor(Theme.textSec())
                setLineSpacing(Display.dp(context, 2f), 1.5f)
                setPadding(0, Display.dpInt(context, 10f), 0, 0)
            })
        })

        // ---- 四个分类入口 ----
        box.addView(Kit.groupHead(context, "设置"))
        val g = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        g.addView(Kit.valueRow(context, "执行授权",
            "无障碍 · Shizuku · 悬浮窗 · 后台保活",
            "⛨", Theme.ok()) { host.openSubPage("perm") })
        g.addView(Kit.valueRow(context, "悬浮与显示",
            "主题 · 悬浮球 · 悬浮窗",
            "◉", Theme.pri()) { host.openSubPage("disp") })
        g.addView(Kit.valueRow(context, "数据与日志",
            "分组 · 导入导出 · 运行日志",
            "▤", Theme.pri2()) { host.openSubPage("data") })
        g.addView(Kit.valueRow(context, "关于与合规",
            "版本 · 更新日志 · 崩溃日志",
            "ⓘ", Theme.warn()) { host.openSubPage("about") })
        box.addView(Kit.settingCard(context, g))

        box.addView(Kit.note(context,
            context.getString(com.autoball.R.string.compliance_notice)))
    }

    /** 授权状态一行：三条通道是否就绪 */
    private fun statusLine(): String {
        val a11y = Display.accessibilityEnabled(context)
        val shz = ShizukuClient.instance.isInstalled() &&
                ShizukuClient.instance.isAuthorized()
        val ov = Display.canDrawOverlay(context)
        val n = listOf(a11y, shz, ov).count { it }
        return "无障碍 ${dot(a11y)}　Shizuku ${dot(shz)}　悬浮窗 ${dot(ov)}\n" +
            "当前 $n / 3 项就绪" +
            if (n == 0) "（至少开启一项才能运行脚本）" else ""
    }

    private fun dot(on: Boolean): String = if (on) "✓" else "✗"

    /** 顶栏：标题 + 齿轮（设置）+ 主题切换 */
    private fun topbar(): LinearLayout {
        val b = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Display.dpInt(context, 18f), Display.dpInt(context, 6f),
                Display.dpInt(context, 18f), Display.dpInt(context, 12f))
            gravity = Gravity.BOTTOM
        }
        val l = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        l.addView(TextView(context).apply {
            text = "我的"
            textSize = 26f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.textPri())
            includeFontPadding = false
        })
        l.addView(TextView(context).apply {
            text = if (Theme.isDark()) "深色主题" else "浅色主题"
            textSize = 12f
            setTextColor(Theme.textSec())
            setPadding(0, Display.dpInt(context, 3f), 0, 0)
        })
        b.addView(l, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        b.addView(TextView(context).apply {
            text = "⚙"
            textSize = 17f
            setTextColor(Theme.textSec())
            gravity = Gravity.CENTER
            background = Theme.rect(Theme.surface(), 12f, context, Theme.line())
            val sz = Display.dpInt(context, 36f)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginEnd = Display.dpInt(context, 6f)
            }
            setOnClickListener { host.openSubPage("set") }
        })
        b.addView(TextView(context).apply {
            text = if (Theme.isDark()) "☾" else "☀"
            textSize = 17f
            setTextColor(if (Theme.isDark()) Theme.accent2()
            else Theme.accent2())
            gravity = Gravity.CENTER
            background = Theme.rect(Theme.surface(), 12f, context, Theme.line())
            val sz = Display.dpInt(context, 36f)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setOnClickListener {
                animate().rotationBy(-90f).setDuration(320).start()
                Theme.toggleDark()
                host.refreshAll()
            }
        })
        return b
    }
}
