package com.autoball.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.autoball.AB
import com.autoball.core.util.Display

/**
 * 设计令牌与主题（对齐 UI 设计方案 v3）。
 *
 * v3 要点：
 * - 统一层级栈（遮罩 50 / 菜单 62 / 全屏选点 94 / 弹窗 96 / 二级弹窗 97 / 弹窗内下拉 100 / 气泡 105 / Toast 120）
 * - 统一渐变方向 135°、圆角 16、毛玻璃强度、色板（g1..g7）
 * - 深浅双主题，切换开关只在「我的」页
 *
 * 所有"图标"一律用纯色 shape 绘制，不引入图标资源——对包体零负担。
 */
object Theme {

    const val KEY_DARK = "theme_dark"

    fun isDark(): Boolean = AB.store.getBool(KEY_DARK, true)

    fun setDark(v: Boolean) { AB.store.putBool(KEY_DARK, v) }

    // ---------- 层级栈（R1/R5：避免弹窗互相压住）----------
    const val Z_MASK = 50
    const val Z_MENU = 62
    const val Z_FS = 94      // 全屏选点 / 区域选择
    const val Z_DIALOG = 96
    const val Z_SUB = 97     // 二级弹窗（坐标编辑等）
    const val Z_POP = 100    // 弹窗内下拉菜单
    const val Z_TIP = 105    // 弹窗内气泡 / 变量提示框
    const val Z_TOAST = 120  // Toast 永远最上

    // ---------- 渐变方向 ----------
    /** 135°（左上→右下），GradientDrawable 用 TL_BR 近似 */
    fun orientation(): GradientDrawable.Orientation = GradientDrawable.Orientation.TL_BR

    // ---------- 深色 ----------
    const val D_BG0 = "#0B0913"
    const val D_BG1 = "#141022"
    const val D_SURFACE = "#1B1730"
    const val D_SURFACE2 = "#221C3A"
    const val D_LINE = "#12FFFFFF"
    const val D_LINE2 = "#21FFFFFF"
    const val D_TX = "#F4F2FF"
    const val D_TX2 = "#9A92BD"
    const val D_TX3 = "#6B6390"

    // ---------- 浅色 ----------
    const val L_BG0 = "#F4F5F9"
    const val L_BG1 = "#FFFFFF"
    const val L_SURFACE = "#FFFFFF"
    const val L_SURFACE2 = "#F7F8FC"
    const val L_LINE = "#12110C2E"
    const val L_LINE2 = "#1F110C2E"
    const val L_TX = "#16121F"
    const val L_TX2 = "#6B6488"
    const val L_TX3 = "#9A93B5"

    // ---------- 主色与语义色（两主题共用）----------
    const val PRI = "#7C3AED"      // 紫
    const val PRI2 = "#2F6BFF"     // 蓝
    const val OK = "#12B76A"
    const val WARN = "#F79009"
    const val DANGER = "#E5484D"
    const val BLUE = "#2F6BFF"
    const val PURPLE = "#7C3AED"

    /** 悬浮球 / FAB 天蓝渐变（v3：155deg #F4FBFF → #C6ECFD → #79D5F7 → #2FA2DA） */
    val FAB_STOPS = intArrayOf(
        Color.parseColor("#F4FBFF"),
        Color.parseColor("#C6ECFD"),
        Color.parseColor("#79D5F7"),
        Color.parseColor("#2FA2DA")
    )
    const val FAB_STROKE = "#BFFFFFFF"
    const val FAB_GLOW = "#2C0EA5E9"
    const val FAB_ICON = "#0E7FB8"

    /** 分组气泡色板（零图标，纯色值）g1..g7 */
    val GROUP_COLORS = intArrayOf(
        Color.parseColor("#7C3AED"), Color.parseColor("#2F6BFF"),
        Color.parseColor("#12B76A"), Color.parseColor("#F79009"),
        Color.parseColor("#EC4899"), Color.parseColor("#6B7280"),
        Color.parseColor("#0EA5E9")
    )

    /** 分组气泡的浅底 / 深字（v3 浅色主题用 10% 底 + 主色字） */
    fun groupTint(i: Int): Int {
        val c = GROUP_COLORS[i % GROUP_COLORS.size]
        return Color.argb(if (isDark()) 42 else 26, Color.red(c), Color.green(c), Color.blue(c))
    }

    fun groupInk(i: Int): Int {
        val c = GROUP_COLORS[i % GROUP_COLORS.size]
        return if (isDark()) lighten(c) else c
    }

    private fun lighten(c: Int): Int {
        val r = (Color.red(c) + (255 - Color.red(c)) * 0.35f).toInt()
        val g = (Color.green(c) + (255 - Color.green(c)) * 0.35f).toInt()
        val b = (Color.blue(c) + (255 - Color.blue(c)) * 0.35f).toInt()
        return Color.rgb(r, g, b)
    }

    // ---------- 取色 ----------
    fun bg0(): Int = c(if (isDark()) D_BG0 else L_BG0)
    fun bg1(): Int = c(if (isDark()) D_BG1 else L_BG1)
    fun surface(): Int = c(if (isDark()) D_SURFACE else L_SURFACE)
    fun surface2(): Int = c(if (isDark()) D_SURFACE2 else L_SURFACE2)
    fun line(): Int = c(if (isDark()) D_LINE else L_LINE)
    fun line2(): Int = c(if (isDark()) D_LINE2 else L_LINE2)
    fun textPri(): Int = c(if (isDark()) D_TX else L_TX)
    fun textSec(): Int = c(if (isDark()) D_TX2 else L_TX2)
    fun textTer(): Int = c(if (isDark()) D_TX3 else L_TX3)
    fun pri(): Int = c(PRI)
    fun pri2(): Int = c(PRI2)
    fun ok(): Int = c(OK)
    fun warn(): Int = c(WARN)
    fun danger(): Int = c(DANGER)

    /** 兼容旧调用：卡片底色 */
    fun card(): Int = surface()
    fun bg(): Int = bg0()

    private fun c(hex: String): Int = Color.parseColor(hex)

    /** 主色渐变（135° 蓝→紫） */
    fun gradStops(): IntArray = intArrayOf(c(PRI2), c(PRI))

    // ---------- Shape ----------

    /** 卡片/气泡通用圆角背景 */
    fun bubble(ctx: Context, color: Int, radiusDp: Float, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = Display.dp(ctx, radiusDp)
            setColor(color)
            if (stroke != 0) setStroke(1, stroke)
        }

    /** 胶囊：圆角取半高，调用方给定高度换算 */
    fun capsule(ctx: Context, color: Int, radiusDp: Float): GradientDrawable =
        bubble(ctx, color, radiusDp)

    fun bubbleRound(ctx: Context, color: Int): GradientDrawable =
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    /** 主色渐变 135° */
    fun gradient(ctx: Context, radiusDp: Float): GradientDrawable =
        GradientDrawable(orientation(), gradStops()).apply {
            cornerRadius = Display.dp(ctx, radiusDp)
        }

    /** FAB 天蓝渐变（155° 用 LEFT_RIGHT 由上到下近似，v3 主视觉） */
    fun fabGradient(): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, FAB_STOPS).apply {
            shape = GradientDrawable.OVAL
        }

    /** 弹窗底：竖向三段渐变（v3 gdlg） */
    fun dialogBg(strokeColor: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            if (isDark()) intArrayOf(c("#241F3E"), c("#1E1A34"), c("#191528"))
            else intArrayOf(c("#FFFFFF"), c("#FCFCFE"), c("#F5F6FA"))
        ).apply { setStroke(1, strokeColor) }

    /** 分离线（1px） */
    fun hairline(ctx: Context): android.view.View = android.view.View(ctx).apply {
        setBackgroundColor(line())
    }
}
