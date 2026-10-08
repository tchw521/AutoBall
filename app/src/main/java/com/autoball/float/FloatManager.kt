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
            view.apply(skin, AB.store.getFloat("panel_button_dp", 40f))

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
    }

    private fun globalKey(code: Int) {
        val a = com.autoball.core.model.Action().apply {
            type = com.autoball.core.model.ActionType.KEY
            keyCode = code
        }
        val ctx = com.autoball.core.backend.ExecContext("float") { false }
        AB.router.execute(a, ctx)
    }

    private fun runSlot(slot: BallSlot) {
        val ctx = com.autoball.App.get()
        val scripts = AB.store.all()
        val s = scripts.firstOrNull { it.slot == slot && it.enabled }
            ?: scripts.firstOrNull { it.isDefault }
            ?: run {
                AB.log.warn("float", "手势「${slot.label}」未绑定脚本")
                return
            }
        ScriptLauncher.launch(ctx, s)
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
