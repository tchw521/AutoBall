package com.autoball.core.util

import com.autoball.core.backend.ScreenResult
import com.autoball.core.model.ActionCondition
import com.autoball.core.model.ConditionSet
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
        /**
         * 位置周围条件：主匹配点偏移 (dxDp, dyDp) 处的颜色是否匹配。
         * 由调用方持主匹配点坐标后换算；不支持返回 null。
         */
        fun probeAt(dxDp: Float, dyDp: Float, hex: String, tol: Int): Boolean? = null
    }

    /**
     * @param raw `Action.condition` 原文：可能是 JSON，也可能是旧的纯表达式
     * @param fallbackExpr 当 raw 不是 JSON 时按旧逻辑求值（保证老脚本不失效）
     */
    /**
     * 求值一整组条件（支持多条 + AND/OR）。
     *
     * 三条判定原则（R-003：失效优于静默）
     * 1. 空条件 → 成立（没配就是不做限制）。
     * 2. 某条**无法判定**（能力缺失）时：AND 下整组判 UNKNOWN，
     *    OR 下该条**不参与**——OR 本就只要一条成立，无法判定不该拖累其他条。
     * 3. 绝不把 UNKNOWN 当作成立。
     */
    fun eval(raw: String?, vars: Map<String, String>, probe: Probe?): Outcome {
        val set = ConditionSet.parse(raw)
        if (set.items.isEmpty()) return Outcome.SATISFIED

        val real = set.items.filter { it.kind != ActionCondition.Kind.NONE }
        if (real.isEmpty()) return Outcome.SATISFIED

        var anyUnknown = false
        for (c in real) {
            when (evalOne(c, vars, probe)) {
                Outcome.SATISFIED -> if (set.op == ConditionSet.Op.OR) return Outcome.SATISFIED
                Outcome.NOT_SATISFIED -> if (set.op == ConditionSet.Op.AND) return Outcome.NOT_SATISFIED
                Outcome.UNKNOWN -> anyUnknown = true
            }
        }
        return if (set.op == ConditionSet.Op.AND) {
            if (anyUnknown) Outcome.UNKNOWN else Outcome.SATISFIED
        } else {
            // OR：走完都没成立
            if (anyUnknown) Outcome.UNKNOWN else Outcome.NOT_SATISFIED
        }
    }

    private fun evalOne(c: ActionCondition, vars: Map<String, String>,
                        probe: Probe?): Outcome {
        val base = when (c.kind) {
            ActionCondition.Kind.NONE -> Outcome.SATISFIED
            ActionCondition.Kind.JS ->
                if (c.value.isBlank()) Outcome.UNKNOWN
                else when (probe?.evalJs(c.value)) {
                    true -> Outcome.SATISFIED
                    false -> Outcome.NOT_SATISFIED
                    null -> Outcome.UNKNOWN
                }
            ActionCondition.Kind.COLOR -> {
                if (c.value.isBlank()) return Outcome.UNKNOWN
                // 先取非空局部变量：`when (probe?.x)` 的分支**不会**让编译器
                // 智能转换 probe 为非空，直接传给 matchProbes 会编译失败
                val p = probe ?: return Outcome.UNKNOWN
                when (p.findColor(c.value, c.tol, c.region)) {
                    true -> matchProbes(c, p)
                    false -> Outcome.NOT_SATISFIED
                    null -> Outcome.UNKNOWN
                }
            }
            ActionCondition.Kind.TEXT -> {
                if (c.value.isBlank()) return Outcome.UNKNOWN
                when (probe?.findText(c.value, c.region)) {
                    true -> Outcome.SATISFIED
                    false -> Outcome.NOT_SATISFIED
                    null -> Outcome.UNKNOWN
                }
            }
            ActionCondition.Kind.IMAGE -> {
                if (c.value.isBlank()) return Outcome.UNKNOWN
                when (probe?.findImage(c.value, c.sim / 100f, c.region)) {
                    true -> Outcome.SATISFIED
                    false -> Outcome.NOT_SATISFIED
                    null -> Outcome.UNKNOWN
                }
            }
        }
        return base
    }

    /**
     * 位置周围条件（多点找色）：主坐标匹配成功后，再校验周围各点。
     *
     * 这是自动精灵「位置周围条件」的用法——单点找色在界面里有大量同色干扰时
     * 会误命中，加上周围几个点的相对颜色约束就能精确定位。
     *
     * 探针能力不足时返回 UNKNOWN（不假装成立）。
     */
    private fun matchProbes(c: ActionCondition, probe: Probe): Outcome {
        if (c.probes.isEmpty()) return Outcome.SATISFIED
        for (p in c.probes) {
            if (p.color.isBlank()) continue
            when (probe.probeAt(p.dx, p.dy, p.color, p.tol)) {
                true -> Unit
                false -> return Outcome.NOT_SATISFIED
                null -> return Outcome.UNKNOWN
            }
        }
        return Outcome.SATISFIED
    }

    /**
     * 模板匹配：归一化互相关（NCC）。
     *
     * 零依赖约束下不能用 OpenCV，自己实现灰度 NCC。
     * 对**亮度整体偏移**不敏感（减均值后归一化），比逐像素比色稳健。
     *
     * @param region 百分比区域 [l,t,r,b]；null 表示整屏
     * @param threshold 相似度阈值 0–1，越高越严格
     */
    fun matchTemplate(sr: ScreenResult.Ok, tpl: android.graphics.Bitmap,
                      threshold: Float, region: FloatArray?): Boolean {
        val w = sr.width; val h = sr.height
        if (tpl.width > w || tpl.height > h) return false

        // 搜索范围（屏幕区域）
        val rx0 = ((region?.get(0) ?: 0f) / 100f * w).toInt().coerceIn(0, w - 1)
        val ry0 = ((region?.get(1) ?: 0f) / 100f * h).toInt().coerceIn(0, h - 1)
        val rx1 = ((region?.get(2) ?: 100f) / 100f * w).toInt().coerceIn(rx0 + tpl.width, w)
        val ry1 = ((region?.get(3) ?: 100f) / 100f * h).toInt().coerceIn(ry0 + tpl.height, h)
        if (rx1 <= rx0 || ry1 <= ry0) return false

        // 模板灰度 + 归一化
        val tw = tpl.width; val th = tpl.height
        val tg = FloatArray(tw * th)
        for (y in 0 until th) for (x in 0 until tw) {
            val c = tpl.getPixel(x, y)
            tg[y * tw + x] = (android.graphics.Color.red(c) * 0.299f +
                android.graphics.Color.green(c) * 0.587f +
                android.graphics.Color.blue(c) * 0.114f)
        }
        val tMean = tg.average().toFloat()
        var tNorm = 0f
        for (v in tg) { val d = v - tMean; tNorm += d * d }
        tNorm = kotlin.math.sqrt(tNorm)
        if (tNorm <= 0f) return false   // 纯色模板无法匹配

        // 全屏逐像素太慢：按搜索面积抽稀，步长上限保证不漏过小目标
        val step = kotlin.math.max(1,
            kotlin.math.min(4, ((rx1 - rx0) * (ry1 - ry0)) / 40_000))
        var best = -1f
        var py = ry0
        while (py + th <= ry1) {
            var px = rx0
            while (px + tw <= rx1) {
                // 窗口灰度
                var wMean = 0f
                for (y in 0 until th step 2) {
                    for (x in 0 until tw step 2) {
                        val c = sr.pixels[(py + y) * w + (px + x)]
                        wMean += (android.graphics.Color.red(c) * 0.299f +
                            android.graphics.Color.green(c) * 0.587f +
                            android.graphics.Color.blue(c) * 0.114f)
                    }
                }
                val cnt = ((th + 1) / 2) * ((tw + 1) / 2)
                wMean /= cnt
                var num = 0f; var dn1 = 0f; var dn2 = 0f
                for (y in 0 until th step 2) {
                    for (x in 0 until tw step 2) {
                        val c = sr.pixels[(py + y) * w + (px + x)]
                        val gv = (android.graphics.Color.red(c) * 0.299f +
                            android.graphics.Color.green(c) * 0.587f +
                            android.graphics.Color.blue(c) * 0.114f)
                        val tv = tg[y * tw + x] - tMean
                        val wv = gv - wMean
                        num += tv * wv; dn1 += tv * tv; dn2 += wv * wv
                    }
                }
                if (dn1 > 0 && dn2 > 0) {
                    val r = num / kotlin.math.sqrt(dn1 * dn2)
                    if (r > best) best = r
                    if (best >= threshold) return true
                }
                px += step
            }
            py += step
        }
        return false
    }

    /**
     * 在区域/整屏内找目标色；返回**命中点的像素坐标**，没找到返回 null。
     *
     * 返回坐标而非布尔值，是为了支持「位置周围条件」——
     * 后续探针要以这个命中点作基准做偏移校验（多点找色）。
     */
    fun findColorPos(sr: ScreenResult.Ok, hex: String, tol: Int,
                     region: FloatArray?): Pair<Int, Int>? {
        val want = runCatching { android.graphics.Color.parseColor(hex) }.getOrNull()
            ?: return null
        val wr = android.graphics.Color.red(want)
        val wg = android.graphics.Color.green(want)
        val wb = android.graphics.Color.blue(want)

        val w = sr.width; val h = sr.height
        val x0 = ((region?.get(0) ?: 0f) / 100f * w).toInt().coerceIn(0, w - 1)
        val y0 = ((region?.get(1) ?: 0f) / 100f * h).toInt().coerceIn(0, h - 1)
        val x1 = ((region?.get(2) ?: 100f) / 100f * w).toInt().coerceIn(x0 + 1, w)
        val y1 = ((region?.get(3) ?: 100f) / 100f * h).toInt().coerceIn(y0 + 1, h)

        // 抽稀：全屏逐像素太慢。步长按搜索面积自适应，上限 4px。
        val step = kotlin.math.max(1,
            kotlin.math.min(4, ((x1 - x0) * (y1 - y0)) / 200_000))
        val t2 = tol * tol
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val c = sr.pixels[y * w + x]
                val dr = android.graphics.Color.red(c) - wr
                val dg = android.graphics.Color.green(c) - wg
                val db = android.graphics.Color.blue(c) - wb
                if (dr * dr + dg * dg + db * db <= t2) return x to y
                x += step
            }
            y += step
        }
        return null
    }

    /** 指定像素点是否为目标色（供周围条件探针用） */
    fun colorAt(sr: ScreenResult.Ok, px: Int, py: Int, hex: String, tol: Int): Boolean {
        val want = runCatching { android.graphics.Color.parseColor(hex) }.getOrNull()
            ?: return false
        val c = sr.pixels[py * sr.width + px]
        val dr = android.graphics.Color.red(c) - android.graphics.Color.red(want)
        val dg = android.graphics.Color.green(c) - android.graphics.Color.green(want)
        val db = android.graphics.Color.blue(c) - android.graphics.Color.blue(want)
        return dr * dr + dg * dg + db * db <= tol * tol
    }

    /** 给日志用的可读描述 */
    fun describe(raw: String?): String {
        val set = ConditionSet.parse(raw)
        if (set.items.isEmpty()) return "不检测"
        val list = set.items.map { one(it) }
        return if (list.size == 1) list[0]
        else list.joinToString(if (set.op == ConditionSet.Op.AND) " 且 " else " 或 ")
    }

    private fun one(c: ActionCondition): String = when (c.kind) {
        ActionCondition.Kind.NONE -> "不检测"
        ActionCondition.Kind.JS -> "JS：${c.value}"
        ActionCondition.Kind.COLOR -> "颜色 ${c.value}" +
            (if (c.probes.isNotEmpty()) " +${c.probes.size}个周围点" else "")
        ActionCondition.Kind.TEXT -> "文字「${c.value}」"
        ActionCondition.Kind.IMAGE -> "图片匹配"
    }

    /** 能力不足时的修复指引 */
    fun howToFix(raw: String?): String {
        val set = ConditionSet.parse(raw)
        val kinds = set.items.map { it.kind }.toSet()
        return when {
            kinds.contains(ActionCondition.Kind.IMAGE) -> "图片匹配需要模板图，请用「取图器」框选并保存"
            kinds.contains(ActionCondition.Kind.TEXT) -> "文字检测需要 OCR 或节点树能力，当前不可用"
            kinds.contains(ActionCondition.Kind.COLOR) -> "颜色检测需要截图能力，请开启无障碍或 Shizuku"
            else -> "需要截图或 JS 引擎能力"
        }
    }
}
