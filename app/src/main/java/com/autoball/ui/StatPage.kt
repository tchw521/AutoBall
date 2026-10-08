package com.autoball.ui

import android.content.Context
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.autoball.AB
import com.autoball.core.log.RunLog
import com.autoball.core.model.Script

/**
 * 运行统计面板（R-104）。
 *
 * 回答三个问题：**这个脚本靠不靠谱 / 跑一次多久 / 失败集中在哪一步**。
 *
 * 数据来源是现有的环形运行日志 [RunLog]，不额外埋点——
 * 日志里已有 runId、level、动作类型、后端、耗时，够算出这些指标。
 *
 * 一个必须说明的口径限制：日志是**环形缓冲**（默认 200 条，可在设置里调），
 * 超容量会被丢弃。所以统计只反映"最近若干条日志"的情况，
 * 不是全量历史。UI 上明确标注样本量，避免用户误以为是完整统计。
 */
class StatPage(context: Context, private val host: PageHost) : FrameLayout(context) {

    private val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        val root = Kit.root(context)
        root.addView(Kit.subbar(context, "运行统计", onBack = { host.showPage(4) }))
        val sc = android.widget.ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
        }
        sc.addView(box)
        root.addView(sc, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(root)
        render()
    }

    private fun render() {
        box.removeAllViews()

        val entries = AB.log.snapshot()
        val sample = entries.size

        if (sample == 0) {
            box.addView(Kit.hintBox(context,
                "还没有运行记录。跑一次脚本后这里会显示成功率、耗时与失败分布。"))
            return
        }

        box.addView(Kit.note(context,
            "样本：最近 $sample 条日志。日志是环形缓冲，超出容量会丢弃最早的记录，"
            + "所以这里反映的是近期情况而非全量历史。可在设置里调大保留条数。"))

        // ---------- 总览 ----------
        val okCount = entries.count { it.level == RunLog.Level.OK }
        val errCount = entries.count { it.level == RunLog.Level.ERROR }
        val warnCount = entries.count { it.level == RunLog.Level.WARN }
        val rate = if (okCount + errCount == 0) 0
        else (okCount * 100 / (okCount + errCount))

        box.addView(Kit.statCards(context, listOf(
            "总记录" to sample.toString(),
            "成功" to okCount.toString(),
            "失败" to errCount.toString()
        )))
        box.addView(Kit.statCards(context, listOf(
            "成功率" to "$rate%",
            "告警" to warnCount.toString(),
            "平均耗时" to "${avgLatency(entries)}ms"
        )))

        // ---------- 失败集中在哪一步 ----------
        val byAction = LinkedHashMap<String, Int>()
        entries.filter { it.level == RunLog.Level.ERROR }
            .forEach { byAction[it.result] = (byAction[it.result] ?: 0) + 1 }
        if (byAction.isNotEmpty()) {
            box.addView(Kit.section(context, "失败分布"))
            val sorted = byAction.toList().sortedByDescending { it.second }
            val max = sorted.first().second
            sorted.take(8).forEach { (name, n) ->
                box.addView(barRow(context, name, n, max))
            }
        }

        // ---------- 后端分布 ----------
        val byBackend = LinkedHashMap<String, Int>()
        entries.forEach {
            val b = it.backend ?: "-"
            byBackend[b] = (byBackend[b] ?: 0) + 1
        }
        if (byBackend.size > 1) {
            box.addView(Kit.section(context, "执行通道"))
            val max = byBackend.values.maxOrNull() ?: 1
            byBackend.toList().sortedByDescending { it.second }.forEach { (name, n) ->
                box.addView(barRow(context, name, n, max))
            }
        }

        // ---------- 各脚本运行次数 ----------
        val scripts = AB.store.all().filter { it.runCount > 0 }
            .sortedByDescending { it.runCount }
        if (scripts.isNotEmpty()) {
            box.addView(Kit.section(context, "脚本运行次数"))
            scripts.take(10).forEach { s ->
                box.addView(Kit.valueRow(context, s.name,
                    "已运行 ${s.runCount} 次", "▶", Theme.pri2(),
                    "${s.runCount}") {
                    host.openScript(s)
                })
            }
        }
    }

    private fun avgLatency(e: List<RunLog.Entry>): Long {
        val withLatency = e.filter { it.latencyMs > 0 }
        if (withLatency.isEmpty()) return 0
        return withLatency.sumOf { it.latencyMs } / withLatency.size
    }

    /** 横向条形：纯 View 宽度模拟，不引第三方图表库（零依赖约束） */
    private fun barRow(ctx: Context, label: String, n: Int, max: Int): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Display.dpInt(ctx, 12f), Display.dpInt(ctx, 7f),
                Display.dpInt(ctx, 12f), Display.dpInt(ctx, 7f))
        }
        row.addView(Kit.twoLine(ctx, label, "$n 次"))

        val h = Display.dpInt(ctx, 8f)
        val full = (Display.screenSize(ctx).x * 0.66f).toInt()
        val fillW = (full * n / max.coerceAtLeast(1)).coerceAtLeast(Display.dpInt(ctx, 4f))

        val wrap = FrameLayout(ctx).apply {
            addView(android.view.View(ctx).apply {
                background = Theme.rect(Theme.surface2(), 4f, ctx)
            }, FrameLayout.LayoutParams(full, h))
            addView(android.view.View(ctx).apply {
                background = Theme.rect(Theme.pri(), 4f, ctx)
            }, FrameLayout.LayoutParams(fillW, h))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Display.dpInt(ctx, 4f)
            }
        }
        row.addView(wrap)
        return row
    }

}
