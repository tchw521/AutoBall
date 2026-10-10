package com.autoball.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.autoball.core.util.Display

/**
 * **动作类型选择——独立组件**。
 *
 * 此前它借用的是统一弹窗（[Ui.dialog] / [FloatDialog]），由此带来三个绕不开的约束：
 *
 * 1. **宽度被统一窗口尺寸锁死**。类型名长短差异很大（"点击" vs "运行JS代码"），
 *    按窗口比例定宽，小屏上长名会被截成省略号——用户看不出那是哪一项。
 * 2. **高度要跟着统一窗口走**，25 项塞进去必然要滚，而滚动容器又受外层约束。
 * 3. **带一层压暗遮罩**。选类型时用户往往正看着目标应用的界面在比对，
 *    压黑之后什么都看不见。
 *
 * 所以它改成自己的窗口：**尺寸完全由内容决定**（宽按最长类型名实测，
 * 高按屏幕比例并内部滚动），**不带遮罩**。
 *
 * 这不是"另造一套弹窗"——统一组件仍服务于所有常规弹窗；
 * 类型选择是**尺寸语义不同的那一类**（内容决定尺寸，而非窗口决定尺寸），
 * 强行统一只会两头不讨好。
 */
object ActionTypePicker {

    /** 卡片高度占屏幕高的比例；内容超出由内部滚动 */
    private const val HEIGHT_SCALE = 0.62f

    /** 卡片左右内边距 */
    private const val PAD_DP = 12f

    @Volatile private var ownView: View? = null
    @Volatile private var ownWm: WindowManager? = null
    @Volatile private var ownDlg: Dialog? = null

    fun isShowing(): Boolean = ownView != null || ownDlg != null

    /**
     * @param a         正在编辑的动作；选中后直接改写它的 type / optionLabel 与预设参数
     * @param floatMode 悬浮窗形态（有悬浮窗权限且当前不在应用内）走 WindowManager；
     *                  应用内走 Dialog。两者必须分开：悬浮窗场景下 Activity 可能在后台，
     *                  此时弹 AlertDialog 会抛「无法从后台启动」类异常。
     * @param onChange  类型已改变，外层表单需按新类型重排字段
     */
    fun show(ctx: Context, a: com.autoball.core.model.Action,
             floatMode: Boolean, onChange: () -> Unit) {
        dismiss()
        val app = ctx.applicationContext
        val body = buildBody(app, a, onChange) { dismiss() }

        if (floatMode && Display.canDrawOverlay(app)) {
            showAsWindow(app, body)
        } else {
            showAsDialog(ctx, body)
        }
    }

    fun dismiss() {
        runCatching { ownView?.let { ownWm?.removeView(it) } }
        ownView = null; ownWm = null
        runCatching { ownDlg?.dismiss() }
        ownDlg = null
    }

    // ---------- 悬浮窗形态：自己的窗口 ----------

    private fun showAsWindow(app: Context, body: View) {
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = Display.screenSize(app)
        val wDp = widthDp(app)

        val card = FrameLayout(app).apply {
            background = GradientDrawable().apply {
                setColor(Theme.surface())
                cornerRadius = Display.dp(app, 16f)
                setStroke(Display.dpInt(app, 1f), Theme.line())
            }
            elevation = Display.dp(app, 12f)
            isClickable = true      // 卡片自己吃掉点击，避免冒泡到外层被当成"点外面"
            addView(body, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT))
        }

        // 背后区域**完全透明**：不做压暗。选类型时要看着目标应用比对，
        // 压黑了就只剩一个孤零零的列表。点卡片外 = 返回（与关闭按钮同义）。
        val root = FrameLayout(app).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(card, FrameLayout.LayoutParams(
                Display.dpInt(app, wDp),
                (size.y * HEIGHT_SCALE).toInt(),
                Gravity.CENTER))
            setOnClickListener { dismiss() }
        }

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            // 不带 FLAG_DIM_BEHIND（否则背后被压暗）；加 NOT_FOCUSABLE 避免抢走输入法
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT)
        p.gravity = Gravity.TOP or Gravity.START
        if (runCatching { wm.addView(root, p) }.isSuccess) {
            ownView = root
            ownWm = wm
            card.scaleX = 0.94f; card.scaleY = 0.94f; card.alpha = 0f
            card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(160).start()
        }
    }

    // ---------- 应用内形态：Dialog ----------

    private fun showAsDialog(ctx: Context, body: View) {
        val d = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        val size = Display.screenSize(ctx)
        val root = FrameLayout(ctx).apply {
            setBackgroundColor(Color.TRANSPARENT)
            val card = FrameLayout(ctx).apply {
                background = GradientDrawable().apply {
                    setColor(Theme.surface())
                    cornerRadius = Display.dp(ctx, 16f)
                    setStroke(Display.dpInt(ctx, 1f), Theme.line())
                }
                isClickable = true
                addView(body, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT))
            }
            addView(card, FrameLayout.LayoutParams(
                Display.dpInt(ctx, widthDp(ctx)),
                (size.y * HEIGHT_SCALE).toInt(),
                Gravity.CENTER))
            setOnClickListener { d.dismiss() }
        }
        d.setContentView(root)
        d.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            // 关键：清掉默认的 FLAG_DIM_BEHIND，否则背后仍会被压暗一层
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0f)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT)
        }
        d.setOnDismissListener { ownDlg = null }
        runCatching { d.show() }
        ownDlg = d
    }

    // ---------- 内容 ----------

    /**
     * 卡片宽度（dp）：**按最长类型名的实测文字宽度**定，不是按窗口比例。
     *
     * 按比例会随机型变化，小屏上把长名截成省略号。用 Paint.measureText 实测
     * 最长那一项，再加内边距与 12% 余量，任何机型都刚好放得下。
     */
    private fun widthDp(ctx: Context): Float {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = Display.dp(ctx, 12.5f)
        }
        val maxText = com.autoball.core.model.ActionPreset.FLAT
            .maxOfOrNull { paint.measureText(it.label) } ?: 0f
        return (maxText / Display.density(ctx).coerceAtLeast(1f) + PAD_DP * 2) * 1.12f
    }

    private fun buildBody(ctx: Context, a: com.autoball.core.model.Action,
                          onChange: () -> Unit, dismiss: () -> Unit): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Display.dpInt(ctx, 10f), Display.dpInt(ctx, 10f),
                Display.dpInt(ctx, 10f), Display.dpInt(ctx, 8f))
        }
        box.addView(TextView(ctx).apply {
            text = "选择动作类型"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.textPri())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, Display.dpInt(ctx, 8f))
        })

        val listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        com.autoball.core.model.ActionPreset.FLAT.forEach { opt ->
            val btn = Ui.boxBtn(ctx, opt.label,
                opt.label == (a.optionLabel ?: a.type.label)) {
                a.type = opt.type
                a.optionLabel = opt.label
                opt.preset(a)
                onChange()
                dismiss()          // 选完即关，回到表单
            }
            listBox.addView(btn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Display.dpInt(ctx, 5f)
            })
        }

        // 列表独占剩余高度并内部滚动；用 weight 而非固定高度，
        // 卡片高度变化时不会把底部「返回」挤出可视区
        val scroll = ScrollView(ctx).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(listBox, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        box.addView(scroll)

        box.addView(TextView(ctx).apply {
            text = "坐标均为百分比，换机型与转屏都不会点偏；带预设的动作已填好常用参数。"
            textSize = 10.5f
            setTextColor(Theme.textTer())
            setPadding(0, Display.dpInt(ctx, 6f), 0, Display.dpInt(ctx, 6f))
        })
        box.addView(TextView(ctx).apply {
            text = "返回"
            textSize = 12.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.textSec())
            gravity = Gravity.CENTER
            background = Theme.rect(Theme.surface2(), 10f, ctx, Theme.line())
            setPadding(Display.dpInt(ctx, 10f), Display.dpInt(ctx, 9f),
                Display.dpInt(ctx, 10f), Display.dpInt(ctx, 9f))
            setOnClickListener { dismiss() }
        })
        return box
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
}
