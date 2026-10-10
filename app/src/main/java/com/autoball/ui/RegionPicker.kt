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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.core.util.Display
import com.autoball.float.FloatManager

/**
 * 区域选择（UI 设计方案 v3 · P-64）：白色取景框 + 四角缩放。
 *
 * 行为：隐藏本软件界面 → 露出真实屏幕 → 拖动取景框移动 / 拖四角缩放
 * → 确定后回调区域（绝对像素：left / top / right / bottom）。
 */
object RegionPicker {

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

    /** @param onPicked left, top, right, bottom（绝对像素） */
    fun pick(context: Context, activity: Activity?, hostDialog: android.app.Dialog?,
             onPicked: (Float, Float, Float, Float) -> Unit) {
        if (!Display.canDrawOverlay(context)) {
            Display.openOverlaySettings(context)
            return
        }
        removeNow()
        val ctx = context.applicationContext
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

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

        val layer = PickLayer(ctx) { l, t, r, b ->
            onPicked(l, t, r, b)
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
        PickerWindow.bind(manager, layer, p)
    }

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
        PickerWindow.release()
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private class PickLayer(
        context: Context,
        private val onConfirm: (Float, Float, Float, Float) -> Unit
    ) : FrameLayout(context) {

        /** 百分比区域 */
        private var lx = 30f
        private var ty = 30f
        private var rw = 40f
        private var rh = 40f

        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = Display.dp(context, 2f)
        }
        private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Theme.inkAlpha(0.35f)
            style = Paint.Style.STROKE
            strokeWidth = Display.dp(context, 1f)
        }
        private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // 框外压暗**必须很淡**（0x33 ≈ 20%）：这是"取真实坐标"的场景，
            // 压暗过重会让人看不清框外还有什么，尤其取点位置本身可能在框外
            color = Theme.scrim(0.20f)
            style = Paint.Style.FILL
        }

        /** 底部条；调节时自动淡出 */
        private var barView: View? = null
        private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Theme.info()
            style = Paint.Style.STROKE
            strokeWidth = Display.dp(context, 2f)
        }
        private val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Theme.plate()
            style = Paint.Style.FILL
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = Display.dp(context, 11f)
            textAlign = Paint.Align.CENTER
        }


        private var mode = ""
        private var sx = 0f
        private var sy = 0f
        private var ol = 0f
        private var ot = 0f
        private var ow = 0f
        private var oh = 0f

        init {
            setWillNotDraw(false)
            setBackgroundColor(Color.TRANSPARENT)
            buildBar()
        }

        private fun buildBar() {
            val bar = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 10f),
                    Display.dpInt(context, 14f), Display.dpInt(context, 14f))
                background = Theme.dialogBg()
                (background as android.graphics.drawable.GradientDrawable).cornerRadius =
                    Display.dp(context, 16f)
            }
            bar.addView(PickerWindow.tipRow(context, "滑动屏幕来调节区域"), 0)

            // 不重复显示尺寸：onDraw 已在取景框上方画了 "W × H" 小牌。
            // 底部条多一行就多挡一片——而底部是最常框选的区域。
            val btns = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val cancel = Ui.button(context, "取消", false)
            cancel.setOnClickListener { close() }
            btns.addView(cancel, LinearLayout.LayoutParams(0,
                Display.dpInt(context, 39f), 1f).apply {
                marginEnd = Display.dpInt(context, 6f)
            })
            // 「选择区域」：重置为默认框，便于重新框选（v3 三按钮布局）
            val reset = Ui.button(context, "选择区域", false)
            reset.setOnClickListener {
                lx = 30f; ty = 30f; rw = 40f; rh = 40f
                invalidate()
                Ui.toast(context, "已重置取景框，拖动框体或四角调整")
            }
            btns.addView(reset, LinearLayout.LayoutParams(0,
                Display.dpInt(context, 39f), 1f).apply {
                marginEnd = Display.dpInt(context, 6f)
            })
            val ok = Ui.button(context, "确定", true)
            ok.setOnClickListener { confirm() }
            btns.addView(ok, LinearLayout.LayoutParams(0,
                Display.dpInt(context, 39f), 1f))
            bar.addView(btns, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
            PickerBar.attach(bar)
            barView = bar
            addView(bar, LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.BOTTOM })
        }

        private fun rectPx(): FloatArray {
            val w = width.toFloat(); val h = height.toFloat()
            return floatArrayOf(lx / 100f * w, ty / 100f * h,
                rw / 100f * w, rh / 100f * h)
        }

        private fun confirm() {
            val r = rectPx()
            Ui.toast(context, "已选择区域 ${lx.toInt()}%,${ty.toInt()}% · ${rw.toInt()}%×${rh.toInt()}%")
            onConfirm(r[0], r[1], r[0] + r[2], r[1] + r[3])
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val r = rectPx()
            val l = r[0]; val t = r[1]; val rr = r[0] + r[2]; val bb = r[1] + r[3]

            // 框外压暗
            canvas.drawRect(0f, 0f, w, t, shade)
            canvas.drawRect(0f, bb, w, h, shade)
            canvas.drawRect(0f, t, l, bb, shade)
            canvas.drawRect(rr, t, w, bb, shade)

            // 白色取景框 + 三分线
            canvas.drawRect(l, t, rr, bb, frame)
            for (i in 1..2) {
                val gx = l + r[2] * i / 3f
                val gy = t + r[3] * i / 3f
                canvas.drawLine(gx, t, gx, bb, guide)
                canvas.drawLine(l, gy, rr, gy, guide)
            }

            // 四角缩放把手
            val hs = Display.dp(context, 11f)
            val pts = arrayOf(floatArrayOf(l, t), floatArrayOf(rr, t),
                floatArrayOf(l, bb), floatArrayOf(rr, bb))
            for (p in pts) {
                canvas.drawCircle(p[0], p[1], hs, handle)
                canvas.drawCircle(p[0], p[1], hs, handleRing)
            }

            // 尺寸读数
            val tw = Display.dp(context, 96f)
            val th = Display.dp(context, 20f)
            val px = (l).coerceAtMost(w - tw)
            val py = (t - Display.dp(context, 26f)).coerceAtLeast(0f)
            canvas.drawRoundRect(px, py, px + tw, py + th,
                Display.dp(context, 6f), Display.dp(context, 6f), plate)
            canvas.drawText("%d × %d".format(r[2].toInt(), r[3].toInt()),
                px + tw / 2f, py + th - Display.dp(context, 6f), text)
        }

        private fun hitHandle(x: Float, y: Float): String {
            val r = rectPx()
            val l = r[0]; val t = r[1]; val rr = r[0] + r[2]; val bb = r[1] + r[3]
            val hs = Display.dp(context, 18f)
            if (dist(x, y, l, t) < hs) return "lt"
            if (dist(x, y, rr, t) < hs) return "rt"
            if (dist(x, y, l, bb) < hs) return "lb"
            if (dist(x, y, rr, bb) < hs) return "rb"
            return if (x in l..rr && y in t..bb) "move" else "new"
        }

        private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float =
            kotlin.math.sqrt(((ax - bx) * (ax - bx) + (ay - by) * (ay - by)).toDouble()).toFloat()

        private fun pct(x: Float, y: Float): FloatArray {
            val w = width.toFloat(); val h = height.toFloat()
            return floatArrayOf((x / w) * 100f, (y / h) * 100f)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    barView?.let { PickerBar.setDragging(it, true) }
                    mode = hitHandle(e.x, e.y)
                    val p = pct(e.x, e.y)
                    sx = p[0]; sy = p[1]
                    ol = lx; ot = ty; ow = rw; oh = rh
                    if (mode == "new") {
                        lx = p[0]; ty = p[1]; rw = 1f; rh = 1f
                        ol = lx; ot = ty; ow = rw; oh = rh
                        mode = "rb"
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    barView?.let { PickerBar.setDragging(it, true) }
                    val p = pct(e.x, e.y)
                    val dx = p[0] - sx
                    val dy = p[1] - sy
                    when (mode) {
                        "move" -> {
                            lx = (ol + dx).coerceIn(0f, 100f - ow)
                            ty = (ot + dy).coerceIn(0f, 100f - oh)
                        }
                        "lt" -> {
                            lx = (ol + dx).coerceIn(0f, ol + ow - 2f)
                            ty = (ot + dy).coerceIn(0f, ot + oh - 2f)
                            rw = ol + ow - lx; rh = ot + oh - ty
                        }
                        "rt" -> {
                            ty = (ot + dy).coerceIn(0f, ot + oh - 2f)
                            rw = (ow + dx).coerceIn(2f, 100f - ol)
                            rh = ot + oh - ty
                        }
                        "lb" -> {
                            lx = (ol + dx).coerceIn(0f, ol + ow - 2f)
                            rw = ol + ow - lx
                            rh = (oh + dy).coerceIn(2f, 100f - ot)
                        }
                        "rb" -> {
                            rw = (ow + dx).coerceIn(2f, 100f - ol)
                            rh = (oh + dy).coerceIn(2f, 100f - ot)
                        }
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    barView?.let { PickerBar.setDragging(it, false) }
                    return true
                }
            }
            return true
        }

    }
}
