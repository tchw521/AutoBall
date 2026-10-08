package com.autoball.core.store

import com.autoball.App
import com.autoball.core.model.Script
import org.json.JSONArray

/**
 * 标签仓库（R-102）。
 *
 * 与分组的区别：**分组单归属，标签多归属**。
 * 脚本到 40+ 个后，单一分组栏不够用——一个脚本往往同时属于
 * 「常用」「微信相关」「待调试」，单归属只能选其一。
 *
 * 标签本身只存名字与颜色索引，归属关系存在 Script.tags 里，
 * 避免维护两份映射导致不同步。
 */
object TagStore {

    private const val PREF = "autoball_tags"

    private fun pref() = App.get().getSharedPreferences(PREF, 0)

    /** 全部标签名（按添加顺序） */
    fun all(): List<String> {
        val raw = pref().getString("items", "[]") ?: "[]"
        return runCatching {
            val a = JSONArray(raw)
            val out = ArrayList<String>()
            for (i in 0 until a.length()) out.add(a.optString(i))
            out
        }.getOrDefault(emptyList())
    }

    fun add(name: String) {
        val n = name.trim()
        if (n.isEmpty() || all().contains(n)) return
        val list = all().toMutableList()
        list.add(n)
        save(list)
    }

    fun rename(old: String, new: String) {
        val n = new.trim()
        if (n.isEmpty()) return
        val list = all().toMutableList()
        val i = list.indexOf(old)
        if (i < 0) return
        list[i] = n
        save(list)
        // 同步改所有脚本上的引用，否则旧标签变成孤儿
        AB_all().forEach { s ->
            val j = s.tags.indexOf(old)
            if (j >= 0) { s.tags[j] = n; AB_store().save(s) }
        }
    }

    fun delete(name: String) {
        save(all().filter { it != name })
        AB_all().forEach { s ->
            if (s.tags.remove(name)) AB_store().save(s)
        }
    }

    private fun save(list: List<String>) {
        pref().edit().putString("items", JSONArray().apply {
            list.forEach { put(it) }
        }.toString()).apply()
    }

    /** 打了该标签的脚本 */
    fun scriptsOf(name: String): List<Script> = AB_all().filter { it.tags.contains(name) }

    private fun AB_all(): List<Script> = com.autoball.AB.store.all()
    private fun AB_store(): com.autoball.core.store.ScriptStore = com.autoball.AB.store
}
