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

        /**
         * 当前前台包名。
         *
         * 优先用窗口事件记录的值；为空时用当前活跃窗口的根节点兜底
         * （部分 ROM 在冷启动时不上报 WINDOW_STATE_CHANGED）。
         */
        fun foregroundPkg(): String? {
            lastForegroundPkg?.let { return it }
            return runCatching {
                instance?.rootInActiveWindow?.packageName?.toString()
            }.getOrNull()
        }

        /**
         * 按**屏幕百分比坐标**找到覆盖该点的最小可点击节点（供 autoFind 提示用）。
         *
         * 用百分比而非像素：调用方给的是归一化坐标，这里按当前屏幕还原，
         * 与跨机型缩放的口径保持一致。
         *
         * @return 简要描述（文字 + 类名短名）；找不到或无权限返回 null
         */
        fun nodeAtPct(pctX: Float, pctY: Float): String? {
            val root = runCatching { instance?.rootInActiveWindow }.getOrNull() ?: return null
            return try {
                val sz = com.autoball.core.util.Display.screenSize(
                    com.autoball.App.get())
                val px = (pctX / 100f * sz.x).toInt()
                val py = (pctY / 100f * sz.y).toInt()

                var best: android.view.accessibility.AccessibilityNodeInfo? = null
                var bestArea = Int.MAX_VALUE
                val stack = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
                stack.add(root)
                while (!stack.isEmpty()) {
                    val n = stack.removeFirst()
                    val r = android.graphics.Rect()
                    n.getBoundsInScreen(r)
                    if (r.contains(px, py)) {
                        // 取**面积最小**的命中节点：最深/最具体的那个才是有意义的控件
                        val area = r.width() * r.height()
                        if (area < bestArea && (n.isClickable || n.text != null ||
                                n.contentDescription != null)) {
                            bestArea = area
                            best = n
                        }
                    }
                    for (i in 0 until n.childCount) {
                        n.getChild(i)?.let { stack.add(it) }
                    }
                }
                best?.let { n ->
                    val cname = n.className?.toString()?.substringAfterLast('.') ?: ""
                    val txt = n.text?.toString() ?: n.contentDescription?.toString() ?: ""
                    buildString {
                        if (txt.isNotBlank()) append("「${txt.take(20)}」")
                        if (cname.isNotBlank()) append(" $cname")
                    }.trim().takeIf { it.isNotBlank() }
                }
            } catch (e: Exception) { null }
            finally { runCatching { root.recycle() } }
        }

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
