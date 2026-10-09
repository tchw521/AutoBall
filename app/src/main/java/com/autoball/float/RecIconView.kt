package com.autoball.float

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.autoball.core.util.Display

/**
 * 录制 / 添加 两个入口的**矢量图标**（不用 emoji）。
 *
 * 为什么不用字符图标：emoji 与 ⚙ 这类字形在各 ROM 上形状不一、颜色不可控，
 * 深色底上有些 ROM 会渲染成彩色 emoji，与整体配色冲突。自绘才能保证一致。
 *
 * - [Kind.REC] 录制：外圆环 + 实心圆点；[active] 时圆点变**方块**（停止语义）
 * - [Kind.ADD] 添加：外圆环 + 圆头十字
 */
class RecIconView(context: Context) : View(context) {

    enum class Kind { REC, ADD }

    var kind: Kind = Kind.REC
        set(value) { field = value; invalidate() }

    /** 录制中：圆点变方块 */
    var active: Boolean = false
        set(value) { field = value; invalidate() }

    var iconColor: Int = 0xFFFFFFFF.toInt()
        set(value) { field = value; ring.color = value; dot.color = value; bar.color = value; invalidate() }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Display.dp(context, 2f)
        color = iconColor
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = iconColor
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = Display.dp(context, 2.2f)
        color = iconColor
    }

    init { setWillNotDraw(false) }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f; val cy = h / 2f
        val r = (minOf(w, h) / 2f) - Display.dp(context, 3f)
        if (r <= 0f) return

        canvas.drawCircle(cx, cy, r, ring)

        when (kind) {
            Kind.REC -> {
                val ir = r * 0.46f
                if (active) {
                    // 停止语义：方块
                    canvas.drawRect(cx - ir, cy - ir, cx + ir, cy + ir, dot)
                } else {
                    canvas.drawCircle(cx, cy, ir, dot)
                }
            }
            Kind.ADD -> {
                val ir = r * 0.44f
                canvas.drawLine(cx - ir, cy, cx + ir, cy, bar)
                canvas.drawLine(cx, cy - ir, cx, cy + ir, bar)
            }
        }
    }

    /**
     * **必须正方形**：放进横排 LinearLayout 时不锁死尺寸会被拉伸成椭圆，
     * 圆环就变成横向的跑道形。measure 阶段直接把宽改成高。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val sz = if (h > 0) h else if (w > 0) w else Display.dpInt(context, 34f)
        setMeasuredDimension(sz, sz)
    }
}
