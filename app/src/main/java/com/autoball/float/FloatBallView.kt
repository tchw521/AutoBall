package com.autoball.float

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.autoball.core.util.Display
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 悬浮球：单球形态，手势盲操驱动脚本。
 *
 * 手势判定：
 * - 单击 / 双击 / 三击：分别绑定不同脚本槽位，判定窗口 300ms；
 * - 长按 ≥350ms：弹出脚本列表；
 * - 拖动位移 >10dp：立即取消一切点击判定（消除拖动误触发）；
 * - 拖到屏幕底部中间区域松手：关闭悬浮球。
 */
class FloatBallView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onSingleTap()
        fun onDoubleTap()
        fun onTripleTap()
        fun onLongPress()
        fun onMoveBy(dx: Int, dy: Int)
        fun onDragStart()
        fun onDragEnd()
        fun onDragOverCloseZone(inZone: Boolean)
        fun onClose()
    }

    companion object {
        /** 长按阈值（需求 2.4：≥350ms） */
        const val LONG_PRESS_MS = 350L
        /** 多击判定窗口（需求：双击间隔 ≤300ms） */
        const val MULTI_TAP_WINDOW_MS = 300L
        /** 拖动取消点击的位移阈值（需求：>10dp） */
        const val DRAG_CANCEL_DP = 10f
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x66FFFFFF
    }

    @Volatile
    var running: Boolean = false
        set(value) { field = value; postInvalidate() }

    @Volatile
    var idleAlpha: Float = 0.72f
        set(value) { field = value; postInvalidate() }

    @Volatile
    var inCloseZone: Boolean = false
        set(value) { field = value; postInvalidate() }

    private var ballSizeDp = 48f
    private val handler = Handler(Looper.getMainLooper())
    private val slopPx: Float = Display.dp(context, DRAG_CANCEL_DP)

    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var moved = false
    private var tapCount = 0
    private var longFired = false

    private val longPressRunnable = Runnable {
        longFired = true
        tapCount = 0
        listener.onLongPress()
    }

    private val tapRunnable = Runnable {
        val n = tapCount
        tapCount = 0
        when (n) {
            1 -> listener.onSingleTap()
            2 -> listener.onDoubleTap()
            else -> listener.onTripleTap()
        }
    }

    fun setSizeDp(v: Float) { ballSizeDp = v; requestLayout(); postInvalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = Display.dpInt(context, ballSizeDp)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = (if (w < h) w else h) / 2f - Display.dp(context, 2f)
        val cx = w / 2f; val cy = h / 2f

        val colors = if (inCloseZone) {
            intArrayOf(0xFFFF8A9B.toInt(), 0xFFFF5B6E.toInt(), 0xFFE23E52.toInt())
        } else if (running) {
            intArrayOf(0xFF9BF6C4.toInt(), 0xFF35D08A.toInt(), 0xFF1FAF70.toInt())
        } else {
            intArrayOf(0xFF8FDBFF.toInt(), 0xFF4A9EFF.toInt(), 0xFF5B7CFF.toInt())
        }
        paint.shader = LinearGradient(0f, 0f, w, h, colors, null, Shader.TileMode.CLAMP)
        paint.alpha = if (running) 0xFF else (idleAlpha * 255).toInt().coerceIn(80, 255)
        canvas.drawCircle(cx, cy, r, paint)

        highlight.strokeWidth = Display.dp(context, 1.5f)
        canvas.drawCircle(cx, cy, r - Display.dp(context, 1f), highlight)

        if (inCloseZone) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt(); strokeWidth = Display.dp(context, 2.5f); style = Paint.Style.STROKE
            }
            val d = r * 0.35f
            canvas.drawLine(cx - d, cy - d, cx + d, cy + d, p)
            canvas.drawLine(cx + d, cy - d, cx - d, cy + d, p)
        }
        paint.shader = null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX; downRawY = event.rawY
                lastRawX = event.rawX; lastRawY = event.rawY
                moved = false
                longFired = false
                handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastRawX
                val dy = event.rawY - lastRawY
                val dist = hypot(event.rawX - downRawX, event.rawY - downRawY)
                if (!moved && dist > slopPx) {
                    moved = true
                    tapCount = 0
                    handler.removeCallbacks(longPressRunnable)
                    listener.onDragStart()
                }
                if (moved) {
                    listener.onMoveBy(dx.toInt(), dy.toInt())
                    val inZone = isInCloseZone(event.rawX, event.rawY)
                    if (inZone != inCloseZone) {
                        inCloseZone = inZone
                        listener.onDragOverCloseZone(inZone)
                    }
                }
                lastRawX = event.rawX; lastRawY = event.rawY
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    inCloseZone = false
                    return true
                }
                if (moved) {
                    if (isInCloseZone(event.rawX, event.rawY)) listener.onClose() else listener.onDragEnd()
                    inCloseZone = false
                    return true
                }
                if (longFired) return true
                // 未达长按阈值即抬起，计入多击序列
                tapCount++
                handler.removeCallbacks(tapRunnable)
                handler.postDelayed(tapRunnable, MULTI_TAP_WINDOW_MS)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun isInCloseZone(rawX: Float, rawY: Float): Boolean {
        val p: Point = Display.screenSize(context)
        val zoneH = Display.dp(context, 120f)
        val zoneW = Display.dp(context, 160f)
        val cx = p.x / 2f
        return rawY > p.y - zoneH && abs(rawX - cx) < zoneW / 2f
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}
