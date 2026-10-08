package com.autoball.core.store

import com.autoball.App
import org.json.JSONArray
import org.json.JSONObject

/**
 * 悬浮窗自定义按键（R-118）。
 *
 * 背景：此前只能从 6 套**固定皮肤**里选，按键种类与排列都是写死的
 * （`FloatPanelView.apply` 的 when 分支）。用户想要"只留三个我常用的键"
 * 或"把某个脚本放到第一格"都做不到。
 *
 * 现在按键是可编辑的列表：每个键有自己的动作、文案与颜色，可增删排序。
 * 皮肤退化为"预设模板"——选皮肤会把该模板的按键写进自定义列表，
 * 用户再自行增删。这样固定皮肤与自定义布局共用一套数据，不用维护两份。
 */
object PanelKeyStore {

    /** 一个按键 */
    class Key(
        /** 动作名，取自 [ACTION_IDS] */
        var action: String = "SLOT_A",
        /** 覆盖显示文案；空则用动作默认文案 */
        var label: String = "",
        /** 绑定的脚本 id（仅 action=SCRIPT 时有效） */
        var scriptId: String = "",
        /** 颜色索引，对应 Theme.G 调色板（0–6） */
        var color: Int = 0
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("a", action); put("l", label)
            put("s", scriptId); put("c", color)
        }

        companion object {
            fun fromJson(o: JSONObject): Key = Key(
                action = o.optString("a", "SLOT_A"),
                label = o.optString("l", ""),
                scriptId = o.optString("s", ""),
                color = o.optInt("c", 0).coerceIn(0, 6))
        }
    }

    /**
     * 可选动作：与 `FloatPanelView.SlotAction` 一一对应，外加「绑定脚本」。
     *
     * 用 String id 而非直接引用 UI 枚举——store 层不依赖 UI 类型，
     * 悬浮窗在另一个进程/窗口里重建时不会因类型不匹配而失效。
     */
    val ACTION_IDS = listOf(
        "SLOT_A" to "脚本A", "SLOT_B" to "脚本B", "SLOT_C" to "脚本C",
        "BACK" to "返回", "HOME" to "主页", "RECENTS" to "最近任务",
        "SHOT" to "截图", "SCRIPT" to "绑定脚本"
    )

    private const val PREF = "autoball_panel_keys"
    private const val K_ITEMS = "items"
    private const val K_ENABLED = "enabled"

    private fun pref() = App.get().getSharedPreferences(PREF, 0)

    /** 是否启用自定义布局；未启用时沿用皮肤预设 */
    fun enabled(): Boolean = pref().getBoolean(K_ENABLED, false)

    fun setEnabled(on: Boolean) = pref().edit().putBoolean(K_ENABLED, on).apply()

    fun all(): List<Key> {
        val raw = pref().getString(K_ITEMS, null)
        if (raw.isNullOrBlank()) return defaultKeys()
        return runCatching {
            val a = JSONArray(raw)
            val out = ArrayList<Key>()
            for (i in 0 until a.length()) out.add(Key.fromJson(a.getJSONObject(i)))
            out
        }.getOrDefault(defaultKeys())
    }

    fun save(list: List<Key>) {
        pref().edit().putString(K_ITEMS, JSONArray().apply {
            list.forEach { put(it.toJson()) }
        }.toString()).apply()
    }

    /**
     * 默认按键：与「默认控制窗」皮肤一致。
     *
     * 首次进入自定义编辑时给一份可用的起点，而不是空列表——
     * 空列表会让悬浮窗变成只有底部操作条，用户以为坏了。
     */
    fun defaultKeys(): List<Key> = listOf(
        Key("SLOT_A", color = 0), Key("SLOT_B", color = 1), Key("SLOT_C", color = 2),
        Key("BACK", color = 3), Key("HOME", color = 5),
        Key("RECENTS", color = 6), Key("SHOT", color = 4)
    )

    /** 上限，防止加太多把悬浮窗撑满屏幕 */
    const val MAX = 12

    fun labelOf(k: Key): String {
        if (k.label.isNotBlank()) return k.label
        if (k.action == "SCRIPT") {
            val n = runCatching { com.autoball.AB.store.get(k.scriptId)?.name }.getOrNull()
            return n ?: "（脚本已删除）"
        }
        return ACTION_IDS.firstOrNull { it.first == k.action }?.second ?: k.action
    }
}
