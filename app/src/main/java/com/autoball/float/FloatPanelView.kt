package com.autoball.float

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.core.util.Display

/**
 * 悬浮窗（UI 设计方案 v3 · 六套皮肤）。
 *
 * 六套皮肤共用同一组动作语义，区别只在布局密度与按钮形态：
 * - SKIN_2020：3×3 九宫格（旧版点击器形态）
 * - DEFAULT：纵向七键（默认控制窗）
 * - SIMPLE：纵向三键（简版）
 * - HORIZONTAL：横向五键
 * - HORIZONTAL_SIMPLE：横向三键
 * - ULTRA：单键（超简易）
 *
 * 每张卡底部固定一条操作条：停止 / 录制 / 收起。
 */
class FloatPanelView(context: Context, private val listener: Listener) : LinearLayout(context) {

    enum class Skin(val label: String, val desc: String) {
        SKIN_2020("皮肤2020", "3×3 九宫格，经典点击器布局"),
        DEFAULT("默认控制窗", "纵向七键，功能最全"),
        SIMPLE("简版控制窗", "纵向三键，占位最小"),
        HORIZONTAL("横向控制窗", "横向五键，贴边更省空间"),
        HORIZONTAL_SIMPLE("横向简版", "横向三键，最扁"),
        ULTRA("超简易", "仅一个运行键")
    }

    interface Listener {
        fun onRunSlot(slot: SlotAction)
        fun onStop()
        fun onCollapse()
        fun onRecord()
    }

    enum class SlotAction(val label: String, val glyph: String) {
        SLOT_A("脚本A", "A"), SLOT_B("脚本B", "B"), SLOT_C("脚本C", "C"),
        BACK("返回", "‹"), HOME("主页", "○"), RECENTS("最近", "▤"), SHOT("截图", "◻")
    }

    private var skin: Skin = Skin.DEFAULT
    private var buttonDp = 40f

    init {
        orientation = VERTICAL
        setBackgroundCompat()
        setPadding(Display.dpInt(context, 6f), Display.dpInt(context, 6f),
            Display.dpInt(context, 6f), Display.dpInt(context, 6f))
        apply(Skin.DEFAULT, 40f)
    }

    fun apply(skin: Skin, buttonDp: Float) {
        this.skin = skin
        this.buttonDp = buttonDp
        removeAllViews()
        when (skin) {
            Skin.SKIN_2020 -> buildGrid(3, listOf(
                SlotAction.SLOT_A, SlotAction.SLOT_B, SlotAction.SLOT_C,
                SlotAction.BACK, SlotAction.HOME, SlotAction.RECENTS,
                SlotAction.SHOT, SlotAction.RECENTS, SlotAction.HOME))
            Skin.DEFAULT -> buildVertical(listOf(
                SlotAction.SLOT_A, SlotAction.SLOT_B, SlotAction.SLOT_C,
                SlotAction.BACK, SlotAction.HOME, SlotAction.RECENTS, SlotAction.SHOT))
            Skin.SIMPLE -> buildVertical(listOf(
                SlotAction.SLOT_A, SlotAction.BACK, SlotAction.HOME))
            Skin.HORIZONTAL -> buildHorizontal(listOf(
                SlotAction.SLOT_A, SlotAction.SLOT_B, SlotAction.SLOT_C,
                SlotAction.BACK, SlotAction.HOME))
            Skin.HORIZONTAL_SIMPLE -> buildHorizontal(listOf(
                SlotAction.SLOT_A, SlotAction.BACK, SlotAction.HOME))
            Skin.ULTRA -> buildVertical(listOf(SlotAction.SLOT_A))
        }
        // 通用操作条
        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, Display.dpInt(context, 5f), 0, 0)
        }
        bar.addView(smallButton("停止", Color.parseColor("#FF5B6E")) { listener.onStop() })
        bar.addView(smallButton("录制", Color.parseColor("#7C3AED")) { listener.onRecord() })
        bar.addView(smallButton("收起", Color.parseColor("#4A9EFF")) { listener.onCollapse() })
        addView(bar)
    }

    fun currentSkin(): Skin = skin

    // ---------- 布局 ----------

    private fun buildVertical(items: List<SlotAction>) {
        items.forEach { addView(key(it)) }
    }

    private fun buildHorizontal(items: List<SlotAction>) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        items.forEachIndexed { i, a ->
            row.addView(key(a), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) marginStart = Display.dpInt(context, 4f)
            })
        }
        addView(row)
    }

    private fun buildGrid(cols: Int, items: List<SlotAction>) {
        items.chunked(cols).forEachIndexed { ri, rowItems ->
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
            }
            rowItems.forEachIndexed { ci, a ->
                row.addView(key(a), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    if (ci > 0) marginStart = Display.dpInt(context, 4f)
                })
            }
            addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (ri > 0) topMargin = Display.dpInt(context, 4f)
            })
        }
    }

    // ---------- 按键 ----------

    /** 主按键：圆形渐变，带内高光与投影 */
    private fun key(a: SlotAction): TextView = TextView(context).apply {
        text = a.glyph
        textSize = (buttonDp * 0.36f)
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        val s = Display.dpInt(context, buttonDp)
        layoutParams = LinearLayout.LayoutParams(s, s).apply {
            bottomMargin = Display.dpInt(context, 4f)
        }
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(colorOf(a), shade(colorOf(a), -0.28f))).apply {
            shape = GradientDrawable.OVAL
            setStroke(Display.dpInt(context, 1f), Color.parseColor("#33FFFFFF"))
        }
        elevation = Display.dp(context, 4f)
        setOnClickListener { listener.onRunSlot(a) }
        contentDescription = a.label
    }

    /** 底部操作条的小按钮 */
    private fun smallButton(text: String, color: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 10.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = Display.dp(context, 8f)
            }
            setPadding(Display.dpInt(context, 9f), Display.dpInt(context, 4f),
                Display.dpInt(context, 9f), Display.dpInt(context, 4f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = Display.dpInt(context, 3f)
                marginEnd = Display.dpInt(context, 3f)
            }
            setOnClickListener { onClick() }
        }

    private fun colorOf(a: SlotAction): Int = when (a) {
        SlotAction.SLOT_A -> Color.parseColor("#7C3AED")
        SlotAction.SLOT_B -> Color.parseColor("#2F6BFF")
        SlotAction.SLOT_C -> Color.parseColor("#12B76A")
        SlotAction.BACK -> Color.parseColor("#F79009")
        SlotAction.HOME -> Color.parseColor("#0EA5E9")
        SlotAction.RECENTS -> Color.parseColor("#6B7280")
        SlotAction.SHOT -> Color.parseColor("#EC4899")
    }

    /** 压暗（amount 为负）或提亮 */
    private fun shade(color: Int, amount: Float): Int {
        val r = (Color.red(color) * (1f + amount)).coerceIn(0f, 255f).toInt()
        val g = (Color.green(color) * (1f + amount)).coerceIn(0f, 255f).toInt()
        val b = (Color.blue(color) * (1f + amount)).coerceIn(0f, 255f).toInt()
        return Color.rgb(r, g, b)
    }

    private fun setBackgroundCompat() {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#F21E1836"), Color.parseColor("#F2141022"))).apply {
            cornerRadius = Display.dp(context, 16f)
            setStroke(Display.dpInt(context, 1f), Color.parseColor("#26FFFFFF"))
        }
        elevation = Display.dp(context, 10f)
    }

    /** 运行时高亮：整块描边改主色 */
    fun setRunning(running: Boolean) {
        val bg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#F21E1836"), Color.parseColor("#F2141022"))).apply {
            cornerRadius = Display.dp(context, 16f)
            setStroke(Display.dpInt(context, 1.6f),
                if (running) Color.parseColor("#C87C3AED") else Color.parseColor("#26FFFFFF"))
        }
        background = bg
    }
}
