package com.autoball.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.autoball.AB
import com.autoball.core.log.RunLog
import com.autoball.core.util.Display

/**
 * 运行日志页（v3 #p-log）：顶部三卡统计 + 运行记录列表。
 *
 * 一比一对齐：
 * - .topbar：h1 26px/800 + 副标题 + 36dp 图标按钮（清空）
 * - .logbar：三张 .lstat（最小高 76、圆角 14、padding 0×14）
 * - .lrun 行：左侧状态点 .st（rn/ok/er）+ 中间（.lname / .lmeta）+ 右侧 .dur
 * - 展开后 .lsteps：虚线上边线 + .lsrow 步骤行
 */
class LogPage(context: Context, private val host: PageHost) : FrameLayout(context) {

    private val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val statRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
    }
    private var openIdx = -1

    init { build() }

    private fun build() {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(topbar())

        val pad = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Display.dpInt(context, 18f), Display.dpInt(context, 2f),
                Display.dpInt(context, 18f), Display.dpInt(context, 96f))
        }
        statRow.setPadding(0, 0, 0, Display.dpInt(context, 10f))
        pad.addView(statRow)
        pad.addView(box)
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(pad)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        render()
    }

    private fun topbar(): LinearLayout {
        val b = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Display.dpInt(context, 18f), Display.dpInt(context, 6f),
                Display.dpInt(context, 18f), Display.dpInt(context, 12f))
            gravity = Gravity.BOTTOM
        }
        val l = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        l.addView(TextView(context).apply {
            text = "运行日志"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textPri())
            includeFontPadding = false
        })
        l.addView(TextView(context).apply {
            text = "只记录动作类型、后端与耗时，不记录任何输入内容"
            textSize = 12f
            setTextColor(Theme.textSec())
            setPadding(0, Display.dpInt(context, 3f), 0, 0)
        })
        b.addView(l, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        b.addView(TextView(context).apply {
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
        })
        return b
    }

    private fun render() {
        val list = AB.log.snapshot()
        // ---- 三卡统计 ----
        statRow.removeAllViews()
        val total = list.size
        val ok = list.count { it.level == RunLog.Level.OK }
        val err = list.count { it.level == RunLog.Level.ERROR }
        statRow.addView(statCard("总记录", total.toString()))
        statRow.addView(statCard("成功", ok.toString()))
        statRow.addView(statCard("失败", err.toString()))

        // ---- 列表：按 runId 归并 ----
        box.removeAllViews()
        val byRun = LinkedHashMap<String, MutableList<RunLog.Entry>>()
        list.forEach { byRun.getOrPut(it.runId) { ArrayList() }.add(it) }
        if (byRun.isEmpty()) {
            box.addView(hintBox("还没有运行记录。运行一次脚本后，这里会显示每一步的结果。"))
            return
        }
        var i = 0
        byRun.entries.reversed().forEach { (runId, items) ->
            val idx = i++
            box.addView(runRow(idx, runId, items))
        }
    }

    /** .lstat：最小高 76、圆角 14、padding 0×14 */
    private fun statCard(label: String, value: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.rect(Theme.surface(), 14f, context, Theme.line())
            setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 10f),
                Display.dpInt(context, 14f), Display.dpInt(context, 10f))
            minimumHeight = Display.dpInt(context, 76f)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = Display.dpInt(context, 6f)
            }
            addView(TextView(context).apply {
                text = value
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.textPri())
            })
            addView(TextView(context).apply {
                text = label
                textSize = 10.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.textSec())
            })
        }

    /** .lrun：状态点 + 主信息 + 耗时 */
    private fun runRow(idx: Int, runId: String, items: List<RunLog.Entry>): LinearLayout {
        val failed = items.any { it.level == RunLog.Level.ERROR }
        val running = items.any { it.level == RunLog.Level.INFO } && !failed
        val c = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.rect(Theme.surface(), 14f, context, Theme.line())
            setPadding(Display.dpInt(context, 12f), Display.dpInt(context, 11f),
                Display.dpInt(context, 12f), Display.dpInt(context, 11f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, Display.dpInt(context, Theme.ROW_MB))
            layoutParams = lp
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // .st
        head.addView(Theme.oval(
            if (failed) Theme.danger() else if (running) Theme.warn() else Theme.ok()
        ).let { bg ->
            TextView(context).apply {
                background = bg
                layoutParams = LinearLayout.LayoutParams(
                    Display.dpInt(context, 10f), Display.dpInt(context, 10f))
            }
        })
        val main = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        main.addView(TextView(context).apply {
            text = items.first().runId
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
        // .dur
        head.addView(TextView(context).apply {
            text = if (failed) "失败" else if (running) "进行中" else "完成"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (failed) Theme.danger() else Theme.textSec())
        })
        c.addView(head)
        c.setOnClickListener { openIdx = if (openIdx == idx) -1 else idx; render() }

        // .lsteps（展开）
        if (openIdx == idx) {
            val steps = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, Display.dpInt(context, 9f), 0, 0)
            }
            steps.addView(android.view.View(context).apply {
                setBackgroundColor(Theme.line2())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })
            items.forEach { e ->
                steps.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, Display.dpInt(context, 5f), 0, Display.dpInt(context, 5f))
                    addView(TextView(context).apply {
                        text = e.line()
                        textSize = 11.5f
                        setTextColor(if (e.level == RunLog.Level.ERROR) Theme.danger()
                        else Theme.textSec())
                        layoutParams = LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                })
            }
            c.addView(steps)
        }
        return c
    }

    private fun hintBox(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(Theme.textSec())
        setLineSpacing(Display.dp(context, 2f), 1.7f)
        setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 13f),
            Display.dpInt(context, 14f), Display.dpInt(context, 13f))
        background = Theme.dashed(context, 13f)
    }

    fun refresh() { render() }
}
