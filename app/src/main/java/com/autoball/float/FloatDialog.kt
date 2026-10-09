package com.autoball.float

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.autoball.core.util.Display
import com.autoball.ui.Theme

/**
 * 悬浮窗弹窗（统一组件）。
 *
 * 设计目标：**所有弹窗都在悬浮窗层弹出，不把用户拽回应用界面**。
 * 录制、添加动作、全局设置都要在"正操作着别的应用"时调出来——
 * 若此时跳回应用界面，用户刚打开的目标应用就被切走了，等于白操作一遍。
 *
 * 用法与 [com.autoball.ui.Ui.dialog] 对齐，便于现有调用点平移：
 *
 * ```
 * FloatDialog.show(ctx, "标题")
 *     .body(view)
 *     .negative("取消")
 *     .positive("确定") { true }
 *     .show()
 * ```
 *
 * 关于输入法：悬浮窗里的 EditText 需要窗口可获取焦点，
 * 因此**不加** FLAG_NOT_FOCUSABLE，改用 FLAG_DIM_BEHIND 做遮罩。
 * 关闭时主动收起键盘，避免键盘残留挡住目标应用。
 */
class FloatDialog private constructor(private val ctx: Context, private val title: String) {

    private var body: View? = null
    /**
     * 弹窗建成后回调（宿主容器 + 标题 TextView）。
     *
     * 用于**就地换页**：调用方把 host 的内容换成另一屏（如动作类型列表），
     * 而不是另开一个弹窗——另开弹窗会与本弹窗争同一窗口层级而被盖住。
     * 用法与 [com.autoball.ui.Ui.dialog] 的 onReady 对齐。
     */
    private var onReady: ((FloatDialog, ScrollView, TextView) -> Unit)? = null
    private var positive: Pair<String, (() -> Boolean)?>? = null
    private var positiveColor = 0
    private var negative: Pair<String, (() -> Unit)?>? = null
    /** 默认宽度；实际取用时若未显式指定，则跟随窗口统一尺寸 */
    private var widthDp = 0f

    fun body(v: View) = apply { body = v }
    fun positive(text: String, onClick: (() -> Boolean)? = null) =
        apply { positive = text to onClick; positiveColor = 0 }
    fun positiveDanger(text: String, onClick: (() -> Boolean)? = null) =
        apply { positive = text to onClick; positiveColor = Theme.danger() }
    fun negative(text: String, onClick: (() -> Unit)? = null) =
        apply { negative = text to onClick }
    fun width(dp: Float) = apply { widthDp = dp }
    fun onReady(cb: (FloatDialog, ScrollView, TextView) -> Unit) = apply { onReady = cb }

    fun show(): Boolean {
        if (!Display.canDrawOverlay(ctx)) return false

        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // 未显式指定宽度时跟随统一窗口尺寸，弹窗与窗口看起来是同一套规格
        val wDp = if (widthDp > 0f) widthDp
                  else FloatWindows.windowSizeDp(ctx).first

        // ---- 遮罩：捕获点击，点空白处关闭 ----
        val shade = FrameLayout(ctx).apply {
            setBackgroundColor(
                if (Theme.isDark()) 0xB3000000.toInt() else 0x99000000.toInt())
            setOnClickListener { dismiss() }
        }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Theme.surface())
                cornerRadius = Display.dp(ctx, 18f)
                setStroke(Display.dpInt(ctx, 1f), Theme.line())
            }
            elevation = Display.dp(ctx, 12f)
        }

        // 标题
        val titleTv = TextView(ctx).apply {
            text = title
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.textPri())
            setPadding(Display.dpInt(ctx, 16f), Display.dpInt(ctx, 14f),
                Display.dpInt(ctx, 16f), Display.dpInt(ctx, 12f))
        }
        card.addView(titleTv)
        card.addView(View(ctx).apply {
            setBackgroundColor(Theme.line())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })

        // 体（可滚动；内容高时限制为屏高 70%）
        val scroll = ScrollView(ctx).apply { isFillViewport = false }
        body?.let {
            it.setPadding(Display.dpInt(ctx, 6f), Display.dpInt(ctx, 4f),
                Display.dpInt(ctx, 6f), Display.dpInt(ctx, 6f))
            scroll.addView(it, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // 与悬浮窗口同一套尺寸规则（宽高比同手机、横竖屏一致），
        // 不再用"屏高 70%"这种随屏幕变化的比例——
        // 那会让同一个弹窗在横竖屏下大小不一，内容一多还得靠压紧高度。
        val maxH = Display.dpInt(ctx, com.autoball.float.FloatWindows.windowHeightDp(ctx))
        card.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            // 内容自然高度，由外层测量后夹紧
        })
        // 高度已固定，不再需要"测量后压紧"的监听器——
        // 那段逻辑会在每次布局后改卡片高度，与固定尺寸冲突

        // 底部按钮
        val pos = positive
        val neg = negative
        if (pos != null || neg != null) {
            card.addView(View(ctx).apply {
                setBackgroundColor(Theme.line())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })
            val bar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(Display.dpInt(ctx, 10f), Display.dpInt(ctx, 9f),
                    Display.dpInt(ctx, 10f), Display.dpInt(ctx, 9f))
            }
            neg?.let { (t, cb) ->
                bar.addView(TextView(ctx).apply {
                    text = t
                    textSize = 13.5f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Theme.textSec())
                    gravity = Gravity.CENTER
                    background = Theme.rect(Theme.surface2(), 10f, ctx, Theme.line())
                    setPadding(Display.dpInt(ctx, 10f), Display.dpInt(ctx, 9f),
                        Display.dpInt(ctx, 10f), Display.dpInt(ctx, 9f))
                    setOnClickListener { cb?.invoke(); dismiss() }
                }, LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = Display.dpInt(ctx, 5f)
                })
            }
            pos?.let { (t, cb) ->
                bar.addView(TextView(ctx).apply {
                    text = t
                    textSize = 13.5f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(if (positiveColor != 0) positiveColor else Color.WHITE)
                    gravity = Gravity.CENTER
                    background = Theme.grad(ctx, 10f)
                    setPadding(Display.dpInt(ctx, 10f), Display.dpInt(ctx, 9f),
                        Display.dpInt(ctx, 10f), Display.dpInt(ctx, 9f))
                    setOnClickListener {
                        if (cb == null || cb.invoke()) dismiss()
                    }
                }, LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = Display.dpInt(ctx, 5f)
                })
            }
            card.addView(bar)
        }

        val w = Display.dpInt(ctx, wDp).coerceAtMost(
            Display.dpInt(ctx, FloatWindows.windowSizeDp(ctx).first + 60f))
        root = FrameLayout(ctx).apply {
            addView(shade, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT))
            // 高度也写死为窗口统一高度：卡片不再随内容变高，
            // 内容超出由内部 ScrollView 滚动
            addView(card, FrameLayout.LayoutParams(w, maxH).apply {
                gravity = Gravity.CENTER
            })
        }
        // 卡片自身吃掉点击，避免冒泡到遮罩导致误关
        card.isClickable = true

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        p.dimAmount = 0.32f
        p.gravity = Gravity.CENTER
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        rootParams = p
        val rv = root
        if (rv != null) FloatWindows.add(ctx, rv, p)

        // 入场
        card.scaleX = 0.94f; card.scaleY = 0.94f; card.alpha = 0f
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180).start()
        onReady?.invoke(this, scroll, titleTv)
        return true
    }

    /** 供外部（如脚本弹窗超时）主动收起；按钮点击时内部也会调用 */
    fun dismiss() {
        runCatching {
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(root?.windowToken, 0)
        }
        val r = root ?: return
        FloatWindows.remove(r)
        root = null
    }

    // ---------- 静态入口 ----------

    companion object {
        private var root: FrameLayout? = null
        private var rootParams: WindowManager.LayoutParams? = null

        fun show(ctx: Context, title: String): FloatDialog = FloatDialog(ctx, title)

        /**
         * 单行文本输入弹窗（重命名、新建分组等）。
         * 键盘处理已在 [show] 中统一，这里只负责取值回调。
         */
        fun input(ctx: Context, title: String, init: String, hint: String = "",
                  onOk: (String) -> Unit) {
            if (!Display.canDrawOverlay(ctx)) return
            val et = EditText(ctx).apply {
                setText(init)
                this.hint = hint
                setHintTextColor(Theme.textTer())
                setTextColor(Theme.textPri())
                textSize = 13.5f
                inputType = InputType.TYPE_CLASS_TEXT
                setSingleLine(true)
                background = Theme.rect(Theme.surface2(), 10f, ctx, Theme.line())
                setPadding(Display.dpInt(ctx, 12f), Display.dpInt(ctx, 10f),
                    Display.dpInt(ctx, 12f), Display.dpInt(ctx, 10f))
            }
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Display.dpInt(ctx, 14f), Display.dpInt(ctx, 10f),
                    Display.dpInt(ctx, 14f), Display.dpInt(ctx, 4f))
                addView(et)
            }
            val d = FloatDialog(ctx, title).body(box)
                .negative("取消")
                .positive("确定") {
                    val v = et.text.toString().trim()
                    if (v.isEmpty()) {
                        com.autoball.ui.Ui.toast(ctx, "内容不能为空")
                        false
                    } else {
                        onOk(v)
                        true
                    }
                }
            if (!d.show()) {
                // 无悬浮窗权限：回退到 Activity 弹窗
                val act = ctx as? android.app.Activity
                if (act != null) {
                    com.autoball.ui.Ui.dialog(act, title).body(box)
                        .negative("取消")
                        .positive("确定") {
                            val v = et.text.toString().trim()
                            if (v.isEmpty()) {
                                com.autoball.ui.Ui.toast(ctx, "内容不能为空")
                                false
                            } else {
                                onOk(v)
                                true
                            }
                        }.show()
                }
            } else {
                // 悬浮窗内主动请求焦点以弹出键盘
                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching {
                        et.requestFocus()
                        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                            ?.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
                    }
                }, 220)
            }
        }

        private fun overlayType(): Int =
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
    }

    /** 拖动把手绑定：超过 8dp 视为移动，不触发点击 */
    fun drag(handle: View) {
        var sx = 0f; var sy = 0f; var px = 0; var py = 0; var moved = false
        handle.setOnTouchListener { _, e ->
            val p = rootParams ?: return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; px = p.x; py = p.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt()
                    val dy = (e.rawY - sy).toInt()
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                        moved = true
                        p.x = px + dx; p.y = py + dy
                        runCatching {
                            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                                .updateViewLayout(root, p)
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) return@setOnTouchListener true
                    handle.performClick()
                }
            }
            true
        }
    }
}
