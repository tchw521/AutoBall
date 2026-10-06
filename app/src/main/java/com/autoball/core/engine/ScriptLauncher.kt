package com.autoball.core.engine

import android.content.Context
import com.autoball.AB
import com.autoball.core.RunnerCoordinator
import com.autoball.float.FloatManager
import com.autoball.core.model.Script

/**
 * 统一的启动入口：悬浮球手势、通知、界面按钮都走这里。
 *
 * 全局只有一个 RunnerCoordinator，同一时间只允许一个脚本运行；
 * 运行期间再次触发会被解释为"停止"，避免脚本 → 悬浮球 → 脚本的自递归。
 */
object ScriptLauncher {

    val coordinator: RunnerCoordinator = RunnerCoordinator()

    fun launch(context: Context, script: Script) {
        val pair = coordinator.tryStart(script)
        if (pair == null) {
            AB.log.warn("launch", "已有脚本在运行，本次触发转为停止")
            return
        }
        val (runId, control) = pair
        val ctx = coordinator.context(runId, control, HashMap())
        FloatManager.setRunning(true)

        val t = Thread {
            try {
                coordinator.markRunning()
                val ok = ScriptRunner.run(script, AB.router, control, ctx)
                if (ok) coordinator.markStopped("运行完成")
                else coordinator.markStopped(ScriptRunner.lastResult ?: "运行结束")
            } catch (e: Throwable) {
                coordinator.markError(e.message ?: "运行异常")
            } finally {
                FloatManager.setRunning(false)
            }
        }
        t.name = "autoball-run-$runId"
        t.isDaemon = true
        t.start()
    }

    fun stop() {
        coordinator.stop()
        AB.router.cancelAll()
    }

    fun isRunning(): Boolean = coordinator.state == RunnerCoordinator.State.RUNNING
}
