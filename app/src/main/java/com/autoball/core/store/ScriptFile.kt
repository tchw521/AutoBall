package com.autoball.core.store

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.autoball.AB
import com.autoball.core.model.Script
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 脚本文件导入导出（R-105）。
 *
 * 此前只有分享码：短脚本够用，但几十步的脚本码会很长，
 * 复制粘贴容易截断，也没法直接存到网盘里备份。
 *
 * 文件格式 `.aball`：本质是一个 JSON，带魔数与版本，内含脚本数组。
 * 与分享码共用 Script 的序列化，不重复实现一套。
 *
 * **必须说明的限制**：模板图（R-101 取图器生成的）存本机私有目录，
 * 不随文件走。导出的脚本里「图片存在」条件会被标记为 UNKNOWN 并跳过。
 */
object ScriptFile {

    const val EXT = ".aball"
    const val MIME = "application/json"
    private const val MAGIC = "AutoBall"
    private const val VERSION = 1

    fun build(scripts: List<Script>): String = JSONObject().apply {
        put("magic", MAGIC)
        put("version", VERSION)
        put("exportedAt", System.currentTimeMillis())
        put("count", scripts.size)
        put("scripts", JSONArray().apply { scripts.forEach { put(it.toJson()) } })
    }.toString(2)

    /**
     * 导出：弹出系统「保存到…」选择器，用户选位置后写入。
     *
     * 走 ACTION_CREATE_DOCUMENT 而非 FileProvider——后者在零依赖约束下
     * 要自己实现 ContentProvider（androidx 不可用），而文档选择器既不需要
     * provider 也不需要任何存储权限，还能直接存到网盘目录。
     *
     * @return 需要调用方 startActivityForResult 的 Intent，及一个写入回调
     */
    fun exportIntent(scripts: List<Script>): Pair<Intent, (Uri) -> String?> {
        val name = if (scripts.size == 1) "${safe(scripts[0].name)}$EXT"
        else "autoball_${scripts.size}_scripts$EXT"
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = MIME
            putExtra(Intent.EXTRA_TITLE, name)
        }
        return i to { uri -> writeTo(uri, scripts) }
    }

    fun writeTo(uri: Uri, scripts: List<Script>): String? {
        return try {
            val ctx = com.autoball.App.get()
            val os = ctx.contentResolver.openOutputStream(uri)
            if (os == null) return "无法写入文件"
            os.use { it.write(build(scripts).toByteArray()) }
            null
        } catch (e: Exception) { "导出失败：${e.message}" }
    }

    /** 导入：弹出系统文件选择器 */
    fun importIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        // 部分国产 ROM 对 application/json 过滤不全，补一个通配兜底由本类校验魔数
        type = "*/*"
        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(MIME, "text/plain", "application/octet-stream"))
    }

    /** 从 URI 导入；返回失败原因或 null 表示成功 */
    fun import(ctx: Context, uri: Uri, onOk: (List<Script>) -> Unit): String? {
        return try {
            val text = ctx.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }
                ?: return "无法读取文件"
            parse(text)?.let { list ->
                if (list.isEmpty()) return "文件里没有脚本"
                list.forEach { s ->
                    // 重新分配 id：避免与本机已有脚本撞 id 导致覆盖
                    s.id = Script.newId()
                    s.slot = com.autoball.core.model.BallSlot.NONE
                    AB.store.save(s)
                }
                onOk(list)
                null
            } ?: "文件格式不正确"
        } catch (e: Exception) {
            "导入失败：${e.message}"
        }
    }

    fun parse(text: String): List<Script>? {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (o.optString("magic") != MAGIC) return null
        if (o.optInt("version", 0) > VERSION) return null
        val arr = o.optJSONArray("scripts") ?: return null
        val out = ArrayList<Script>()
        for (i in 0 until arr.length()) {
            runCatching { Script.fromJson(arr.getJSONObject(i)) }
                .getOrNull()?.let { out.add(it) }
        }
        return out
    }

    private fun safe(name: String): String =
        name.replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "_").take(40)
            .ifEmpty { "script" }
}
