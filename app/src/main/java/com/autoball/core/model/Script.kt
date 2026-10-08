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

    /**
     * 标签（与 groupId 的区别：分组是**单归属**，标签是**多归属**）。
     *
     * 脚本到 40+ 个后，单一分组栏不够用——一个脚本往往同时属于
     * 「常用」「微信相关」「待调试」，单归属只能选其一。
     */
    var tags: MutableList<String> = ArrayList()

    var enabled: Boolean = true
    var isDefault: Boolean = false

    // ---------- 自动精灵：定时触发 / 循环运行 ----------
    /** 定时触发开关 */
    var scheduleEnabled: Boolean = false
    /** 触发时刻：一天内的分钟数（0–1439），如 8*60+30 = 08:30 */
    var scheduleMinute: Int = -1
    /** 每周重复位掩码：bit0=周日 … bit6=周六；0 表示每天 */
    var scheduleDays: Int = 0
    /** 重启手机后也触发一次 */
    var scheduleOnBoot: Boolean = false
    /** 上次触发的日期（yyyyMMdd），避免同一天重复触发 */
    var lastFiredDay: Int = 0

    /** 循环运行：0 表示不循环（跑一次），n>0 表示循环 n 次，-1 表示无限 */
    var loopCount: Int = 0
    /** 每次循环之间的间隔（ms） */
    var loopIntervalMs: Long = 0L

    /** 分享码加密口令；空表示不加密 */
    var sharePass: String = ""

    // ---------- 自动精灵：消息触发 ----------
    /** 消息触发开关（需系统授予通知使用权） */
    var notifyEnabled: Boolean = false
    /** 触发来源包名；空表示任意应用 */
    var notifyPkg: String = ""
    /** 关键词；空表示任意消息 */
    var notifyKeyword: String = ""
    var createdAt: Long = System.currentTimeMillis()
    var updatedAt: Long = createdAt
    var runCount: Int = 0

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("kind", kind.name)
        put("groupId", groupId)
        if (tags.isNotEmpty()) put("tags", org.json.JSONArray().apply { tags.forEach { put(it) } })
        targetPkg?.let { put("targetPkg", it) }
        put("slot", slot.name)
        put("jsCode", jsCode)
        flow?.let { put("flow", it.toJson()) }
        put("enabled", enabled)
        put("isDefault", isDefault)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("runCount", runCount)
        put("scheduleEnabled", scheduleEnabled)
        put("scheduleMinute", scheduleMinute)
        put("scheduleDays", scheduleDays)
        put("scheduleOnBoot", scheduleOnBoot)
        put("loopCount", loopCount)
        put("loopIntervalMs", loopIntervalMs)
        if (sharePass.isNotEmpty()) put("sharePass", sharePass)
        put("notifyEnabled", notifyEnabled)
        put("notifyPkg", notifyPkg)
        put("notifyKeyword", notifyKeyword)
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
            s.tags = ArrayList()
            o.optJSONArray("tags")?.let { a ->
                for (i in 0 until a.length()) a.optString(i)?.let { s.tags.add(it) }
            }
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
            s.scheduleEnabled = o.optBoolean("scheduleEnabled", false)
            s.scheduleMinute = o.optInt("scheduleMinute", -1)
            s.scheduleDays = o.optInt("scheduleDays", 0)
            s.scheduleOnBoot = o.optBoolean("scheduleOnBoot", false)
            s.loopCount = o.optInt("loopCount", 0)
            s.loopIntervalMs = o.optLong("loopIntervalMs", 0L)
            s.sharePass = o.optStringOrNull("sharePass") ?: ""
            s.notifyEnabled = o.optBoolean("notifyEnabled", false)
            s.notifyPkg = o.optStringOrNull("notifyPkg") ?: ""
            s.notifyKeyword = o.optStringOrNull("notifyKeyword") ?: ""
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
