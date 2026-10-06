package com.autoball.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.autoball.AB
import com.autoball.float.FloatManager

/**
 * 开机 / 包替换后尝试恢复悬浮球。
 *
 * 注意：非 Root 下 Shizuku 每次重启都要重新启动；无障碍服务的开关由系统控制，
 * 本接收器只恢复"悬浮层可见"，不承诺自动恢复授权。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (AB.store.getBool("float_persistent", true)) {
                    runCatching { FloatManager.showBall(context) }
                }
                // Shizuku 需要重新握手
                Thread {
                    runCatching { ShizukuClient.instance.probe() }
                }.apply { isDaemon = true }.start()
            }
        }
    }
}
