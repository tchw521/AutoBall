package com.autoball.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.autoball.AB
import com.autoball.App
import com.autoball.R
import com.autoball.float.FloatManager
import com.autoball.ui.MainActivity

/**
 * 悬浮球 / 悬浮窗的宿主前台服务。
 *
 * 说明：保活只能降低被回收的频率，不能承诺不被系统杀死。
 * Android 14 要求显式声明 foregroundServiceType（manifest 中为 specialUse）。
 */
class FloatingService : Service() {

    companion object {
        private const val NOTIFY_ID = 1001
        const val ACTION_START = "com.autoball.action.START_FLOAT"
        const val ACTION_STOP = "com.autoball.action.STOP_FLOAT"
        const val ACTION_RUN = "com.autoball.action.RUN"
        const val EXTRA_SCRIPT_ID = "script_id"

        fun start(context: android.content.Context): Boolean {
            val i = Intent(context, FloatingService::class.java).setAction(ACTION_START)
            // Android 12+ 后台启动前台服务会被系统拒绝；失败只记录，不让调用方崩
            return try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
                else context.startService(i)
                true
            } catch (e: Throwable) {
                AB.log.error("service", "启动悬浮服务失败：${e.javaClass.simpleName}")
                false
            }
        }

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, FloatingService::class.java).setAction(ACTION_STOP))
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 关键：调用 startForegroundService() 后必须在数秒内真正调用 startForeground()，
        // 否则系统会抛 ForegroundServiceDidNotStartInTimeException 直接杀进程。
        // 因此这里不能用 runCatching 静默吞掉异常——失败必须止损（stopSelf），
        // 否则服务留在"已承诺前台"状态却没进前台，必定超时崩溃。
        val ok = startForegroundCompat()
        if (!ok) {
            AB.log.error("service", "前台化失败，服务主动停止以避免系统超时崩溃")
            stopSelf()
            return
        }
        AB.log.info("service", "悬浮服务已启动")
    }

    /** 与 manifest 声明的 specialUse 类型保持一致；低版本用两参重载 */
    private fun startForegroundCompat(): Boolean = try {
        val n = buildNotification(getString(R.string.notify_idle))
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFY_ID, n)
        }
        true
    } catch (e: Throwable) {
        AB.log.error("service", "startForeground 失败：${e.javaClass.simpleName} ${e.message}")
        false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                FloatManager.hideAll()
                stopForegroundCompat()
                stopSelf()
            }
            ACTION_RUN -> {
                val id = intent.getStringExtra(EXTRA_SCRIPT_ID)
                if (!id.isNullOrEmpty()) {
                    val script = AB.store.get(id)
                    if (script != null) {
                        com.autoball.core.engine.ScriptLauncher.launch(this, script)
                    }
                }
            }
            else -> {
                FloatManager.showBall(this)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        FloatManager.hideAll()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingService::class.java).setAction(ACTION_STOP),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
        )

        val b = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, App.CHANNEL_RUN)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b.setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("AutoBall")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .addAction(buildStopAction(stopPi))
            .build()
    }

    /**
     * 通知动作图标：Android 6.0（API 23）才有 Icon，低版本用旧的 int 资源重载。
     * 图标必须是纯 alpha 图，否则部分 ROM 会因 "Bad notification posted" 崩溃。
     */
    private fun buildStopAction(stopPi: PendingIntent): Notification.Action {
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 23) {
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat),
                "停止", stopPi).build()
        } else {
            Notification.Action.Builder(R.drawable.ic_stat, "停止", stopPi).build()
        }
    }

    /** 供外部刷新通知文案 */
    fun refreshNotification(text: String) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NOTIFY_ID, buildNotification(text))
    }
}
