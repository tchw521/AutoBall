package com.autoball.core.util

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Point
import android.os.Build
import android.provider.Settings
import android.view.WindowManager

object Display {

    /**
     * 屏幕密度缓存。
     *
     * 性能：`dp()` / `dpInt()` 是全局调用频次最高的函数之一（一次页面重建
     * 上千次），原实现每次都要经 `ctx.resources.displayMetrics` 取值。
     * 密度在同一进程内不会变，缓存后省掉重复的 Resources 查找。
     */
    @Volatile
    private var densityCache: Float = 0f

    private fun density(ctx: Context): Float {
        val d = densityCache
        if (d > 0f) return d
        val v = ctx.resources.displayMetrics.density
        densityCache = v
        return v
    }

    fun dp(ctx: Context, v: Float): Float = v * density(ctx)
    fun dpInt(ctx: Context, v: Float): Int = (v * density(ctx) + 0.5f).toInt()

    /**
     * 屏幕尺寸缓存。
     *
     * 性能：动作流每步都要按屏幕换算百分比坐标，原实现每步都查一次
     * WindowManager。屏幕尺寸只在转屏时变，缓存后由 `invalidateScreen()`
     * 主动失效。
     */
    @Volatile
    private var screenCache: Point? = null

    fun screenSize(ctx: Context): Point {
        val c = screenCache
        if (c != null) return Point(c.x, c.y)
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val p = if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val q = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(q)
            q
        }
        screenCache = Point(p.x, p.y)
        return p
    }

    /** 转屏或分辨率变化后调用，使缓存失效 */
    fun invalidateScreen() { screenCache = null }

    /** 悬浮窗权限 */
    fun canDrawOverlay(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    /** 无障碍是否已启用（配置层检查） */
    fun accessibilityEnabled(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE)
                as? android.view.accessibility.AccessibilityManager ?: return false
        val list = am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC)
        val pkg = ctx.packageName
        for (info in list) {
            val id = info.id
            if (id != null && id.startsWith(pkg)) return true
            val rc = info.resolveInfo
            if (rc != null && rc.serviceInfo != null && rc.serviceInfo.packageName == pkg) return true
        }
        return false
    }

    /**
     * 已安装的可启动应用列表（供「目标应用」选择）。
     *
     * 只取带 LAUNCHER 入口的应用，并按包名去重——同一应用常有多个入口 Activity，
     * 不去重会出现重复项。结果按名称排序，主线程调用可接受（数量在百级）。
     */
    fun launchableApps(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        val seen = HashSet<String>()
        val out = ArrayList<Pair<String, String>>()
        val list = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent,
                android.content.pm.PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        for (ri in list) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (!seen.add(pkg)) continue
            val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
            out.add(pkg to label)
        }
        out.sortBy { it.second }
        return out
    }

    /** 应用名（取不到就回退包名） */
    fun appLabel(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** 启动指定包名的应用；失败返回 false */
    fun launchApp(ctx: Context, pkg: String): Boolean {
        return try {
            val launch = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            ctx.startActivity(launch)
            true
        } catch (e: Exception) {
            false
        }
    }

    @SuppressLint("NewApi")
    fun openOverlaySettings(ctx: Context) {
        try {
            ctx.startActivity(android.content.Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + ctx.packageName)
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (ignored: Exception) { }
    }
}
