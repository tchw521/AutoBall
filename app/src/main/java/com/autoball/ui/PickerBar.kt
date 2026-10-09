package com.autoball.ui

import android.view.View

/**
 * **拾取层底部条统一透明度**（CoordPicker 单点 / CoordPicker 滑动 / RegionPicker 区域）。
 *
 * 触发 R-001 三次法则：三处都在做同一件事（让底栏别挡住要取的点），
 * 各写一遍就会出现三种不同的透明度，改的时候还会漏。
 *
 * 为什么**动态**而不只是固定半透明：
 * 遮挡只在"正在调节"的那一刻造成困扰——手指落在屏幕下方时，
 * 底栏正好压着要看的位置。所以：
 * - 静止：0.72，能看清按钮文案（纯半透明到 0.4 以下连字都糊了）
 * - 拖动中：0.22，几乎透视，能看清底下的落点
 *
 * 只改 View 的 alpha（不动画背景色），因此文字与按钮同步变淡，
 * 不会出现"背景透了、文字还实着"的割裂感。
 */
object PickerBar {

    /** 静止时：仍可辨识按钮，但已能看出下面是屏幕 */
    const val ALPHA_IDLE = 0.72f

    /** 拖动/调节中：几乎透视，落点清晰可见 */
    const val ALPHA_DRAG = 0.22f

    private const val DURATION = 120L

    /** 建好底栏后调用：设置静止透明度 */
    fun attach(bar: View) {
        bar.alpha = ALPHA_IDLE
    }

    /**
     * 调节状态切换。
     *
     * 用 [View.animate] 做了去抖：拖动中 MOVE 会连续触发几十次，
     * 若每次都重新起一个动画，会互相打断导致闪烁。这里只在状态**真的变了**才动。
     */
    fun setDragging(bar: View, dragging: Boolean) {
        val target = if (dragging) ALPHA_DRAG else ALPHA_IDLE
        // **必须用单参 setTag（View.tag），不能用 setTag(int key, obj)**：
        // 带 key 的版本要求 key 是**资源 id**，传任意整数在部分 ROM 上会抛
        // `IllegalArgumentException: The key must be an application-specific resource id`
        // （本项目在 HONOR / Android 16 上真实崩溃过一次）。
        if ((bar.tag as? Float) == target) return
        bar.tag = target
        bar.animate().alpha(target).setDuration(DURATION).start()
    }
}
