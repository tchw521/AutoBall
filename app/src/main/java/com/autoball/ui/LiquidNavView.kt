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
        // ---- onDraw 热路径色值预解析 ----
        //
        // **这里的字面量是全工程唯一刻意保留的一批**，理由有两条：
        // 1) onDraw 每帧执行，逐帧 parseColor 会明显掉帧；这里在类加载时解析一次。
        //    导航栏带呼吸动画，是常驻重绘视图，不能改成运行时取色。
        // 2) 换肤**是生效的**：每个色都备了 D_/L_ 两份，onDraw 里按 Theme.isDark()
        //    选取。所以不要把这里当成"没走主题"的欠账去改成 Theme.xxx()——
        //    那样会在每帧引入 parseColor / 函数调用，反而造成掉帧。
        //    真正的欠账是"写死单一颜色、不判断主题"的那几处，已清理完毕。
        private val GL_D_TOP = Color.parseColor("#3DFFFFFF")
        private val GL_D_MID = Color.parseColor("#14FFFFFF")
        private val GL_D_BOT = Color.parseColor("#0AFFFFFF")
        private val GL_L_TOP = Color.parseColor("#F2FFFFFF")
        private val GL_L_MID = Color.parseColor("#BFFFFFFF")
        private val GL_L_BOT = Color.parseColor("#A3FFFFFF")

        private val BODY_D = Color.parseColor("#B31E173A")
        private val BODY_L = Color.parseColor("#8CFFFFFF")

        private val SHEEN_D = Color.parseColor("#26FFFFFF")
        private val SHEEN_L = Color.parseColor("#8FFFFFFF")
        private val BLUE_D = Color.parseColor("#1F2F6BFF")
        private val BLUE_L = Color.parseColor("#33FFFFFF")

        private val RIM_T_D = Color.parseColor("#5CFFFFFF")
        private val RIM_T_L = Color.parseColor("#FFFFFFFF")
        private val RIM_B_D = Color.parseColor("#14FFFFFF")
        private val RIM_B_L = Color.parseColor("#14000000")

        private val SPEC_D = Color.parseColor("#3DFFFFFF")
        private val SPEC_L = Color.parseColor("#B3FFFFFF")

        private val HALO = Color.parseColor("#7A7DD3FC")

        // 以下三个原先写在 onDraw 里，导致**每帧** parseColor。
        // 导航栏带呼吸动画，是常驻重绘视图，必须预解析。
        private val GLOW_BODY = Color.parseColor("#7C0EA5E9")
        private val HL_TOP = Color.parseColor("#B8FFFFFF")
        private val PLUS_SHADOW = Color.parseColor("#590E7FB8")
        val TABS = arrayOf("脚本", "编辑", "", "市场", "我的")
        /** 导航图标类型；0=脚本 1=编辑 2=中央 3=市场 4=我的 */
        private val ICONS = intArrayOf(0, 1, 2, 3, 4)

        /** 凝胶本体高度 */
        private const val BAR_DP = 64f
        /** FAB 顶出上沿的高度 */
        private const val OVER_DP = 20f
        /** FAB 直径 */
        private const val FAB_DP = 56f

        /** 容器总高 = 顶出部分 + 凝胶本体 */
        fun heightDp(): Float = BAR_DP + OVER_DP
        /** 导航离屏幕底边的距离：半框避让时要把这段也算进去 */
        const val BOTTOM_MARGIN_DP = 14f
    }

    // 导航栏带呼吸动画 → onDraw 每帧执行。两个渐变原本每帧 new，

    // 现按依赖参数缓存（尺寸/主题变化时才重建）

    private var gelGrad: android.graphics.Shader? = null

    private var gelTop = -1f

    private var gelH = -1f

    private var gelDark: Boolean? = null

    private var specGrad: android.graphics.Shader? = null

    private var specW = -1f

    private var specDark: Boolean? = null


    private val gel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spec = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)

    private val tabs = ArrayList<TextView>()
    private val icons = ArrayList<IconView>()
    private var selected = 0

    /** 液态呼吸相位：v3 gelMorph 9s 周期。幅度极小（32↔34dp），不刺眼 */
    @Volatile
    private var morphT = 0f

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
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                // 只有用户真实点击才通知外部。
                // 放进 select() 会形成 select → showPage → select 无限递归，启动即栈溢出。
                setOnClickListener {
                    if (i == 2) onCreate()
                    else { select(i); onSelect(i) }
                }
            }
            val iv = IconView(context, ICONS[i]).apply {
                val lp = LinearLayout.LayoutParams(Display.dpInt(context, 21f),
                    Display.dpInt(context, 21f))
                lp.bottomMargin = Display.dpInt(context, 2f)
                layoutParams = lp
                if (i == 2) visibility = View.INVISIBLE   // 中央由 FAB 承担
            }
            icons.add(iv)
            cell.addView(iv)
            val tv = TextView(context).apply {
                text = TABS[i]
                textSize = 10f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 0, 0, Display.dpInt(context, 9f))
                setTextColor(Theme.textSec())
            }
            tabs.add(tv)
            cell.addView(tv)
            row.addView(cell, LinearLayout.LayoutParams(0,
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
        startMorph()
    }

    fun select(index: Int) {
        selected = index
        for (i in tabs.indices) {
            val tv = tabs[i]
            val isSel = (i == index && i != 2)
            tv.setTextColor(if (isSel) Theme.pri() else Theme.textSec())
            icons.getOrNull(i)?.tint(if (isSel) Theme.pri() else Theme.textSec())
            tv.textSize = if (isSel) 10.5f else 10f
            tv.visibility = View.VISIBLE
        }
        // 不在此回调 onSelect：select() 也被外部同步调用，回传会形成递归
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return   // 尺寸为 0 时 Shader 会抛异常

        val barH = Display.dp(context, BAR_DP)
        val top = h - barH
        // 液态：圆角在 32↔34dp 之间极缓呼吸（v3 gelMorph 9s）
        val morph = (kotlin.math.sin(morphT * 2.0 * Math.PI).toFloat() + 1f) / 2f
        val r = Display.dp(context, Theme.TABBAR_R + 2f * morph)
        val dark = Theme.isDark()

        // ---- 液态玻璃：静置的折射质感，不做任何循环动画 ----
        // 玻璃基底：竖向三段，上缘更亮（模拟环境光在弧面顶部的聚集）
        if (gelTop != top || gelH != h || gelDark != dark) {
            gelTop = top; gelH = h; gelDark = dark
            gelGrad = LinearGradient(0f, top, 0f, h,
                intArrayOf(
                    if (dark) GL_D_TOP else GL_L_TOP,
                    if (dark) GL_D_MID else GL_L_MID,
                    if (dark) GL_D_BOT else GL_L_BOT),
                floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP)
        }
        gel.shader = gelGrad
        canvas.drawRoundRect(0f, top, w, h, r, r, gel)
        gel.shader = null

        // 玻璃本体色（含不透明度，模拟磨砂玻璃后的底色）
        gel.color = if (dark) BODY_D else BODY_L
        canvas.drawRoundRect(0f, top, w, h, r, r, gel)

        // 降低毛玻璃：跳过环境反射与镜面折射（两次 RadialGradient + 一次
        // LinearGradient 绘制），改用纯半透明，观感接近但开销大幅下降。
        if (!Perf.lowBlur()) {

        // 左侧大面积环境反射（静置高光，不移动）
        canvas.save()
        canvas.clipRect(0f, top, w, h)
        sheen.shader = android.graphics.RadialGradient(
            w * 0.18f, top - h * 0.18f, h * 1.35f,
            if (dark) SHEEN_D else SHEEN_L,
            Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(0f, top, w, h, r, r, sheen)
        sheen.shader = null
        // 右下蓝调环境光
        sheen.shader = android.graphics.RadialGradient(
            w * 1.02f, h * 1.12f, h * 0.95f,
            if (dark) BLUE_D else BLUE_L,
            Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(0f, top, w, h, r, r, sheen)
        sheen.shader = null
        canvas.restore()

        // 玻璃边缘：上缘 1px 亮线（折射），下缘极淡（厚度）
        rim.strokeWidth = Display.dp(context, 1f)
        rim.color = if (dark) RIM_T_D else RIM_T_L
        canvas.drawLine(Display.dp(context, 18f), top + Display.dp(context, 1f),
            w - Display.dp(context, 18f), top + Display.dp(context, 1f), rim)
        rim.color = if (dark) RIM_B_D else RIM_B_L
        canvas.drawLine(Display.dp(context, 18f), h - Display.dp(context, 1f),
            w - Display.dp(context, 18f), h - Display.dp(context, 1f), rim)

        // 顶部一道极细的镜面反射点（静置，非扫光）
        spec.strokeWidth = Display.dp(context, 1.2f)
        if (specW != w || specDark != dark) {
            specW = w; specDark = dark
            specGrad = LinearGradient(0f, 0f, w, 0f,
                intArrayOf(Color.TRANSPARENT,
                    if (dark) SPEC_D else SPEC_L,
                    Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        }
        spec.shader = specGrad
        canvas.drawLine(Display.dp(context, 14f), top + Display.dp(context, 2f),
            w - Display.dp(context, 14f), top + Display.dp(context, 2f), spec)
        spec.shader = null
        }

        // FAB 液体融合光晕（v3 .fabslot::before：68dp 圆，rgba(125,211,252,.48) → 透明）
        halo.shader = android.graphics.RadialGradient(
            w / 2f, top - Display.dp(context, 2f), Display.dp(context, 34f),
            HALO, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(w / 2f, top - Display.dp(context, 2f),
            Display.dp(context, 34f), halo)
        halo.shader = null

        super.onDraw(canvas)
    }

    /**
     * 极缓呼吸：9s 一轮，仅轻微改变圆角，形成"液体表面张力"的观感。
     *
     * 流畅模式（[Perf.perf]）下**直接不启动**——导航栏是常驻视图，
     * 每帧 invalidate 是主要掉帧来源，关掉后观感损失很小。
     */
    private fun startMorph() {
        if (Perf.perf()) { morphT = 0f; invalidate(); return }
        post(object : Runnable {
            override fun run() {
                morphT += 1f / (9f * 30f)      // 9 秒一轮，约 30fps
                if (morphT > 1f) morphT -= 1f
                invalidate()
                postDelayed(this, 33L)
            }
        })
    }

    /**
     * 导航图标：按类型直接自绘几何图形。
     *
     * 不引入图片资源（避免增包），也不用 `android.util.PathParser`
     * （它属于 androidx，本工程零第三方依赖）——四个图标都是简单几何形，
     * 直接画反而更可控。
     */
    class IconView(context: Context, private val kind: Int) : View(context) {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = Display.dp(context, 1.7f)
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            color = Theme.textSec()
        }

        fun tint(c: Int) { p.color = c; invalidate() }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val u = width / 24f          // 24×24 逻辑画布
            val px = { v: Float -> v * u }
            when (kind) {
                0 -> { // 脚本：列表三行
                    for (k in 0..2) {
                        val y = px(7f + k * 5f)
                        canvas.drawLine(px(4f), y, px(20f - k * 5f), y, p)
                    }
                }
                1 -> { // 编辑：铅笔
                    canvas.drawLine(px(5f), px(19f), px(17f), px(7f), p)
                    canvas.drawLine(px(15f), px(5f), px(19f), px(9f), p)
                    canvas.drawLine(px(5f), px(19f), px(7f), px(21f), p)
                }
                3 -> { // 市场：商店（屋顶 + 屋身）
                    canvas.drawLine(px(3f), px(10f), px(12f), px(3f), p)
                    canvas.drawLine(px(12f), px(3f), px(21f), px(10f), p)
                    canvas.drawLine(px(5f), px(10f), px(5f), px(20f), p)
                    canvas.drawLine(px(19f), px(10f), px(19f), px(20f), p)
                    canvas.drawLine(px(5f), px(20f), px(19f), px(20f), p)
                }
                4 -> { // 我的：人（头 + 肩）
                    canvas.drawCircle(px(12f), px(8f), px(4f), p)
                    canvas.drawLine(px(5f), px(20f), px(5f + 0f), px(20f), p)
                    val r = android.graphics.RectF(px(4f), px(14f), px(20f), px(30f))
                    canvas.drawArc(r, 180f, 180f, false, p)
                }
            }
        }
    }

    /** 中央 56dp 天蓝渐变加号按钮 */
    class FabButton(context: Context) : View(context) {

        // 高光渐变缓存（半径变化时才重建）

        // 两个渐变都缓存：onDraw 每帧执行，此前每帧各 new 一个 Shader，
        // 呼吸动画下等于持续制造短命对象（掉帧 + GC 压力）
        private var hlShader: android.graphics.Shader? = null
        private var bodyGrad: android.graphics.Shader? = null
        private var bodyH = -1f
        private var hlR = -1f
        private var hlCx = -1f
        private var hlCy = -1f


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
                Theme.fabGlow())
            glow.color = GLOW_BODY
            canvas.drawCircle(cx, cy, r * 0.98f, glow)
            glow.clearShadowLayer()

            // 天蓝渐变球体（155° 近似为自上而下）
            if (bodyH != h) {
                bodyH = h
                bodyGrad = LinearGradient(0f, 0f, 0f, h, Theme.FAB_STOPS, null,
                    Shader.TileMode.CLAMP)
            }
            body.shader = bodyGrad
            canvas.drawCircle(cx, cy, r * 0.96f, body)
            body.shader = null

            // 顶部液态高光。
            // 渐变坐标必须在**画布绝对坐标**上（以圆心 cx,cy 为基准）——
            // 只用 r 推相对值会让高光跑到视图左上角。
            if (hlR != r || hlCx != cx || hlCy != cy) {
                hlR = r; hlCx = cx; hlCy = cy
                hlShader = android.graphics.RadialGradient(
                    cx - r * 0.36f, cy - r * 0.76f, r * 1.1f,
                    HL_TOP, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            }
            body.shader = hlShader
            canvas.drawCircle(cx, cy, r * 0.96f, body)
            body.shader = null

            // 白色描边
            ring.style = Paint.Style.STROKE
            ring.strokeWidth = Display.dp(context, 1f)
            ring.color = Theme.fabStroke()
            canvas.drawCircle(cx, cy, r * 0.96f, ring)
            ring.style = Paint.Style.FILL

            drawPlus(canvas, cx, cy, Display.dp(context, 13f))
        }

        /**
         * 加号（设计稿：中间凸起 Fab 为「＋」，四角为圆角）。
         *
         * 此前画的是四角星，与设计稿的「＋」不符；同时星形在小尺寸下
         * 尖角容易糊成一团，加号在 56dp 上更清晰。
         */
        private fun drawPlus(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            val w = r * 0.30f          // 笔画半宽
            val len = r                 // 笔画半长
            star.setShadowLayer(Display.dp(context, 2f), 0f, Display.dp(context, 1f),
                PLUS_SHADOW)
            canvas.drawRoundRect(cx - len, cy - w, cx + len, cy + w,
                w, w, star)
            canvas.drawRoundRect(cx - w, cy - len, cx + w, cy + len,
                w, w, star)
            star.clearShadowLayer()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }
}
