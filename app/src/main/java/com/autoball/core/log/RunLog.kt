package com.autoball.core.log

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 运行日志：环形缓冲，默认保留最近 500 条。
 *
 * 合规要求：不记录输入文本、控件文本与分享码原文，只记录动作类型、后端、耗时与结果。
 */
/**
 * 运行日志环形缓冲。
 *
 * 容量从设置项「日志保留条数」读取（默认 200，与设计稿一致），
 * 超出后丢弃最早的条目。用读时取值而不是构造时固定，
 * 这样用户在设置页改了条数不必重启进程。
 */
class RunLog(private val defaultCapacity: Int = 200) {

    private val capacity: Int
        get() = runCatching {
            com.autoball.AB.store.getString("logKeep", "200 条")
                .filter { it.isDigit() }.toInt()
        }.getOrDefault(defaultCapacity).coerceIn(50, 5000)

    enum class Level { INFO, OK, WARN, ERROR }

    class Entry(
        val ts: Long,
        val runId: String,
        val level: Level,
        val actionId: String?,
        val backend: String?,
        val result: String,
        val latencyMs: Long,
        val message: String?,
        /**
         * 失败时的变量快照（R-107 调试回填用）。
         * 序列化为紧凑 JSON；只在失败时写入，且总量截断，避免撑爆环形缓冲。
         */
        val varsJson: String? = null
    ) {
        fun line(): String {
            val t = String.format("%02d:%02d:%02d",
                (ts / 3_600_000) % 24, (ts / 60_000) % 60, (ts / 1000) % 60)
            val tail = if (message.isNullOrEmpty()) "" else " · $message"
            return "$t [${level.name}] ${backend ?: "-"} ${result}${if (latencyMs > 0) " ${latencyMs}ms" else ""}$tail"
        }
    }

    private val buf = CopyOnWriteArrayList<Entry>()

    @Volatile
    var onChange: (() -> Unit)? = null

    fun add(runId: String, level: Level, result: String, message: String? = null,
            actionId: String? = null, backend: String? = null, latencyMs: Long = 0,
            varsJson: String? = null) {
        buf.add(Entry(System.currentTimeMillis(), runId, level, actionId, backend,
            result, latencyMs, message, varsJson))
        while (buf.size > capacity) buf.removeAt(0)
        onChange?.invoke()
    }

    fun info(runId: String, msg: String) = add(runId, Level.INFO, "INFO", msg)
    fun ok(runId: String, msg: String, backend: String? = null, ms: Long = 0) =
        add(runId, Level.OK, "OK", msg, null, backend, ms)
    fun warn(runId: String, msg: String) = add(runId, Level.WARN, "WARN", msg)
    fun error(runId: String, msg: String) = add(runId, Level.ERROR, "ERROR", msg)

    /** 失败并附带变量快照，供调试回填（R-107） */
    fun errorWithVars(runId: String, msg: String, vars: Map<String, String>) =
        add(runId, Level.ERROR, "ERROR", msg, null, null, 0, snapshotOf(vars))

    /**
     * 变量快照序列化。
     *
     * 两点约束：
     * 1. **只在失败时记录**——成功路径不落盘，减少留存。
     * 2. **总量截断 2KB**——否则变量多时会把环形缓冲挤满，
     *    反而挤掉了真正的运行记录。
     */
    private fun snapshotOf(vars: Map<String, String>): String {
        if (vars.isEmpty()) return "{}"
        val o = org.json.JSONObject()
        var size = 2
        for ((k, v) in vars) {
            val item = ""$k":"
            if (size + item.length + v.length + 2 > 2048) break
            o.put(k, v)
            size += item.length + v.length + 2
        }
        return o.toString()
    }

    /** 最近一次失败的变量快照；没有返回 null */
    fun lastFailVars(): Map<String, String>? {
        val e = buf.lastOrNull { it.level == Level.ERROR && !it.varsJson.isNullOrBlank() }
            ?: return null
        return runCatching {
            val o = org.json.JSONObject(e.varsJson!!)
            val out = LinkedHashMap<String, String>()
            val it2 = o.keys()
            while (it2.hasNext()) {
                val k = it2.next()
                out[k] = o.optString(k, "")
            }
            out
        }.getOrNull()
    }

    /** UI 只读副本 */
    fun snapshot(): List<Entry> = ArrayList(buf)

    fun clear() { buf.clear(); onChange?.invoke() }
}
