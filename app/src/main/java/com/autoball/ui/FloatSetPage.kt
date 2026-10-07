package com.autoball.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.autoball.AB
import com.autoball.core.model.BallSlot
import com.autoball.core.model.Script
import com.autoball.core.util.Display
import com.autoball.float.FloatManager

/**
 * 悬浮设置页（v3 #p-float）：两段切换 + 预览 + 手势绑定 + 皮肤。
 *
 * 一比一对齐：
 * - 自带状态栏（与 JS 页一致：整页沉浸式）
 * - .subbar：34dp 返回 .bk + h2
 * - .fseg：两段（悬浮球 / 悬浮窗）
 * - 球页：.ballprev 132dp 预览（含底部虚线关闭区）+ `.gest` 四手势行 + `.sz` 滑块行
 * - 窗页：`.skins` 两列六套皮肤 + 尺寸透明滑块 + 开关行
 */
class FloatSetPage(context: Context, private val host: PageHost) : FrameLayout(context) {

    private var pane = 0
    private lateinit var segRow: LinearLayout
    private lateinit var ballPane: LinearLayout
    private lateinit var winPane: LinearLayout

    init { build() }

    private fun build() {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(statusBar())

        val sub = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Display.dpInt(context, 18f), Display.dpInt(context, 2f),
                Display.dpInt(context, 18f), Display.dpInt(context, 10f))
        }
        sub.addView(TextView(context).apply {
            text = "‹"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textSec())
            gravity = Gravity.CENTER
            background = Theme.rect(Theme.surface(), 11f, context, Theme.line())
            val s = Display.dpInt(context, 34f)
            layoutParams = LinearLayout.LayoutParams(s, s)
            setOnClickListener { host.showPage(4) }
        })
        sub.addView(TextView(context).apply {
            text = "悬浮设置"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textPri())
            setPadding(Display.dpInt(context, 10f), 0, 0, 0)
        })
        root.addView(sub)

        segRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Theme.rect(Theme.surface(), 12f, context, Theme.line())
            setPadding(Display.dpInt(context, 4f), Display.dpInt(context, 4f),
                Display.dpInt(context, 4f), Display.dpInt(context, 4f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(Display.dpInt(context, 18f), 0,
                Display.dpInt(context, 18f), Display.dpInt(context, 12f))
            layoutParams = lp
        }
        root.addView(segRow)
        renderSeg()

        ballPane = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        winPane = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val holder = FrameLayout(context).apply {
            addView(ballPane)
            addView(winPane.apply { visibility = View.GONE })
        }
        val sc = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        sc.addView(holder)
        root.addView(sc, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        renderBall()
        renderWin()
    }

    private fun statusBar(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(Display.dpInt(context, 18f), Display.dpInt(context, 8f),
            Display.dpInt(context, 18f), 0)
        addView(TextView(context).apply {
            text = ""
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textPri())
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(context).apply {
            text = "🔋"
            textSize = 12f
            setTextColor(Theme.textPri())
        })
    }

    private fun renderSeg() {
        segRow.removeAllViews()
        listOf("悬浮球", "悬浮窗").forEachIndexed { i, t ->
            segRow.addView(TextView(context).apply {
                text = t
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (i == pane) Color.WHITE else Theme.textSec())
                gravity = Gravity.CENTER
                background = if (i == pane) Theme.grad(context, 9f)
                else Theme.rect(Color.TRANSPARENT, 9f, context)
                setPadding(0, Display.dpInt(context, 8f), 0, Display.dpInt(context, 8f))
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    pane = i
                    ballPane.visibility = if (i == 0) View.VISIBLE else View.GONE
                    winPane.visibility = if (i == 1) View.VISIBLE else View.GONE
                    renderSeg()
                }
            })
        }
    }

    // ---------- 悬浮球 ----------

    private fun renderBall() {
        ballPane.removeAllViews()
        ballPane.setPadding(Display.dpInt(context, 18f), 0,
            Display.dpInt(context, 18f), Display.dpInt(context, 92f))

        // .ballprev：132dp 预览 + 底部虚线关闭区
        val prev = FrameLayout(context).apply {
            background = Theme.rect(Color.parseColor(if (Theme.isDark()) "#1E1A33" else "#F0F1F8"),
                16f, context, Theme.line())
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                Display.dpInt(context, 132f))
            lp.setMargins(0, 0, 0, Display.dpInt(context, 12f))
            layoutParams = lp
        }
        val ball = TextView(context).apply {
            text = "●"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Theme.gradOval()
            val s = Display.dpInt(context, 48f)
            layoutParams = FrameLayout.LayoutParams(s, s).apply {
                gravity = Gravity.CENTER
            }
        }
        prev.addView(ball)
        prev.addView(TextView(context).apply {
            text = "拖到此处关闭"
            textSize = 9.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F87171"))
            gravity = Gravity.CENTER
            background = Theme.closeZone(context)
            layoutParams = FrameLayout.LayoutParams(Display.dpInt(context, 86f),
                Display.dpInt(context, 36f)).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = Display.dpInt(context, 8f)
            }
        })
        ballPane.addView(prev)

        ballPane.addView(TextView(context).apply {
            text = "拖动可移动位置，拖到底部虚线区即关闭悬浮球。"
            textSize = 10.5f
            setTextColor(Theme.textTer())
            setPadding(0, 0, 0, Display.dpInt(context, 10f))
        })

        // .sec + 四手势
        ballPane.addView(sec("悬浮球手势"))
        BallSlot.values().filter { it != BallSlot.NONE }.forEach { slot ->
            ballPane.addView(gestRow(slot))
        }

        // 开关
        ballPane.addView(sec("行为"))
        ballPane.addView(switchRow("常驻显示", AB.store.getBool("float_persistent", true)) {
            AB.store.putBool("float_persistent", it)
            if (it) runCatching { FloatManager.showBall(context.applicationContext) }
            else FloatManager.hideBall()
        })
        ballPane.addView(switchRow("自动贴边", AB.store.getBool("ball_snap_edge", true)) {
            AB.store.putBool("ball_snap_edge", it)
        })
        ballPane.addView(switchRow("闲置半透明", AB.store.getBool("ball_idle_alpha", true)) {
            AB.store.putBool("ball_idle_alpha", it)
        })

        // .sz 滑块
        ballPane.addView(sliderRow("大小",
            AB.store.getFloat("ball_size", 48f), 36f, 72f, "dp") {
            AB.store.putFloat("ball_size", it)
        })
        ballPane.addView(sliderRow("闲置透明度",
            AB.store.getFloat("ball_alpha", 55f), 20f, 100f, "%") {
            AB.store.putFloat("ball_alpha", it)
        })
    }

    private fun gestRow(slot: BallSlot): LinearLayout {
        val bound = AB.store.all().firstOrNull { it.slot == slot }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.rect(Theme.surface(), 13f, context, Theme.line())
            setPadding(Display.dpInt(context, 13f), Display.dpInt(context, 11f),
                Display.dpInt(context, 13f), Display.dpInt(context, 11f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, Display.dpInt(context, 8f))
            layoutParams = lp

            addView(TextView(context).apply {
                text = when (slot) {
                    BallSlot.SINGLE -> "1"
                    BallSlot.DOUBLE -> "2"
                    BallSlot.TRIPLE -> "3"
                    else -> "⏱"
                }
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = Theme.gradOval()
                layoutParams = LinearLayout.LayoutParams(
                    Display.dpInt(context, 29f), Display.dpInt(context, 29f))
            })
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(context).apply {
                text = slot.label
                textSize = 13.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.textPri())
            })
            col.addView(TextView(context).apply {
                text = bound?.name ?: "未绑定"
                textSize = 10.5f
                setTextColor(Theme.textSec())
            })
            addView(col, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Display.dpInt(context, 11f)
            })
            addView(TextView(context).apply {
                text = "选择"
                textSize = 10.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.pri2())
                setPadding(Display.dpInt(context, 8f), Display.dpInt(context, 4f),
                    Display.dpInt(context, 8f), Display.dpInt(context, 4f))
                background = Theme.rect(Theme.surface2(), 7f, context)
                setOnClickListener { pickScript(slot) }
            })
        }
    }

    private fun pickScript(slot: BallSlot) {
        val act = context as? android.app.Activity ?: return
        val items = AB.store.all()
        if (items.isEmpty()) {
            Ui.toast(act, "还没有脚本可绑定")
            return
        }
        val names = items.map { it.name } + "取消绑定"
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        items.forEach { s ->
            box.addView(Ui.sheetOption(act, "▶", Theme.pri2(), s.name, "绑定到${slot.label}") {
                AB.store.all().forEach { if (it.slot == slot) { it.slot = BallSlot.NONE; AB.store.save(it) } }
                s.slot = slot
                AB.store.save(s)
                renderBall()
            })
        }
        box.addView(Ui.sheetOption(act, "✕", Theme.danger(), "取消绑定", "清空该手势") {
            AB.store.all().forEach { if (it.slot == slot) { it.slot = BallSlot.NONE; AB.store.save(it) } }
            renderBall()
        })
        Ui.sheet(act, "${slot.label} 绑定的脚本").body(box).show()
    }

    // ---------- 悬浮窗 ----------

    private val SKINS = listOf(
        "经典" to "紧凑三键", "迷你" to "仅运行停止", "横向" to "扁条布局",
        "环形" to "圆形排布", "玻璃" to "毛玻璃质感", "极简" to "单键"
    )

    private fun renderWin() {
        winPane.removeAllViews()
        winPane.setPadding(Display.dpInt(context, 18f), 0,
            Display.dpInt(context, 18f), Display.dpInt(context, 92f))

        winPane.addView(sec("悬浮窗皮肤"))
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val cur = AB.store.getInt("panel_skin", 0)
        SKINS.chunked(2).forEachIndexed { ri, row ->
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, 0, 0, Display.dpInt(context, 10f))
                layoutParams = lp
            }
            row.forEachIndexed { ci, (name, desc) ->
                val idx = ri * 2 + ci
                line.addView(skinCard(idx, name, desc, idx == cur),
                    LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (ci > 0) marginStart = Display.dpInt(context, 10f)
                    })
            }
            grid.addView(line)
        }
        winPane.addView(grid)

        winPane.addView(TextView(context).apply {
            text = "悬浮窗在脚本运行时出现，可暂停/停止，不影响点击注入。"
            textSize = 10.5f
            setTextColor(Theme.textTer())
            setPadding(0, 0, 0, Display.dpInt(context, 10f))
        })

        winPane.addView(sec("尺寸"))
        winPane.addView(sliderRow("宽度", AB.store.getFloat("panel_w", 180f), 140f, 260f, "dp") {
            AB.store.putFloat("panel_w", it)
        })
        winPane.addView(sliderRow("闲置透明度", AB.store.getFloat("panel_alpha", 70f), 30f, 100f, "%") {
            AB.store.putFloat("panel_alpha", it)
        })

        winPane.addView(sec("行为"))
        winPane.addView(switchRow("运行时自动显示", AB.store.getBool("panel_auto", true)) {
            AB.store.putBool("panel_auto", it)
        })
        winPane.addView(switchRow("显示步骤名", AB.store.getBool("panel_step", false)) {
            AB.store.putBool("panel_step", it)
        })
    }

    private fun skinCard(idx: Int, name: String, desc: String, on: Boolean): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.rect(Theme.surface(), 14f, context,
                if (on) Theme.pri() else Theme.line())
            setPadding(Display.dpInt(context, 12f), Display.dpInt(context, 11f),
                Display.dpInt(context, 12f), Display.dpInt(context, 11f))
            setOnClickListener {
                AB.store.putInt("panel_skin", idx)
                renderWin()
            }
            // 预览条：按皮肤类型画不同数量的按钮
            val bar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, Display.dpInt(context, 8f))
            }
            val n = when (idx) { 1 -> 2; 5 -> 1; else -> 3 }
            repeat(n) {
                bar.addView(android.view.View(context).apply {
                    background = Theme.oval(Theme.pri2())
                    val s = Display.dpInt(context, 10f)
                    layoutParams = LinearLayout.LayoutParams(s, s).apply {
                        marginEnd = Display.dpInt(context, 6f)
                    }
                })
            }
            addView(bar)
            addView(TextView(context).apply {
                text = name
                textSize = 11.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (on) Theme.pri() else Theme.textPri())
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = desc
                textSize = 9.5f
                setTextColor(Theme.textTer())
                gravity = Gravity.CENTER
            })
        }

    // ---------- 通用组件 ----------

    private fun sec(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 11f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Theme.textTer())
        letterSpacing = 0.03f
        setPadding(Display.dpInt(context, 2f), Display.dpInt(context, 14f),
            Display.dpInt(context, 2f), Display.dpInt(context, 8f))
    }

    private fun switchRow(title: String, init: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        var on = init
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.rect(Theme.surface(), 13f, context, Theme.line())
            setPadding(Display.dpInt(context, 13f), Display.dpInt(context, 11f),
                Display.dpInt(context, 13f), Display.dpInt(context, 11f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, Display.dpInt(context, 8f))
            layoutParams = lp
        }
        row.addView(TextView(context).apply {
            text = title
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textPri())
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val track = LinearLayout(context).apply {
            background = Theme.rect(if (on) Theme.pri2() else Theme.line2(), 12f, context)
            layoutParams = LinearLayout.LayoutParams(
                Display.dpInt(context, Theme.SW_W), Display.dpInt(context, Theme.SW_H))
        }
        track.addView(View(context).apply {
            background = Theme.oval(Color.WHITE)
            val lp = LinearLayout.LayoutParams(Display.dpInt(context, Theme.SW_KNOB),
                Display.dpInt(context, Theme.SW_KNOB))
            lp.leftMargin = if (on) Display.dpInt(context, 21f) else Display.dpInt(context, 3f)
            lp.topMargin = Display.dpInt(context, 3f)
            layoutParams = lp
        })
        row.addView(track)
        row.setOnClickListener {
            on = !on
            track.background = Theme.rect(if (on) Theme.pri2() else Theme.line2(), 12f, context)
            val k = track.getChildAt(0).layoutParams as LinearLayout.LayoutParams
            k.leftMargin = if (on) Display.dpInt(context, 21f) else Display.dpInt(context, 3f)
            track.getChildAt(0).requestLayout()
            onChange(on)
        }
        return row
    }

    /** .sz：标题 + 滑块 + 数值 */
    private fun sliderRow(title: String, init: Float, min: Float, max: Float,
                          unit: String, onChange: (Float) -> Unit): LinearLayout {
        var v = init
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.rect(Theme.surface(), 13f, context, Theme.line())
            setPadding(Display.dpInt(context, 13f), Display.dpInt(context, 11f),
                Display.dpInt(context, 13f), Display.dpInt(context, 11f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, Display.dpInt(context, 8f))
            layoutParams = lp
        }
        row.addView(TextView(context).apply {
            text = title
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textPri())
            layoutParams = LinearLayout.LayoutParams(Display.dpInt(context, 78f),
                LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        val barOuter = LinearLayout(context).apply {
            background = Theme.rect(Theme.line2(), 2f, context)
            layoutParams = LinearLayout.LayoutParams(0,
                Display.dpInt(context, 4f), 1f)
        }
        barOuter.addView(android.view.View(context).apply {
            background = Theme.rect(Theme.pri2(), 2f, context)
            val pct = ((v - min) / (max - min)).coerceIn(0f, 1f)
            layoutParams = LinearLayout.LayoutParams(0,
                Display.dpInt(context, 4f), pct * 1000)
        })
        row.addView(barOuter)
        val valTv = TextView(context).apply {
            text = "${v.toInt()}$unit"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textSec())
            setPadding(Display.dpInt(context, 8f), 0, 0, 0)
            minWidth = Display.dpInt(context, 40f)
            gravity = Gravity.END
        }
        row.addView(valTv)
        row.setOnClickListener {
            // 点一次加一档，循环回到最小值（零依赖下不引入自定义拖动控件）
            val step = (max - min) / 8f
            v += step
            if (v > max) v = min
            onChange(v)
            valTv.text = "${v.toInt()}$unit"
            (barOuter.getChildAt(0).layoutParams as LinearLayout.LayoutParams).weight =
                ((v - min) / (max - min)).coerceIn(0f, 1f) * 1000
            barOuter.getChildAt(0).requestLayout()
        }
        return row
    }
}
