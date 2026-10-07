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
                // 目标应用：运行前先唤起，避免脚本在错误界面上白跑。
                // 唤起后等待界面稳定（1.2s + 500ms 轮询确认前台），失败只告警不中断。
                val pkg = script.targetPkg
                if (!pkg.isNullOrBlank()) {
                    AB.log.info(runId, "唤起目标应用 $pkg")
                    if (com.autoball.core.util.Display.launchApp(context, pkg)) {
                        waitForForeground(context, pkg)
                    } else {
                        AB.log.warn(runId, "无法唤起 $pkg，按当前界面继续")
                    }
                }
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

    /**
     * 等待目标应用进入前台。
     *
     * 前台包名取自无障碍服务（`lastForegroundPkg`，由窗口事件维护）——
     * 不额外申请 `REAL_GET_TASKS`（系统级权限，三方应用拿不到）或
     * UsageStats（国产 ROM 常返回空）。无障碍不可用时退化为固定等待。
     * 最多等 ~2.7 秒，超时只告警不中断，避免脚本卡死在启动阶段。
     */
    private fun waitForForeground(context: Context, pkg: String) {
        runCatching { Thread.sleep(1200) }
        val svc = com.autoball.service.AutoBallAccessibilityService.instance
        if (svc == null) return          // 无无障碍：只能靠固定等待
        for (i in 0 until 3) {
            if (com.autoball.service.AutoBallAccessibilityService.foregroundPkg() == pkg) return
            runCatching { Thread.sleep(500) }
        }
        AB.log.warn("launch", "$pkg 可能未进入前台，按当前界面继续")
    }

    fun stop() {
        coordinator.stop()
        AB.router.cancelAll()
    }

    fun isRunning(): Boolean = coordinator.state == RunnerCoordinator.State.RUNNING
}
