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
import com.autoball.core.util.Perf

/**
 * 底部半框 / 气泡帮助 / 页标题 / 说明文字。
 *
 * 从 Ui.kt 拆出：原文件 1237 行、混了四类职责，改一处要在巨型文件里翻找。
 * 按常驻需求 R-001 收口为按职责分离的独立组件库。
 */
object UiSheets {

    // =====================================================================
    // 底部半框（v3：占屏幕 1/4，右上角圆形关闭，用于「新建脚本」这类入口选择）
    // =====================================================================

    class SheetBuilder(private val ctx: Context, private val title: String) {

        private var body: View? = null
        private var closeable = true
        /** 是否上抬到导航栏之上（默认 true：半框不遮挡导航） */
        private var aboveNav = true

        fun body(v: View) = apply { body = v }
        /** 是否显示右上角圆形关闭按钮 */
        fun closeable(v: Boolean) = apply { closeable = v }
        /** 少数需要真正贴底的半框（如确认类）可关闭避让 */
        fun aboveNav(v: Boolean) = apply { aboveNav = v }

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

            // 液态玻璃面板：半透明而非实色，顶部圆角 26dp
            //
            // 关键：不能是不透明实色——设计稿的半框要能透出底层界面的模糊光，
            // 否则会盖住背景、也盖住导航栏，看起来像一块贴片。
            wrap.background = glassSheet(ctx)

            dlg = AlertDialog.Builder(ctx).setView(wrap).setCancelable(true).create()
            dlg.show()
            dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dlg.window?.setGravity(Gravity.BOTTOM)
            dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            // 贴在导航栏上方：否则半框会压住导航，用户既看不到导航、
            // 也没法直接切页。Gravity.BOTTOM 下 y 为正即向上偏移。
            if (aboveNav) {
                // 上抬到导航栏之上。Gravity.BOTTOM 下 y 为正即向上偏移。
                val a = dlg.window?.attributes
                if (a != null) {
                    a.y = Display.dpInt(ctx, LiquidNavView.heightDp() +
                        LiquidNavView.BOTTOM_MARGIN_DP)
                    dlg.window?.attributes = a
                }
                // 关键：清掉背景压暗。AlertDialog 默认 dim 会把底层界面与导航栏
                // 一起压暗，视觉上等同于"遮住"了导航——即便几何位置已经错开。
                dlg.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                dlg.window?.setDimAmount(0f)
            }
            // Android 12+ 背后的真实模糊，配合半透明形成玻璃感；
            // 低端机（lowBlur）下跳过——实时模糊开销很高
            if (Build.VERSION.SDK_INT >= 31 && !Perf.lowBlur()) {
                runCatching { dlg.window?.setBackgroundBlurRadius(28) }
            }
            // 入场：底部滑入 + 轻微上浮
            wrap.translationY = Display.dp(ctx, 48f)
            wrap.alpha = 0f
            wrap.animate().translationY(0f).alpha(1f).setDuration(260).start()
            return dlg
        }
    }

    fun sheet(ctx: Context, title: String): SheetBuilder = SheetBuilder(ctx, title)

    /**
     * 液态玻璃面板底。
     *
     * 四段渐变营造"上薄下厚"的玻璃厚度感，颜色带 alpha 让底层界面透出来；
     * 顶缘一条 1px 高光模拟弧面折射。lowBlur 下改为纯半透明，不做渐变。
     */
    private fun glassSheet(ctx: Context): GradientDrawable {
        val dark = Theme.isDark()
        val grad = if (Perf.lowBlur()) {
            GradientDrawable().apply {
                setColor(if (dark) 0xF2201C36.toInt() else 0xF8F7F5FF.toInt())
            }
        } else {
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                if (dark) intArrayOf(
                    0xE6242040.toInt(), 0xF01F1A34.toInt(),
                    0xF41B1730.toInt(), 0xFA171326.toInt())
                else intArrayOf(
                    0xE6FFFFFF.toInt(), 0xF2FBFAFF.toInt(),
                    0xF6F6F4FF.toInt(), 0xFAF2F0FF.toInt()))
        }
        grad.cornerRadii = floatArrayOf(
            Display.dp(ctx, 26f), Display.dp(ctx, 26f),
            Display.dp(ctx, 26f), Display.dp(ctx, 26f),
            0f, 0f, 0f, 0f)
        grad.setStroke(Display.dpInt(ctx, 1f),
            if (dark) 0x2EFFFFFF else 0x33000000.toInt())
        return grad
    }

    /**
     * 帮助气泡（统一组件）。
     *
     * 所有「?」按钮统一走这里——此前各处自己拼气泡，文案与圆角各不相同。
     * 气泡在锚点上方展开，超出屏幕时自动落到下方。
     */
    fun helpBubble(anchor: View, title: String, text: String) {
        val ctx = anchor.context
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(if (Theme.isDark()) 0xF21F1B33.toInt() else 0xFAFFFFFF.toInt())
                cornerRadius = Display.dp(ctx, 12f)
                setStroke(Display.dpInt(ctx, 1f), Theme.line())
            }
            setPadding(Display.dpInt(ctx, 12f), Display.dpInt(ctx, 10f),
                Display.dpInt(ctx, 12f), Display.dpInt(ctx, 10f))
            elevation = Display.dp(ctx, 10f)
        }
        box.addView(TextView(ctx).apply {
            this.text = title
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.pri2())
            setPadding(0, 0, 0, Display.dpInt(ctx, 4f))
        })
        box.addView(TextView(ctx).apply {
            this.text = text
            textSize = 11.5f
            setTextColor(Theme.textSec())
            setLineSpacing(Display.dp(ctx, 2f), 1.6f)
        })
        val pw = android.widget.PopupWindow(box,
            Display.dpInt(ctx, 236f),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true)
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.isOutsideTouchable = true
        pw.elevation = Display.dp(ctx, 10f)
        pw.showAsDropDown(anchor, -Display.dpInt(ctx, 150f), -Display.dpInt(ctx, 8f))
    }

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
}
