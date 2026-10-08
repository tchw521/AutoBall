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
import com.autoball.core.recorder.RecordController
import com.autoball.core.util.Display
import com.autoball.ui.ActionEditor
import com.autoball.ui.Theme

/**
 * 录制 / 添加动作悬浮窗（统一组件）——**一比一复刻自动精灵**。
 *
 * 自动精灵的录制小窗形态（**本应用只保留这一个窗口**）：
 * ```
 * ┌───────────────────────────┐
 * │ 未命名脚本              ✕ │   标题栏，可拖动
 * ├───────────────────────────┤
 * │ 1. 点击(63.6%, 49.7%)     │   动作列表，百分比坐标
 * │ 2. 长按(65.4%, 55.2%)     │
 * │    空态：脚本为空 请先添加一个动作 │
 * ├───────────────────────────┤
 * │ [运行] [录制] [⋯]         │   底部三键
 * └───────────────────────────┘
 * ```
 *
 * 录制中底部三键切换为录制控制条（自动精灵同为原地切换，不再另开浮层）：
 * `[暂停/继续] [撤销] [等待1s] [停止]`
 *
 * **融合说明**：早前录制时另有一个白色小窗（RecChrome）与独立提示条，
 * 两者与本窗口叠在一起，既遮挡目标应用又互相挡住按钮。
 * 现全部并入本窗口，录制期间屏幕上只有这一个窗口。
 *
 * **录制时让出屏幕**：[enterStealth] 把本窗口也一并隐藏，
 * 只留一枚贴边胶囊显示步数——这样采集层采到的是真实的目标应用操作。
 */
object FloatWorkWindow {

    interface Callback {
        fun onRun(script: Script)
        /** willRecord 表示操作**后**的状态 */
        fun onRecord(script: Script, willRecord: Boolean)
        fun onAddAction(script: Script)
        fun onSave(script: Script)
        fun onClear(script: Script)
        fun onToggleLog(script: Script)
        /** 打开「更多工具」快捷动作面板 */
        fun onTools(script: Script)
        fun onVars(script: Script)
        fun onSettings(script: Script)
        /** 录制控制：暂停/继续、撤销、插入等待、停止 */
        fun onPause(script: Script, willPause: Boolean)
        fun onUndo(script: Script)
        fun onInsertWait(script: Script, ms: Long)
        fun onStopRecord(script: Script)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null
    private var recording = false
    private var paused = false
    private var stealth = false

    /** 录制时贴边的迷你胶囊（让出屏幕后唯一可见的本应用界面） */
    private var capView: View? = null
    private var capParams: WindowManager.LayoutParams? = null

    private class Holder(
        val list: LinearLayout,
        val more: LinearLayout,
        val dot: TextView,
        val title: TextView,
        val mainBar: LinearLayout,
        val recBar: LinearLayout
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
            removeCap()
            stealth = false
        }
    }

    fun setRecording(on: Boolean) {
        recording = on
        paused = false
        handler.post { refreshState() }
    }

    fun setPaused(on: Boolean) {
        paused = on
        handler.post { refreshState() }
    }

    /**
     * 录制时让出屏幕：隐藏主窗口，只留贴边胶囊。
     *
     * 这是自动精灵的做法——录制期间屏幕上不能盖着一块大浮层，
     * 否则既遮挡目标应用，采集层也容易把点击判为不可信遮挡。
     */
    fun enterStealth(ctx: Context) {
        handler.post {
            if (stealth) return@post
            stealth = true
            val v = view
            // 只从窗口摘下，**保留 view 引用**——否则 exitStealth 拿不回原窗口，
            // 只能重建，已填的表单和滚动位置都会丢
            if (v != null) FloatWindows.remove(v)
            showCap(ctx)
        }
    }

    /** 退出让出屏幕：收回胶囊，恢复主窗口 */
    fun exitStealth(ctx: Context) {
        handler.post {
            if (!stealth) return@post
            stealth = false
            removeCap()
            val v = view
            val p = params
            if (v != null && p != null) FloatWindows.add(ctx, v, p)
        }
    }

    /** 动作列表变化后刷新 */
    fun refresh(script: Script) {
        handler.post { fillList(script); refreshCap() }
    }

    /** 转屏后重算窗口宽度（横屏收窄到屏宽 1/4） */
    fun onConfigChanged(ctx: Context) {
        handler.post {
            val p = params ?: return@post
            p.width = Display.dpInt(ctx, FloatWindows.widthDp(ctx))
            FloatWindows.update(view, p)
            capParams?.let { cp ->
                cp.y = 0
                capView?.let { FloatWindows.update(it, cp) }
            }
        }
    }

    // ================= 构建 =================

    private fun buildView(ctx: Context, script: Script, cb: Callback): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg(ctx)
            elevation = Display.dp(ctx, 10f)
        }

        // ---- 标题栏：状态点 + 脚本名 + ✕ ----
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

        // ---- 动作列表（限高，动作多时内部滚动）----
        val scroll = ScrollView(ctx).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // ScrollView 没有 maxHeight 属性，用固定高度（屏高一半）夹紧，
            // 窗口不会越滚越长顶满屏幕
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                FloatWindows.maxHeightPx(ctx) / 2)
        }
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))

        // ---- 底部主条：运行 / 录制 / 更多 ----
        val mainBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Display.dpInt(ctx, 10f), Display.dpInt(ctx, 8f),
                Display.dpInt(ctx, 10f), Display.dpInt(ctx, 10f))
        }
        mainBar.addView(flatBtn(ctx, "运行", Theme.pri()) { cb.onRun(script) },
            LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = Display.dpInt(ctx, 4f)
            })
        mainBar.addView(flatBtn(ctx, "录制", Theme.ok()) {
            cb.onRecord(script, !recording)
        }, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = Display.dpInt(ctx, 2f)
            marginEnd = Display.dpInt(ctx, 2f)
        })
        // 三键等分：早前「⋯」用固定 40dp，窄屏时会把「运行」「录制」挤出可视区
        mainBar.addView(flatBtn(ctx, "⋯", Theme.textSec()) { toggleMore() },
            LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f).apply {
                marginStart = Display.dpInt(ctx, 2f)
            })
        root.addView(mainBar)

        // ---- 录制控制条（录制中显示，替换主条）----
        val recBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Display.dpInt(ctx, 10f), Display.dpInt(ctx, 8f),
                Display.dpInt(ctx, 10f), Display.dpInt(ctx, 10f))
            visibility = View.GONE
        }
        recBar.addView(flatBtn(ctx, "暂停", Theme.warn()) {
            cb.onPause(script, !paused)
        }, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = Display.dpInt(ctx, 3f)
        })
        recBar.addView(flatBtn(ctx, "撤销", Theme.textSec()) { cb.onUndo(script) },
            LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Display.dpInt(ctx, 3f)
                marginEnd = Display.dpInt(ctx, 3f)
            })
        recBar.addView(flatBtn(ctx, "等待1s", Theme.textSec()) {
            cb.onInsertWait(script, 1000)
        }, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = Display.dpInt(ctx, 3f)
            marginEnd = Display.dpInt(ctx, 3f)
        })
        recBar.addView(flatBtn(ctx, "停止", Theme.danger()) { cb.onStopRecord(script) },
            LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Display.dpInt(ctx, 3f)
            })
        root.addView(recBar)

        // ---- 更多（默认收起）----
        val more = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(Display.dpInt(ctx, 10f), 0,
                Display.dpInt(ctx, 10f), Display.dpInt(ctx, 10f))
        }
        more.addView(moreRow(ctx, "添加动作") { cb.onAddAction(script) })
        more.addView(moreRow(ctx, "更多工具") { cb.onTools(script) })
        more.addView(moreRow(ctx, "保存脚本") { cb.onSave(script) })
        more.addView(moreRow(ctx, "清空动作") { cb.onClear(script) })
        more.addView(moreRow(ctx, "开启日志") { cb.onToggleLog(script) })
        more.addView(moreRow(ctx, "查看变量") { cb.onVars(script) })
        more.addView(moreRow(ctx, "全局设置") { cb.onSettings(script) })
        root.addView(more)

        holder = Holder(list, more, dot, title, mainBar, recBar)
        fillList(script)
        refreshState()
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
        h.dot.setTextColor(when {
            recording && paused -> Theme.warn()
            recording -> Theme.danger()
            else -> Theme.ok()
        })
        if (recording && !paused) startBlink(h.dot) else {
            h.dot.clearAnimation(); h.dot.alpha = 1f
        }
        // 录制中显示控制条，否则显示主条——自动精灵同为原地切换
        h.mainBar.visibility = if (recording) View.GONE else View.VISIBLE
        h.recBar.visibility = if (recording) View.VISIBLE else View.GONE
        if (recording) {
            val pb = h.recBar.getChildAt(0) as? TextView
            pb?.text = if (paused) "继续" else "暂停"
        }
        runCatching { wm?.updateViewLayout(view, params) }
    }

    private fun startBlink(tv: TextView) {
        tv.animate().alpha(0.25f).setDuration(500)
            .withEndAction {
                tv.animate().alpha(1f).setDuration(500)
                    .withEndAction { if (recording && !paused) startBlink(tv) }.start()
            }.start()
    }

    private fun toggleMore() {
        val h = holder ?: return
        h.more.visibility = if (h.more.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        runCatching { wm?.updateViewLayout(view, params) }
    }

    // ================= 录制胶囊（让出屏幕时的唯一界面） =================

    private fun showCap(ctx: Context) {
        val steps = currentSteps()
        val v = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Theme.surface())
                cornerRadius = Display.dp(ctx, 20f)
                setStroke(Display.dpInt(ctx, 1f), Theme.line())
            }
            elevation = Display.dp(ctx, 6f)
            addView(TextView(ctx).apply {
                text = "●"
                textSize = 9f
                setTextColor(Theme.danger())
                gravity = Gravity.CENTER
            })
            addView(TextView(ctx).apply {
                text = "$steps"
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Theme.textPri())
                gravity = Gravity.CENTER
                setTag(1, this)
            })
            val sz = Display.dpInt(ctx, 54f)
            // 点一下恢复主窗口（可继续暂停/停止）
            setOnClickListener { exitStealth(ctx) }
            layoutParams = FrameLayout.LayoutParams(sz, Display.dpInt(ctx, 62f))
        }
        val p = WindowManager.LayoutParams(
            Display.dpInt(ctx, 54f), Display.dpInt(ctx, 62f),
            FloatWindows.overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        p.x = 0
        capView = v
        capParams = p
        FloatWindows.add(ctx, v, p)
    }

    private fun removeCap() {
        val v = capView ?: return
        FloatWindows.remove(v)
        capView = null
        capParams = null
    }

    private fun refreshCap() {
        val v = capView ?: return
        (v.findViewWithTag<TextView>(1))?.text = currentSteps().toString()
    }

    private var stepsRef: (() -> Int)? = null

    /** 由宿主注入当前脚本步数，供胶囊显示 */
    fun bindSteps(src: () -> Int) { stepsRef = src }

    private fun currentSteps(): Int = runCatching { stepsRef?.invoke() ?: 0 }.getOrDefault(0)

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
}
