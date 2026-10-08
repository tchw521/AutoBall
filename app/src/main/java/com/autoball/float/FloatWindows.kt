package com.autoball.float

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import com.autoball.core.util.Display

/**
 * 悬浮窗统一管理（统一组件）。
 *
 * 之前每个悬浮窗各自 addView / removeView，于是三件事没人负责：
 * 1. **尺寸**：写死 dp，横屏时窗口可能比屏幕还高；
 * 2. **层叠**：后开的窗口被旧窗口盖住；
 * 3. **让出屏幕**：选点/录制要隐藏本应用全部界面，各自只记得自己那一层。
 *
 * 这里统一收口：
 *
 * - [widthDp]：竖屏取屏宽 1/2，横屏取屏宽 1/4。
 *   横屏时竖屏尺寸会顶满高度，必须按比例收窄。
 * - **窗口栈**：[add] 入栈、[remove] 出栈；新窗口后 add，天然压在旧窗口之上。
 * - [hideAll] / [restore]：选点与录制期间把本应用界面整体让出，结束复原。
 *
 * 说明：TYPE_APPLICATION_OVERLAY 同为系统窗口层，
 * 同一 WindowManager 下后 addView 的 z 序更高，无需额外 flag。
 */
object FloatWindows {

    private data class Entry(val view: View, var params: WindowManager.LayoutParams)

    private val handler = Handler(Looper.getMainLooper())
    private val stack = ArrayList<Entry>()
    private var wm: WindowManager? = null
    /** 让出屏幕期间被摘下的窗口，用于 [restore] */
    private val stashed = ArrayList<Entry>()

    private fun manager(ctx: Context): WindowManager {
        val m = wm ?: (ctx.applicationContext
            .getSystemService(Context.WINDOW_SERVICE) as WindowManager)
        wm = m
        return m
    }

    /**
     * 窗口宽度（dp）。
     *
     * 竖屏：屏宽 1/2；横屏：屏宽 1/4（横屏时竖屏尺寸会顶满高度）。
     *
     * **注意单位**：[Display.screenSize] 返回的是**像素**，而 WindowManager.LayoutParams
     * 的宽高也是像素——但这里对外承诺的是 dp，调用方会用 [Display.dpInt] 再乘密度。
     * 上一版直接拿像素当 dp 用，等于把窗口放大了一个密度倍数（约 2.6 倍），
     * 结果窗口比屏幕还宽，底部按钮被裁到屏幕外——这正是「只剩一个按钮」的原因。
     * 这里先按密度换算回 dp 再取比例。
     */
    fun widthDp(ctx: Context): Float {
        val sz = Display.screenSize(ctx)
        val den = Display.density(ctx).takeIf { it > 0f } ?: 1f
        val wDp = sz.x / den
        val hDp = sz.y / den
        val landscape = sz.x > sz.y
        val w = wDp / if (landscape) 4f else 2f
        // 下界保证内容放得下；上界保证窗口不会顶满屏幕
        return w.coerceIn(240f, (if (landscape) hDp else wDp) - 24f)
    }

    /**
     * 窗口最大高度（dp）：横屏时收窄，避免窗口顶满屏幕。
     * 竖屏 78%，横屏 70%。
     */
    fun maxHeightRatio(ctx: Context): Float {
        val sz = Display.screenSize(ctx)
        return if (sz.x > sz.y) 0.70f else 0.78f
    }

    fun isLandscape(ctx: Context): Boolean {
        val sz = Display.screenSize(ctx)
        return sz.x > sz.y
    }

    /** 内容区最大高度（px）：竖屏 78%，横屏 70%——横屏可用高度小，要更保守 */
    fun maxHeightPx(ctx: Context): Int {
        val sz = Display.screenSize(ctx)
        return (sz.y * if (sz.x > sz.y) 0.70f else 0.78f).toInt()
    }

    /**
     * 动作列表固定高度（px）= 4 行 × 行高。
     *
     * 窗口要保持固定大小：列表高度不随动作数量变化，
     * 空的时候不会塌成一条，动作多了也不会把窗口顶满——超出部分内部滚动。
     */
    fun listHeightPx(ctx: Context): Int = 4 * Display.dpInt(ctx, ROW_H_DP)

    /** 列表行高（dp）：与 FloatWorkWindow 的行 padding 一致 */
    const val ROW_H_DP = 34f

    /** 加入一个窗口；返回 false 表示已有同名窗口或没有权限 */
    fun add(ctx: Context, view: View, params: WindowManager.LayoutParams): Boolean {
        if (!Display.canDrawOverlay(ctx)) return false
        val m = manager(ctx)
        if (stack.any { it.view === view }) return true
        val e = Entry(view, params)
        stack.add(e)
        // 失败必须回滚：否则 stack 里留下一个"没真正挂上"的幽灵条目，
        // hideAll/restore 会去 removeView 一个根本没 attach 的 View，
        // 后续 add 的层叠顺序也会被污染。
        val ok = runCatching { m.addView(view, params); true }.getOrDefault(false)
        if (!ok) stack.remove(e)
        return ok
    }

    fun remove(view: View?) {
        val v = view ?: return
        val e = stack.firstOrNull { it.view === v } ?: return
        stack.remove(e)
        runCatching { wm?.removeView(v) }
    }

    fun update(view: View?, params: WindowManager.LayoutParams?) {
        val v = view ?: return
        val p = params ?: return
        // 必须把新参数写回栈里的条目：
        // 原写法 `?.let { it.view.layoutParams }` 只是取值、无任何副作用，
        // 于是拖动窗口后 hideAll→restore 会把窗口弹回旧位置。
        stack.firstOrNull { it.view === v }?.params = p
        runCatching { wm?.updateViewLayout(v, p) }
    }

    /**
     * 让出屏幕：摘下本应用全部悬浮窗口。
     * 选点与录制时调用——否则采集层只能采到本应用自己的界面，
     * 用户也看不到目标应用。
     */
    fun hideAll() {
        handler.post {
            val m = wm ?: return@post
            stashed.clear()
            stack.toList().forEach { e ->
                runCatching { m.removeView(e.view) }
                stashed.add(e)
            }
        }
    }

    /** 恢复 [hideAll] 摘下的窗口（保持原有层叠顺序） */
    fun restore() {
        handler.post {
            val m = wm ?: return@post
            if (stashed.isEmpty()) return@post
            stashed.forEach { e -> runCatching { m.addView(e.view, e.params) } }
            stashed.clear()
        }
    }

    /** 转屏后重算所有窗口尺寸：逐个回调让宿主重新布局 */
    fun onConfigChanged(ctx: Context, resize: (View, Float) -> Unit) {
        handler.post {
            val w = widthDp(ctx)
            stack.toList().forEach { e -> resize(e.view, w) }
        }
    }

    fun count(): Int = stack.size

    // ---------- 通用参数 ----------

    fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    fun params(ctx: Context, wDp: Float, focusable: Boolean = false)
            : WindowManager.LayoutParams {
        val p = WindowManager.LayoutParams(
            Display.dpInt(ctx, wDp),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = android.view.Gravity.CENTER
        return p
    }
}
