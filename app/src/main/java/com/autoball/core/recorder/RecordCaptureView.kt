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
 * 工作方式：**整屏接管 + 立即补发**（底部留 40dp 给系统手势条）。
 * 窗口内触摸由 AutoBall 消费，随即用无障碍 / Shizuku 通道把同一个手势补发给目标应用；
 * 于是用户在目标 App 上看到的是**实时生效**的操作，AutoBall 只是在旁边记了一笔。
 *
 * 已知限制：底部 40dp 内的起手会直接穿透给系统（不录），这是刻意留给手势导航的。
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
        // 范围标记框：录制期间必须让用户**看得见**采集范围。
        // 此前采集层完全透明，用户无从判断哪片区域的触摸会被记录，
        // 点在框外就会静默丢动作——表现为"录制不灵"。
        willNotDraw = false
    }

    /**
     * 画采集范围的边框（自动精灵录制态同款：屏幕四周一圈标记）。
     *
     * 用 onDraw 而不是再叠一层边框窗口：采集层本来就是整屏的，
     * 多一个窗口就多一份被系统判为"不可信遮挡"的风险。
     */
    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val i = Display.dp(context, 3f)
        framePaint.strokeWidth = Display.dp(context, 2f)
        // 四角用亮色描出 L 形，四边用细线连起来——
        // 纯细线在浅色壁纸上几乎看不见
        canvas.drawLine(i, i, w - i, i, framePaint)
        canvas.drawLine(w - i, i, w - i, h - i, framePaint)
        canvas.drawLine(w - i, h - i, i, h - i, framePaint)
        canvas.drawLine(i, h - i, i, i, framePaint)
        val L = Display.dp(context, 22f)
        cornerPaint.strokeWidth = Display.dp(context, 4f)
        // 左上
        canvas.drawLine(i, i + L, i, i, cornerPaint)
        canvas.drawLine(i, i, i + L, i, cornerPaint)
        // 右上
        canvas.drawLine(w - i - L, i, w - i, i, cornerPaint)
        canvas.drawLine(w - i, i, w - i, i + L, cornerPaint)
        // 左下
        canvas.drawLine(i, h - i - L, i, h - i, cornerPaint)
        canvas.drawLine(i, h - i, i + L, h - i, cornerPaint)
        // 右下
        canvas.drawLine(w - i - L, h - i, w - i, h - i, cornerPaint)
        canvas.drawLine(w - i, h - i, w - i, h - i - L, cornerPaint)
    }

    private val framePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x662FA2DA
        style = android.graphics.Paint.Style.STROKE
    }
    private val cornerPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xEE2FA2DA.toInt()
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
    }

    private fun reset() {
        samples.clear()
        pointerMap.clear()
        activePointer = -1
        multi = false
    }

    /**
     * 接管窗口内的触摸并编译为动作——**录制功能的核心**。
     *
     * 此前这里只有背景色、没有任何触摸处理，采集层是个纯摆设：
     * `controller.onStroke()` 永远不会被调用，于是录制期间怎么点都录不到东西，
     * 而界面上一切正常（状态、控制条都在），表现为"录制没有效果"。
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!active) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                activePointer = ev.getPointerId(ev.actionIndex)
                addSample(activePointer, ev, ev.actionIndex)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                addSample(ev.getPointerId(ev.actionIndex), ev, ev.actionIndex)
                if (pointerMap.size > 1) multi = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until ev.pointerCount) {
                    addSample(ev.getPointerId(i), ev, i)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                addSample(ev.getPointerId(ev.actionIndex), ev, ev.actionIndex)
                return true
            }
            MotionEvent.ACTION_UP -> {
                // 抬手时把手上所有指针都补最后一帧，再统一编译
                for (i in 0 until ev.pointerCount) {
                    addSample(ev.getPointerId(i), ev, i)
                }
                flush()
                return true
            }
        }
        return true
    }

    /**
     * 采样并用 **raw 屏幕坐标**。
     *
     * 不能用 getX/getY：那是视图内局部坐标，而采集窗带偏移（x=7%、y=20%），
     * 直接用会整体平移，录出来的点全偏。
     */
    private fun addSample(id: Int, ev: MotionEvent, idx: Int) {
        val list = pointerMap.getOrPut(id) { ArrayList() }
        val t = System.currentTimeMillis()
        val last = list.lastOrNull()
        if (last != null && t - last.t < GestureCompiler.SAMPLE_MIN_INTERVAL_MS) return
        list.add(GestureCompiler.Sample(ev.getRawX(idx), ev.getRawY(idx), t))
    }

    /** 一次手势结束：编译并交给控制器（补发 + 追加到动作流） */
    private fun flush() {
        val now = System.currentTimeMillis()
        val strokes = pointerMap.values
            .filter { it.isNotEmpty() }
            .map { pts -> GestureCompiler.Stroke(pts.first().t, now, ArrayList(pts)) }
        reset()
        if (strokes.isEmpty()) return
        if (strokes.size > 1) controller.onStrokeMulti(strokes)
        else controller.onStroke(strokes[0])
    }

    /**
     * 坐标提示：在触摸点旁短暂显示该点坐标。
     *
     * 录制时用户点下去，需要一个即时反馈确认"这一点被记下来了、记在哪"。
     * 没有它的话，录制过程完全黑盒——用户只能结束录制后回列表才知道有没有录上。
     * 显示**百分比**（与脚本里存的一致），而不是像素：脚本换机型靠百分比生效，
     * 给像素会误导用户以为坐标是绝对的。
     */
    fun showHint(rawX: Float, rawY: Float, text: String) {
        val p = windowParams
        val lx = rawX - (p?.x ?: 0)
        val ly = rawY - (p?.y ?: 0)
        val tv = TextView(context).apply {
            this.text = "◎ $text"
            textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(Display.dpInt(context, 7f), Display.dpInt(context, 3f),
                Display.dpInt(context, 7f), Display.dpInt(context, 3f))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = Display.dp(context, 9f)
                setColor(0xCC2F6BFF.toInt())
            }
            alpha = 0f
        }
        addView(tv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
        })
        // 放在触点右上方：手指本身会盖住触点正下方
        tv.translationX = (lx + Display.dp(context, 16f))
            .coerceIn(0f, (width - Display.dp(context, 60f)).coerceAtLeast(0f))
        tv.translationY = (ly - Display.dp(context, 34f)).coerceAtLeast(0f)
        tv.animate().alpha(1f).setDuration(90).start()
        postDelayed({
            tv.animate().alpha(0f).setDuration(260)
                .withEndAction { removeView(tv) }.start()
        }, 720)
    }

    companion object {
        /**
         * 采集窗尺寸：**整屏**（底部留 40dp 给系统手势条）。
         *
         * 早前是屏幕 86% × 72%（居中偏下），代价是顶部状态栏附近与底部区域
         * 的点击录不到——而"点其他 App 的按钮"恰恰常在这两处（返回箭头在上、
         * 底部导航在下）。录不到就是静默丢动作，用户只会觉得录制不灵。
         *
         * 底部留 40dp 是为了不抢系统手势区（上滑返回/回桌面），
         * 否则录制期间连退出都做不到，只能靠胶囊停止。
         */
        fun createParams(context: Context): WindowManager.LayoutParams {
            val p = Display.screenSize(context)
            val type = if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val bottom = Display.dpInt(context, 40f)
            return WindowManager.LayoutParams(
                p.x, (p.y - bottom).coerceAtLeast(p.y / 2),
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    // 必须加：不加的话窗口是模态的，会吞掉**整个屏幕**的触摸，
                    // 窗口外的操作既录不到也传不到目标应用——等于录屏时手机失灵。
                    // 加了之后：窗口内由 AutoBall 接管并补发，窗口外直接穿透。
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
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

    /**
     * 暂停 / 继续。
     *
     * 暂停必须**真正移除窗口**，而不是留着窗口把触摸吞掉：
     * 窗口还在的话，用户在暂停期间对目标应用的任何操作都不会生效，
     * 看起来像手机失灵。
     */
    /** 在触摸点旁显示坐标提示（录制反馈） */
    fun hintAt(rawX: Float, rawY: Float, text: String) {
        val v = view ?: return
        if (!v.active) return
        v.post { runCatching { v.showHint(rawX, rawY, text) } }
    }

    fun setActive(active: Boolean) {
        val v = view ?: return
        v.active = active
        if (active) {
            if (v.parent == null) {
                val p = v.windowParams
                if (p != null) runCatching { wm?.addView(v, p) }
            }
        } else {
            if (v.parent != null) runCatching { wm?.removeView(v) }
        }
    }
}
