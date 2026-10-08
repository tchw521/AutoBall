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
    //
    // 性能：`all()` 原实现每次都读文件 + 解析 JSON，而页面 render 与各个
    // 手势绑定查询会在循环里反复调用它（一次 render 可达数十次），
    // I/O 与解析开销被成倍放大。这里加一层内存缓存，写操作后立即重建，
    // 读路径不再碰磁盘。缓存与 `writeAll` 在同一把锁下，保证一致。

    @Volatile
    private var cache: MutableList<Script>? = null

    /** 读缓存副本；调用方可自由修改，不影响缓存 */
    fun all(): MutableList<Script> = synchronized(lock) {
        val c = cache
        if (c != null) {
            val out = ArrayList<Script>(c.size)
            for (s in c) out.add(s)
            return@synchronized out
        }
        val list = loadFromDisk()
        cache = list
        val out = ArrayList<Script>(list.size)
        for (s in list) out.add(s)
        out
    }

    private fun loadFromDisk(): MutableList<Script> {
        val root = read(scriptFile)
        val arr = root.optJSONArray("scripts") ?: return ArrayList()
        val out = ArrayList<Script>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Script.fromJson(o))
        }
        return out
    }

    fun get(id: String): Script? = all().firstOrNull { it.id == id }

    fun save(s: Script) = synchronized(lock) {
        val list = all()
        val idx = list.indexOfFirst { it.id == s.id }
        s.updatedAt = System.currentTimeMillis()
        // 快照必须在覆盖**之前**留——存的是能被回退到的旧版本
        if (idx >= 0) runCatching { SnapshotStore.snapshot(list[idx]) }
        if (idx >= 0) list[idx] = s else list.add(s)
        writeAll(list)
    }

    fun delete(ids: Collection<String>) = synchronized(lock) {
        val list = all().filterNot { it.id in ids }.toMutableList()
        writeAll(list)
        // 脚本删了，它的快照一起清掉，否则会留下一堆永远用不到的孤儿文件
        ids.forEach { runCatching { SnapshotStore.clearFor(it) } }
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
        cache = ArrayList(list)
        notifyChange()
    }

    /** 外部直接改动了文件（如导入）后调用，强制下次读取重新解析 */
    fun invalidate() { synchronized(lock) { cache = null } }

    // ---------- 分组 ----------

    @Volatile
    private var groupCache: MutableList<Group>? = null

    fun groups(): MutableList<Group> = synchronized(lock) {
        val c = groupCache
        if (c != null) {
            val out = ArrayList<Group>(c.size)
            for (g in c) out.add(g)
            return@synchronized out
        }
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
        groupCache = out
        val copy = ArrayList<Group>(out.size)
        for (g in out) copy.add(g)
        copy
    }

    fun saveGroup(g: Group) = synchronized(lock) {
        val list = groups()
        val idx = list.indexOfFirst { it.id == g.id }
        if (idx >= 0) list[idx] = g else list.add(g)
        writeGroups(list)
    }

    private fun writeGroups(list: List<Group>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        write(groupFile, JSONObject().put("groups", arr))
        groupCache = ArrayList(list)
    }

    fun deleteGroup(id: String) = synchronized(lock) {
        if (id == "default") return@synchronized
        val list = groups().filterNot { it.id == id }.toMutableList()
        writeGroups(list)
        // 组内脚本回落到默认分组，避免脚本「消失」
        move(all().filter { it.groupId == id }.map { it.id }, "default")
    }

    fun getGroup(id: String): Group? = groups().firstOrNull { it.id == id }

    // ---------- 设置 ----------

    /**
     * 类型安全的取值。
     *
     * 背景：配置项在版本演进中改过类型（如 `panel_skin` 从皮肤名字符串
     * 改为索引整数），旧版本写入的脏值还留在 SharedPreferences 里，
     * 新版本用 `getInt` 读会直接抛 ClassCastException 崩溃。
     * 这里统一兜底：类型不符时尝试迁移，无法迁移就清掉脏值返回默认。
     */
    fun getString(key: String, def: String): String = try {
        pref.getString(key, def) ?: def
    } catch (e: ClassCastException) { drop(key); def }
    fun putString(key: String, v: String) { pref.edit().putString(key, v).apply() }

    fun getInt(key: String, def: Int): Int = try {
        pref.getInt(key, def)
    } catch (e: ClassCastException) { migrateInt(key, def) }
    fun putInt(key: String, v: Int) { pref.edit().putInt(key, v).apply() }

    fun getBool(key: String, def: Boolean): Boolean = try {
        pref.getBoolean(key, def)
    } catch (e: ClassCastException) { drop(key); def }
    fun putBool(key: String, v: Boolean) { pref.edit().putBoolean(key, v).apply() }

    fun getFloat(key: String, def: Float): Float = try {
        pref.getFloat(key, def)
    } catch (e: ClassCastException) { drop(key); def }
    fun putFloat(key: String, v: Float) { pref.edit().putFloat(key, v).apply() }

    private fun drop(key: String) {
        com.autoball.AB.log.warn("store", "配置项 $key 与当前版本不兼容，已重置")
        pref.edit().remove(key).apply()
    }

    /** 旧值是字符串时按已知映射迁移到整数；无法迁移则丢弃 */
    private fun migrateInt(key: String, def: Int): Int {
        val raw = try { pref.getString(key, null) } catch (e: ClassCastException) { null }
        val mapped = when (key) {
            "panel_skin" -> SKIN_NAMES.indexOf(raw)
            else -> -1
        }
        if (mapped >= 0) {
            pref.edit().putInt(key, mapped).apply()
            return mapped
        }
        drop(key)
        return def
    }

    /** 悬浮窗皮肤名顺序，与 FloatPanelView.Skin 一致 */
    private val SKIN_NAMES = listOf(
        "SKIN_2020", "DEFAULT", "SIMPLE",
        "HORIZONTAL", "HORIZONTAL_SIMPLE", "ULTRA"
    )

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
