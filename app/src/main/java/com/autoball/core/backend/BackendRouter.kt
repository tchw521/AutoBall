package com.autoball.core.backend

import com.autoball.core.model.*

/**
 * 后端路由：**无障碍与 Shizuku 并行对等**，按动作能力位路由。
 *
 * 用户偏好只限制"候选集"，不覆盖单个动作的能力匹配——
 * 例如用户选"仅 Shizuku"，但脚本里有点击节点动作，该动作仍会被判定为需无障碍，
 * 并在报告中明确告知，而不是静默失败。
 */
class BackendRouter {

    enum class Preference(val label: String) {
        AUTO("自动择优"),
        ONLY_ACCESSIBILITY("仅无障碍"),
        ONLY_SHIZUKU("仅 Shizuku")
    }

    /** 运行时统计：连续失败降级、连续成功恢复，带冷却避免抖动切换 */
    private class Stats {
        var consecFail = 0
        var consecOk = 0
        var penalty = 0
        var lastFailTs = 0L
        fun onFail() {
            consecFail++
            consecOk = 0
            lastFailTs = System.currentTimeMillis()
            // 连续失败达到阈值即扣分（降低被选中的概率），达到冷却时间后逐步恢复
            if (consecFail >= FAIL_THRESHOLD) {
                penalty++
                consecFail = 0
            }
        }
        fun onOk() {
            consecOk++
            consecFail = 0
            if (consecOk >= RECOVER_THRESHOLD && penalty > 0) {
                penalty--
                consecOk = 0
            }
        }
        companion object {
            const val FAIL_THRESHOLD = 3
            const val RECOVER_THRESHOLD = 10
            const val COOLDOWN_MS = 5_000L
        }
    }

    val accessibility = AccessibilityBackend()
    val shizuku = ShizukuBackend()
    val backends: List<InputBackend> = listOf(accessibility, shizuku)

    var preference: Preference = Preference.AUTO

    private val stats = HashMap<BackendId, Stats>()

    /** 允许上层注入降级策略（多指 / 截图不可用时怎么办） */
    var multiPointerStrategy: MultiPointerStrategy = MultiPointerStrategy.FALLBACK_SEQUENTIAL
    var screenStrategy: ScreenStrategy = ScreenStrategy.SKIP_AND_LOG

    fun refresh() {
        // 无障碍状态由服务连接回调驱动；此处仅触发 Shizuku 重新握手
        runCatching { shizuku.isAvailable() }
    }

    fun status(): List<Pair<BackendId, BackendHealth>> =
        backends.map { it.id to it.health() }

    fun anyAvailable(): Boolean = backends.any { it.isAvailable() }

    private fun statsOf(id: BackendId): Stats = stats.getOrPut(id) { Stats() }

    private fun allowed(b: InputBackend): Boolean = when (preference) {
        Preference.AUTO -> true
        Preference.ONLY_ACCESSIBILITY -> b.id == BackendId.ACCESSIBILITY
        Preference.ONLY_SHIZUKU -> b.id == BackendId.SHIZUKU
    }

    /** 为单个动作选择后端；返回 null 表示没有任何后端能满足 */
    fun select(action: Action): InputBackend? {
        val candidates = backends.filter { allowed(it) && it.isAvailable() }
        if (candidates.isEmpty()) return null

        // 显式指定后端且可用
        val hint = action.backendHint
        if (hint != null) {
            val forced = candidates.firstOrNull { it.id.name.equals(hint, true) }
            if (forced != null) return forced
        }

        var best: InputBackend? = null
        var bestScore = Int.MIN_VALUE
        for (b in candidates) {
            val caps = b.capabilities()
            val base = caps.score(action.requiredCaps(), action.optionalCaps())
            if (base <= 0) continue
            val s = base - statsOf(b.id).penalty * 50 + when (b.health()) {
                BackendHealth.READY -> 30
                BackendHealth.DEGRADED -> 0
                BackendHealth.UNAVAILABLE -> -1000
                BackendHealth.DEAD -> -1000
            }
            if (s > bestScore) { bestScore = s; best = b }
        }
        return best
    }

    /** 执行：首选后端失败后，自动回退到另一个能满足该动作的后端 */
    fun execute(action: Action, ctx: ExecContext): ActionResult {
        val primary = select(action)
        if (primary == null) {
            return ActionResult.fail(
                BackendId.NONE, 0,
                "没有可用后端可以执行「${action.type.label}」，请至少开启一种授权（无障碍 或 Shizuku）"
            )
        }
        var r = primary.execute(action, ctx)
        if (r.ok) { statsOf(primary.id).onOk(); return r }

        statsOf(primary.id).onFail()
        val fallback = backends.firstOrNull {
            it !== primary && allowed(it) && it.isAvailable() &&
                it.capabilities().supports(action.requiredCaps())
        }
        if (fallback != null) {
            ctx.log("${primary.id.label} 执行失败，回退 ${fallback.id.label}：${r.cause}")
            val r2 = fallback.execute(action, ctx)
            if (r2.ok) statsOf(fallback.id).onOk() else statsOf(fallback.id).onFail()
            return r2
        }
        return r
    }

    fun screenshot(ctx: ExecContext): ScreenResult {
        for (b in backends.filter { allowed(it) && it.isAvailable() }) {
            if (!b.capabilities().has(Cap.SCREENSHOT)) continue
            val sr = b.screenshot(ctx)
            if (sr is ScreenResult.Ok) return sr
        }
        return ScreenResult.Unavailable("当前没有可用的取屏通道")
    }

    fun cancelAll() { backends.forEach { runCatching { it.cancel() } } }

    // ---------- 运行前的能力体检 ----------

    class CapabilityReport(
        val anyBackend: Boolean,
        val availableBackends: List<BackendId>,
        val missingCaps: Set<Cap>,
        val degradedActions: List<Pair<String, String>>, // actionId -> 原因
        val blockedActions: List<Pair<String, String>>
    ) {
        fun summary(): String = when {
            !anyBackend -> "尚未开启任何授权，请先开启无障碍服务或 Shizuku"
            blockedActions.isNotEmpty() -> "有 ${blockedActions.size} 个动作在当前授权下无法执行"
            degradedActions.isNotEmpty() -> "可运行，其中 ${degradedActions.size} 个动作将降级执行"
            else -> "当前授权可完整执行"
        }
    }

    /** 对动作流做静态体检，用于运行前提示（不阻塞运行） */
    fun analyze(actions: List<Action>): CapabilityReport {
        val available = backends.filter { it.isAvailable() }.map { it.id }
        val union = LinkedHashSet<Cap>()
        for (b in backends.filter { it.isAvailable() }) {
            union.addAll(b.capabilities().granted)
            union.addAll(b.capabilities().experimental)
        }
        val missing = LinkedHashSet<Cap>()
        val degraded = ArrayList<Pair<String, String>>()
        val blocked = ArrayList<Pair<String, String>>()

        walk(actions) { a ->
            if (!a.enabled) return@walk
            val need = a.requiredCaps()
            val miss = need.filter { !union.contains(it) }.toSet()
            if (miss.isNotEmpty()) {
                missing.addAll(miss)
                blocked.add(a.id to "「${a.type.label}」需要 ${miss.joinToString(",")}，当前授权不具备")
                return@walk
            }
            // 检查是否有某个后端能"完整"（非实验性）支持
            val full = backends.filter { it.isAvailable() }
                .any { it.capabilities().granted.containsAll(need) }
            if (!full) {
                degraded.add(a.id to "「${a.type.label}」仅在实验性能力下可用，可能被降级执行")
            }
        }
        return CapabilityReport(available.isNotEmpty(), available, missing, degraded, blocked)
    }

    private fun walk(list: List<Action>, visitor: (Action) -> Unit) {
        for (a in list) {
            visitor(a)
            if (a.subActions.isNotEmpty()) walk(a.subActions, visitor)
        }
    }
}
