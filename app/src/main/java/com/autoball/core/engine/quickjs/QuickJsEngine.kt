package com.autoball.core.engine.quickjs

import com.autoball.core.engine.HostCb
import com.autoball.core.engine.JsEngine
import com.autoball.core.engine.JsHost
import com.autoball.core.engine.JsOutcome
import org.json.JSONObject

/**
 * QuickJS 引擎（JNI）。
 *
 * 每个实例独占一个 OS 线程、一个 JSRuntime、一个 JSContext：
 * QuickJS 的 Runtime/Context 非线程安全，绝不能跨线程共享。
 */
class QuickJsEngine : JsEngine {

    companion object {
        @Volatile
        private var loaded = false

        @Volatile
        private var loadError: String? = null

        @JvmStatic
        fun isLoaded(): Boolean = loaded

        @JvmStatic
        fun loadErrorMessage(): String? = loadError

        init {
            try {
                System.loadLibrary("autoball")
                loaded = true
            } catch (e: Throwable) {
                loaded = false
                loadError = e.message
            }
        }
    }

    private var handle: Long = 0L

    fun available(): Boolean = loaded

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeEval(handle: Long, code: String, cb: HostCb, timeoutMs: Long): String
    private external fun nativeInterrupt(handle: Long)

    private fun ensure(): Long {
        if (handle == 0L) {
            if (!loaded) throw IllegalStateException("QuickJS 未加载: ${loadError ?: "未知原因"}")
            handle = nativeCreate()
        }
        return handle
    }

    override fun run(code: String, host: JsHost, timeoutMs: Long): JsOutcome {
        if (!loaded) return JsOutcome(false, null, "QuickJS 不可用：${loadError ?: "未加载"}")
        return try {
            val h = ensure()
            val adapter = object : HostCb {
                override fun call(name: String, argsJson: String): String = host.call(name, argsJson)
            }
            val raw = nativeEval(h, code, adapter, timeoutMs)
            val o = JSONObject(raw)
            JsOutcome(
                ok = o.optBoolean("ok", false),
                value = if (o.has("value")) o.optString("value", null) else null,
                error = o.optStringOrNull("error"),
                stack = o.optStringOrNull("stack")
            )
        } catch (e: Throwable) {
            JsOutcome(false, null, e.message, null)
        }
    }

    override fun interrupt() {
        if (handle != 0L && loaded) runCatching { nativeInterrupt(handle) }
    }

    override fun close() {
        if (handle != 0L) {
            runCatching { nativeDestroy(handle) }
            handle = 0L
        }
    }
}

internal fun org.json.JSONObject.optStringOrNull(key: String): String? {
    if (!has(key)) return null
    val v = opt(key)
    return if (v == null || v === org.json.JSONObject.NULL) null else v.toString()
}
