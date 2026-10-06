package com.autoball.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.DialogInterface
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
 * 跨应用坐标拾取。
 *
 * 流程：隐藏本应用的一切窗口（弹窗 / 悬浮球 / 悬浮窗）→ 回到桌面
 * → 用户可自由打开任意应用或停在桌面 → 拖动准星瞄准真实位置 → 点一下取回坐标
 * → 自动回到本应用并恢复弹窗与悬浮球。
 *
 * 与旧实现的根本区别：旧版用全屏透明层在本应用内取点，既看不到也点不到其他应用；
 * 新版是一枚不遮挡目标界面的可拖动准星，取的是其他应用 / 桌面上的**真实坐标**。
 *
 * 已知限制：坐标为绝对像素；动作流自 v0.4 起支持按屏幕签名自动缩放。
 */
object CoordPicker {

    private const val STAR_DP = 76f

    @Volatile
    private var view: PickerView? = null
    @Volatile
    private var wm: WindowManager? = null
    @Volatile
    private var params: WindowManager.LayoutParams? = null
    @Volatile
    private var hostActivity: Activity? = null
    @Volatile
    private var hostDialog: android.app.Dialog? = null
    @Volatile
    private var wasBallShown = false

    private val handler = Handler(Looper.getMainLooper())

    /**
     * @param hostDialog 触发拾取的弹窗（可为空）；拾取期间隐藏，取完自动恢复
     */
    fun pick(context: Context, activity: Activity?, hostDialog: android.app.Dialog?,
             onPicked: (Float, Float) -> Unit) {
        if (!Display.canDrawOverlay(context)) {
            Display.openOverlaySettings(context)
            return
        }
        removeViewNow()

        val ctx = context.applicationContext
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 1) 隐藏本应用的一切遮挡
        wasBallShown = FloatManager.isBallShown()
        FloatManager.hideAll()
        hostActivity = activity
        this.hostDialog = hostDialog
        runCatching { hostDialog?.hide() }

        // 2) 真正离开本应用：回桌面，之后用户可打开任意目标应用
        handler.postDelayed({
            runCatching {
                activity?.startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }, 80)

        // 3) 只留一枚可拖动准星
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

    /** 完成取点：移除准星，恢复弹窗与悬浮球，并把本应用带回前台 */
    fun close() {
        handler.post {
            removeViewNow()
            val act = hostActivity
            hostActivity = null
            val dlg = hostDialog
            hostDialog = null

            if (act != null) {
                runCatching {
                    act.startActivity(Intent(act, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            runCatching { dlg?.show() }
            if (wasBallShown) {
                val app = act ?: runCatching { com.autoball.App.get() }.getOrNull()
                if (app != null) FloatManager.showBall(app)
            }
        }
    }

    /** 仅移除准星，不动宿主状态 */
    private fun removeViewNow() {
        val v = view
        if (v != null) runCatching { wm?.removeView(v) }
        view = null
        wm = null
        params = null
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
            // 底部小面板：显示坐标，保证在任何应用上都看得清
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
                    startX = CoordPicker.params?.x ?: 0
                    startY = CoordPicker.params?.y ?: 0
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
                        syncCenter()
                        onPicked(cx, cy)
                    }
                    return true
                }
            }
            return true
        }

        private fun syncCenter() {
            val p = CoordPicker.params ?: return
            cx = p.x + width / 2f
            cy = p.y + height / 2f
            invalidate()
        }
    }

    /** 提示文案，用于表单「?」帮助 */
    fun helpText(field: String): String = when (field) {
        "点击位置" -> "屏幕绝对像素坐标。点「拾取」后本应用会让出屏幕回到桌面，拖动准星到任意应用或桌面的目标位置，点一下准星即取回真实坐标。"
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
