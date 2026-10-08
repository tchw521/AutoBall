package com.autoball.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.AB
import com.autoball.core.model.BallSlot
import com.autoball.core.model.Script
import com.autoball.core.util.Display
import com.autoball.float.FloatManager

/**
 * 悬浮设置页（v3 #p-float）：两段切换 + 预览 + 手势绑定 + 皮肤。
 *
 * 行、卡片、滑块、分段一律走 Kit 统一组件；本文件只负责数据与预览。
 */
class FloatSetPage(context: Context, private val host: PageHost) : FrameLayout(context) {

    private var pane = 0
    private lateinit var segRow: LinearLayout
    private val ballPane = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val winPane = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init { build() }

    private fun build() {
        val root = Kit.root(context)
        root.addView(Kit.statusBar(context))
        root.addView(Kit.subbar(context, "悬浮设置", onBack = { host.showPage(4) }))

        segRow = Kit.segment(context, listOf("悬浮球", "悬浮窗"), pane) { i ->
            pane = i
            ballPane.visibility = if (i == 0) View.VISIBLE else View.GONE
            winPane.visibility = if (i == 1) View.VISIBLE else View.GONE
            refreshSeg()
        }
        root.addView(segRow)

        val holder = FrameLayout(context).apply {
            addView(ballPane)
            addView(winPane.apply { visibility = View.GONE })
        }
        val sc = android.widget.ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        sc.addView(holder)
        root.addView(sc, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // 两块面板分别容错：一处渲染异常不至于让整个二级页打不开
        runCatching { renderBall() }.onFailure {
            com.autoball.core.log.CrashGuard.report("渲染悬浮球面板失败", it)
        }
        runCatching { renderWin() }.onFailure {
            com.autoball.core.log.CrashGuard.report("渲染悬浮窗面板失败", it)
        }
    }

    private fun refreshSeg() {
        // 分段控件重建，保持当前选中
        val parent = segRow.parent as? LinearLayout ?: return
        val idx = parent.indexOfChild(segRow)
        parent.removeView(segRow)
        segRow = Kit.segment(context, listOf("悬浮球", "悬浮窗"), pane) { i ->
            pane = i
            ballPane.visibility = if (i == 0) View.VISIBLE else View.GONE
            winPane.visibility = if (i == 1) View.VISIBLE else View.GONE
            refreshSeg()
        }
        parent.addView(segRow, idx)
    }

    // ---------- 悬浮球 ----------

    private fun renderBall() {
        ballPane.removeAllViews()
        ballPane.setPadding(Display.dpInt(context, 18f), 0,
            Display.dpInt(context, 18f), Display.dpInt(context, 92f))

        // .ballprev：132dp 预览 + 底部虚线关闭区
        ballPane.addView(ballPreview())
        ballPane.addView(Kit.note(context,
            "拖动可移动位置，拖到底部虚线区即关闭悬浮球。", 0f).apply {
            setPadding(0, 0, 0, Display.dpInt(context, 10f))
        })

        ballPane.addView(Kit.section(context, "悬浮球手势"))
        BallSlot.values().filter { it != BallSlot.NONE }.forEach { ballPane.addView(gestRow(it)) }

        ballPane.addView(Kit.section(context, "行为"))
        ballPane.addView(Kit.switchRow(context, "常驻显示", "退出界面后仍显示", "●", Theme.pri2(),
            AB.store.getBool("float_persistent", true)) {
            AB.store.putBool("float_persistent", it)
            if (it) runCatching { FloatManager.showBall(context.applicationContext) }
            else FloatManager.hideBall()
        })
        ballPane.addView(Kit.switchRow(context, "自动贴边", "松手后吸附到屏幕边缘", "⇤", Theme.ok(),
            AB.store.getBool("ball_snap_edge", true)) {
            AB.store.putBool("ball_snap_edge", it)
        })
        // 注意：ball_idle_alpha 存的是 Float 透明度，这里用的是独立的布尔开关 key。
        // 早前两处共用同一个 key，getBool 读到 Float 会走降级分支，语义也互相覆盖。
        ballPane.addView(Kit.switchRow(context, "闲置半透明", "不用时自动变淡", "◑",
            Theme.warn(), AB.store.getBool("ball_fade_idle", true)) {
            AB.store.putBool("ball_fade_idle", it)
        })

        ballPane.addView(Kit.sliderRow(context, "大小",
            AB.store.getFloat("ball_size_dp", 48f), 36f, 72f, "dp") {
            AB.store.putFloat("ball_size_dp", it)
        })
        ballPane.addView(Kit.sliderRow(context, "闲置透明度",
            AB.store.getFloat("ball_idle_alpha", 0.72f) * 100f, 20f, 100f, "%") {
            AB.store.putFloat("ball_alpha", it)
        })
    }

    private fun ballPreview(): FrameLayout = FrameLayout(context).apply {
        background = Theme.rect(
            Color.parseColor(if (Theme.isDark()) "#1E1A33" else "#F0F1F8"),
            16f, context, Theme.line())
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            Display.dpInt(context, 132f))
        lp.setMargins(0, 0, 0, Display.dpInt(context, 12f))
        layoutParams = lp
        addView(TextView(context).apply {
            text = "●"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Theme.gradOval()
            val s = Display.dpInt(context, 48f)
            layoutParams = FrameLayout.LayoutParams(s, s).apply { gravity = Gravity.CENTER }
        })
        addView(TextView(context).apply {
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
    }

    private fun gestRow(slot: BallSlot): LinearLayout {
        val bound = AB.store.all().firstOrNull { it.slot == slot }
        val row = Kit.rowCard(context)
        row.addView(TextView(context).apply {
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
        row.addView(Kit.twoLine(context, slot.label,
            com.autoball.core.store.GestureBinding.summary(slot)))
        row.addView(TextView(context).apply {
            text = "选择"
            textSize = 10.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.pri2())
            setPadding(Display.dpInt(context, 8f), Display.dpInt(context, 4f),
                Display.dpInt(context, 8f), Display.dpInt(context, 4f))
            background = Theme.rect(Theme.surface2(), 7f, context)
            setOnClickListener { pickScript(slot) }
        })
        return row
    }

    /**
     * 手势绑定（R-106）：一个手势可绑多个脚本，**按勾选顺序**依次执行。
     *
     * 原实现是单选（一个手势只能绑一个），想"双击先跑 A 再跑 B"就只能
     * 把两者合成一个大脚本，那样 A 又没法单独复用了。
     */
    private fun pickScript(slot: BallSlot) {
        val act = context as? Activity ?: return
        val items = AB.store.all()
        if (items.isEmpty()) {
            Ui.toast(act, "还没有脚本可绑定")
            return
        }
        val bound = com.autoball.core.store.GestureBinding.ids(slot).toMutableList()
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

        fun fill() {
            box.removeAllViews()
            box.addView(Kit.note(act,
                "可多选，按**勾选顺序**依次执行。取消勾选会从序列中移除。"))
            items.forEach { s ->
                val idx = bound.indexOf(s.id)
                box.addView(Kit.switchRow(act, s.name,
                    if (idx >= 0) "第 ${idx + 1} 个执行" else "未绑定",
                    "▶", Theme.pri2(), idx >= 0) { on ->
                    if (on) {
                        if (s.id !in bound) bound.add(s.id)
                    } else bound.remove(s.id)
                    fill()
                })
            }
            if (bound.size > 1) {
                // 顺序调整：上下移动
                box.addView(Kit.section(act, "执行顺序"))
                bound.forEachIndexed { i, id ->
                    val s2 = items.firstOrNull { it.id == id } ?: return@forEachIndexed
                    box.addView(Kit.rowCard(act).apply {
                        addView(Kit.twoLine(act, "${i + 1}. ${s2.name}", null))
                        addView(Kit.miniBtn(act, "↑") {
                            if (i > 0) {
                                bound.removeAt(i); bound.add(i - 1, id); fill()
                            }
                        })
                        addView(Kit.miniBtn(act, "↓") {
                            if (i < bound.size - 1) {
                                bound.removeAt(i); bound.add(i + 1, id); fill()
                            }
                        })
                    })
                }
            }
        }
        fill()

        Ui.dialog(act, "${slot.label} 绑定的脚本").body(box)
            .width(Theme.DIALOG_W + 10f).maxHeight(0.8f)
            .negative("清空") {
                com.autoball.core.store.GestureBinding.set(slot, emptyList())
                renderBall()
            }
            .positive("确定") {
                com.autoball.core.store.GestureBinding.set(slot, bound)
                renderBall(); true
            }.show()
    }

    private fun clearSlot(slot: BallSlot) {
        AB.store.all().forEach {
            if (it.slot == slot) { it.slot = BallSlot.NONE; AB.store.save(it) }
        }
    }

    // ---------- 悬浮窗 ----------

    private val SKINS = listOf(
        "皮肤2020" to "3×3 九宫格", "默认控制窗" to "纵向七键", "简版控制窗" to "纵向三键",
        "横向控制窗" to "横向五键", "横向简版" to "横向三键", "超简易" to "单键"
    )

    private fun renderWin() {
        winPane.removeAllViews()
        winPane.setPadding(Display.dpInt(context, 18f), 0,
            Display.dpInt(context, 18f), Display.dpInt(context, 92f))

        winPane.addView(Kit.section(context, "悬浮窗皮肤"))
        // 旧版本把皮肤名存成字符串，getInt 已带类型兜底，这里再夹一次范围
        val cur = AB.store.getInt("panel_skin", 0).coerceIn(0, SKINS.size - 1)
        SKINS.chunked(2).forEachIndexed { ri, rowItems ->
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, 0, 0, Display.dpInt(context, 10f))
                layoutParams = lp
            }
            rowItems.forEachIndexed { ci, (name, desc) ->
                val idx = ri * 2 + ci
                line.addView(skinCard(idx, name, desc, idx == cur),
                    LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (ci > 0) marginStart = Display.dpInt(context, 10f)
                    })
            }
            winPane.addView(line)
        }

        winPane.addView(Kit.note(context,
            "悬浮窗在脚本运行时出现，可暂停/停止，不影响点击注入。", 0f).apply {
            setPadding(0, 0, 0, Display.dpInt(context, 10f))
        })

        winPane.addView(Kit.section(context, "尺寸"))
        winPane.addView(Kit.sliderRow(context, "宽度",
            AB.store.getFloat("panel_w", 180f), 140f, 260f, "dp") {
            AB.store.putFloat("panel_w", it)
        })
        winPane.addView(Kit.sliderRow(context, "闲置透明度",
            AB.store.getFloat("panel_alpha", 70f), 30f, 100f, "%") {
            AB.store.putFloat("panel_alpha", it)
        })

        winPane.addView(Kit.section(context, "行为"))
        winPane.addView(Kit.switchRow(context, "运行时自动显示", "脚本开始即弹出", "▷", Theme.ok(),
            AB.store.getBool("panel_auto", true)) {
            AB.store.putBool("panel_auto", it)
        })
        winPane.addView(Kit.switchRow(context, "显示步骤名", "在悬浮窗上显示当前动作", "≡", Theme.pri2(),
            AB.store.getBool("panel_step", false)) {
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
            val bar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, Display.dpInt(context, 8f))
            }
            val n = when (idx) { 0 -> 9; 1 -> 7; 2 -> 3; 3 -> 5; 4 -> 3; else -> 1 }
            repeat(n) {
                bar.addView(View(context).apply {
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
}
