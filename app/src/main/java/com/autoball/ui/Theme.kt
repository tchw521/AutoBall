package com.autoball.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.autoball.AB
import com.autoball.core.util.Display

/**
 * 设计令牌 —— 一比一对齐 UI 设计方案 v3 的 CSS 变量。
 *
 * 深色：
 *   --bg0:#0B0913  --bg1:#141022  --surface:#1B1730  --surface2:#221C3A
 *   --line:rgba(255,255,255,.07)  --line2:rgba(255,255,255,.13)
 *   --tx:#F4F2FF  --tx2:#9A92BD  --tx3:#6B6390
 *   --pri:#7C3AED  --pri2:#2F6BFF  --ok:#12B76A  --warn:#F79009
 *   --radius:16px  --shadow:0 8px 28px rgba(0,0,0,.4)
 * 浅色：
 *   --bg0:#F4F5F9  --bg1:#FFFFFF  --surface:#FFFFFF  --surface2:#F7F8FC
 *   --tx:#16121F  --tx2:#6B6488  --tx3:#9A93B5
 *   --shadow:0 6px 20px rgba(23,15,73,.10)
 *
 * 渐变方向 --gd:135deg；毛玻璃 blur(22px) saturate(150%)。
 */
object Theme {

    const val KEY_DARK = "theme_dark"

    fun isDark(): Boolean = AB.store.getBool(KEY_DARK, true)
    fun setDark(v: Boolean) { AB.store.putBool(KEY_DARK, v) }
    fun toggleDark() { setDark(!isDark()) }

    // ---------- 层级栈（R1/R5）----------
    const val Z_MASK = 50
    const val Z_MENU = 62
    const val Z_FS = 94
    const val Z_DIALOG = 96
    const val Z_SUB = 97
    const val Z_POP = 100
    const val Z_TIP = 105
    const val Z_TOAST = 120

    // ---------- 尺寸（v3 标称值）----------
    const val RADIUS = 16f            // --radius
    const val CARD_PAD = 12f
    const val CARD_GAP = 11f
    const val CARD_MB = 10f
    const val BADGE = 40f             // .badge 40x40 / radius 12
    const val BADGE_R = 12f
    const val RUN = 38f               // .run 圆形运行按钮
    const val GROUPBAR_W = 100f       // 左分组栏宽度
    const val BUB_MAX = 74f           // 气泡小框最大宽度
    const val TABBAR_H = 64f          // 底部导航高
    const val TABBAR_R = 32f          // 圆角（呼吸 32↔34）
    const val TABBAR_LR = 12f         // 左右边距
    const val TABBAR_B = 14f          // 离底
    const val FAB = 56f               // 中央四角星直径
    const val FAB_TOP = -18f          // 凸起于导航栏
    const val FABSLOT = 68f           // 占位槽宽
    const val SHEET_H = 25f           // 底部半框：占屏 25%
    const val SHEET_MIN = 186f
    const val SHEET_R = 26f
    const val ROW_PAD = 13f           // .row padding 13x14 / radius 14 / mb 9
    const val ROW_R = 14f
    const val ROW_MB = 9f
    const val SW_W = 42f              // .sw 42x24，滑块 18 @3
    const val SW_H = 24f
    const val SW_KNOB = 18f
    const val SEC_PAD_T = 14f
    const val SEC_PAD_B = 8f
    const val TOAST_B = 100f
    const val MULTI_B = 96f
    const val BTN_H = 44f             // .btn 高 / radius 13
    const val BTN_R = 13f
    const val DIALOG_W = 206f         // .adlg 添加动作弹窗宽
    const val DIALOG_R = 18f
    const val GDLG_R = 18f

    // ---------- 色板 ----------
    const val D_BG0 = "#0B0913"
    const val D_BG1 = "#141022"
    const val D_SURFACE = "#1B1730"
    const val D_SURFACE2 = "#221C3A"
    const val D_LINE = "#12FFFFFF"
    const val D_LINE2 = "#21FFFFFF"
    const val D_TX = "#F4F2FF"
    const val D_TX2 = "#9A92BD"
    const val D_TX3 = "#6B6390"

    const val L_BG0 = "#F4F5F9"
    const val L_BG1 = "#FFFFFF"
    const val L_SURFACE = "#FFFFFF"
    const val L_SURFACE2 = "#F7F8FC"
    const val L_LINE = "#12110C2E"
    const val L_LINE2 = "#1F110C2E"
    const val L_TX = "#16121F"
    const val L_TX2 = "#6B6488"
    const val L_TX3 = "#9A93B5"

    const val PRI = "#7C3AED"
    const val PRI2 = "#2F6BFF"
    const val OK = "#12B76A"
    const val WARN = "#F79009"
    const val DANGER = "#E5484D"

    /** 分组气泡 7 色（g1..g7）：深色底 16%/45% 边，浅色底 10% + 主色字 */
    val G = intArrayOf(
        Color.parseColor("#7C3AED"), Color.parseColor("#2F6BFF"),
        Color.parseColor("#12B76A"), Color.parseColor("#F79009"),
        Color.parseColor("#EC4899"), Color.parseColor("#0EA5E9"),
        Color.parseColor("#6B7280")
    )
    /** 深色下气泡文字色（.bub.cN 的 color） */
    val G_INK_D = intArrayOf(
        Color.parseColor("#B794F6"), Color.parseColor("#7EA6FF"),
        Color.parseColor("#4ADE80"), Color.parseColor("#FBBF24"),
        Color.parseColor("#F472B6"), Color.parseColor("#38BDF8"),
        Color.parseColor("#9CA3AF")
    )
    /** 浅色下气泡文字色 */
    val G_INK_L = intArrayOf(
        Color.parseColor("#7C3AED"), Color.parseColor("#2563EB"),
        Color.parseColor("#059669"), Color.parseColor("#D97706"),
        Color.parseColor("#DB2777"), Color.parseColor("#0284C7"),
        Color.parseColor("#6B7280")
    )

    fun gInk(i: Int): Int {
        val k = i % G.size
        return if (isDark()) G_INK_D[k] else G_INK_L[k]
    }

    fun gTint(i: Int): Int {
        val c = G[i % G.size]
        return Color.argb(if (isDark()) 41 else 26, Color.red(c), Color.green(c), Color.blue(c))
    }

    fun gEdge(i: Int): Int {
        val c = G[i % G.size]
        return Color.argb(115, Color.red(c), Color.green(c), Color.blue(c))
    }

    /** FAB 天蓝渐变（155deg #F4FBFF → #C6ECFD → #79D5F7 → #2FA2DA） */
    val FAB_STOPS = intArrayOf(
        Color.parseColor("#F4FBFF"),
        Color.parseColor("#C6ECFD"),
        Color.parseColor("#79D5F7"),
        Color.parseColor("#2FA2DA")
    )
    const val FAB_STROKE = "#BFFFFFFF"
    const val FAB_GLOW = "#7DD3FC"

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

    /** 兼容旧调用 */
    fun card(): Int = surface()
    fun bg(): Int = bg0()
    fun purple(): Int = pri()

    private fun c(hex: String): Int = Color.parseColor(hex)

    /** 主色渐变 135°（--grad） */
    fun gradStops(): IntArray = intArrayOf(c(PRI2), c(PRI))
    fun orientation(): GradientDrawable.Orientation = GradientDrawable.Orientation.TL_BR

    // ---------- Shape ----------

    fun rect(color: Int, rDp: Float, ctx: Context, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = Display.dp(ctx, rDp)
            setColor(color)
            if (stroke != 0) setStroke(1, stroke)
        }

    /** 卡片：--radius 16 + --shadow */
    fun cardBg(ctx: Context): GradientDrawable = rect(surface(), RADIUS, ctx, line())

    fun oval(color: Int): GradientDrawable =
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    fun grad(ctx: Context, rDp: Float): GradientDrawable =
        GradientDrawable(orientation(), gradStops()).apply {
            cornerRadius = Display.dp(ctx, rDp)
        }

    fun gradOval(): GradientDrawable =
        GradientDrawable(orientation(), gradStops()).apply { shape = GradientDrawable.OVAL }

    /** FAB 天蓝渐变球（155deg 近似为自上而下） */
    fun fab(): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, FAB_STOPS).apply {
            shape = GradientDrawable.OVAL
        }

    /** 弹窗底（v3 gdlg：竖向三段渐变） */
    fun dialogBg(): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            if (isDark()) intArrayOf(c("#241F3E"), c("#1E1A34"), c("#191528"))
            else intArrayOf(c("#FFFFFF"), c("#FCFCFE"), c("#F5F6FA"))
        ).apply { cornerRadius = 0f; setStroke(1, line()) }

    // ---------- 兼容别名（历史调用点）----------
    fun bubble(ctx: Context, color: Int, rDp: Float, stroke: Int = 0): GradientDrawable =
        rect(color, rDp, ctx, stroke)
    fun bubbleRound(ctx: Context, color: Int): GradientDrawable = oval(color)
    fun gradient(ctx: Context, rDp: Float): GradientDrawable = grad(ctx, rDp)
    fun fabGradient(): GradientDrawable = fab()
    const val BLUE = PRI2
    const val PURPLE = PRI
    val GROUP_COLORS: IntArray get() = G
    fun groupTint(i: Int): Int = gTint(i)
    fun groupInk(i: Int): Int = gInk(i)

    /** 虚线框（v3 .step.add / 空态）：细描边 + 低透明底 */
    fun dashed(ctx: Context, rDp: Float): android.graphics.drawable.GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = Display.dp(ctx, rDp)
            setColor(if (isDark()) Color.parseColor("#0DFFFFFF") else Color.parseColor("#05FFFFFF"))
            setStroke(Display.dpInt(ctx, 1.2f), line2())
        }

    /** 提示条（v3 .tip）：左侧 3px 主色竖条，其余圆角 */
    fun tipBg(ctx: Context): android.graphics.drawable.GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = Display.dp(ctx, 10f)
            setColor(if (isDark()) Color.parseColor("#1A7C3AED") else Color.parseColor("#0F7C3AED"))
        }

    fun hairline(ctx: Context): android.view.View = android.view.View(ctx).apply {
        setBackgroundColor(line())
    }
}
