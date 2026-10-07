package com.autoball.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.AB
import com.autoball.core.log.RunLog
import com.autoball.core.util.Display

/**
 * 运行日志页（v3 #p-log）：顶部三卡统计 + 运行记录列表。
 *
 * 卡片与提示框走 Kit 统一组件，本文件只负责按 runId 归并数据与展开态。
 */
class LogPage(context: Context, private val host: PageHost) : FrameLayout(context) {

    private val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val statRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private var openIdx = -1

    init {
        val root = Kit.root(context)
        root.addView(Kit.topbar(context, "运行日志",
            "只记录动作类型、后端与耗时，不记录任何输入内容",
            listOf(clearBtn())))
        val col = Kit.column(context)
        col.addView(statRow)
        col.addView(box)
        val sc = android.widget.ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        sc.addView(col)
        root.addView(sc, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        render()
    }

    private fun clearBtn(): TextView = TextView(context).apply {
        text = "清空"
        textSize = 11.5f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Theme.textSec())
        gravity = Gravity.CENTER
        background = Theme.rect(Theme.surface(), 12f, context, Theme.line())
        setPadding(Display.dpInt(context, 10f), 0, Display.dpInt(context, 10f), 0)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, Display.dpInt(context, 36f))
        setOnClickListener {
            AB.log.clear()
            openIdx = -1
            render()
            Ui.toast(context, "已清空日志")
        }
    }

    private fun render() {
        val list = AB.log.snapshot()
        statRow.removeAllViews()
        val cards = Kit.statCards(context, listOf(
            "总记录" to list.size.toString(),
            "成功" to list.count { it.level == RunLog.Level.OK }.toString(),
            "失败" to list.count { it.level == RunLog.Level.ERROR }.toString()
        ))
        statRow.addView(cards, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))

        box.removeAllViews()
        val byRun = LinkedHashMap<String, MutableList<RunLog.Entry>>()
        list.forEach { byRun.getOrPut(it.runId) { ArrayList() }.add(it) }
        if (byRun.isEmpty()) {
            box.addView(Kit.hintBox(context,
                "还没有运行记录。运行一次脚本后，这里会显示每一步的结果。"))
            return
        }
        var i = 0
        byRun.entries.reversed().forEach { (runId, items) ->
            val idx = i++
            box.addView(runRow(idx, runId, items))
        }
    }

    private fun runRow(idx: Int, runId: String, items: List<RunLog.Entry>): LinearLayout {
        val failed = items.any { it.level == RunLog.Level.ERROR }
        val running = items.any { it.level == RunLog.Level.INFO } && !failed
        val c = Kit.card(context, 12f).apply {
            orientation = LinearLayout.VERTICAL
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(View(context).apply {
            background = Theme.oval(
                if (failed) Theme.danger() else if (running) Theme.warn() else Theme.ok())
            layoutParams = LinearLayout.LayoutParams(
                Display.dpInt(context, 10f), Display.dpInt(context, 10f))
        })
        val main = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        main.addView(TextView(context).apply {
            text = runId
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textPri())
        })
        main.addView(TextView(context).apply {
            text = "${items.size} 条 · ${items.first().line()}"
            textSize = 11f
            setTextColor(Theme.textSec())
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, Display.dpInt(context, 4f), 0, 0)
        })
        head.addView(main, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = Display.dpInt(context, 10f)
        })
        head.addView(TextView(context).apply {
            text = if (failed) "失败" else if (running) "进行中" else "完成"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (failed) Theme.danger() else Theme.textSec())
        })
        c.addView(head)
        c.setOnClickListener { openIdx = if (openIdx == idx) -1 else idx; render() }

        if (openIdx == idx) {
            val steps = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, Display.dpInt(context, 9f), 0, 0)
            }
            steps.addView(View(context).apply {
                setBackgroundColor(Theme.line2())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })
            items.forEach { e ->
                steps.addView(TextView(context).apply {
                    text = e.line()
                    textSize = 11.5f
                    setTextColor(if (e.level == RunLog.Level.ERROR) Theme.danger()
                    else Theme.textSec())
                    setPadding(0, Display.dpInt(context, 5f), 0, Display.dpInt(context, 5f))
                })
            }
            c.addView(steps)
        }
        return c
    }

    fun refresh() { render() }
}
