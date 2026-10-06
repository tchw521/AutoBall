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
 * 全屏选点（UI 设计方案 v3 · N20 / N15）。
 *
 * 行为：隐藏本软件一切界面 → 露出真实屏幕（可停在任意应用或桌面）
 * → 拖动 / 点击定位十字准星 → 百分比显示 + ±1% 微调 → 确定后回调。
 *
 * 与旧实现的根本区别：旧版用一枚小准星浮标，看不到全局；本版是全屏选点层，
 * 完全对齐 v3 —— 百分比坐标（0–100%）、方向微调、底部确定/取消条。
 *
 * 说明：坐标内部以**百分比**保存（分辨率无关），回调时按当前屏幕换算为绝对像素，
 * 以兼容动作模型；动作流自 v0.4 起另有屏幕签名缩放。
 */
object CoordPicker {

    @Volatile
    private var view: PickLayer? = null
    @Volatile
    private var wm: WindowManager? = null
    @Volatile
    private var hostActivity: Activity? = null
    @Volatile
    private var hostDialog: android.app.Dialog? = null
    @Volatile
    private var wasBallShown = false

    private val handler = Handler(Looper.getMainLooper())

    fun pick(context: Context, activity: Activity?, onPicked: (Float, Float) -> Unit) {
        pick(context, activity, null, onPicked)
    }

    /**
     * @param hostDialog 触发选点的弹窗（可为空）；选点期间隐藏，取完自动恢复
     * @param onPicked   回调绝对像素坐标（由百分比按当前屏幕换算）
     */
    fun pick(context: Context, activity: Activity?, hostDialog: android.app.Dialog?,
             onPicked: (Float, Float) -> Unit) {
        if (!Display.canDrawOverlay(context)) {
            Display.openOverlaySettings(context)
            return
        }
        removeNow()

        val ctx = context.applicationContext
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val sw = Display.screenSize(ctx)

        // 1) 隐藏本应用的一切遮挡，露出真实屏幕
        wasBallShown = FloatManager.isBallShown()
        FloatManager.hideAll()
        hostActivity = activity
        this.hostDialog = hostDialog
        runCatching { hostDialog?.hide() }
        handler.postDelayed({
            runCatching {
                activity?.startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }, 80)

        // 2) 全屏选点层（背景透明，真实屏幕可见）
        val layer = PickLayer(ctx, sw.x, sw.y) { px, py ->
            onPicked(px, py)
            close()
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSPARENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        runCatching { manager.addView(layer, p) }
        view = layer
        wm = manager
    }

    /** 完成：移除选点层，恢复弹窗与悬浮球，并把本应用带回前台 */
    fun close() {
        handler.post {
            removeNow()
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

    private fun removeNow() {
        val v = view
        if (v != null) runCatching { wm?.removeView(v) }
        view = null
        wm = null
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    /** 全屏选点层：十字准星 + 百分比读数 + 底部操作条 */
    private class PickLayer(
        context: Context,
        private val screenW: Int,
        private val screenH: Int,
        private val onConfirm: (Float, Float) -> Unit
    ) : FrameLayout(context) {

        private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF0EA5E9")
            style = Paint.Style.STROKE
            strokeWidth = Display.dp(context, 1.6f)
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFE5484D")
            style = Paint.Style.FILL
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC0EA5E9")
            style = Paint.Style.STROKE
            strokeWidth = Display.dp(context, 2f)
        }
        private val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E61C1832")
            style = Paint.Style.FILL
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = Display.dp(context, 12f)
            textAlign = Paint.Align.CENTER
        }

        /** 百分比坐标 */
        private var pxPct = 50f
        private var pyPct = 50f

        init {
            setWillNotDraw(false)
            setBackgroundColor(Color.TRANSPARENT)   // 关键：露出真实屏幕
            buildBar()
        }

        private val posText: TextView

        private fun buildBar() {
            val bar = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 10f),
                    Display.dpInt(context, 14f), Display.dpInt(context, 14f))
                background = Theme.dialogBg(Theme.line2())
                (background as android.graphics.drawable.GradientDrawable).cornerRadius =
                    Display.dp(context, 16f)
            }

            // 微调四个方向
            val arrows = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            fun arrow(label: String, dx: Int, dy: Int): TextView = TextView(context).apply {
                text = label
                textSize = 15f
                setTextColor(Theme.textPri())
                gravity = Gravity.CENTER
                background = Theme.bubbleRound(context, Theme.surface2())
                val s = Display.dpInt(context, 38f)
                layoutParams = LinearLayout.LayoutParams(s, s).apply {
                    setMargins(Display.dpInt(context, 4f), 0, Display.dpInt(context, 4f), 0)
                }
                setOnClickListener {
                    pxPct = (pxPct + dx).coerceIn(0f, 100f)
                    pyPct = (pyPct + dy).coerceIn(0f, 100f)
                    invalidate(); syncText()
                }
            }
            arrows.addView(arrow("◀", -1, 0))
            arrows.addView(arrow("▲", 0, -1))
            arrows.addView(arrow("▼", 0, 1))
            arrows.addView(arrow("▶", 1, 0))
            bar.addView(arrows)

            posText = TextView(context).apply {
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Theme.pri2())
                gravity = Gravity.CENTER
                setPadding(0, Display.dpInt(context, 8f), 0, Display.dpInt(context, 8f))
            }
            bar.addView(posText)

            val btns = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val cancel = Ui.button(context, "取消", false)
            cancel.setOnClickListener { close() }
            btns.addView(cancel, LinearLayout.LayoutParams(0,
                Display.dpInt(context, 39f), 1f).apply {
                marginEnd = Display.dpInt(context, 9f)
            })
            val ok = Ui.button(context, "确定", true)
            ok.setOnClickListener { confirm() }
            btns.addView(ok, LinearLayout.LayoutParams(0,
                Display.dpInt(context, 39f), 1f))
            bar.addView(btns, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))

            addView(bar, LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.BOTTOM })
        }

        private fun syncText() {
            posText.text = "${pxPct.toInt()}% , ${pyPct.toInt()}%"
        }

        private fun confirm() {
            val px = pxPct / 100f * screenW
            val py = pyPct / 100f * screenH
            Ui.toast(context, "已选择位置 (${pxPct.toInt()}%, ${pyPct.toInt()}%)")
            onConfirm(px, py)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val x = pxPct / 100f * w
            val y = pyPct / 100f * h
            canvas.drawLine(x, 0f, x, h, cross)
            canvas.drawLine(0f, y, w, y, cross)
            canvas.drawCircle(x, y, Display.dp(context, 3f), dot)
            canvas.drawCircle(x, y, Display.dp(context, 16f), ring)
            // 坐标读数小牌
            val tw = Display.dp(context, 82f)
            val th = Display.dp(context, 20f)
            val lx = (x + Display.dp(context, 20f)).coerceAtMost(w - tw)
            val ly = (y - Display.dp(context, 28f)).coerceAtLeast(0f)
            canvas.drawRoundRect(lx, ly, lx + tw, ly + th,
                Display.dp(context, 6f), Display.dp(context, 6f), plate)
            canvas.drawText("${pxPct.toInt()}% , ${pyPct.toInt()}%",
                lx + tw / 2f, ly + th - Display.dp(context, 6f), text)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val w = width.toFloat(); val h = height.toFloat()
                    if (w > 0f && h > 0f) {
                        pxPct = ((event.x / w) * 100).coerceIn(0f, 100f)
                        pyPct = ((event.y / h) * 100).coerceIn(0f, 100f)
                        invalidate(); syncText()
                    }
                    return true
                }
            }
            return true
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            syncText()
        }
    }

    /** 提示文案，用于表单「?」帮助 */
    fun helpText(field: String): String = when (field) {
        "点击位置" -> "屏幕百分比坐标。点「拾取」后本软件会让出屏幕，在任意应用或桌面上拖动准星定位，确定即取回真实坐标。"
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
