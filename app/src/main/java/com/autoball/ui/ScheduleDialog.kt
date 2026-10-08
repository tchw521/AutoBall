package com.autoball.ui

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.core.model.Script
import com.autoball.core.util.Display
import java.util.Calendar

/**
 * 定时触发 + 循环运行设置（复刻自动精灵「定时触发 / 循环执行」）。
 *
 * 自动精灵提供两类触发：指定时间触发、重启手机时触发；循环则是
 * 「循环间隔 + 循环次数」两个独立参数。
 *
 * 本应用不联网、不驻留常驻服务，所以用**下次进入应用时补触发**的策略：
 * - 到点时刻已过且当天未触发过 → 进入应用时立即跑一次
 * - 未到点 → 不跑
 *
 * 这与"后台常驻定时"有别，但避免了保活与耗电，也符合本工具
 * 「用户手动启动、随时可停」的定位。
 */
object ScheduleDialog {

    private val DAY_LABELS = arrayOf("日", "一", "二", "三", "四", "五", "六")

    fun show(ctx: android.app.Activity, s: Script, onSaved: () -> Unit) {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // ---- 定时触发 ----
        box.addView(Kit.section(ctx, "定时触发"))
        box.addView(Kit.switchRow(ctx, "启用定时", "到点后在应用内自动运行一次", "⏰",
            Theme.warn(), s.scheduleEnabled) { s.scheduleEnabled = it })

        // 时刻：小时 + 分钟两个滚轮式加减
        val cal = Calendar.getInstance()
        val curH = if (s.scheduleMinute >= 0) s.scheduleMinute / 60 else 8
        val curM = if (s.scheduleMinute >= 0) s.scheduleMinute % 60 else 0
        var hour = curH
        var min = curM
        val timeTv = TextView(ctx).apply {
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.textPri())
            gravity = Gravity.CENTER
        }
        fun syncTime() {
            timeTv.text = String.format("%02d:%02d", hour, min)
        }
        val timeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Display.dpInt(ctx, 10f), Display.dpInt(ctx, 6f),
                Display.dpInt(ctx, 10f), Display.dpInt(ctx, 6f))
        }
        timeRow.addView(stepperBtn(ctx, "−") { hour = (hour + 23) % 24; syncTime() })
        timeRow.addView(timeTv, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        timeRow.addView(stepperBtn(ctx, "+") { hour = (hour + 1) % 24; syncTime() })
        timeRow.addView(TextView(ctx).apply { text = "   " })
        timeRow.addView(stepperBtn(ctx, "−") { min = (min + 59) % 60; syncTime() })
        timeRow.addView(stepperBtn(ctx, "+") { min = (min + 1) % 60; syncTime() })
        syncTime()
        box.addView(Kit.rowCard(ctx).apply { addView(timeRow) })

        // 星期选择
        var days = s.scheduleDays
        val dayRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(Display.dpInt(ctx, 6f), Display.dpInt(ctx, 6f),
                Display.dpInt(ctx, 6f), Display.dpInt(ctx, 6f))
        }
        val dayBtns = ArrayList<TextView>()
        DAY_LABELS.forEachIndexed { i, lb ->
            val b = TextView(ctx).apply {
                text = lb
                textSize = 11.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(Display.dpInt(ctx, 3f), Display.dpInt(ctx, 6f),
                    Display.dpInt(ctx, 3f), Display.dpInt(ctx, 6f))
                setOnClickListener {
                    days = days xor (1 shl i)
                    syncDays(dayBtns, days)
                }
            }
            dayBtns.add(b)
            dayRow.addView(b, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Display.dpInt(ctx, 2f)
                marginEnd = Display.dpInt(ctx, 2f)
            })
        }
        syncDays(dayBtns, days)
        box.addView(dayRow)
        box.addView(Kit.note(ctx, "全部不选 = 每天触发；选中则只在对应星期触发。", 0f))

        // ---- 循环运行 ----
        box.addView(Kit.section(ctx, "循环运行"))
        var loop = s.loopCount
        val loopTv = TextView(ctx).apply {
            textSize = 12.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.pri())
            gravity = Gravity.END
            minWidth = Display.dpInt(ctx, 40f)
        }
        fun syncLoop() {
            loopTv.text = when {
                loop == 0 -> "不循环"
                loop < 0 -> "无限"
                else -> "${loop}次"
            }
        }
        syncLoop()
        box.addView(Kit.valueRow(ctx, "循环次数", "0=跑一次  -1=无限循环", "↻",
            Theme.pri(), loopTv.text.toString()) {
            pickLoop(ctx, loop) { v -> loop = v; syncLoop() }
        })

        var intervalSec = (s.loopIntervalMs / 1000L).toInt().coerceAtLeast(0)
        box.addView(Kit.sliderRow(ctx, "循环间隔",
            intervalSec.toFloat(), 0f, 60f, "秒") {
            intervalSec = it.toInt()
        })

        box.addView(Kit.note(ctx,
            "定时采用「进入应用时补触发」：到点且当天未跑过就自动运行一次，" +
            "不做后台常驻，避免耗电与被系统回收。", 4f))

        Ui.dialog(ctx, "定时与循环")
            .body(box)
            .width(Theme.DIALOG_W + 20f)
            .maxHeight(0.78f)
            .negative("取消") { }
            .positive("确定") {
                s.scheduleMinute = hour * 60 + min
                s.scheduleDays = days
                s.loopCount = loop
                s.loopIntervalMs = intervalSec * 1000L
                onSaved()
                true
            }.show()
    }

    private fun syncDays(btns: List<TextView>, days: Int) {
        btns.forEachIndexed { i, b ->
            val on = if (days == 0) true else (days shr i) and 1 == 1
            b.background = Theme.rect(
                if (on) Theme.pri2() else Theme.surface2(), 8f, b.context,
                if (on) Theme.pri() else Theme.line())
            b.setTextColor(if (on) android.graphics.Color.WHITE else Theme.textTer())
        }
    }

    private fun stepperBtn(ctx: Context, glyph: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = glyph
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.textSec())
            gravity = Gravity.CENTER
            background = Theme.rect(Theme.surface2(), 10f, ctx, Theme.line())
            setPadding(Display.dpInt(ctx, 14f), Display.dpInt(ctx, 6f),
                Display.dpInt(ctx, 14f), Display.dpInt(ctx, 6f))
            setOnClickListener { onClick() }
        }

    private fun pickLoop(ctx: android.app.Activity, cur: Int, onPick: (Int) -> Unit) {
        val opts = listOf(0 to "不循环（跑一次）", 2 to "2 次", 3 to "3 次",
            5 to "5 次", 10 to "10 次", -1 to "无限循环")
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var dlg: android.app.AlertDialog? = null
        opts.forEach { (v, lb) ->
            box.addView(TextView(ctx).apply {
                text = lb
                textSize = 13f
                setTextColor(if (v == cur) Theme.pri() else Theme.textPri())
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(Display.dpInt(ctx, 12f), Display.dpInt(ctx, 11f),
                    Display.dpInt(ctx, 12f), Display.dpInt(ctx, 11f))
                background = Theme.rect(
                    if (v == cur) Theme.surface2() else Theme.surface(), 10f, ctx,
                    if (v == cur) Theme.pri() else Theme.line())
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, Display.dpInt(ctx, 3f), 0, Display.dpInt(ctx, 3f))
                layoutParams = lp
                setOnClickListener { onPick(v); dlg?.dismiss() }
            })
        }
        dlg = Ui.dialog(ctx, "循环次数").body(box).negative("关闭") { }.show()
    }

    /**
     * 判断是否应当补触发一次。
     * 由应用启动/回到前台时调用。
     */
    fun shouldFire(s: Script, now: Calendar = Calendar.getInstance()): Boolean {
        if (!s.scheduleEnabled || s.scheduleMinute < 0) return false
        val dow = now.get(Calendar.DAY_OF_WEEK) - 1   // 0=周日
        if (s.scheduleDays != 0 && ((s.scheduleDays shr dow) and 1) == 0) return false
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        if (nowMin < s.scheduleMinute) return false
        val day = now.get(Calendar.YEAR) * 10000 +
                  (now.get(Calendar.MONTH) + 1) * 100 +
                  now.get(Calendar.DAY_OF_MONTH)
        return day != s.lastFiredDay
    }

    fun markFired(s: Script, now: Calendar = Calendar.getInstance()) {
        s.lastFiredDay = now.get(Calendar.YEAR) * 10000 +
                (now.get(Calendar.MONTH) + 1) * 100 +
                now.get(Calendar.DAY_OF_MONTH)
    }
}
