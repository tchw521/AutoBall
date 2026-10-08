package com.autoball.core.recorder

import android.app.Activity
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.core.engine.Morph
import com.autoball.core.model.Flow
import com.autoball.core.util.Display
import com.autoball.ui.ListenerDialog
import com.autoball.ui.Theme
import com.autoball.ui.Ui

/**
 * 脚本全局设置（统一组件）。
 *
 * 入口有两处：脚本工作台弹窗右上角「⚙」，以及编辑页的「脚本设置」。
 * 两处共用本弹窗，保证参数口径一致。
 *
 * 一比一复刻设计稿 gdlg：
 * - 默认等待：选填输入框 + 单位「秒」+ 「?」
 * - 重复次数：选填输入框 + 「?」
 * - 有动作失败立即暂停：勾选框 + 「?」
 * - 全局监听动作：显示「未设置 / 已设置 N 项」+ 「?」
 * - 全局手势变形：长输入框 + 「?」
 * - 底部：取消 / 确定
 *
 * 所有「?」统一走 [Ui.helpBubble]。
 */
object GlobalSettingsDialog {

    private const val H_WAIT =
        "每个动作执行后额外等待的时间，留空表示不额外等待。网络慢或界面切换慢时建议设 0.5～1 秒。"
    private const val H_REPEAT =
        "整轮动作列表的重复次数。留空按 1 次执行；填 0 代表无限循环，配合监听动作可做批量任务。"
    private const val H_FAIL =
        "任意一步失败时立即中断整个脚本，防止后面的动作在错误界面上乱点。建议保持开启。"
    private const val H_LISTEN =
        "在 9 个时机（脚本开始前、每轮开头、每个动作前后、成功/失败后、每轮结尾、脚本结束后）" +
            "自动插入动作，常用于截图留证、写日志与失败兜底。"
    private const val H_MORPH =
        "对脚本内坐标类手势（点击、滑动、单指/多指手势）做图形矩阵仿射变换，" +
            "可实现平移、缩放、旋转的叠加效果，让手势更像真人。\n" +
            "输入逗号分隔的 6 个数值，对应 css matrix(a, b, c, d, e, f)：\n" +
            "· a/d 为缩放，b/c 为旋转与斜切，e/f 为平移（单位 dp）\n" +
            "· e/f 可写 ±N，表示在该区间内随机抖动\n" +
            "· 末尾可追加「· 时长±20%」随机化手势时长\n" +
            "留空表示不做变换。"

    /**
     * @param flow 目标脚本流程；修改直接写回该对象
     * @param onSaved 点「确定」后的回调（通常用于落库）
     */
    fun show(ctx: Activity, flow: Flow, onSaved: (() -> Unit)? = null) {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var dlg: android.app.Dialog? = null

        // 默认值：重复次数 0 显示为 1 次（0 = 无限），这里按设计稿「选填」留空
        val waitSec = if (flow.defaultWaitMs > 0) (flow.defaultWaitMs / 1000f).toString() else ""
        val repeat = if (flow.loopCount > 0) flow.loopCount.toString() else ""

        // ---- 默认等待 ----
        val waitEt = numInput(ctx, waitSec, "选填")
        box.addView(fieldRow(ctx, "默认等待", waitEt, unitView(ctx, "秒"), H_WAIT))

        // ---- 重复次数 ----
        val repeatEt = numInput(ctx, repeat, "选填")
        box.addView(fieldRow(ctx, "重复次数", repeatEt, null, H_REPEAT))

        // ---- 有动作失败立即暂停 ----
        val failTv = TextView(ctx).apply {
            text = if (flow.failStop) "☑" else "☐"
            textSize = 15f
            setTextColor(if (flow.failStop) Theme.ok() else Theme.textTer())
            gravity = Gravity.CENTER
            setPadding(Display.dpInt(ctx, 6f), 0, Display.dpInt(ctx, 6f), 0)
        }
        val failRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Display.dpInt(ctx, 8f), Display.dpInt(ctx, 5f),
                Display.dpInt(ctx, 8f), Display.dpInt(ctx, 5f))
            setOnClickListener {
                flow.failStop = !flow.failStop
                failTv.text = if (flow.failStop) "☑" else "☐"
                failTv.setTextColor(if (flow.failStop) Theme.ok() else Theme.textTer())
            }
            addView(failTv)
            addView(TextView(ctx).apply {
                text = "有动作失败立即暂停"
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Theme.textPri())
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        box.addView(fieldRow(ctx, "", failRow, null, H_FAIL))

        // ---- 全局监听动作 ----
        val listenTv = TextView(ctx).apply {
            val (stages, n) = flow.hookSummary()
            text = if (stages == 0) "未设置" else "已设置 $stages 项 · $n 个动作"
            textSize = 11.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(if (stages == 0) Theme.textTer() else Theme.pri2())
            gravity = Gravity.END
        }
        val listenWrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Display.dpInt(ctx, 8f), Display.dpInt(ctx, 5f),
                Display.dpInt(ctx, 8f), Display.dpInt(ctx, 5f))
            setOnClickListener {
                ListenerDialog.show(ctx, flow) {
                    val (stages, n) = flow.hookSummary()
                    listenTv.text = if (stages == 0) "未设置" else "已设置 $stages 项 · $n 个动作"
                    listenTv.setTextColor(if (stages == 0) Theme.textTer() else Theme.pri2())
                }
            }
            addView(TextView(ctx).apply {
                text = "全局监听动作"
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Theme.textPri())
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(listenTv)
        }
        box.addView(fieldRow(ctx, "", listenWrap, null, H_LISTEN))

        // ---- 全局手势变形 ----
        val morphEt = EditText(ctx).apply {
            setText(flow.morph)
            hint = "留空不变换，如 1,0,0,1,±6,±6"
            setHintTextColor(Theme.textTer())
            setTextColor(Theme.textPri())
            textSize = 11.5f
            setSingleLine(true)
            background = Theme.rect(Theme.surface2(), 8f, ctx, Theme.line())
            setPadding(Display.dpInt(ctx, 8f), Display.dpInt(ctx, 6f),
                Display.dpInt(ctx, 8f), Display.dpInt(ctx, 6f))
        }
        box.addView(fieldRow(ctx, "全局手势变形", morphEt, null, H_MORPH))

        dlg = Ui.dialog(ctx, "脚本全局设置")
            .body(box)
            .width(Theme.DIALOG_W + 24f)
            .maxHeight(0.78f)
            .negative("取消") { }
            .positive("确定") {
                val v = morphEt.text.toString().trim()
                if (v.isNotEmpty() && !Morph.valid(v)) {
                    Ui.toast(ctx, "手势变形格式不正确，应为 a,b,c,d,e,f")
                    false
                } else {
                    flow.morph = v
                    val w = waitEt.text.toString().trim().toFloatOrNull()
                    flow.defaultWaitMs = if (w == null || w <= 0f) 0L else (w * 1000).toLong()
                    val r = repeatEt.text.toString().trim().toIntOrNull()
                    flow.loopCount = r ?: 0
                    onSaved?.invoke()
                    true
                }
            }.show()
    }

    // ---------- 复用件 ----------

    private fun numInput(ctx: Activity, value: String, hint: String): EditText =
        EditText(ctx).apply {
            setText(value)
            this.hint = hint
            setHintTextColor(Theme.textTer())
            setTextColor(Theme.textPri())
            textSize = 12f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            background = Theme.rect(Theme.surface2(), 8f, ctx, Theme.line())
            setPadding(Display.dpInt(ctx, 8f), Display.dpInt(ctx, 5f),
                Display.dpInt(ctx, 8f), Display.dpInt(ctx, 5f))
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

    private fun unitView(ctx: Activity, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 11.5f
        setTextColor(Theme.textTer())
        setPadding(Display.dpInt(ctx, 6f), 0, 0, 0)
    }

    /**
     * 一行：标签（可空）+ 内容 + 「?」气泡。
     * 标签为空时内容整行铺满（勾选框与监听行属于这种）。
     */
    private fun fieldRow(ctx: Activity, label: String, content: android.view.View,
                         unit: android.view.View?, help: String): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Display.dpInt(ctx, 10f), Display.dpInt(ctx, 4f),
                Display.dpInt(ctx, 6f), Display.dpInt(ctx, 4f))
            if (label.isNotEmpty()) {
                addView(TextView(ctx).apply {
                    text = label
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Theme.textSec())
                    layoutParams = LinearLayout.LayoutParams(
                        Display.dpInt(ctx, 74f),
                        LinearLayout.LayoutParams.WRAP_CONTENT)
                })
            }
            addView(content)
            unit?.let { addView(it) }
            addView(TextView(ctx).apply {
                text = "?"
                textSize = 9.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Theme.textTer())
                gravity = Gravity.CENTER
                background = Theme.rect(Theme.surface2(), 9f, ctx)
                val sz = Display.dpInt(ctx, 17f)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    marginStart = Display.dpInt(ctx, 4f)
                }
                setOnClickListener { Ui.helpBubble(this, label.ifEmpty { "说明" }, help) }
            })
        }
}
