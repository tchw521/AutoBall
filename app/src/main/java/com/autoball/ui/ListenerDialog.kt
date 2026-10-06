package com.autoball.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.core.model.Action
import com.autoball.core.util.Display

/**
 * 监听动作（UI 设计方案 v3 · listenDlg）。
 *
 * 七个触发阶段，每阶段可挂一个动作；阶段动作在对应时机被执行，
 * 用于「条件不满足时截图」「失败后重试」「结束后清理」等场景。
 *
 * 数据落在 Action.listeners：Map<阶段, Action?>，随动作一并序列化。
 */
object ListenerDialog {

    /** 触发阶段：顺序即执行文档中的展示顺序 */
    enum class Stage(val label: String, val desc: String) {
        BEFORE_CONDITION("检查运行条件前", "判断条件表达式之前执行，常用于准备变量或截图留证"),
        CONDITION_OK("运行条件满足后", "条件成立、即将执行本动作之前"),
        CONDITION_FAIL("运行条件失败后", "条件不成立、本动作被跳过时执行，常用于重试或记录"),
        BEFORE_ACTION("动作开始运行前", "注入之前执行，常用于前置等待"),
        ACTION_OK("动作运行成功后", "本动作执行成功之后"),
        ACTION_FAIL("动作运行失败后", "本动作执行失败之后，常用于兜底或告警"),
        AFTER_ACTION("动作运行结束后", "无论成功失败都会执行，常用于清理"),
        ;

        companion object {
            fun of(a: Action): List<Stage> = values().toList()
        }
    }

    fun show(activity: Activity, a: Action, onChanged: () -> Unit) {
        val ctx = activity
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        box.addView(Ui.note(ctx,
            "在指定时机自动执行一个附加动作。点击右侧按钮选择要执行的动作。"))

        val stages = Stage.values()
        for (st in stages) {
            val cur = a.listeners[st.name]
            box.addView(stageRow(ctx, st, cur) { picked ->
                if (picked == null) a.listeners.remove(st.name)
                else a.listeners[st.name] = picked
                onChanged()
                show(activity, a, onChanged)
            })
        }

        Ui.dialog(ctx, "监听动作")
            .body(box)
            .negative("关闭")
            .show()
    }

    private fun stageRow(ctx: Activity, st: Stage, cur: Action?,
                         onPick: (Action?) -> Unit): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Display.dpInt(ctx, 10f), Display.dpInt(ctx, 10f),
                Display.dpInt(ctx, 10f), Display.dpInt(ctx, 10f))
            background = Theme.bubble(ctx, Color.TRANSPARENT, 11f)
        }

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(ctx).apply {
            text = st.label
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textPri())
        })
        if (cur != null) {
            col.addView(TextView(ctx).apply {
                text = cur.type.label
                textSize = 11f
                setTextColor(Theme.pri2())
                setPadding(0, Display.dpInt(ctx, 2f), 0, 0)
            })
        }
        row.addView(col, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // 值按钮：未设置 / 已设置
        val btn = TextView(ctx).apply {
            text = if (cur == null) "未设置" else "已设置"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (cur == null) Theme.textSec() else Theme.pri2())
            gravity = Gravity.CENTER
            setPadding(Display.dpInt(ctx, 10f), Display.dpInt(ctx, 6f),
                Display.dpInt(ctx, 10f), Display.dpInt(ctx, 6f))
            background = Theme.bubble(ctx, Theme.surface2(), 8f)
            setOnClickListener { pickActionDialog(ctx, cur, onPick) }
        }
        row.addView(btn)

        // 「?」帮助
        row.addView(TextView(ctx).apply {
            text = "?"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textTer())
            gravity = Gravity.CENTER
            background = Theme.bubbleRound(ctx, Theme.surface2())
            val s = Display.dpInt(ctx, 22f)
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                marginStart = Display.dpInt(ctx, 8f)
            }
            setOnClickListener { Ui.tip(this, "说明", st.desc) }
        })
        row.addView(TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(Display.dpInt(ctx, 4f), 1)
        })
        return row
    }

    private fun pickActionDialog(ctx: Activity, cur: Action?, onPick: (Action?) -> Unit) {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(Ui.sheetOption(ctx, "●", Theme.ok(), "选择一个动作类型",
            "为这个时机指定要执行的动作") {
            ActionEditor.show(ctx, null) { a -> onPick(a) }
        })
        if (cur != null) {
            box.addView(Ui.sheetOption(ctx, "✕", Theme.danger(), "清除此监听",
                "移除该时机上已设置的动作") { onPick(null) })
        }
        Ui.dialog(ctx, "设置监听").body(box).negative("取消").show()
    }
}
