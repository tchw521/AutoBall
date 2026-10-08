package com.autoball.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.autoball.AB
import com.autoball.core.model.Script
import com.autoball.core.recorder.GlobalSettingsDialog
import com.autoball.float.FloatWorkWindow
import com.autoball.service.AutoBallAccessibilityService

/**
 * 脚本工作台入口（统一组件）。
 *
 * 「开始录制」与「添加动作」共用一个界面——进来后脚本还是空的，
 * 由用户决定是录制还是手动加动作，不在入口处就分叉。
 *
 * 优先形态是**悬浮窗**（[FloatWorkWindow]）：两者都要操作别的应用，
 * 应用内弹窗会占住屏幕，用户切不过去。未授予悬浮窗权限时自动降级为
 * 应用内弹窗（[showFallback]）。
 */
object ScriptWorkDialog {

    private val handler = Handler(Looper.getMainLooper())

    fun show(activity: Activity, script: Script, host: PageHost) {
        if (!com.autoball.core.util.Display.canDrawOverlay(activity)) {
            showFallback(activity, script, host)
            return
        }
        FloatWorkWindow.show(activity, script, object : FloatWorkWindow.Callback {
            override fun onRecord(s: Script) {
                host.startRecording()
            }

            override fun onAddAction(s: Script) {
                AutoBallAccessibilityService.openMainActivity(activity)
                handler.postDelayed({ host.openScript(s) }, 300)
            }

            override fun onSettings(s: Script) {
                val flow = s.flow
                if (flow == null) {
                    Ui.toast(activity, "该脚本还没有动作流")
                    return
                }
                // 全局设置含输入框与列表，用 Activity 弹窗承载更稳
                AutoBallAccessibilityService.openMainActivity(activity)
                handler.postDelayed({
                    GlobalSettingsDialog.show(activity, flow) { AB.store.save(s) }
                }, 340)
            }
        })
    }

    /** 降级：无悬浮窗权限时用应用内弹窗，功能不变但无法跨应用操作 */
    fun showFallback(activity: Activity, script: Script, host: PageHost) {
        val cb = object : FloatWorkWindow.Callback {
            override fun onRecord(s: Script) = host.startRecording()
            override fun onAddAction(s: Script) = host.openScript(s)
            override fun onSettings(s: Script) {
                val flow = s.flow
                if (flow == null) {
                    Ui.toast(activity, "该脚本还没有动作流")
                    return
                }
                GlobalSettingsDialog.show(activity, flow) { AB.store.save(s) }
            }
        }
        FloatWorkWindow.showInApp(activity, script, cb)
    }
}
