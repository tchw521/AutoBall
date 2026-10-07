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
        val message: String?
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
            actionId: String? = null, backend: String? = null, latencyMs: Long = 0) {
        buf.add(Entry(System.currentTimeMillis(), runId, level, actionId, backend, result, latencyMs, message))
        while (buf.size > capacity) buf.removeAt(0)
        onChange?.invoke()
    }

    fun info(runId: String, msg: String) = add(runId, Level.INFO, "INFO", msg)
    fun ok(runId: String, msg: String, backend: String? = null, ms: Long = 0) =
        add(runId, Level.OK, "OK", msg, null, backend, ms)
    fun warn(runId: String, msg: String) = add(runId, Level.WARN, "WARN", msg)
    fun error(runId: String, msg: String) = add(runId, Level.ERROR, "ERROR", msg)

    /** UI 只读副本 */
    fun snapshot(): List<Entry> = ArrayList(buf)

    fun clear() { buf.clear(); onChange?.invoke() }
}
