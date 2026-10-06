package com.autoball.core.backend

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.view.accessibility.AccessibilityNodeInfo
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.autoball.core.model.*
import com.autoball.service.AutoBallAccessibilityService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * 无障碍执行后端。
 *
 * 特点：无需第二个 App、普适性最好、独有控件节点能力；
 * 短板是原生多指在部分 ROM 上被降级、连续高频注入不稳定、截图在部分场景受限。
 */
class AccessibilityBackend : InputBackend {

    override val id: BackendId = BackendId.ACCESSIBILITY

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun isAvailable(): Boolean = AutoBallAccessibilityService.instance != null

    override fun health(): BackendHealth {
        val svc = AutoBallAccessibilityService.instance ?: return BackendHealth.UNAVAILABLE
        return if (svc.isConnected) BackendHealth.READY else BackendHealth.DEGRADED
    }

    override fun capabilities(): CapabilitySet {
        if (!isAvailable()) return CapabilitySet(emptySet())
        val granted = LinkedHashSet<Cap>()
        granted.add(Cap.POINTER_CLICK)
        granted.add(Cap.LONG_PRESS)
        granted.add(Cap.SINGLE_SWIPE)
        granted.add(Cap.GLOBAL_BACK)
        granted.add(Cap.NODE_QUERY)
        granted.add(Cap.UI_FEEDBACK)
        granted.add(Cap.APP_START)
        if (Build.VERSION.SDK_INT >= 21) granted.add(Cap.TEXT_INPUT)
        if (Build.VERSION.SDK_INT >= 30) granted.add(Cap.SCREENSHOT)

        // 多指与高频注入在厂商 ROM 上不稳定，只作为实验性能力参与排序，不当作承诺
        val experimental = LinkedHashSet<Cap>()
        experimental.add(Cap.MULTI_POINTER)
        experimental.add(Cap.HIGH_THROUGHPUT)
        return CapabilitySet(granted, experimental)
    }

    override fun cancel() {
        // 无障碍没有取消 API：靠不再派发 + 全局手势（HOME 会打断，但不主动使用）
    }

    override fun execute(action: Action, ctx: ExecContext): ActionResult {
        val t0 = System.currentTimeMillis()
        val svc = AutoBallAccessibilityService.instance
            ?: return ActionResult.fail(BackendId.ACCESSIBILITY, 0, "无障碍服务未启动")
        val res = when (action.type) {
            ActionType.CLICK,
            ActionType.CLICK_IMAGE,
            ActionType.CLICK_TEXT,
            ActionType.CLICK_COLOR,
            ActionType.CLICK_NODE,
            ActionType.AI_CLICK -> click(svc, action, ctx)
            ActionType.SWIPE -> swipe(svc, action)
            ActionType.GESTURE_SINGLE -> singleGesture(svc, action)
            ActionType.GESTURE_MULTI -> multiGesture(svc, action)
            ActionType.KEY -> globalOrKey(svc, action)
            ActionType.OPEN_APP -> openApp(svc, action)
            ActionType.OPEN_URL -> openUrl(svc, action)
            ActionType.INPUT_TEXT -> inputText(svc, action)
            ActionType.TOAST -> toast(action)
            ActionType.RECOGNIZE_SCREEN -> recognize(action, ctx)
            else -> ActionResult.unsupported(id, action.type.required)
        }
        val ms = System.currentTimeMillis() - t0
        return ActionResult(res.ok, id, ms, res.message, res.degraded, res.cause)
    }

    // ---------- 指针 ----------

    private fun click(svc: AutoBallAccessibilityService, action: Action, ctx: ExecContext): ActionResult {
        // 需要节点查找的动作先定位坐标
        if (action.type == ActionType.CLICK_NODE || action.type == ActionType.CLICK_TEXT) {
            val node = findNode(svc, action)
            if (node != null) {
                val r = android.graphics.Rect()
                node.getBoundsInScreen(r)
                node.recycle()
                return if (dispatchTap(svc, r.centerX().toFloat(), r.centerY().toFloat(),
                        action.durationMs.coerceAtLeast(10L), action.timeoutMs)) {
                    ActionResult.ok(id, 0, "节点点击")
                } else ActionResult.fail(id, 0, "节点点击被取消")
            }
            if (action.x <= 0f && action.y <= 0f) {
                return ActionResult.fail(id, 0, "未找到匹配控件")
            }
        }
        if (action.type == ActionType.CLICK_IMAGE || action.type == ActionType.CLICK_COLOR) {
            val pt = resolveByScreen(action, ctx)
            if (pt == null) return ActionResult.fail(id, 0, "未匹配到目标（取色/找图）")
            return if (dispatchTap(svc, pt.x, pt.y, action.durationMs.coerceAtLeast(10L), action.timeoutMs))
                ActionResult.ok(id, 0, "匹配点击") else ActionResult.fail(id, 0, "匹配点击被取消")
        }
        return if (dispatchTap(svc, action.x, action.y, action.durationMs.coerceAtLeast(10L), action.timeoutMs))
            ActionResult.ok(id, 0, null) else ActionResult.fail(id, 0, "手势被取消")
    }

    private fun dispatchTap(svc: AutoBallAccessibilityService, x: Float, y: Float, dur: Long, timeout: Long): Boolean {
        val p = Path()
        p.moveTo(x, y)
        p.lineTo(x, y)
        return dispatch(svc, GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, dur))
            .build(), timeout)
    }

    private fun swipe(svc: AutoBallAccessibilityService, action: Action): ActionResult {
        val p = Path()
        p.moveTo(action.x, action.y)
        p.lineTo(action.x2, action.y2)
        val ok = dispatch(svc, GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, action.durationMs.coerceAtLeast(30L)))
            .build(), action.timeoutMs)
        return if (ok) ActionResult.ok(id, 0, null) else ActionResult.fail(id, 0, "滑动被取消")
    }

    private fun singleGesture(svc: AutoBallAccessibilityService, action: Action): ActionResult {
        val pts = if (action.path.size >= 2) action.path else
            mutableListOf(Pt(action.x, action.y), Pt(action.x2, action.y2))
        if (pts.size < 2) return ActionResult.fail(id, 0, "手势点数不足")
        val p = Path()
        p.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) p.lineTo(pts[i].x, pts[i].y)
        val ok = dispatch(svc, GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, action.durationMs.coerceAtLeast(30L)))
            .build(), action.timeoutMs)
        return if (ok) ActionResult.ok(id, 0, null) else ActionResult.fail(id, 0, "手势被取消")
    }

    /** 多指：并行 stroke。ROM 不支持时会退化成顺序执行或失败，由上层策略决定 */
    private fun multiGesture(svc: AutoBallAccessibilityService, action: Action): ActionResult {
        val strokes = action.strokes.filter { it.size >= 2 }
        if (strokes.isEmpty()) return ActionResult.fail(id, 0, "多指手势数据为空")
        val builder = GestureDescription.Builder()
        for (st in strokes) {
            val p = Path()
            p.moveTo(st[0].x, st[0].y)
            for (i in 1 until st.size) p.lineTo(st[i].x, st[i].y)
            builder.addStroke(GestureDescription.StrokeDescription(p, 0, action.durationMs.coerceAtLeast(30L)))
        }
        val ok = dispatch(svc, builder.build(), action.timeoutMs)
        return if (ok) ActionResult.ok(id, 0, null) else ActionResult.fail(id, 0, "多指手势被取消")
    }

    /** 派发统一走主线程，避免部分 ROM 在非 UI 线程注入失败 */
    private fun dispatch(svc: AutoBallAccessibilityService, gd: GestureDescription, timeoutMs: Long): Boolean {
        val latch = CountDownLatch(1)
        var done = false
        var ok = false
        val posted = mainHandler.post {
            val sent = svc.dispatchGesture(gd, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    ok = true; done = true; latch.countDown()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    ok = false; done = true; latch.countDown()
                }
            }, mainHandler)
            if (!sent) { done = true; latch.countDown() }
        }
        if (!posted) return false
        try {
            latch.await(timeoutMs.coerceAtMost(60_000), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            return false
        }
        return done && ok
    }

    // ---------- 系统动作 ----------

    private fun globalOrKey(svc: AutoBallAccessibilityService, action: Action): ActionResult {
        val mapped = when (action.keyCode) {
            android.view.KeyEvent.KEYCODE_BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            android.view.KeyEvent.KEYCODE_HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            android.view.KeyEvent.KEYCODE_APP_SWITCH -> AccessibilityService.GLOBAL_ACTION_RECENTS
            else -> -1
        }
        if (mapped < 0) return ActionResult.fail(id, 0, "无障碍后端不支持该按键，可切换 Shizuku")
        return if (svc.performGlobalAction(mapped)) ActionResult.ok(id, 0, null)
        else ActionResult.fail(id, 0, "全局动作执行失败")
    }

    private fun openApp(svc: AutoBallAccessibilityService, action: Action): ActionResult {
        val pkg = action.pkg ?: return ActionResult.fail(id, 0, "未指定应用")
        return try {
            val intent = svc.packageManager.getLaunchIntentForPackage(pkg)
                ?: return ActionResult.fail(id, 0, "无法获取启动意图")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            svc.startActivity(intent)
            ActionResult.ok(id, 0, null)
        } catch (e: Exception) {
            ActionResult.fail(id, 0, e.message)
        }
    }

    private fun openUrl(svc: AutoBallAccessibilityService, action: Action): ActionResult {
        val url = action.url ?: return ActionResult.fail(id, 0, "未指定链接")
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            svc.startActivity(intent)
            ActionResult.ok(id, 0, null)
        } catch (e: Exception) {
            ActionResult.fail(id, 0, e.message)
        }
    }

    private fun inputText(svc: AutoBallAccessibilityService, action: Action): ActionResult {
        if (Build.VERSION.SDK_INT < 21) return ActionResult.fail(id, 0, "系统版本过低")
        val root = svc.rootInActiveWindow ?: return ActionResult.fail(id, 0, "无法获取当前界面")
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: run { root.recycle(); return ActionResult.fail(id, 0, "没有获得焦点的输入框") }
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text ?: "")
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        node.recycle()
        root.recycle()
        return if (ok) ActionResult.ok(id, 0, null) else ActionResult.fail(id, 0, "输入失败")
    }

    private fun toast(action: Action): ActionResult {
        mainHandler.post {
            android.widget.Toast.makeText(
                com.autoball.App.get(), action.text ?: "", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        return ActionResult.ok(id, 0, null)
    }

    private fun recognize(action: Action, ctx: ExecContext): ActionResult {
        val sr = screenshot(ctx)
        return when (sr) {
            is ScreenResult.Ok -> {
                ctx.log("识别屏幕完成 ${sr.width}x${sr.height}")
                ActionResult.ok(id, 0, "识别完成")
            }
            is ScreenResult.Unavailable -> ActionResult.fail(id, 0, sr.reason)
        }
    }

    // ---------- 节点 ----------

    private fun findNode(svc: AutoBallAccessibilityService, action: Action): AccessibilityNodeInfo? {
        val root = svc.rootInActiveWindow ?: return null
        val spec = action.nodeSpec
        val list = if (spec != null) {
            when {
                !spec.id.isNullOrEmpty() -> root.findAccessibilityNodeInfosByViewId(spec.id!!)
                !spec.text.isNullOrEmpty() -> root.findAccessibilityNodeInfosByText(spec.text!!)
                else -> findByPredicate(root, spec)
            }
        } else if (!action.text.isNullOrEmpty()) {
            root.findAccessibilityNodeInfosByText(action.text!!)
        } else emptyList()

        if (list.isEmpty()) { root.recycle(); return null }
        val picked = list[0]
        list.forEach { if (it !== picked) it.recycle() }
        root.recycle()
        return picked
    }

    private fun findByPredicate(root: AccessibilityNodeInfo, spec: NodeSpec): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        walk(root) { n ->
            val okText = spec.text.isNullOrEmpty() ||
                    (n.text?.toString()?.contains(spec.text!!) == true)
            val okDesc = spec.desc.isNullOrEmpty() ||
                    (n.contentDescription?.toString()?.contains(spec.desc!!) == true)
            val okCls = spec.className.isNullOrEmpty() ||
                    n.className?.toString()?.contains(spec.className!!) == true
            val okClick = !spec.clickableOnly || n.isClickable
            if (okText && okDesc && okCls && okClick) out.add(AccessibilityNodeInfo.obtain(n))
        }
        return out
    }

    private fun walk(node: AccessibilityNodeInfo, visitor: (AccessibilityNodeInfo) -> Unit) {
        visitor(node)
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            walk(c, visitor)
            c.recycle()
        }
    }

    /** 对外暴露的节点定位：只算坐标，不产生点击副作用 */
    fun nodeCenter(action: Action): Pt? {
        val svc = AutoBallAccessibilityService.instance ?: return null
        val node = findNode(svc, action) ?: return null
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        node.recycle()
        return Pt(r.centerX().toFloat(), r.centerY().toFloat())
    }

    // ---------- 截图 ----------

    @SuppressLint("NewApi")
    override fun screenshot(ctx: ExecContext): ScreenResult {
        if (Build.VERSION.SDK_INT < 30) return ScreenResult.Unavailable("Android 11 以下不支持无障碍截图")
        val svc = AutoBallAccessibilityService.instance ?: return ScreenResult.Unavailable("无障碍服务未启动")
        val latch = CountDownLatch(1)
        var bmp: Bitmap? = null
        var err: String? = null
        val exec = Executor { cmd -> mainHandler.post(cmd) }
        val posted = mainHandler.post {
            try {
                svc.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, exec,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            bmp = extractBitmap(result)
                            if (bmp == null) err = "无法解析截图结果"
                            latch.countDown()
                        }
                        override fun onFailure(errorCode: Int) {
                            err = "截图失败($errorCode)"
                            latch.countDown()
                        }
                    })
            } catch (e: Throwable) {
                err = e.message ?: "截图异常"
                latch.countDown()
            }
        }
        if (!posted) return ScreenResult.Unavailable("无法派发截图")
        try { latch.await(5, TimeUnit.SECONDS) } catch (e: InterruptedException) { }
        val b = bmp ?: return ScreenResult.Unavailable(err ?: "截图超时")
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        return ScreenResult.Ok(w, h, px, id)
    }

    /** 不同版本 ScreenshotResult 的取值方式不同，用反射兜底，避免编译期绑定到单一实现 */
    private fun extractBitmap(r: AccessibilityService.ScreenshotResult): Bitmap? {
        try {
            val m = r.javaClass.getMethod("getBitmap")
            val o = m.invoke(r)
            if (o is Bitmap) return o
        } catch (ignored: Throwable) { }
        // 从 HardwareBuffer 取 Bitmap 需 API 28 的 wrapHardwareBuffer；低版本返回 null 由上层显式提示
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val m = r.javaClass.getMethod("getHardwareBuffer")
                val hb = m.invoke(r)
                if (hb is android.hardware.HardwareBuffer) {
                    return Bitmap.wrapHardwareBuffer(hb, null)
                }
            } catch (ignored: Throwable) { }
        }
        return null
    }

    /** 找图/找色：先把屏幕取回来，再在像素里匹配。模块未下载时显式返回不可用 */
    private fun resolveByScreen(action: Action, ctx: ExecContext): Pt? {
        val sr = screenshot(ctx)
        when (sr) {
            is ScreenResult.Unavailable -> return null
            is ScreenResult.Ok -> {
                if (action.type == ActionType.CLICK_COLOR && action.colorHex != null) {
                    val target = parseColor(action.colorHex!!) ?: return null
                    var sumX = 0.0; var sumY = 0.0; var n = 0
                    val tol = action.colorTolerance
                    val step = 3
                    var y = 0
                    while (y < sr.height) {
                        var x = 0
                        while (x < sr.width) {
                            val c = sr.pixels[y * sr.width + x]
                            if (kotlin.math.abs((c shr 16 and 0xFF) - (target shr 16 and 0xFF)) <= tol &&
                                kotlin.math.abs((c shr 8 and 0xFF) - (target shr 8 and 0xFF)) <= tol &&
                                kotlin.math.abs((c and 0xFF) - (target and 0xFF)) <= tol) {
                                sumX += x; sumY += y; n++
                            }
                            x += step
                        }
                        y += step
                    }
                    return if (n == 0) null else Pt((sumX / n).toFloat(), (sumY / n).toFloat())
                }
                return null
            }
        }
    }

    private fun parseColor(hex: String): Int? = try {
        android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")
    } catch (e: Exception) { null }

    companion object {
        fun eventTypeName(ev: AccessibilityEvent): String = when (ev.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT"
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> "VIEW_FOCUSED"
            else -> "OTHER"
        }
    }
}
