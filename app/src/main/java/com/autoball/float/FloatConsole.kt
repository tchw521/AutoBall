package com.autoball.float

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.autoball.core.log.RunLog
import com.autoball.core.util.Display
import com.autoball.ui.Theme

/**
 * 悬浮控制台（`console.show()`）——脚本调试闭环的关键一环（R-131）。
 *
 * 为什么必须有：脚本运行时用户正在**别的应用**里，日志页在应用内，
 * 根本看不到。出了错只能等脚本跑完回头翻，等于盲调。
 *
 * 设计取舍：
 * 1. **只读**，不提供输入框——悬浮窗抢焦点会让目标应用失去输入焦点，
 *    反而干扰被操作的应用。要看变量用 `printVars()`（走弹窗）。
 * 2. **节流刷新**：脚本高频打日志时按 120ms 合并刷新，
 *    否则每条都 invalidate 会拖慢脚本本身。
 * 3. **只保留最近 200 行**：DOM 无限增长会让窗口越来越卡。
 */
object FloatConsole {

    private val main = Handler(Looper.getMainLooper())

    private var root: FrameLayout? = null
    private var body: LinearLayout? = null
    private var scroller: ScrollView? = null
    private var listener: (() -> Unit)? = null

    /** 已显示条数，超过 [MAX_LINES] 时丢弃最早的 */
    private var shown = 0
    private const val MAX_LINES = 200
    private const val THROTTLE_MS = 120L
    private var lastFlush = 0L
    private var pending = false

    private val flush = Runnable {
        pending = false
        lastFlush = android.os.SystemClock.uptimeMillis()
        render()
    }

    /** 只能主线程调用 */
    fun show(ctx: Context): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { runCatching { show(ctx) } }
            return true
        }
        if (root != null) return true
        val w = Display.dpInt(ctx, FloatWindows.widthDp(ctx))
        val maxH = FloatWindows.maxHeightPx(ctx)

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.floatStops()[0])
            val p = Display.dpInt(ctx, 10f)
            setPadding(p, p, p, p)
        }

        // 标题栏
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, Display.dpInt(ctx, 6f))
        }
        bar.addView(TextView(ctx).apply {
            text = "控制台"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.textPri())
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(miniBtn(ctx, "清空") { com.autoball.AB.log.clear() })
        bar.addView(miniBtn(ctx, "✕") { hide() })
        card.addView(bar)

        // 日志区
        val inner = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val sv = ScrollView(ctx).apply {
            addView(inner)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        card.addView(sv)

        val holder = FrameLayout(ctx).apply {
            addView(card, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT))
        }

        val params = android.view.WindowManager.LayoutParams().apply {
            this.width = w
            this.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
            type = android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = (android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            format = android.graphics.PixelFormat.TRANSLUCENT
            gravity = Gravity.BOTTOM or Gravity.START
            x = Display.dpInt(ctx, 12f)
            y = Display.dpInt(ctx, 96f)
        }

        if (!FloatWindows.add(ctx, holder, params)) return false

        root = holder
        body = inner
        scroller = sv

        // 高度封顶：日志多时窗口不能无限长高
        holder.post {
            val h = holder.height
            if (h > maxH) {
                params.height = maxH
                runCatching {
                    (ctx.applicationContext.getSystemService(
                        Context.WINDOW_SERVICE) as android.view.WindowManager)
                        .updateViewLayout(holder, params)
                }
            }
        }

        val l: () -> Unit = { scheduleFlush() }
        listener = l
        com.autoball.AB.log.addListener(l)
        render()
        return true
    }

    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { hide() }
            return
        }
        listener?.let { com.autoball.AB.log.removeListener(it) }
        listener = null
        main.removeCallbacks(flush)
        val r = root ?: return
        FloatWindows.remove(r)
        root = null; body = null; scroller = null; shown = 0
    }

    fun isShowing(): Boolean = root != null

    private fun scheduleFlush() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { scheduleFlush() }
            return
        }
        if (pending) return
        val wait = (THROTTLE_MS - (android.os.SystemClock.uptimeMillis() - lastFlush))
            .coerceAtLeast(0L)
        pending = true
        main.postDelayed(flush, wait)
    }

    /** 全量重绘最近 [MAX_LINES] 条 */
    private fun render() {
        val box = body ?: return
        val list = com.autoball.AB.log.snapshot().takeLast(MAX_LINES)
        box.removeAllViews()
        val ctx = box.context
        list.forEach { e ->
            box.addView(TextView(ctx).apply {
                text = e.line()
                textSize = 11f
                // 等宽更利于对齐阅读；日志里常含坐标与耗时
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(when (e.level) {
                    RunLog.Level.ERROR -> Theme.warn()
                    RunLog.Level.WARN -> Theme.warn()
                    RunLog.Level.OK -> Theme.ok()
                    else -> Theme.textSec()
                })
                setPadding(0, Display.dpInt(ctx, 1f), 0, Display.dpInt(ctx, 1f))
            })
        }
        shown = list.size
        scroller?.post { scroller?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun miniBtn(ctx: Context, t: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = t
            textSize = 11f
            setTextColor(Theme.textSec())
            setPadding(Display.dpInt(ctx, 8f), 0, 0, 0)
            setOnClickListener { onClick() }
        }
}
