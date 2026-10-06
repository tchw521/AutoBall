package com.autoball.core

import com.autoball.AB
import com.autoball.core.backend.ExecContext
import com.autoball.core.model.Script

/** 运行控制：取消 / 暂停 / 单步，协作式中断 */
class RunControl {
    @Volatile var canceled: Boolean = false
        private set
    @Volatile var paused: Boolean = false
    @Volatile var stepMode: Boolean = false

    private val gate = Object()
    @Volatile private var stepGate = Object()

    fun cancel() {
        canceled = true
        paused = false
        synchronized(gate) { gate.notifyAll() }
        synchronized(stepGate) { stepGate.notifyAll() }
    }

    fun pause() { paused = true }
    fun resume() { paused = false; synchronized(gate) { gate.notifyAll() } }

    /** 暂停点：不强制中断正在进行的注入，只阻止下一个动作 */
    fun checkPause() {
        while (paused && !canceled) {
            synchronized(gate) {
                try { gate.wait(200) } catch (e: InterruptedException) { return }
            }
        }
    }

    /** 单步模式下等待外部放行 */
    fun checkStep() {
        if (!stepMode) return
        synchronized(stepGate) {
            try { stepGate.wait() } catch (e: InterruptedException) { return }
        }
    }

    fun nextStep() { synchronized(stepGate) { stepGate.notifyAll() } }

    /** 可中断睡眠，分段响应取消与暂停 */
    fun sleep(ms: Long): Boolean {
        if (ms <= 0) return !canceled
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (canceled) return false
            checkPause()
            val left = end - System.currentTimeMillis()
            val slice = if (left > 20) 20 else left
            try { Thread.sleep(slice) } catch (e: InterruptedException) { return false }
        }
        return !canceled
    }
}

/**
 * 全局运行协调器：**同一时间只允许一个脚本运行**。
 *
 * 悬浮球点击先检查协调器，避免脚本触发悬浮球、悬浮球又触发脚本的自递归。
 */
class RunnerCoordinator {

    enum class State { IDLE, PREPARING, RUNNING, PAUSED, STOPPING, STOPPED, ERROR }

    @Volatile
    var state: State = State.IDLE
        private set

    @Volatile
    var currentScriptId: String? = null
        private set

    @Volatile
    var currentRunId: String? = null
        private set

    @Volatile
    private var control: RunControl? = null

    @Volatile
    var lastMessage: String? = null

    var onStateChanged: ((State, String?) -> Unit)? = null

    /** 有外部动作（如悬浮球手势）想启动运行时调用；已被占用返回 null */
    fun tryStart(script: Script): Pair<String, RunControl>? {
        if (state == State.RUNNING || state == State.PREPARING) {
            // 已在运行：转为停止请求，避免自递归
            stop()
            return null
        }
        val runId = "r" + System.nanoTime().toString(36)
        val c = RunControl()
        control = c
        currentRunId = runId
        currentScriptId = script.id
        state = State.PREPARING
        emit(null)
        return runId to c
    }

    fun markRunning() { state = State.RUNNING; emit(null) }
    fun markPaused() { state = State.PAUSED; emit(null) }
    fun markResumed() { state = State.RUNNING; emit(null) }
    fun markStopped(msg: String? = null) {
        state = State.STOPPED
        lastMessage = msg
        emit(msg)
        reset()
    }
    fun markError(msg: String) {
        state = State.ERROR
        lastMessage = msg
        emit(msg)
        reset()
    }

    fun stop() {
        val c = control
        state = State.STOPPING
        emit(null)
        c?.cancel()
    }

    fun activeControl(): RunControl? = control

    private fun reset() {
        control = null
        currentRunId = null
        currentScriptId = null
        state = State.IDLE
    }

    private fun emit(msg: String?) { onStateChanged?.invoke(state, msg) }

    /** 构造执行上下文，绑定取消标志与日志 */
    fun context(runId: String, c: RunControl, vars: MutableMap<String, String>): ExecContext {
        val ctx = ExecContext(runId, vars) { c.canceled }
        ctx.logger = { msg -> AB.log.info(runId, msg) }
        return ctx
    }
}
