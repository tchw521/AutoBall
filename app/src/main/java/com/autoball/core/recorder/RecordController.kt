package com.autoball.core.recorder

import android.content.Context
import com.autoball.AB
import com.autoball.core.backend.ExecContext
import com.autoball.core.model.*
import com.autoball.core.util.Display
import kotlin.concurrent.thread

/**
 * 录制控制器。
 *
 * 链路：透明采集层接管内触摸 → 编译为 Action → 立即 dispatchGesture 补发（保证目标应用真实响应）
 * → 回声抑制屏蔽补发回声 → 追加到动作流。
 *
 * 录制结果存**动作流**（可逐条删改），不直接存 JS（需求 2.2）。
 */
class RecordController(private val context: Context) {

    enum class State { IDLE, RECORDING, PAUSED, INTERRUPTED }

    interface Callback {
        fun onStateChanged(state: State)
        fun onActionAdded(action: Action, count: Int)
        fun onInterrupted(reason: String, count: Int, estimatedMs: Long)
    }

    @Volatile
    var state: State = State.IDLE
        private set

    var callback: Callback? = null

    private var flow: Flow = Flow().apply { id = Flow.newId() }
    private val suppressor = EchoSuppressor()
    private val ctx = ExecContext("record") { false }
    private var pausedAt = 0L

    fun currentFlow(): Flow = flow

    fun start(name: String) {
        flow = Flow().apply {
            id = Flow.newId()
            this.name = name
            val p = Display.screenSize(context)
            display = DisplaySignature(p.x, p.y, context.resources.displayMetrics.density, 0)
        }
        suppressor.reset()
        state = State.RECORDING
        AB.log.info("record", "开始录制「$name」")
        callback?.onStateChanged(state)
    }

    fun pause() {
        if (state != State.RECORDING) return
        state = State.PAUSED
        pausedAt = System.currentTimeMillis()
        callback?.onStateChanged(state)
    }

    fun resume() {
        if (state != State.PAUSED) return
        state = State.RECORDING
        callback?.onStateChanged(state)
    }

    /** 撤销上一步 */
    fun undo(): Boolean {
        if (flow.actions.isEmpty()) return false
        flow.actions.removeAt(flow.actions.size - 1)
        callback?.onActionAdded(flow.actions.lastOrNull() ?: Action(), flow.actions.size)
        return true
    }

    /** 插入等待 */
    fun insertWait(ms: Long) {
        val a = Action().apply {
            id = Action.newId()
            type = ActionType.CONTROL_FLOW
            controlOp = ControlOp.WAIT
            durationMs = ms
            waitMs = 0
        }
        append(a)
    }

    fun estimatedMs(): Long {
        var sum = 0L
        for (a in flow.actions) sum += a.durationMs + a.waitMs
        return sum
    }

    /**
     * 采集层回调：一次完整手势（抬手）到达。
     * @return 是否接受为新的用户手势（false 表示被判定为回声）
     */
    fun onStroke(stroke: GestureCompiler.Stroke): Boolean {
        if (state != State.RECORDING) return false
        val now = System.currentTimeMillis()

        if (suppressor.isEcho(stroke.samples.last().x, stroke.samples.last().y, now)) {
            if (suppressor.shouldFuse()) {
                interrupt("疑似自触发：连续收到补发回声")
            }
            return false
        }
        suppressor.accept(now)

        val density = context.resources.displayMetrics.density
        val action = GestureCompiler.compile(stroke, density)
        action.id = Action.newId()

        // 立即补发，保证录制过程中目标应用真实响应
        val last = stroke.samples.last()
        replay(action, last.x, last.y, now, action.durationMs)

        // 坐标提示：告知用户这一点被记下来了、记在哪个百分比位置。
        // 用**像素**点定位（提示要贴在手指位置），文案用百分比（与脚本存储一致）。
        RecordOverlay.hintAt(last.x, last.y, pctText(action))

        // 采集层采到的是**像素**坐标，而 Action.x/y 的约定是**百分比**。
        // 不转换的话，列表里会显示成 "点击(612.0%, 1344.0%)" 这种荒谬的值。
        // 必须在补发之后转——补发要用真实像素。
        append(toPercent(action))
        return true
    }

    /**
     * **补发**：把刚录到的这一步真正作用到目标应用。
     *
     * 必须在**后台线程**执行，两个原因缺一不可：
     *
     * 1. 整条链路（dispatchTouchEvent → flush → 本函数）都在主线程。
     *    若在主线程等 dispatchGesture 的结果回调，post 出去的 Runnable 永远
     *    执行不到——主线程正被 latch 阻塞——必然超时返回 false。这正是早前
     *    "录制期间点其他软件毫无反应"的成因。
     * 2. 补发期间要把采集层的遮挡临时撤掉（见 [RecordOverlay.setPassthroughTemp]），
     *    注入结束后再恢复。恢复必须等注入真正完成，也就必须能阻塞等待，
     *    只有后台线程才不会把 UI 卡住。
     */
    private fun replay(action: Action, x: Float, y: Float, now: Long, durationMs: Long) {
        suppressor.markDispatch(x, y, now, durationMs)
        thread(name = "ab-record-replay") {
            RecordOverlay.setPassthroughTemp(true)
            val r = try {
                AB.router.execute(action, ctx)
            } finally {
                RecordOverlay.setPassthroughTemp(false)
            }
            if (!r.ok) {
                suppressor.clearDispatch()
                // 必须记录：补发失败意味着"这一步只被记下来、没真正作用到目标应用"，
                // 用户看到的现象是"点了没反应"，不写日志根本无从排查是哪个后端拒了
                AB.log.warn("record",
                    "补发失败（${r.message ?: r.cause ?: "未知"}），该动作仅记录未生效")
            }
        }
    }

    /**
     * 采集层回调：一次**多指**手势（所有手指抬起）到达。
     *
     * 与 [onStroke] 的区别只在于编译方式（[GestureCompiler.compileMulti]），
     * 回声抑制与补发逻辑一致。此前采集层没有多指分支，多指手势会被
     * 当成单指处理、只记录第一根手指，录出来的动作与用户实际操作不符。
     */
    fun onStrokeMulti(strokes: List<GestureCompiler.Stroke>): Boolean {
        if (state != State.RECORDING) return false
        val now = System.currentTimeMillis()
        val ref = strokes.first().samples.last()
        if (suppressor.isEcho(ref.x, ref.y, now)) {
            if (suppressor.shouldFuse()) interrupt("疑似自触发：连续收到补发回声")
            return false
        }
        suppressor.accept(now)

        val density = context.resources.displayMetrics.density
        val action = GestureCompiler.compileMulti(strokes, density)
        action.id = com.autoball.core.model.Action.newId()

        replay(action, ref.x, ref.y, now, strokes.maxOf { it.durationMs })

        RecordOverlay.hintAt(ref.x, ref.y, pctText(action))

        append(toPercent(action))
        return true
    }

    /** 提示文案：百分比坐标，保留一位小数 */
    private fun pctText(a: Action): String {
        if (!a.type.hasCoord) return "手势 ${a.type.label}"
        return "%.1f%%, %.1f%%".format(a.x, a.y)
    }

    /** 像素 → 百分比。带坐标的动作才转，其余字段原样保留 */
    private fun toPercent(a: Action): Action {
        if (!a.type.hasCoord) return a
        val p = Display.screenSize(context)
        val w = p.x.coerceAtLeast(1)
        val h = p.y.coerceAtLeast(1)
        a.x = a.x / w * 100f
        a.y = a.y / h * 100f
        if (a.type == ActionType.SWIPE || a.type == ActionType.GESTURE_SINGLE ||
            a.type == ActionType.GESTURE_MULTI) {
            a.x2 = a.x2 / w * 100f
            a.y2 = a.y2 / h * 100f
        }
        return a
    }

    private fun append(a: Action) {
        flow.actions.add(a)
        callback?.onActionAdded(a, flow.actions.size)
    }

    /** 中断：无障碍断开 / Shizuku 掉线 / 疑似自触发 / 采集层被移除 */
    fun interrupt(reason: String) {
        if (state == State.IDLE) return
        state = State.INTERRUPTED
        AB.log.warn("record", "录制中断：$reason")
        callback?.onInterrupted(reason, flow.actions.size, estimatedMs())
        callback?.onStateChanged(state)
    }

    /**
     * 用户主动停止：结束录制并返回动作流，**不触发 onInterrupted**。
     *
     * 与 [interrupt] 的区别：那是异常中断，需要弹窗告知原因；
     * 而用户点「停止」的预期是**回到工作台继续编辑**，弹个对话框反而打断。
     */
    fun finish(): Flow {
        val f = flow
        state = State.IDLE
        AB.log.info("record", "录制结束，共 ${f.actions.size} 个动作")
        callback?.onStateChanged(state)
        return f
    }

    /** 保存：返回动作流并复位 */
    fun save(): Flow {
        val f = flow
        state = State.IDLE
        callback?.onStateChanged(state)
        AB.log.info("record", "录制保存，共 ${f.actions.size} 个动作")
        return f
    }

    /** 放弃 */
    fun discard() {
        flow.actions.clear()
        state = State.IDLE
        callback?.onStateChanged(state)
    }
}
