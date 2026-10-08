package com.autoball.ui

import android.app.Activity
import com.autoball.AB
import com.autoball.core.model.Script
import com.autoball.core.recorder.GlobalSettingsDialog
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
                    host.startRecording()
                    // 录制时让出屏幕：隐藏主窗口，只留贴边胶囊显示步数。
                    // 自动精灵如此——否则浮层盖住目标应用，采集层也易判为不可信遮挡
                    FloatWorkWindow.bindSteps { s.flow?.actions?.size ?: 0 }
                    FloatWorkWindow.enterStealth(activity)
                } else {
                    FloatWorkWindow.setRecording(false)
                    FloatWorkWindow.exitStealth(activity)
                    // 控制器没有 stop()：interrupt 会触发 onInterrupted 回调，
                    // 由 CreatePage 弹出「放弃 / 继续 / 保存」三选一并结束录制
                    CreatePage.controller?.interrupt("用户停止")
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

            override fun onStopRecord(s: Script) {
                FloatWorkWindow.setRecording(false)
                FloatWorkWindow.exitStealth(activity)
                CreatePage.controller?.interrupt("用户停止")
                FloatWorkWindow.refresh(s)
            }

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

    /** 动作列表变化后同步悬浮窗 */
    fun refresh(script: Script) = FloatWorkWindow.refresh(script)

    /** 录制结束：复位悬浮窗状态 */
    fun stopRecording() = FloatWorkWindow.setRecording(false)
}
