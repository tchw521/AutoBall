package com.autoball.core.model

import org.json.JSONObject

/** 脚本类型徽标：JS 手写 / 录制（动作流） */
enum class ScriptKind(val label: String) { JS("JS"), FLOW("录"), MIXED("混合") }

/** 悬浮球手势槽位 */
enum class BallSlot(val label: String) {
    NONE("未绑定"),
    SINGLE("单击"),
    DOUBLE("双击"),
    TRIPLE("三击"),
    LONG("长按")
}

class Script {
    var id: String = ""
    var name: String = "未命名脚本"
    var kind: ScriptKind = ScriptKind.FLOW

    // 需求「未完成」项：分组 / 目标应用 / 手势槽位
    var groupId: String = "default"
    var targetPkg: String? = null
    var slot: BallSlot = BallSlot.NONE

    var jsCode: String = ""
    var flow: Flow? = null

    var enabled: Boolean = true
    var isDefault: Boolean = false
    var createdAt: Long = System.currentTimeMillis()
    var updatedAt: Long = createdAt
    var runCount: Int = 0

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("kind", kind.name)
        put("groupId", groupId)
        targetPkg?.let { put("targetPkg", it) }
        put("slot", slot.name)
        put("jsCode", jsCode)
        flow?.let { put("flow", it.toJson()) }
        put("enabled", enabled)
        put("isDefault", isDefault)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("runCount", runCount)
    }

    companion object {
        fun newId(): String = "s" + System.nanoTime().toString(36)

        fun fromJson(o: JSONObject): Script {
            val s = Script()
            s.id = o.optStringOrNull("id") ?: newId()
            s.name = o.optStringOrNull("name") ?: "未命名脚本"
            s.kind = try { ScriptKind.valueOf(o.optStringOrNull("kind") ?: "FLOW") }
                     catch (e: Exception) { ScriptKind.FLOW }
            s.groupId = o.optStringOrNull("groupId") ?: "default"
            s.targetPkg = o.optStringOrNull("targetPkg")
            s.slot = try { BallSlot.valueOf(o.optStringOrNull("slot") ?: "NONE") }
                     catch (e: Exception) { BallSlot.NONE }
            s.jsCode = o.optStringOrNull("jsCode") ?: ""
            val fo = o.optJSONObject("flow")
            if (fo != null) s.flow = Flow.fromJson(fo)
            s.enabled = o.optBoolean("enabled", true)
            s.isDefault = o.optBoolean("isDefault", false)
            s.createdAt = o.optLong("createdAt", System.currentTimeMillis())
            s.updatedAt = o.optLong("updatedAt", s.createdAt)
            s.runCount = o.optInt("runCount", 0)
            return s
        }

        fun blank(name: String): Script = Script().apply {
            id = newId(); this.name = name
            kind = ScriptKind.FLOW
            flow = Flow().apply { id = Flow.newId(); this.name = name }
        }
    }
}

/** 分组：自定义分组 + 按应用分组并存 */
class Group {
    var id: String = ""
    var name: String = ""
    var kind: String = "custom"   // custom | app
    var pkg: String? = null
    var colorIndex: Int = 0       // 彩色气泡色板索引，零图标资源

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("kind", kind)
        pkg?.let { put("pkg", it) }
        put("colorIndex", colorIndex)
    }

    companion object {
        fun fromJson(o: JSONObject): Group = Group().apply {
            id = o.optStringOrNull("id") ?: "default"
            name = o.optStringOrNull("name") ?: "默认分组"
            kind = o.optStringOrNull("kind") ?: "custom"
            pkg = o.optStringOrNull("pkg")
            colorIndex = o.optInt("colorIndex", 0)
        }
    }
}
