package com.autoball.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.core.util.Display

/**
 * 底部导航：悬浮液态通透凝胶（UI v9）。
 *
 * 规格：脱离屏幕底边 14dp、26dp 胶囊圆角、半透明底 + 内高光 + 双层外投影；
 * 中央 56dp 渐变四角星凸起按钮，顶出导航上沿 23dp，「制作」标签在圆钮正下方、栏内。
 *
 * 关键实现约束：**不使用负 margin**。
 * 上一版把中央按钮用 -26dp 负 margin 顶出上沿，但 ViewGroup 默认裁剪 + 各家 ROM 对
 * elevation outline 的处理差异，导致圆钮上半截被切掉、标签也一起消失。
 * 本版改为容器自身预留 23dp 顶部空间：凝胶只画下方 62dp，圆钮画在顶部 56dp，
 * 全部子 View 都在容器边界内，任何 ROM 都不会裁到。
 */
class LiquidNavView(
    context: Context,
    private val onSelect: (Int) -> Unit,
    private val onCreate: () -> Unit
) : FrameLayout(context) {

    companion object {
        val TABS = arrayOf("脚本", "编辑", "制作", "市场", "我的")

        /** 凝胶本体高度 */
        private const val BAR_DP = 62f
        /** 圆钮顶出上沿的高度 */
        private const val OVER_DP = 23f
        /** 圆钮直径 */
        private const val STAR_DP = 56f

        /** 容器总高 = 顶出部分 + 凝胶本体 */
        fun heightDp(): Float = BAR_DP + OVER_DP
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val topLightPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val tabs = ArrayList<TextView>()
    private var selected = 0

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        elevation = Display.dp(context, 12f)

        val barH = Display.dpInt(context, BAR_DP)

        // 文字行：贴在凝胶本体（下方 62dp）内
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Display.dpInt(context, 8f), 0, Display.dpInt(context, 8f), 0)
        }
        for (i in TABS.indices) {
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            val tv = TextView(context).apply {
                text = TABS[i]
                textSize = 11f
                if (i == 2) {
                    // 「制作」标签：落在圆钮正下方、导航栏内部
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    setPadding(0, 0, 0, Display.dpInt(context, 6f))
                } else {
                    gravity = Gravity.CENTER
                }
                setTextColor(Theme.textSec())
                // 只有用户真实点击才通知外部。
                // 若把回调放进 select()，会形成 select → showPage → select 的无限递归，
                // 启动时直接 StackOverflowError 闪退。
                setOnClickListener { if (i != 2) { select(i); onSelect(i) } }
            }
            tabs.add(tv)
            row.addView(tv, lp)
        }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, barH).apply {
            gravity = Gravity.BOTTOM
        })

        // 中央圆钮：容器顶部，天然顶出凝胶上沿 23dp，无需负 margin
        val star = StarButton(context).apply {
            setOnClickListener { onCreate() }
        }
        val size = Display.dpInt(context, STAR_DP)
        addView(star, LayoutParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = 0
        })

        select(0)
    }

    fun select(index: Int) {
        selected = index
        for (i in tabs.indices) {
            val tv = tabs[i]
            val isSel = (i == index && i != 2)
            tv.setTextColor(if (isSel) Theme.textPri() else Theme.textSec())
            tv.textSize = if (isSel) 12f else 11f
            // 选中态：图标后垫一块更小的模糊胶囊（像手指按在凝胶上陷下去）
            tv.background = if (isSel)
                Theme.bubble(context, Color.parseColor(
                    if (Theme.isDark()) "#33FFFFFF" else "#1F000000"), 13f)
            else null
            tv.visibility = View.VISIBLE
        }
        // 不再在此回调 onSelect：select() 同时被外部同步调用（如 showPage），
        // 回传会形成递归。页面切换统一由用户点击驱动。
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return   // 尺寸为 0 时 LinearGradient 会抛异常

        val barH = Display.dp(context, BAR_DP)
        val top = h - barH                // 凝胶上沿
        val r = Display.dp(context, 26f)
        val dark = Theme.isDark()

        // 半透明凝胶底（只覆盖下方 62dp）
        bgPaint.shader = LinearGradient(0f, top, w, h,
            intArrayOf(Color.parseColor(if (dark) "#5C1C1832" else "#D8FFFFFF"),
                Color.parseColor(if (dark) "#471B1730" else "#C8FFFFFF")),
            null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(0f, top, w, h, r, r, bgPaint)
        bgPaint.shader = null

        // 内高光 inset 0 1px 0 rgba(255,255,255,.28)
        innerPaint.style = Paint.Style.STROKE
        innerPaint.strokeWidth = Display.dp(context, 1f)
        innerPaint.color = Color.parseColor(if (dark) "#47FFFFFF" else "#FFFFFFFF")
        canvas.drawRoundRect(Display.dp(context, 0.5f), top + Display.dp(context, 0.5f),
            w - Display.dp(context, 0.5f), h - Display.dp(context, 0.5f), r, r, innerPaint)

        // 顶部流光：横向渐变 92% 白
        topLightPaint.shader = LinearGradient(0f, 0f, w, 0f,
            intArrayOf(Color.TRANSPARENT, Color.parseColor("#EBFFFFFF"), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        topLightPaint.strokeWidth = Display.dp(context, 1.2f)
        canvas.drawLine(Display.dp(context, 14f), top + Display.dp(context, 1f),
            w - Display.dp(context, 14f), top + Display.dp(context, 1f), topLightPaint)
        topLightPaint.shader = null

        super.onDraw(canvas)
    }

    /** 中央 56dp 四角星按钮 */
    class StarButton(context: Context) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val star = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        init { setWillNotDraw(false) }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val cx = w / 2f; val cy = h / 2f
            val r = (if (w < h) w else h) / 2f

            // 外发光 24px
            glow.color = Color.parseColor("#4D5B7CFF")
            glow.setShadowLayer(Display.dp(context, 24f), 0f, 0f, Color.parseColor("#665B7CFF"))
            canvas.drawCircle(cx, cy, r * 0.92f, glow)
            glow.clearShadowLayer()

            // 渐变球体（145° 近似为左上→右下）
            paint.shader = LinearGradient(0f, 0f, w, h,
                intArrayOf(Color.parseColor(Theme.BALL_A), Color.parseColor(Theme.BALL_B),
                    Color.parseColor(Theme.BALL_C)), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r * 0.92f, paint)
            paint.shader = null

            // 5px 玻璃环
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = Display.dp(context, 5f)
            paint.color = Color.parseColor("#33FFFFFF")
            canvas.drawCircle(cx, cy, r * 0.92f, paint)
            paint.style = Paint.Style.FILL

            // 四角星 26dp
            drawStar(canvas, cx, cy, Display.dp(context, 13f))
        }

        private fun drawStar(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            val p = Path()
            val inner = r * 0.42f
            for (i in 0 until 8) {
                val rad = Math.toRadians((i * 45 - 90).toDouble())
                val rr = if (i % 2 == 0) r else inner
                val x = cx + (rr * Math.cos(rad)).toFloat()
                val y = cy + (rr * Math.sin(rad)).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            star.setShadowLayer(Display.dp(context, 3f), 0f, Display.dp(context, 1f),
                Color.parseColor("#66000000"))
            canvas.drawPath(p, star)
            star.clearShadowLayer()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }
}
