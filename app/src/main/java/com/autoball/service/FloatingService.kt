package com.autoball.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
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

        fun start(context: android.content.Context) {
            val i = Intent(context, FloatingService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, FloatingService::class.java).setAction(ACTION_STOP))
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 前台服务启动失败不应连带把进程拖崩：失败时记录原因并继续以普通服务运行
        runCatching {
            startForeground(NOTIFY_ID, buildNotification(getString(R.string.notify_idle)))
        }.onFailure { AB.log.error("service", "前台通知启动失败：${it.message}") }
        AB.log.info("service", "悬浮服务已启动")
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
