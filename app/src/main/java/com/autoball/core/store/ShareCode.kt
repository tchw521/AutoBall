package com.autoball.core.store

import android.util.Base64
import com.autoball.core.model.Script
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 分享码：版本化头 + org.json + gzip + Base64 + CRC32 校验。
 *
 * 定位说明：这是**便于传输的开放格式**，不是加密授权，也不绑定设备信息。
 * 导入前校验完整性与版本，避免损坏或被篡改的代码被静默执行。
 */
object ShareCode {

    private const val PREFIX = "AB1:"
    private const val MAX_CODE_LEN = 512 * 1024   // 防止超大脚本拖垮导入
    private const val MAX_SCRIPT_LEN = 2 * 1024 * 1024

    fun encode(script: Script): String = encode(script, script.sharePass)

    /**
     * @param pass 口令；非空时对明文做 AES 加密（自动精灵同款「加密分享」）。
     *             口令不随码传输，导入方必须手动输入同样的口令。
     */
    fun encode(script: Script, pass: String): String {
        var bytes = script.toJson().toString().toByteArray(Charsets.UTF_8)
        if (pass.isNotEmpty()) bytes = CipherBox.encrypt(bytes, pass)
        val crc = crc32(bytes)
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(bytes) }
        val body = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        return PREFIX + crc.toString(16) + ":" + body
    }

    /**
     * 批量导出：把多个脚本打成一个 JSON 数组后走同一套打包流程。
     * 导入端用 [decodeAll] 还原，单脚本码仍走 [decode]，两者前缀不同可区分。
     */
    private const val PREFIX_ALL = "AB1A:"

    fun encodeAll(scripts: List<Script>): String {
        val arr = org.json.JSONArray()
        scripts.forEach { arr.put(it.toJson()) }
        val bytes = org.json.JSONObject().put("version", 1)
            .put("scripts", arr).toString().toByteArray(Charsets.UTF_8)
        val crc = crc32(bytes)
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(bytes) }
        val body = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        return PREFIX_ALL + crc.toString(16) + ":" + body
    }

    /** @return 还原出的脚本列表；格式错误或校验失败返回 null */
    fun decodeAll(code: String): List<Script>? {
        return try {
            val c = code.trim()
            if (!c.startsWith(PREFIX_ALL)) return null
            if (c.length > MAX_CODE_LEN) return null
            val rest = c.substring(PREFIX_ALL.length)
            val sep = rest.indexOf(':')
            if (sep <= 0) return null
            val crcHex = rest.substring(0, sep)
            val body = rest.substring(sep + 1)
            val bytes = GUNZIP(Base64.decode(body, Base64.NO_WRAP))
            if (bytes.size > MAX_SCRIPT_LEN) return null
            if (crc32(bytes).toString(16) != crcHex) return null
            val o = org.json.JSONObject(String(bytes, Charsets.UTF_8))
            val arr = o.optJSONArray("scripts") ?: return null
            val out = ArrayList<Script>()
            for (i in 0 until arr.length()) {
                val so = arr.optJSONObject(i) ?: continue
                out.add(Script.fromJson(so))
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    /** 是否批量码（用于导入时分流） */
    fun isBatch(code: String): Boolean = code.trim().startsWith(PREFIX_ALL)

    /** @return 解析出的脚本；格式错误或校验失败返回 null */
    fun decode(code: String): Script? = decode(code, "")

    /** @param pass 口令；码是加密的而口令为空/错误时返回 null */
    fun decode(code: String, pass: String): Script? {
        return try {
            val c = code.trim()
            if (!c.startsWith(PREFIX)) return null
            if (c.length > MAX_CODE_LEN) return null
            val rest = c.substring(PREFIX.length)
            val sep = rest.indexOf(':')
            if (sep <= 0) return null
            val crcHex = rest.substring(0, sep)
            val body = rest.substring(sep + 1)
            val packed = Base64.decode(body, Base64.NO_WRAP)
            val bytes = GUNZIP(packed)
            if (bytes.size > MAX_SCRIPT_LEN) return null
            if (crc32(bytes).toString(16) != crcHex) return null
            // 加密码：先按口令解密
            val plain = if (pass.isNotEmpty()) {
                runCatching { CipherBox.decrypt(bytes, pass) }.getOrNull() ?: return null
            } else if (CipherBox.looksEncrypted(bytes)) {
                // 没给口令但内容是密文——直接返回 null，避免解析出乱码
                return null
            } else bytes
            val o = JSONObject(String(plain, Charsets.UTF_8))
            Script.fromJson(o)
        } catch (e: Exception) {
            null
        }
    }

    private fun GUNZIP(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(data)).use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                bos.write(buf, 0, n)
            }
        }
        return bos.toByteArray()
    }

    private fun crc32(bytes: ByteArray): Long {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value
    }
}
