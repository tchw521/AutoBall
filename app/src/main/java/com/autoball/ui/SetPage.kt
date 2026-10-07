package com.autoball.ui

import android.content.Context
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.autoball.AB
import com.autoball.core.engine.JsEngines
import com.autoball.core.util.Display

/**
 * 设置页（v3 #p-set）：分组卡 + 行列表。
 *
 * 全部行与卡片走 Kit 统一组件，本文件只负责分组与数据。
 */
class SetPage(context: Context, private val host: PageHost) : FrameLayout(context) {

    private val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        val root = Kit.root(context)
        root.addView(Kit.topbar(context, "设置", "",
            listOf(Kit.themeBtn(context) { host.toggleTheme() })).apply {
            // 设置页顶栏左侧带返回按钮
            setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 6f),
                Display.dpInt(context, 18f), Display.dpInt(context, 12f))
            addView(Kit.backBtn(context) { host.showPage(4) }, 0)
        })
        val sc = android.widget.ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        wrap.setPadding(0, 0, 0, Display.dpInt(context, 92f))
        sc.addView(wrap)
        root.addView(sc, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        render()
    }

    private fun render() {
        wrap.removeAllViews()

        // ---- 通用 ----
        wrap.addView(Kit.groupHead(context, "通用"))
        val g1 = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        g1.addView(Kit.switchRow(context, "深色主题", "跟随设计稿双主题",
            "◐", Theme.pri(), Theme.isDark()) { host.toggleTheme() })
        g1.addView(Kit.valueRow(context, "悬浮设置", "悬浮球 / 悬浮窗 / 手势",
            "◉", Theme.pri2()) { host.openSubPage("float") })
        g1.addView(Kit.valueRow(context, "运行日志", "查看每一步的执行结果",
            "≡", Theme.ok()) { host.openSubPage("log") })
        g1.addView(Kit.valueRow(context, "JS 脚本", "编写与调试脚本",
            "{", Theme.warn()) { host.openSubPage("js") })
        wrap.addView(Kit.settingCard(context, g1))

        // ---- 执行 ----
        wrap.addView(Kit.groupHead(context, "执行"))
        val g2 = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val eng = JsEngines.engineName()
        g2.addView(Kit.valueRow(context, "脚本引擎",
            eng + if (eng == "quickjs") "（原生）" else "（纯 Java）",
            "⚙", Theme.pri2(), eng) {})
        g2.addView(Kit.switchRow(context, "运行前体检", "缺少能力时提前提示",
            "✓", Theme.ok(), AB.store.getBool("preflight", true)) {
            AB.store.putBool("preflight", it)
        })
        g2.addView(Kit.switchRow(context, "失败自动切换通道",
            "无障碍与 Shizuku 之间自动回退", "⇄", Theme.warn(),
            AB.store.getBool("auto_fallback", true)) {
            AB.store.putBool("auto_fallback", it)
        })
        wrap.addView(Kit.settingCard(context, g2))

        // ---- 坐标 ----
        wrap.addView(Kit.groupHead(context, "坐标"))
        val g3 = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        g3.addView(Kit.valueRow(context, "坐标基准", "百分比（换机型不偏移）",
            "◎", Theme.pri(), "百分比") {})
        g3.addView(Kit.switchRow(context, "转屏自动适配", "旋转后按新宽高换算",
            "⟳", Theme.pri2(), AB.store.getBool("auto_rotate", true)) {
            AB.store.putBool("auto_rotate", it)
        })
        wrap.addView(Kit.settingCard(context, g3))

        wrap.addView(Kit.tip(context,
            "所有设置立即生效并本地保存，不会上传。"))
    }
}
