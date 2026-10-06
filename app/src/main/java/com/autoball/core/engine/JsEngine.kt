package com.autoball.core.engine

import com.autoball.core.engine.quickjs.QuickJsEngine
import com.autoball.core.engine.rhino.RhinoEngine

/** JS 执行结果 */
class JsOutcome(
    val ok: Boolean,
    val value: String? = null,
    val error: String? = null,
    val stack: String? = null
)

/** 宿主回调：引擎调用宿主时用的最小契约，QuickJS 与 Rhino 共用 */
interface HostCb {
    fun call(name: String, argsJson: String): String
}

/**
 * JS 引擎抽象。
 *
 * 默认实现为 QuickJS（JNI）；当 CI 无 NDK 时用 `-PuseRhino=true` 切到纯 Java 的 Rhino 逃生口。
 * 两种引擎必须暴露**完全相同**的 API 表面，官方脚本需在两者上跑同一份黄金用例。
 */
interface JsEngine {
    fun run(code: String, host: JsHost, timeoutMs: Long): JsOutcome
    fun interrupt()
    fun close()
}

/** 引擎工厂：按构建开关与运行期可用性选择 */
object JsEngines {
    fun create(): JsEngine {
        if (com.autoball.BuildConfig.USE_RHINO) return RhinoEngine()
        val q = QuickJsEngine()
        return if (q.available()) q else RhinoEngine()
    }

    fun engineName(): String = if (com.autoball.BuildConfig.USE_RHINO) "rhino" else "quickjs"
}
