package com.autoball.core.recorder

import android.app.Activity
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.content.Context
import com.autoball.core.engine.Morph
import com.autoball.core.model.Flow
import com.autoball.core.util.Display
import com.autoball.ui.ListenerDialog
import com.autoball.ui.Theme
import com.autoball.float.FloatDialog
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
     * @param onSaved 点「确定」且校验通过后的回调（通常用于落库）
     */
    /**
     * 悬浮窗形态（默认）：直接在当前屏幕上弹出，不把用户拽回应用界面。
     *
     * 这是主路径——脚本正跑在别的应用上时调设置，跳回应用会把目标应用切走。
     * 无悬浮窗权限时回退到 Activity 弹窗。
     */
    fun showFloat(ctx: Context, flow: Flow, onSaved: (() -> Unit)? = null) {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val hook = buildBody(ctx, flow, box, true)
        val d = FloatDialog.show(ctx, "脚本全局设置")
            .body(box)
            .width(Theme.DIALOG_W + 24f)
            .negative("取消")
            .positive("确定") { if (hook()) { onSaved?.invoke(); true } else false }
        if (!d.show()) {
            val act = ctx as? Activity ?: return
            show(act, flow, onSaved)
        }
    }

    /** Activity 形态：无悬浮窗权限或需要复杂输入时的回退 */
    fun show(ctx: Activity, flow: Flow, onSaved: (() -> Unit)? = null) {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val hook = buildBody(ctx, flow, box, false)
        Ui.dialog(ctx, "脚本全局设置")
            .body(box)
            .width(Theme.DIALOG_W + 24f)
            .maxHeight(0.78f)
            .negative("取消") { }
            .positive("确定") { if (hook()) { onSaved?.invoke(); true } else false }
            .show()
    }

    /**
     * 构建表单体，返回「确定」时的提交回调。
     *
     * 抽出来是为了让悬浮窗形态与 Activity 形态共用同一份表单与取值逻辑——
     * 两处各写一遍必然出现参数口径不一致。
     */
    private fun buildBody(ctx: Context, flow: Flow, box: LinearLayout,
                          asFloat: Boolean): () -> Boolean {

        // 重复次数显示为「选填」= 单次。
        // 注意不能只看 loopCount：flow.loop 为 false 时即使 loopCount 是 0
        // 也只是"历史默认值"，含义是单次，不是无限。
        val singleRound = !flow.loop && flow.loopCount <= 1
        val repeat = if (singleRound) "" else flow.loopCount.toString()

        // ---- 默认等待 ----
        // 统一时长组件：数值 + 单位下拉（毫秒/秒/分钟），内部按毫秒存。
        // 此前固定按秒——想默认等 2 分钟得填 120，还得自己换算。
        //
        // **不再直接回写 flow**：此前 onChange 一触发就写进 flow，加上
        // 「失败暂停」是点一下立刻改 flow.failStop，于是点「取消」什么也还原不了
        // ——取消键形同虚设，用户以为放弃了修改，实际已经生效。
        // 现一律先落到局部变量，只在「确定」时统一提交。
        var waitMs = flow.defaultWaitMs
        val waitRow = com.autoball.ui.DurationField.row(ctx, "默认等待",
            flow.defaultWaitMs, H_WAIT) { ms -> waitMs = ms }
        box.addView(waitRow)

        // ---- 重复次数 ----
        val repeatEt = numInput(ctx, repeat, "选填")
        box.addView(fieldRow(ctx, "重复次数", repeatEt, null, H_REPEAT))

        // ---- 有动作失败立即暂停 ----
        var failStop = flow.failStop
        val failTv = TextView(ctx).apply {
            text = if (failStop) "☑" else "☐"
            textSize = 15f
            setTextColor(if (failStop) Theme.ok() else Theme.textTer())
            gravity = Gravity.CENTER
            setPadding(Display.dpInt(ctx, 6f), 0, Display.dpInt(ctx, 6f), 0)
        }
        val failRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Display.dpInt(ctx, 8f), Display.dpInt(ctx, 5f),
                Display.dpInt(ctx, 8f), Display.dpInt(ctx, 5f))
            setOnClickListener {
                failStop = !failStop
                failTv.text = if (failStop) "☑" else "☐"
                failTv.setTextColor(if (failStop) Theme.ok() else Theme.textTer())
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
                // **形态必须跟随本弹窗，不能看 ctx 是不是 Activity**。
                //
                // 本弹窗（全局设置）在悬浮窗层弹出，但调用方传进来的 ctx 常常
                // **就是 Activity**（工作台弹窗持有 activity 引用）。早前这里写
                // `act != null → ListenerDialog.show(act, ...)`，走成了 Activity 内的
                // AlertDialog —— 而此时用户正在桌面或别的应用上，Activity 在后台，
                // 对话框根本不显示，也不报错。表现为「点「未设置」一点用都没有」。
                //
                // 这正是「全局监听动作」主路径（工作台 ⚙）失效的根因。
                val act = ctx as? Activity
                val refresh = {
                    val (stages, n) = flow.hookSummary()
                    listenTv.text = if (stages == 0) "未设置" else "已设置 $stages 项 · $n 个动作"
                    listenTv.setTextColor(if (stages == 0) Theme.textTer() else Theme.pri2())
                }
                val actUsable = act != null && !act!!.isFinishing && !act!!.isDestroyed
                if (!asFloat && actUsable) ListenerDialog.show(act!!, flow) { refresh() }
                else ListenerDialog.showFloat(ctx, flow) { refresh() }
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

        return {
            val v = morphEt.text.toString().trim()
            if (v.isNotEmpty() && !Morph.valid(v)) {
                Ui.toast(ctx, "手势变形格式不正确，应为 a,b,c,d,e,f")
                false
            } else {
                flow.morph = v
                flow.defaultWaitMs = (waitRow.tag as? () -> Long)?.invoke() ?: waitMs
                flow.failStop = failStop
                // 默认等待由 DurationField 直接回写 flow.defaultWaitMs，这里不再二次读取
                //
                // **重复次数此前完全不生效**（第 5 类失效：界面能存、运行时不看）：
                // FlowRunner 的循环条件是 `if (!flow.loop) break` —— 必须 flow.loop
                // 为 true 才会进入下一轮，而这里只写了 loopCount、从来没置 loop。
                // 填了 5 次，脚本照样只跑一轮，且没有任何提示。
                // 现在按"是否多于一轮"反推 loop：0（无限）与 >1 都需要开启循环。
                val r = repeatEt.text.toString().trim().toIntOrNull()
                flow.loopCount = r ?: 1      // 留空按帮助文案取 1 次，不能取 0（那是无限）
                flow.loop = flow.loopCount != 1
                true
            }
        }
    }

    // ---------- 复用件 ----------

    private fun numInput(ctx: Context, value: String, hint: String): EditText =
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

    private fun unitView(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 11.5f
        setTextColor(Theme.textTer())
        setPadding(Display.dpInt(ctx, 6f), 0, 0, 0)
    }

    /**
     * 一行：标签（可空）+ 内容 + 「?」气泡。
     * 标签为空时内容整行铺满（勾选框与监听行属于这种）。
     */
    private fun fieldRow(ctx: Context, label: String, content: android.view.View,
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
