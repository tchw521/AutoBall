package com.autoball.core.recorder

import android.content.Context
import com.autoball.AB
import com.autoball.core.backend.ExecContext
import com.autoball.core.model.*
import com.autoball.core.util.Display
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    data class Stats(
        val state: State,
        val actionCount: Int,
        val estimatedMs: Long,
        val elapsedMs: Long,
        val lastActionAt: Long
    )

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
    private var startedAt = 0L
    private var lastActionAt = 0L

    fun currentFlow(): Flow = flow

    fun stats(): Stats {
        val elapsed = if (state == State.RECORDING || state == State.PAUSED) {
            System.currentTimeMillis() - startedAt
        } else 0L
        return Stats(state, flow.actions.size, estimatedMs(), elapsed, lastActionAt)
    }

    private fun defaultScriptName(): String =
        "脚本_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}"

    private fun makeFlow(name: String): Flow = Flow().apply {
        id = Flow.newId()
        this.name = name.ifBlank { defaultScriptName() }
        val p = Display.screenSize(context)
        display = DisplaySignature(p.x, p.y, context.resources.displayMetrics.density, 0)
    }

    fun start(name: String) {
        val scriptName = name.trim().ifBlank { defaultScriptName() }
        flow = makeFlow(scriptName)
        suppressor.reset()
        pausedAt = 0L
        startedAt = System.currentTimeMillis()
        lastActionAt = startedAt
        state = State.RECORDING
        AB.log.info("record", "开始录制「${flow.name}」")
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
        val last = flow.actions.last()
        flow.actions.removeAt(flow.actions.size - 1)
        callback?.onActionAdded(last, flow.actions.size)
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

        val last = stroke.samples.last()
        replay(action, last.x, last.y, now, action.durationMs)

        RecordOverlay.hintAt(last.x, last.y, pctText(action))
        append(toPercent(action))
        return true
    }

    /**
     * **补发**：把刚录到的这一步真正作用到目标应用。
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
                AB.log.warn(
                    "record",
                    "补发失败（${r.message ?: r.cause ?: "未知"}），该动作仅记录未生效"
                )
            }
        }
    }

    /**
     * 采集层回调：一次**多指**手势（所有手指抬起）到达。
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
        action.id = Action.newId()

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
            a.type == ActionType.GESTURE_MULTI
        ) {
            a.x2 = a.x2 / w * 100f
            a.y2 = a.y2 / h * 100f
        }
        return a
    }

    private fun append(a: Action) {
        flow.actions.add(a)
        lastActionAt = System.currentTimeMillis()
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
     */
    fun finish(name: String? = null): Flow {
        val f = flow.apply {
            if (!name.isNullOrBlank()) {
                this.name = name.trim()
            } else if (this.name.isBlank()) {
                this.name = defaultScriptName()
            }
        }
        state = State.IDLE
        AB.log.info("record", "录制结束，共 ${f.actions.size} 个动作")
        callback?.onStateChanged(state)
        return f
    }

    /** 保存：返回动作流并复位 */
    fun save(name: String? = null): Flow {
        val f = flow.apply {
            if (!name.isNullOrBlank()) {
                this.name = name.trim()
            } else if (this.name.isBlank()) {
                this.name = defaultScriptName()
            }
        }
        state = State.IDLE
        callback?.onStateChanged(state)
        AB.log.info("record", "录制保存，共 ${f.actions.size} 个动作")
        return f
    }

    /** 放弃 */
    fun discard(name: String? = null) {
        flow = makeFlow((name ?: flow.name).takeIf { !it.isNullOrBlank() } ?: defaultScriptName())
        startedAt = 0L
        lastActionAt = 0L
        state = State.IDLE
        callback?.onStateChanged(state)
    }
}
