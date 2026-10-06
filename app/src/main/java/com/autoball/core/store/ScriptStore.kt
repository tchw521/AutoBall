package com.autoball.core.store

import android.content.Context
import com.autoball.core.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本地存储：脚本、分组、设置。全部为 org.json + 私有目录文件，无任何第三方依赖。
 */
class ScriptStore(private val ctx: Context) {

    private val dir: File = File(ctx.filesDir, "autoball").apply { mkdirs() }
    private val scriptFile = File(dir, "scripts.json")
    private val groupFile = File(dir, "groups.json")
    private val pref = ctx.getSharedPreferences("autoball_settings", Context.MODE_PRIVATE)
    private val lock = Any()

    // ---------- 脚本 ----------

    fun all(): MutableList<Script> = synchronized(lock) {
        val root = read(scriptFile)
        val arr = root.optJSONArray("scripts") ?: return@synchronized ArrayList<Script>()
        val out = ArrayList<Script>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Script.fromJson(o))
        }
        out
    }

    fun get(id: String): Script? = all().firstOrNull { it.id == id }

    fun save(s: Script) = synchronized(lock) {
        val list = all()
        val idx = list.indexOfFirst { it.id == s.id }
        s.updatedAt = System.currentTimeMillis()
        if (idx >= 0) list[idx] = s else list.add(s)
        writeAll(list)
    }

    fun delete(ids: Collection<String>) = synchronized(lock) {
        val list = all().filterNot { it.id in ids }.toMutableList()
        writeAll(list)
    }

    fun move(ids: Collection<String>, groupId: String) = synchronized(lock) {
        val list = all()
        list.forEach { if (it.id in ids) it.groupId = groupId }
        writeAll(list)
    }

    private fun writeAll(list: List<Script>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        write(scriptFile, JSONObject().put("version", 1).put("scripts", arr))
        notifyChange()
    }

    // ---------- 分组 ----------

    fun groups(): MutableList<Group> = synchronized(lock) {
        val root = read(groupFile)
        val arr = root.optJSONArray("groups")
        val out = ArrayList<Group>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(Group.fromJson(o))
            }
        }
        if (out.isEmpty()) {
            out.add(Group().apply { id = "default"; name = "默认分组"; kind = "custom" })
        }
        out
    }

    fun saveGroup(g: Group) = synchronized(lock) {
        val list = groups()
        val idx = list.indexOfFirst { it.id == g.id }
        if (idx >= 0) list[idx] = g else list.add(g)
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        write(groupFile, JSONObject().put("groups", arr))
    }

    fun deleteGroup(id: String) = synchronized(lock) {
        if (id == "default") return@synchronized
        val list = groups().filterNot { it.id == id }.toMutableList()
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        write(groupFile, JSONObject().put("groups", arr))
        move(all().filter { it.groupId == id }.map { it.id }, "default")
    }

    // ---------- 设置 ----------

    fun getString(key: String, def: String): String = pref.getString(key, def) ?: def
    fun putString(key: String, v: String) { pref.edit().putString(key, v).apply() }
    fun getInt(key: String, def: Int): Int = pref.getInt(key, def)
    fun putInt(key: String, v: Int) { pref.edit().putInt(key, v).apply() }
    fun getBool(key: String, def: Boolean): Boolean = pref.getBoolean(key, def)
    fun putBool(key: String, v: Boolean) { pref.edit().putBoolean(key, v).apply() }
    fun getFloat(key: String, def: Float): Float = pref.getFloat(key, def)
    fun putFloat(key: String, v: Float) { pref.edit().putFloat(key, v).apply() }

    // ---------- 变更通知 ----------

    var onChange: (() -> Unit)? = null
    private fun notifyChange() { onChange?.invoke() }

    // ---------- 文件读写 ----------

    private fun read(f: File): JSONObject {
        if (!f.exists()) return JSONObject()
        return try {
            JSONObject(f.readText())
        } catch (e: Exception) { JSONObject() }
    }

    private fun write(f: File, o: JSONObject) {
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (e: Exception) { /* 存储失败不应崩溃 */ }
    }
}
