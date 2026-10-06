package com.autoball.core.model

import org.json.JSONArray
import org.json.JSONObject

/** 二维点（虚拟像素空间，与录制时的显示签名绑定） */
data class Pt(val x: Float, val y: Float) {
    fun toJson(): JSONObject = JSONObject().put("x", x.toDouble()).put("y", y.toDouble())
    companion object {

        fun fromJson(o: JSONObject?): Pt? =
            if (o == null) null else Pt(o.optDouble("x").toFloat(), o.optDouble("y").toFloat())
        fun listFromJson(arr: JSONArray?): List<Pt> {
            val out = ArrayList<Pt>()
            if (arr == null) return out
            for (i in 0 until arr.length()) {
                val p = fromJson(arr.optJSONObject(i)) ?: continue
                out.add(p)
            }
            return out
        }
    }
}

/** 控件节点查找条件（CLICK_NODE / CLICK_TEXT 使用） */
data class NodeSpec(
    var text: String? = null,
    var id: String? = null,
    var desc: String? = null,
    var className: String? = null,
    var pkg: String? = null,
    var index: Int = 0,
    var clickableOnly: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        text?.let { put("text", it) }
        id?.let { put("id", it) }
        desc?.let { put("desc", it) }
        className?.let { put("className", it) }
        pkg?.let { put("pkg", it) }
        put("index", index)
        put("clickableOnly", clickableOnly)
    }

    companion object {
        fun fromJson(o: JSONObject?): NodeSpec? {
            if (o == null) return null
            return NodeSpec(
                text = o.optStringOrNull("text"),
                id = o.optStringOrNull("id"),
                desc = o.optStringOrNull("desc"),
                className = o.optStringOrNull("className"),
                pkg = o.optStringOrNull("pkg"),
                index = o.optInt("index", 0),
                clickableOnly = o.optBoolean("clickableOnly", true)
            )
        }
    }
}

/**
 * 动作：三种来源（逐步添加 / 录制生成 / JS 调用）统一落到这一个对象上。
 *
 * 扩展字段只在解析时按 type 绑定，未知字段进入 unknown 而不是抛异常，
 * 保证旧版本分享码在新版本上不会被拒绝。
 */
class Action {
    var id: String = ""
    var type: ActionType = ActionType.CLICK
    var comment: String? = null
    var enabled: Boolean = true

    // ---- 公共项（需求 2.3）----
    var waitMs: Long = 300          // 运行等待：本动作执行后等待
    var preDelayMs: Long = 0        // 执行前等待
    var repeat: Int = 1             // 重复次数
    var repeatIntervalMs: Long = 0
    var condition: String? = null   // 运行条件：JS 表达式，选填
    var timeoutMs: Long = 10_000

    // ---- 坐标类 ----
    var x: Float = 0f
    var y: Float = 0f
    var x2: Float = 0f
    var y2: Float = 0f
    var durationMs: Long = 80       // 按下时间 / 滑动时长

    // ---- 内容类 ----
    var text: String? = null
    var pkg: String? = null
    var url: String? = null
    var keyCode: Int = 0
    var scriptId: String? = null
    var code: String? = null
    var varName: String? = null
    var varValue: String? = null

    // ---- 结构类 ----
    var controlOp: ControlOp = ControlOp.WAIT
    var gotoId: String? = null
    var subActions: MutableList<Action> = ArrayList()

    // ---- 手势 ----
    var path: MutableList<Pt> = ArrayList()          // 单指手势控制点
    var strokes: MutableList<MutableList<Pt>> = ArrayList() // 多指：每个指针一条路径

    // ---- 查找 ----
    var nodeSpec: NodeSpec? = null
    var colorHex: String? = null
    var colorTolerance: Int = 24
    var imageRef: String? = null
    var matchThreshold: Float = 0.85f

    // ---- 路由 ----
    var backendHint: String? = null  // null=自动择优；"accessibility" / "shizuku"

    /** 监听动作：触发阶段名 -> 该时机要执行的动作（v3 listenDlg，7 个阶段） */
    var listeners: MutableMap<String, Action> = LinkedHashMap()

    var unknown: JSONObject? = null

    fun requiredCaps(): Set<Cap> = type.required
    fun optionalCaps(): Set<Cap> = type.optional

    // ---------- JSON ----------
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        comment?.let { put("comment", it) }
        put("enabled", enabled)
        put("waitMs", waitMs)
        put("preDelayMs", preDelayMs)
        put("repeat", repeat)
        put("repeatIntervalMs", repeatIntervalMs)
        condition?.let { put("condition", it) }
        put("timeoutMs", timeoutMs)
        if (listeners.isNotEmpty()) {
            val lobj = JSONObject()
            listeners.forEach { (k, v) -> lobj.put(k, v.toJson()) }
            put("listeners", lobj)
        }

        put("x", x.toDouble()); put("y", y.toDouble())
        put("x2", x2.toDouble()); put("y2", y2.toDouble())
        put("durationMs", durationMs)

        text?.let { put("text", it) }
        pkg?.let { put("pkg", it) }
        url?.let { put("url", it) }
        put("keyCode", keyCode)
        scriptId?.let { put("scriptId", it) }
        code?.let { put("code", it) }
        varName?.let { put("varName", it) }
        varValue?.let { put("varValue", it) }

        put("controlOp", controlOp.name)
        gotoId?.let { put("gotoId", it) }
        if (subActions.isNotEmpty()) {
            val arr = JSONArray()
            subActions.forEach { arr.put(it.toJson()) }
            put("subActions", arr)
        }

        if (path.isNotEmpty()) {
            val arr = JSONArray()
            path.forEach { arr.put(it.toJson()) }
            put("path", arr)
        }
        if (strokes.isNotEmpty()) {
            val arr = JSONArray()
            strokes.forEach { st ->
                val inner = JSONArray()
                st.forEach { inner.put(it.toJson()) }
                arr.put(inner)
            }
            put("strokes", arr)
        }

        nodeSpec?.let { put("nodeSpec", it.toJson()) }
        colorHex?.let { put("colorHex", it) }
        put("colorTolerance", colorTolerance)
        imageRef?.let { put("imageRef", it) }
        put("matchThreshold", matchThreshold.toDouble())
        backendHint?.let { put("backendHint", it) }
    }

    companion object {
        fun newId(): String = "a" + System.nanoTime().toString(36)

        private fun listenersFromJson(o: JSONObject?): MutableMap<String, Action> {
            val out = LinkedHashMap<String, Action>()
            val lobj = o ?: return out
            val it = lobj.keys()
            while (it.hasNext()) {
                val k = it.next()
                val sub = lobj.optJSONObject(k) ?: continue
                out[k] = fromJson(sub)
            }
            return out
        }

        fun fromJson(o: JSONObject): Action {
            val a = Action()
            a.id = o.optStringOrNull("id") ?: newId()
            a.type = ActionType.fromName(o.optStringOrNull("type")) ?: ActionType.CLICK
            a.comment = o.optStringOrNull("comment")
            a.enabled = o.optBoolean("enabled", true)
            a.waitMs = o.optLong("waitMs", 300)
            a.preDelayMs = o.optLong("preDelayMs", 0)
            a.repeat = o.optInt("repeat", 1).coerceAtLeast(1)
            a.repeatIntervalMs = o.optLong("repeatIntervalMs", 0)
            a.condition = o.optStringOrNull("condition")
            a.timeoutMs = o.optLong("timeoutMs", 10_000)
            a.listeners = listenersFromJson(o.optJSONObject("listeners"))

            a.x = o.optDouble("x").toFloat()
            a.y = o.optDouble("y").toFloat()
            a.x2 = o.optDouble("x2").toFloat()
            a.y2 = o.optDouble("y2").toFloat()
            a.durationMs = o.optLong("durationMs", 80)

            a.text = o.optStringOrNull("text")
            a.pkg = o.optStringOrNull("pkg")
            a.url = o.optStringOrNull("url")
            a.keyCode = o.optInt("keyCode", 0)
            a.scriptId = o.optStringOrNull("scriptId")
            a.code = o.optStringOrNull("code")
            a.varName = o.optStringOrNull("varName")
            a.varValue = o.optStringOrNull("varValue")

            val op = o.optStringOrNull("controlOp")
            if (op != null) {
                a.controlOp = try { ControlOp.valueOf(op) } catch (e: Exception) { ControlOp.WAIT }
            }
            a.gotoId = o.optStringOrNull("gotoId")

            val subs = o.optJSONArray("subActions")
            if (subs != null) {
                for (i in 0 until subs.length()) {
                    val so = subs.optJSONObject(i) ?: continue
                    a.subActions.add(fromJson(so))
                }
            }

            a.path = ArrayList(Pt.listFromJson(o.optJSONArray("path")))
            val strokes = o.optJSONArray("strokes")
            if (strokes != null) {
                for (i in 0 until strokes.length()) {
                    val inner = strokes.optJSONArray(i) ?: continue
                    a.strokes.add(ArrayList(Pt.listFromJson(inner)))
                }
            }

            a.nodeSpec = NodeSpec.fromJson(o.optJSONObject("nodeSpec"))
            a.colorHex = o.optStringOrNull("colorHex")
            a.colorTolerance = o.optInt("colorTolerance", 24)
            a.imageRef = o.optStringOrNull("imageRef")
            a.matchThreshold = o.optDouble("matchThreshold", 0.85).toFloat()
            a.backendHint = o.optStringOrNull("backendHint")
            return a
        }

        fun listFromJson(arr: JSONArray?): MutableList<Action> {
            val out = ArrayList<Action>()
            if (arr == null) return out
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(fromJson(o))
            }
            return out
        }
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key)) return null
    val v = opt(key)
    return if (v == null || v === JSONObject.NULL) null else v.toString()
}
