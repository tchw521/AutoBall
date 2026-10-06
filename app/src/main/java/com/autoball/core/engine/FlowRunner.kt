package com.autoball.core.engine

import com.autoball.core.RunControl
import com.autoball.core.backend.BackendRouter
import com.autoball.core.backend.ExecContext
import com.autoball.core.log.RunLog
import com.autoball.core.model.*
import com.autoball.core.util.Condition

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
    private val runScript: ((String) -> Boolean)? = null
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

        var round = 0
        while (true) {
            for (a in flow.actions) {
                if (control.canceled) {
                    return Outcome(false, executed, failed, "已停止")
                }
                control.checkStep()
                control.checkPause()
                if (!a.enabled) continue

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
                    val ok = execOne(a)
                    executed++
                    if (!ok) { failed++; okAll = false }
                    if (r < reps - 1) {
                        if (!control.sleep((a.repeatIntervalMs / speed).toLong())) {
                            return Outcome(false, executed, failed, "已停止")
                        }
                    }
                }
                if (!okAll && a.type != ActionType.RUN_JS) {
                    // 单个动作失败不中止整体，交由上层策略决定是否继续
                    log.warn(ctx.runId, "动作失败：${a.type.label}")
                }
                if (!control.sleep((a.waitMs / speed).toLong())) {
                    return Outcome(false, executed, failed, "已停止")
                }
            }

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
                val sub = FlowRunner(router, control, ctx, log, jsEval, runScript)
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
                val r = router.execute(a, ctx)
                log.add(ctx.runId,
                    if (r.ok) RunLog.Level.OK else RunLog.Level.ERROR,
                    a.type.label, r.message, a.id, r.backend.label, r.latencyMs)
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
