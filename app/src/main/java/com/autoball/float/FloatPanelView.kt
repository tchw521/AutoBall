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
 * 悬浮窗（点击器形态）：与悬浮球并存的第二种形态。
 * 6 套皮肤共用同一组动作语义，只是布局密度不同；录制时由 FloatManager 自动隐藏。
 */
class FloatPanelView(context: Context, private val listener: Listener) : LinearLayout(context) {

    enum class Skin(val label: String) {
        SKIN_2020("皮肤2020"),
        DEFAULT("默认控制窗"),
        SIMPLE("简版控制窗"),
        HORIZONTAL("横向控制窗"),
        HORIZONTAL_SIMPLE("横向简版"),
        ULTRA("超简易")
    }

    interface Listener {
        fun onRunSlot(slot: SlotAction)
        fun onStop()
        fun onCollapse()
        fun onRecord()
    }

    enum class SlotAction(val label: String) {
        SLOT_A("脚本A"), SLOT_B("脚本B"), SLOT_C("脚本C"),
        BACK("返回"), HOME("主页"), RECENTS("最近"), SHOT("截图")
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
        }
        bar.addView(smallButton("停止", Color.parseColor("#FF5B6E")) { listener.onStop() })
        bar.addView(smallButton("录制", Color.parseColor("#7C3AED")) { listener.onRecord() })
        bar.addView(smallButton("收起", Color.parseColor("#4A9EFF")) { listener.onCollapse() })
        addView(bar)
    }

    private fun buildVertical(actions: List<SlotAction>) {
        val col = LinearLayout(context).apply { orientation = VERTICAL }
        actions.forEach { col.addView(actButton(it)) }
        addView(col)
    }

    private fun buildHorizontal(actions: List<SlotAction>) {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        actions.forEach { row.addView(actButton(it)) }
        addView(row)
    }

    private fun buildGrid(columns: Int, actions: List<SlotAction>) {
        var row: LinearLayout? = null
        actions.forEachIndexed { i, a ->
            if (i % columns == 0) {
                row = LinearLayout(context).apply { orientation = HORIZONTAL }
                addView(row)
            }
            row?.addView(actButton(a))
        }
    }

    private fun actButton(a: SlotAction): TextView {
        val size = Display.dpInt(context, buttonDp)
        return TextView(context).apply {
            text = a.label
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val gd = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = Display.dp(context, 8f)
                setColor(Color.parseColor("#3A2E6B"))
            }
            background = gd
            layoutParams = LayoutParams(size, size).apply {
                setMargins(Display.dpInt(context, 3f), Display.dpInt(context, 3f),
                    Display.dpInt(context, 3f), Display.dpInt(context, 3f))
            }
            setOnClickListener { listener.onRunSlot(a) }
        }
    }

    private fun smallButton(text: String, color: Int, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = Display.dp(context, 10f)
                setColor(color)
            }
            setPadding(Display.dpInt(context, 10f), Display.dpInt(context, 5f),
                Display.dpInt(context, 10f), Display.dpInt(context, 5f))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(Display.dpInt(context, 3f), Display.dpInt(context, 3f),
                    Display.dpInt(context, 3f), 0)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun setBackgroundCompat() {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = Display.dp(context, 16f)
            setColor(Color.parseColor("#CC1B1730"))
            setStroke(Display.dpInt(context, 1f), Color.parseColor("#33FFFFFF"))
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun setButtonSize(dp: Float) { apply(skin, dp) }

    fun currentSkin(): Skin = skin
}
