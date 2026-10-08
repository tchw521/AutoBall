package com.autoball.float

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import com.autoball.AB
import com.autoball.core.engine.ScriptLauncher
import com.autoball.core.model.BallSlot
import com.autoball.core.util.Display

/**
 * 悬浮球 / 悬浮窗的窗口管理。
 *
 * 悬浮球与悬浮窗是两种独立形态，可同时存在；录制时悬浮窗自动隐藏（需求 2.5）。
 */
object FloatManager {

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var ball: FloatBallView? = null
    @Volatile
    private var ballParams: WindowManager.LayoutParams? = null
    @Volatile
    private var panel: FloatPanelView? = null
    @Volatile
    private var panelParams: WindowManager.LayoutParams? = null
    @Volatile
    private var wm: WindowManager? = null

    @Volatile
    var recording: Boolean = false
        private set

    fun isBallShown(): Boolean = ball != null

    // ---------- 悬浮球 ----------

    fun showBall(context: Context) {
        lastCtx = context.applicationContext
        if (!Display.canDrawOverlay(context)) {
            AB.log.warn("float", "未获得悬浮窗权限")
            return
        }
        handler.post {
            if (ball != null) return@post
            val ctx = context.applicationContext
            val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm = manager

            val view = FloatBallView(ctx, ballListener)
            val size = AB.store.getFloat("ball_size_dp", 48f)
            view.setSizeDp(size)
            view.idleAlpha = AB.store.getFloat("ball_idle_alpha", 0.72f)

            val p = WindowManager.LayoutParams(
                Display.dpInt(ctx, size), Display.dpInt(ctx, size),
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            p.gravity = Gravity.TOP or Gravity.START
            p.x = AB.store.getInt("ball_x", 40)
            p.y = AB.store.getInt("ball_y", Display.screenSize(ctx).y / 3)
            ballParams = p
            ball = view
            runCatching { manager.addView(view, p) }
            AB.log.info("float", "悬浮球已显示")
        }
    }

    fun hideBall() {
        handler.post {
            val v = ball ?: return@post
            val p = ballParams
            runCatching { wm?.removeView(v) }
            if (p != null && AB.store.getBool("ball_remember_pos", true)) {
                AB.store.putInt("ball_x", p.x)
                AB.store.putInt("ball_y", p.y)
            }
            ball = null
            ballParams = null
        }
    }

    fun setRunning(running: Boolean) {
        handler.post { ball?.running = running }
    }

    // ---------- 悬浮窗 ----------

    fun showPanel(context: Context) {
        lastCtx = context.applicationContext
        if (!Display.canDrawOverlay(context)) return
        handler.post {
            if (panel != null) return@post
            val ctx = context.applicationContext
            val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm = manager
            val view = FloatPanelView(ctx, panelListener)
            val skinName = AB.store.getString("panel_skin", FloatPanelView.Skin.DEFAULT.name)
            val skin = try { FloatPanelView.Skin.valueOf(skinName) }
                       catch (e: Exception) { FloatPanelView.Skin.DEFAULT }
            // 自定义布局启用时按用户配置的列数排布（R-118）
            val cols = AB.store.getInt("panel_cols", 1).coerceIn(1, 4)
            view.apply(skin, AB.store.getFloat("panel_button_dp", 40f), cols)

            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            p.gravity = Gravity.TOP or Gravity.END
            p.x = 12; p.y = Display.screenSize(ctx).y / 4
            panelParams = p
            panel = view
            runCatching { manager.addView(view, p) }
        }
    }

    /** 运行时更新悬浮窗上的步骤名；传 null 表示清空 */
    fun setStep(text: String?) {
        handler.post { panel?.setStep(text) }
    }

    /**
     * 重建悬浮窗（配置变更后让用户立刻看到效果）。
     *
     * 只在**已经显示**时重建：没显示却去 showPanel 会凭空弹出一个窗口，
     * 用户只是在设置页改配置，不该被强行弹窗打扰。
     */
    fun refresh() {
        handler.post {
            val v = panel ?: return@post
            runCatching { wm?.removeView(v) }
            panel = null
            val c = lastCtx ?: return@post
            showPanel(c)
        }
    }

    fun hidePanel() {
        handler.post {
            val v = panel ?: return@post
            runCatching { wm?.removeView(v) }
            panel = null
            panelParams = null
        }
    }

    /**
     * 转屏后重算悬浮球位置。
     *
     * 悬浮球存的是绝对坐标，转屏后仍按旧坐标摆放会跑到屏幕外——
     * 这里按比例映射到新屏幕，并夹回可视区。
     */
    fun onConfigChanged(ctx: Context) {
        val sz = Display.screenSize(ctx)
        val bx = AB.store.getInt("ball_x", -1)
        val by = AB.store.getInt("ball_y", -1)
        if (bx < 0 || by < 0) return
        // 旧屏幕尺寸未知（首次安装未记录）时按当前屏夹回即可
        val nx = bx.coerceIn(0, (sz.x - Display.dpInt(ctx, 56f)).coerceAtLeast(0))
        val ny = by.coerceIn(0, (sz.y - Display.dpInt(ctx, 120f)).coerceAtLeast(0))
        if (nx != bx || ny != by) {
            AB.store.putInt("ball_x", nx)
            AB.store.putInt("ball_y", ny)
            if (isBallShown()) { hideBall(); showBall(ctx.applicationContext) }
        }
    }

    fun hideAll() { hideBall(); hidePanel() }

    /**
     * 取色/取图结束后恢复悬浮球与悬浮窗（与 [hideAll] 配对）。
     *
     * 只恢复**之前确实显示过**的组件——否则取色完会凭空冒出一个
     * 用户本来没开的悬浮球。
     */
    fun restore() {
        val ctx = lastCtx ?: return
        handler.post {
            if (ballWasShown && !isBallShown()) showBall(ctx)
            if (panelWasShown && panel == null) showPanel(ctx)
        }
    }

    @Volatile private var lastCtx: android.content.Context? = null
    @Volatile private var ballWasShown = false
    @Volatile private var panelWasShown = false

    /** 记录当前显示状态，供 [restore] 还原 */
    fun markShown() {
        ballWasShown = isBallShown()
        panelWasShown = panel != null
    }

    /** 录制开始时隐藏悬浮窗，结束时恢复 */
    fun setRecording(recording: Boolean) {
        this.recording = recording
        handler.post {
            if (recording) hidePanel()
        }
    }

    // ---------- 手势回调 ----------

    private val ballListener = object : FloatBallView.Listener {
        override fun onSingleTap() = runSlot(BallSlot.SINGLE)
        override fun onDoubleTap() = runSlot(BallSlot.DOUBLE)
        override fun onTripleTap() = runSlot(BallSlot.TRIPLE)
        override fun onLongPress() { openSlotList() }
        override fun onDragStart() { }
        override fun onMoveBy(dx: Int, dy: Int) {
            handler.post {
                val p = ballParams ?: return@post
                p.x += dx; p.y += dy
                runCatching { wm?.updateViewLayout(ball, p) }
            }
        }
        override fun onDragEnd() {
            handler.post {
                val p = ballParams ?: return@post
                val ctx = ball?.context ?: return@post
                if (AB.store.getBool("ball_snap_edge", true)) {
                    val sw = Display.screenSize(ctx).x
                    p.x = if (p.x + p.width / 2 < sw / 2) 0 else sw - p.width
                }
                runCatching { wm?.updateViewLayout(ball, p) }
            }
        }
        override fun onDragOverCloseZone(inZone: Boolean) { }
        override fun onClose() {
            AB.log.info("float", "悬浮球已拖到底部关闭")
            hideBall()
            AB.store.putBool("float_persistent", false)
        }
    }

    private val panelListener = object : FloatPanelView.Listener {
        override fun onRunSlot(a: FloatPanelView.SlotAction) {
            val slot = when (a) {
                FloatPanelView.SlotAction.SLOT_A -> BallSlot.SINGLE
                FloatPanelView.SlotAction.SLOT_B -> BallSlot.DOUBLE
                FloatPanelView.SlotAction.SLOT_C -> BallSlot.TRIPLE
                FloatPanelView.SlotAction.BACK -> return globalKey(android.view.KeyEvent.KEYCODE_BACK)
                FloatPanelView.SlotAction.HOME -> return globalKey(android.view.KeyEvent.KEYCODE_HOME)
                FloatPanelView.SlotAction.RECENTS -> return globalKey(android.view.KeyEvent.KEYCODE_APP_SWITCH)
                FloatPanelView.SlotAction.SHOT -> { AB.log.info("float", "截图"); return }
            }
            runSlot(slot)
        }
        override fun onStop() = ScriptLauncher.stop()
        override fun onCollapse() = hidePanel()
        override fun onRecord() { AB.log.info("float", "请在主界面「制作」中开始录制") }

        /** 自定义按键里「绑定脚本」：直接跑目标脚本（R-118） */
        override fun onRunScript(scriptId: String) {
            val sc = runCatching { AB.store.get(scriptId) }.getOrNull()
            if (sc == null) {
                AB.log.warn("float", "按键绑定的脚本不存在，可能已被删除")
                Ui_toast("该按键绑定的脚本已删除")
                return
            }
            ScriptLauncher.launch(com.autoball.App.get(), sc)
        }
    }

    private fun globalKey(code: Int) {
        val a = com.autoball.core.model.Action().apply {
            type = com.autoball.core.model.ActionType.KEY
            keyCode = code
        }
        val ctx = com.autoball.core.backend.ExecContext("float") { false }
        AB.router.execute(a, ctx)
    }

    /**
     * 触发手势：支持一个手势按顺序绑多个脚本（R-106），串行依次执行。
     *
     * 串行而非并行是刻意的：两个脚本同时注入点击会互相干扰坐标，
     * 结果不可预测。顺序执行也符合"手势触发一串动作"的直觉。
     */
    private fun runSlot(slot: BallSlot) {
        val ctx = com.autoball.App.get()
        val list = com.autoball.core.store.GestureBinding.scripts(slot)
        if (list.isEmpty()) {
            AB.log.warn("float", "手势「${slot.label}」未绑定脚本")
            Ui_toast("手势「${slot.label}」还没绑定脚本")
            return
        }
        if (list.size == 1) {
            ScriptLauncher.launch(ctx, list[0])
            return
        }
        // 多个脚本：后台串行执行，避免阻塞悬浮球的手势响应
        AB.log.info("float", "手势「${slot.label}」依次运行 ${list.size} 个脚本")
        Thread {
            list.forEachIndexed { i, s ->
                if (ScriptLauncher.isRunning() && i > 0) {
                    // 上一个还没跑完——不叠加，记录后停止后续
                    AB.log.warn("float", "「${s.name}」：上一个脚本仍在运行，已跳过")
                    return@forEachIndexed
                }
                ScriptLauncher.launch(ctx, s)
                waitUntilIdle()
            }
        }.apply { isDaemon = true }.start()
    }

    /** 等待当前脚本跑完（轮询，最多 30 分钟） */
    private fun waitUntilIdle() {
        val deadline = System.currentTimeMillis() + 30 * 60 * 1000L
        while (ScriptLauncher.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(300)
        }
    }

    private fun Ui_toast(msg: String) {
        handler.post { android.widget.Toast.makeText(
            com.autoball.App.get(), msg, android.widget.Toast.LENGTH_SHORT).show() }
    }

    private fun openSlotList() {
        // 长按弹出脚本列表：直接进入主界面脚本页，由 UI 展示槽位绑定
        val ctx = com.autoball.App.get()
        com.autoball.service.AutoBallAccessibilityService.openMainActivity(ctx)
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
}
