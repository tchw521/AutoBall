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
 * 底部导航（UI 设计方案 v3）。
 *
 * 规格：left/right 12dp、bottom 14dp、高 64dp、圆角 32dp 胶囊；
 * 半透明凝胶底 + 顶/底双层内高光 + 双层外投影；中央 56dp **天蓝渐变四角星** FAB，
 * top -18dp 凸起于导航栏，「制作」标签落在 FAB 正下方、栏内。
 *
 * 关键实现约束：**不使用负 margin**。
 * 负 margin 依赖父容器关闭裁剪，各家 ROM 对 elevation outline 处理不一致，
 * 圆钮上半截会被切掉（v0.4.2 已踩坑）。本版容器自身预留 20dp 顶部空间，
 * 所有子 View 都在边界内，任何 ROM 都裁不到。
 */
class LiquidNavView(
    context: Context,
    private val onSelect: (Int) -> Unit,
    private val onCreate: () -> Unit
) : FrameLayout(context) {

    companion object {
        val TABS = arrayOf("脚本", "编辑", "制作", "市场", "我的")

        /** 凝胶本体高度 */
        private const val BAR_DP = 64f
        /** FAB 顶出上沿的高度 */
        private const val OVER_DP = 20f
        /** FAB 直径 */
        private const val FAB_DP = 56f

        /** 容器总高 = 顶出部分 + 凝胶本体 */
        fun heightDp(): Float = BAR_DP + OVER_DP
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val innerTop = Paint(Paint.ANTI_ALIAS_FLAG)
    private val innerBottom = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val tabs = ArrayList<TextView>()
    private var selected = 0
    @Volatile
    private var flowT = -1f

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        elevation = Display.dp(context, 12f)

        val barH = Display.dpInt(context, BAR_DP)

        // 文字行：贴在凝胶本体（下方 64dp）内
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Display.dpInt(context, 8f), 0, Display.dpInt(context, 8f), 0)
        }
        for (i in TABS.indices) {
            val tv = TextView(context).apply {
                text = TABS[i]
                textSize = 10f
                setTypeface(null, android.graphics.Typeface.BOLD)
                if (i == 2) {
                    // 「制作」标签：落在 FAB 正下方、导航栏内部
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    setPadding(0, 0, 0, Display.dpInt(context, 6f))
                } else {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    setPadding(0, 0, 0, Display.dpInt(context, 11f))
                }
                setTextColor(Theme.textSec())
                // 只有用户真实点击才通知外部。
                // 放进 select() 会形成 select → showPage → select 无限递归，启动即栈溢出。
                setOnClickListener { if (i != 2) { select(i); onSelect(i) } }
            }
            tabs.add(tv)
            row.addView(tv, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, barH).apply {
            gravity = Gravity.BOTTOM
        })

        // 中央 FAB：容器顶部，天然顶出凝胶上沿
        addView(FabButton(context).apply {
            setOnClickListener { onCreate() }
        }, LayoutParams(Display.dpInt(context, FAB_DP),
            Display.dpInt(context, FAB_DP)).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        })

        select(0)
    }

    fun select(index: Int) {
        selected = index
        for (i in tabs.indices) {
            val tv = tabs[i]
            val isSel = (i == index && i != 2)
            tv.setTextColor(if (isSel) Theme.pri() else Theme.textSec())
            tv.textSize = if (isSel) 10.5f else 10f
            tv.visibility = View.VISIBLE
        }
        // 不在此回调 onSelect：select() 也被外部同步调用，回传会形成递归
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return   // 尺寸为 0 时 LinearGradient 会抛异常

        val barH = Display.dp(context, BAR_DP)
        val top = h - barH
        val r = Display.dp(context, 32f)
        val dark = Theme.isDark()

        // 凝胶底：竖向三段 + 半透明
        bgPaint.shader = LinearGradient(0f, top, 0f, h,
            intArrayOf(
                Color.parseColor(if (dark) "#33FFFFFF" else "#EBFFFFFF"),
                Color.parseColor(if (dark) "#12FFFFFF" else "#A8FFFFFF"),
                Color.parseColor(if (dark) "#08FFFFFF" else "#8CFFFFFF")),
            floatArrayOf(0f, 0.38f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(0f, top, w, h, r, r, bgPaint)
        bgPaint.shader = null
        bgPaint.color = Color.parseColor(if (dark) "#991E173A" else "#59FFFFFF")
        canvas.drawRoundRect(0f, top, w, h, r, r, bgPaint)

        // 顶部流光：缓慢横向扫过（v3 gelflow）
        if (flowT < 0f) { flowT = 0f; startFlow() }
        val seg = w * 0.6f
        val cx = -seg + flowT * (w + seg)
        flowPaint.shader = LinearGradient(cx, 0f, cx + seg, 0f,
            intArrayOf(Color.TRANSPARENT,
                Color.parseColor(if (dark) "#38FFFFFF" else "#66FFFFFF"),
                Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        canvas.save()
        canvas.clipRect(0f, top, w, h)
        canvas.translate(cx, 0f)
        canvas.drawRect(0f, top, seg, h, flowPaint)
        canvas.restore()
        flowPaint.shader = null

        // 内高光：顶 inset 1px 白 30%，底 inset 1px 白 8%
        innerTop.color = Color.parseColor(if (dark) "#4DFFFFFF" else "#FFFFFFFF")
        canvas.drawLine(Display.dp(context, 16f), top + Display.dp(context, 1f),
            w - Display.dp(context, 16f), top + Display.dp(context, 1f), innerTop)
        innerBottom.color = Color.parseColor(if (dark) "#14FFFFFF" else "#0F110C2E")
        canvas.drawLine(Display.dp(context, 16f), h - Display.dp(context, 1f),
            w - Display.dp(context, 16f), h - Display.dp(context, 1f), innerBottom)

        super.onDraw(canvas)
    }

    private fun startFlow() {
        post(object : Runnable {
            override fun run() {
                flowT += 0.012f
                if (flowT > 1f) flowT = -0.2f
                invalidate()
                postDelayed(this, 32L)
            }
        })
    }

    /** 中央 56dp 天蓝渐变四角星 */
    class FabButton(context: Context) : View(context) {

        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val body = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
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

            // 液体融合光晕
            glow.setShadowLayer(Display.dp(context, 12f), 0f, 0f,
                Color.parseColor(Theme.FAB_GLOW))
            glow.color = Color.parseColor("#7C0EA5E9")
            canvas.drawCircle(cx, cy, r * 0.98f, glow)
            glow.clearShadowLayer()

            // 天蓝渐变球体（155° 近似为自上而下）
            body.shader = LinearGradient(0f, 0f, 0f, h, Theme.FAB_STOPS, null,
                Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r * 0.96f, body)
            body.shader = null

            // 顶部液态高光
            body.shader = android.graphics.RadialGradient(
                cx - r * 0.36f, cy - r * 0.76f, r * 1.1f,
                Color.parseColor("#B8FFFFFF"), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r * 0.96f, body)
            body.shader = null

            // 白色描边
            ring.style = Paint.Style.STROKE
            ring.strokeWidth = Display.dp(context, 1f)
            ring.color = Color.parseColor(Theme.FAB_STROKE)
            canvas.drawCircle(cx, cy, r * 0.96f, ring)
            ring.style = Paint.Style.FILL

            drawStar(canvas, cx, cy, Display.dp(context, 14f))
        }

        private fun drawStar(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            val p = Path()
            val inner = r * 0.40f
            for (i in 0 until 8) {
                val rad = Math.toRadians((i * 45 - 90).toDouble())
                val rr = if (i % 2 == 0) r else inner
                val x = cx + (rr * Math.cos(rad)).toFloat()
                val y = cy + (rr * Math.sin(rad)).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            star.setShadowLayer(Display.dp(context, 2f), 0f, Display.dp(context, 1f),
                Color.parseColor("#590E7FB8"))
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
