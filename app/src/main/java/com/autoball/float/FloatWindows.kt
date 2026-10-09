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
    /**
     * **窗口统一尺寸**（dp）：宽高比与手机屏幕一致，且不随横竖屏跳变。
     *
     * 两条规则：
     * 1. **竖屏时窗口长宽比 = 手机的长宽比**——窗口宽度取屏宽 1/2，
     *    高度按同一比例推出，看起来就是"缩小了一倍的手机屏幕"。
     * 2. **横屏时尺寸与竖屏时相同**——先把屏幕归一化成"短边为宽、长边为高"
     *    再算，横屏下拿到的是同一组基准值，转屏窗口不会变大变小。
     *
     * 唯一的例外是放不下：横屏时可用高度只剩短边，
     * 基准高度可能超出，此时**整体等比缩小**保持宽高比，
     * 而不是只压高度——只压高度的话窗口会被拉扁，比例就不对了。
     */
    fun windowSizeDp(ctx: Context): Pair<Float, Float> {
        val sz = Display.screenSize(ctx)
        val den = Display.density(ctx).takeIf { it > 0f } ?: 1f
        val a = sz.x / den          // 当前屏幕宽（横屏时是长边）
        val b = sz.y / den          // 当前屏幕高（横屏时是短边）
        // 归一化成竖屏口径：短边当宽、长边当高。横竖屏得到同一组值。
        val pw = minOf(a, b)
        val ph = maxOf(a, b)
        var w = (pw / 2f).coerceIn(240f, pw - 24f)
        var h = w * (ph / pw)

        // **在基准尺寸上再收缩**（用户要求）：高度减半、宽度减 1/5。
        // 收缩后再做边界夹取——顺序反了的话，"放不下就等比缩小"
        // 会把用户指定的收缩比例冲掉。
        h *= HEIGHT_SCALE
        w *= WIDTH_SCALE

        // 当前屏幕放不下（横屏常见）→ 等比缩小
        val maxH = b - 24f
        if (h > maxH) {
            val k = maxH / h
            h = maxH
            w *= k
        }
        val maxW = a - 24f
        if (w > maxW) {
            val k = maxW / w
            w = maxW
            h *= k
        }
        return w to h
    }

    /**
     * 窗口高度相对"与手机同宽高比"基准的缩放：**减半**。
     *
     * 注意这会让窗口不再与手机同比例（变成扁的）——这是用户明确要求的，
     * 目的是少挡住被操作的界面。
     */
    const val HEIGHT_SCALE = 0.5f

    /** 窗口宽度缩放：**减少 1/5**（即取基准的 0.8） */
    const val WIDTH_SCALE = 0.8f

    /**
     * 动作列表相对内容区的缩放：**宽 1/3、高 1/2**。
     *
     * 列表远小于窗口是有意为之：窗口里还有标题栏、底部条、更多菜单，
     * 列表只作为"当前进度提示"存在，不需要占满。
     */
    const val LIST_WIDTH_SCALE = 1f / 3f
    const val LIST_HEIGHT_SCALE = 0.5f

    /**
     * **选择动作类型**列表的缩放（与窗口内动作列表是两套）：
     * 宽取窗口的 1/2、高取内容区的 2/3（即"长度减少三分之一"）。
     *
     * 用内容区而不是整窗口高，是因为弹窗里还有标题栏和底部按钮，
     * 按整窗口算会把按钮挤出屏幕。
     */
    const val TYPE_LIST_WIDTH_SCALE = 0.5f
    const val TYPE_LIST_HEIGHT_SCALE = 0.6667f

    /** 窗口统一高度（dp），见 [windowSizeDp] */
    fun windowHeightDp(ctx: Context): Float = windowSizeDp(ctx).second

    /**
     * **窗口框架高度（px）**：在 [windowHeightDp] 基础上加下限保护。
     *
     * 这是"底部按钮消失"的直接成因：窗口高度被写死成一个值，
     * 而标题栏 + 底部条是**固定开销**，内容区再怎么挤也挤不掉它们。
     * 一旦窗口高度 < 头部+底条+最小内容，LinearLayout 会按顺序排下去，
     * 底条被排到框架外面 → 看不见、也点不到。
     *
     * 所以任何地方要用窗口高度，都必须走这里而不是直接用 [windowHeightDp]。
     */
    fun frameHeightPx(ctx: Context): Int {
        val h = Display.dpInt(ctx, windowHeightDp(ctx))
        val min = Display.dpInt(ctx, MIN_FRAME_DP)
        return maxOf(h, min)
    }

    /** 窗口最小框架高度：头部 + 底条 + 最小内容区（dp） */
    val MIN_FRAME_DP: Float get() = HEAD_DP + BAR_DP + MIN_CONTENT_DP

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

    /**
     * 内容区最大高度（px）：竖屏 78%，横屏 70%——横屏可用高度小，要更保守。
     *
     * 同样加 [MIN_CONTENT_DP] 下限：屏高很小的设备（或分屏）按比例算出来
     * 可能只剩几十 dp，内容区被压没。
     */
    fun maxHeightPx(ctx: Context): Int {
        val sz = Display.screenSize(ctx)
        val byRatio = (sz.y * if (sz.x > sz.y) 0.70f else 0.78f).toInt()
        return maxOf(byRatio, Display.dpInt(ctx, MIN_CONTENT_DP))
    }

    /**
     * 动作列表固定高度（px）= 4 行 × 行高。
     *
     * 窗口要保持固定大小：列表高度不随动作数量变化，
     * 空的时候不会塌成一条，动作多了也不会把窗口顶满——超出部分内部滚动。
     */
    /**
     * 动作列表高度（px）：行数夹在 4～8 行之间，超出部分**内部滚动**。
     *
     * 两条约束缺一不可：
     * - 下限 4 行：空态或只有一两步时窗口不会塌成一条，大小可预期；
     * - 上限 8 行：动作多时窗口不会一路顶满屏幕（17 步时曾把整个屏幕撑满）。
     *
     * @param count 当前动作数；0 表示空态（返回 WRAP_CONTENT 由调用方处理）
     */
    /**
     * 内容区高度（px）：固定值 = 窗口高度减去头部与底部条。
     *
     * 早前按动作数算（4~8 行夹取），窗口还是会长短不一；
     * 现在窗口整体固定，内容区就是"剩下的那块"，超出一律内部滚动。
     */
    fun contentHeightPx(ctx: Context): Int {
        val h = windowHeightDp(ctx)
        val chrome = HEAD_DP + BAR_DP + 2f
        return Display.dpInt(ctx, (h - chrome).coerceAtLeast(MIN_CONTENT_DP))
    }

    /** 窗口固定框架：标题栏 + 底部条 + 分割线（dp） */
    const val HEAD_DP = 40f
    const val BAR_DP = 44f
    const val MIN_CONTENT_DP = 90f

    /**
     * 列表高度（px）——保留仅为兼容旧调用点，一律返回内容区高度。
     * @param count 已忽略
     */
    @Deprecated("窗口已固定大小，列表高度不再随动作数变化")
    fun listHeightPx(ctx: Context, count: Int = 4): Int {
        return contentHeightPx(ctx)
    }

    /** 列表最少 4 行 / 最多 8 行 */
    const val MIN_LIST_ROWS = 4
    const val MAX_LIST_ROWS = 8

    /** 列表行高（dp）：与 FloatWorkWindow 的行 padding 一致 */
    const val ROW_H_DP = com.autoball.float.TextSz.ROW_H

    /** 列表**最少**显示行数：菜单展开、列表被压到 1/3 时也要保证看到这么多行 */
    const val MIN_VISIBLE_ROWS = 3

    /** 列表最小高度（px）：保证 [MIN_VISIBLE_ROWS] 行可见 */
    fun listMinHeightPx(ctx: Context): Int =
        MIN_VISIBLE_ROWS * Display.dpInt(ctx, ROW_H_DP)

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

    /** 是否让所有窗口使用统一固定尺寸（宽高比同手机、横竖屏一致） */
    @Volatile
    var fixedSize: Boolean = true

    fun params(ctx: Context, wDp: Float, focusable: Boolean = false)
            : WindowManager.LayoutParams {
        // 固定尺寸：高度也写死。此前只写宽、高是 WRAP_CONTENT，
        // 于是窗口高度随内容走——动作多、菜单展开、文案变长都会让窗口变高。
        // 高度走 frameHeightPx（带下限保护），保证底条永远在框架内。
        val h = if (fixedSize) frameHeightPx(ctx)
                else WindowManager.LayoutParams.WRAP_CONTENT
        val p = WindowManager.LayoutParams(
            Display.dpInt(ctx, if (fixedSize) windowSizeDp(ctx).first else wDp),
            h,
            overlayType(),
            if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = android.view.Gravity.CENTER
        return p
    }
}
