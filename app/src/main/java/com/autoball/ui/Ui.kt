package com.autoball.ui

import android.app.AlertDialog
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
import com.autoball.core.util.Display

/**
 * 通用 UI 组件库（对齐 UI 设计方案 v3）。
 *
 * 统一收口：弹窗 / Toast / 气泡 / 开关 / 数值内联 / 弹出小菜单 / 卡片 / 徽标 / 多选操作条。
 * 目的：避免各页散落硬编码，保证层级栈、圆角、渐变方向一致。
 */
object Ui {

    // =====================================================================
    // 底部半框（v3：占屏幕 1/4，右上角圆形关闭，用于「新建脚本」这类入口选择）
    // =====================================================================

    class SheetBuilder(private val ctx: Context, private val title: String) {

        private var body: View? = null
        private var closeable = true

        fun body(v: View) = apply { body = v }
        /** 是否显示右上角圆形关闭按钮 */
        fun closeable(v: Boolean) = apply { closeable = v }

        fun show(): AlertDialog {
            val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

            // ---- 头：标题 + 右上角圆形关闭 ----
            val head = FrameLayout(ctx).apply {
                setPadding(Display.dpInt(ctx, 18f), Display.dpInt(ctx, 16f),
                    Display.dpInt(ctx, 14f), Display.dpInt(ctx, 8f))
            }
            head.addView(TextView(ctx).apply {
                text = title
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.textPri())
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                }
            })
            var dlg: AlertDialog? = null
            if (closeable) {
                head.addView(TextView(ctx).apply {
                    text = "✕"
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Theme.textSec())
                    gravity = Gravity.CENTER
                    background = Theme.bubbleRound(ctx, Theme.surface2())
                    val s = Display.dpInt(ctx, 28f)
                    layoutParams = FrameLayout.LayoutParams(s, s).apply {
                        gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    }
                    setOnClickListener { dlg?.dismiss() }
                })
            }
            wrap.addView(head)

            // ---- 体 ----
            body?.let {
                wrap.addView(it, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT))
            }

            // 底部安全留白
            wrap.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    Display.dpInt(ctx, 10f))
            })

            // 玻璃面板底：顶部圆角 24dp
            val gd = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                if (Theme.isDark()) intArrayOf(Theme.surface(), Theme.bg1())
                else intArrayOf(Color.WHITE, Theme.surface2())).apply {
                cornerRadii = floatArrayOf(
                    Display.dp(ctx, 24f), Display.dp(ctx, 24f),
                    Display.dp(ctx, 24f), Display.dp(ctx, 24f),
                    0f, 0f, 0f, 0f)
                setStroke(1, Theme.line())
            }
            wrap.background = gd

            dlg = AlertDialog.Builder(ctx).setView(wrap).setCancelable(true).create()
            dlg.show()
            dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dlg.window?.setGravity(Gravity.BOTTOM)
            dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            // 入场：底部滑入 + 轻微上浮
            wrap.translationY = Display.dp(ctx, 48f)
            wrap.alpha = 0f
            wrap.animate().translationY(0f).alpha(1f).setDuration(260).start()
            return dlg
        }
    }

    fun sheet(ctx: Context, title: String): SheetBuilder = SheetBuilder(ctx, title)

    /** 页面大标题（各页统一，消除散落的硬编码 TextView） */
    fun pageTitle(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 20f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Theme.textPri())
        setPadding(Display.dpInt(ctx, 16f), Display.dpInt(ctx, 18f),
            Display.dpInt(ctx, 16f), Display.dpInt(ctx, 10f))
    }

    /** 弹窗内的多行说明块 */
    fun note(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 11.5f
        setTextColor(Theme.textSec())
        setLineSpacing(Display.dp(ctx, 3f), 1.4f)
        setPadding(Display.dpInt(ctx, 14f), Display.dpInt(ctx, 10f),
            Display.dpInt(ctx, 14f), Display.dpInt(ctx, 10f))
    }

    /** 底部半框的圆形图标选项行：图标 + 标题 + 说明（v3「新建脚本」三入口） */
    fun sheetOption(ctx: Context, icon: String, iconColor: Int, title: String,
                    desc: String, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(Display.dpInt(ctx, 14f), Display.dpInt(ctx, 4f),
                Display.dpInt(ctx, 14f), Display.dpInt(ctx, 4f))
            layoutParams = lp
            setPadding(Display.dpInt(ctx, 12f), Display.dpInt(ctx, 11f),
                Display.dpInt(ctx, 12f), Display.dpInt(ctx, 11f))
            background = Theme.bubble(ctx, Theme.surface2(), 14f)
            setOnClickListener { onClick() }
        }
        row.addView(TextView(ctx).apply {
            text = icon
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable(Theme.orientation(),
                intArrayOf(iconColor, iconColor)).apply {
                shape = GradientDrawable.OVAL
            }
            layoutParams = LinearLayout.LayoutParams(Display.dpInt(ctx, 36f),
                Display.dpInt(ctx, 36f))
        })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(ctx).apply {
            text = title
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textPri())
        })
        col.addView(TextView(ctx).apply {
            text = desc
            textSize = 11f
            setTextColor(Theme.textSec())
            setPadding(0, Display.dpInt(ctx, 2f), 0, 0)
        })
        row.addView(col, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = Display.dpInt(ctx, 12f)
        })
        return row
    }

    // =====================================================================
    // 弹窗（v3：302dp 宽、18dp 圆角、头/体/底三段、scale .94→1 弹性入场）
    // =====================================================================

    class DialogBuilder(private val ctx: Context, private val title: String) {

        private var body: View? = null
        private var positive: Pair<String, (() -> Boolean)?>? = null
        private var negative: Pair<String, (() -> Unit)?>? = null
        private var widthDp = 302f
        private var maxHeightRatio = 0.76f

        fun body(v: View) = apply { body = v }
        fun positive(text: String, onClick: (() -> Boolean)? = null) =
            apply { positive = text to onClick }
        fun negative(text: String, onClick: (() -> Unit)? = null) =
            apply { negative = text to onClick }
        fun width(dp: Float) = apply { widthDp = dp }
        fun maxHeight(ratio: Float) = apply { maxHeightRatio = ratio }

        fun show(): AlertDialog {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = Theme.dialogBg()
                (background as GradientDrawable).cornerRadius = Display.dp(ctx, 18f)
            }

            // 头
            box.addView(TextView(ctx).apply {
                text = title
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.textPri())
                setPadding(Display.dpInt(ctx, 16f), Display.dpInt(ctx, 14f),
                    Display.dpInt(ctx, 16f), Display.dpInt(ctx, 12f))
            })
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
                    val b = button(ctx, t, false)
                    b.setOnClickListener { cb?.invoke(); d?.dismiss() }
                    foot.addView(b, LinearLayout.LayoutParams(0,
                        Display.dpInt(ctx, 39f), 1f).apply {
                        if (pos != null) marginEnd = Display.dpInt(ctx, 9f)
                    })
                }
                pos?.let { (t, cb) ->
                    val b = button(ctx, t, true)
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

            // 限制最大高度
            val maxH = (ctx.resources.displayMetrics.heightPixels * maxHeightRatio).toInt()
            scroll.post {
                if (box.height > maxH) d?.window?.setLayout(w, maxH)
            }
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
              onValue: () -> Unit, onHelp: (() -> Unit)? = null): LinearLayout {
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
        if (onHelp != null) {
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
                setOnClickListener { onHelp.invoke() }
            })
        }
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

    // =====================================================================
    // 弹窗内下拉菜单（v3 .gpop，层级 100：压在弹窗之上、气泡之下）
    // =====================================================================

    /** 在锚点处弹出选项菜单；selectedIndex 为 -1 表示无选中 */
    fun popMenu(ctx: Context, anchor: View, items: List<String>,
                selectedIndex: Int, onPick: (Int) -> Unit): PopupWindow {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.dialogBg()
            (background as GradientDrawable).cornerRadius = Display.dp(ctx, 10f)
            setPadding(Display.dpInt(ctx, 4f), Display.dpInt(ctx, 4f),
                Display.dpInt(ctx, 4f), Display.dpInt(ctx, 4f))
        }
        items.forEachIndexed { i, t ->
            box.addView(TextView(ctx).apply {
                text = (if (i == selectedIndex) "✓ " else "　") + t
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (i == selectedIndex) Theme.pri2() else Theme.textPri())
                setPadding(Display.dpInt(ctx, 10f), Display.dpInt(ctx, 8f),
                    Display.dpInt(ctx, 10f), Display.dpInt(ctx, 8f))
                background = Theme.rect(
                    if (i == selectedIndex) Theme.surface2()
                    else android.graphics.Color.TRANSPARENT, 7f, ctx)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, Display.dpInt(ctx, 1f), 0, Display.dpInt(ctx, 1f))
                layoutParams = lp
                setOnClickListener { onPick(i) }
            })
        }
        val pw = PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            isFocusable = true
            elevation = Display.dp(ctx, 8f)
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
        pw.showAsDropDown(anchor, 0, Display.dpInt(ctx, 4f))
        return pw
    }

    fun dialog(ctx: Context, title: String): DialogBuilder = DialogBuilder(ctx, title)

    /** 通用按钮（v3 gbtn / gbtn.pri） */
    fun button(ctx: Context, text: String, primary: Boolean): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 13.5f
        setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(if (primary) Color.WHITE else Theme.textSec())
        background = if (primary) {
            GradientDrawable(Theme.orientation(), Theme.gradStops()).apply {
                cornerRadius = Display.dp(ctx, 12f)
            }
        } else {
            GradientDrawable().apply {
                cornerRadius = Display.dp(ctx, 12f)
                setColor(Theme.surface2())
                setStroke(1, Theme.line())
            }
        }
        isClickable = true
    }

    // =====================================================================
    // Toast（v3：永远最上，胶囊底 + 主色描边）
    // =====================================================================

    fun toast(ctx: Context, msg: String) {
        val tv = TextView(ctx).apply {
            text = msg
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textPri())
            setPadding(Display.dpInt(ctx, 14f), Display.dpInt(ctx, 9f),
                Display.dpInt(ctx, 14f), Display.dpInt(ctx, 9f))
            background = GradientDrawable().apply {
                cornerRadius = Display.dp(ctx, 14f)
                setColor(Theme.surface())
                setStroke(1, Theme.line2())
            }
        }
        Toast(ctx).apply {
            view = tv
            duration = Toast.LENGTH_SHORT
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, Display.dpInt(ctx, 104f))
        }.show()
    }

    // =====================================================================
    // 气泡提示（v3 gtip：222dp 宽、12dp 圆角、琥珀标题 + 正文）
    // =====================================================================

    /** 把气泡显示在 anchor 下方；返回句柄便于关闭 */
    fun tip(anchor: View, title: String, text: String): PopupWindow {
        val ctx = anchor.context
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(ctx).apply {
            this.text = title
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.warn())
            setPadding(0, 0, 0, Display.dpInt(ctx, 4f))
        })
        box.addView(TextView(ctx).apply {
            this.text = text
            textSize = 11f
            setTextColor(Theme.textPri())
            setLineSpacing(Display.dp(ctx, 3f), 1.05f)
        })
        val pad = Display.dpInt(ctx, 11f)
        box.setPadding(pad, pad, pad, pad)
        box.background = GradientDrawable().apply {
            cornerRadius = Display.dp(ctx, 12f)
            setColor(Theme.surface())
            setStroke(1, Theme.line2())
        }
        val pw = PopupWindow(box, Display.dpInt(ctx, 222f),
            ViewGroup.LayoutParams.WRAP_CONTENT, true)
        pw.isOutsideTouchable = true
        pw.elevation = Display.dp(ctx, 12f)
        pw.showAsDropDown(anchor, 0, Display.dpInt(ctx, 6f))
        return pw
    }

    // =====================================================================
    // 弹出小菜单（v3 gpop：13dp 圆角、选中项打勾）
    // =====================================================================

    fun popMenu(anchor: View, items: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val ctx = anchor.context
        val lpw = ListPopupWindow(ctx)
        lpw.anchorView = anchor
        lpw.setDropDownGravity(Gravity.START)
        lpw.width = Display.dpInt(ctx, 132f)
        val bg = GradientDrawable().apply {
            cornerRadius = Display.dp(ctx, 13f)
            setColor(Theme.surface())
            setStroke(1, Theme.line2())
        }
        lpw.setBackgroundDrawable(bg)
        lpw.setAdapter(object : BaseAdapter() {
            override fun getCount(): Int = items.size
            override fun getItem(i: Int): String = items[i]
            override fun getItemId(i: Int): Long = i.toLong()
            override fun getView(i: Int, v: View?, parent: ViewGroup?): View {
                val tv = (v as? TextView) ?: TextView(ctx)
                tv.text = if (i == selected) "${items[i]}  ✓" else items[i]
                tv.textSize = 12.5f
                setTypefaceSafe(tv, if (i == selected) Typeface.BOLD else Typeface.NORMAL)
                tv.setTextColor(if (i == selected) Theme.pri2() else Theme.textPri())
                tv.setPadding(Display.dpInt(ctx, 12f), Display.dpInt(ctx, 9f),
                    Display.dpInt(ctx, 12f), Display.dpInt(ctx, 9f))
                return tv
            }
        })
        lpw.setOnItemClickListener { _, _, pos, _ -> onPick(pos); lpw.dismiss() }
        lpw.show()
    }

    private fun setTypefaceSafe(tv: TextView, style: Int) {
        tv.setTypeface(null, style)
    }

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
                setOnClickListener { tip(this, "说明", help) }
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

    // =====================================================================
    // 小部件：分组气泡 / 徽标 / 标签 / 分区标题 / 胶囊按钮
    // =====================================================================

    /** 分组气泡（v3 bub c1..c7 / all / add） */
    fun bubbleChip(ctx: Context, text: String, colorIdx: Int, active: Boolean): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = Display.dpInt(ctx, 74f)
            setTextColor(if (active) Color.WHITE else Theme.groupInk(colorIdx))
            setPadding(Display.dpInt(ctx, 9f), Display.dpInt(ctx, 4f),
                Display.dpInt(ctx, 9f), Display.dpInt(ctx, 4f))
            background = if (active) {
                GradientDrawable(Theme.orientation(), Theme.gradStops()).apply {
                    cornerRadius = Display.dp(ctx, 9f)
                }
            } else {
                GradientDrawable().apply {
                    cornerRadius = Display.dp(ctx, 9f)
                    setColor(Theme.groupTint(colorIdx))
                    setStroke(1, Theme.groupInk(colorIdx))
                }
            }
        }

    /** 脚本卡片徽标（JS / 录制 两种渐变） */
    fun badge(ctx: Context, text: String, rec: Boolean): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 11f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = GradientDrawable(Theme.orientation(),
            if (rec) intArrayOf(Theme.ok(), Color.parseColor("#0E9F5D"))
            else intArrayOf(Theme.pri2(), Color.parseColor("#5B8DEF"))
        ).apply { cornerRadius = Display.dp(ctx, 12f) }
        layoutParams = LinearLayout.LayoutParams(Display.dpInt(ctx, 40f),
            Display.dpInt(ctx, 40f))
    }

    /** 小标签（默认 / 槽位） */
    fun tag(ctx: Context, text: String, kind: Int): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 9.5f
        setTypeface(null, Typeface.BOLD)
        setTextColor(if (kind == 0) Theme.pri() else Theme.ok())
        setPadding(Display.dpInt(ctx, 5f), Display.dpInt(ctx, 2f),
            Display.dpInt(ctx, 5f), Display.dpInt(ctx, 2f))
        background = GradientDrawable().apply {
            cornerRadius = Display.dp(ctx, 5f)
            setColor(Theme.groupTint(if (kind == 0) 0 else 2))
        }
    }

    /** 分区标题 */
    fun section(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 11f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Theme.textTer())
        setPadding(Display.dpInt(ctx, 2f), Display.dpInt(ctx, 14f),
            Display.dpInt(ctx, 2f), Display.dpInt(ctx, 8f))
    }

    /** 行内胶囊按钮 */
    fun chip(ctx: Context, text: String, active: Boolean, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (active) Color.WHITE else Theme.textSec())
            setPadding(Display.dpInt(ctx, 11f), Display.dpInt(ctx, 5f),
                Display.dpInt(ctx, 11f), Display.dpInt(ctx, 5f))
            background = if (active) {
                GradientDrawable(Theme.orientation(), Theme.gradStops()).apply {
                    cornerRadius = Display.dp(ctx, 9f)
                }
            } else {
                GradientDrawable().apply {
                    cornerRadius = Display.dp(ctx, 9f)
                    setColor(Theme.surface())
                    setStroke(1, Theme.line())
                }
            }
            setOnClickListener { onClick() }
        }

    /** 圆形运行按钮 */
    fun runButton(ctx: Context, onClick: () -> Unit): TextView = TextView(ctx).apply {
        text = "▶"
        textSize = 15f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = GradientDrawable(Theme.orientation(), Theme.gradStops()).apply {
            shape = GradientDrawable.OVAL
        }
        layoutParams = LinearLayout.LayoutParams(Display.dpInt(ctx, 38f),
            Display.dpInt(ctx, 38f))
        setOnClickListener { onClick() }
    }

    /** 复选框 */
    fun check(ctx: Context, on: Boolean): TextView = TextView(ctx).apply {
        text = if (on) "✓" else ""
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = if (on) {
            GradientDrawable(Theme.orientation(), Theme.gradStops()).apply {
                cornerRadius = Display.dp(ctx, 7f)
            }
        } else {
            GradientDrawable().apply {
                cornerRadius = Display.dp(ctx, 7f)
                setColor(Color.TRANSPARENT)
                setStroke(2, Theme.line2())
            }
        }
        layoutParams = LinearLayout.LayoutParams(Display.dpInt(ctx, 22f),
            Display.dpInt(ctx, 22f))
    }

    /** 空态 / 说明文字 */
    fun hint(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 11.5f
        setTextColor(Theme.textTer())
        setLineSpacing(Display.dp(ctx, 3f), 1.35f)
        setPadding(Display.dpInt(ctx, 2f), Display.dpInt(ctx, 6f),
            Display.dpInt(ctx, 2f), Display.dpInt(ctx, 6f))
    }
}
