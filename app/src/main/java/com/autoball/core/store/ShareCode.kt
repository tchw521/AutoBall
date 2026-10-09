package com.autoball.core.store

import android.util.Base64
import com.autoball.core.model.ActionCondition
import com.autoball.core.model.ConditionSet
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

    // ---------- 模板图内联（用户选择：压缩后内联进分享码） ----------

    /** 单张模板图内联后的上限（原始字节）；超出则放弃内联，退回"仅本机可用" */
    private const val MAX_TPL_EACH = 24 * 1024
    /** 一个码里所有模板图的总上限 */
    private const val MAX_TPL_TOTAL = 96 * 1024
    /** 模板图最长边压到多少像素——160px 足够匹配，再大收益递减而码变长 */
    private const val TPL_MAX_EDGE = 160

    /**
     * 收集脚本里用到的模板图，压缩后以 base64 内联。
     *
     * 取舍：不内联的话「图片存在」条件在他人机器上直接失效；
     * 内联整图的话码会变成几百 KB 没法传。所以压到 160px + JPEG 75，
     * 单张上限 24KB、总量 96KB——典型模板压完只有几 KB。
     *
     * 压缩会损失细节，但对模板匹配（灰度 NCC）影响很小：
     * NCC 本身就是低频相似度度量。
     */
    private fun collectTemplates(script: Script): org.json.JSONObject {
        val out = org.json.JSONObject()
        var total = 0
        script.flow?.actions?.forEach { a ->
            // 用统一模型解析，支持多条条件（任一条件里的图片都要带上）
            ConditionSet.parse(a.condition).items.forEach { c ->
                if (c.kind != ActionCondition.Kind.IMAGE) return@forEach
                val id = c.value.takeIf { it.isNotBlank() } ?: return@forEach
                val bmp = TemplateStore.load(id) ?: return@forEach
                val small = scaleDown(bmp, TPL_MAX_EDGE)
                val bos = java.io.ByteArrayOutputStream()
                small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, bos)
                val bytes = bos.toByteArray()
                if (bytes.size > MAX_TPL_EACH) return@forEach
                if (total + bytes.size > MAX_TPL_TOTAL) return@forEach
                // 连同**相对屏幕的比例**一起带走（R-132）：
                // 只给图不给比例的话，导入方屏幕分辨率不同就永远匹配不上。
                val obj = org.json.JSONObject().put("b", Base64.encodeToString(bytes, Base64.NO_WRAP))
                TemplateStore.ratioOf(id)?.let { (wr, hr) ->
                    obj.put("wr", wr.toDouble()).put("hr", hr.toDouble())
                }
                out.put(id, obj)
                total += bytes.size
            }
        }
        return out
    }

    @Suppress("unused")
    private fun collectTemplatesLegacy(script: Script): org.json.JSONObject {
        val out = org.json.JSONObject()
        var total = 0
        script.flow?.actions?.forEach { a ->
            val id = imageRefOf(a.condition) ?: return@forEach
            val bmp = TemplateStore.load(id) ?: return@forEach
            val small = scaleDown(bmp, TPL_MAX_EDGE)
            val bos = java.io.ByteArrayOutputStream()
            small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, bos)
            val bytes = bos.toByteArray()
            if (bytes.size > MAX_TPL_EACH) return@forEach
            if (total + bytes.size > MAX_TPL_TOTAL) return@forEach
            out.put(id, Base64.encodeToString(bytes, Base64.NO_WRAP))
            total += bytes.size
        }
        return out
    }

    private fun scaleDown(src: android.graphics.Bitmap, maxEdge: Int): android.graphics.Bitmap {
        val w = src.width; val h = src.height
        val s = (maxEdge.toFloat() / maxOf(w, h)).coerceAtMost(1f)
        if (s >= 1f) return src
        return android.graphics.Bitmap.createScaledBitmap(
            src, (w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1), true)
    }

    /** 从条件的 JSON 里取出图片引用 id */
    private fun imageRefOf(raw: String?): String? {
        if (raw.isNullOrBlank() || !raw.trim().startsWith("{")) return null
        return runCatching {
            val o = org.json.JSONObject(raw)
            if (o.optString("k") != "IMAGE") null else o.optString("v").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** 导入端：把内联的模板图写回本机 TemplateStore，并重写条件里的引用 id */
    private fun restoreTemplates(script: Script, tpl: org.json.JSONObject?) {
        if (tpl == null || tpl.length() == 0) return
        script.flow?.actions?.forEach { a ->
            val set = ConditionSet.parse(a.condition)
            var changed = false
            set.items.forEach { c ->
                if (c.kind != ActionCondition.Kind.IMAGE) return@forEach
                // 兼容两种格式：旧分享码是裸字符串，新的是 {b, wr, hr}
                val v = tpl.opt(c.value)
                val (b64, wr, hr) = when (v) {
                    is org.json.JSONObject -> Triple(
                        v.optString("b", ""),
                        v.optDouble("wr", 0.0).takeIf { it > 0.0 },
                        v.optDouble("hr", 0.0).takeIf { it > 0.0 })
                    else -> Triple(tpl.optString(c.value, ""), null, null)
                }
                if (b64.isEmpty()) return@forEach
                val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }
                    .getOrNull() ?: return@forEach
                val bmp = android.graphics.BitmapFactory
                    .decodeByteArray(bytes, 0, bytes.size) ?: return@forEach
                // 重写为本机新 id，避免与导入方已有模板撞 id
                c.value = TemplateStore.saveBitmap(bmp, wr?.toFloat(), hr?.toFloat())
                changed = true
            }
            if (changed) a.condition = ConditionSet.serialize(set)
        }
    }

    /**
     * @param pass 口令；非空时对明文做 AES 加密（自动精灵同款「加密分享」）。
     *             口令不随码传输，导入方必须手动输入同样的口令。
     */
    fun encode(script: Script, pass: String): String {
        val root = script.toJson()
        val tpl = collectTemplates(script)
        if (tpl.length() > 0) root.put("tpl", tpl)
        var bytes = root.toString().toByteArray(Charsets.UTF_8)
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
        val tplAll = org.json.JSONObject()
        scripts.forEach { sc ->
            arr.put(sc.toJson())
            // 批量导出时合并所有脚本的模板图
            val t = collectTemplates(sc)
            val it2 = t.keys()
            while (it2.hasNext()) {
                val k = it2.next()
                if (!tplAll.has(k)) tplAll.put(k, t.optString(k))
            }
        }
        val root = org.json.JSONObject().put("version", 1).put("scripts", arr)
        if (tplAll.length() > 0) root.put("tpl", tplAll)
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
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
            val tpl = o.optJSONObject("tpl")
            val out = ArrayList<Script>()
            for (i in 0 until arr.length()) {
                val so = arr.optJSONObject(i) ?: continue
                val sc = Script.fromJson(so)
                restoreTemplates(sc, tpl)
                out.add(sc)
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
            val sc = Script.fromJson(o)
            restoreTemplates(sc, o.optJSONObject("tpl"))
            sc
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
