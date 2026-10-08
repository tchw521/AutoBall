package com.autoball.float

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.core.RunControl
import com.autoball.core.util.Display
import com.autoball.ui.Theme

/**
 * 单步执行的悬浮控制条（R-131 调试闭环）。
 *
 * [RunControl] 里 `stepMode / nextStep / checkStep` 早就实现好了，
 * 但一直**没有 UI 入口**——等于有能力用不上。这是本文件存在的原因。
 *
 * 为什么是悬浮条而不是应用内按钮：单步执行时你正盯着**被操作的应用**，
 * 每次都要切回应用点「下一步」就失去意义了。
 *
 * 「继续」按钮关掉 stepMode 并放行——单步通常只用来卡住出错的那几步，
 * 之后应该让脚本跑完，而不是一步步点到底。
 */
object FloatStepBar {

    private val main = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var label: TextView? = null
    private var control: RunControl? = null
    private var stepNo = 0

    fun show(ctx: Context, c: RunControl) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { show(ctx, c) }
            return
        }
        hide()
        control = c
        stepNo = 0

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Theme.floatStops()[0])
            val p = Display.dpInt(ctx, 8f)
            setPadding(p, p, p, p)
        }

        label = TextView(ctx).apply {
            text = "单步 0"
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.textPri())
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(label)

        bar.addView(btn(ctx, "下一步") {
            stepNo++
            label?.text = "单步 $stepNo"
            // 先放行走一步，再重新置位：checkStep 每步都会 wait
            c.nextStep()
        })
        bar.addView(btn(ctx, "继续") {
            c.stepMode = false
            c.nextStep()
            hide()
        })
        bar.addView(btn(ctx, "停止") {
            c.cancel()
            hide()
        })

        val holder = FrameLayout(ctx).apply { addView(bar) }
        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            format = android.graphics.PixelFormat.TRANSLUCENT
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = Display.dpInt(ctx, 150f)
        }
        if (!FloatWindows.add(ctx, holder, params)) return
        root = holder

        // 首步也要手动放行：checkStep 在第一个动作前就会 wait
        stepNo = 0
    }

    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { hide() }
            return
        }
        control = null
        label = null
        val r = root ?: return
        FloatWindows.remove(r)
        root = null
    }

    fun isShowing(): Boolean = root != null

    private fun btn(ctx: Context, t: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = t
            textSize = 12f
            setTextColor(Theme.pri())
            setPadding(Display.dpInt(ctx, 10f), Display.dpInt(ctx, 4f),
                Display.dpInt(ctx, 10f), Display.dpInt(ctx, 4f))
            setOnClickListener { onClick() }
        }
}
