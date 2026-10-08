package com.autoball.service

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.autoball.AB
import com.autoball.core.model.Script

/**
 * 消息触发（复刻自动精灵「收到指定消息时启动脚本」）。
 *
 * 用户在系统设置里授予「通知使用权」后，本服务才会被绑定。
 * 收到通知时按脚本配置的 包名 + 关键词 匹配，命中即运行对应脚本。
 *
 * 两点约束：
 * 1. **默认不开启**：需要用户显式授权，且每个脚本单独配置触发条件。
 * 2. **不做后台常驻拉活**：服务由系统在有通知时回调，不额外保活。
 */
class NotifyService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        AB.log.info("notify", "通知监听已连接")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        AB.log.warn("notify", "通知监听已断开")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        runCatching {
            val pkg = n.packageName ?: return

            // 本应用自己的通知不算——否则运行提示会自我触发
            if (pkg == packageName) return

            val extras: Bundle = n.notification?.extras ?: return
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
            val body = listOf(title, text, bigText).filter { it.isNotEmpty() }
                .joinToString("\n")
            if (body.isEmpty()) return

            val hit = AB.store.all().firstOrNull { s -> matches(s, pkg, body) } ?: return

            AB.log.info("notify", "消息触发：${hit.name}（来自 $pkg）")
            // 把触发来源写进变量，脚本里可用 $notifyPkg / $notifyText
            trigger(hit, pkg, body)
        }.onFailure {
            AB.log.warn("notify", "处理通知失败：${it.message}")
        }
    }

    private fun matches(s: Script, pkg: String, body: String): Boolean {
        if (!s.notifyEnabled) return false
        if (s.notifyPkg.isNotEmpty() && !pkg.equals(s.notifyPkg, ignoreCase = true)) return false
        val kw = s.notifyKeyword.trim()
        return kw.isEmpty() || body.contains(kw, ignoreCase = true)
    }

    private fun trigger(s: Script, pkg: String, text: String) {
        // 通知回调线程不能直接操作 UI，交给宿主 Activity 或悬浮窗执行
        val host = AB.notifyHost
        if (host != null) {
            host.runScriptWithVars(s, mapOf("notifyPkg" to pkg, "notifyText" to text))
        } else {
            AB.log.warn("notify", "无运行宿主，已忽略触发（请先启动一次应用）")
        }
    }

    companion object {
        /** 是否已授予通知使用权 */
        fun isEnabled(): Boolean {
            return try {
                android.provider.Settings.Secure.getString(
                    com.autoball.App.get().contentResolver,
                    "enabled_notification_listeners"
                )?.contains(NotifyService::class.java.name) == true
            } catch (e: Exception) { false }
        }
    }
}
