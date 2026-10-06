package com.autoball.core.model

import org.json.JSONArray
import org.json.JSONObject

/** 变量声明（SET_VAR 与条件表达式共享） */
data class VarDef(
    var name: String = "",
    var type: String = "string", // string / int / float / bool
    var value: String = ""
) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("type", type).put("value", value)
    companion object {
        fun fromJson(o: JSONObject): VarDef = VarDef(
            o.optStringOrNull("name") ?: "",
            o.optStringOrNull("type") ?: "string",
            o.optStringOrNull("value") ?: ""
        )
    }
}

/** 显示签名：录制时的屏幕状态。用于 v0.8 归一化与转屏保护 */
data class DisplaySignature(
    var width: Int = 0,
    var height: Int = 0,
    var density: Float = 1f,
    var rotation: Int = 0
) {
    fun toJson(): JSONObject = JSONObject()
        .put("w", width).put("h", height)
        .put("d", density.toDouble()).put("r", rotation)

    fun matches(curW: Int, curH: Int, curRot: Int): Boolean {
        if (width == 0 || height == 0) return true
        return width == curW && height == curH && rotation == curRot
    }

    companion object {
        fun fromJson(o: JSONObject?): DisplaySignature? {
            if (o == null) return null
            return DisplaySignature(
                o.optInt("w", 0), o.optInt("h", 0),
                o.optDouble("d", 1.0).toFloat(), o.optInt("r", 0)
            )
        }
    }
}

/**
 * 动作流：录制与逐步添加的落地格式，不经 JS 引擎直接执行。
 */
class Flow {
    var version: Int = 1
    var id: String = ""
    var name: String = "未命名流程"
    var actions: MutableList<Action> = ArrayList()
    var vars: MutableList<VarDef> = ArrayList()
    var display: DisplaySignature? = null
    var loop: Boolean = false
    var loopCount: Int = 0          // 0 = 无限（受全局超时约束）
    var speed: Float = 1f           // 倍速

    /** 静态能力需求：用于运行前告知用户"当前只有一种授权时哪些动作会降级" */
    fun requiredCaps(): Set<Cap> {
        val s = HashSet<Cap>()
        collectCaps(actions, s)
        return s
    }

    private fun collectCaps(list: List<Action>, out: MutableSet<Cap>) {
        for (a in list) {
            if (!a.enabled) continue
            out.addAll(a.type.required)
            if (a.subActions.isNotEmpty()) collectCaps(a.subActions, out)
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", version)
        put("id", id)
        put("name", name)
        val arr = JSONArray()
        actions.forEach { arr.put(it.toJson()) }
        put("actions", arr)
        if (vars.isNotEmpty()) {
            val vs = JSONArray()
            vars.forEach { vs.put(it.toJson()) }
            put("vars", vs)
        }
        display?.let { put("display", it.toJson()) }
        put("loop", loop)
        put("loopCount", loopCount)
        put("speed", speed.toDouble())
    }

    companion object {
        fun newId(): String = "flow_" + System.nanoTime().toString(36)

        fun fromJson(o: JSONObject): Flow {
            val f = Flow()
            f.version = o.optInt("version", 1)
            f.id = o.optStringOrNull("id") ?: newId()
            f.name = o.optStringOrNull("name") ?: "未命名流程"
            f.actions = Action.listFromJson(o.optJSONArray("actions"))
            val vars = o.optJSONArray("vars")
            if (vars != null) {
                for (i in 0 until vars.length()) {
                    val vo = vars.optJSONObject(i) ?: continue
                    f.vars.add(VarDef.fromJson(vo))
                }
            }
            f.display = DisplaySignature.fromJson(o.optJSONObject("display"))
            f.loop = o.optBoolean("loop", false)
            f.loopCount = o.optInt("loopCount", 0)
            f.speed = o.optDouble("speed", 1.0).toFloat()
            return f
        }
    }
}
