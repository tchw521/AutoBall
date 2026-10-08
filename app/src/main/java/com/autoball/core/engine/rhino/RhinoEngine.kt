package com.autoball.core.engine.rhino

import com.autoball.core.engine.JsBridge

import com.autoball.core.engine.JsEngine
import com.autoball.core.engine.JsHost
import com.autoball.core.engine.JsOutcome
import org.json.JSONArray
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * Rhino 逃生口（纯 Java，用于无 NDK 的构建）。
 *
 * 关键约束：**编译期不引用 Rhino 任何类**，全部走反射 +
 * 动态代理实现宿主函数。这样默认（QuickJS）构建里即使没有 Rhino 依赖也能编译通过。
 */
class RhinoEngine : JsEngine {

    @Volatile
    private var interrupted = false

    override fun run(code: String, host: JsHost, timeoutMs: Long): JsOutcome {
        return try {
            val cls = Class.forName("org.mozilla.javascript.Context")
                ?: return JsOutcome(false, null, "Rhino 不可用")
            val enter = cls.getMethod("enter")
            val cx = enter.invoke(null) ?: return JsOutcome(false, null, "无法进入 Rhino 上下文")

            // Android 上必须关闭优化（解释器模式）
            runCatching { cx.javaClass.getMethod("setOptimizationLevel", Int::class.javaPrimitiveType).invoke(cx, -1) }

            val scope = cx.javaClass.getMethod("initStandardObjects").invoke(cx)
                ?: return JsOutcome(false, null, "无法初始化 scope")

            val fnIface = Class.forName("org.mozilla.javascript.Function")
            val proxy = Proxy.newProxyInstance(fnIface.classLoader, arrayOf<Class<*>>(fnIface),
                InvocationHandler { _, method, args ->
                    when (method.name) {
                        "call" -> {
                            val callArgs = args?.getOrNull(3) as? Array<*> ?: return@InvocationHandler ""
                            val name = callArgs.firstOrNull()?.toString() ?: return@InvocationHandler ""
                            val arr = JSONArray()
                            for (i in 1 until callArgs.size) arr.put(unwrapNative(callArgs[i]))
                            try {
                                host.call(name, arr.toString())
                            } catch (e: Throwable) {
                                "{\"ok\":false,\"error\":\"" + (e.message ?: "").replace("\"", "'") + "\"}"
                            }
                        }
                        "getClassName", "getDefaultValue" -> "host"
                        else -> defaultValue(method.returnType)
                    }
                })

            val soCls = Class.forName("org.mozilla.javascript.ScriptableObject")
            soCls.getMethod("putProperty", Class.forName("org.mozilla.javascript.Scriptable"),
                String::class.java, Class.forName("org.mozilla.javascript.Scriptable"))
                .invoke(null, scope, "__host", proxy)
            soCls.getMethod("putProperty", Class.forName("org.mozilla.javascript.Scriptable"),
                String::class.java, java.lang.Object::class.java)
                .invoke(null, scope, "__canceled", java.lang.Boolean.FALSE)

            val evalStr = cx.javaClass.getMethod(
                "evaluateString",
                Class.forName("org.mozilla.javascript.Scriptable"),
                String::class.java, String::class.java, Int::class.javaPrimitiveType,
                java.lang.Object::class.java
            )

            val prelude = JsBridge.PRELUDE
            evalStr.invoke(cx, scope, prelude, "<bridge>", 1, null)

            val t0 = System.currentTimeMillis()
            val watch = Thread {
                while (!interrupted && System.currentTimeMillis() - t0 < timeoutMs) {
                    try { Thread.sleep(50) } catch (e: InterruptedException) { return@Thread }
                }
                // 超时：Rhino 无可靠中断，标记后靠下一次宿主调用抛错退出
                interrupted = true
            }.apply { isDaemon = true }
            watch.start()

            val result = evalStr.invoke(cx, scope, code, "<script>", 1, null)
            interrupted = false
            JsOutcome(true, result?.toString(), null, null)
        } catch (e: Throwable) {
            JsOutcome(false, null, e.message, null)
        } finally {
            try {
                val cls = Class.forName("org.mozilla.javascript.Context")
                cls.getMethod("exit").invoke(null)
            } catch (ignored: Throwable) { }
        }
    }

    override fun interrupt() { interrupted = true }

    override fun close() { interrupted = false }

    private fun unwrapNative(v: Any?): Any {
        // Rhino 的 NativeJavaObject 包装了 Java 对象，取回原始值
        return try {
            val c = v?.javaClass
            if (c != null && c.name.startsWith("org.mozilla.javascript.NativeJavaObject")) {
                val m = c.getMethod("unwrap")
                m.invoke(v) ?: v.toString()
            } else v ?: ""
        } catch (e: Throwable) { v?.toString() ?: "" }
    }

    private fun defaultValue(t: Class<*>): Any? = when {
        !t.isPrimitive -> null
        t == java.lang.Boolean.TYPE -> false
        t == java.lang.Integer.TYPE -> 0
        t == java.lang.Long.TYPE -> 0L
        t == java.lang.Double.TYPE -> 0.0
        t == java.lang.Float.TYPE -> 0f
        t == java.lang.Short.TYPE -> 0.toShort()
        t == java.lang.Byte.TYPE -> 0.toByte()
        t == java.lang.Character.TYPE -> ' '
        t == Void.TYPE -> null
        else -> null
    }

    companion object {
    }
}
