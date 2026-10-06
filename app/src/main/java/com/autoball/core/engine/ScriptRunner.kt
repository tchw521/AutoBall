package com.autoball.core.engine

import com.autoball.AB
import com.autoball.core.RunControl
import com.autoball.core.backend.BackendRouter
import com.autoball.core.backend.ExecContext
import com.autoball.core.model.Script
import com.autoball.core.model.ScriptKind
import com.autoball.core.util.CoordMapper
import com.autoball.core.util.Display

/**
 * 脚本运行入口：把「JS 脚本」与「动作流」两种脚本统一调度起来。
 *
 * 两条链路共用同一个后端路由，所以任意一个授权（无障碍 或 Shizuku）可用都能跑完整脚本。
 */
object ScriptRunner {

    /** 默认单次运行上限，用户可在设置里调低 */
    const val DEFAULT_TIMEOUT_MS = 15 * 60 * 1000L

    @Volatile
    var lastResult: String? = null

    /**
     * 同步执行（调用方应在后台线程）。
     * @return 是否成功完成
     */
    fun run(
        script: Script,
        router: BackendRouter,
        control: RunControl,
        ctx: ExecContext,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Boolean {
        AB.log.info(ctx.runId, "开始运行「${script.name}」 · 引擎 ${JsEngines.engineName()}")
        lastResult = null

        // 运行前体检：只提示，不阻塞
        val report = router.analyze(script.flow?.actions ?: emptyList())
        AB.log.info(ctx.runId, "能力体检：${report.summary()}")
        if (report.blockedActions.isNotEmpty()) {
            report.blockedActions.take(5).forEach { (_, reason) -> AB.log.warn(ctx.runId, reason) }
        }
        if (!report.anyBackend) {
            AB.log.error(ctx.runId, report.summary())
            lastResult = report.summary()
            return false
        }

        val host = JsHost(router, ctx, control, AB.log)

        val jsEval: (String, ExecContext) -> Boolean = { code, c ->
            val engine = JsEngines.create()
            try {
                val out = engine.run(code, host, timeoutMs)
                if (out.ok) true else {
                    AB.log.error(c.runId, "JS: ${out.error}")
                    false
                }
            } finally { engine.close() }
        }

        // 动作流（含 RUN_JS / RUN_SCRIPT 的嵌套调度）
        var flowOk = true
        val flow = script.flow
        if (flow != null) {
            // 多分辨率适配：按录制时的屏幕签名缩放坐标
            val scale = runCatching {
                val p = Display.screenSize(com.autoball.App.get())
                val rot = com.autoball.App.get().resources.configuration.orientation
                CoordMapper.compute(flow.display, p.x, p.y, rot)
            }.getOrElse { CoordMapper.Scale.NONE }
            if (scale.reason != null) AB.log.warn(ctx.runId, scale.reason!!)
            else if (scale.active) AB.log.info(ctx.runId, "已按屏幕尺寸缩放坐标 %.2f×%.2f".format(scale.sx, scale.sy))

            val runner = FlowRunner(router, control, ctx, AB.log, jsEval, null, scale) { sid ->
                val sub = AB.store.get(sid)
                if (sub == null) { AB.log.warn(ctx.runId, "子脚本不存在: $sid"); false }
                else runNested(sub, router, control, ctx, timeoutMs)
            }
            val outcome = runner.run(flow)
            flowOk = outcome.ok
            outcome.message?.let { AB.log.info(ctx.runId, it) }
        }

        // JS 主体
        var jsOk = true
        if (script.kind == ScriptKind.JS && script.jsCode.isNotBlank()) {
            val engine = JsEngines.create()
            val out = engine.run(script.jsCode, host, timeoutMs)
            jsOk = out.ok
            if (out.ok) {
                AB.log.ok(ctx.runId, "JS 执行完成", JsEngines.engineName())
                out.value?.let { if (it.isNotBlank()) AB.log.info(ctx.runId, "返回值 $it") }
            } else {
                AB.log.error(ctx.runId, "JS 错误: ${out.error}")
                out.stack?.let { if (it.isNotBlank()) AB.log.error(ctx.runId, it) }
                lastResult = out.error
            }
            engine.close()
        }

        val ok = flowOk && jsOk && !control.canceled
        if (control.canceled) AB.log.warn(ctx.runId, "已被用户停止")
        lastResult = lastResult ?: if (ok) "运行完成" else "运行未完成"
        return ok
    }

    private fun runNested(
        script: Script,
        router: BackendRouter,
        control: RunControl,
        ctx: ExecContext,
        timeoutMs: Long
    ): Boolean {
        val host = JsHost(router, ctx, control, AB.log)
        return when {
            script.jsCode.isNotBlank() -> {
                val engine = JsEngines.create()
                try { engine.run(script.jsCode, host, timeoutMs).ok } finally { engine.close() }
            }
            script.flow != null -> {
                FlowRunner(router, control, ctx, AB.log, { code, c ->
                    val engine = JsEngines.create()
                    try { engine.run(code, host, timeoutMs).ok } finally { engine.close() }
                }, null).run(script.flow!!).ok
            }
            else -> false
        }
    }
}
