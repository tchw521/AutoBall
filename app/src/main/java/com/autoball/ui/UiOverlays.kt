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
 * 浮层：弹出菜单 / 变量提示 / 动作宫格 / 版本条 / Toast / 气泡。
 *
 * 从 Ui.kt 拆出：原文件 1237 行、混了四类职责，改一处要在巨型文件里翻找。
 * 按常驻需求 R-001 收口为按职责分离的独立组件库。
 */
object UiOverlays {

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

    // =====================================================================
    // 变量提示框（v3 .vtip：214dp 宽，深色石材质感，层级 105）
    //
    // 在输入框旁列出可插入的变量，点一下即写入。
    // =====================================================================

    /**
     * @param target 点选变量后回填的输入框（可为空，仅展示）
     */
    fun varTip(ctx: Context, anchorView: View, target: EditText?,
               vars: List<Pair<String, String>>): PopupWindow {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#3F3939"), Color.parseColor("#2B2727"))).apply {
                cornerRadius = Display.dp(ctx, 15f)
                setStroke(1, Color.parseColor("#21FFFFFF"))
            }
            elevation = Display.dp(ctx, 12f)
        }
        box.addView(TextView(ctx).apply {
            text = "插入变量"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#EDE8E4"))
            setPadding(Display.dpInt(ctx, 14f), Display.dpInt(ctx, 12f),
                Display.dpInt(ctx, 14f), Display.dpInt(ctx, 8f))
        })
        box.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#1AFFFFFF"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })
        val pw = PopupWindow(box, Display.dpInt(ctx, 214f),
            ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        vars.forEach { (name, desc) ->
            box.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Display.dpInt(ctx, 14f), Display.dpInt(ctx, 8f),
                    Display.dpInt(ctx, 14f), Display.dpInt(ctx, 8f))
                setOnClickListener {
                    target?.let { et ->
                        val ins = "{$name}"
                        val pos = et.selectionStart.coerceAtLeast(0)
                        et.text.insert(pos, ins)
                    }
                    Ui.toast(ctx, "已插入 {$name}")
                    pw.dismiss()
                }
                addView(TextView(ctx).apply {
                    text = "{$name}"
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#7EA6FF"))
                })
                addView(TextView(ctx).apply {
                    text = desc
                    textSize = 10.5f
                    setTextColor(Color.parseColor("#A8A29A"))
                    setPadding(0, Display.dpInt(ctx, 2f), 0, 0)
                })
            })
        }
        if (vars.isEmpty()) {
            box.addView(TextView(ctx).apply {
                text = "还没有变量。用「设置变量」动作先存一个。"
                textSize = 11f
                setTextColor(Color.parseColor("#A8A29A"))
                setPadding(Display.dpInt(ctx, 14f), Display.dpInt(ctx, 12f),
                    Display.dpInt(ctx, 14f), Display.dpInt(ctx, 12f))
            })
        }
        // 底部开关行：本次不再提示
        box.addView(TextView(ctx).apply {
            text = "本次运行不再提示"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#A8A29A"))
            setPadding(Display.dpInt(ctx, 14f), Display.dpInt(ctx, 10f),
                Display.dpInt(ctx, 14f), Display.dpInt(ctx, 12f))
            setOnClickListener {
                AB.store.putBool("var_tip_off", true)
                pw.dismiss()
            }
        })
        box.scaleX = 0.94f; box.scaleY = 0.94f; box.alpha = 0f
        pw.showAsDropDown(anchorView, 0, Display.dpInt(ctx, 4f))
        box.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200).start()
        return pw
    }

    // =====================================================================
    // 三点菜单（v3 .script-menu：180dp 宽，米白卡片，右上弹出）
    // =====================================================================

    fun menu(ctx: Context, anchorView: View, items: List<Pair<String, Boolean>>,
             onPick: (Int) -> Unit): PopupWindow {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#FFFFFF"), Color.parseColor("#F7F5F2"))).apply {
                cornerRadius = Display.dp(ctx, 15f)
                setStroke(1, Color.parseColor("#17000000"))
            }
            elevation = Display.dp(ctx, 12f)
            setPadding(Display.dpInt(ctx, 5f), Display.dpInt(ctx, 5f),
                Display.dpInt(ctx, 5f), Display.dpInt(ctx, 5f))
        }
        val pw = PopupWindow(box, Display.dpInt(ctx, 180f),
            ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        items.forEachIndexed { i, (t, danger) ->
            box.addView(TextView(ctx).apply {
                text = t
                textSize = 12.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (danger) Color.parseColor("#E5484D")
                else Color.parseColor("#2E2A2A"))
                setPadding(Display.dpInt(ctx, 10f), Display.dpInt(ctx, 9f),
                    Display.dpInt(ctx, 10f), Display.dpInt(ctx, 9f))
                background = Theme.rect(Color.TRANSPARENT, 10f, ctx)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, Display.dpInt(ctx, 1f), 0, Display.dpInt(ctx, 1f))
                layoutParams = lp
                setOnClickListener { onPick(i); pw.dismiss() }
            })
        }
        pw.showAsDropDown(anchorView, 0, -Display.dpInt(ctx, 8f))
        return pw
    }

    // =====================================================================
    // 动作宫格（v3 .agrid：4 列，每格图标 + 名称）
    // =====================================================================

    /** 4 列宫格；items 为「名称 + 说明」 */
    fun actionGrid(ctx: Context, items: List<Pair<String, String>>,
                   onPick: (Int) -> Unit): LinearLayout {
        val grid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        items.chunked(4).forEach { rowItems ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            rowItems.forEachIndexed { ci, (name, _) ->
                val idx = items.indexOfFirst { it.first == name }
                val cell = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = Theme.rect(Theme.surface2(), 14f, ctx, Theme.line())
                    setPadding(Display.dpInt(ctx, 2f), Display.dpInt(ctx, 10f),
                        Display.dpInt(ctx, 2f), Display.dpInt(ctx, 8f))
                    setOnClickListener { onPick(idx) }
                }
                cell.addView(TextView(ctx).apply {
                    text = name.take(1)
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = Theme.gradOval()
                    layoutParams = LinearLayout.LayoutParams(
                        Display.dpInt(ctx, 28f), Display.dpInt(ctx, 28f))
                })
                cell.addView(TextView(ctx).apply {
                    text = name
                    textSize = 10.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Theme.textPri())
                    gravity = Gravity.CENTER
                    setSingleLine(true)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, Display.dpInt(ctx, 5f), 0, 0)
                })
                row.addView(cell, LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (ci > 0) marginStart = Display.dpInt(ctx, 9f)
                    bottomMargin = Display.dpInt(ctx, 9f)
                })
            }
            grid.addView(row)
        }
        return grid
    }

    // =====================================================================
    // 底部版本条（v3 .verbar：26dp 高，毛玻璃，点击看更新日志）
    // =====================================================================

    fun versionBar(ctx: Context, version: String, desc: String,
                   onClick: () -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#6B140A0A"))
            setPadding(Display.dpInt(ctx, 10f), 0, Display.dpInt(ctx, 10f), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Display.dpInt(ctx, 26f))
            setOnClickListener { onClick() }
            addView(TextView(ctx).apply {
                text = version
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#D6CBC4"))
            })
            addView(TextView(ctx).apply {
                text = desc
                textSize = 10f
                setTextColor(Color.parseColor("#B9AEA7"))
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(Display.dpInt(ctx, 7f), 0, 0, 0)
            })
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
}
