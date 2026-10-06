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
 * 中央 56dp 渐变四角星凸起按钮（顶出导航上沿 23dp）。
 *
 * 实现说明：Android 12 有 RenderEffect，但它只能模糊 View 自身绘制内容，
 * 无法模糊其下方内容；真正的背景模糊由 Activity 的 window.setBackgroundBlurRadius 提供，
 * 低版本自动降级为高不透明度纯色兜底（需求四：兼容）。
 */
class LiquidNavView(
    context: Context,
    private val onSelect: (Int) -> Unit,
    private val onCreate: () -> Unit
) : FrameLayout(context) {

    companion object {
        val TABS = arrayOf("脚本", "编辑", "制作", "市场", "我的")
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val topLightPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val tabs = ArrayList<TextView>()
    private var selected = 0

    private val starBtn: StarButton

    init {
        setWillNotDraw(false)
        setPadding(Display.dpInt(context, 8f), 0, Display.dpInt(context, 8f), 0)
        // 双层外投影
        elevation = Display.dp(context, 12f)

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in TABS.indices) {
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            val tv = TextView(context).apply {
                text = TABS[i]
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Theme.textSec())
                setOnClickListener { if (i != 2) select(i) }
            }
            tabs.add(tv)
            row.addView(tv, lp)
        }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        starBtn = StarButton(context).apply {
            setOnClickListener { onCreate() }
        }
        val size = Display.dpInt(context, 56f)
        val lp = LayoutParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = -Display.dpInt(context, 26f)
        }
        addView(starBtn, lp)

        select(0)
    }

    fun select(index: Int) {
        selected = index
        for (i in tabs.indices) {
            val tv = tabs[i]
            tv.setTextColor(if (i == index) Theme.textPri() else Theme.textSec())
            tv.textSize = if (i == index) 12f else 11f
            // 选中态：图标后垫一块更小的模糊胶囊（像手指按在凝胶上陷下去）
            tv.background = if (i == index)
                Theme.bubble(context, Color.parseColor(if (Theme.isDark()) "#33FFFFFF" else "#1F000000"), 13f)
            else null
            tv.visibility = if (i == 2) View.INVISIBLE else View.VISIBLE
        }
        invalidate()
        if (index != 2) onSelect(index)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = Display.dp(context, 26f)

        // 半透明凝胶底
        bgPaint.shader = LinearGradient(0f, 0f, w, h,
            intArrayOf(Color.parseColor(if (Theme.isDark()) "#5C1C1832" else "#D8FFFFFF"),
                Color.parseColor(if (Theme.isDark()) "#471B1730" else "#C8FFFFFF")),
            null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(0f, 0f, w, h, r, r, bgPaint)
        bgPaint.shader = null

        // 内高光 inset 0 1px 0 rgba(255,255,255,.28)
        innerPaint.style = Paint.Style.STROKE
        innerPaint.strokeWidth = Display.dp(context, 1f)
        innerPaint.color = Color.parseColor(if (Theme.isDark()) "#47FFFFFF" else "#FFFFFFFF")
        canvas.drawRoundRect(Display.dp(context, 0.5f), Display.dp(context, 0.5f),
            w - Display.dp(context, 0.5f), h - Display.dp(context, 0.5f), r, r, innerPaint)

        // 顶部流光：横向渐变 92% 白
        topLightPaint.shader = LinearGradient(0f, 0f, w, 0f,
            intArrayOf(Color.TRANSPARENT, Color.parseColor("#EBFFFFFF"), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        topLightPaint.strokeWidth = Display.dp(context, 1.2f)
        canvas.drawLine(Display.dp(context, 14f), Display.dp(context, 1f),
            w - Display.dp(context, 14f), Display.dp(context, 1f), topLightPaint)
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
