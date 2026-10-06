package com.autoball.service

import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.autoball.App
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method

/**
 * Shizuku 接入客户端。
 *
 * 设计原则：**编译期零绑定，运行期按协议自适应**。
 * AutoBall 不引入 Shizuku 官方 AAR（零第三方依赖约束），改为：
 *   1) 反射取得系统服务 "shizuku" 的 IBinder；
 *   2) 反射调用 AIDL Stub.asInterface 取得服务代理；
 *   3) 按**方法名**查找并执行，避免依赖具体 transaction code 与签名。
 *
 * 任意一步失败都只让"Shizuku 通道"不可用，绝不影响无障碍后端运行脚本。
 */
class ShizukuClient {

    /** 命令执行结果 */
    class CmdResult(val exitCode: Int, val out: String, val err: String) {
        val ok: Boolean get() = exitCode == 0
    }

    /** 当前可用通道 */
    enum class Channel { SHIZUKU_BINDER, ROOT_SU, PLAIN, NONE }

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var channel: Channel = Channel.NONE
        private set

    @Volatile
    var shizukuVersion: Int = -1
        private set

    private var cachedBinder: IBinder? = null
    private var cachedService: Any? = null

    companion object {
        val instance: ShizukuClient by lazy { ShizukuClient() }

        private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
        private const val SHIZUKU_PKG_ALT = "rikka.shizuku"
    }

    /** Shizuku App 是否已安装（不表示已授权） */
    fun isInstalled(): Boolean {
        val pm = App.get().packageManager
        for (pkg in arrayOf(SHIZUKU_PKG, SHIZUKU_PKG_ALT)) {
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0)
                }
                return true
            } catch (e: Exception) { /* 未安装 */ }
        }
        return false
    }

    /** 探测并确定可用通道。每次调用都会重新握手，避免服务已死但仍认为可用 */
    fun probe(): Channel {
        channel = Channel.NONE
        lastError = null

        if (tryBindShizuku()) {
            channel = Channel.SHIZUKU_BINDER
            return channel
        }
        if (tryRootSu()) {
            channel = Channel.ROOT_SU
            return channel
        }
        channel = Channel.PLAIN
        return channel
    }

    fun isAuthorized(): Boolean = channel == Channel.SHIZUKU_BINDER || channel == Channel.ROOT_SU

    // ---------- Shizuku Binder 通道 ----------

    private fun getSystemServiceBinder(name: String): IBinder? {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val m: Method = sm.getDeclaredMethod("getService", String::class.java)
            m.isAccessible = true
            m.invoke(null, name) as? IBinder
        } catch (e: Throwable) {
            lastError = "无法读取系统服务: ${e.message}"
            null
        }
    }

    private fun tryBindShizuku(): Boolean {
        val binder = getSystemServiceBinder("shizuku")
            ?: run { lastError = "未找到 shizuku 服务（可能未启动或未授权）"; return false }
        if (!binder.pingBinder()) {
            lastError = "shizuku 服务已失效"
            cachedBinder = null
            cachedService = null
            return false
        }
        return try {
            val stub = Class.forName("moe.shizuku.api.IShizukuService\$Stub")
            val asInterface = stub.getDeclaredMethod("asInterface", IBinder::class.java)
            asInterface.isAccessible = true
            val svc = asInterface.invoke(null, binder) ?: return false

            // 握手：按名字找 getVersion，拿不到也认为通道可用（不同版本协议差异）
            val vm = findMethod(svc.javaClass, "getVersion")
            if (vm != null) {
                val v = vm.invoke(svc)
                if (v is Int) shizukuVersion = v
            }
            // 权限探测：不同版本方法名不同，失败不致命
            val pm = findMethod(svc.javaClass, "checkPermission")
            if (pm != null) {
                runCatching { pm.invoke(svc, "android.permission.INTERACT_ACROSS_USERS") }
            }
            cachedBinder = binder
            cachedService = svc
            true
        } catch (e: Throwable) {
            lastError = "Shizuku 握手失败: ${e.message}"
            false
        }
    }

    private fun findMethod(cls: Class<*>, name: String): Method? {
        try {
            val direct = cls.getDeclaredMethod(name)
            direct.isAccessible = true
            return direct
        } catch (ignored: Throwable) { }
        var c: Class<*>? = cls
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name == name) { m.isAccessible = true; return m }
            }
            c = c.superclass
        }
        for (m in cls.methods) {
            if (m.name == name) { m.isAccessible = true; return m }
        }
        return null
    }

    /** 通过 Shizuku 起一个进程执行命令；失败返回 null 由调用方降级 */
    private fun execViaShizuku(argv: Array<String>): CmdResult? {
        val svc = cachedService ?: return null
        val newProcess = findMethod(svc.javaClass, "newProcess") ?: return null
        return try {
            val proc = when (newProcess.parameterTypes.size) {
                3 -> newProcess.invoke(svc, argv, null, null)
                2 -> newProcess.invoke(svc, argv, null)
                else -> newProcess.invoke(svc, argv)
            } ?: return null

            val out = readProcessStream(proc, "getInputStream")
            val err = readProcessStream(proc, "getErrorStream")
            val exit: Int = try {
                val wm = findMethod(proc.javaClass, "waitFor")
                val r = wm?.invoke(proc)
                (r as? Int) ?: 0
            } catch (e: Throwable) { 0 }
            CmdResult(exit, out, err)
        } catch (e: Throwable) {
            lastError = "Shizuku 执行失败: ${e.message}"
            null
        }
    }

    private fun readProcessStream(proc: Any, methodName: String): String {
        return try {
            val m = findMethod(proc.javaClass, methodName) ?: return ""
            val obj = m.invoke(proc) ?: return ""
            when (obj) {
                is ParcelFileDescriptor -> {
                    val ins = ParcelFileDescriptor.AutoCloseInputStream(obj)
                    val bos = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        bos.write(buf, 0, n)
                    }
                    runCatching { ins.close() }
                    String(bos.toByteArray(), Charsets.UTF_8)
                }
                is java.io.InputStream -> {
                    val bos = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = obj.read(buf)
                        if (n <= 0) break
                        bos.write(buf, 0, n)
                    }
                    String(bos.toByteArray(), Charsets.UTF_8)
                }
                else -> ""
            }
        } catch (e: Throwable) { "" }
    }

    // ---------- Root / 直连兜底 ----------

    private fun tryRootSu(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out = String(readAll(p.inputStream), Charsets.UTF_8)
            p.waitFor()
            out.contains("uid=0")
        } catch (e: Throwable) { false }
    }

    private fun readAll(ins: java.io.InputStream): ByteArray {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                bos.write(buf, 0, n)
            }
        } catch (ignored: Throwable) { }
        return bos.toByteArray()
    }

    /** 同步执行命令，按通道自动选择执行方式 */
    fun exec(argv: Array<String>, timeoutMs: Long = 8000): CmdResult {
        if (channel == Channel.NONE) probe()

        if (channel == Channel.SHIZUKU_BINDER) {
            val r = execViaShizuku(argv)
            if (r != null) return r
            // Shizuku 进程服务不可用，降级尝试 su
            if (tryRootSu()) channel = Channel.ROOT_SU
        }

        val argv2 = if (channel == Channel.ROOT_SU) {
            arrayOf("su", "-c", argv.joinToString(" ") { q(it) })
        } else argv

        return try {
            val p = Runtime.getRuntime().exec(argv2)
            val outFuture = drain(p.inputStream)
            val errFuture = drain(p.errorStream)
            val finished = waitFor(p, timeoutMs)
            if (!finished) {
                runCatching { p.destroy() }
                CmdResult(-1, outFuture.get(), "执行超时")
            } else {
                CmdResult(p.exitValue(), outFuture.get(), errFuture.get())
            }
        } catch (e: Throwable) {
            CmdResult(-1, "", e.message ?: "执行异常")
        }
    }

    private fun q(s: String): String =
        if (s.matches(Regex("[A-Za-z0-9_./:=@%+,-]+"))) s else "'" + s.replace("'", "'\\''") + "'"

    private fun drain(ins: java.io.InputStream): java.util.concurrent.Callable<String> {
        val task = java.util.concurrent.Callable<String> {
            String(readAll(ins), Charsets.UTF_8)
        }
        val f = java.util.concurrent.FutureTask(task)
        val t = Thread(f, "ab-cmd-drain")
        t.isDaemon = true
        t.start()
        return f
    }

    private fun waitFor(p: Process, timeoutMs: Long): Boolean {
        val f = java.util.concurrent.FutureTask(java.util.concurrent.Callable<Boolean> {
            try { p.waitFor(); true } catch (e: Throwable) { true }
        })
        val t = Thread(f, "ab-cmd-wait")
        t.isDaemon = true
        t.start()
        return try {
            f.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Throwable) {
            false
        }
    }
}
