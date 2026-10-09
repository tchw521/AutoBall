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
 * 小部件：按钮 / 分组气泡 / 徽标 / 标签 / 分区标题 / 胶囊。
 *
 * 从 Ui.kt 拆出：原文件 1237 行、混了四类职责，改一处要在巨型文件里翻找。
 * 按常驻需求 R-001 收口为按职责分离的独立组件库。
 */
object UiBits {

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

    /** 就地切换勾选框外观（避免重建整行） */
    fun setCheck(v: TextView, on: Boolean) {
        v.text = if (on) "✓" else ""
        v.background = if (on) {
            GradientDrawable(Theme.orientation(), Theme.gradStops()).apply {
                cornerRadius = Display.dp(v.context, 7f)
            }
        } else {
            GradientDrawable().apply {
                cornerRadius = Display.dp(v.context, 7f)
                setColor(Color.TRANSPARENT)
                setStroke(2, Theme.line2())
            }
        }
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
