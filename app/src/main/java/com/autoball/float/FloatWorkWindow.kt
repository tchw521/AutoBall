package com.autoball.float

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.autoball.AB
import com.autoball.core.model.Action
import com.autoball.core.model.Script
import com.autoball.core.util.Display
import com.autoball.ui.Theme

/**
 * 工作台悬浮窗（统一组件）——仿自动精灵悬浮窗布局。
 *
 * 「开始录制」与「添加动作」都在这里进行，不再是应用内弹窗：
 * 两者都要操作别的应用，应用内弹窗占住屏幕，用户根本切不过去。
 *
 * 布局一比一对齐自动精灵：
 * ```
 * ┌─────────────────────────────┐
 * │ ● 未命名脚本         ⚙ ⋯ ✕ │  ← 头部，可拖动；录制中圆点闪烁
 * ├─────────────────────────────┤
 * │ 1. 点击(63.6%, 49.7%)       │  ← 动作列表，百分比坐标
 * │ 2. 滑动(50%,80%)→(50%,20%)  │
 * │    空态：脚本为空 请先添加一个动作 │
 * ├─────────────────────────────┤
 * │ [开始录制] [添加动作] [更多] │  ← 底部三按钮
 * └─────────────────────────────┘
 * ```
 * 「更多」展开：保存脚本 / 清空动作 / 全局设置 / 运行日志。
 */
object FloatWorkWindow {

    interface Callback {
        fun onRecord(script: Script)
        fun onAddAction(script: Script)
        fun onSettings(script: Script)
        fun onSave(script: Script)
        fun onClear(script: Script)
        fun onLog(script: Script)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null
    private var current: Script? = null
    private var recording = false

    fun isShown(): Boolean = view != null

    fun show(context: Context, script: Script, cb: Callback, goHome: Boolean = true) {
        if (!Display.canDrawOverlay(context)) {
            AB.log.warn("work", "未获得悬浮窗权限，工作台无法显示")
            android.widget.Toast.makeText(context, "请先授予悬浮窗权限", 0).show()
            return
        }
        current = script
        handler.post {
            if (view != null) return@post
            val ctx = context.applicationContext
            val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm = manager
            val v = buildView(ctx, script, cb)
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            p.gravity = Gravity.CENTER
            p.x = 0
            p.y = Display.screenSize(ctx).y / 6
            view = v
            params = p
            runCatching { manager.addView(v, p) }
            if (goHome) goHome(ctx)
        }
    }

    fun hide() {
        handler.post {
            val v = view ?: return@post
            runCatching { wm?.removeView(v) }
            view = null
            params = null
        }
    }

    /** 录制状态切换：头部圆点闪烁 + 底部按钮文案变化 */
    fun setRecording(on: Boolean) {
        recording = on
        handler.post { view?.let { refreshState(it) } }
    }

    /** 动作数变化后刷新列表 */
    fun refresh(script: Script) {
        current = script
        handler.post { view?.let { fillList(it, script) } }
    }

    // ---------- 构建 ----------

    private fun buildView(ctx: Context, script: Script, cb: Callback): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg(ctx)
            elevation = Display.dp(ctx, 10f)
        }
        val w = Display.dpInt(ctx, 268f)

        // ---- 头：状态点 + 脚本名 + ⚙ ⋯ ✕ ----
        val head = FrameLayout(ctx).apply {
            setPadding(Display.dpInt(ctx, 12f), Display.dpInt(ctx, 11f),
                Display.dpInt(ctx, 8f), Display.dpInt(ctx, 9f))
        }
        val titleBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                marginEnd = Display.dpInt(ctx, 92f)
            }
        }
        val dot = TextView(ctx).apply {
            text = "●"
            textSize = 9f
            setTextColor(Theme.ok())
            setPadding(0, 0, Display.dpInt(ctx, 5f), 0)
        }
        dot.setTag(TAG_DOT, dot)
        titleBox.addView(dot)
        titleBox.addView(TextView(ctx).apply {
            text = script.name
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.textPri())
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        head.addView(titleBox)

        val btns = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
        }
        btns.addView(roundBtn(ctx, "⚙") { cb.onSettings(script) })
        btns.addView(roundBtn(ctx, "⋯") { toggleMore(root) })
        btns.addView(roundBtn(ctx, "✕") { hide() })
        head.addView(btns)
        dragAttach(head)
        root.addView(head)

        root.addView(View(ctx).apply {
            setBackgroundColor(Theme.line())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })

        // ---- 动作列表 ----
        val scroll = ScrollView(ctx).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        list.setTag(TAG_LIST, list)
        scroll.addView(list, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- 底部主按钮 ----
        val mainBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Display.dpInt(ctx, 10f), Display.dpInt(ctx, 8f),
                Display.dpInt(ctx, 10f), Display.dpInt(ctx, 10f))
        }
        val recBtn = flatBtn(ctx, "开始录制", Theme.ok()) { cb.onRecord(script) }
        recBtn.setTag(TAG_REC, recBtn)
        mainBar.addView(recBtn, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = Display.dpInt(ctx, 4f)
        })
        mainBar.addView(flatBtn(ctx, "添加动作", Theme.pri()) { cb.onAddAction(script) },
            LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Display.dpInt(ctx, 4f)
            })
        root.addView(mainBar)

        // ---- 更多（默认收起）----
        val more = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(Display.dpInt(ctx, 10f), 0,
                Display.dpInt(ctx, 10f), Display.dpInt(ctx, 10f))
        }
        more.setTag(TAG_MORE, more)
        more.addView(moreRow(ctx, "保存脚本") { cb.onSave(script); hide() })
        more.addView(moreRow(ctx, "清空动作") { cb.onClear(script) })
        more.addView(moreRow(ctx, "全局设置") { cb.onSettings(script) })
        more.addView(moreRow(ctx, "运行日志") { cb.onLog(script) })
        root.addView(more)

        fillList(root, script)
        root.layoutParams = FrameLayout.LayoutParams(w,
            FrameLayout.LayoutParams.WRAP_CONTENT)
        return root
    }

    // ---------- 列表 ----------

    private fun fillList(root: View, script: Script) {
        val list = root.getTag(TAG_LIST) as? LinearLayout ?: return
        list.removeAllViews()
        val acts = script.flow?.actions ?: emptyList()
        if (acts.isEmpty()) {
            list.addView(TextView(root.context).apply {
                text = "脚本为空  请先添加一个动作"
                textSize = 12.5f
                setTextColor(Theme.textSec())
                gravity = Gravity.CENTER
                setPadding(Display.dpInt(root.context, 12f),
                    Display.dpInt(root.context, 22f),
                    Display.dpInt(root.context, 12f),
                    Display.dpInt(root.context, 22f))
            })
            return
        }
        acts.forEachIndexed { i, a ->
            list.addView(TextView(root.context).apply {
                text = "${i + 1}. ${summary(root.context, a)}"
                textSize = 12f
                setTextColor(Theme.textSec())
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(Display.dpInt(root.context, 12f),
                    Display.dpInt(root.context, 6f),
                    Display.dpInt(root.context, 12f),
                    Display.dpInt(root.context, 6f))
            })
        }
    }

    /**
     * 动作摘要，坐标统一按百分比显示（与自动精灵一致）。
     *
     * 用百分比而非绝对像素，是因为脚本常被换到别的机型上跑，
     * 百分比是用户唯一能跨设备理解的坐标表示。
     */
    private fun summary(ctx: Context, a: Action): String {
        val sz = Display.screenSize(ctx)
        val px = { v: Float -> (v / sz.x * 100).let { "%.1f%%".format(it) } }
        val py = { v: Float -> (v / sz.y * 100).let { "%.1f%%".format(it) } }
        return when (a.type) {
            com.autoball.core.model.ActionType.CLICK,
            com.autoball.core.model.ActionType.CLICK_IMAGE,
            com.autoball.core.model.ActionType.CLICK_TEXT,
            com.autoball.core.model.ActionType.CLICK_COLOR,
            com.autoball.core.model.ActionType.CLICK_NODE,
            com.autoball.core.model.ActionType.AI_CLICK ->
                "点击(${px(a.x)}, ${py(a.y)})"
            com.autoball.core.model.ActionType.SWIPE,
            com.autoball.core.model.ActionType.GESTURE_SINGLE,
            com.autoball.core.model.ActionType.GESTURE_MULTI ->
                "滑动(${px(a.x)}, ${py(a.y)})→(${px(a.x2)}, ${py(a.y2)})"
            com.autoball.core.model.ActionType.KEY -> "按键(${a.keyCode})"
            com.autoball.core.model.ActionType.INPUT_TEXT ->
                "输入「${a.text ?: ""}」"
            com.autoball.core.model.ActionType.OPEN_APP -> "打开应用"
            else -> a.type.label
        }
    }

    // ---------- 状态 ----------

    private fun refreshState(root: View) {
        (root.getTag(TAG_DOT) as? TextView)?.apply {
            setTextColor(if (recording) Theme.danger() else Theme.ok())
            if (recording) startBlink(this) else { clearAnimation(); alpha = 1f }
        }
        (root.getTag(TAG_REC) as? TextView)?.apply {
            text = if (recording) "停止录制" else "开始录制"
        }
    }

    private fun startBlink(tv: TextView) {
        tv.animate().alpha(0.25f).setDuration(500)
            .withEndAction { tv.animate().alpha(1f).setDuration(500)
                .withEndAction { if (recording) startBlink(tv) }.start() }
            .start()
    }

    private fun toggleMore(root: View) {
        val more = root.getTag(TAG_MORE) as? LinearLayout ?: return
        more.visibility = if (more.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        runCatching { wm?.updateViewLayout(view, params) }
    }

    // ---------- 复用件 ----------

    private fun panelBg(ctx: Context): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            if (Theme.isDark()) intArrayOf(0xF6242040.toInt(), 0xFA1B1730.toInt())
            else intArrayOf(0xFAFFFFFF.toInt(), 0xFAF5F3FF.toInt())).apply {
            cornerRadius = Display.dp(ctx, 18f)
            setStroke(Display.dpInt(ctx, 1f), Theme.line())
        }

    private fun roundBtn(ctx: Context, glyph: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = glyph
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.textSec())
            gravity = Gravity.CENTER
            background = Theme.bubbleRound(ctx, Theme.surface2())
            val sz = Display.dpInt(ctx, 27f)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginStart = Display.dpInt(ctx, 3f)
            }
            setOnClickListener { onClick() }
        }

    private fun flatBtn(ctx: Context, text: String, color: Int,
                        onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 12.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(color)
            gravity = Gravity.CENTER
            background = Theme.rect(Theme.surface2(), 12f, ctx, Theme.line())
            setPadding(Display.dpInt(ctx, 8f), Display.dpInt(ctx, 11f),
                Display.dpInt(ctx, 8f), Display.dpInt(ctx, 11f))
            setOnClickListener { onClick() }
        }

    private fun moreRow(ctx: Context, text: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setTextColor(Theme.textSec())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Display.dpInt(ctx, 10f), Display.dpInt(ctx, 9f),
                Display.dpInt(ctx, 10f), Display.dpInt(ctx, 9f))
            setOnClickListener { onClick() }
        }

    /** 头部拖动：超过 8dp 视为移动，不触发点击 */
    private fun dragAttach(head: View) {
        var sx = 0f; var sy = 0f; var px = 0; var py = 0; var moved = false
        head.setOnTouchListener { _, e ->
            val p = params ?: return@setOnTouchListener false
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
                        runCatching { wm?.updateViewLayout(view, p) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) return@setOnTouchListener true
                    head.performClick()
                }
            }
            true
        }
    }

    private fun goHome(ctx: Context) {
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    // 用 View.setTag 需要 key；这里用稳定 int 常量，避免新增资源 id
    private const val TAG_LIST = 0x7f010001
    private const val TAG_MORE = 0x7f010002
    private const val TAG_DOT = 0x7f010003
    private const val TAG_REC = 0x7f010004
}
