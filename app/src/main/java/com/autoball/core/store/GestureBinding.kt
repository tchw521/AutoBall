package com.autoball.core.store

import com.autoball.AB
import com.autoball.App
import com.autoball.core.model.BallSlot
import com.autoball.core.model.Script
import org.json.JSONArray

/**
 * 悬浮球手势绑定（R-106）。
 *
 * 原实现：一个手势只绑一个脚本（`Script.slot`），取第一个匹配项执行。
 * 问题：想让「双击」依次跑 A 再跑 B 就做不到——只能把 A、B 合成一个大脚本，
 * 但那样 A 就没法单独复用了。
 *
 * 现支持一个手势按顺序绑多个脚本，触发时**串行依次执行**。
 *
 * 两点兼容性处理：
 * 1. 保留 `Script.slot` 不动——老版本数据照旧可读，作为绑定的默认值。
 * 2. 绑定里存**脚本 id** 而非脚本对象，避免持有两份数据导致不同步；
 *    脚本被删时自动清理对应 id，不会留下指向空脚本的绑定。
 */
object GestureBinding {

    private const val PREF = "autoball_gesture"

    private fun pref() = App.get().getSharedPreferences(PREF, 0)

    /** 某手势绑定的脚本 id（按执行顺序） */
    fun ids(slot: BallSlot): List<String> {
        val raw = pref().getString(slot.name, null)
        if (raw != null) return parse(raw)
        // 未显式配置时回落到旧字段：保持老用户升级后行为不变
        return AB.store.all().filter { it.slot == slot && it.enabled }.map { it.id }
    }

    fun scripts(slot: BallSlot): List<Script> {
        val byId = AB.store.all().associateBy { it.id }
        return ids(slot).mapNotNull { byId[it] }.filter { it.enabled }
    }

    fun set(slot: BallSlot, ids: List<String>) {
        pref().edit().putString(slot.name, JSONArray().apply {
            ids.forEach { put(it) }
        }.toString()).apply()
        // 同步 Script.slot：让其它读旧字段的地方（如脚本卡片上的绑定标记）
        // 也能反映出"第一个"绑定，不至于显示成未绑定
        val all = AB.store.all()
        all.forEach { sc ->
            if (sc.slot == slot && sc.id !in ids) sc.slot = BallSlot.NONE
        }
        ids.firstOrNull()?.let { first ->
            all.firstOrNull { it.id == first }?.let { it.slot = slot }
        }
        all.forEach { AB.store.save(it) }
    }

    /** 脚本被删时清理引用 */
    fun prune(removedIds: Collection<String>) {
        BallSlot.values().filter { it != BallSlot.NONE }.forEach { slot ->
            val cur = ids(slot).filter { it !in removedIds }
            if (cur.size != ids(slot).size) set(slot, cur)
        }
    }

    /** 显示用：手势 → 脚本名列表 */
    fun summary(slot: BallSlot): String {
        val list = scripts(slot)
        return when {
            list.isEmpty() -> "未绑定"
            list.size == 1 -> list[0].name
            else -> "${list[0].name} 等 ${list.size} 个"
        }
    }

    private fun parse(raw: String): List<String> = runCatching {
        val a = JSONArray(raw)
        val out = ArrayList<String>()
        for (i in 0 until a.length()) out.add(a.optString(i))
        out
    }.getOrDefault(emptyList())

}
