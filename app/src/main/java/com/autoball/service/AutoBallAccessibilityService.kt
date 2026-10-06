package com.autoball.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.autoball.AB
import com.autoball.ui.MainActivity

/**
 * 无障碍后端宿主。
 *
 * 只负责"连接存活 + 事件转发"，具体注入逻辑在 AccessibilityBackend 里，
 * 这样后端可以被 Shizuku 后端对等替换，执行链路不依赖本服务的类结构。
 */
class AutoBallAccessibilityService : AccessibilityService() {

    @Volatile
    var isConnected: Boolean = false
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isConnected = true
        AB.log.info("service", "无障碍服务已连接")
        AB.router.refresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 事件仅用于前台包名与界面变化感知，不做任何内容上传
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString()
                if (!pkg.isNullOrEmpty()) lastForegroundPkg = pkg
            }
            else -> { /* 其余事件不处理，避免无谓开销 */ }
        }
    }

    override fun onInterrupt() {
        AB.log.warn("service", "无障碍服务被中断")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isConnected = false
        if (instance === this) instance = null
        AB.log.warn("service", "无障碍服务已解绑")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        isConnected = false
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: AutoBallAccessibilityService? = null
            private set

        @Volatile
        var lastForegroundPkg: String? = null

        /** 引导用户前往无障碍设置页 */
        fun openAccessibilitySettings(context: android.content.Context) {
            try {
                context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                try {
                    context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (ignored: Exception) { }
            }
        }

        fun openAppSettings(context: android.content.Context) {
            try {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + context.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (ignored: Exception) { }
        }

        fun openMainActivity(context: android.content.Context) {
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
