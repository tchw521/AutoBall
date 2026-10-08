package com.autoball.core.engine

import com.autoball.core.RunControl
import com.autoball.core.backend.BackendRouter
import com.autoball.core.backend.ExecContext
import com.autoball.core.log.RunLog
import com.autoball.core.model.*
import com.autoball.core.util.Condition
import com.autoball.core.util.CoordMapper

/**
 * 动作流执行器：**不经 JS 引擎**，直接把结构化动作交给后端执行。
 *
 * 与 JS 脚本共用同一套 Action 语义与同一个后端路由，
 * 因此"点击 / 录制 / JS"三种来源在运行时是同一条链路。
 */
class FlowRunner(
    private val router: BackendRouter,
    private val control: RunControl,
    private val ctx: ExecContext,
    private val log: RunLog,
    /** 运行 JS 片段（RUN_JS 动作）；无引擎时返回 false 并记录 */
    private val jsEval: ((String, ExecContext) -> Boolean)? = null,
    /** 运行子脚本（RUN_SCRIPT 动作）；由协调器注入以防重入 */
    private val runScript: ((String) -> Boolean)? = null,
    /** 坐标缩放（多分辨率适配）：由调用方按录制签名计算后传入 */
    private val scale: CoordMapper.Scale = CoordMapper.Scale.NONE,
    /** 进度回调（动作序号从 0 开始）：用于悬浮窗显示当前步骤名 */
    private val onProgress: ((index: Int, total: Int, action: com.autoball.core.model.Action) -> Unit)? = null,
    /** 手势矩阵变形参数（v3 morph）；null 表示不做变换 */
    private val morph: Morph.Params? = null,
    /** 屏幕中心（变换原点），由调用方按当前屏幕注入 */
    private val center: Pair<Float, Float> = 0f to 0f
) {

    class Outcome(
        val ok: Boolean,
        val executed: Int,
        val failed: Int,
        val message: String?
    )

    fun run(flow: Flow): Outcome {
        var executed = 0
        var failed = 0
        val speed = if (flow.speed > 0f) flow.speed else 1f
        val hooks = flow.hooks

        /** 执行某个时机的全部监听动作（v3 9 钩子） */
        fun fire(stage: String) {
            val list = hooks[stage] ?: return
            for (ha in list) {
                if (control.canceled) return
                if (!ha.enabled) continue
                runCatching { execOne(ha) }
                    .onFailure { log.warn(ctx.runId, "监听动作[$stage]异常：${it.message}") }
            }
        }

        val total = flow.actions.count { it.enabled }
        var round = 0
        var shown = 0
        while (true) {
            fire("lt")            // 列表开头
            if (control.canceled) return Outcome(false, executed, failed, "已停止")
            for (a in flow.actions) {
                if (control.canceled) {
                    return Outcome(false, executed, failed, "已停止")
                }
                if (a.enabled) {
                    onProgress?.invoke(shown, total, a)
                    shown++
                }
                control.checkStep()
                control.checkPause()
                if (!a.enabled) continue

                fire("br")        // 每个动作运行前
                if (control.canceled) return Outcome(false, executed, failed, "已停止")

                if (!Condition.eval(a.condition, ctx.vars)) {
                    ctx.log("跳过 ${a.type.label}（条件不满足）")
                    continue
                }

                if (!control.sleep((a.preDelayMs / speed).toLong())) {
                    return Outcome(false, executed, failed, "已停止")
                }

                val reps = a.repeat.coerceAtLeast(1)
                var okAll = true
                for (r in 0 until reps) {
                    var ok = execOne(a)
                    // 失败自动重试一次（v3 retry）
                    if (!ok && flow.retryOnce && r == reps - 1) {
                        log.warn(ctx.runId, "动作失败，自动重试一次：${a.type.label}")
                        if (control.sleep(200)) ok = execOne(a)
                    }
                    executed++
                    if (!ok) { failed++; okAll = false }
                    if (r < reps - 1) {
                        if (!control.sleep((a.repeatIntervalMs / speed).toLong())) {
                            return Outcome(false, executed, failed, "已停止")
                        }
                    }
                }
                onProgress?.invoke(-1, total, a)
                // 自动精灵：运行条件可引用前序动作的执行状态。
                // 写入三个变量供后续动作的「运行条件」表达式使用：
                //   $ok    全局：截至目前是否全部成功
                //   $last  上一个动作是否成功（1/0）
                //   $stepN 第 N 个动作是否成功（如 $step3）
                runCatching { ctx.vars["last"] = if (okAll) "1" else "0" }
                runCatching { ctx.vars["ok"] = if (failed == 0) "1" else "0" }
                runCatching { ctx.vars["step${executed - failed}"] = if (okAll) "1" else "0" }
                fire("ba")        // 每个动作运行后
                if (!okAll && a.type != ActionType.RUN_JS) {
                    // 「有动作失败立即暂停」：防止后续动作在错误界面上乱点
                    if (flow.failStop) {
                        log.error(ctx.runId, "动作失败且已开启失败暂停，中止脚本：${a.type.label}")
                        fire("er")
                        return Outcome(false, executed, failed, "动作失败，已按设置暂停")
                    }
                }
                if (!okAll && a.type != ActionType.RUN_JS) {
                    // 单个动作失败不中止整体，交由上层策略决定是否继续
                    log.warn(ctx.runId, "动作失败：${a.type.label}")
                }
                if (!control.sleep((a.waitMs / speed).toLong())) {
                    return Outcome(false, executed, failed, "已停止")
                }
                // 动作间默认等待（v3 全局设置）
                if (flow.defaultWaitMs > 0 &&
                    !control.sleep((flow.defaultWaitMs / speed).toLong())) {
                    return Outcome(false, executed, failed, "已停止")
                }
                fire("ae")        // 每个动作运行结束后
            }

            fire("le")            // 列表结尾
            if (!flow.loop) break
            round++
            if (flow.loopCount > 0 && round >= flow.loopCount) break
            if (control.canceled) break
        }

        val msg = if (failed == 0) null else "完成，其中 $failed 个动作失败"
        return Outcome(failed == 0, executed, failed, msg)
    }

    /** 单步执行：供调试使用 */
    fun runSingle(action: Action): Boolean = execOne(action)

    /**
     * 对坐标类动作施加矩阵变形。
     *
     * 只处理有坐标的类型（点击/长按/滑动…），其余原样返回——
     * 对「等待」「返回键」这类动作做抖动没有意义。
     */
    private fun morphAction(a: Action): Action {
        val p = morph ?: return a
        if (!a.type.hasCoord) return a
        val (cx, cy) = center
        val (nx, ny) = Morph.point(p, a.x, a.y, cx, cy)
        val out = a.copy()
        out.x = nx; out.y = ny
        if (a.x2 != 0f || a.y2 != 0f) {
            val (nx2, ny2) = Morph.point(p, a.x2, a.y2, cx, cy)
            out.x2 = nx2; out.y2 = ny2
        }
        if (a.durationMs > 0) out.durationMs = Morph.duration(p, a.durationMs)
        return out
    }

    private fun execOne(a: Action): Boolean {
        return when (a.type) {
            ActionType.SET_VAR -> {
                val n = a.varName
                if (n.isNullOrEmpty()) return true
                ctx.setVar(n, a.varValue ?: a.text ?: "")
                log.info(ctx.runId, "设置变量 $n")
                true
            }
            ActionType.CONTROL_FLOW -> execControl(a)
            ActionType.RUN_ACTIONS -> {
                if (a.subActions.isEmpty()) return true
                val sub = FlowRunner(router, control, ctx, log, jsEval, runScript, scale)
                val nested = Flow().apply {
                    actions = a.subActions
                    speed = 1f
                }
                val o = sub.run(nested)
                o.failed == 0
            }
            ActionType.RUN_JS -> {
                val code = a.code
                if (code.isNullOrEmpty()) return true
                val fn = jsEval
                if (fn == null) {
                    log.warn(ctx.runId, "JS 引擎不可用，跳过运行JS代码")
                    false
                } else fn(code, ctx)
            }
            ActionType.RUN_SCRIPT -> {
                val sid = a.scriptId
                if (sid.isNullOrEmpty()) return true
                val fn = runScript
                if (fn == null) { log.warn(ctx.runId, "无法运行子脚本"); false } else fn(sid)
            }
            else -> {
                val target = CoordMapper.applyTo(morphAction(a), scale)
                val r = router.execute(target, ctx)
                log.add(ctx.runId,
                    if (r.ok) RunLog.Level.OK else RunLog.Level.ERROR,
                    target.type.label, r.message, a.id, r.backend.label, r.latencyMs)
                if (r.degraded) log.warn(ctx.runId, "降级执行：${r.message}")
                r.ok
            }
        }
    }

    private fun execControl(a: Action): Boolean {
        return when (a.controlOp) {
            ControlOp.PAUSE -> { control.pause(); true }
            ControlOp.RESUME -> { control.resume(); true }
            ControlOp.STOP -> { control.cancel(); true }
            ControlOp.WAIT -> control.sleep(a.durationMs)
            ControlOp.GOTO -> {
                log.warn(ctx.runId, "跳转指令在动作流中按顺序执行，暂不支持回跳")
                true
            }
        }
    }
}
