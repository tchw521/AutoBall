package com.autoball.core.recorder

import android.content.Context
import com.autoball.AB
import com.autoball.core.backend.ExecContext
import com.autoball.core.model.*
import com.autoball.core.util.Display

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
        suppressor.markDispatch(last.x, last.y, now, action.durationMs)
        val r = AB.router.execute(action, ctx)
        if (!r.ok) suppressor.clearDispatch()

        // 采集层采到的是**像素**坐标，而 Action.x/y 的约定是**百分比**。
        // 不转换的话，列表里会显示成 "点击(612.0%, 1344.0%)" 这种荒谬的值。
        // 必须在补发之后转——补发要用真实像素。
        append(toPercent(action))
        return true
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
