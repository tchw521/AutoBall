package com.autoball.ui

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.AB
import com.autoball.core.model.Script
import com.autoball.core.recorder.GlobalSettingsDialog
import com.autoball.core.util.Display

/**
 * 脚本工作台弹窗（统一组件）。
 *
 * 「开始录制」与「空白动作」两个入口**共用**这一个弹窗——
 * 设计稿里它们本就是同一个界面的两种进入方式：进来后脚本还是空的，
 * 由用户决定是录制还是手动加动作，而不是在入口处就分叉成两条路。
 *
 * 一比一复刻设计稿：
 * - 标题 = 当前脚本名
 * - 右上角两个圆钮：⚙（**当前脚本**的全局设置）+ ✕（关闭）
 * - 中部状态文案：空脚本提示「脚本为空 请先添加一个动作」，
 *   有动作时显示已有步数
 * - 底部两个大按钮：开始录制 / 添加动作
 */
object ScriptWorkDialog {

    fun show(activity: Activity, script: Script, host: PageHost) {
        val ctx = activity
        var dlg: android.app.Dialog? = null

        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val stateTv = TextView(ctx)

        fun refresh() {
            val n = script.flow?.actions?.size ?: 0
            stateTv.text = if (n == 0) "脚本为空  请先添加一个动作"
            else "已有 $n 个动作，可继续添加或直接运行"
        }

        // ---- 头：标题 + ⚙ + ✕ ----
        val head = FrameLayout(ctx).apply {
            setPadding(Display.dpInt(ctx, 16f), Display.dpInt(ctx, 14f),
                Display.dpInt(ctx, 12f), Display.dpInt(ctx, 10f))
        }
        head.addView(TextView(ctx).apply {
            text = script.name
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.textPri())
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                marginEnd = Display.dpInt(ctx, 76f)
            }
        })
        // 右：⚙ 当前脚本全局设置 + ✕ 关闭
        val btns = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
        }
        btns.addView(roundBtn(ctx, "⚙", false) {
            script.flow?.let { GlobalSettingsDialog.show(ctx, it) { AB.store.save(script) } }
        })
        btns.addView(roundBtn(ctx, "✕", true) { dlg?.dismiss() })
        head.addView(btns)
        box.addView(head)

        // ---- 状态文案 ----
        stateTv.apply {
            textSize = 12.5f
            setTextColor(Theme.textSec())
            gravity = Gravity.CENTER
            setPadding(Display.dpInt(ctx, 16f), Display.dpInt(ctx, 14f),
                Display.dpInt(ctx, 16f), Display.dpInt(ctx, 16f))
        }
        box.addView(stateTv)
        refresh()

        // ---- 两个大按钮 ----
        box.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Display.dpInt(ctx, 14f), 0,
                Display.dpInt(ctx, 14f), Display.dpInt(ctx, 14f))
            addView(bigBtn(ctx, "开始录制", "●", Theme.ok()) {
                dlg?.dismiss()
                host.startRecording()
            }, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = Display.dpInt(ctx, 5f)
            })
            addView(bigBtn(ctx, "添加动作", "＋", Theme.pri()) {
                dlg?.dismiss()
                host.openScript(script)
            }, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Display.dpInt(ctx, 5f)
            })
        })

        dlg = Ui.sheet(ctx, script.name).body(box).closeable(false).show()
    }

    /** 右上角圆钮：⚙ / ✕ 统一走这里，尺寸与配色一致 */
    private fun roundBtn(ctx: android.content.Context, glyph: String,
                         danger: Boolean, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = glyph
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(if (danger) Theme.textSec() else Theme.pri2())
            gravity = Gravity.CENTER
            background = Theme.bubbleRound(ctx, Theme.surface2())
            val sz = Display.dpInt(ctx, 28f)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginStart = Display.dpInt(ctx, 6f)
            }
            setOnClickListener { onClick() }
        }

    /** 底部大按钮：图标 + 文字，纵向 */
    private fun bigBtn(ctx: android.content.Context, text: String, glyph: String,
                       color: Int, onClick: () -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Theme.rect(Theme.surface2(), 16f, ctx, Theme.line())
            setPadding(Display.dpInt(ctx, 10f), Display.dpInt(ctx, 18f),
                Display.dpInt(ctx, 10f), Display.dpInt(ctx, 18f))
            setOnClickListener { onClick() }
            addView(TextView(ctx).apply {
                this.text = glyph
                textSize = 20f
                setTextColor(color)
                gravity = Gravity.CENTER
            })
            addView(TextView(ctx).apply {
                this.text = text
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Theme.textPri())
                gravity = Gravity.CENTER
                setPadding(0, Display.dpInt(ctx, 7f), 0, 0)
            })
        }
}
