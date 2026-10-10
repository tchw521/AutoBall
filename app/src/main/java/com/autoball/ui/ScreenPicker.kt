package com.autoball.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.autoball.AB
import com.autoball.core.backend.ScreenResult
import com.autoball.core.util.Display
import com.autoball.float.FloatManager
import com.autoball.float.FloatWindows
import com.autoball.core.store.TemplateStore
import kotlin.math.roundToInt

/**
 * 屏幕取色 / 取图（统一组件，解决 R-101）。
 *
 * 「颜色存在」「图片存在」两种运行条件此前**只能手填十六进制或路径**——
 * 用户不知道目标色的准确色值，也无从生成模板图，等于这两个条件用不起来。
 *
 * 本组件先截当前屏，铺成全屏层，用户点一下即取色；区域模式框选后裁出模板图。
 * 两种模式共用同一份"截图 → 全屏层 → 交互 → 回调"骨架。
 */
object ScreenPicker {

    enum class Mode { COLOR, IMAGE, REGION }

    @Volatile private var view: PickView? = null
    @Volatile private var wm: WindowManager? = null
    @Volatile private var hostDialog: android.app.Dialog? = null
    private val handler = Handler(Looper.getMainLooper())

    /** 最近一次截图（供模板裁剪复用，避免同一次操作截两遍） */
    @Volatile private var lastBitmap: Bitmap? = null

    /**
     * @param onColor 取色回调：#RRGGBB
     * @param onImage 取图回调：模板图保存后的标识（存进条件的 v 字段）
     */
    fun pick(
        context: Context,
        activity: Activity?,
        mode: Mode,
        hostDialog: android.app.Dialog? = null,
        onColor: ((String) -> Unit)? = null,
        onImage: ((String) -> Unit)? = null,
        /** 区域模式回调：百分比 [左,上,右,下]（0–100） */
        onRegionPct: ((FloatArray) -> Unit)? = null
    ) {
        if (!Display.canDrawOverlay(context)) {
            Display.openOverlaySettings(context)
            return
        }
        removeNow()

        // 截图必须在悬浮层让出之后进行——否则会拍到本应用自己的浮窗
        this.hostDialog = hostDialog
        runCatching { hostDialog?.hide() }
        FloatManager.hideAll()
        FloatWindows.hideAll()
        handler.postDelayed({
            runCatching {
                activity?.startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
            // 再延迟一点，等桌面真正绘制出来
            handler.postDelayed({
                shoot(context, activity, mode, onColor, onImage, onRegionPct)
            }, 420)
        }, 80)
    }

    private fun shoot(context: Context, activity: Activity?, mode: Mode,
                      onColor: ((String) -> Unit)?, onImage: ((String) -> Unit)?,
                      onRegionPct: ((FloatArray) -> Unit)?) {
        val sr = runCatching { AB.router.screenshot(
            com.autoball.core.backend.ExecContext("pick")) }.getOrNull()
        val ok = sr as? ScreenResult.Ok
        if (ok == null) {
            restore(activity)
            val why = (sr as? ScreenResult.Unavailable)?.reason ?: "截图失败"
            Ui.toast(context, "无法取色：$why\n请先开启无障碍或 Shizuku")
            return
        }
        val bmp = Bitmap.createBitmap(ok.width, ok.height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(ok.pixels, 0, ok.width, 0, 0, ok.width, ok.height)
        lastBitmap = bmp
        showLayer(context.applicationContext, activity, bmp, mode,
            onColor, onImage, onRegionPct)
    }

    private fun showLayer(ctx: Context, activity: Activity?, bmp: Bitmap, mode: Mode,
                          onColor: ((String) -> Unit)?, onImage: ((String) -> Unit)?,
                          onRegionPct: ((FloatArray) -> Unit)?) {
        val onDone: () -> Unit = { restore(activity); removeNow() }
        // 网格与吸附此前是设置项里能开、代码里没人读的开关（只写不读）。
        // 现在真正接上：网格是视觉参考，吸附只在足够接近网格线时生效。
        val gridOn = com.autoball.AB.store.getBool("showGrid", false)
        val snapOn = com.autoball.AB.store.getBool("snapAlign", true)
        val layer = PickView(ctx, bmp, mode,
            onColor = { hex -> onColor?.invoke(hex); onDone() },
            onRegion = { l, t, r, b ->
                if (mode == Mode.REGION) {
                    // 区域模式：换算成屏幕百分比（截图即整屏，比例一致）
                    onRegionPct?.invoke(floatArrayOf(l, t, r, b))
                } else {
                    val ref = TemplateStore.save(bmp, l, t, r, b)
                    onImage?.invoke(ref)
                }
                onDone()
            },
            onCancel = onDone, showGrid = gridOn, snapAlign = snapOn,
            autoFind = com.autoball.AB.store.getBool("autoFind", false))

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSPARENT)
        p.gravity = Gravity.TOP or Gravity.START
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { manager.addView(layer, p) }
        view = layer
        wm = manager
    }

    private fun restore(activity: Activity?) {
        runCatching { hostDialog?.show() }
        hostDialog = null
        FloatWindows.restore()
        FloatManager.restore()
        val act = activity ?: return
        runCatching {
            act.startActivity(
                Intent(act, act::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun removeNow() {
        runCatching { view?.let { wm?.removeView(it) } }
        view = null; wm = null
    }

    private fun overlayType(): Int =
        if (android.os.Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

    // =====================================================================
    // 全屏取色 / 框选层
    // =====================================================================

    private class PickView(
        ctx: Context,
        private val bmp: Bitmap,
        private val mode: Mode,
        private val onColor: (String) -> Unit,
        private val onRegion: (Float, Float, Float, Float) -> Unit,
        private val onCancel: () -> Unit,
        /** 显示坐标网格（设置项 showGrid） */
        private val showGrid: Boolean,
        /** 吸附到网格线（设置项 snapAlign） */
        private val snapAlign: Boolean,
        /** 取色后自动识别该位置控件（设置项 autoFind） */
        private val autoFind: Boolean
    ) : FrameLayout(ctx) {

        companion object {
            /** 网格间距（屏幕百分比）：10% 一格，与百分比坐标体系对齐 */
            const val GRID_STEP = 10f
            /** 吸附触发距离（百分比）：离网格线超过这个距离就不吸附，避免坐标失真 */
            const val SNAP_TOL = 2.5f
        }

        /** 网格线覆盖层：只画不响应触摸 */
        private val grid = object : View(ctx) {
            private val paint = android.graphics.Paint().apply {
                color = Theme.inkAlpha(0.33f)
                strokeWidth = Display.dpInt(ctx, 1f).toFloat()
            }
            override fun onDraw(c: android.graphics.Canvas) {
                super.onDraw(c)
                if (!this@PickView.showGrid) return
                var i = GRID_STEP
                while (i < 100f) {
                    val x = width * i / 100f
                    c.drawLine(x, 0f, x, height.toFloat(), paint)
                    val y = height * i / 100f
                    c.drawLine(0f, y, width.toFloat(), y, paint)
                    i += GRID_STEP
                }
            }
        }

        private val preview = android.widget.ImageView(ctx).apply {
            setImageBitmap(bmp)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        private val magnifier = TextView(ctx).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Theme.rect(0xCC000000.toInt(), 10f, ctx)
            setPadding(Display.dpInt(ctx, 12f), Display.dpInt(ctx, 8f),
                Display.dpInt(ctx, 12f), Display.dpInt(ctx, 8f))
            visibility = View.GONE
        }
        private val tip = TextView(ctx).apply {
            text = when (mode) {
                Mode.COLOR -> "点一下屏幕取色"
                Mode.IMAGE -> "框选要匹配的区域"
                Mode.REGION -> "拖动框选检测区域"
            }
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Theme.rect(0xCC000000.toInt(), 12f, ctx)
            setPadding(Display.dpInt(ctx, 14f), Display.dpInt(ctx, 9f),
                Display.dpInt(ctx, 14f), Display.dpInt(ctx, 9f))
        }
        private val cancel = TextView(ctx).apply {
            text = "取消"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Theme.rect(0xCC000000.toInt(), 12f, ctx)
            setPadding(Display.dpInt(ctx, 16f), Display.dpInt(ctx, 9f),
                Display.dpInt(ctx, 16f), Display.dpInt(ctx, 9f))
            setOnClickListener { onCancel() }
        }

        /** 最近一次操作的屏幕百分比坐标（供 autoFind 查询控件） */
        private var lastPct: Pair<Float, Float> = 0f to 0f

        // 框选状态（像素）
        private var sx = 0f; private var sy = 0f
        private var ex = 0f; private var ey = 0f
        private var dragging = false

        private val box = View(ctx).apply {
            background = Theme.rect(Color.TRANSPARENT, 0f, ctx).apply {
                setStroke(Display.dpInt(ctx, 2f), Theme.info())
            }
            visibility = View.GONE
        }

        init {
            setBackgroundColor(Theme.scrim(0.40f))
            addView(preview, LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT))
            addView(grid, LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT))
            addView(box)

            val bar = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            bar.addView(tip)
            bar.addView(cancel)
            addView(bar, LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.BOTTOM
                bottomMargin = Display.dpInt(ctx, 24f) })
            addView(magnifier, LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = Display.dpInt(ctx, 40f) })
        }

        /**
         * 查询该屏幕百分比位置上的控件；查不到或无权限返回 null。
         *
         * 用百分比坐标而非像素——后端按当前屏幕换算，换机型也一致。
         */
        private fun nodeAt(pctX: Float, pctY: Float): String? = runCatching {
            com.autoball.service.AutoBallAccessibilityService.nodeAtPct(pctX, pctY)
        }.getOrNull()

        /** 把像素坐标吸附到最近的网格线；不够近就原样返回 */
        private fun snap(v: Float, size: Float): Float {
            if (size <= 0f) return v
            val pct = v / size * 100f
            val near = (pct / GRID_STEP).roundToInt() * GRID_STEP
            return if (kotlin.math.abs(pct - near) <= SNAP_TOL) near / 100f * size else v
        }

        override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
            when (ev.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    sx = ev.x; sy = ev.y; ex = sx; ey = sy
                    dragging = true
                    if (mode == Mode.IMAGE || mode == Mode.REGION) {
                        box.visibility = View.VISIBLE
                        layoutBox()
                    }
                    previewColor(ev.x, ev.y)
                    return true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    ex = ev.x; ey = ev.y
                    if (mode == Mode.IMAGE || mode == Mode.REGION) layoutBox()
                    previewColor(ev.x, ev.y)
                    return true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    dragging = false
                    // 吸附：把坐标对齐到最近的网格线。
                    // 只在**足够接近**时才吸附——否则等于凭空挪动用户的选择，
                    // 且挪动后的坐标与用户看到的界面不符，比不吸附更糟。
                    val (rawX, rawY) = ev.x to ev.y
                    val px = if (snapAlign) snap(rawX, width.toFloat()) else rawX
                    val py = if (snapAlign) snap(rawY, height.toFloat()) else rawY
                    lastPct = px / width.coerceAtLeast(1) * 100f to
                        py / height.coerceAtLeast(1) * 100f
                    if (mode == Mode.COLOR) {
                        val hex = colorAt(px, py)
                        if (hex == null) { onCancel(); return true }
                        // 自动识别控件：很多脚本用坐标点击，但该位置其实有稳定控件——
                        // 换成节点匹配后，界面缩放/换机型都不会失效。提示而非代劳。
                        if (autoFind) {
                            val info = nodeAt(lastPct.first, lastPct.second)
                            if (!info.isNullOrBlank()) {
                                Ui.toast(context, "该位置控件：$info\n可改用「节点匹配」更稳")
                            }
                        }
                        onColor(hex)
                    } else {
                        val l = minOf(sx, ex); val t = minOf(sy, ey)
                        val r = maxOf(sx, ex); val b = maxOf(sy, ey)
                        // 太小的框多半是误触，不当作有效选择
                        if (r - l < 20 || b - t < 20) {
                            Ui.toast(context, "框选区域太小，请重新框选")
                            box.visibility = View.GONE
                        } else if (mode == Mode.REGION) {
                            // 截图即整屏，视图铺满屏幕 → 直接按视图尺寸归一为百分比
                            val vw = width.toFloat().coerceAtLeast(1f)
                            val vh = height.toFloat().coerceAtLeast(1f)
                            onRegion(l / vw * 100f, t / vh * 100f,
                                r / vw * 100f, b / vh * 100f)
                        } else {
                            // 换算回图片坐标（预览是 FIT_CENTER，需按缩放比还原）
                            val m = imgRect()
                            val fx = bmp.width.toFloat() / m.width()
                            val fy = bmp.height.toFloat() / m.height()
                            onRegion((l - m.left) * fx, (t - m.top) * fy,
                                (r - m.left) * fx, (b - m.top) * fy)
                        }
                    }
                    return true
                }
            }
            return super.dispatchTouchEvent(ev)
        }

        /** 预览图在屏幕上的实际矩形（FIT_CENTER 后） */
        private fun imgRect(): android.graphics.RectF {
            val vw = width.toFloat(); val vh = height.toFloat()
            val s = minOf(vw / bmp.width, vh / bmp.height)
            val w = bmp.width * s; val h = bmp.height * s
            return android.graphics.RectF((vw - w) / 2, (vh - h) / 2,
                (vw - w) / 2 + w, (vh - h) / 2 + h)
        }

        private fun colorAt(vx: Float, vy: Float): String? {
            val m = imgRect()
            val bx = ((vx - m.left) / m.width() * bmp.width).toInt()
            val by = ((vy - m.top) / m.height() * bmp.height).toInt()
            if (bx !in 0 until bmp.width || by !in 0 until bmp.height) return null
            val c = bmp.getPixel(bx, by)
            return "#%06X".format(0xFFFFFF and c)
        }

        private fun previewColor(vx: Float, vy: Float) {
            val hex = colorAt(vx, vy) ?: return
            val c = runCatching { Color.parseColor(hex) }.getOrNull() ?: return
            magnifier.visibility = View.VISIBLE
            magnifier.text = "$hex   R${Color.red(c)} G${Color.green(c)} B${Color.blue(c)}"
            magnifier.setBackgroundColor(c)
            magnifier.setTextColor(
                if ((Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114)
                    / 1000 > 128) Color.BLACK else Color.WHITE)
        }

        private fun layoutBox() {
            val l = minOf(sx, ex).toInt(); val t = minOf(sy, ey).toInt()
            box.layout(l, t, maxOf(sx, ex).toInt(), maxOf(sy, ey).toInt())
        }
    }
}
