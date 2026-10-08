package com.autoball.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.autoball.AB
import com.autoball.core.model.Script
import com.autoball.core.recorder.GlobalSettingsDialog
import com.autoball.core.util.Display
import com.autoball.float.FloatWorkWindow

/**
 * 脚本工作台入口（统一组件）。
 *
 * 「开始录制」与「添加动作」共用一个界面——进来后脚本还是空的，
 * 由用户决定是录制还是手动加动作，不在入口处就分叉。
 *
 * 形态是**悬浮窗**（[FloatWorkWindow]，仿自动精灵布局）：两者都要操作别的应用，
 * 应用内弹窗占住屏幕，用户切不过去。未授予悬浮窗权限时提示用户去开启，
 * 因为降级成应用内弹窗会让录制功能事实上不可用。
 */
object ScriptWorkDialog {

    private val handler = Handler(Looper.getMainLooper())

    fun show(activity: Activity, script: Script, host: PageHost) {
        if (!Display.canDrawOverlay(activity)) {
            Ui.toast(activity, "录制与添加动作需要悬浮窗权限，请先开启")
            Display.openOverlaySettings(activity)
            return
        }
        FloatWorkWindow.show(activity, script, object : FloatWorkWindow.Callback {
            override fun onRecord(s: Script) {
                FloatWorkWindow.setRecording(true)
                host.startRecording()
            }

            override fun onAddAction(s: Script) {
                // 添加动作要回到应用内的动作编辑器（表单复杂，悬浮窗承载不下）
                host.openScript(s)
            }

            override fun onSettings(s: Script) {
                // 全局设置同样在悬浮窗层弹出，不把用户拽回应用界面
                val flow = s.flow
                if (flow == null) {
                    Ui.toast(activity, "该脚本还没有动作流")
                    return
                }
                GlobalSettingsDialog.showFloat(activity, flow) { AB.store.save(s) }
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

            override fun onLog(s: Script) {
                host.openSubPage("log")
            }
        })
    }

    /** 动作列表变化后同步悬浮窗 */
    fun refresh(script: Script) = FloatWorkWindow.refresh(script)

    /** 录制结束：复位悬浮窗状态 */
    fun stopRecording() = FloatWorkWindow.setRecording(false)
}
