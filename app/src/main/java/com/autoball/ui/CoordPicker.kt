package com.autoball.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.autoball.core.util.Display

/**
 * 坐标拾取：全屏透明层显示十字准星，点击屏幕取回绝对像素坐标。
 *
 * 已知限制（需求六）：坐标为绝对像素，换机型/转屏会点偏，归一化规划在 v0.8。
 */
object CoordPicker {

    @Volatile
    private var view: PickerView? = null
    @Volatile
    private var wm: WindowManager? = null

    fun pick(context: Context, onPicked: (Float, Float) -> Unit) {
        if (!Display.canDrawOverlay(context)) {
            Display.openOverlaySettings(context)
            return
        }
        close()
        val ctx = context.applicationContext
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = PickerView(ctx) { x, y ->
            onPicked(x, y)
            close()
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        runCatching { manager.addView(v, p) }
        view = v
        wm = manager
    }

    fun close() {
        val v = view ?: return
        runCatching { wm?.removeView(v) }
        view = null
        wm = null
    }

    private class PickerView(context: Context, private val onPicked: (Float, Float) -> Unit) :
        View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF5B6E")
            strokeWidth = Display.dp(context, 1.5f)
            style = Paint.Style.STROKE
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = Display.dp(context, 12f)
        }

        @Volatile
        private var px = -1f
        @Volatile
        private var py = -1f

        init {
            setBackgroundColor(Color.parseColor("#33000000"))
            setWillNotDraw(false)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (px < 0) return
            canvas.drawLine(px, 0f, px, height.toFloat(), paint)
            canvas.drawLine(0f, py, width.toFloat(), py, paint)
            canvas.drawCircle(px, py, Display.dp(context, 14f), paint)
            canvas.drawText("${px.toInt()} , ${py.toInt()}",
                px + Display.dp(context, 18f), py - Display.dp(context, 8f), textPaint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    px = event.rawX; py = event.rawY
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    onPicked(event.rawX, event.rawY)
                    return true
                }
                MotionEvent.ACTION_DOWN -> {
                    px = event.rawX; py = event.rawY
                    invalidate()
                    return true
                }
            }
            return true
        }
    }

    /** 提示文案，用于表单「?」帮助 */
    fun helpText(field: String): String = when (field) {
        "点击位置" -> "屏幕绝对像素坐标，可用「拾取」按钮在屏幕上点选。换机型或转屏会偏移（v0.8 起支持归一化）。"
        "按下时间" -> "按下到抬起的时长，单位毫秒。≥350ms 会被识别为长按。"
        "滑动时长" -> "滑动过程持续时间，越短越快。建议 300ms 左右。"
        "文本内容" -> "输入到当前焦点输入框的文本；无障碍后端要求输入框已获得焦点。"
        "目标应用" -> "应用包名，例如 com.android.settings。"
        "运行等待" -> "本动作执行后等待的时间，用于等待界面响应。"
        "重复次数" -> "本动作连续执行的次数。"
        "运行条件" -> "可选。例如 \$count > 3，条件不满足时跳过该动作。"
        else -> "该字段用于配置动作参数。"
    }

    fun helpView(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 11f
        setTextColor(Theme.textSec())
        setPadding(0, 0, 0, Display.dpInt(context, 6f))
    }
}
