package com.autoball.core.util

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Point
import android.os.Build
import android.provider.Settings
import android.view.WindowManager

object Display {

    fun dp(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density
    fun dpInt(ctx: Context, v: Float): Int = dp(ctx, v).toInt()

    fun screenSize(ctx: Context): Point {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val m = wm.currentWindowMetrics
            val b = m.bounds
            Point(b.width(), b.height())
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            p
        }
    }

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
