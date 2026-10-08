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
import com.autoball.core.model.Script
import com.autoball.core.util.Display
import com.autoball.ui.ActionEditor
import com.autoball.ui.Theme

/**
 * 录制 / 添加动作悬浮窗（统一组件）——**一比一复刻自动精灵**。
 *
 * 自动精灵的录制小窗形态：
 * ```
 * ┌───────────────────────────┐
 * │ 未命名脚本              ✕ │   标题栏，可拖动
 * ├───────────────────────────┤
 * │ 1. 点击(63.6%, 49.7%)     │   动作列表，百分比坐标
 * │ 2. 点击(65.4%, 55.2%)     │
 * │ 3. 长按(51.8%, 58.9%)     │
 * │    空态：脚本为空 请先添加一个动作 │
 * ├───────────────────────────┤
 * │ [运行] [录制] [⋯]         │   底部三键
 * └───────────────────────────┘
 * ```
 * 「⋯」展开更多：添加动作 / 保存脚本 / 清空动作 / 开启日志 / 查看变量 / 全局设置。
 *
 * 之所以必须是悬浮窗：录制与添加动作都要操作**别的应用**，
 * 应用内弹窗占住屏幕，用户根本切不过去。
 */
object FloatWorkWindow {

    interface Callback {
        /** 运行当前脚本 */
        fun onRun(script: Script)
        /** 开始 / 停止录制（isRecording 表示操作后的状态） */
        fun onRecord(script: Script, willRecord: Boolean)
        /** 手动添加一个动作 */
        fun onAddAction(script: Script)
        fun onSave(script: Script)
        fun onClear(script: Script)
        /** 开启 / 关闭运行日志 */
        fun onToggleLog(script: Script)
        /** 打开「更多工具」快捷动作面板（自动精灵同款） */
        fun onTools(script: Script)
        /** 查看脚本变量 */
        fun onVars(script: Script)
        fun onSettings(script: Script)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null
    private var recording = false

    // 用稳定常量做 view 标识；setTag(int) 的 key 需为资源 id，
    // 这里改用持有引用的方式（见 Holder），避免兼容风险
    private class Holder(
        val list: LinearLayout,
        val more: LinearLayout,
        val dot: TextView,
        val recBtn: TextView,
        val title: TextView
    )

    private var holder: Holder? = null

    fun isShown(): Boolean = view != null

    fun show(context: Context, script: Script, cb: Callback, goHome: Boolean = true) {
        if (!Display.canDrawOverlay(context)) {
            AB.log.warn("work", "未获得悬浮窗权限，录制窗口无法显示")
            android.widget.Toast.makeText(context, "请先授予悬浮窗权限", 0).show()
            return
        }
        handler.post {
            if (view != null) return@post
            val ctx = context.applicationContext
            val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm = manager
            val v = buildView(ctx, script, cb)
            // 宽度走统一规则：竖屏屏宽 1/2、横屏 1/4，避免横屏顶满屏幕
            val p = FloatWindows.params(ctx, FloatWindows.widthDp(ctx))
            p.y = Display.screenSize(ctx).y / 6
            view = v
            params = p
            // 走统一栈：新窗口后入栈，天然压在旧窗口之上
            if (!FloatWindows.add(ctx, v, p)) {
                view = null
                return@post
            }
            if (goHome) goHome(ctx)
        }
    }

    fun hide() {
        handler.post {
            val v = view ?: return@post
            FloatWindows.remove(v)
            view = null
            params = null
            holder = null
        }
    }

    fun setRecording(on: Boolean) {
        recording = on
        handler.post { refreshState() }
    }

    /** 转屏后重算窗口宽度（横屏收窄到屏宽 1/4） */
    fun onConfigChanged(ctx: Context) {
        handler.post {
            val p = params ?: return@post
            p.width = Display.dpInt(ctx, FloatWindows.widthDp(ctx))
            FloatWindows.update(view, p)
        }
    }

    /** 动作列表变化后刷新 */
    fun refresh(script: Script) {
        handler.post { fillList(script) }
    }

    // ================= 构建 =================

    private fun buildView(ctx: Context, script: Script, cb: Callback): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg(ctx)
            elevation = Display.dp(ctx, 10f)
        }

        // ---- 标题栏：脚本名 + 状态点 + ✕ ----
        val head = FrameLayout(ctx).apply {
            setPadding(Display.dpInt(ctx, 12f), Display.dpInt(ctx, 10f),
                Display.dpInt(ctx, 8f), Display.dpInt(ctx, 8f))
        }
        val titleBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                marginEnd = Display.dpInt(ctx, 40f)
            }
        }
        val dot = TextView(ctx).apply {
            text = "●"
            textSize = 8.5f
            setTextColor(Theme.ok())
            setPadding(0, 0, Display.dpInt(ctx, 5f), 0)
        }
        val title = TextView(ctx).apply {
            text = script.name
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.textPri())
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        titleBox.addView(dot)
        titleBox.addView(title)
        head.addView(titleBox)
        head.addView(roundBtn(ctx, "✕") { hide() }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        })
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
            // 动作多时列表内部滚动，窗口高度封顶，不会顶满屏幕。
            // ScrollView 没有 maxHeight 属性，用固定高度（屏高一半）夹紧
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                FloatWindows.maxHeightPx(ctx) / 2)
            layoutParams = lp
        }
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- 底部三键：运行 / 录制 / 更多 ----
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Display.dpInt(ctx, 10f), Display.dpInt(ctx, 8f),
                Display.dpInt(ctx, 10f), Display.dpInt(ctx, 10f))
        }
        bar.addView(flatBtn(ctx, "运行", Theme.pri()) { cb.onRun(script) },
            LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = Display.dpInt(ctx, 4f)
            })
        val recBtn = flatBtn(ctx, "录制", Theme.ok()) {
            cb.onRecord(script, !recording)
        }
        bar.addView(recBtn, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = Display.dpInt(ctx, 2f)
            marginEnd = Display.dpInt(ctx, 2f)
        })
        // 三键等分：早前「⋯」用固定 40dp，窄屏时把「运行」「录制」挤出可视区，
        // 看起来就像只剩一个按钮
        bar.addView(flatBtn(ctx, "⋯", Theme.textSec()) { toggleMore() },
            LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f).apply {
                marginStart = Display.dpInt(ctx, 2f)
            })
        root.addView(bar)

        // ---- 更多（默认收起）----
        val more = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(Display.dpInt(ctx, 10f), 0,
                Display.dpInt(ctx, 10f), Display.dpInt(ctx, 10f))
        }
        // 与自动精灵「更多」一致：添加动作 / 更多工具 / 保存脚本 / 清空动作 /
        // 开启日志 / 查看变量 / 全局设置
        more.addView(moreRow(ctx, "添加动作") { cb.onAddAction(script) })
        more.addView(moreRow(ctx, "更多工具") { cb.onTools(script) })
        more.addView(moreRow(ctx, "保存脚本") { cb.onSave(script) })
        more.addView(moreRow(ctx, "清空动作") { cb.onClear(script) })
        more.addView(moreRow(ctx, "开启日志") { cb.onToggleLog(script) })
        more.addView(moreRow(ctx, "查看变量") { cb.onVars(script) })
        more.addView(moreRow(ctx, "全局设置") { cb.onSettings(script) })
        root.addView(more)

        holder = Holder(list, more, dot, recBtn, title)
        fillList(script)
        root.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT)
        return root
    }

    // ================= 列表 =================

    private fun fillList(script: Script) {
        val h = holder ?: return
        h.title.text = script.name
        h.list.removeAllViews()
        val acts = script.flow?.actions ?: emptyList()
        if (acts.isEmpty()) {
            h.list.addView(TextView(h.list.context).apply {
                text = "脚本为空  请先添加一个动作"
                textSize = 12.5f
                setTextColor(Theme.textSec())
                gravity = Gravity.CENTER
                setPadding(Display.dpInt(context, 12f),
                    Display.dpInt(context, 20f),
                    Display.dpInt(context, 12f),
                    Display.dpInt(context, 20f))
            })
            return
        }
        acts.forEachIndexed { i, a ->
            h.list.addView(TextView(h.list.context).apply {
                text = "${i + 1}. ${ActionEditor.describe(a)}"
                textSize = 12f
                setTextColor(Theme.textSec())
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(Display.dpInt(context, 12f),
                    Display.dpInt(context, 6f),
                    Display.dpInt(context, 12f),
                    Display.dpInt(context, 6f))
            })
        }
    }

    // ================= 状态 =================

    private fun refreshState() {
        val h = holder ?: return
        h.dot.setTextColor(if (recording) Theme.danger() else Theme.ok())
        if (recording) startBlink(h.dot) else {
            h.dot.clearAnimation(); h.dot.alpha = 1f
        }
        h.recBtn.text = if (recording) "停止" else "录制"
        runCatching { wm?.updateViewLayout(view, params) }
    }

    private fun startBlink(tv: TextView) {
        tv.animate().alpha(0.25f).setDuration(500)
            .withEndAction {
                tv.animate().alpha(1f).setDuration(500)
                    .withEndAction { if (recording) startBlink(tv) }.start()
            }.start()
    }

    private fun toggleMore() {
        val h = holder ?: return
        h.more.visibility = if (h.more.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        runCatching { wm?.updateViewLayout(view, params) }
    }

    // ================= 复用件 =================

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
            layoutParams = FrameLayout.LayoutParams(sz, sz)
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

    /** 标题栏拖动：超过 8dp 视为移动，不触发点击 */
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
}
