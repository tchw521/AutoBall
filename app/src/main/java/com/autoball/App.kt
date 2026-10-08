package com.autoball

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.autoball.core.backend.BackendRouter
import com.autoball.core.log.CrashGuard
import com.autoball.core.log.RunLog
import com.autoball.core.model.Script
import com.autoball.core.store.ScriptStore
import com.autoball.service.ShizukuClient

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        appRef = this
        CrashGuard.install()
        createChannels()
        trackTopActivity()
        // Shizuku 握手走后台线程：失败不影响主线程启动，也不影响无障碍后端
        Thread {
            runCatching { ShizukuClient.instance.probe() }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 记录当前前台 Activity。
     *
     * 脚本在**后台线程**里运行（不能卡主线程），而弹窗必须在主线程显示，
     * 且 AlertDialog 需要一个 Activity 作为宿主。此前没有任何地方持有
     * 前台 Activity，脚本里的 alert/confirm 根本无处可挂。
     *
     * 用弱引用：Activity 销毁后不该被 Application 一直持有。
     */
    private fun trackTopActivity() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { topRef = java.lang.ref.WeakReference(a) }
            override fun onActivityPaused(a: Activity) {
                if (topRef?.get() === a) topRef = null
            }
            override fun onActivityCreated(a: Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    /** 当前前台 Activity（可能为 null，如应用已退到后台） */
    fun topActivity(): Activity? = topRef?.get()

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL_RUN, getString(R.string.notify_channel_name),
                NotificationManager.IMPORTANCE_LOW)
            ch.setSound(null, null)
            nm.createNotificationChannel(ch)
        }
    }

    companion object {
        const val CHANNEL_RUN = "autoball_run"

        @Volatile
        private var topRef: java.lang.ref.WeakReference<Activity>? = null

        @Volatile
        private var appRef: App? = null

        fun get(): App = appRef ?: throw IllegalStateException("App 尚未初始化")
    }
}

/** 全局运行时单例（零依赖注入框架，手工持有） */
object AB {
    val router: BackendRouter = BackendRouter()
    val log: RunLog = RunLog()
    val store: ScriptStore by lazy { ScriptStore(App.get()) }

    /**
     * 消息触发的运行宿主。
     *
     * 通知回调发生在 NotifyService 进程里，那里没有 Activity，
     * 直接弹 UI 会崩。由主界面在 onCreate/onResume 注册自己，
     * 触发时把脚本与变量交给它执行。
     */
    @Volatile
    var notifyHost: NotifyHost? = null
}

/** 消息触发的运行宿主：由主界面实现 */
interface NotifyHost {
    /** 带初始变量运行脚本（供触发来源 pkg / text 注入） */
    fun runScriptWithVars(s: Script, vars: Map<String, String>)
}
