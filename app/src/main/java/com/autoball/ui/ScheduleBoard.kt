package com.autoball.ui

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.autoball.AB
import com.autoball.core.model.Script
import com.autoball.core.util.Display
import java.util.Calendar

/**
 * 定时计划日历视图（R-119）。
 *
 * 此前定时触发只能逐个脚本进菜单查看——脚本一多就不知道"哪天有哪些要跑"，
 * 也发现不了时间撞车。这里给一个全局视野：
 * 1. **本周七天纵览**：每天列出当天会触发的脚本与时刻
 * 2. **时刻冲突检测**：同一时刻多个脚本会在进入应用时**串行依次跑**，
 *    耗时叠加，用户应当知情
 * 3. **下次触发倒计时**：不用自己算还有多久
 *
 * 与 [ScheduleDialog] 共用"补触发"语义（不做后台常驻），日历只做展示与跳转。
 */
class ScheduleBoard(private val ctx: Context, private val onEdit: (Script) -> Unit) : ScrollView(ctx) {

    private val dayLabels = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    init { render() }

    fun render() {
        removeAllViews()
        val root = Kit.column(ctx).apply {
            setPadding(Display.dpInt(ctx, 12f), Display.dpInt(ctx, 10f),
                Display.dpInt(ctx, 12f), Display.dpInt(ctx, 16f))
        }

        val all = AB.store.all().filter { it.scheduleEnabled && it.scheduleMinute >= 0 }
            .sortedBy { it.scheduleMinute }

        if (all.isEmpty()) {
            root.addView(Kit.note(ctx,
                "还没有设置任何定时任务。\n在脚本的三点菜单里选「定时与循环」即可添加。"))
            addView(root)
            return
        }

        root.addView(nextFireCard(all))
        root.addView(conflictCard(all))

        // 本周七天：只列出"有任务"的天，空天不占位（否则一周要滚很久）
        val now = Calendar.getInstance()
        val todayDow = now.get(Calendar.DAY_OF_WEEK) - 1   // 0=周日
        for (d in 0..6) {
            val dow = (todayDow + d) % 7
            val list = all.filter { hitsDay(it, dow) }
            if (list.isEmpty()) continue

            val isToday = d == 0
            val head = Kit.groupHead(ctx,
                dayLabels[dow] + (if (isToday) " · 今天" else ""))
            root.addView(head)

            for (s in list) {
                // Kit.rowCard 只有单参版本，带标题与点击的行要用 valueRow
                root.addView(Kit.valueRow(ctx, s.name, fmtTime(s.scheduleMinute),
                    icon = "⏰", iconColor = Theme.warn()) {
                    onEdit(s)
                })
            }
        }
        addView(root)
    }

    /** 每天触发位掩码：bit0=周日 … bit6=周六；0 表示每天 */
    private fun hitsDay(s: Script, dow: Int): Boolean =
        s.scheduleDays == 0 || ((s.scheduleDays shr dow) and 1) == 1

    private fun fmtTime(minute: Int): String =
        "%02d:%02d".format(minute / 60, minute % 60)

    /** 下次触发卡片：算出距今最近的那个"未来时刻" */
    private fun nextFireCard(all: List<Script>): LinearLayout {
        val now = Calendar.getInstance()
        val dow = now.get(Calendar.DAY_OF_WEEK) - 1
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        var best: Pair<Int, Script>? = null   // 天数偏移 → 脚本
        for (d in 0..7) {
            val wd = (dow + d) % 7
            for (s in all) {
                if (!hitsDay(s, wd)) continue
                // 同一天要求时刻未过；之后的日子任意时刻都算未来
                if (d == 0 && s.scheduleMinute < nowMin) continue
                if (best == null || d < best!!.first ||
                    (d == best!!.first && s.scheduleMinute < best!!.second.scheduleMinute)) {
                    best = d to s
                }
            }
            if (best != null) break
        }

        val tip = if (best == null) "最近七天没有待触发的任务"
        else {
            val (d, s) = best!!
            val whenTxt = if (d == 0) "今天 ${fmtTime(s.scheduleMinute)}"
            else "${d} 天后 ${dayLabels[(dow + d) % 7]} ${fmtTime(s.scheduleMinute)}"
            "下次触发：$whenTxt · ${s.name}"
        }
        return Kit.card(ctx).apply {
            addView(TextView(ctx).apply {
                text = tip
                textSize = 13f
                setTextColor(Theme.textPri())
                setPadding(Display.dpInt(ctx, 12f), Display.dpInt(ctx, 10f),
                    Display.dpInt(ctx, 12f), Display.dpInt(ctx, 10f))
            })
        }
    }

    /**
     * 撞车提示。
     *
     * 同一时刻的多个脚本在进入应用时会**串行依次执行**，总耗时叠加。
     * 用户多半以为它们是并行或只跑一个，这里明确告知。
     */
    private fun conflictCard(all: List<Script>): LinearLayout? {
        val groups = all.groupBy { it.scheduleMinute }.filter { it.value.size > 1 }
        if (groups.isEmpty()) return null
        val txt = "以下时刻有多个脚本，会依次串行运行（耗时叠加）：\n" +
            groups.entries.sortedBy { it.key }
                .joinToString("\n") { (m, list) ->
                    "· ${fmtTime(m)}：${list.joinToString("、") { it.name }}"
                }
        return Kit.card(ctx).apply {
            addView(TextView(ctx).apply {
                text = txt
                textSize = 12f
                setTextColor(Theme.warn())
                setPadding(Display.dpInt(ctx, 12f), Display.dpInt(ctx, 10f),
                    Display.dpInt(ctx, 12f), Display.dpInt(ctx, 10f))
                gravity = Gravity.START
            })
        }
    }
}
