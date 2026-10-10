package com.autoball.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.core.util.Display

/**
 * **拾取层的「虚拟遮挡」开关**（CoordPicker 单点 / 滑动 / RegionPicker 区域 / ScreenPicker）。
 *
 * ## 为什么需要
 *
 * 拾取层是全屏窗口，打开后**独占触摸**——用户只能取点或者取消，
 * 没法再去操作后面的应用。但真实流程常常是反过来的：
 *
 * > 想取"某个按钮"的坐标 → 那个按钮在二级页面里 → 得先点进去 → 可拾取层挡着，点不了
 *
 * 于是用户只能先取消、切到目标应用翻到那一页、再回来取点，来回折腾。
 *
 * ## 做法
 *
 * 开启后给窗口加 `FLAG_NOT_TOUCHABLE`：触摸**穿透**到下层应用，用户可以正常
 * 点、滑、切页面；拾取层的准星 / 取景框 / 提示**仍然画在上面**（这就是"虚拟"——
 * 看得见，但不挡手）。再点一次切回锁定，恢复取点。
 *
 * 只能用窗口 flag 实现：View 层没法把已收到的事件再传给下层窗口。
 */
object PickerWindow {

    @Volatile private var wm: WindowManager? = null
    @Volatile private var view: View? = null
    @Volatile private var params: WindowManager.LayoutParams? = null
    @Volatile private var passthrough = false

    /** 已发出的开关按钮，穿透状态变化时统一刷新外观 */
    private val chips = ArrayList<TextView>()

    /** 穿透中的「回退浮标」窗口（见 [setPassthrough]） */
    @Volatile private var buoy: View? = null
    @Volatile private var buoyWm: WindowManager? = null

    /** 拾取层挂上窗口后调用，登记三件套 */
    fun bind(wm: WindowManager, view: View, params: WindowManager.LayoutParams) {
        this.wm = wm
        this.view = view
        this.params = params
        this.passthrough = false
    }

    /** 拾取层摘除时调用，避免拿着失效引用去 updateViewLayout */
    fun release() {
        removeBuoy()
        chips.clear()
        wm = null
        view = null
        params = null
        passthrough = false
    }

    /** 当前是否处于穿透状态 */
    fun isPassthrough(): Boolean = passthrough

    /**
     * 切换穿透。
     *
     * @return 切换后的状态；窗口三件套缺失（层已关闭）时返回 false
     */
    fun setPassthrough(on: Boolean): Boolean {
        val m = wm ?: return false
        val v = view ?: return false
        val p = params ?: return false
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        p.flags = if (on) p.flags or flag else p.flags and flag.inv()
        passthrough = on
        // 层可能刚被关掉：updateViewLayout 对已移除的 view 会抛，吞掉即可
        runCatching { m.updateViewLayout(v, p) }

        // **穿透时必须另挂一个可点的浮标**。
        // FLAG_NOT_TOUCHABLE 作用于整个窗口——底栏上的开关按钮也在这一层里，
        // 穿透后连它自己都点不到，用户就再也回不来了（只能杀进程）。
        // 浮标是**独立窗口**，不受本层 flag 影响，点它就切回锁定。
        if (on) showBuoy(v.context) else removeBuoy()
        chips.toList().forEach { applyState(it, on) }
        return on
    }

    /** 穿透中的回退浮标：屏幕上方居中的一枚小胶囊 */
    private fun showBuoy(ctx: Context) {
        if (buoy != null) return
        val m = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val tv = TextView(ctx).apply {
            text = "穿透中 · 点此回到取点"
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Theme.rect(Theme.pri(), 14f, ctx)
            val pd = Display.dpInt(ctx, 8f)
            setPadding(Display.dpInt(ctx, 14f), pd, Display.dpInt(ctx, 14f), pd)
            setOnClickListener { setPassthrough(false) }
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (android.os.Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT)
        p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        p.y = Display.dpInt(ctx, 56f)
        if (runCatching { m.addView(tv, p) }.isSuccess) {
            buoy = tv
            buoyWm = m
        }
    }

    private fun removeBuoy() {
        val b = buoy ?: return
        runCatching { buoyWm?.removeView(b) }
        buoy = null
        buoyWm = null
    }

    /**
     * 穿透开关按钮（放在底栏提示行右侧）。
     *
     * 文案直接写状态而不是动作——"穿透中 / 已锁定"比一个含糊的图标更好判断
     * 当前到底是哪种模式；这是最容易搞混的一类开关，搞混了就表现为
     * "怎么点屏幕没反应"（锁定中）或"怎么取不了点"（穿透中）。
     */
    fun chip(ctx: Context, onChanged: ((Boolean) -> Unit)? = null): TextView =
        TextView(ctx).apply {
            text = "已锁定"
            textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Theme.textSec())
            background = Theme.rect(Theme.surface2(), 9f, ctx, Theme.line())
            val pd = Display.dpInt(ctx, 6f)
            setPadding(Display.dpInt(ctx, 9f), pd, Display.dpInt(ctx, 9f), pd)
            chips.add(this)
            setOnClickListener {
                val on = setPassthrough(!passthrough)
                // 外观由 setPassthrough 统一刷新（浮标切回时也要同步）
                onChanged?.invoke(on)
            }
        }

    /** 按状态刷新按钮外观：穿透中是主色实心（醒目），锁定后回到描边 */
    fun applyState(btn: TextView, on: Boolean) {
        btn.text = if (on) "穿透中" else "已锁定"
        btn.setTextColor(if (on) Color.WHITE else Theme.textSec())
        btn.background = if (on) Theme.rect(Theme.pri(), 9f, btn.context)
                         else Theme.rect(Theme.surface2(), 9f, btn.context, Theme.line())
    }

    /**
     * 提示行：左边说明 + 右边穿透开关。
     *
     * 抽在这里是因为三个拾取层都要这一行，各写一遍必然出现"有的有开关、
     * 有的没有"的错漏——而这恰恰是用户最容易察觉的不一致。
     */
    fun tipRow(ctx: Context, tip: String,
               tipPassthrough: String = "穿透中：可直接操作其他应用",
               onChanged: ((Boolean) -> Unit)? = null): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Display.dpInt(ctx, 10f), 0, Display.dpInt(ctx, 10f),
                Display.dpInt(ctx, 6f))
            val tv = TextView(ctx).apply {
                text = tip
                textSize = 11f
                setTextColor(Theme.textSec())
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(tv)
            // 文案由本组件自己切换：调用方拿不到这一行的子 View 引用
            // （Kotlin 局部变量不能在自己的初始化器里被 lambda 引用）
            addView(chip(ctx) { on ->
                tv.text = if (on) tipPassthrough else tip
                onChanged?.invoke(on)
            })
        }
}
