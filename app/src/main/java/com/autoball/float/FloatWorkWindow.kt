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
        /** 点击列表中某一步：编辑该动作 */
        fun onEditAction(script: Script, index: Int)
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
        val moreWrap: android.widget.ScrollView,
        val dot: TextView,
        val title: TextView,
        val mainBar: LinearLayout,
        val recBar: LinearLayout
    )

    private var holder: Holder? = null
    /** 当前回调，供空态窗口的两个入口按钮使用 */
    private var cbRef: Callback? = null

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
            // 先存回调再构建：buildView 内会渲染空态按钮，
            // 那些按钮点击时要用 cbRef（延迟执行，此处赋值即可）
            cbRef = cb
            curScript = script
            curCb = cb
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
            curScript = null
            curCb = null
            cbRef = null
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
            // 悬浮球 / 悬浮窗也要一并收起。
            // 录制的是**别的 App**上的操作，本应用的任何界面留在屏幕上都会：
            // 1) 挡住目标 App 的按钮；2) 自己抢走触摸（尤其是悬浮球可拖动的命中区）。
            // 只留贴边胶囊——它是停止录制的唯一入口，不能收。
            // 先记录"哪些本来是开着的"——restore() 只还原确实显示过的组件，
            // 不记的话录制结束会凭空冒出一个用户根本没开的悬浮球
            FloatManager.markShown()
            FloatManager.hideAll()
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
            // 录制结束后把悬浮球 / 悬浮窗放回来（按用户原本的开关状态）
            FloatManager.restore()
        }
    }

    /** 动作列表变化后刷新 */
    fun refresh(script: Script) {
        handler.post { fillList(script); refreshCap() }
    }

    /**
     * 转屏后重算窗口尺寸（横屏收窄到屏宽 1/4）。
     *
     * **为什么这里重新构建而不是只改宽度**：动作列表的最大高度是按当时屏幕
     * 算出来的固定像素值，只改宽度的话，横屏下列表仍按竖屏的限高显示，
     * 窗口会被撑到屏幕外。所以转屏必须重建整个内容视图，再塞回同一个窗口
     * 位置——用户看到的窗口不跳、不闪，只是换了尺寸。
     */
    fun onConfigChanged(ctx: Context) {
        handler.post {
            val p = params ?: return@post
            val old = view ?: return@post
            val script = curScript
            val cb = curCb
            if (script == null || cb == null) {
                // 没有可重建的数据时，退化为只改宽度
                p.width = Display.dpInt(ctx, FloatWindows.widthDp(ctx))
                FloatWindows.update(old, p)
                return@post
            }
            FloatWindows.remove(old)
            val v = buildView(ctx.applicationContext, script, cb)
            p.width = Display.dpInt(ctx, FloatWindows.widthDp(ctx))
            view = v
            FloatWindows.add(ctx, v, p)
            capParams?.let { capView?.let { FloatWindows.update(it, it.layoutParams as WindowManager.LayoutParams) } }
        }
    }

    /** 当前宿主脚本与回调，供转屏后重建内容 */
    private var curScript: Script? = null
    private var curCb: Callback? = null

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
        // 右上：设置图标（脚本全局设置）+ 竖三点菜单 + 关闭
        // 设置做成独立图标按钮，不再藏进「⋯」菜单里——它是高频入口
        val rightBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
        }
        rightBox.addView(roundBtn(ctx, "⚙") { cb.onSettings(script) })
        rightBox.addView(roundBtn(ctx, "⋮") { toggleMore() })
        rightBox.addView(roundBtn(ctx, "✕") { hide() })
        head.addView(rightBox)
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
            // 列表固定 4 行高：窗口大小恒定，超出部分内部滚动。
            // 早前用 WRAP_CONTENT，空态时塌成一条、动作多了又顶满屏幕，
            // 窗口忽大忽小没法用。
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                FloatWindows.listHeightPx(ctx))
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
        // 运行 / 录制 收窄（0.8 权重），腾出位置给「添加动作」——它是最常用入口，
        // 藏在菜单里每次都要多点两下
        mainBar.addView(flatBtn(ctx, "运行", Theme.pri()) { cb.onRun(script) },
            LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f).apply {
                marginEnd = Display.dpInt(ctx, 3f)
            })
        mainBar.addView(flatBtn(ctx, "录制", Theme.ok()) {
            cb.onRecord(script, !recording)
        }, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f).apply {
            marginStart = Display.dpInt(ctx, 3f)
            marginEnd = Display.dpInt(ctx, 3f)
        })
        mainBar.addView(flatBtn(ctx, "添加动作", Theme.pri2()) { cb.onAddAction(script) },
            LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f).apply {
                marginStart = Display.dpInt(ctx, 3f)
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
            LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f).apply {
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
        // 包一层 ScrollView：菜单项多时窗口会被撑高，触发上面的最大高度限制后，
        // 没有滚动容器的话多出来的项就点不到了。
        val moreWrap = android.widget.ScrollView(ctx).apply {
            visibility = View.GONE
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val more = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
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
        moreWrap.addView(more, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(moreWrap, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT, 0f))

        holder = Holder(list, more, moreWrap, dot, title, mainBar, recBar)
        fillList(script)
        refreshState()
        root.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT)

        // 整体最大高度：列表已固定 4 行，但展开「更多」菜单会把窗口撑高，
        // 菜单项一多就顶满甚至超出屏幕。这里夹住上限，超出部分内部滚动。
        root.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int,
                                        ol: Int, ot: Int, or_: Int, ob: Int) {
                val maxH = FloatWindows.maxHeightPx(ctx)
                val p = params ?: return
                if (b - t > maxH && p.height != maxH) {
                    p.height = maxH
                    runCatching { wm?.updateViewLayout(view, p) }
                }
            }
        })
        return root
    }

    // ================= 列表 =================

    private fun fillList(script: Script) {
        val h = holder ?: return
        h.title.text = script.name
        h.list.removeAllViews()
        val acts = script.flow?.actions ?: emptyList()
        if (acts.isEmpty()) {
            // 空态窗口：与有动作的窗口**分成两个形态**。
            // 空态不能运行、也没有列表可看，给「运行」按钮只会让人点了报错；
            // 这里直接换成两个入口按钮，与自动精灵空态一致。
            h.list.addView(TextView(h.list.context).apply {
                text = "脚本为空  请先添加一个动作"
                textSize = 12.5f
                setTextColor(Theme.textSec())
                gravity = Gravity.CENTER
                setPadding(Display.dpInt(context, 12f),
                    Display.dpInt(context, 14f),
                    Display.dpInt(context, 12f),
                    Display.dpInt(context, 10f))
            })
            val row = LinearLayout(h.list.context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(Display.dpInt(context, 12f), 0,
                    Display.dpInt(context, 12f), Display.dpInt(context, 12f))
            }
            val lc = h.list.context
            row.addView(bigBtn(lc, "开始录制", Theme.ok()) {
                cbRef?.onRecord(script, true)
            }, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = Display.dpInt(lc, 5f)
            })
            row.addView(bigBtn(lc, "添加动作", Theme.pri()) {
                cbRef?.onAddAction(script)
            }, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Display.dpInt(lc, 5f)
            })
            h.list.addView(row)
            return
        }
        acts.forEachIndexed { i, a ->
            val lc = h.list.context
            val row = LinearLayout(lc).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // 行高固定，保证"至少四行"是可预期的
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    Display.dpInt(lc, FloatWindows.ROW_H_DP))
                setPadding(Display.dpInt(lc, 12f), 0,
                    Display.dpInt(lc, 6f), 0)
                // 录制结束后要在这里逐条改参数，所以整行可点
                setOnClickListener { cbRef?.onEditAction(script, i) }
                background = Theme.rect(android.graphics.Color.TRANSPARENT, 6f, lc)
            }
            row.addView(TextView(lc).apply {
                text = "${i + 1}. ${ActionEditor.describe(a)}"
                textSize = 12f
                setTextColor(Theme.textSec())
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            // 删除这一步：录制时误触的一步直接在列表里删掉，
            // 不必进编辑框再退出
            row.addView(TextView(lc).apply {
                text = "✕"
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Theme.textTer())
                gravity = Gravity.CENTER
                val sz = Display.dpInt(lc, 24f)
                layoutParams = LinearLayout.LayoutParams(sz, sz)
                setOnClickListener {
                    script.flow?.actions?.removeAt(i)
                    com.autoball.AB.store.save(script)
                    refresh(script)
                }
            })
            h.list.addView(row)
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
        val empty = (curScript?.flow?.actions?.size ?: 0) == 0
        h.mainBar.visibility = if (recording || empty) View.GONE else View.VISIBLE
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
        h.moreWrap.visibility =
            if (h.moreWrap.visibility == View.VISIBLE) View.GONE else View.VISIBLE
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
                setTag(com.autoball.R.id.work_state, this)
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
        (v.findViewWithTag<TextView>(com.autoball.R.id.work_state))
            ?.text = currentSteps().toString()
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

    /** 空态入口大按钮（比 flatBtn 更高，突出"从哪开始"） */
    private fun bigBtn(ctx: Context, text: String, color: Int,
                       onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(color)
            gravity = Gravity.CENTER
            background = Theme.rect(Theme.surface2(), 12f, ctx, Theme.line())
            setPadding(Display.dpInt(ctx, 10f), Display.dpInt(ctx, 13f),
                Display.dpInt(ctx, 10f), Display.dpInt(ctx, 13f))
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
