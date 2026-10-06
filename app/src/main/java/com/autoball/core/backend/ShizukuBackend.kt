package com.autoball.core.backend

import android.graphics.BitmapFactory
import com.autoball.core.model.*
import com.autoball.service.ShizukuClient
import java.io.File

/**
 * Shizuku / 提权 Shell 执行后端。
 *
 * 与无障碍后端**并行对等**：只要 Shizuku 通道可用，即可独立运行完整脚本。
 * 特点：坐标注入更接近系统原生、按键与文本输入能力完整、`screencap` 取屏快；
 * 短板：无控件节点能力、无 Toast 通道、多指 shell 不支持（降级为顺序单指）。
 */
class ShizukuBackend : InputBackend {

    override val id: BackendId = BackendId.SHIZUKU

    private val client: ShizukuClient get() = ShizukuClient.instance

    private val tmpPng: String get() = "/data/local/tmp/autoball_screen.png"

    override fun isAvailable(): Boolean = client.isAuthorized()

    override fun health(): BackendHealth {
        if (!isAvailable()) return BackendHealth.UNAVAILABLE
        return when (client.channel) {
            ShizukuClient.Channel.SHIZUKU_BINDER -> BackendHealth.READY
            ShizukuClient.Channel.ROOT_SU -> BackendHealth.READY
            else -> BackendHealth.DEGRADED
        }
    }

    override fun capabilities(): CapabilitySet {
        if (!isAvailable()) return CapabilitySet(emptySet())
        val granted = LinkedHashSet<Cap>()
        granted.add(Cap.POINTER_CLICK)
        granted.add(Cap.LONG_PRESS)
        granted.add(Cap.SINGLE_SWIPE)
        granted.add(Cap.TEXT_INPUT)
        granted.add(Cap.SYSTEM_KEY)
        granted.add(Cap.GLOBAL_BACK)
        granted.add(Cap.APP_START)
        granted.add(Cap.SCREENSHOT)
        granted.add(Cap.HIGH_THROUGHPUT)
        // shell `input` 无法表达真正多指：记为实验性，运行时降级为顺序单指
        val experimental = LinkedHashSet<Cap>()
        experimental.add(Cap.MULTI_POINTER)
        return CapabilitySet(granted, experimental)
    }

    override fun cancel() {
        // shell 命令是一次性的，无进行中的注入可取消
    }

    override fun execute(action: Action, ctx: ExecContext): ActionResult {
        val t0 = System.currentTimeMillis()
        val res = when (action.type) {
            ActionType.CLICK -> tap(action)
            ActionType.CLICK_IMAGE, ActionType.CLICK_COLOR, ActionType.AI_CLICK -> tapResolved(action, ctx)
            ActionType.CLICK_NODE, ActionType.CLICK_TEXT -> tapResolved(action, ctx)
            ActionType.SWIPE -> swipe(action)
            ActionType.GESTURE_SINGLE -> singleGesture(action)
            ActionType.GESTURE_MULTI -> multiGesture(action)
            ActionType.KEY -> key(action)
            ActionType.OPEN_APP -> openApp(action)
            ActionType.OPEN_URL -> openUrl(action)
            ActionType.INPUT_TEXT -> inputText(action)
            ActionType.RECOGNIZE_SCREEN -> recognize(action, ctx)
            else -> ActionResult.unsupported(id, action.type.required)
        }
        val ms = System.currentTimeMillis() - t0
        return ActionResult(res.ok, id, ms, res.message, res.degraded, res.cause)
    }

    // ---------- 指针 ----------

    private fun tap(a: Action): ActionResult {
        val r = client.exec(arrayOf("input", "tap", a.x.toInt().toString(), a.y.toInt().toString()))
        return if (r.ok) ActionResult.ok(id, 0, null) else ActionResult.fail(id, 0, r.err.ifEmpty { "点击失败" })
    }

    private fun tapResolved(a: Action, ctx: ExecContext): ActionResult {
        // 需要节点/图像的动作：Shizuku 无节点能力；有截图时可按取色定位，否则要求已给定坐标
        if (a.x > 0f || a.y > 0f) return tap(a)
        if (a.type == ActionType.CLICK_COLOR) {
            val sr = screenshot(ctx)
            if (sr is ScreenResult.Ok) {
                val pt = matchColor(sr, a)
                if (pt != null) {
                    val r = client.exec(arrayOf("input", "tap", pt.x.toInt().toString(), pt.y.toInt().toString()))
                    return if (r.ok) ActionResult.ok(id, 0, "取色点击") else ActionResult.fail(id, 0, r.err)
                }
            }
        }
        return ActionResult.fail(id, 0, "该动作需要控件节点或图像能力，请切换无障碍后端")
    }

    private fun swipe(a: Action): ActionResult {
        val r = client.exec(
            arrayOf(
                "input", "swipe",
                a.x.toInt().toString(), a.y.toInt().toString(),
                a.x2.toInt().toString(), a.y2.toInt().toString(),
                a.durationMs.coerceAtLeast(30L).toString()
            )
        )
        return if (r.ok) ActionResult.ok(id, 0, null) else ActionResult.fail(id, 0, r.err.ifEmpty { "滑动失败" })
    }

    /** shell 只支持直线滑动：多段路径降级为首末点直线，并明确标记为降级 */
    private fun singleGesture(a: Action): ActionResult {
        val pts = if (a.path.size >= 2) a.path else mutableListOf(Pt(a.x, a.y), Pt(a.x2, a.y2))
        if (pts.size < 2) return ActionResult.fail(id, 0, "手势点数不足")
        val first = pts.first()
        val last = pts.last()
        val r = client.exec(
            arrayOf(
                "input", "swipe",
                first.x.toInt().toString(), first.y.toInt().toString(),
                last.x.toInt().toString(), last.y.toInt().toString(),
                a.durationMs.coerceAtLeast(30L).toString()
            )
        )
        val degraded = pts.size > 2
        return if (r.ok) ActionResult(true, id, 0, if (degraded) "多段手势已降级为直线滑动" else null, degraded)
        else ActionResult.fail(id, 0, r.err)
    }

    /** 多指：顺序执行每条指针路径（FALLBACK_SEQUENTIAL），显式标记降级 */
    private fun multiGesture(a: Action): ActionResult {
        val strokes = a.strokes.filter { it.size >= 2 }
        if (strokes.isEmpty()) return ActionResult.fail(id, 0, "多指手势数据为空")
        for (st in strokes) {
            val first = st.first(); val last = st.last()
            val r = client.exec(
                arrayOf(
                    "input", "swipe",
                    first.x.toInt().toString(), first.y.toInt().toString(),
                    last.x.toInt().toString(), last.y.toInt().toString(),
                    a.durationMs.coerceAtLeast(30L).toString()
                )
            )
            if (!r.ok) return ActionResult.fail(id, 0, r.err.ifEmpty { "多指降级执行失败" })
        }
        return ActionResult.degraded(id, 0, "shell 不支持原生多指，已按顺序单指执行")
    }

    // ---------- 系统与文本 ----------

    private fun key(a: Action): ActionResult {
        val r = client.exec(arrayOf("input", "keyevent", a.keyCode.toString()))
        return if (r.ok) ActionResult.ok(id, 0, null) else ActionResult.fail(id, 0, r.err.ifEmpty { "按键失败" })
    }

    private fun openApp(a: Action): ActionResult {
        val pkg = a.pkg ?: return ActionResult.fail(id, 0, "未指定应用")
        var r = client.exec(arrayOf("monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1"))
        if (!r.ok) {
            r = client.exec(arrayOf("cmd", "package", "resolve-activity", "--brief", pkg))
            if (!r.ok) return ActionResult.fail(id, 0, "启动应用失败: ${r.err}")
        }
        return ActionResult.ok(id, 0, null)
    }

    private fun openUrl(a: Action): ActionResult {
        val url = a.url ?: return ActionResult.fail(id, 0, "未指定链接")
        val r = client.exec(arrayOf("am", "start", "-a", "android.intent.action.VIEW", "-d", url))
        return if (r.ok) ActionResult.ok(id, 0, null) else ActionResult.fail(id, 0, r.err.ifEmpty { "打开链接失败" })
    }

    private fun inputText(a: Action): ActionResult {
        val raw = a.text ?: return ActionResult.fail(id, 0, "未指定文本")
        // input text 用 %s 表示空格
        val safe = raw.replace(" ", "%s").replace("'", "").replace("\"", "")
        val r = client.exec(arrayOf("input", "text", safe))
        return if (r.ok) ActionResult.ok(id, 0, null) else ActionResult.fail(id, 0, r.err.ifEmpty { "输入失败" })
    }

    private fun recognize(a: Action, ctx: ExecContext): ActionResult {
        val sr = screenshot(ctx)
        return when (sr) {
            is ScreenResult.Ok -> { ctx.log("Shizuku 取屏 ${sr.width}x${sr.height}"); ActionResult.ok(id, 0, "识别完成") }
            is ScreenResult.Unavailable -> ActionResult.fail(id, 0, sr.reason)
        }
    }

    // ---------- 截图 ----------

    override fun screenshot(ctx: ExecContext): ScreenResult {
        if (!isAvailable()) return ScreenResult.Unavailable("Shizuku 未授权")
        val f = File(tmpPng)
        runCatching { if (f.exists()) f.delete() }
        val r = client.exec(arrayOf("screencap", "-p", tmpPng), 8000)
        if (!r.ok) return ScreenResult.Unavailable(r.err.ifEmpty { "screencap 执行失败" })
        if (!f.exists()) return ScreenResult.Unavailable("截图文件未生成")
        return try {
            val bmp = BitmapFactory.decodeFile(f.absolutePath)
                ?: return ScreenResult.Unavailable("截图解码失败")
            val w = bmp.width; val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            bmp.recycle()
            ScreenResult.Ok(w, h, px, id)
        } catch (e: Throwable) {
            ScreenResult.Unavailable(e.message ?: "截图异常")
        }
    }

    private fun matchColor(sr: ScreenResult.Ok, a: Action): Pt? {
        val hex = a.colorHex ?: return null
        val target = try { android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex") }
                     catch (e: Exception) { return null }
        val tol = a.colorTolerance
        var sx = 0.0; var sy = 0.0; var n = 0
        val step = 3
        var y = 0
        while (y < sr.height) {
            var x = 0
            while (x < sr.width) {
                val c = sr.pixels[y * sr.width + x]
                if (kotlin.math.abs((c shr 16 and 0xFF) - (target shr 16 and 0xFF)) <= tol &&
                    kotlin.math.abs((c shr 8 and 0xFF) - (target shr 8 and 0xFF)) <= tol &&
                    kotlin.math.abs((c and 0xFF) - (target and 0xFF)) <= tol) {
                    sx += x; sy += y; n++
                }
                x += step
            }
            y += step
        }
        return if (n == 0) null else Pt((sx / n).toFloat(), (sy / n).toFloat())
    }
}
