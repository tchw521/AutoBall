package com.autoball.core.store

import com.autoball.App
import com.autoball.core.model.Script
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 脚本快照与回滚（R-109）。
 *
 * 长脚本改坏或误删动作后无法恢复，是编辑体验里最痛的一点。
 * 这里做**本地快照链**：保存前自动留一份，可回退到任意历史版本。
 *
 * 三条克制规则
 * 1. **每脚本最多保留 10 份**——再多只会占空间，也没人会翻到第 11 份。
 * 2. **只在内容真的变了时才留**——原样点保存不产生快照，否则会攒一堆相同的。
 * 3. **合并同脚本连续快照**：1 分钟内连续多次小改动只保留最新一份，
 *    避免"改一个字一份快照"把历史冲稀。
 */
object SnapshotStore {

    private const val MAX_PER_SCRIPT = 10
    private const val MERGE_WINDOW_MS = 60_000L

    data class Snapshot(
        val id: String,
        val scriptId: String,
        val ts: Long,
        val name: String,
        val stepCount: Int
    )

    private fun dir(): File {
        val d = File(App.get().filesDir, "snapshots")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun idxFile(): File = File(dir(), "index.json")

    private fun loadIdx(): MutableList<Snapshot> {
        if (!idxFile().exists()) return ArrayList()
        return runCatching {
            val o = JSONObject(idxFile().readText())
            val out = ArrayList<Snapshot>()
            o.optJSONArray("items")?.let { a ->
                for (i in 0 until a.length()) {
                    val e = a.getJSONObject(i)
                    out.add(Snapshot(
                        id = e.optString("id"),
                        scriptId = e.optString("scriptId"),
                        ts = e.optLong("ts"),
                        name = e.optString("name"),
                        stepCount = e.optInt("steps")))
                }
            }
            out
        }.getOrDefault(ArrayList())
    }

    private fun saveIdx(list: List<Snapshot>) {
        runCatching {
            idxFile().writeText(JSONObject().apply {
                put("items", JSONArray().apply {
                    list.forEach { s ->
                        put(JSONObject().apply {
                            put("id", s.id); put("scriptId", s.scriptId)
                            put("ts", s.ts); put("name", s.name)
                            put("steps", s.stepCount)
                        })
                    }
                })
            }.toString())
        }
    }

    /** 某脚本的快照（新的在前） */
    fun of(scriptId: String): List<Snapshot> =
        loadIdx().filter { it.scriptId == scriptId }
            .sortedByDescending { it.ts }

    /**
     * 保存前留一份快照。
     * @param current 保存**之前**的脚本状态（即能被回退到的版本）
     */
    fun snapshot(current: Script) {
        val list = loadIdx()
        val mine = list.filter { it.scriptId == current.id }
            .sortedByDescending { it.ts }

        // 内容未变则不留
        val prevRaw = mine.firstOrNull()?.let { readRaw(it.id) }
        val curRaw = current.toJson().toString()
        if (prevRaw != null && prevRaw == curRaw) return

        // 合并窗口内的连续改动：替换最新一份
        val newest = mine.firstOrNull()
        if (newest != null && System.currentTimeMillis() - newest.ts < MERGE_WINDOW_MS) {
            runCatching { File(dir(), "${newest.id}.json").writeText(curRaw) }
            newest.let { s ->
                val i = list.indexOfFirst { it.id == s.id }
                if (i >= 0) list[i] = s.copy(ts = System.currentTimeMillis(),
                    name = current.name, stepCount = current.flow?.actions?.size ?: 0)
            }
            saveIdx(list)
            return
        }

        val id = "snap_" + current.id + "_" + System.currentTimeMillis().toString(36)
        runCatching { File(dir(), "$id.json").writeText(curRaw) }
        list.add(Snapshot(id, current.id, System.currentTimeMillis(),
            current.name, current.flow?.actions?.size ?: 0))

        // 超限：删掉该脚本最旧的
        val mine2 = list.filter { it.scriptId == current.id }
            .sortedByDescending { it.ts }
        if (mine2.size > MAX_PER_SCRIPT) {
            mine2.drop(MAX_PER_SCRIPT).forEach { old ->
                runCatching { File(dir(), "${old.id}.json").delete() }
                list.removeAll { it.id == old.id }
            }
        }
        saveIdx(list)
    }

    private fun readRaw(id: String): String? = runCatching {
        File(dir(), "$id.json").readText()
    }.getOrNull()

    fun load(id: String): Script? = runCatching {
        val raw = readRaw(id) ?: return null
        Script.fromJson(JSONObject(raw))
    }.getOrNull()

    fun delete(id: String) {
        val list = loadIdx()
        list.removeAll { it.id == id }
        saveIdx(list)
        runCatching { File(dir(), "$id.json").delete() }
    }

    /** 清空某脚本的全部快照 */
    fun clearFor(scriptId: String) {
        val list = loadIdx()
        list.filter { it.scriptId == scriptId }.forEach {
            runCatching { File(dir(), "${it.id}.json").delete() }
        }
        saveIdx(list.filter { it.scriptId != scriptId })
    }

    fun count(scriptId: String): Int = of(scriptId).size
}
