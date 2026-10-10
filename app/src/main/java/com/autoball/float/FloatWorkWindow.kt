package com.autoball.float

import android.content.Context
import android.content.Intent
import android.graphics.Color
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
        val recBar: LinearLayout,
        val emptyBar: LinearLayout
    )

    private var holder: Holder? = null
    /** 当前回调，供空态窗口的两个入口按钮使用 */
    private var cbRef: Callback? = null

    /** 空态底栏图标边长（dp）。26dp 时按钮过大，收到 18dp */
    private const val ICON_DP = 18f

    /** 空态底栏宽度 = 窗口宽 × 该比例（用户要求"底部栏宽度降低一半"） */
    private const val EMPTY_BAR_W_SCALE = 0.5f

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
        handler.post {
            fillList(script)
            refreshCap()
            // 列表行数变了（4~8 行夹取）会改变窗口高度，
            // 光改子 View 的 LayoutParams 不够——WindowManager 的窗口尺寸
            // 必须显式 updateViewLayout 才会重算，否则窗口停在旧尺寸上，
            // 新加的几行被裁在外面看不到。
            runCatching { wm?.updateViewLayout(view, params) }
        }
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
                // 没有可重建的数据时，退化为只改尺寸（同样必须走统一规则）
                applyWindowSize(ctx, p)
                FloatWindows.update(old, p)
                return@post
            }
            FloatWindows.remove(old)
            val v = buildView(ctx.applicationContext, script, cb)
            applyWindowSize(ctx, p)
            view = v
            FloatWindows.add(ctx, v, p)
            capParams?.let { capView?.let { FloatWindows.update(it, it.layoutParams as WindowManager.LayoutParams) } }
        }
    }

    /** 当前宿主脚本与回调，供转屏后重建内容 */
    private var curScript: Script? = null
    private var curCb: Callback? = null

    /**
     * 把窗口参数对齐到统一尺寸规则（宽高比同手机、横竖屏一致）。
     *
     * 此前这里写的是 `widthDp(ctx)`——那是**旧的**宽度规则（竖屏屏宽 1/2、
     * 横屏 1/4），而窗口内部布局用的是 `windowSizeDp`（带 0.8/0.5 收缩）。
     * 两套规则并存的结果是：转屏后窗口宽度突然跳到另一个标准，
     * 内容区按另一套宽度排版，右侧空一块或内容被裁掉。
     *
     * 而且**高度此前完全没有更新**：横屏可用高度变小时，窗口高度仍停留在
     * 竖屏算出来的值，底条会被排到屏幕外——与之前"底部按钮不见了"同源。
     *
     * y 也要重新夹取：竖屏的 y 在横屏下可能已超出屏幕。
     */
    private fun applyWindowSize(ctx: Context, p: WindowManager.LayoutParams) {
        val sz = Display.screenSize(ctx)
        p.width = Display.dpInt(ctx, FloatWindows.windowSizeDp(ctx).first)
        p.height = FloatWindows.frameHeightPx(ctx)
        val maxY = (sz.y - p.height).coerceAtLeast(0)
        p.y = p.y.coerceIn(0, maxY)
        val maxX = (sz.x - p.width).coerceAtLeast(0)
        p.x = p.x.coerceIn(0, maxX)
    }

    // ================= 构建 =================

    private fun buildView(ctx: Context, script: Script, cb: Callback): View {
        // 窗口整体固定大小（宽高比同手机屏幕、横竖屏一致），
        // 内容区吃掉剩下的高度并内部滚动。
        // 此前 root 是 WRAP_CONTENT：动作多、菜单展开、脚本名长都会把窗口撑高。
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg(ctx)
            elevation = Display.dp(ctx, 10f)
        }
        root.layoutParams = LinearLayout.LayoutParams(
            Display.dpInt(ctx, FloatWindows.windowSizeDp(ctx).first),
            FloatWindows.frameHeightPx(ctx))

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
            textSize = TextSz.TITLE
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
        // 「⋮」已移到**底部条最右**（见下方 mainBar）：标题栏右侧只留设置与关闭，
        // 高频的"更多"离拇指更近，也更符合"下方是操作区"的分区习惯。
        rightBox.addView(roundBtn(ctx, "⚙") { cb.onSettings(script) })
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
        //
        // 尺寸按用户要求**明显缩小**：宽取窗口的 1/3、高取内容区的 1/2。
        // 窗口里还有标题栏、底部条、更多菜单，列表只作为"当前进度提示"，
        // 不必占满。外面再包一层容器做**居中**——列表偏在一角不好看。
        val scroll = ScrollView(ctx).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT)
        }
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        // 注意：**不要**在 addView 里再传一个 LayoutParams。
        // 上面 apply{} 里已经给 ScrollView 设了定高，
        // 而 addView(view, params) 会用这个新的 params 覆盖原来的——
        // 早前这里传的是 WRAP_CONTENT，直接把限高冲掉了，
        // 于是动作一多窗口就一路变长（17 步时顶满屏幕）。
        val listWrap = android.widget.FrameLayout(ctx).apply {
            // 最小高度：菜单展开时列表被 weight 压得很小，
            // 没有下限的话会塌成一条，看不出当前有几步
            minimumHeight = Display.dpInt(ctx, FloatWindows.MIN_CONTENT_DP) / 2
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(scroll, android.widget.FrameLayout.LayoutParams(
                (Display.dpInt(ctx, FloatWindows.windowSizeDp(ctx).first)
                        * FloatWindows.LIST_WIDTH_SCALE).toInt(),
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL))
        }
        root.addView(listWrap)

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
        // mainBar 与 recBar 是**互斥显示**的（刷新时原地切换），
        // 直接挂到 root 会导致切换时底栏高度跳变。装进同一个 slot 更稳。
        val barSlot = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        barSlot.addView(mainBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))

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
        barSlot.addView(recBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))

        // ---- 空态底栏：开始录制 / 添加动作（图标按钮）----
        //
        // 用户要求：这两个入口**改成图标**并**放到最下面**。
        // 此前它们是列表里的文字按钮，既被 1/3 宽的容器挤变形，也不在底栏。
        val emptyBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(Display.dpInt(ctx, 10f), Display.dpInt(ctx, 8f),
                Display.dpInt(ctx, 10f), Display.dpInt(ctx, 10f))
            visibility = View.GONE
        }
        emptyBar.addView(iconEntry(ctx, RecIconView.Kind.REC, "开始录制", Theme.ok()) {
            cbRef?.onRecord(script, true)
        }, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = Display.dpInt(ctx, 5f)
        })
        emptyBar.addView(iconEntry(ctx, RecIconView.Kind.ADD, "添加动作", Theme.pri()) {
            cbRef?.onAddAction(script)
        }, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = Display.dpInt(ctx, 5f)
        })
        // 宽度减半并居中：整条铺满时两个图标按钮被拉得太宽，
        // 与"按钮太大"的反馈直接相关；居中比靠左更像是主动收窄的设计。
        barSlot.addView(emptyBar, LinearLayout.LayoutParams(
            (Display.dpInt(ctx, FloatWindows.windowSizeDp(ctx).first)
                * EMPTY_BAR_W_SCALE).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT, 0f).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        // ---- 底部条 + 常驻「⋮」----
        //
        // 「⋮」必须**常驻在右下角**，不能挂进 mainBar：
        // 空态与录制态下 mainBar 是 GONE（那时底栏换成空态入口或录制控制条），
        // 挂进去会让它在最常用的空态下**完全看不见**。
        // 所以单独起一行：左边是随状态切换的底栏，右边是永远在的「⋮」。
        val barRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        barRow.addView(barSlot, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        barRow.addView(barMoreBtn(ctx) { toggleMore() },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = Display.dpInt(ctx, 8f)
            })
        root.addView(barRow)

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
        // 不再放「添加动作」：底部条已有一个常驻入口，菜单里再来一条
        // 只会让人犹豫该点哪个（且两处文案完全一样，看不出区别）
        more.addView(moreRow(ctx, "更多工具") { cb.onTools(script) })
        more.addView(moreRow(ctx, "保存脚本") { cb.onSave(script) })
        more.addView(moreRow(ctx, "清空动作") { cb.onClear(script) })
        more.addView(moreRow(ctx, "开启日志") { cb.onToggleLog(script) })
        more.addView(moreRow(ctx, "查看变量") { cb.onVars(script) })
        // 「全局设置」已删除：标题栏右上角有独立的 ⚙ 入口，
        // 菜单里再来一条完全同名的项只会让人以为是两个不同功能
        moreWrap.addView(more, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        // 初始收起：权重 0 不给它空间（展开时由 toggleMore 改成 2）
        root.addView(moreWrap, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 0f))

        holder = Holder(list, more, moreWrap, dot, title, mainBar, recBar, emptyBar)
        fillList(script)
        refreshState()

        // **必须是 MATCH_PARENT，不能是 WRAP_CONTENT**——这是"底部按钮不见了"的根因。
        //
        // 窗口容器高度是固定的（params.height = frameHeightPx）。
        // root 若用 WRAP_CONTENT，它的高度会按内容自然高度算；
        // 内容一旦超过容器高度，root 就比容器高，
        // LinearLayout 从顶部往下排 → **底条被排到容器外面**，
        // 既看不见也点不到（取消/添加动作/运行全都失效）。
        //
        // 用 MATCH_PARENT 后 root 高度 = 容器高度（精确测量），
        // 中间的内容区 weight=1 自动吃掉剩余，头部与底条永远在框架内。
        root.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT)

        // 兜底：万一内容所需的最小高度超过当前窗口高度（极小屏 / 超大字号），
        // 把窗口**调高**而不是裁掉底条。只增不减，避免抖动。
        root.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int,
                                        ol: Int, ot: Int, or_: Int, ob: Int) {
                val p = params ?: return
                val minH = FloatWindows.frameHeightPx(ctx)
                if (p.height < minH) {
                    p.height = minH
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
        // 空态的列表要**占满宽度**：空态里放的是「开始录制 / 添加动作」两个入口按钮，
        // 它们被塞进 1/3 宽的列表容器里会被挤没（截图里按钮消失就是这个原因）。
        // 有动作时列表才缩到 1/3 宽——那时它只是进度提示。
        (h.list.parent as? android.view.View)?.let { sc ->
            val flp = sc.layoutParams as? android.widget.FrameLayout.LayoutParams
            flp?.width = if (acts.isEmpty())
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            else (Display.dpInt(h.list.context,
                FloatWindows.windowSizeDp(h.list.context).first)
                    * FloatWindows.LIST_WIDTH_SCALE).toInt()
            if (flp != null) sc.layoutParams = flp
        }
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
            // 两个入口按钮**不再放这里**：它们在列表容器里，而列表被缩到 1/3 宽，
            // 按钮会被挤变形；且中间位置不符合"操作区在底部"的分区。
            // 已移到最底部底栏（emptyBar），见下方。
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
                textSize = TextSz.ROW
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
                textSize = TextSz.ROW_MINOR
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
        h.emptyBar.visibility = if (empty && !recording) View.VISIBLE else View.GONE
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
        val show = h.moreWrap.visibility != View.VISIBLE
        h.moreWrap.visibility = if (show) View.VISIBLE else View.GONE

        // 中间那块（列表 + 菜单）的高度按 2:1 重新分配：
        // 菜单展开时优先占满（7 项基本一眼看全），列表收缩但仍可见一点上下文。
        // LinearLayout 的 weight：值越大分到的剩余空间越多。
        //
        // 注意必须**新建** LayoutParams 再赋值——直接改已有对象的 weight
        // 不会触发重新布局（View 不知道自己变了）。
        // h.list 的父是 ScrollView，ScrollView 的父才是参与 weight 分配的
        // listWrap 容器。只取一层的话改的是 ScrollView 自己的 LayoutParams，
        // window 尺寸不会变——菜单展开时列表不会收缩。
        val listHost = h.list.parent?.let { (it as? android.view.View)?.parent }
                as? android.view.View
        if (show) {
            // 列表给**固定 3 行**、weight=0（不再参与比例分配），
            // 菜单 weight=1 吃掉剩余全部 → 菜单尽量占满，列表稳定 3 行。
            //
            // 早前两边都用 weight（2:1），但 LinearLayout 会先把
            // minimumHeight 计入再按 weight 分剩余，结果列表反而被撑到
            // 比 3 行更多、菜单被压缩——与"菜单优先"相反。
            h.moreWrap.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            // 列表按用户要求保持"内容区的 1/2"：这里用 0.5f 权重即可
            // （菜单也是 1f，两者 1:0.5 = 2:1，正好是列表占一半）
            listHost?.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0,
                FloatWindows.LIST_HEIGHT_SCALE)
        } else {
            h.moreWrap.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 0f)
            // 收起时列表独占剩余空间
            listHost?.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        runCatching { wm?.updateViewLayout(view, params) }
    }

    /** 菜单展开时列表固定 3 行（见 toggleMore 说明），菜单吃掉剩余空间 */

    // ================= 录制胶囊（让出屏幕时的唯一界面） =================

    /**
     * 录制中的悬浮标（自动精灵同款）：**可拖动** + 显示步数 + 红色停止按钮。
     *
     * 两个必须满足的点：
     * 1. **可移动**——它会盖在目标 App 上，位置不能固定；用户要能把它挪到
     *    不挡操作的地方，否则录制时想点的按钮正好被它压住，就点不到了。
     * 2. **红色停止按钮常驻**——录制期间本应用其它界面全部隐藏，
     *    这枚按钮是唯一的停止入口；藏在"点一下恢复窗口再停止"里太深。
     */
    private fun showCap(ctx: Context) {
        val steps = currentSteps()
        val v = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Theme.surface())
                cornerRadius = Display.dp(ctx, 18f)
                setStroke(Display.dpInt(ctx, 1f), Theme.line())
            }
            elevation = Display.dp(ctx, 6f)
            setPadding(Display.dpInt(ctx, 9f), Display.dpInt(ctx, 5f),
                Display.dpInt(ctx, 5f), Display.dpInt(ctx, 5f))
            // 拖动把手：只有这一块能拖，避免和按钮点击冲突
            addView(TextView(ctx).apply {
                text = "⠿"
                textSize = TextSz.GLYPH
                setTextColor(Theme.textTer())
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    Display.dpInt(ctx, 16f), Display.dpInt(ctx, 26f))
            })
            addView(TextView(ctx).apply {
                text = "●"
                textSize = TextSz.DOT
                setTextColor(Theme.danger())
                gravity = Gravity.CENTER
            })
            addView(TextView(ctx).apply {
                text = "录制中 $steps 步"
                textSize = TextSz.ROW
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Theme.textPri())
                gravity = Gravity.CENTER
                setPadding(Display.dpInt(ctx, 4f), 0, Display.dpInt(ctx, 6f), 0)
                setTag(com.autoball.R.id.work_state, this)
            })
            // 红色停止：录制期间唯一的停止入口
            addView(TextView(ctx).apply {
                text = "■"
                textSize = TextSz.GLYPH
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Theme.danger())
                    cornerRadius = Display.dp(ctx, 11f)
                }
                layoutParams = LinearLayout.LayoutParams(
                    Display.dpInt(ctx, 30f), Display.dpInt(ctx, 30f))
                setOnClickListener { cbRef?.onStopRecord(curScript ?: return@setOnClickListener) }
            })
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            FloatWindows.overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.END
        p.x = Display.dpInt(ctx, 8f)
        p.y = Display.dpInt(ctx, 96f)
        capView = v
        capParams = p
        FloatWindows.add(ctx, v, p)
        // 整块拖动：录到一半发现它挡住了要点的按钮，随手就能挪开
        capDragAttach(v, p)
    }

    /** 录制标的拖动（独立于主窗口的 [dragAttach]，用 capParams 而不是 params） */
    private fun capDragAttach(head: View, p: WindowManager.LayoutParams) {
        var sx = 0f; var sy = 0f; var px = 0; var py = 0; var moved = false
        head.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; px = p.x; py = p.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt()
                    val dy = (e.rawY - sy).toInt()
                    if (Math.abs(dx) > 6 || Math.abs(dy) > 6) {
                        moved = true
                        // gravity 是 TOP|END：x 越大越靠左，别搞反
                        p.x = px - dx
                        p.y = py + dy
                        runCatching { wm?.updateViewLayout(head, p) }
                    }
                }
                MotionEvent.ACTION_UP -> if (moved) return@setOnTouchListener true
            }
            // 返回 false 让子 View（停止按钮）还能收到点击
            !moved
        }
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
            ?.text = "录制中 ${currentSteps()} 步"
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
            textSize = TextSz.GLYPH
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
            textSize = TextSz.BAR
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(color)
            gravity = Gravity.CENTER
            background = Theme.rect(Theme.surface2(), 12f, ctx, Theme.line())
            setPadding(Display.dpInt(ctx, 8f), Display.dpInt(ctx, 9f),
                Display.dpInt(ctx, 8f), Display.dpInt(ctx, 9f))
            setOnClickListener { onClick() }
        }

    /**
     * 底部条的「⋮」按钮。
     *
     * **必须给定宽高的正方形**：底部条是横排 LinearLayout，
     * 若用 weight 或 MATCH_PARENT 宽，图标会被拉伸成椭圆。
     * 高度与 [flatBtn] 对齐（padding 相同），视觉上才像同一排。
     */
    private fun barMoreBtn(ctx: Context, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = "⋮"
            textSize = TextSz.GLYPH
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.textSec())
            gravity = Gravity.CENTER
            background = Theme.rect(Theme.surface2(), 12f, ctx, Theme.line())
            setPadding(Display.dpInt(ctx, 10f), Display.dpInt(ctx, 9f),
                Display.dpInt(ctx, 10f), Display.dpInt(ctx, 9f))
            setOnClickListener { onClick() }
            // 固定正方形边长：横排 LinearLayout 里不锁死尺寸的话，
            // 会随文字宽度变扁或被 weight 拉宽，三个点挤成一条线
            val sz = Display.dpInt(ctx, 30f)
            minWidth = sz
            minHeight = sz
            width = sz
        }

    /**
     * 底栏图标入口（开始录制 / 添加动作）。
     *
     * **只显示图标，不显示文字**（用户要求）。
     * 代价：两个圆环图标的区别只剩"圆点与十字"，初次使用不易分辨，
     * 因此保留 contentDescription，长按无障碍提示仍能区分。
     */
    private fun iconEntry(ctx: Context, kind: RecIconView.Kind, label: String,
                          color: Int, onClick: () -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Theme.rect(Theme.surface2(), 12f, ctx, Theme.line())
            // 用户反馈"按钮太大"：整体收缩，padding 减半
            setPadding(Display.dpInt(ctx, 5f), Display.dpInt(ctx, 5f),
                Display.dpInt(ctx, 5f), Display.dpInt(ctx, 5f))
            setOnClickListener { onClick() }
            val iv = RecIconView(ctx).apply {
                this.kind = kind
                this.iconColor = color
            }
            addView(iv, LinearLayout.LayoutParams(
                Display.dpInt(ctx, ICON_DP), Display.dpInt(ctx, ICON_DP)))
            // **不再显示文字**：用户要求只留图标。
            // 代价是两个圆环图标的区别只剩"圆点与十字"，初次使用不易分辨，
            // 所以保留 contentDescription，长按无障碍提示仍可区分。
            contentDescription = label
        }

    private fun moreRow(ctx: Context, text: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = TextSz.MENU
            setTextColor(Theme.textSec())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Display.dpInt(ctx, 10f), Display.dpInt(ctx, 7f),
                Display.dpInt(ctx, 10f), Display.dpInt(ctx, 7f))
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
