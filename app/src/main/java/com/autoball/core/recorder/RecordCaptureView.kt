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
        buildChrome()
        setBackgroundColor(Color.parseColor("#08000000"))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildChrome() {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E61B1730"))
                cornerRadius = Display.dp(context, 12f)
            }
            setPadding(Display.dpInt(context, 10f), Display.dpInt(context, 6f),
                Display.dpInt(context, 10f), Display.dpInt(context, 6f))
        }
        bar.addView(chip("暂停") {
            if (controller.state == RecordController.State.RECORDING) controller.pause() else controller.resume()
        })
        bar.addView(chip("撤销") { controller.undo() })
        bar.addView(chip("等待1s") { controller.insertWait(1000) })
        bar.addView(chip("停止", Color.parseColor("#FF5B6E")) { controller.interrupt("用户停止") })

        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
        addView(bar, lp)

        bar.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    dragStartX = e.rawX
                    dragStartY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        val p = windowParams
                        val wm = windowManager
                        if (p != null && wm != null) {
                            p.x += (e.rawX - dragStartX).toInt()
                            p.y += (e.rawY - dragStartY).toInt()
                            dragStartX = e.rawX
                            dragStartY = e.rawY
                            runCatching { wm.updateViewLayout(this, p) }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; true }
                else -> true
            }
        }
    }

    private fun chip(text: String, color: Int = Color.parseColor("#4A9EFF"), onClick: () -> Unit): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(color); cornerRadius = Display.dp(context, 10f) }
            setPadding(Display.dpInt(context, 12f), Display.dpInt(context, 5f),
                Display.dpInt(context, 12f), Display.dpInt(context, 5f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(Display.dpInt(context, 4f), 0, Display.dpInt(context, 4f), 0)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    /** 采集区域：消费触摸并转发给控制器 */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!active || controller.state != RecordController.State.RECORDING) return false
        val now = System.currentTimeMillis()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointer = event.getPointerId(0)
                downT = now
                multi = false
                samples.clear()
                pointerMap.clear()
                samples.add(GestureCompiler.Sample(event.x, event.y, now))
                pointerMap[activePointer] = ArrayList(samples)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multi = true
                val idx = event.actionIndex
                val pid = event.getPointerId(idx)
                pointerMap[pid] = ArrayList(
                    listOf(GestureCompiler.Sample(event.getX(idx), event.getY(idx), now)))
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val list = pointerMap[pid] ?: continue
                    val lastT = list.lastOrNull()?.t ?: 0
                    if (now - lastT < 8) continue
                    list.add(GestureCompiler.Sample(event.getX(i), event.getY(i), now))
                }
                if (!multi) {
                    val lastT = samples.lastOrNull()?.t ?: 0
                    if (now - lastT >= 8) samples.add(GestureCompiler.Sample(event.x, event.y, now))
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                val pid = event.getPointerId(idx)
                pointerMap[pid]?.add(GestureCompiler.Sample(event.getX(idx), event.getY(idx), now))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) { reset(); return true }
                if (multi) {
                    val strokes = pointerMap.values
                        .filter { it.size >= 2 }
                        .map { GestureCompiler.Stroke(it.first().t, now, it) }
                    if (strokes.size >= 2) {
                        val a = GestureCompiler.compileMulti(strokes, context.resources.displayMetrics.density)
                        a.id = com.autoball.core.model.Action.newId()
                        com.autoball.AB.log.info("record", "记录多指手势（${strokes.size} 指）")
                        controller.onStroke(GestureCompiler.Stroke(downT, now, samples))
                    } else {
                        controller.onStroke(GestureCompiler.Stroke(downT, now, samples))
                    }
                } else if (samples.isNotEmpty()) {
                    samples.add(GestureCompiler.Sample(event.x, event.y, now))
                    controller.onStroke(GestureCompiler.Stroke(downT, now, ArrayList(samples)))
                }
                reset()
                return true
            }
        }
        return false
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
