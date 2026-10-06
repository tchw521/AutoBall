package com.autoball.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.autoball.core.util.Display
import com.autoball.float.FloatManager

/**
 * 坐标拾取：把本应用收到后台、隐藏自身所有悬浮层，只留一个可拖动的小准星，
 * 用户把它拖到目标应用 / 桌面上的真实位置，点击即取回坐标。
 *
 * 与旧实现的区别：旧版用全屏透明层盖住整个屏幕，只能在本应用内取点，
 * 看不到也点不到其他应用；新版为准星浮标，不遮挡目标界面，可跨应用取真实坐标。
 *
 * 已知限制：坐标为绝对像素，换机型/转屏会偏移（v0.4 起动作流已支持自动缩放）。
 */
object CoordPicker {

    private const val STAR_DP = 68f

    @Volatile
    private var view: PickerView? = null
    @Volatile
    private var wm: WindowManager? = null
    @Volatile
    private var params: WindowManager.LayoutParams? = null
    @Volatile
    private var hostActivity: Activity? = null
    @Volatile
    private var wasBallShown = false

    private val handler = Handler(Looper.getMainLooper())

    /**
     * @param activity 调用方界面，拾取时会被收到后台以露出目标应用
     * @param onPicked 取回的是屏幕绝对像素坐标
     */
    fun pick(context: Context, activity: Activity?, onPicked: (Float, Float) -> Unit) {
        if (!Display.canDrawOverlay(context)) {
            Display.openOverlaySettings(context)
            return
        }
        close()
        val ctx = context.applicationContext
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 拾取期间隐藏本应用的一切遮挡：悬浮球、悬浮窗，以及界面本身
        wasBallShown = FloatManager.isBallShown()
        FloatManager.hideAll()
        hostActivity = activity
        handler.postDelayed({
            runCatching { activity?.moveTaskToBack(true) }
        }, 120)

        val size = Display.dpInt(ctx, STAR_DP)
        val sw = Display.screenSize(ctx)
        val p = WindowManager.LayoutParams(
            size, size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = (sw.x - size) / 2
        p.y = (sw.y - size) / 2

        val v = PickerView(ctx) { x, y ->
            onPicked(x, y)
            close()
        }
        v.updatePos(p.x + size / 2f, p.y + size / 2f)

        runCatching { manager.addView(v, p) }
        view = v
        wm = manager
        params = p
    }

    fun close() {
        handler.post {
            val v = view ?: return@post
            runCatching { wm?.removeView(v) }
            view = null
            wm = null
            params = null
            // 回到本应用并恢复悬浮球
            val act = hostActivity
            hostActivity = null
            if (act != null) {
                runCatching {
                    act.startActivity(Intent(act, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
            }
            if (wasBallShown) {
                val app = act ?: runCatching { com.autoball.App.get() }.getOrNull() ?: return@post
                FloatManager.showBall(app)
            }
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    /** 可拖动的十字准星浮标 */
    private class PickerView(context: Context, private val onPicked: (Float, Float) -> Unit) :
        View(context) {

        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC2F6BFF")
            style = Paint.Style.STROKE
            strokeWidth = Display.dp(context, 2f)
        }
        private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFF5B6E")
            style = Paint.Style.STROKE
            strokeWidth = Display.dp(context, 1.5f)
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFF5B6E")
            style = Paint.Style.FILL
        }
        private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E61C1832")
            style = Paint.Style.FILL
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = Display.dp(context, 11f)
            textAlign = Paint.Align.CENTER
        }

        @Volatile
        private var cx = 0f
        @Volatile
        private var cy = 0f

        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        init { setWillNotDraw(false) }

        fun updatePos(x: Float, y: Float) {
            cx = x; cy = y
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val c = w / 2f
            // 半透明底盘，保证在其他应用上也能看清
            canvas.drawRoundRect(0f, h - Display.dp(context, 18f), w, h,
                Display.dp(context, 8f), Display.dp(context, 8f), panel)
            canvas.drawText("${cx.toInt()} , ${cy.toInt()}", w / 2f,
                h - Display.dp(context, 5f), text)
            // 十字准星
            canvas.drawLine(c, 0f, c, h, cross)
            canvas.drawLine(0f, c, w, c, cross)
            canvas.drawCircle(c, c, Display.dp(context, 3f), dot)
            canvas.drawCircle(c, c, c - Display.dp(context, 10f), ring)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = paramsX(); startY = paramsY()
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!moved && kotlin.math.abs(dx) + kotlin.math.abs(dy) >
                        Display.dp(context, 8f)) moved = true
                    if (moved) {
                        val p = CoordPicker.params ?: return true
                        p.x = (startX + dx).toInt()
                        p.y = (startY + dy).toInt()
                        runCatching { CoordPicker.wm?.updateViewLayout(this, p) }
                        syncCenter()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        // 点击准星 = 确认
                        syncCenter()
                        onPicked(cx, cy)
                    }
                    return true
                }
            }
            return true
        }

        private fun paramsX(): Int = CoordPicker.params?.x ?: 0
        private fun paramsY(): Int = CoordPicker.params?.y ?: 0

        private fun syncCenter() {
            val p = CoordPicker.params ?: return
            cx = p.x + width / 2f
            cy = p.y + height / 2f
            invalidate()
        }
    }

    /** 提示文案，用于表单「?」帮助 */
    fun helpText(field: String): String = when (field) {
        "点击位置" -> "屏幕绝对像素坐标。点「拾取」后本应用会收到后台，拖动准星到目标位置再点一下即可取回真实坐标。"
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
