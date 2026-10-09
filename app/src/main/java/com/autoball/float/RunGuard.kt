package com.autoball.float

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.core.util.Display
import com.autoball.ui.Theme

/**
 * 运行时防误触层（设置项 guardTouch）。
 *
 * 作用：脚本运行时盖一层消费触摸的透明层，避免手碰到屏幕打断脚本。
 * 脚本的点击由**系统级**通道下发（无障碍 dispatchGesture / Shizuku input），
 * 不经过应用窗口，因此不受本层影响。
 *
 * 三个必须说明的取舍：
 *
 * 1. **默认关闭**。
 *    全屏拦截层会挡住用户对手机的一切操作（包括想中途干别的事）。
 *    属于"有副作用的安全措施"，应由用户主动选择，而不是默认替他决定。
 *    默认 true 会让不知情的用户在运行后以为手机卡死。
 *
 * 2. **必须自带停止入口**。
 *    层一旦消费触摸，用户就点不到任何别的东西；
 *    若层上没停止按钮，脚本就只能等它自己跑完——反而更难中断。
 *
 * 3. **截图前必须隐藏**。
 *    本层是 window 层，截图（找色 / 找图条件）可能把它合成进去。
 *    虽然 alpha=0 理论上不改变像素，但 ROM 合成行为不完全可控，
 *    一次误判就会让"图片存在"条件恒不成立。
 */
object RunGuard {

    private val main = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var onStop: (() -> Unit)? = null

    fun isShowing(): Boolean = root != null

    /** 仅主线程 */
    fun show(ctx: Context, scriptName: String, stop: () -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { show(ctx, scriptName, stop) }
            return
        }
        hide()
        onStop = stop

        val layer = FrameLayout(ctx).apply {
            // 完全透明：视觉上不存在，只用于消费触摸
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, _ -> true }
        }

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Theme.floatStops()[0].let { c ->
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(c); cornerRadius = Display.dpInt(ctx, 14f).toFloat()
                }
            }
            val p = Display.dpInt(ctx, 12f)
            setPadding(p, p, p, p)
        }
        bar.addView(TextView(ctx).apply {
            text = "运行中 · $scriptName"
            textSize = 12f
            setTextColor(Theme.textPri())
        })
        bar.addView(TextView(ctx).apply {
            text = "点击此处停止"
            textSize = 11f
            setTextColor(Theme.warn())
            setPadding(0, Display.dpInt(ctx, 2f), 0, 0)
            setOnClickListener { stop() }
        })
        bar.isClickable = true
        layer.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = Display.dpInt(ctx, 28f)
        })

        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            // 不加 FLAG_NOT_TOUCH_MODAL：本窗口区域内要**消费**触摸
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = android.graphics.PixelFormat.TRANSPARENT
            gravity = Gravity.TOP or Gravity.START
        }
        if (!FloatWindows.add(ctx, layer, params)) return
        root = layer
    }

    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { hide() }
            return
        }
        onStop = null
        val r = root ?: return
        FloatWindows.remove(r)
        root = null
    }
}
