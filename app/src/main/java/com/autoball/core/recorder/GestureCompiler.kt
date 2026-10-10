package com.autoball.core.recorder

import com.autoball.core.model.*
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 原始指针轨迹 → 结构化 Action。
 *
 * 关键点压缩 + 点击/长按/滑动识别 + 去抖，让录制结果是可逐条编辑的动作流，
 * 而不是一堆无法维护的中间点。
 */
object GestureCompiler {

    /** 采样点 */
    class Sample(val x: Float, val y: Float, val t: Long)

    /** 一次完整手势（DOWN…UP） */
    class Stroke(
        val downT: Long,
        val upT: Long,
        val samples: List<Sample>
    ) {
        val durationMs: Long get() = (upT - downT).coerceAtLeast(1)
    }

    /** 识别阈值（研究报告 4 建议的 PoC 起点，需真机校准） */
    const val TAP_MAX_MS = 150L
    const val TAP_MAX_MOVE_DP = 12f
    const val LONG_PRESS_MIN_MS = 350L
    const val SAMPLE_MIN_INTERVAL_MS = 16L
    const val GESTURE_MAX_MS = 60_000L

    /**
     * 单指轨迹编译为动作。
     * @param density 屏幕密度，用于把 dp 阈值换算为像素
     */
    fun compile(stroke: Stroke, density: Float): Action {
        val first = stroke.samples.first()
        val last = stroke.samples.last()
        val movePx = maxMove(stroke.samples)
        val moveDp = movePx / density

        return when {
            stroke.durationMs >= GESTURE_MAX_MS ->
                Action().apply { type = ActionType.SWIPE; optionLabel = "滑动"; copyPoints(this, stroke) }
            moveDp <= TAP_MAX_MOVE_DP && stroke.durationMs <= TAP_MAX_MS -> {
                Action().apply {
                    type = ActionType.CLICK
                    optionLabel = "点击"
                    x = last.x; y = last.y
                    durationMs = stroke.durationMs.coerceAtLeast(10)
                    waitMs = 300
                }
            }
            moveDp <= TAP_MAX_MOVE_DP && stroke.durationMs >= LONG_PRESS_MIN_MS -> {
                Action().apply {
                    type = ActionType.CLICK
                    optionLabel = "长按"
                    x = last.x; y = last.y
                    durationMs = stroke.durationMs
                    comment = "长按"
                    waitMs = 300
                }
            }
            stroke.samples.size <= 2 -> {
                Action().apply {
                    type = ActionType.SWIPE
                    optionLabel = "滑动"
                    x = first.x; y = first.y; x2 = last.x; y2 = last.y
                    durationMs = stroke.durationMs
                    waitMs = 300
                }
            }
            else -> {
                Action().apply {
                    type = ActionType.GESTURE_SINGLE
                    optionLabel = "单指手势"
                    durationMs = stroke.durationMs
                    path.addAll(compress(stroke.samples))
                    waitMs = 300
                }
            }
        }
    }

    /** 多指：每个指针一条路径 */
    fun compileMulti(strokeList: List<Stroke>, density: Float): Action {
        return Action().apply {
            type = ActionType.GESTURE_MULTI
            optionLabel = "多指手势"
            durationMs = strokeList.maxOf { it.durationMs }
            for (s in strokeList) {
                val pts = compress(s.samples)
                if (pts.size >= 2) strokes.add(ArrayList(pts))
            }
            waitMs = 300
        }
    }

    private fun copyPoints(a: Action, stroke: Stroke) {
        val pts = compress(stroke.samples)
        if (pts.size >= 2) {
            a.x = pts.first().x; a.y = pts.first().y
            a.x2 = pts.last().x; a.y2 = pts.last().y
        }
    }

    /** 关键点压缩：保留方向变化点，去掉抖动中间点 */
    private fun compress(samples: List<Sample>): List<Pt> {
        if (samples.size <= 2) return samples.map { Pt(it.x, it.y) }
        val out = ArrayList<Pt>()
        out.add(Pt(samples.first().x, samples.first().y))
        var lastT = samples.first().t
        var prevDir = 0 to 0
        for (i in 1 until samples.size - 1) {
            val s = samples[i]
            if (s.t - lastT < SAMPLE_MIN_INTERVAL_MS) continue
            val p = samples[i - 1]
            val n = samples[i + 1]
            val dx1 = s.x - p.x; val dy1 = s.y - p.y
            val dx2 = n.x - s.x; val dy2 = n.y - s.y
            val dir1 = dirOf(dx1, dy1)
            val dir2 = dirOf(dx2, dy2)
            if (dir1 != dir2 || dir1 != prevDir) {
                out.add(Pt(s.x, s.y))
                prevDir = dir2
                lastT = s.t
            }
        }
        out.add(Pt(samples.last().x, samples.last().y))
        return out
    }

    private fun dirOf(dx: Float, dy: Float): Pair<Int, Int> {
        val threshold = 0.5f
        val x = if (abs(dx) < threshold) 0 else if (dx > 0) 1 else -1
        val y = if (abs(dy) < threshold) 0 else if (dy > 0) 1 else -1
        return x to y
    }

    private fun maxMove(samples: List<Sample>): Float {
        if (samples.isEmpty()) return 0f
        val f = samples.first()
        var m = 0f
        for (s in samples) {
            val d = hypot(s.x - f.x, s.y - f.y)
            if (d > m) m = d
        }
        return m
    }

    /** 导出为 JS：动作流 → JS 是可行的（语义可被更高表达力表示） */
    fun toJs(flow: com.autoball.core.model.Flow): String {
        val sb = StringBuilder()
        sb.append("// 由 AutoBall 动作流导出：").append(flow.name).append('\n')
        sb.append("// 注意：JS → 动作流不可逆（循环/条件/函数无法无损映射）\n")
        for (v in flow.vars) sb.append("var ").append(v.name).append(" = ")
            .append(quote(v.value)).append(";\n")
        for (a in flow.actions) {
            when (a.type) {
                ActionType.CLICK -> sb.append("click(").append(a.x.toInt()).append(", ")
                    .append(a.y.toInt()).append(");\n")
                ActionType.SWIPE -> sb.append("swipe(").append(a.x.toInt()).append(", ")
                    .append(a.y.toInt()).append(", ").append(a.x2.toInt()).append(", ")
                    .append(a.y2.toInt()).append(", ").append(a.durationMs).append(");\n")
                ActionType.INPUT_TEXT -> sb.append("input(").append(quote(a.text ?: "")).append(");\n")
                ActionType.OPEN_APP -> sb.append("openApp(").append(quote(a.pkg ?: "")).append(");\n")
                ActionType.KEY -> sb.append("key(").append(a.keyCode).append(");\n")
                ActionType.TOAST -> sb.append("toast(").append(quote(a.text ?: "")).append(");\n")
                ActionType.SET_VAR -> sb.append("setVar(").append(quote(a.varName ?: "")).append(", ")
                    .append(quote(a.varValue ?: "")).append(");\n")
                ActionType.RUN_JS -> sb.append(a.code ?: "").append('\n')
                else -> sb.append("// 未映射动作：").append(a.type.label).append('\n')
            }
            if (a.waitMs > 0) sb.append("sleep(").append(a.waitMs).append(");\n")
        }
        return sb.toString()
    }

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
