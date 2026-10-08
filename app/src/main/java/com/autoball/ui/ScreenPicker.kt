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

    enum class Mode { COLOR, IMAGE }

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
        onImage: ((String) -> Unit)? = null
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
            handler.postDelayed({ shoot(context, activity, mode, onColor, onImage) }, 420)
        }, 80)
    }

    private fun shoot(context: Context, activity: Activity?, mode: Mode,
                      onColor: ((String) -> Unit)?, onImage: ((String) -> Unit)?) {
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
        showLayer(context.applicationContext, activity, bmp, mode, onColor, onImage)
    }

    private fun showLayer(ctx: Context, activity: Activity?, bmp: Bitmap, mode: Mode,
                          onColor: ((String) -> Unit)?, onImage: ((String) -> Unit)?) {
        val layer = PickView(ctx, bmp, mode,
            onColor = { hex ->
                onColor?.invoke(hex)
                restore(activity)
                removeNow()
            },
            onRegion = { l, t, r, b ->
                val ref = TemplateStore.save(bmp, l, t, r, b)
                onImage?.invoke(ref)
                restore(activity)
                removeNow()
            },
            onCancel = { restore(activity); removeNow() })

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
        runCatching {
            activity?.startActivity(Intent(ctx()).setClassName(
                ctx().packageName, activity::class.java.name
            ).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun ctx(): Context = AB.ctx

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
        private val onCancel: () -> Unit
    ) : FrameLayout(ctx) {

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
            text = if (mode == Mode.COLOR) "点一下屏幕取色" else "框选要匹配的区域"
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

        // 框选状态（像素）
        private var sx = 0f; private var sy = 0f
        private var ex = 0f; private var ey = 0f
        private var dragging = false

        private val box = View(ctx).apply {
            background = Theme.rect(Color.TRANSPARENT, 0f, ctx).apply {
                setStroke(Display.dpInt(ctx, 2f), Color.parseColor("#0EA5E9"))
            }
            visibility = View.GONE
        }

        init {
            setBackgroundColor(Color.parseColor("#66000000"))
            addView(preview, LayoutParams(LayoutParams.MATCH_PARENT,
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

        override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
            when (ev.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    sx = ev.x; sy = ev.y; ex = sx; ey = sy
                    dragging = true
                    if (mode == Mode.IMAGE) {
                        box.visibility = View.VISIBLE
                        layoutBox()
                    }
                    previewColor(ev.x, ev.y)
                    return true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    ex = ev.x; ey = ev.y
                    if (mode == Mode.IMAGE) layoutBox()
                    previewColor(ev.x, ev.y)
                    return true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    dragging = false
                    val px = ev.x; val py = ev.y
                    if (mode == Mode.COLOR) {
                        val hex = colorAt(px, py)
                        if (hex != null) onColor(hex) else onCancel()
                    } else {
                        val l = minOf(sx, ex); val t = minOf(sy, ey)
                        val r = maxOf(sx, ex); val b = maxOf(sy, ey)
                        // 太小的框多半是误触，不当作有效选择
                        if (r - l < 20 || b - t < 20) {
                            Ui.toast(context, "框选区域太小，请重新框选")
                            box.visibility = View.GONE
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
