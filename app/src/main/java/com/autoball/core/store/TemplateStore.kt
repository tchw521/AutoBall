package com.autoball.core.store

import android.graphics.Bitmap
import com.autoball.App
import java.io.File

/**
 * 模板图存储（供「图片存在」运行条件使用）。
 *
 * 模板图由 [com.autoball.ui.ScreenPicker] 框选裁出，存到私有目录，
 * 条件里只存**引用 id**；分享码导出时才把图压缩内联（长边 160px、单张 ≤24KB）。
 *
 * 每条模板额外存一个 `.ratio` 元数据：模板**相对于录制屏幕**的宽高比例（R-132）。
 * 跨设备匹配必须靠它——模板是像素尺寸，分辨率不同的设备上待匹配区域尺寸
 * 根本对不上，NCC 会必然失败。坐标早在 v0.4 就做了百分比归一化，
 * 模板图当时漏了，这是同一件事的另一半。
 *
 * 限制：内联有总量上限，超限时模板不会随分享码走，导入方判定为 UNKNOWN 并跳过
 * （比静默当作成立安全，见 R-003）。UI 已在取图器处告知。
 */
object TemplateStore {

    private fun dir(): File {
        val d = File(App.get().filesDir, "templates")
        if (!d.exists()) d.mkdirs()
        return d
    }

    /**
     * 裁剪并保存；返回引用 id（存进条件）。
     *
     * 同时记录模板**相对于当时屏幕的比例**（见 [saveRatio]）。
     * src 是截屏，其宽高就是屏幕宽高，所以这里能直接算出比例。
     */
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
        saveRatio(id, w.toFloat() / src.width, h.toFloat() / src.height,
            com.autoball.App.get().resources.displayMetrics.density)
        return id
    }

    /**
     * 记录模板相对于录制屏幕的比例（跨设备匹配的关键，R-132）。
     *
     * 为什么必须记：模板是**像素尺寸**，但脚本要跨设备跑。
     * 在 1080 宽的设备上裁 100×100，拿到 720 宽的设备上对应区域只有 67×67——
     * NCC 要求模板与待匹配窗口同尺寸，尺寸对不上必然匹配失败，
     * 而且失败得很安静（返回 false），用户只会觉得"脚本导入后不灵"。
     *
     * 坐标早已做了百分比归一化（v0.4 起），模板图没有——这是同一个疏漏的另一半。
     */
    /**
     * @param density 录制时的屏幕密度（供「基于像素密度缩放」策略换算）。
     *                旧模板无此值时按 0 处理，DENSITY 策略退化为 BOTH。
     */
    private fun saveRatio(id: String, wRatio: Float, hRatio: Float, density: Float) {
        runCatching {
            File(dir(), "$id.ratio").writeText("$wRatio $hRatio $density")
        }
    }

    /** 模板在屏幕上的相对尺寸；无记录（旧模板）返回 null，调用方按原尺寸处理 */
    /** 模板元数据：相对屏幕的宽高比例 + 录制时密度 */
    class Meta(val wRatio: Float, val hRatio: Float, val density: Float)

    /** 读不到（旧模板）返回 null，调用方按"原尺寸"处理 */
    fun metaOf(id: String): Meta? = runCatching {
        val t = File(dir(), "$id.ratio").takeIf { it.exists() }?.readText() ?: return null
        val p = t.trim().split(Regex("\\s+"))
        if (p.size < 2) return null
        val w = p[0].toFloat(); val h = p[1].toFloat()
        if (w <= 0f || h <= 0f || w > 1f || h > 1f) return null
        Meta(w, h, p.getOrNull(2)?.toFloat() ?: 0f)
    }.getOrNull()

    /** 兼容旧调用：只要宽高比例 */
    fun ratioOf(id: String): Pair<Float, Float>? = metaOf(id)?.let { it.wRatio to it.hRatio }

    /** 直接保存一张位图（导入端还原内联模板图时用） */
    fun saveBitmap(bmp: Bitmap): String = saveBitmap(bmp, null, null)

    /**
     * @param wr / hr 模板相对于录制屏幕的宽高比例；
     *            导入分享码时由导出方带来，null 表示未知（按原尺寸匹配）
     */
    fun saveBitmap(bmp: Bitmap, wr: Float?, hr: Float?): String {
        val id = "tpl_" + System.currentTimeMillis().toString(36) +
            "_" + (kotlin.random.Random.nextInt(1000))
        File(dir(), "$id.png").outputStream().use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        // 导入方无法得知导出设备的密度，DENSITY 策略在这种情况下退化为 BOTH
        if (wr != null && hr != null && wr > 0f && hr > 0f && wr <= 1f && hr <= 1f) {
            saveRatio(id, wr, hr, 0f)
        }
        return id
    }

    fun load(id: String): Bitmap? = runCatching {
        val f = File(dir(), "$id.png")
        if (!f.exists()) return null
        android.graphics.BitmapFactory.decodeFile(f.absolutePath)
    }.getOrNull()

    fun exists(id: String): Boolean = File(dir(), "$id.png").exists()

    fun delete(id: String) {
        runCatching { File(dir(), "$id.png").delete() }
        runCatching { File(dir(), "$id.ratio").delete() }
    }

    fun all(): List<String> = dir().listFiles()
        ?.filter { it.name.startsWith("tpl_") && it.name.endsWith(".png") }
        ?.map { it.name.removeSuffix(".png") } ?: emptyList()
}
