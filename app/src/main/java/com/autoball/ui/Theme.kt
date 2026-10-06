package com.autoball.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.autoball.AB
import com.autoball.core.util.Display

/**
 * 主题与视觉常量（UI v9 定稿）。
 *
 * 深浅双主题，切换开关只在「我的」页（需求 3.6）。
 * 所有"图标"一律用纯色 shape 绘制，不引入图标资源——对包体零负担。
 */
object Theme {

    const val KEY_DARK = "theme_dark"

    fun isDark(): Boolean = AB.store.getBool(KEY_DARK, true)

    fun setDark(v: Boolean) { AB.store.putBool(KEY_DARK, v) }

    // 深色
    const val BG = "#12101F"
    const val BG_SOFT = "#1B1730"
    const val CARD = "#241F3E"
    const val TEXT_PRI = "#F2F3FF"
    const val TEXT_SEC = "#A7A9C6"
    const val GEL = "#47FFFFFF"   // 导航半透明底 rgba(28,24,50,.28)

    // 浅色
    const val L_BG = "#F5F6FA"
    const val L_CARD = "#FFFFFF"
    const val L_TEXT_PRI = "#1A1A2E"
    const val L_TEXT_SEC = "#6B6D85"
    const val L_GEL = "#8CFFFFFF"

    // 主色
    const val BLUE = "#2F6BFF"
    const val PURPLE = "#7C3AED"
    const val BALL_A = "#8FDBFF"
    const val BALL_B = "#4A9EFF"
    const val BALL_C = "#5B7CFF"

    /** 分组气泡色板（零图标，纯色值） */
    val GROUP_COLORS = intArrayOf(
        Color.parseColor("#4A9EFF"), Color.parseColor("#7C3AED"),
        Color.parseColor("#35D08A"), Color.parseColor("#FFB020"),
        Color.parseColor("#FF5B6E"), Color.parseColor("#22D3EE")
    )

    fun bg(): Int = if (isDark()) Color.parseColor(BG) else Color.parseColor(L_BG)
    fun card(): Int = if (isDark()) Color.parseColor(CARD) else Color.parseColor(L_CARD)
    fun textPri(): Int = if (isDark()) Color.parseColor(TEXT_PRI) else Color.parseColor(L_TEXT_PRI)
    fun textSec(): Int = if (isDark()) Color.parseColor(TEXT_SEC) else Color.parseColor(L_TEXT_SEC)
    fun gel(): Int = Color.parseColor(if (isDark()) GEL else L_GEL)

    /** 卡片/气泡通用圆角背景 */
    fun bubble(ctx: Context, color: Int, radiusDp: Float, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = Display.dp(ctx, radiusDp)
            setColor(color)
            if (stroke != 0) setStroke(1, stroke)
        }

    fun bubbleRound(ctx: Context, color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    /** 主色渐变（145°） */
    fun gradient(ctx: Context, radiusDp: Float): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor(BALL_A), Color.parseColor(BALL_B), Color.parseColor(BALL_C))
        ).apply { cornerRadius = Display.dp(ctx, radiusDp) }
}
