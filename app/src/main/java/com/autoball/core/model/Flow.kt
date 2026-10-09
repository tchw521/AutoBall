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

    /**
     * 全局监听钩子（v3 listen）：9 个时机，每个时机可挂**多个**动作。
     *
     * key 取值见 [ListenerDialog.Stage.name]：
     * sb / lt / br / ba / ae / ok / er / le / se。
     * 用于「开始前截图留证」「每轮开头复位」「失败后上报」等场景。
     *
     * 与 `Action.listeners`（动作级、单动作、7 阶段）不冲突：
     * 前者是脚本生命周期钩子，后者是单个动作的前后钩子。
     */
    var hooks: MutableMap<String, MutableList<Action>> = LinkedHashMap()

    /** 是否「有动作失败立即暂停」 */
    var failStop: Boolean = true
    /** 失败自动重试一次 */
    var retryOnce: Boolean = false
    /** 动作间默认等待（毫秒），0 表示不额外等待 */
    var defaultWaitMs: Long = 0L
    /** 等待单位：ms / s / min（仅用于编辑页显示，存储统一为毫秒） */
    var waitUnit: String = "ms"

    /**
     * 手势矩阵变形（v3 morph）：让注入更接近真人。
     *
     * 格式为空串表示不做变换；否则为 css matrix(a,b,c,d,e,f) 六个数值，
     * 其中 e/f 允许写成 `±6` 表示在该区间内随机抖动。
     * 另支持 `· 时长±20%` 后缀，表示同时随机化手势时长。
     */
    var morph: String = ""

    /**
     * 深拷贝。配合 [Script.copy] 使用——编辑页要持有自己的副本，
     * 否则原地修改会污染 ScriptStore 的缓存（连带让快照失效）。
     *
     * actions / vars / hooks 里的元素都要逐个拷：
     * Action 与 VarDef 都是可变对象，浅拷贝会共享引用。
     */
    fun copy(): Flow {
        val c = Flow()
        c.version = version
        c.id = id
        c.name = name
        c.actions = ArrayList(actions.map { it.copy(newId = false) })
        c.vars = ArrayList(vars.map { it.copy() })
        // DisplaySignature 是**可变** data class，直接赋值会与原对象共享引用：
        // 改副本的显示签名会连带改到缓存里的原件（第 2 类失效）。
        c.display = display?.copy()
        c.loop = loop
        c.loopCount = loopCount
        c.speed = speed
        c.hooks = LinkedHashMap(hooks.mapValues { (_, v) -> ArrayList(v.map { it.copy(newId = false) }) })
        c.failStop = failStop
        c.retryOnce = retryOnce
        c.defaultWaitMs = defaultWaitMs
        c.waitUnit = waitUnit
        c.morph = morph
        return c
    }

    /** 已挂载监听动作的时机数与动作总数，用于编辑页摘要 */
    fun hookSummary(): Pair<Int, Int> {
        var stages = 0
        var n = 0
        hooks.forEach { (_, v) -> if (v.isNotEmpty()) { stages++; n += v.size } }
        return stages to n
    }

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
        if (hooks.isNotEmpty()) {
            val ho = JSONObject()
            hooks.forEach { (k, v) ->
                if (v.isEmpty()) return@forEach
                val arr = JSONArray()
                v.forEach { arr.put(it.toJson()) }
                ho.put(k, arr)
            }
            if (ho.length() > 0) put("hooks", ho)
        }
        put("failStop", failStop)
        put("retryOnce", retryOnce)
        put("defaultWaitMs", defaultWaitMs)
        put("waitUnit", waitUnit)
        if (morph.isNotEmpty()) put("morph", morph)
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
            val ho = o.optJSONObject("hooks")
            if (ho != null) {
                val keys = ho.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val arr = ho.optJSONArray(k) ?: continue
                    val list = ArrayList<Action>()
                    for (i in 0 until arr.length()) {
                        val ao = arr.optJSONObject(i) ?: continue
                        list.add(Action.fromJson(ao))
                    }
                    f.hooks[k] = list
                }
            }
            f.failStop = o.optBoolean("failStop", true)
            f.retryOnce = o.optBoolean("retryOnce", false)
            f.defaultWaitMs = o.optLong("defaultWaitMs", 0L)
            f.waitUnit = o.optStringOrNull("waitUnit") ?: "ms"
            f.morph = o.optStringOrNull("morph") ?: ""
            return f
        }
    }
}
