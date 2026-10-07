package com.autoball.core.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 手势矩阵变形（UI 设计方案 v3 · morph）。
 *
 * 目的：让注入的坐标与时长带上细微随机性，更接近真人操作，
 * 而不是每轮都落在完全相同的像素上、用完全相同的时长。
 *
 * 配置串格式（与 v3 原型一致）：
 * - 空串：不做任何变换
 * - `a,b,c,d,e,f`：css matrix 六元组，对坐标做仿射变换
 *   - e / f 可写 `±N`，表示在该区间内**随机**平移（其余分量固定）
 * - 可选后缀 `· 时长±P%`：同时对手势时长做随机浮动
 *
 * 例：
 * - `1,0,0,1,±6,±6`  坐标随机抖动 ±6px
 * - `0.98,0.02,-0.02,0.98,0,0`  轻微旋转 + 缩放
 * - `1,0,0,1,0,0 · 时长±20%`  只随机化时长
 */
object Morph {

    /** 内置预设（与 v3 原型 MORPH_BUILTIN 一致） */
    val BUILTIN = listOf(
        "坐标随机 ±6px" to "1,0,0,1,±6,±6",
        "坐标随机 ±12px" to "1,0,0,1,±12,±12",
        "时长随机 ±20%" to "1,0,0,1,0,0 · 时长±20%",
        "贝塞尔曲线滑动" to "0.98,0.02,-0.02,0.98,0,0"
    )

    class Params(
        val a: Float, val b: Float, val c: Float, val d: Float,
        /** 平移分量；random>0 时为随机抖动半径 */
        val e: Float, val f: Float, val eRandom: Float, val fRandom: Float,
        /** 时长浮动比例，0 表示不浮动 */
        val durJitter: Float
    )

    /**
     * 解析配置串。
     * @return 解析结果；格式不合法返回 null（调用方应视为「不做变换」并记日志）
     */
    fun parse(raw: String?): Params? {
        val s = raw?.trim() ?: ""
        if (s.isEmpty()) return null

        var durJitter = 0f
        var matrix = s
        val dot = s.indexOf('·')
        if (dot >= 0) {
            val tail = s.substring(dot + 1).trim()
            val m = Regex("时长\\s*±\\s*(\\d+(?:\\.\\d+)?)\\s*%").find(tail)
            if (m != null) durJitter = m.groupValues[1].toFloatOrNull() ?: 0f
            matrix = s.substring(0, dot).trim()
        }

        val parts = matrix.split(',').map { it.trim() }
        if (parts.size != 6) return null
        val nums = FloatArray(6)
        val rnd = FloatArray(6)
        for (i in 0 until 6) {
            val t = parts[i]
            if (t.startsWith("±")) {
                val v = t.substring(1).toFloatOrNull() ?: return null
                rnd[i] = abs(v)
                nums[i] = 0f
            } else {
                nums[i] = t.toFloatOrNull() ?: return null
            }
        }
        return Params(nums[0], nums[1], nums[2], nums[3], nums[4], nums[5],
            rnd[4], rnd[5], durJitter)
    }

    /** 校验配置串是否合法（编辑页保存前用） */
    fun valid(raw: String?): Boolean {
        val s = raw?.trim() ?: ""
        if (s.isEmpty()) return true
        return parse(s) != null
    }

    /** 对单个坐标点做变换；p 为 null 时原样返回 */
    fun point(p: Params?, x: Float, y: Float, cx: Float, cy: Float): Pair<Float, Float> {
        if (p == null) return x to y
        // 以屏幕中心为变换原点，避免缩放/旋转把点推出屏幕
        val dx = x - cx
        val dy = y - cy
        val nx = p.a * dx + p.c * dy + p.e + jitter(p.eRandom)
        val ny = p.b * dx + p.d * dy + p.f + jitter(p.fRandom)
        return (cx + nx) to (cy + ny)
    }

    /**
     * 贝塞尔化的两点插值（用于滑动手势的时间曲线）。
     * 设计稿里「贝塞尔曲线滑动」预设只给矩阵，这里保留接口供后续扩展。
     */
    fun duration(p: Params?, ms: Long): Long {
        if (p == null || p.durJitter <= 0f) return ms
        val k = p.durJitter / 100f
        val factor = 1f + (Random.nextFloat() * 2f - 1f) * k
        return (ms * factor).toLong().coerceAtLeast(1L)
    }

    private fun jitter(r: Float): Float =
        if (r <= 0f) 0f else (Random.nextFloat() * 2f - 1f) * r

    /**
     * 旋转预设生成（供自定义预设保存时使用）。
     * @param deg 角度，@param scale 缩放
     */
    fun rotateScale(deg: Float, scale: Float): String {
        val rad = Math.toRadians(deg.toDouble())
        val cos = cos(rad).toFloat() * scale
        val sin = sin(rad).toFloat() * scale
        return String.format("%.4f,%.4f,%.4f,%.4f,0,0", cos, sin, -sin, cos)
    }
}
