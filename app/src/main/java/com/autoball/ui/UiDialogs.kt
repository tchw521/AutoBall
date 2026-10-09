package com.autoball.ui

import android.app.AlertDialog
import android.os.Build
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.autoball.AB
import com.autoball.core.util.Display

/**
 * 通用弹窗 / 紧凑弹窗行 / 表单行。
 *
 * 从 Ui.kt 拆出：原文件 1237 行、混了四类职责，改一处要在巨型文件里翻找。
 * 按常驻需求 R-001 收口为按职责分离的独立组件库。
 */
object UiDialogs {

    // =====================================================================
    // 弹窗（v3：302dp 宽、18dp 圆角、头/体/底三段、scale .94→1 弹性入场）
    // =====================================================================

    class DialogBuilder(private val ctx: Context, private val title: String) {

        private var body: View? = null
        private var positive: Pair<String, (() -> Boolean)?>? = null
        private var positiveColor: Int = 0
        private var negative: Pair<String, (() -> Unit)?>? = null
        private var widthDp = 302f
        private var maxHeightRatio = 0.76f
        /** 标题栏右侧的小动作（如「从屏幕测试找图…」） */
        private var trailing: Pair<String, () -> Unit>? = null

        /**
         * 弹窗就绪回调：(弹窗, 内容容器, 标题控件)。
         *
         * 用途：需要在**同一个弹窗内换页**的场景（如「编辑动作」→「选择动作类型」）。
         * 早前这类二级选择是另开一个 Dialog，两个 Dialog 争同一窗口层级，
         * 内层常被外层遮住——就地换页彻底避开这个问题，也保留了外层已填的表单内容。
         */
        // 容器用 ScrollView（弹窗体本身可滚动），换页时对它 removeAllViews 再 addView
        private var onReady: ((AlertDialog, android.widget.ScrollView, TextView) -> Unit)? = null

        fun onReady(cb: (AlertDialog, android.widget.ScrollView, TextView) -> Unit) =
            apply { onReady = cb }

        fun body(v: View) = apply { body = v }
        fun positive(text: String, onClick: (() -> Boolean)? = null) =
            apply { positive = text to onClick; positiveColor = 0 }
        /** 危险操作按钮：标红 */
        fun positiveDanger(text: String, onClick: (() -> Boolean)? = null) =
            apply { positive = text to onClick; positiveColor = Theme.danger() }
        fun negative(text: String, onClick: (() -> Unit)? = null) =
            apply { negative = text to onClick }
        /**
         * 标题栏右侧小动作。
         *
         * 自动精灵的运行条件弹窗在右上角放了「从屏幕测试找图…」——
         * 测试入口必须**同屏可见**，藏在菜单里用户根本发现不了。
         */
        fun trailing(text: String, onClick: () -> Unit) =
            apply { trailing = text to onClick }

        fun width(dp: Float) = apply { widthDp = dp }
        fun maxHeight(ratio: Float) = apply { maxHeightRatio = ratio }

        fun show(): AlertDialog {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = Theme.dialogBg()
                (background as GradientDrawable).cornerRadius = Display.dp(ctx, 18f)
            }

            // 头
            val tr = trailing
            if (tr == null) {
                box.addView(TextView(ctx).apply {
                    text = title
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Theme.textPri())
                    setPadding(Display.dpInt(ctx, 16f), Display.dpInt(ctx, 14f),
                        Display.dpInt(ctx, 16f), Display.dpInt(ctx, 12f))
                })
            } else {
                val head = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(Display.dpInt(ctx, 16f), Display.dpInt(ctx, 12f),
                        Display.dpInt(ctx, 10f), Display.dpInt(ctx, 10f))
                }
                head.addView(TextView(ctx).apply {
                    text = title
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Theme.textPri())
                    layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                head.addView(TextView(ctx).apply {
                    text = tr.first
                    textSize = 11f
                    setTextColor(Theme.pri2())
                    gravity = Gravity.CENTER
                    setPadding(Display.dpInt(ctx, 8f), Display.dpInt(ctx, 5f),
                        Display.dpInt(ctx, 8f), Display.dpInt(ctx, 5f))
                    background = Theme.rect(Theme.surface2(), 9f, ctx, Theme.line())
                    setOnClickListener { tr.second() }
                })
                box.addView(head)
            }
            val titleTv = (box.getChildAt(0) as? TextView)
                ?: ((box.getChildAt(0) as? LinearLayout)?.getChildAt(0) as? TextView)
                ?: TextView(ctx)
            box.addView(View(ctx).apply {
                setBackgroundColor(Theme.line())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })

            // 体（可滚动）
            val scroll = ScrollView(ctx).apply {
                isFillViewport = false
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            // 高度改用**窗口统一尺寸**：与悬浮窗口同一规格，横竖屏一致。
            // 早前用"屏高 × 比例（横屏再压到 0.62）"，
            // 于是同一个弹窗在横竖屏下高度不同、换了设备也不同，
            // 而且与悬浮窗口的高度对不上，视觉上像两套东西。
            val sz = Display.screenSize(ctx)
            val winH = com.autoball.float.FloatWindows.frameHeightPx(ctx)
            body?.let {
                it.setPadding(Display.dpInt(ctx, 6f), Display.dpInt(ctx, 4f),
                    Display.dpInt(ctx, 6f), Display.dpInt(ctx, 6f))
                scroll.addView(it, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            box.addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

            // 底
            var d: AlertDialog? = null
            val pos = positive
            val neg = negative
            if (pos != null || neg != null) {
                box.addView(View(ctx).apply {
                    setBackgroundColor(Theme.line())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1)
                })
                val foot = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(Display.dpInt(ctx, 14f), Display.dpInt(ctx, 11f),
                        Display.dpInt(ctx, 14f), Display.dpInt(ctx, 14f))
                }
                neg?.let { (t, cb) ->
                    val b = UiBits.button(ctx, t, false)
                    b.setOnClickListener { cb?.invoke(); d?.dismiss() }
                    foot.addView(b, LinearLayout.LayoutParams(0,
                        Display.dpInt(ctx, 39f), 1f).apply {
                        if (pos != null) marginEnd = Display.dpInt(ctx, 9f)
                    })
                }
                pos?.let { (t, cb) ->
                    val b = UiBits.button(ctx, t, true)
                    if (positiveColor != 0) {
                        b.background = Theme.rect(positiveColor, Theme.BTN_R, ctx)
                    }
                    b.setOnClickListener {
                        // 返回 false 表示校验未通过，保持弹窗不关闭
                        if (cb?.invoke() != false) d?.dismiss()
                    }
                    foot.addView(b, LinearLayout.LayoutParams(0,
                        Display.dpInt(ctx, 39f), 1f))
                }
                box.addView(foot)
            }

            d = AlertDialog.Builder(ctx).setView(box).setCancelable(true).create()
            d?.show()
            d?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val sw = ctx.resources.displayMetrics.widthPixels
            val w = kotlin.math.min(Display.dpInt(ctx, widthDp), (sw * 0.86f).toInt())
            d?.window?.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
            // 入场动画：scale .94 → 1 + 轻微上移
            box.scaleX = 0.94f
            box.scaleY = 0.94f
            box.alpha = 0f
            box.translationY = Display.dp(ctx, 8f)
            box.animate().scaleX(1f).scaleY(1f).alpha(1f).translationY(0f)
                .setDuration(240).setStartDelay(0).start()

            // 就绪回调：交给调用方做「就地换页」等后续操作
            onReady?.invoke(d!!, scroll, titleTv)

            // 限制最大高度：用窗口统一高度（见上方 winH），不再按比例算
            val maxH = winH
            scroll.post {
                if (box.height > maxH) d?.window?.setLayout(w, maxH)
            }
            // 转屏重排：Activity 若不重建（已声明 configChanges），
            // 这里跟着新屏幕重算宽高，否则弹窗会停在旧尺寸上被裁
            box.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int,
                                            ol: Int, ot: Int, or_: Int, ob: Int) {
                    val sz = Display.screenSize(ctx)
                    val nw = kotlin.math.min(Display.dpInt(ctx, widthDp),
                        (sz.x * 0.86f).toInt())
                    // 与悬浮窗口同高；屏幕实在放不下时才退回按比例
                    val nH = kotlin.math.min(winH, (sz.y * 0.9f).toInt())
                    val win = d?.window ?: return
                    if (v.height > nH) win.setLayout(nw, nH) else win.setLayout(nw,
                        ViewGroup.LayoutParams.WRAP_CONTENT)
                }
            })
            return d!!
        }

    }

    // =====================================================================
    // 紧凑弹窗行（v3 .adlg：206dp 宽；.adrow 高 23dp，行内 gap 5）
    //
    // 用于「添加动作 / 运行条件 / 重复 / 监听」这类字段密集的小弹窗，
    // 与通用弹窗（302dp、行高宽松）区分开。
    // =====================================================================

    /** .adrow：标签 + 值 + 问号。行高 23dp、圆角 6、左右 padding 5 */
    fun adRow(ctx: Context, label: String, value: String, set: Boolean,
              help: String? = null, onValue: () -> Unit): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Display.dpInt(ctx, 5f), 0, Display.dpInt(ctx, 5f), 0)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Display.dpInt(ctx, 23f))
            lp.setMargins(0, Display.dpInt(ctx, 1f), 0, Display.dpInt(ctx, 1f))
            layoutParams = lp
            setOnClickListener { onValue() }
        }
        row.addView(TextView(ctx).apply {
            text = label
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textSec())
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        // .adi / .adi.set
        row.addView(TextView(ctx).apply {
            text = value
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (set) Theme.pri2() else Theme.textTer())
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = Display.dpInt(ctx, 96f)
            setPadding(Display.dpInt(ctx, 7f), Display.dpInt(ctx, 2f),
                Display.dpInt(ctx, 7f), Display.dpInt(ctx, 2f))
            background = Theme.rect(
                if (set) Theme.surface2() else android.graphics.Color.TRANSPARENT,
                6f, ctx)
        })
        // .adq：17dp 圆形问号
        if (help != null) {
            row.addView(TextView(ctx).apply {
                text = "?"
                textSize = 9.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.textTer())
                gravity = Gravity.CENTER
                background = Theme.rect(Theme.surface2(), 9f, ctx)
                val sz = Display.dpInt(ctx, 17f)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    marginStart = Display.dpInt(ctx, 4f)
                }
                setOnClickListener { UiOverlays.tip(this, "说明", help) }
            })
        }
        return row
    }

    /**
     * 紧凑弹窗内的**开关行**（复选框 + 标签 + 问号）。
     *
     * 触发 R-001 三次法则：运行条件里有「快速搜图 / 条件反相 / 等待前检查 /
     * 重复检查直到成功」四项开关，各写一遍就是四份重复，样式还会走样。
     */
    fun adCheck(ctx: Context, label: String, on: Boolean, help: String?,
                onToggle: (Boolean) -> Unit): LinearLayout {
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
        var cur = on
        val box = UiBits.check(ctx, on)
        row.addView(box)
        (box.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.width = Display.dpInt(ctx, 15f); it.height = Display.dpInt(ctx, 15f)
        }
        row.addView(TextView(ctx).apply {
            text = label
            textSize = 11.5f
            setTextColor(if (on) Theme.textPri() else Theme.textSec())
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Display.dpInt(ctx, 6f)
            }
        })
        if (help != null) {
            row.addView(TextView(ctx).apply {
                text = "?"
                textSize = 9.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.textTer())
                gravity = Gravity.CENTER
                background = Theme.rect(Theme.surface2(), 9f, ctx)
                val sz = Display.dpInt(ctx, 17f)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    marginStart = Display.dpInt(ctx, 4f)
                }
                setOnClickListener { UiOverlays.tip(this, "说明", help) }
            })
        }
        row.setOnClickListener {
            cur = !cur
            // 直接改文字颜色比重建整行省事，也避免重建时丢失滚动位置
            UiBits.setCheck(box, cur)
            ((row.getChildAt(1)) as? TextView)?.setTextColor(
                if (cur) Theme.textPri() else Theme.textSec())
            onToggle(cur)
        }
        return row
    }

    /**
     * 紧凑弹窗内的**滑块行**（标签 + 数值 + 滑杆 + 问号）。
     *
     * 自动精灵的相似度是滑块（50–100）而不是离散选项——
     * 相似度需要微调，给四档固定值不够用。
     */
    fun adSlider(ctx: Context, label: String, value: Int, min: Int, max: Int,
                 suffix: String = "%", help: String?,
                 onChange: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Display.dpInt(ctx, 5f), 0, Display.dpInt(ctx, 5f), 0)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Display.dpInt(ctx, 25f))
            lp.setMargins(0, Display.dpInt(ctx, 1f), 0, Display.dpInt(ctx, 1f))
            layoutParams = lp
        }
        row.addView(TextView(ctx).apply {
            text = label
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textSec())
        })
        var cur = value.coerceIn(min, max)
        val num = TextView(ctx).apply {
            text = "$cur$suffix"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.pri2())
            setPadding(Display.dpInt(ctx, 6f), 0, Display.dpInt(ctx, 6f), 0)
        }
        row.addView(num)
        val bar = android.widget.SeekBar(ctx).apply {
            this.max = max - min
            progress = cur - min
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(bar)
        if (help != null) {
            row.addView(TextView(ctx).apply {
                text = "?"
                textSize = 9.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.textTer())
                gravity = Gravity.CENTER
                background = Theme.rect(Theme.surface2(), 9f, ctx)
                val sz = Display.dpInt(ctx, 17f)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    marginStart = Display.dpInt(ctx, 4f)
                }
                setOnClickListener { UiOverlays.tip(this, "说明", help) }
            })
        }
        bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                cur = p + min
                num.text = "$cur$suffix"
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) { onChange(cur) }
        })
        return row
    }

    /** .adsec：4dp 高的分组分隔（顶部细线） */
    fun adSec(ctx: Context): android.view.View = android.view.View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            Display.dpInt(ctx, 4f)).apply {
            setMargins(0, Display.dpInt(ctx, 3f), 0, Display.dpInt(ctx, 3f))
        }
        background = Theme.hairlineTop(ctx)
    }

    /** 紧凑弹窗内的数字输入框 */
    fun adNumber(ctx: Context, value: String, unit: String,
                 hint: String = ""): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Display.dpInt(ctx, 5f), Display.dpInt(ctx, 3f),
                Display.dpInt(ctx, 5f), Display.dpInt(ctx, 3f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        row.addView(TextView(ctx).apply {
            text = hint
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textSec())
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val et = EditText(ctx).apply {
            setText(value)
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textPri())
            gravity = Gravity.CENTER
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            background = Theme.rect(Theme.surface2(), 6f, ctx)
            setPadding(Display.dpInt(ctx, 8f), Display.dpInt(ctx, 3f),
                Display.dpInt(ctx, 8f), Display.dpInt(ctx, 3f))
            layoutParams = LinearLayout.LayoutParams(
                Display.dpInt(ctx, 56f), Display.dpInt(ctx, 26f))
        }
        row.addView(et)
        row.addView(TextView(ctx).apply {
            text = unit
            textSize = 10.5f
            setTextColor(Theme.textTer())
            setPadding(Display.dpInt(ctx, 4f), 0, 0, 0)
        })
        row.tag = et
        return row
    }

    /** 取回 adNumber 的输入值 */
    fun adNumberValue(row: LinearLayout): String =
        ((row.tag as? EditText)?.text?.toString() ?: "").trim()

    /** 紧凑弹窗内的文本输入框（整行） */
    fun adText(ctx: Context, value: String, hint: String): EditText =
        EditText(ctx).apply {
            setText(value)
            this.hint = hint
            textSize = 11.5f
            setTextColor(Theme.textPri())
            setHintTextColor(Theme.textTer())
            background = Theme.rect(Theme.surface2(), 6f, ctx)
            setPadding(Display.dpInt(ctx, 8f), Display.dpInt(ctx, 5f),
                Display.dpInt(ctx, 8f), Display.dpInt(ctx, 5f))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(Display.dpInt(ctx, 5f), Display.dpInt(ctx, 2f),
                Display.dpInt(ctx, 5f), Display.dpInt(ctx, 2f))
            layoutParams = lp
        }

    fun dialog(ctx: Context, title: String): DialogBuilder = DialogBuilder(ctx, title)

    /** 通用按钮（v3 gbtn / gbtn.pri） */

    // =====================================================================
    // 行 / 开关 / 数值内联（v3 grow / gsw / gnum）
    // =====================================================================

    /** 一行：左标题 + 右值（可带问号气泡） */
    fun row(ctx: Context, label: String, value: String, help: String? = null,
            onClick: (() -> Unit)? = null): LinearLayout {
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Display.dpInt(ctx, 10f), Display.dpInt(ctx, 11f),
                Display.dpInt(ctx, 10f), Display.dpInt(ctx, 11f))
            background = Theme.bubble(ctx, Color.TRANSPARENT, 11f)
        }
        r.addView(TextView(ctx).apply {
            text = label
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textPri())
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        r.addView(TextView(ctx).apply {
            text = value
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textSec())
        })
        if (help != null) {
            r.addView(TextView(ctx).apply {
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
                setOnClickListener { UiOverlays.tip(this, "说明", help) }
            })
        }
        onClick?.let { cb ->
            r.setOnClickListener { cb() }
            r.isClickable = true
        }
        return r
    }

    /** 开关行 */
    fun switchRow(ctx: Context, label: String, init: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        var on = init
        val sw = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(Display.dpInt(ctx, 42f),
                Display.dpInt(ctx, 24f))
            background = GradientDrawable().apply {
                cornerRadius = Display.dp(ctx, 12f)
                setColor(if (on) Theme.pri2() else Theme.line2())
            }
        }
        val knob = View(ctx).apply {
            background = Theme.bubbleRound(ctx, Color.WHITE)
        }
        sw.addView(knob, FrameLayout.LayoutParams(Display.dpInt(ctx, 18f),
            Display.dpInt(ctx, 18f)).apply {
            leftMargin = if (on) Display.dpInt(ctx, 21f) else Display.dpInt(ctx, 3f)
            topMargin = Display.dpInt(ctx, 3f)
        })
        val r = row(ctx, label, if (on) "开" else "关")
        r.addView(sw)
        r.setOnClickListener {
            on = !on
            sw.background = GradientDrawable().apply {
                cornerRadius = Display.dp(ctx, 12f)
                setColor(if (on) Theme.pri2() else Theme.line2())
            }
            (knob.layoutParams as FrameLayout.LayoutParams).leftMargin =
                if (on) Display.dpInt(ctx, 21f) else Display.dpInt(ctx, 3f)
            knob.requestLayout()
            onChange(on)
        }
        return r
    }

    /** 数值内联（v3 gnum） */
    fun numEdit(ctx: Context, value: String, hint: String = ""): EditText = EditText(ctx).apply {
        setText(value)
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or
                InputType.TYPE_NUMBER_FLAG_DECIMAL
        setTextColor(Theme.textPri())
        setHintTextColor(Theme.textTer())
        textSize = 12.5f
        setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(Display.dpInt(ctx, 8f), Display.dpInt(ctx, 6f),
            Display.dpInt(ctx, 8f), Display.dpInt(ctx, 6f))
        background = GradientDrawable().apply {
            cornerRadius = Display.dp(ctx, 8f)
            setColor(Theme.surface2())
            setStroke(1, Theme.line2())
        }
    }

    fun edit(ctx: Context, value: String, hint: String): EditText = EditText(ctx).apply {
        setText(value)
        this.hint = hint
        setTextColor(Theme.textPri())
        setHintTextColor(Theme.textTer())
        textSize = 13f
        setPadding(Display.dpInt(ctx, 10f), Display.dpInt(ctx, 8f),
            Display.dpInt(ctx, 10f), Display.dpInt(ctx, 8f))
        background = GradientDrawable().apply {
            cornerRadius = Display.dp(ctx, 8f)
            setColor(Theme.surface2())
            setStroke(1, Theme.line2())
        }
    }
}
