package com.autoball.core.store

import com.autoball.App
import org.json.JSONObject
import java.io.File

/**
 * 脚本可访问的**文件与键值存储**（R-124）。
 *
 * 设计要点（来自调研结论）：
 * 1. **不依赖 `/sdcard/`**。Android 10+ 分区存储下应用无法直接写外部根目录，
 *    自动精灵文档里 `writeFile('/sdcard/test.txt', ...)` 那种写法在目标 SDK 34
 *    上会失败。这里把脚本传入的任意路径**映射**到应用私有目录，
 *    脚本照原样写也能跑通（语义一致，位置不同）。
 * 2. **阻断路径穿越**。`../` 必须挡掉——脚本来源可能是别人分享的，
 *    不能允许它读写私有目录之外的任何位置。
 * 3. 上限保护：单文件写入上限，避免脚本写爆存储。
 */
object ScriptFiles {

    /** 单文件写入上限：1MB。脚本存的是文本数据，超过基本就是误用 */
    private const val MAX_BYTES = 1_048_576

    private fun root(): File = File(App.get().filesDir, "scriptfs").apply { mkdirs() }

    /**
     * 把脚本路径映射到私有根内。
     * @return null 表示路径不合法（空白、含 `..`、或解析后逃逸出根目录）
     */
    fun resolve(raw: String): File? {
        val p = raw.trim().replace('\\', '/')
        if (p.isBlank()) return null
        val parts = p.trimStart('/').split('/')
        if (parts.any { it == ".." }) return null
        return runCatching {
            val f = File(root(), parts.joinToString("/"))
            // 双重校验：canonicalPath 必须仍在根内（防软链接等绕过）
            if (!f.canonicalPath.startsWith(root().canonicalPath)) null else f
        }.getOrNull()
    }

    fun read(path: String): String? {
        val f = resolve(path) ?: return null
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull()
    }

    fun write(path: String, content: String): Boolean {
        val f = resolve(path) ?: return false
        if (content.toByteArray().size > MAX_BYTES) return false
        return runCatching {
            f.parentFile?.mkdirs()
            f.writeText(content)
            true
        }.getOrDefault(false)
    }

    fun append(path: String, content: String): Boolean {
        val f = resolve(path) ?: return false
        if (content.toByteArray().size > MAX_BYTES) return false
        return runCatching {
            f.parentFile?.mkdirs()
            f.appendText(content)
            true
        }.getOrDefault(false)
    }

    // ---------- 键值存储 ----------

    private fun pref() = App.get().getSharedPreferences("script_storage", 0)

    /** scope 用于隔离不同脚本的数据；空串表示全局 */
    private fun k(scope: String, key: String) = "$scope|$key"

    fun getStorage(key: String, scope: String = ""): String? =
        pref().getString(k(scope, key), null)

    fun setStorage(key: String, value: String, scope: String = "") {
        pref().edit().putString(k(scope, key), value).apply()
    }

    fun removeStorage(key: String, scope: String = "") {
        pref().edit().remove(k(scope, key)).apply()
    }

    /** 某个 scope 下的全部键值（脚本调试用） */
    fun dump(scope: String = ""): String {
        val o = JSONObject()
        val prefix = "$scope|"
        pref().all.forEach { (kk, v) ->
            if (kk.startsWith(prefix)) o.put(kk.removePrefix(prefix), v ?: "")
        }
        return o.toString()
    }
}
