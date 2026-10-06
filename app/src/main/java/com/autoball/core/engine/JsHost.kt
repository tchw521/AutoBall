package com.autoball.core.engine

import com.autoball.core.RunControl
import com.autoball.core.backend.BackendRouter
import com.autoball.core.backend.ExecContext
import com.autoball.core.backend.ScreenResult
import com.autoball.core.log.RunLog
import com.autoball.core.model.*
import com.autoball.core.util.Condition
import org.json.JSONArray
import org.json.JSONObject

/**
 * JS 宿主 API：click / longClick / swipe / sleep / globalAction / log / toast /
 * screenshot / findNode / setVar / getVar / stop / press / key / input / openApp
 *
 * 引擎通过"方法名 + JSON 参数数组"回调进来，返回 JSON 结果串。
 * 这样 QuickJS 与 Rhino 共用同一份实现，API 表面完全一致。
 */
class JsHost(
    private val router: BackendRouter,
    private val ctx: ExecContext,
    private val control: RunControl,
    private val log: RunLog
) {

    companion object {
        /** sleep 上限：避免 sleep(MAX_INT) 让脚本永不响应停止 */
        const val MAX_SLEEP_MS = 300_000L
    }

    /** 返回 JSON：{"ok":bool,"value":<任意>,"error":string?} */
    fun call(name: String, argsJson: String): String {
        return try {
            val args = JSONArray(argsJson)
            val v = when (name) {
                "click" -> {
                    val a = tapAction(args.optDouble(0).toFloat(), args.optDouble(1).toFloat(), 80)
                    exec(a)
                }
                "press" -> {
                    val a = tapAction(args.optDouble(0).toFloat(), args.optDouble(1).toFloat(),
                        args.optLong(2, 80).coerceAtLeast(10))
                    exec(a)
                }
                "longClick" -> {
                    val a = tapAction(args.optDouble(0).toFloat(), args.optDouble(1).toFloat(),
                        args.optLong(2, 600).coerceAtLeast(350))
                    exec(a)
                }
                "swipe" -> {
                    val a = Action().apply {
                        type = ActionType.SWIPE
                        x = args.optDouble(0).toFloat(); y = args.optDouble(1).toFloat()
                        x2 = args.optDouble(2).toFloat(); y2 = args.optDouble(3).toFloat()
                        durationMs = args.optLong(4, 300).coerceAtLeast(30)
                    }
                    exec(a)
                }
                "sleep" -> {
                    val ms = args.optLong(0, 0).coerceIn(0, MAX_SLEEP_MS)
                    val ok = control.sleep(ms)
                    if (ok) true else throw CancelException("已停止")
                }
                "globalAction" -> {
                    val a = Action().apply {
                        type = ActionType.KEY
                        keyCode = when (args.optString(0, "").lowercase()) {
                            "back" -> android.view.KeyEvent.KEYCODE_BACK
                            "home" -> android.view.KeyEvent.KEYCODE_HOME
                            "recents", "recent" -> android.view.KeyEvent.KEYCODE_APP_SWITCH
                            "notifications" -> android.view.KeyEvent.KEYCODE_NOTIFICATION
                            else -> args.optInt(0, android.view.KeyEvent.KEYCODE_BACK)
                        }
                    }
                    exec(a)
                }
                "key" -> exec(Action().apply {
                    type = ActionType.KEY
                    keyCode = args.optInt(0, android.view.KeyEvent.KEYCODE_BACK)
                })
                "input" -> exec(Action().apply {
                    type = ActionType.INPUT_TEXT
                    text = args.optString(0, "")
                })
                "openApp" -> exec(Action().apply {
                    type = ActionType.OPEN_APP
                    pkg = args.optString(0, "")
                })
                "toast" -> {
                    val a = Action().apply { type = ActionType.TOAST; text = args.optString(0, "") }
                    exec(a)
                }
                "screenshot" -> {
                    val sr = router.screenshot(ctx)
                    when (sr) {
                        is ScreenResult.Ok -> {
                            log.info(ctx.runId, "截图 ${sr.width}x${sr.height} (${sr.backend.label})")
                            JSONObject().put("w", sr.width).put("h", sr.height).put("backend", sr.backend.name)
                        }
                        is ScreenResult.Unavailable -> throw CancelException(sr.reason)
                    }
                }
                "findNode" -> {
                    val a = Action().apply {
                        type = ActionType.CLICK_NODE
                        nodeSpec = NodeSpec(text = args.optString(0, ""), clickableOnly = true)
                    }
                    val node = resolveNodePoint(a)
                    if (node == null) null else {
                        JSONObject().put("x", node.x.toDouble()).put("y", node.y.toDouble())
                    }
                }
                "clickText" -> {
                    val a = Action().apply {
                        type = ActionType.CLICK_TEXT
                        text = args.optString(0, "")
                    }
                    exec(a)
                }
                "setVar" -> {
                    ctx.setVar(args.optString(0, ""), args.optString(1, ""))
                    true
                }
                "getVar" -> ctx.getVar(args.optString(0, "")) ?: ""
                "log" -> {
                    val msg = args.optString(0, "")
                    log.info(ctx.runId, "JS: $msg")
                    true
                }
                "stop" -> { control.cancel(); true }
                "isCanceled" -> control.canceled
                "backend" -> router.status().firstOrNull { it.second.name == "READY" }?.first?.name ?: "NONE"
                else -> throw CancelException("未知 API: $name")
            }
            JSONObject().put("ok", true).put("value", v ?: JSONObject.NULL).toString()
        } catch (ce: CancelException) {
            JSONObject().put("ok", false).put("error", ce.message).toString()
        } catch (e: Throwable) {
            JSONObject().put("ok", false).put("error", e.message ?: "宿主异常").toString()
        }
    }

    private fun tapAction(x: Float, y: Float, dur: Long) = Action().apply {
        type = ActionType.CLICK
        this.x = x; this.y = y
        durationMs = dur
    }

    private fun exec(a: Action): Boolean {
        val r = router.execute(a, ctx)
        log.add(ctx.runId,
            if (r.ok) RunLog.Level.OK else RunLog.Level.ERROR,
            a.type.label, r.message, null, r.backend.label, r.latencyMs)
        if (!r.ok) throw CancelException(r.cause ?: "执行失败")
        return true
    }

    /** 节点定位是无障碍独有能力；Shizuku 后端下显式返回 null，不静默伪造 */
    private fun resolveNodePoint(a: Action): Pt? {
        if (!router.accessibility.isAvailable()) {
            log.warn(ctx.runId, "findNode 需要无障碍服务的节点能力，Shizuku 后端不支持")
            return null
        }
        return router.accessibility.nodeCenter(a)
    }

    /** 供 RUN_JS 动作与条件表达式复用 */
    fun evalCondition(expr: String): Boolean = Condition.eval(expr, ctx.vars)

    class CancelException(msg: String) : RuntimeException(msg)
}
