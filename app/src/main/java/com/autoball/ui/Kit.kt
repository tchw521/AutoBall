package com.autoball.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.autoball.core.util.Display

/**
 * 统一组件库。
 *
 * 背景：v0.7–v1.2 为了赶进度，各页面各自私有一份 topbar / section / switchRow /
 * sliderRow / hintBox / miniBtn 等，同一规格被抄了 3–6 遍，改一处要改 N 个文件。
 * 本文件把这些收成单一实现，页面只负责拼装与数据。
 *
 * 规格一律对齐 UI 设计方案 v3 的设计令牌（见 Theme.kt 的常量）。
 */
object Kit {

    // =====================================================================
    // 顶栏 / 子栏
    // =====================================================================

    /** 36dp 图标按钮；color 为 0 时用表面色描边，否则实心填充 */
    fun actionBtn(ctx: Context, glyph: String, color: Int = 0,
                  onClick: () -> Unit): TextView = TextView(ctx).apply {
        text = glyph
        textSize = 17f
        setTypeface(null, Typeface.BOLD)
        setTextColor(if (color != 0) Color.WHITE else Theme.textSec())
        gravity = Gravity.CENTER
        background = if (color != 0) Theme.rect(color, 12f, ctx)
        else Theme.rect(Theme.surface(), 12f, ctx, Theme.line())
        val s = Display.dpInt(ctx, 36f)
        layoutParams = LinearLayout.LayoutParams(s, s).apply {
            marginStart = Display.dpInt(ctx, 6f)
        }
        setOnClickListener { onClick() }
    }

    /** 34dp 返回按钮（v3 .bk） */
    fun backBtn(ctx: Context, onClick: () -> Unit): TextView = TextView(ctx).apply {
        text = "‹"
        textSize = 20f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Theme.textSec())
        gravity = Gravity.CENTER
        background = Theme.rect(Theme.surface(), 11f, ctx, Theme.line())
        val s = Display.dpInt(ctx, 34f)
        layoutParams = LinearLayout.LayoutParams(s, s)
        setOnClickListener { onClick() }
    }

    /** 主题切换按钮（☾ / ☀，带发光） */
    fun themeBtn(ctx: Context, onClick: () -> Unit): TextView = TextView(ctx).apply {
        text = if (Theme.isDark()) "☾" else "☀"
        textSize = 17f
        gravity = Gravity.CENTER
        setTextColor(Theme.accent2())
        background = Theme.rect(Theme.surface(), 12f, ctx, Theme.line())
        val s = Display.dpInt(ctx, 36f)
        layoutParams = LinearLayout.LayoutParams(s, s).apply {
            marginStart = Display.dpInt(ctx, 6f)
        }
        setOnClickListener { onClick() }
    }

    /**
     * 一级页顶栏：26px 标题 + 副标题 + 右侧动作按钮。
     * @param actions 右侧按钮（已排好序）
     */
    fun topbar(ctx: Context, title: String, subtitle: String,
               actions: List<View> = emptyList(),
               subtitleView: TextView? = null): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Display.dpInt(ctx, 18f), Display.dpInt(ctx, 6f),
                Display.dpInt(ctx, 18f), Display.dpInt(ctx, 12f))
            gravity = Gravity.BOTTOM or Gravity.CENTER_VERTICAL
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(ctx).apply {
                text = title
                textSize = 26f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.textPri())
                includeFontPadding = false
            })
            if (subtitle.isNotEmpty() || subtitleView != null) {
                val tv = subtitleView ?: TextView(ctx)
                tv.text = subtitle
                tv.textSize = 12f
                tv.setTextColor(Theme.textSec())
                tv.setPadding(0, Display.dpInt(ctx, 3f), 0, 0)
                col.addView(tv)
            }
            addView(col, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            actions.forEach { addView(it) }
        }

    /** 二级页顶部：返回 + 20px 标题 + 右侧动作 */
    fun subbar(ctx: Context, title: String, onBack: () -> Unit,
               trailing: View? = null): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Display.dpInt(ctx, 14f), Display.dpInt(ctx, 2f),
                Display.dpInt(ctx, 18f), Display.dpInt(ctx, 10f))
            addView(backBtn(ctx, onBack))
            addView(TextView(ctx).apply {
                text = title
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.textPri())
                setPadding(Display.dpInt(ctx, 10f), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            trailing?.let { addView(it) }
        }

    /** 沉浸式二级页的状态栏占位（与 JS 页 / 悬浮设置页一致） */
    fun statusBar(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(Display.dpInt(ctx, 18f), Display.dpInt(ctx, 8f),
            Display.dpInt(ctx, 18f), 0)
        addView(TextView(ctx).apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textPri())
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(ctx).apply {
            text = "🔋"
            textSize = 12f
            setTextColor(Theme.textPri())
        })
    }

    /** 页面滚动容器：自带底部安全间距，避免被导航挡住 */
    fun scroller(ctx: Context, bottomDp: Float, content: View): android.widget.ScrollView =
        android.widget.ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }.also {
            content.setPadding(content.paddingLeft, content.paddingTop,
                content.paddingRight, Display.dpInt(ctx, bottomDp))
        }

    /** 标准页面根容器：VERTICAL，MATCH×MATCH */
    fun root(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT)
    }

    /** 内容列：左右 18dp */
    fun column(ctx: Context, bottomDp: Float = 96f): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Display.dpInt(ctx, 18f), Display.dpInt(ctx, 2f),
                Display.dpInt(ctx, 18f), Display.dpInt(ctx, bottomDp))
        }

    // =====================================================================
    // 分区 / 卡片
    // =====================================================================

    /** 页面内小节标题：11px 三级色 */
    fun section(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 11f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Theme.textTer())
        letterSpacing = 0.03f
        setPadding(Display.dpInt(ctx, 2f), Display.dpInt(ctx, 14f),
            Display.dpInt(ctx, 2f), Display.dpInt(ctx, 8f))
    }

    /** 设置页分组标题：12px 主色（v3 .sgh） */
    fun groupHead(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Theme.pri())
        letterSpacing = 0.02f
        setPadding(Display.dpInt(ctx, 18f), Display.dpInt(ctx, 16f),
            Display.dpInt(ctx, 18f), Display.dpInt(ctx, 7f))
    }

    /** 分区标题 + 右侧渐变胶囊按钮（v3 .secrow + .addbtn） */
    fun secRow(ctx: Context, title: String, actionText: String,
               onClick: () -> Unit): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, Display.dpInt(ctx, 20f), 0, Display.dpInt(ctx, 9f))
        layoutParams = lp
        addView(TextView(ctx).apply {
            text = title
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textTer())
            letterSpacing = 0.03f
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(ctx).apply {
            text = actionText
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Theme.grad(ctx, 10f)
            setPadding(Display.dpInt(ctx, 12f), 0, Display.dpInt(ctx, 12f), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Display.dpInt(ctx, 30f))
            setOnClickListener { onClick() }
        })
    }

    /** 通用卡片：圆角 16、surface 底、细描边 */
    fun card(ctx: Context, padDp: Float = 12f): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.rect(Theme.surface(), Theme.RADIUS, ctx, Theme.line())
            setPadding(Display.dpInt(ctx, padDp), Display.dpInt(ctx, padDp),
                Display.dpInt(ctx, padDp), Display.dpInt(ctx, padDp))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, Display.dpInt(ctx, 10f))
            layoutParams = lp
        }

    /** 设置页卡片：圆角 14、左右 12 外边距（v3 .scard） */
    fun settingCard(ctx: Context, inner: LinearLayout): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.rect(Theme.surface(), 14f, ctx, Theme.line())
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(Display.dpInt(ctx, 12f), 0,
                Display.dpInt(ctx, 12f), Display.dpInt(ctx, 12f))
            layoutParams = lp
            addView(inner)
            clipToOutline = true
        }

    /** 行卡片：圆角 13、padding 13×11（悬浮设置页与设置页共用） */
    fun rowCard(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Theme.rect(Theme.surface(), 13f, ctx, Theme.line())
        setPadding(Display.dpInt(ctx, 13f), Display.dpInt(ctx, 11f),
            Display.dpInt(ctx, 13f), Display.dpInt(ctx, 11f))
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, Display.dpInt(ctx, 8f))
        layoutParams = lp
    }

    /** 字段行：label + 内容（v3 .field） */
    fun field(ctx: Context, label: String, content: View): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.rect(Theme.surface(), 13f, ctx, Theme.line())
            setPadding(Display.dpInt(ctx, 14f), Display.dpInt(ctx, 11f),
                Display.dpInt(ctx, 14f), Display.dpInt(ctx, 11f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, Display.dpInt(ctx, 9f))
            layoutParams = lp
            addView(TextView(ctx).apply {
                text = label
                textSize = 12.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.textSec())
                layoutParams = LinearLayout.LayoutParams(
                    Display.dpInt(ctx, 56f), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            addView(content, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

    // =====================================================================
    // 行组件
    // =====================================================================

    /** 29dp 圆角 9 图标块（v3 .si） */
    fun iconBox(ctx: Context, glyph: String, color: Int): TextView = TextView(ctx).apply {
        text = glyph
        textSize = 13f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = Theme.rect(color, 9f, ctx)
        layoutParams = LinearLayout.LayoutParams(Display.dpInt(ctx, 29f),
            Display.dpInt(ctx, 29f))
    }

    /** 主标题 + 副标题两行（v3 .sm） */
    fun twoLine(ctx: Context, title: String, sub: String? = null): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Display.dpInt(ctx, 11f)
                marginEnd = Display.dpInt(ctx, 6f)
            }
            addView(TextView(ctx).apply {
                text = title
                textSize = 13.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.textPri())
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (!sub.isNullOrEmpty()) addView(TextView(ctx).apply {
                text = sub
                textSize = 10.5f
                setTextColor(Theme.textSec())
                setLineSpacing(Display.dp(ctx, 1f), 1.45f)
                setPadding(0, Display.dpInt(ctx, 3f), 0, 0)
            })
        }

    /**
     * 开关行。
     * 用真实的两态渲染：滑块位置与轨道颜色都跟随状态，点击整行切换。
     */
    fun switchRow(ctx: Context, title: String, sub: String? = null,
                  icon: String = "", iconColor: Int = 0,
                  init: Boolean = false, onChange: (Boolean) -> Unit): LinearLayout {
        var on = init
        val row = rowCard(ctx)
        if (icon.isNotEmpty()) row.addView(iconBox(ctx, icon, iconColor))
        row.addView(twoLine(ctx, title, sub))

        val track = LinearLayout(ctx).apply {
            background = Theme.rect(if (on) Theme.pri2() else Theme.line2(), 12f, ctx)
            layoutParams = LinearLayout.LayoutParams(
                Display.dpInt(ctx, Theme.SW_W), Display.dpInt(ctx, Theme.SW_H))
        }
        val knob = View(ctx).apply {
            background = Theme.oval(Color.WHITE)
            val lp = LinearLayout.LayoutParams(Display.dpInt(ctx, Theme.SW_KNOB),
                Display.dpInt(ctx, Theme.SW_KNOB))
            lp.leftMargin = if (on) Display.dpInt(ctx, 21f) else Display.dpInt(ctx, 3f)
            lp.topMargin = Display.dpInt(ctx, 3f)
            layoutParams = lp
        }
        track.addView(knob)
        row.addView(track)
        row.setOnClickListener {
            on = !on
            track.background = Theme.rect(if (on) Theme.pri2() else Theme.line2(), 12f, ctx)
            (knob.layoutParams as LinearLayout.LayoutParams).leftMargin =
                if (on) Display.dpInt(ctx, 21f) else Display.dpInt(ctx, 3f)
            knob.requestLayout()
            onChange(on)
        }
        return row
    }

    /** 值行：右侧显示值或箭头（v3 .srow + .sv） */
    fun valueRow(ctx: Context, title: String, sub: String? = null,
                 icon: String = "", iconColor: Int = 0,
                 value: String = "›", valueColor: Int = 0,
                 onClick: () -> Unit): LinearLayout {
        val row = rowCard(ctx)
        if (icon.isNotEmpty()) row.addView(iconBox(ctx, icon, iconColor))
        row.addView(twoLine(ctx, title, sub))
        row.addView(TextView(ctx).apply {
            text = value
            textSize = if (value == "›") 17f else 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (valueColor != 0) valueColor else Theme.textTer())
            gravity = Gravity.END
            minWidth = Display.dpInt(ctx, 26f)
        })
        row.setOnClickListener { onClick() }
        return row
    }

    /**
     * 滑块行（v3 .sz）。
     * 用系统 SeekBar 实现真实拖动，而非早前的「点一次跳一档」。
     */
    fun sliderRow(ctx: Context, title: String, init: Float, minVal: Float, maxVal: Float,
                  unit: String, onChange: (Float) -> Unit): LinearLayout {
        val row = rowCard(ctx)
        row.addView(TextView(ctx).apply {
            text = title
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textPri())
            layoutParams = LinearLayout.LayoutParams(Display.dpInt(ctx, 78f),
                LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        val v0 = init.coerceIn(minVal, maxVal)
        val valTv = TextView(ctx).apply {
            text = fmt(v0, unit)
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textSec())
            minWidth = Display.dpInt(ctx, 44f)
            gravity = Gravity.END
        }
        val bar = SeekBar(ctx).apply {
            setMax(1000)
            if (Build.VERSION.SDK_INT >= 21) {
                progressTintList = android.content.res.ColorStateList.valueOf(Theme.pri2())
                thumbTintList = android.content.res.ColorStateList.valueOf(Theme.pri2())
            }
            progress = (((v0 - minVal) / (maxVal - minVal).coerceAtLeast(0.0001f)) * 1000)
                .toInt().coerceIn(0, 1000)
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    val v = minVal + (maxVal - minVal) * (p / 1000f)
                    valTv.text = fmt(v, unit)
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        row.addView(bar)
        row.addView(valTv)
        return row
    }

    private fun fmt(v: Float, unit: String): String =
        if (unit == "%" || unit == "dp") "${v.toInt()}$unit"
        else String.format("%.1f%s", v, unit)

    /** 34dp 圆形小按钮（列表行内操作） */
    fun miniBtn(ctx: Context, glyph: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = glyph
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textSec())
            gravity = Gravity.CENTER
            background = Theme.oval(if (Theme.isDark()) Theme.chipBase()
            else Theme.surface())
            val s = Display.dpInt(ctx, 34f)
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                marginStart = Display.dpInt(ctx, 6f)
            }
            setOnClickListener { onClick() }
        }

    /** 38dp 圆形按钮（卡片底部操作区，v3 .mini） */
    fun roundBtn(ctx: Context, glyph: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = glyph
            textSize = 14f
            setTextColor(Theme.textSec())
            gravity = Gravity.CENTER
            background = Theme.oval(if (Theme.isDark()) Theme.chipBase()
            else Theme.surface())
            val s = Display.dpInt(ctx, 38f)
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                marginEnd = Display.dpInt(ctx, 14f)
            }
            setOnClickListener { onClick() }
        }

    // =====================================================================
    // 分段 / 统计 / 提示
    // =====================================================================

    /** 分段控件（v3 .fseg / .seg） */
    fun segment(ctx: Context, items: List<String>, index: Int,
                onPick: (Int) -> Unit): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        background = Theme.rect(Theme.surface(), 12f, ctx, Theme.line())
        setPadding(Display.dpInt(ctx, 4f), Display.dpInt(ctx, 4f),
            Display.dpInt(ctx, 4f), Display.dpInt(ctx, 4f))
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(Display.dpInt(ctx, 18f), 0,
            Display.dpInt(ctx, 18f), Display.dpInt(ctx, 12f))
        layoutParams = lp
        items.forEachIndexed { i, t ->
            addView(TextView(ctx).apply {
                text = t
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (i == index) Color.WHITE else Theme.textSec())
                gravity = Gravity.CENTER
                background = if (i == index) Theme.grad(ctx, 9f)
                else Theme.rect(Color.TRANSPARENT, 9f, ctx)
                setPadding(0, Display.dpInt(ctx, 8f), 0, Display.dpInt(ctx, 8f))
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { onPick(i) }
            })
        }
    }

    /** 单张统计卡（v3 .lstat：最小高 76、圆角 14） */
    fun statCard(ctx: Context, label: String, value: String): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.rect(Theme.surface(), 14f, ctx, Theme.line())
            setPadding(Display.dpInt(ctx, 14f), Display.dpInt(ctx, 10f),
                Display.dpInt(ctx, 14f), Display.dpInt(ctx, 10f))
            minimumHeight = Display.dpInt(ctx, 76f)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                text = value
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.textPri())
            })
            addView(TextView(ctx).apply {
                text = label
                textSize = 10.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.textSec())
            })
        }

    /** 一排统计卡 */
    fun statCards(ctx: Context, items: List<Pair<String, String>>): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val n = items.size.coerceAtLeast(1)
            items.forEachIndexed { i, (label, value) ->
                addView(statCard(ctx, label, value).apply {
                    (layoutParams as LinearLayout.LayoutParams).apply {
                        if (i < n - 1) marginEnd = Display.dpInt(ctx, 6f)
                    }
                })
            }
        }

    /** 虚线空态提示框 */
    fun hintBox(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(Theme.textSec())
        setLineSpacing(Display.dp(ctx, 2f), 1.7f)
        setPadding(Display.dpInt(ctx, 14f), Display.dpInt(ctx, 13f),
            Display.dpInt(ctx, 14f), Display.dpInt(ctx, 13f))
        background = Theme.dashed(ctx, 13f)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, Display.dpInt(ctx, 9f))
        layoutParams = lp
    }

    /** 提示条：左侧 3px 主色竖条（v3 .tip） */
    fun tip(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(Theme.textSec())
        setLineSpacing(Display.dp(ctx, 2f), 1.7f)
        setPadding(Display.dpInt(ctx, 14f), Display.dpInt(ctx, 11f),
            Display.dpInt(ctx, 14f), Display.dpInt(ctx, 11f))
        background = Theme.tipBg(ctx)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, Display.dpInt(ctx, 12f), 0, Display.dpInt(ctx, 12f))
        layoutParams = lp
    }

    /** 次要说明文字（10.5px 三级色），常用于控件下方注解 */
    fun note(ctx: Context, text: String, padTopDp: Float = 6f): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 10.5f
            setTextColor(Theme.textTer())
            setPadding(Display.dpInt(ctx, 8f), Display.dpInt(ctx, padTopDp),
                Display.dpInt(ctx, 8f), 0)
        }

    // =====================================================================
    // 按钮
    // =====================================================================

    /** 主 / 次按钮（44dp 高、圆角 13） */
    fun button(ctx: Context, text: String, primary: Boolean,
               onClick: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 14f
        setTypeface(null, Typeface.BOLD)
        setTextColor(if (primary) Color.WHITE else Theme.textPri())
        gravity = Gravity.CENTER
        background = if (primary) Theme.grad(ctx, Theme.BTN_R)
        else Theme.rect(Theme.surface(), Theme.BTN_R, ctx, Theme.line())
        setOnClickListener { onClick() }
    }

    /** 双按钮行（保存 / 保存并运行 之类） */
    fun btnLine(ctx: Context, left: Pair<String, () -> Unit>,
                right: Pair<String, () -> Unit>, rightPrimary: Boolean = true)
            : LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, Display.dpInt(ctx, 14f), 0, 0)
        layoutParams = lp
        val h = Display.dpInt(ctx, Theme.BTN_H)
        addView(button(ctx, left.first, false, left.second).apply {
            layoutParams = LinearLayout.LayoutParams(0, h, 1f)
        })
        addView(button(ctx, right.first, rightPrimary, right.second).apply {
            layoutParams = LinearLayout.LayoutParams(0, h, 1f).apply {
                marginStart = Display.dpInt(ctx, 9f)
            }
        })
    }

    /** 胶囊按钮（v3 .pill） */
    /**
     * 圆形色点（可选中）。
     *
     * 抽出来的原因：分组配色、标签配色、悬浮按键配色三处都要"一排色点选一个"，
     * 各写一遍就会有三种不同的选中标记与尺寸（R-001 三次法则）。
     */
    fun colorDot(ctx: Context, color: Int, selected: Boolean,
                 sizeDp: Float = 30f, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = if (selected) "✓" else ""
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val d = Display.dpInt(ctx, sizeDp)
            layoutParams = LinearLayout.LayoutParams(d, d).apply {
                marginStart = Display.dpInt(ctx, 4f)
                marginEnd = Display.dpInt(ctx, 4f)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                if (selected) setStroke(Display.dpInt(ctx, 2f), Theme.textPri())
            }
            setOnClickListener { onClick() }
        }

    /**
     * 单行文本输入弹窗（统一组件）。
     *
     * 触发 R-001 三次法则：ScriptPage 里「重命名 / 新建分组 / 重命名分组」
     * 三处各抄了一遍完全相同的 EditText + 容器 + 空值校验，
     * 改一处样式要改三遍。收口后页面只提供标题与回调。
     *
     * @param onOk 返回 true 关闭弹窗；返回 false 保持打开（校验未通过）
     */
    fun inputDialog(ctx: Context, title: String, hint: String = "", initial: String = "",
                    okText: String = "确定", emptyMsg: String = "名称不能为空",
                    onOk: (String) -> Boolean) {
        val et = android.widget.EditText(ctx).apply {
            setText(initial)
            if (hint.isNotEmpty()) {
                this.hint = hint
                setHintTextColor(Theme.textTer())
            }
            setTextColor(Theme.textPri())
            textSize = 14f
            setSingleLine(true)
            if (initial.isNotEmpty()) setSelection(initial.length)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Display.dpInt(ctx, 20f), Display.dpInt(ctx, 12f),
                Display.dpInt(ctx, 20f), 0)
            addView(et)
        }
        Ui.dialog(ctx, title).body(box)
            .negative("取消")
            .positive(okText) {
                val v = et.text.toString().trim()
                if (v.isEmpty()) { Ui.toast(ctx, emptyMsg); false } else onOk(v)
            }.show()
    }

    fun pill(ctx: Context, text: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textSec())
            gravity = Gravity.CENTER
            background = Theme.rect(Theme.surface(), 10f, ctx, Theme.line())
            setPadding(Display.dpInt(ctx, 12f), Display.dpInt(ctx, 6f),
                Display.dpInt(ctx, 12f), Display.dpInt(ctx, 6f))
            setOnClickListener { onClick() }
        }
}
