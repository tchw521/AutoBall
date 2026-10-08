package com.autoball.core.store

import android.graphics.Bitmap
import com.autoball.App
import java.io.File

/**
 * 模板图存储（供「图片存在」运行条件使用）。
 *
 * 模板图由 [com.autoball.ui.ScreenPicker] 框选裁出，存到私有目录，
 * 只把**引用 id** 写进条件的 v 字段——不把图片塞进分享码，
 * 否则分享码会变成几百 KB 的字符串，根本没法传。
 *
 * 由此带来一个必须说明的限制：**模板图不随分享码走**。
 * 别人导入你的脚本后，「图片存在」条件会判定为 UNKNOWN（缺模板）并跳过。
 * 这比静默当作成立安全，但需要在 UI 上告知用户。
 */
object TemplateStore {

    private fun dir(): File {
        val d = File(App.get().filesDir, "templates")
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** 裁剪并保存；返回引用 id（存进条件） */
    fun save(src: Bitmap, l: Float, t: Float, r: Float, b: Float): String {
        val x = l.toInt().coerceIn(0, src.width - 1)
        val y = t.toInt().coerceIn(0, src.height - 1)
        val w = (r.toInt() - x).coerceIn(1, src.width - x)
        val h = (b.toInt() - y).coerceIn(1, src.height - y)
        val crop = Bitmap.createBitmap(src, x, y, w, h)
        val id = "tpl_" + System.currentTimeMillis().toString(36)
        File(dir(), "$id.png").outputStream().use {
            crop.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return id
    }

    /** 直接保存一张位图（导入端还原内联模板图时用） */
    fun saveBitmap(bmp: Bitmap): String {
        val id = "tpl_" + System.currentTimeMillis().toString(36) +
            "_" + (kotlin.random.Random.nextInt(1000))
        File(dir(), "$id.png").outputStream().use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return id
    }

    fun load(id: String): Bitmap? = runCatching {
        val f = File(dir(), "$id.png")
        if (!f.exists()) return null
        android.graphics.BitmapFactory.decodeFile(f.absolutePath)
    }.getOrNull()

    fun exists(id: String): Boolean = File(dir(), "$id.png").exists()

    fun delete(id: String) { runCatching { File(dir(), "$id.png").delete() } }

    fun all(): List<String> = dir().listFiles()
        ?.filter { it.name.startsWith("tpl_") && it.name.endsWith(".png") }
        ?.map { it.name.removeSuffix(".png") } ?: emptyList()
}
