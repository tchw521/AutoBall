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

    fun encode(script: Script): String {
        val json = script.toJson()
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val crc = crc32(bytes)
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(bytes) }
        val packed = bos.toByteArray()
        val body = Base64.encodeToString(packed, Base64.NO_WRAP)
        return PREFIX + crc.toString(16) + ":" + body
    }

    /** @return 解析出的脚本；格式错误或校验失败返回 null */
    fun decode(code: String): Script? {
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
            val o = JSONObject(String(bytes, Charsets.UTF_8))
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
