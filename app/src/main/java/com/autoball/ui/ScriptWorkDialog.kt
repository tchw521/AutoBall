package com.autoball.ui

import android.app.Activity
import com.autoball.AB
import com.autoball.core.model.Script
import com.autoball.core.recorder.GlobalSettingsDialog
import com.autoball.core.recorder.RecordOverlay
import com.autoball.core.util.Display
import com.autoball.float.FloatWorkWindow

/**
 * 录制 / 添加动作的总入口（统一组件）——一比一对齐自动精灵。
 *
 * 形态是**悬浮窗**（[FloatWorkWindow]）：两者都要操作别的应用，
 * 应用内弹窗占住屏幕，用户切不过去。未授予悬浮窗权限时直接引导去开启——
 * 降级成应用内弹窗会让录制功能事实上不可用，不如明确告知。
 *
 * 悬浮窗上的每个动作都对应自动精灵的一项：
 * 运行 / 录制(停止) / 更多 → 添加动作 · 保存脚本 · 清空动作 · 开启日志 · 查看变量 · 全局设置
 */
object ScriptWorkDialog {

    fun show(activity: Activity, script: Script, host: PageHost) {
        if (!Display.canDrawOverlay(activity)) {
            Ui.toast(activity, "录制与添加动作需要悬浮窗权限，请先开启")
            Display.openOverlaySettings(activity)
            return
        }
        FloatWorkWindow.show(activity, script, object : FloatWorkWindow.Callback {

            override fun onRun(s: Script) = host.runScript(s)

            override fun onRecord(s: Script, willRecord: Boolean) {
                if (willRecord) {
                    FloatWorkWindow.setRecording(true)
                    // 让录制控制器能把新增动作同步回本窗口的列表
                    CreatePage.currentScript = s
                    FloatWorkWindow.bindSteps { s.flow?.actions?.size ?: 0 }

                    // **顺序关键：先让出屏幕，再挂采集层。**
                    //
                    // 早前是先 startRecording（内部立刻 addView 采集层）再 enterStealth，
                    // 两者都走主线程 Handler，实际顺序并不确定；
                    // 一旦采集层先挂上，它会接管整屏触摸，
                    // 而此时本应用的窗口还叠在上面——用户看到两层 UI，
                    // 采到的坐标也可能落在本应用自己的界面而非目标应用。
                    //
                    // 现在：先隐藏（主窗口 + 悬浮球 + 悬浮窗，只留胶囊），
                    // 稍后再挂采集层，保证"获取到的坐标来自真实的目标应用界面"。
                    FloatWorkWindow.enterStealth(activity)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        host.startRecording()
                    }, 300)
                } else {
                    stopAndBackToList(activity, s)
                }
            }

            override fun onEditAction(s: Script, index: Int) {
                val flow = s.flow ?: return
                val a = flow.actions.getOrNull(index) ?: return
                // 同样在悬浮窗层弹编辑框：用户此刻还在目标 App 上，
                // 跳回应用会把目标 App 切走，改完还得再切回来
                ActionEditor.showFloat(activity, a, flow) { edited ->
                    flow.actions[index] = edited
                    com.autoball.AB.store.save(s)
                    FloatWorkWindow.refresh(s)
                }
            }

            override fun onPause(s: Script, willPause: Boolean) {
                val c = CreatePage.controller ?: return
                if (willPause) c.pause() else c.resume()
                FloatWorkWindow.setPaused(willPause)
            }

            override fun onUndo(s: Script) {
                CreatePage.controller?.undo()
                FloatWorkWindow.refresh(s)
            }

            override fun onInsertWait(s: Script, ms: Long) {
                CreatePage.controller?.insertWait(ms)
                FloatWorkWindow.refresh(s)
            }

            override fun onStopRecord(s: Script) = stopAndBackToList(activity, s)

            override fun onAddAction(s: Script) {
                // 直接在悬浮窗层弹出动作编辑框（仿自动精灵）：
                // 用户此刻正在操作别的应用，跳回应用会把目标应用切走
                val flow = s.flow
                if (flow == null) {
                    Ui.toast(activity, "该脚本还没有动作流")
                    return
                }
                ActionEditor.showFloat(activity, null, flow) { act ->
                    flow.actions.add(act)
                    AB.store.save(s)
                    FloatWorkWindow.refresh(s)
                    Ui.toast(activity, "已添加：${ActionEditor.describe(act)}")
                }
            }

            override fun onSave(s: Script) {
                AB.store.save(s)
                Ui.toast(activity, "已保存「${s.name}」")
                FloatWorkWindow.setRecording(false)
            }

            override fun onClear(s: Script) {
                s.flow?.actions?.clear()
                AB.store.save(s)
                FloatWorkWindow.refresh(s)
                Ui.toast(activity, "已清空动作")
            }

            override fun onToggleLog(s: Script) {
                val on = !AB.store.getBool("log_enabled", true)
                AB.store.putBool("log_enabled", on)
                Ui.toast(activity, if (on) "运行日志已开启" else "运行日志已关闭")
            }

            override fun onTools(s: Script) {
                // 自动精灵同款「更多工具」：高频动作一点即插入
                ToolPanel.show(activity, s) {
                    com.autoball.AB.store.save(s)
                    FloatWorkWindow.refresh(s)
                }
            }

            override fun onVars(s: Script) {
                VarsDialog.show(activity, s)
            }

            override fun onSettings(s: Script) {
                val flow = s.flow
                if (flow == null) {
                    Ui.toast(activity, "该脚本还没有动作流")
                    return
                }
                // 全局设置同样在悬浮窗层弹出，不把用户拽回应用界面
                GlobalSettingsDialog.showFloat(activity, flow) { AB.store.save(s) }
            }
        })
    }

    /**
     * 结束录制 → 回到**添加动作的列表窗口**。
     *
     * 不弹「放弃 / 继续 / 保存」对话框：用户点停止的预期是接着编辑刚才录到的东西，
     * 中间插一个对话框只会打断。录到的动作已经在脚本里（onActionAdded 同步过），
     * 这里再落一次盘，避免用户直接关窗口导致录制结果丢失。
     */
    private fun stopAndBackToList(activity: Activity, s: Script) {
        val c = CreatePage.controller
        c?.finish()
        RecordOverlay.hide()
        com.autoball.float.FloatManager.setRecording(false)
        FloatWorkWindow.setRecording(false)
        FloatWorkWindow.setPaused(false)
        // 动作已经在 s.flow 里，落盘保底
        com.autoball.AB.store.save(s)
        FloatWorkWindow.exitStealth(activity)
        FloatWorkWindow.refresh(s)
        val n = s.flow?.actions?.size ?: 0
        Ui.toast(activity,
            if (n > 0) "录制结束，共 $n 步 · 可继续编辑或添加动作"
            else "录制结束，没有录到动作")
    }

    /** 动作列表变化后同步悬浮窗 */
    fun refresh(script: Script) = FloatWorkWindow.refresh(script)

    /** 录制结束：复位悬浮窗状态 */
    fun stopRecording() = FloatWorkWindow.setRecording(false)
}
