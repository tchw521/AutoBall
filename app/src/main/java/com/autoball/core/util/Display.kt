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
