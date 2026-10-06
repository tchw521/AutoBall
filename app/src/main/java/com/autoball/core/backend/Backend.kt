package com.autoball.core.backend

import com.autoball.core.model.Action
import com.autoball.core.model.Cap
import com.autoball.core.model.CapabilitySet

enum class BackendId(val label: String) {
    ACCESSIBILITY("无障碍"),
    SHIZUKU("Shizuku"),
    NONE("无可用后端")
}

enum class BackendHealth { READY, DEGRADED, UNAVAILABLE, DEAD }

/** 截图结果：不可用时必须显式返回，不能静默给旧图 */
sealed class ScreenResult {
    class Ok(val width: Int, val height: Int, val pixels: IntArray, val backend: BackendId) : ScreenResult()
    class Unavailable(val reason: String) : ScreenResult()
}

/** 单个动作的执行结果 */
class ActionResult(
    val ok: Boolean,
    val backend: BackendId,
    val latencyMs: Long = 0,
    val message: String? = null,
    val degraded: Boolean = false,
    val cause: String? = null
) {
    companion object {
        fun ok(b: BackendId, ms: Long, msg: String? = null) = ActionResult(true, b, ms, msg)
        fun fail(b: BackendId, ms: Long, msg: String?) = ActionResult(false, b, ms, null, false, msg)
        fun degraded(b: BackendId, ms: Long, msg: String) = ActionResult(true, b, ms, msg, true)
        fun unsupported(b: BackendId, missing: Set<Cap>) =
            ActionResult(false, b, 0, null, false, "后端缺少能力: " + missing.joinToString(","))
    }
}

/** 执行上下文：变量表 + 取消标志 + 日志，随一次运行传递 */
class ExecContext(
    val runId: String,
    val vars: MutableMap<String, String> = HashMap(),
    var cancelFlag: () -> Boolean = { false }
) {
    var logger: ((String) -> Unit)? = null

    fun isCanceled(): Boolean = cancelFlag()

    fun log(msg: String) { logger?.invoke(msg) }

    fun getVar(name: String): String? = vars[name]
    fun setVar(name: String, v: String) { vars[name] = v }
}

/** 多指降级策略：后端不具备原生多指时如何处置 */
enum class MultiPointerStrategy { PREFER_NATIVE, FALLBACK_SEQUENTIAL, FAIL_FAST }

/** 截图不可用时的策略 */
enum class ScreenStrategy { WAIT_RETRY, SKIP_AND_LOG, ABORT }

/**
 * 执行后端统一抽象。
 *
 * 无障碍与 Shizuku 是**并行对等**的两种实现：任意一种授权可用即可运行脚本，
 * 由 BackendRouter 按能力位路由，而不是让用户去猜该用哪个接口。
 */
interface InputBackend {
    val id: BackendId
    fun capabilities(): CapabilitySet
    fun health(): BackendHealth
    fun isAvailable(): Boolean

    /** 执行单个动作。必须快速返回，长等待由内部超时控制 */
    fun execute(action: Action, ctx: ExecContext): ActionResult

    /** 请求截图；不支持时返回 ScreenResult.Unavailable */
    fun screenshot(ctx: ExecContext): ScreenResult

    /** 尽力取消进行中的注入 */
    fun cancel()
}
