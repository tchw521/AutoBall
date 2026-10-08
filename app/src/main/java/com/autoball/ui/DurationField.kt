package com.autoball.ui

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.core.util.Display

/**
 * 时长输入（统一组件）——数值 + 单位下拉。
 *
 * 自动精灵的时间字段都带单位下拉（毫秒 / 秒 / 分钟）。
 * 此前三处各写各的：
 * - 动作编辑器「运行等待」：固定按秒，填小数
 * - 动作编辑器「按下时间」「滑动时长」：固定毫秒
 * - 全局设置「默认等待」：固定秒
 *
 * 单位写死的直接后果是用户得自己做换算——想等 2 分钟得填 120。
 * 这里收口为一个组件，内部一律按**毫秒**存，UI 上按当前单位换算显示。
 */
object DurationField {

    enum class Unit(val label: String, val factor: Long) {
        MS("毫秒", 1L),
        SEC("秒", 1000L),
        MIN("分钟", 60_000L);

        companion object {
            /** 按数值大小自动挑一个读起来最自然的单位 */
            fun fit(ms: Long): Unit = when {
                ms <= 0 -> SEC
                ms % MIN.factor == 0L && ms >= MIN.factor -> MIN
                ms % SEC.factor == 0L && ms >= SEC.factor -> SEC
                else -> MS
            }

            val LABELS: List<String> = values().map { it.label }
        }
    }

    /**
     * @param valueMs 当前值（毫秒）；0 或负表示未设置
     * @param onChange 回传毫秒
     */
    fun row(
        ctx: Context,
        label: String,
        valueMs0: Long,
        help: String? = null,
        onChange: (Long) -> Unit
    ): LinearLayout {
        var unit = Unit.fit(valueMs0)
        var valueMs = valueMs0

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Display.dpInt(ctx, 5f), 0, Display.dpInt(ctx, 5f), 0)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Display.dpInt(ctx, 23f))
            lp.setMargins(0, Display.dpInt(ctx, 1f), 0, Display.dpInt(ctx, 1f))
            layoutParams = lp
        }

        row.addView(TextView(ctx).apply {
            text = label
            textSize = 11.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.textSec())
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f)
        })

        val et = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "选填"
            textSize = 11.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.pri2())
            setHintTextColor(Theme.textTer())
            setSingleLine(true)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            background = Theme.rect(Theme.surface2(), 6f, ctx)
            setPadding(Display.dpInt(ctx, 7f), Display.dpInt(ctx, 2f),
                Display.dpInt(ctx, 7f), Display.dpInt(ctx, 2f))
            if (valueMs > 0) setText(show(valueMs, unit))
            setOnFocusChangeListener { _, has ->
                if (!has) {
                    val v = text.toString().trim().toDoubleOrNull()
                    valueMs = if (v == null || v <= 0) 0L else (v * unit.factor).toLong()
                    onChange(valueMs)
                }
            }
        }

        val unitTv = TextView(ctx).apply {
            text = unit.label
            textSize = 10.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.pri())
            gravity = Gravity.CENTER
            background = Theme.rect(Theme.surface2(), 8f, ctx, Theme.line())
            setPadding(Display.dpInt(ctx, 7f), Display.dpInt(ctx, 4f),
                Display.dpInt(ctx, 7f), Display.dpInt(ctx, 4f))
            setOnClickListener {
                Ui.popMenu(this, Unit.LABELS, unit.ordinal) { i ->
                    val nu = Unit.values()[i]
                    // 换单位时把已填数值按旧单位换算过去——
                    // 否则「500 毫秒」切到「秒」会变成「500 秒」
                    val cur = et.text.toString().trim().toDoubleOrNull()
                    if (cur != null) valueMs = (cur * unit.factor).toLong()
                    unit = nu
                    this.text = nu.label
                    if (valueMs > 0) et.setText(show(valueMs, nu))
                    onChange(valueMs)
                }
            }
        }

        row.addView(et, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f))
        row.addView(unitTv)

        if (help != null) {
            row.addView(TextView(ctx).apply {
                text = "?"
                textSize = 9.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Theme.textTer())
                gravity = Gravity.CENTER
                background = Theme.rect(Theme.surface2(), 9f, ctx)
                val sz = Display.dpInt(ctx, 17f)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    marginStart = Display.dpInt(ctx, 4f)
                }
                setOnClickListener { Ui.tip(this, "说明", help) }
            })
        }
        return row
    }

    /** 按单位格式化显示值；整数不带小数点 */
    private fun show(ms: Long, u: Unit): String {
        val v = ms.toDouble() / u.factor
        return if (v % 1.0 == 0.0) v.toLong().toString() else "%.2f".format(v)
    }
}
