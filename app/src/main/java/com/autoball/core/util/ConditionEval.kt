package com.autoball.core.util

import com.autoball.core.backend.ScreenResult
import org.json.JSONObject

/**
 * 运行条件求值——**修复了一个让条件形同虚设的缺陷**。
 *
 * 背景：`Action.condition` 存的是一段紧凑 JSON（含 kind / value / region），
 * 但 `Condition.eval()` 只会把它当普通字符串求值：非空字符串 → 非空且不等于
 * "false"/"0" → **永远返回 true**。
 *
 * 也就是说：用户配了「图片存在才执行」，实际等于没配。这类静默失效最难发现，
 * 因为界面上显示「已设置」，脚本也照常跑，只是条件从不生效。
 *
 * 本类按 kind 分派真实求值，并对"能力不足无法判定"给出 [Outcome.UNKNOWN]——
 * **绝不静默当作成立**。
 */
object ConditionEval {

    enum class Outcome { SATISFIED, NOT_SATISFIED, UNKNOWN }

    /** 判定所需的能力；由调用方注入，本类不持有后端引用 */
    interface Probe {
        /** 截图；不可用返回 null */
        fun screen(): ScreenResult?
        /** 在指定区域找颜色（#RRGGBB，容差 0–255）；不支持返回 null */
        fun findColor(hex: String, tol: Int, region: FloatArray?): Boolean?
        /** 在屏幕文字里找子串；不支持返回 null */
        fun findText(text: String, region: FloatArray?): Boolean?
        /** 图像模板匹配；不支持返回 null */
        fun findImage(path: String, threshold: Float, region: FloatArray?): Boolean?
        /** 求值 JS 表达式；无引擎返回 null */
        fun evalJs(expr: String): Boolean?
    }

    /**
     * @param raw `Action.condition` 原文：可能是 JSON，也可能是旧的纯表达式
     * @param fallbackExpr 当 raw 不是 JSON 时按旧逻辑求值（保证老脚本不失效）
     */
    fun eval(raw: String?, vars: Map<String, String>, probe: Probe?): Outcome {
        if (raw.isNullOrBlank()) return Outcome.SATISFIED

        val t = raw.trim()

        // 不是 JSON → 旧格式（纯表达式），保持向后兼容
        if (!t.startsWith("{")) {
            return if (Condition.eval(t, vars)) Outcome.SATISFIED
            else Outcome.NOT_SATISFIED
        }

        val o = runCatching { JSONObject(t) }.getOrNull()
            ?: return Outcome.UNKNOWN

        val kind = o.optString("k", "NONE")
        val value = o.optString("v", "")
        val region = o.optJSONArray("r")?.let { a ->
            if (a.length() == 4) floatArrayOf(
                a.optDouble(0, 0.0).toFloat(), a.optDouble(1, 0.0).toFloat(),
                a.optDouble(2, 0.0).toFloat(), a.optDouble(3, 0.0).toFloat()
            ) else null
        }

        return when (kind) {
            "NONE" -> Outcome.SATISFIED

            "JS" -> when (probe?.evalJs(value)) {
                true -> Outcome.SATISFIED
                false -> Outcome.NOT_SATISFIED
                null -> Outcome.UNKNOWN
            }

            "COLOR" -> {
                if (value.isBlank()) return Outcome.UNKNOWN
                val tol = o.optInt("tol", 10)
                when (probe?.findColor(value, tol, region)) {
                    true -> Outcome.SATISFIED
                    false -> Outcome.NOT_SATISFIED
                    null -> Outcome.UNKNOWN
                }
            }

            "TEXT" -> {
                if (value.isBlank()) return Outcome.UNKNOWN
                when (probe?.findText(value, region)) {
                    true -> Outcome.SATISFIED
                    false -> Outcome.NOT_SATISFIED
                    null -> Outcome.UNKNOWN
                }
            }

            "IMG" -> {
                if (value.isBlank()) return Outcome.UNKNOWN
                val th = o.optDouble("th", 0.9).toFloat()
                when (probe?.findImage(value, th, region)) {
                    true -> Outcome.SATISFIED
                    false -> Outcome.NOT_SATISFIED
                    null -> Outcome.UNKNOWN
                }
            }

            else -> Outcome.UNKNOWN
        }
    }

    /**
     * 在截图像素里找颜色。
     *
     * 这是目前**唯一能真实判定**的识别类条件——截图是两条后端都有的能力，
     * 不需要额外模块。图像/文字匹配依赖按需下载的能力包，缺了就是 UNKNOWN。
     *
     * @param region 百分比区域 [l,t,r,b]（0–100）；null 表示整屏
     */
    fun matchColor(sr: ScreenResult.Ok, hex: String, tol: Int,
                   region: FloatArray?): Boolean {
        val target = runCatching {
            android.graphics.Color.parseColor(
                if (hex.startsWith("#")) hex else "#$hex")
        }.getOrNull() ?: return false
        val tr = android.graphics.Color.red(target)
        val tg = android.graphics.Color.green(target)
        val tb = android.graphics.Color.blue(target)

        val w = sr.width
        val h = sr.height
        val px = sr.pixels
        val x0 = ((region?.get(0) ?: 0f) / 100f * w).toInt().coerceIn(0, w - 1)
        val y0 = ((region?.get(1) ?: 0f) / 100f * h).toInt().coerceIn(0, h - 1)
        val x1 = ((region?.get(2) ?: 100f) / 100f * w).toInt().coerceIn(x0 + 1, w)
        val y1 = ((region?.get(3) ?: 100f) / 100f * h).toInt().coerceIn(y0 + 1, h)

        // 步长：大屏全屏逐像素太慢，按区域面积抽稀，最少 2dp 粒度
        val step = ((x1 - x0) / 200).coerceAtLeast(1).coerceAtMost(8)
        val tol2 = tol * tol
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val c = px[y * w + x]
                val dr = android.graphics.Color.red(c) - tr
                val dg = android.graphics.Color.green(c) - tg
                val db = android.graphics.Color.blue(c) - tb
                if (dr * dr + dg * dg + db * db <= tol2) return true
                x += step
            }
            y += step
        }
        return false
    }

    /** 给日志用的可读描述 */
    fun describe(raw: String?): String {
        if (raw.isNullOrBlank()) return "不检测"
        val t = raw.trim()
        if (!t.startsWith("{")) return "表达式：$t"
        val o = runCatching { JSONObject(t) }.getOrNull() ?: return "未知"
        val k = o.optString("k", "NONE")
        val v = o.optString("v", "")
        return when (k) {
            "NONE" -> "不检测"
            "JS" -> "JS：$v"
            "COLOR" -> "颜色 $v"
            "TEXT" -> "文字「$v」"
            "IMG" -> "图片匹配"
            else -> "未知类型"
        }
    }
}
