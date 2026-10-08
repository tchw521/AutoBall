package com.autoball.core.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.core.util.Display

/**
 * 录制采集层。
 *
 * 设计取舍（研究报告 4）：使用**可移动的小区域采集窗**而不是全屏拦截层。
 * Android 12+ 会阻止应用以"不可信遮挡"方式消费触摸，全屏透明层既可能被判为遮挡，
 * 也会与手势导航边缘区、输入法冲突。窗口内由 AutoBall 消费并补发，窗口外直接穿透到目标应用。
 *
 * 已知限制：录制时请避开屏幕边缘起手（系统会抢手势区）。
 */
class RecordCaptureView(
    context: Context,
    private val controller: RecordController
) : FrameLayout(context) {

    private val samples = ArrayList<GestureCompiler.Sample>()
    private val pointerMap = HashMap<Int, ArrayList<GestureCompiler.Sample>>()
    private var downT = 0L
    private var activePointer = -1
    private var multi = false

    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragging = false
    var windowParams: WindowManager.LayoutParams? = null
    var windowManager: WindowManager? = null

    @Volatile
    var active: Boolean = true

    init {
        // 采集层**不带任何可见控件**。
        //
        // 早前这里挂了一条 chip 控制条（暂停/撤销/等待1s/停止），与录制小窗
        // 叠在一起：既挡住目标应用，也互相挡住按钮。录制控制已并入
        // FloatWorkWindow 的录制控制条，采集层只负责接管触摸。
        setBackgroundColor(Color.parseColor("#08000000"))
    }

    private fun reset() {
        samples.clear()
        pointerMap.clear()
        activePointer = -1
        multi = false
    }

    companion object {
        /** 采集窗尺寸：屏幕的 86% × 72%，居中偏下，避开手势导航边缘 */
        fun createParams(context: Context): WindowManager.LayoutParams {
            val p = Display.screenSize(context)
            val type = if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            return WindowManager.LayoutParams(
                (p.x * 0.86f).toInt(), (p.y * 0.72f).toInt(),
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (p.x * 0.07f).toInt()
                y = (p.y * 0.20f).toInt()
            }
        }
    }
}

/** 采集层的窗口管理 */
object RecordOverlay {

    @Volatile
    private var view: RecordCaptureView? = null
    @Volatile
    private var wm: WindowManager? = null

    fun show(context: Context, controller: RecordController) {
        hide()
        val ctx = context.applicationContext
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = RecordCaptureView(ctx, controller)
        val p = RecordCaptureView.createParams(ctx)
        v.windowParams = p
        v.windowManager = manager
        runCatching { manager.addView(v, p) }
        view = v
        wm = manager
    }

    fun hide() {
        val v = view ?: return
        runCatching { wm?.removeView(v) }
        view = null
        wm = null
    }

    fun setActive(active: Boolean) { view?.active = active }
}
