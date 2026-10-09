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

    fun launch(context: Context, script: Script) = launch(context, script, emptyMap(), false)

    /** @param initVars 初始变量，供消息触发注入 $notifyPkg / $notifyText */
    fun launch(context: Context, script: Script, initVars: Map<String, String>) =
        launch(context, script, initVars, false)

    /**
     * @param step 单步执行：每个动作前暂停，等悬浮条放行（R-131）
     *
     * 只对**动作流**有效——JS 脚本不经过 FlowRunner 的 checkStep，
     * 单步对它没意义。这里如实提示而不是静默忽略（R-003）。
     */
    fun launch(context: Context, script: Script, initVars: Map<String, String>, step: Boolean) {
        if (step && script.kind == com.autoball.core.model.ScriptKind.JS) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                com.autoball.ui.Ui.toast(context, "单步只支持动作流脚本，JS 脚本请用 console 调试")
            }
        }
        val pair = coordinator.tryStart(script)
        if (pair == null) {
            AB.log.warn("launch", "已有脚本在运行，本次触发转为停止")
            return
        }
        val (runId, control) = pair
        lastScriptId = script.id
        val seed = HashMap<String, String>(initVars)
        val ctx = coordinator.context(runId, control, seed)
        FloatManager.setRunning(true)

        // 防误触层（guardTouch）：默认关闭，用户主动开才生效。
        // 它会挡住用户对手机的一切操作，默认开启等于替用户做决定（见 RunGuard 注释）。
        val guard = com.autoball.AB.store.getBool("guardTouch", false) &&
            !needsScreenOrNodes(script)
        if (com.autoball.AB.store.getBool("guardTouch", false) && !guard) {
            // 不静默降级：用户开了开关却没生效，如果不说，他会以为功能坏了或没开对
            AB.log.info(runId, "防误触层已跳过：本脚本用到截图/节点，覆盖层会干扰")
        }
        if (guard) {
            com.autoball.float.RunGuard.show(context, script.name) {
                coordinator.stop()
            }
        }

        // 单步：必须在启动线程**之前**置位，否则前几个动作会直接跑过去
        val useStep = step && script.kind != com.autoball.core.model.ScriptKind.JS
        if (useStep) {
            control.stepMode = true
            com.autoball.float.FloatStepBar.show(context, control)
            AB.log.info(runId, "单步模式：每个动作前等待放行")
        }

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
                // 单步条必须收掉：它挂在屏幕上会一直挡着，且持有 control 引用
                if (useStep) com.autoball.float.FloatStepBar.hide()
                com.autoball.float.RunGuard.hide()
                FloatManager.setRunning(false)
            }
        }
        t.name = "autoball-run-$runId"
        t.isDaemon = true
        t.start()
    }

    /**
     * 脚本是否用到**截图或控件节点**——这两类能力会被防误触覆盖层干扰。
     *
     * - 截图：覆盖层是 window 层，可能被合进截图像素（找色 / 找图条件）
     * - 节点：`TYPE_APPLICATION_OVERLAY` 窗口会被无障碍服务遍历到，
     *   `rootInActiveWindow` 有可能返回**我们自己的覆盖层**而非目标应用——
     *   那样 findNode 全部失效，属于最难排查的一类失败。
     *
     * 所以宁可让防误触不生效，也不能让脚本的识别能力失效。
     */
    private fun needsScreenOrNodes(script: com.autoball.core.model.Script): Boolean {
        val acts = script.flow?.actions ?: return false
        val kinds = setOf(
            com.autoball.core.model.ActionCondition.Kind.IMAGE.name,
            com.autoball.core.model.ActionCondition.Kind.NODE.name,
            com.autoball.core.model.ActionCondition.Kind.TEXT.name,
            com.autoball.core.model.ActionCondition.Kind.COLOR.name)
        acts.forEach { a ->
            com.autoball.core.model.ConditionSet.parse(a.condition)
                .items.forEach { if (it.kind.name in kinds) return true }
            if (a.type in setOf(
                    com.autoball.core.model.ActionType.CLICK_IMAGE,
                    com.autoball.core.model.ActionType.CLICK_TEXT,
                    com.autoball.core.model.ActionType.CLICK_COLOR,
                    com.autoball.core.model.ActionType.CLICK_NODE,
                    com.autoball.core.model.ActionType.AI_CLICK,
                    com.autoball.core.model.ActionType.RECOGNIZE_SCREEN)) return true
        }
        // JS 脚本无法静态判断，保守当作"需要"
        return script.kind == com.autoball.core.model.ScriptKind.JS
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

    /** 最近一次运行的脚本 id：供运行日志「跳转到失败步骤」使用 */
    @Volatile
    var lastScriptId: String? = null
        private set

    fun stop() {
        coordinator.stop()
        AB.router.cancelAll()
    }

    fun isRunning(): Boolean = coordinator.state == RunnerCoordinator.State.RUNNING
}
