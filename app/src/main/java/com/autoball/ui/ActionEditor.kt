package com.autoball.ui

import android.app.Activity
import android.content.Context
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.core.model.Action
import com.autoball.core.model.ActionPreset
import com.autoball.core.model.ActionType
import com.autoball.core.model.ControlOp
import com.autoball.core.util.Display

/**
 * 添加 / 编辑动作——**一比一对齐自动精灵**的动作编辑弹窗。
 *
 * 自动精灵的字段布局（每行：标签 → 值右对齐 → 可选单位 → 可选拾取按钮 → ? 帮助）：
 * ```
 * 动作类型   [点击]                    ?
 * 点击位置   [(25.6%, 22.6%)]  [...]   ?
 * 按下时间   [选填]  毫秒      [...]   ?
 * 运行等待   [选填]  秒                ?
 * 重复次数   [选填]                    ?
 * 运行条件   [未设置]                  ?
 *                      取消    确定
 * ```
 *
 * 两个与旧版的关键差异：
 * 1. 数值字段**不给默认值**，一律留空显示「选填」——自动精灵如此，
 *    且默认值会掩盖"用户根本没配过"这一事实。
 * 2. 坐标统一用**百分比**显示与拾取，换机型不点偏。
 *
 * 动作类型按自动精灵的分组宫格呈现（基础触摸 / 识别定位 / 系统操作 / 高级），
 * 并带预设参数——例如选「长按」就是 CLICK + 700ms，选「返回键」就是 KEY + code 4，
 * 用户不必再手工填按键码。
 */
object ActionEditor {

    // =====================================================================
    // 动作选项（自动精灵分组 + 预设参数）
    // =====================================================================

    /**
     * 回显用预设。动作预设已统一到 [com.autoball.core.model.ActionPreset]——
     * 早前本文件与 ToolPanel 各存一份，按键码硬编码两处，改一处就会漏另一处。
     */
    private fun optionOf(a: Action): ActionPreset =
        ActionPreset.byLabel(a)

    // =====================================================================
    // 摘要
    // =====================================================================

    /** 一行摘要：用于步骤列表与日志（百分比坐标，与自动精灵一致） */
    fun describe(a: Action): String {
        val p = { v: Float -> "%.1f%%".format(v) }
        return when (a.type) {
            ActionType.CLICK, ActionType.CLICK_IMAGE, ActionType.CLICK_TEXT,
            ActionType.CLICK_COLOR, ActionType.CLICK_NODE, ActionType.AI_CLICK ->
                "${a.optionLabel ?: "点击"}(${p(a.x)}, ${p(a.y)})"
            ActionType.SWIPE, ActionType.GESTURE_SINGLE, ActionType.GESTURE_MULTI ->
                "滑动(${p(a.x)}, ${p(a.y)})→(${p(a.x2)}, ${p(a.y2)})"
            ActionType.INPUT_TEXT -> "输入「${a.text ?: ""}」"
            ActionType.OPEN_APP -> "打开应用 ${a.pkg ?: ""}"
            ActionType.OPEN_URL -> "打开链接 ${a.url ?: ""}"
            ActionType.KEY -> a.optionLabel ?: "按键 ${a.keyCode}"
            ActionType.RUN_JS -> "JS ${a.code?.length ?: 0} 字符"
            ActionType.SET_VAR -> "${a.varName} = ${a.varValue}"
            ActionType.TOAST -> "提示「${a.text ?: ""}」"
            ActionType.CONTROL_FLOW -> a.controlOp.label
            ActionType.RECOGNIZE_SCREEN -> "识别屏幕"
            ActionType.RUN_SCRIPT -> "子脚本"
            ActionType.RUN_ACTIONS -> "${a.subActions.size} 个子动作"
        }.let { base ->
            val extra = ArrayList<String>()
            if (a.repeat > 1) extra.add("×${a.repeat}")
            if (a.condition != null) extra.add("有条件")
            if (extra.isEmpty()) base else "$base · ${extra.joinToString(" ")}"
        }
    }

    @Volatile
    private var gridDlg: Dialog? = null


    @Volatile
    private var flowRef: com.autoball.core.model.Flow? = null

    fun show(activity: Activity, existing: Action?, onSave: (Action) -> Unit) =
        show(activity, existing, null, onSave)

    fun show(activity: Activity, existing: Action?, flow: com.autoball.core.model.Flow?,
             onSave: (Action) -> Unit) {
        flowRef = flow
        val a = existing ?: Action().apply {
            id = Action.newId()
            type = ActionType.CLICK
            x = 50f; y = 50f
            optionLabel = "点击"
        }
        showForm(activity, a, onSave)
    }

    /**
     * 悬浮窗形态：**在当前屏幕上直接弹出动作编辑框，不回应用界面**。
     *
     * 自动精灵正是如此——录制/添加动作时用户正在操作别的应用，
     * 跳回应用会把目标应用切走，等于白操作一遍。
     * 无悬浮窗权限时自动回退到 [show]。
     */
    fun showFloat(ctx: android.content.Context, existing: Action?,
                  flow: com.autoball.core.model.Flow?, onSave: (Action) -> Unit) {
        flowRef = flow
        val a = existing ?: Action().apply {
            id = Action.newId()
            type = ActionType.CLICK
            x = 50f; y = 50f
            optionLabel = "点击"
        }
        val (box, submit) = buildForm(ctx, a)
        val d = com.autoball.float.FloatDialog.show(ctx, "编辑动作")
            .body(box)
            .width(Theme.DIALOG_W + 24f)
            .negative("取消") { }
            .positive("确定") {
                submit()
                if (a.type == ActionType.CLICK && a.durationMs <= 0L) a.durationMs = 60L
                onSave(a)
                true
            }
        if (!d.show()) {
            val act = ctx as? Activity ?: return
            show(act, existing, flow, onSave)
        }
    }

    // =====================================================================
    // 自动精灵风格表单
    // =====================================================================

    private fun showForm(activity: Activity, a: Action, onSave: (Action) -> Unit) {
        val (box, submit) = buildForm(activity, a)
        // 当前显示哪一屏：表单 或 类型列表（就地换页，共用同一个 Dialog）
        var page = 0
        var dlg: Dialog? = null
        var host: android.widget.ScrollView? = null
        var titleTv: TextView? = null

        fun showFormPage() {
            page = 0
            titleTv?.text = "编辑动作"
            host?.removeAllViews()
            host?.addView(box, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        fun showTypePage() {
            page = 1
            val h = host ?: return
            val t = titleTv ?: return
            showTypeList(activity, a, h, t) { showFormPage() }
        }
        // 表单里点「动作类型」→ 切到类型列表页
        onPickType = { showTypePage() }

        Ui.dialog(activity, "编辑动作")
            .body(box)
            .width(Theme.DIALOG_W + 24f)
            .maxHeight(0.78f)
            .negative("取消") { }
            .positive("确定") {
                // 停在类型列表页时「确定」当作返回表单，避免误把未确认的类型写回
                if (page != 0) {
                    showFormPage()
                    false
                } else {
                    submit()
                    if (a.type == ActionType.CLICK && a.durationMs <= 0L) a.durationMs = 60L
                    onSave(a)
                    true
                }
            }
            .onReady { d, content, tv ->
                dlg = d
                host = content
                titleTv = tv
            }
            .show()
    }

    /** 由表单注入：点「动作类型」时切页（避免表单持有弹窗引用） */
    private var onPickType: (() -> Unit)? = null

    /**
     * 构建表单，返回 (视图, 提交回调)。
     *
     * 抽出来让应用内弹窗与悬浮窗两种形态共用同一份表单与取值——
     * 两处各写一遍必然出现参数口径不一致。
     */
    private fun buildForm(ctx: android.content.Context, a: Action): Pair<View, () -> Unit> {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var dialog: Dialog? = null

        // 提交前从各输入行回读；用 key 精确定位，避免「第几个输入框」错位
        val readers = LinkedHashMap<String, () -> Unit>()

        fun rebuild() {
            box.removeAllViews()
            val opt = optionOf(a)

            // ---- 动作类型 ----
            box.addView(zsRow(ctx, "动作类型",
                valueView(ctx, opt.label, opt.label != "未设置"),
                null,
                pick = { onPickType?.invoke() },
                help = "共 ${ActionPreset.ALL.size} 种动作，按 " +
                    ActionPreset.GROUPS.joinToString(" / ") + " 分组；\n" +
                    "选中即套用预设参数（如长按 700ms、返回键 code 4）。"))

            // ---- 坐标类字段 ----
            val g = a.type.fieldGroups
            if (g.contains(com.autoball.core.model.FieldGroup.POINT)) {
                box.addView(zsRow(ctx, "点击位置",
                    valueView(ctx, "(${pct(a.x)}, ${pct(a.y)})", true),
                    null,
                    pick = {
                        CoordPicker.pick(ctx, ctx as? Activity, dialog) { px, py ->
                            a.x = px; a.y = py; rebuild()
                        }
                    },
                    help = "百分比坐标，换机型与转屏都不会点偏。\n" +
                        "点右侧「⋯」在全屏选点：按住拖动可微调，底部显示实时坐标。"))
            }
            if (g.contains(com.autoball.core.model.FieldGroup.POINT_END)) {
                box.addView(zsRow(ctx, "结束位置",
                    valueView(ctx, "(${pct(a.x2)}, ${pct(a.y2)})",
                        a.x2 != 0f || a.y2 != 0f),
                    null,
                    pick = {
                        RegionPicker.pick(ctx, ctx as? Activity, dialog) { l, t, r, b ->
                            a.x = l; a.y = t; a.x2 = l + r; a.y2 = t + b; rebuild()
                        }
                    },
                    help = "框选终点区域：一次填满起点与终点两个坐标。"))
            }

            // ---- 数值字段：一律「选填」，不给默认值 ----
            if (g.contains(com.autoball.core.model.FieldGroup.PRESS_DURATION) ||
                g.contains(com.autoball.core.model.FieldGroup.DURATION)) {
                val unit = if (a.type == ActionType.SWIPE ||
                    a.type == ActionType.GESTURE_SINGLE ||
                    a.type == ActionType.GESTURE_MULTI) "毫秒(时长)" else "毫秒"
                val et = numField(ctx, a.durationMs.takeIf { it > 0 }?.toString() ?: "", "选填")
                readers["duration"] = {
                    a.durationMs = et.text.toString().trim().toLongOrNull() ?: 0L
                }
                box.addView(zsRow(ctx, if (a.type == ActionType.SWIPE ||
                    a.type == ActionType.GESTURE_SINGLE ||
                    a.type == ActionType.GESTURE_MULTI) "滑动时长" else "按下时间",
                    et, unit, null,
                    help = "留空则用脚本全局设置的默认时长。\n" +
                        "长按建议 500～800 毫秒，滑动建议 300～600 毫秒。"))
            }

            // 运行等待（自动精灵：选填 秒）
            val waitEt = numField(ctx,
                a.waitMs.takeIf { it > 0 }?.let { (it / 1000f).toString() } ?: "", "选填")
            readers["wait"] = {
                val v = waitEt.text.toString().trim().toFloatOrNull()
                a.waitMs = if (v == null || v <= 0f) 0L else (v * 1000).toLong()
            }
            box.addView(zsRow(ctx, "运行等待", waitEt, "秒", null,
                help = "该动作执行完后再等待多久才继续下一个。\n" +
                    "单位秒，可填小数（如 0.5）。留空表示不额外等待。"))

            // 重复次数
            val repEt = numField(ctx, a.repeat.takeIf { it > 0 }?.toString() ?: "", "选填")
            readers["repeat"] = { a.repeat = repEt.text.toString().trim().toIntOrNull() ?: 0 }
            box.addView(zsRow(ctx, "重复次数", repEt, "次", null,
                help = "该动作重复执行几次。留空按 1 次。\n" +
                    "连续点击可填 5～20，配合间隔使用。"))

            if (a.repeat > 1) {
                val ivEt = numField(ctx,
                    a.repeatIntervalMs.takeIf { it > 0 }?.toString() ?: "", "选填")
                readers["interval"] = {
                    a.repeatIntervalMs = ivEt.text.toString().trim().toLongOrNull() ?: 0L
                }
                box.addView(zsRow(ctx, "重复间隔", ivEt, "毫秒", null,
                    help = "每次重复之间的间隔。留空则不等待。"))
            }

            // ---- 文本 / 包名 / 代码等 ----
            if (g.contains(com.autoball.core.model.FieldGroup.TEXT)) {
                val et = textField(ctx, a.text ?: "", "选填")
                readers["text"] = { a.text = et.text.toString() }
                box.addView(zsRow(ctx,
                    if (a.type == ActionType.CLICK_TEXT) "目标文字" else "输入内容",
                    et, null, null,
                    help = "留空则运行时提示输入。"))
            }
            if (g.contains(com.autoball.core.model.FieldGroup.PACKAGE)) {
                val et = textField(ctx, a.pkg ?: "", "选填")
                readers["pkg"] = { a.pkg = et.text.toString() }
                box.addView(zsRow(ctx, "目标应用", et, null, null,
                    help = "包名，如 com.tencent.mm。留空则打开当前应用。"))
            }
            if (g.contains(com.autoball.core.model.FieldGroup.KEYCODE)) {
                val et = numField(ctx, a.keyCode.takeIf { it != 0 }?.toString() ?: "", "选填")
                readers["key"] = { a.keyCode = et.text.toString().trim().toIntOrNull() ?: 0 }
                box.addView(zsRow(ctx, "按键码", et, null, null,
                    help = "常用：${ActionPreset.KeyCode.HOME}=HOME  " +
                        "${ActionPreset.KeyCode.BACK}=返回  " +
                        "${ActionPreset.KeyCode.RECENTS}=最近任务  " +
                        "${ActionPreset.KeyCode.EXPAND_STATUS}=下拉状态栏\n" +
                        "选预设动作时会自动填好，一般无需手工输入。"))
            }
            if (g.contains(com.autoball.core.model.FieldGroup.CODE)) {
                val et = textField(ctx, a.code ?: "", "JS 代码")
                readers["code"] = { a.code = et.text.toString() }
                box.addView(zsRow(ctx, "JS 代码", et, null, null,
                    help = "可调用 click / swipe / key / wait 等宿主 API。"))
            }
            if (g.contains(com.autoball.core.model.FieldGroup.CONTROL)) {
                box.addView(zsRow(ctx, "控制方式",
                    valueView(ctx, a.controlOp.label, true), null,
                    pick = {
                        Ui.popMenu(ctx, box, ControlOp.values().map { it.label },
                            ControlOp.values().indexOf(a.controlOp)) { i ->
                            a.controlOp = ControlOp.values()[i]
                            rebuild()
                        }
                    },
                    help = "暂停 / 继续 / 停止 / 跳转 / 等待。"))
            }

            // ---- 运行条件（自动精灵独立一行）----
            box.addView(Ui.adSec(ctx))
            box.addView(zsRow(ctx, "运行条件",
                valueView(ctx, if (a.condition != null) "已设置" else "未设置",
                    a.condition != null),
                null,
                pick = {
                    (ctx as? Activity)?.let { act ->
                        ConditionDialog.show(act, a) { rebuild() }
                    }
                },
                help = "不检测 / 图片存在 / 文字存在 / 颜色存在 / JS 表达式。\n" +
                    "条件不成立时可跳过、等待重试或停止脚本。"))

            // ---- 监听动作（自动精灵同款：动作级钩子）----
            // 脚本级 9 个时机在「脚本全局设置」里；这里是**本动作**的钩子，
            // 两者粒度不同，不能互相替代。
            val lc = a.listeners?.size ?: 0
            box.addView(zsRow(ctx, "监听动作",
                valueView(ctx, if (lc > 0) "已设置 $lc 项" else "未设置", lc > 0),
                null,
                pick = {
                    (ctx as? Activity)?.let { act ->
                        ListenerDialog.show(act, a) { rebuild() }
                    }
                },
                help = "本动作执行前后挂载的动作（截图、日志、兜底）。\n" +
                    "与「脚本全局设置 → 全局监听动作」的区别：那作用于整段脚本，" +
                    "这里只作用于当前动作。"))

            // ---- 动作描述（备注，自动精灵在末尾一行）----
            val descEt = textField(ctx, a.desc ?: "", "选填")
            readers["desc"] = { a.desc = descEt.text.toString().trim() }
            box.addView(zsRow(ctx, "动作描述", descEt, null, null,
                help = "仅作备注，不影响执行；便于日后回看脚本时理解每一步在做什么。"))
        }

        rebuild()
        return box to { readers.values.forEach { it() } }
    }

    // =====================================================================
    // 分组宫格：仿自动精灵的动作类型选择
    // =====================================================================

    /**
     * 动作类型选择——**列表展现**（自动精灵「更多工具」即为此形态）。
     *
     * 早前用宫格，22 项挤在 3 列里，每项只剩两个字，说明文字全被砍掉；
     * 列表能同时显示图标、名称与用途，选错的概率更低。
     */
    private fun showTypeList(ctx: android.content.Context, a: Action,
                             host: android.widget.ScrollView, titleTv: TextView,
                             onChange: () -> Unit) {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val groups = ActionPreset.GROUPS
        var cur = groups.indexOf(optionOf(a).group).takeIf { it >= 0 } ?: 0
        val listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        fun fill() {
            listBox.removeAllViews()
            ActionPreset.ofGroup(groups[cur]).forEach { opt ->
                listBox.addView(listItem(ctx, opt.label, opt.type.label,
                    opt.label == optionOf(a).label) {
                    a.type = opt.type
                    a.optionLabel = opt.label
                    opt.preset(a)
                    onChange()
                })
            }
        }

        box.addView(Kit.segment(ctx, groups, cur) { i -> cur = i; fill() })
        box.addView(listBox, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Display.dpInt(ctx, 8f)
        })
        fill()
        box.addView(TextView(ctx).apply {
            text = "坐标均为百分比，换机型与转屏都不会点偏；带预设的动作已填好常用参数。"
            textSize = 10.5f
            setTextColor(Theme.textTer())
            setPadding(0, Display.dpInt(ctx, 6f), 0, Display.dpInt(ctx, 4f))
        })
        box.addView(TextView(ctx).apply {
            text = "返回"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textSec())
            gravity = Gravity.CENTER
            background = Theme.rect(Theme.surface2(), 10f, ctx, Theme.line())
            setPadding(Display.dpInt(ctx, 10f), Display.dpInt(ctx, 9f),
                Display.dpInt(ctx, 10f), Display.dpInt(ctx, 9f))
            setOnClickListener { onChange() }
        })

        // 就地换页：把外层「编辑动作」的内容容器换成类型列表。
        // 另开 Dialog 会与外层争同一窗口层级而被遮住，这里从根上避开。
        host.removeAllViews()
        host.addView(box, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        titleTv.text = "选择动作类型"
    }

    /** 列表项：图标 + 名称 + 说明 + 右侧选中标记 */
    private fun listItem(ctx: android.content.Context, title: String, sub: String,
                         selected: Boolean, onClick: () -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.rect(
                if (selected) Theme.surface2() else Theme.surface(), 10f, ctx,
                if (selected) Theme.pri() else Theme.line())
            setPadding(Display.dpInt(ctx, 10f), Display.dpInt(ctx, 9f),
                Display.dpInt(ctx, 10f), Display.dpInt(ctx, 9f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, Display.dpInt(ctx, 3f), 0, Display.dpInt(ctx, 3f))
            layoutParams = lp
            setOnClickListener { onClick() }

            addView(TextView(ctx).apply {
                text = title.take(2)
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (selected) Theme.pri() else Theme.textSec())
                gravity = Gravity.CENTER
                background = Theme.oval(if (selected) Theme.pri2() else Theme.surface2())
                layoutParams = LinearLayout.LayoutParams(
                    Display.dpInt(ctx, 32f), Display.dpInt(ctx, 32f))
            })
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(ctx).apply {
                text = title
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (selected) Theme.pri() else Theme.textPri())
            })
            col.addView(TextView(ctx).apply {
                text = sub
                textSize = 10.5f
                setTextColor(Theme.textTer())
                setPadding(0, Display.dpInt(ctx, 2f), 0, 0)
            })
            addView(col, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Display.dpInt(ctx, 10f)
            })
            if (selected) addView(TextView(ctx).apply {
                text = "✓"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.pri())
            })
        }

    // =====================================================================
    // 行与控件（自动精灵风格）
    // =====================================================================

    /**
     * 自动精灵风格字段行：
     * `标签  [值/输入框]  单位  ⋯  ?`
     *
     * 值区右对齐占满剩余宽度；单位灰色小字；「⋯」为拾取/选择入口；
     * 末尾「?」统一弹 [Ui.helpBubble]。
     */
    private fun zsRow(ctx: android.content.Context, label: String, value: View,
                      unit: String?, pick: (() -> Unit)?, help: String): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Display.dpInt(ctx, 8f), Display.dpInt(ctx, 4f),
                Display.dpInt(ctx, 4f), Display.dpInt(ctx, 4f))
            if (pick != null) setOnClickListener { pick() }

            addView(TextView(ctx).apply {
                text = label
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.textSec())
                layoutParams = LinearLayout.LayoutParams(
                    Display.dpInt(ctx, 66f),
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            addView(value, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            if (unit != null) {
                addView(TextView(ctx).apply {
                    text = unit
                    textSize = 10.5f
                    setTextColor(Theme.textTer())
                    setPadding(Display.dpInt(ctx, 5f), 0, 0, 0)
                })
            }
            if (pick != null) {
                addView(TextView(ctx).apply {
                    text = "⋯"
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Theme.pri2())
                    gravity = Gravity.CENTER
                    background = Theme.rect(Theme.surface2(), 8f, ctx)
                    val sz = Display.dpInt(ctx, 22f)
                    layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                        marginStart = Display.dpInt(ctx, 4f)
                    }
                    setOnClickListener { pick() }
                })
            }
            addView(TextView(ctx).apply {
                text = "?"
                textSize = 9.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.textTer())
                gravity = Gravity.CENTER
                background = Theme.rect(Theme.surface2(), 9f, ctx)
                val sz = Display.dpInt(ctx, 17f)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    marginStart = Display.dpInt(ctx, 4f)
                }
                setOnClickListener {
                    Ui.helpBubble(this, label, help)
                }
            })
        }

    /** 只读值（点整行或「⋯」触发选择） */
    private fun valueView(ctx: android.content.Context, text: String, set: Boolean): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (set) Theme.pri2() else Theme.textTer())
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            background = Theme.rect(
                if (set) Theme.surface2() else Color.TRANSPARENT, 6f, ctx)
            setPadding(Display.dpInt(ctx, 7f), Display.dpInt(ctx, 6f),
                Display.dpInt(ctx, 7f), Display.dpInt(ctx, 6f))
        }

    /** 数值输入：hint 显示「选填」，不给默认值 */
    private fun numField(ctx: android.content.Context, value: String, hint: String): EditText =
        EditText(ctx).apply {
            setText(value)
            this.hint = hint
            setHintTextColor(Theme.textTer())
            setTextColor(Theme.textPri())
            textSize = 11.5f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            background = Theme.rect(Theme.surface2(), 6f, ctx, Theme.line())
            setPadding(Display.dpInt(ctx, 8f), Display.dpInt(ctx, 6f),
                Display.dpInt(ctx, 8f), Display.dpInt(ctx, 6f))
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }

    private fun textField(ctx: android.content.Context, value: String, hint: String): EditText =
        EditText(ctx).apply {
            setText(value)
            this.hint = hint
            setHintTextColor(Theme.textTer())
            setTextColor(Theme.textPri())
            textSize = 11.5f
            setSingleLine(true)
            background = Theme.rect(Theme.surface2(), 6f, ctx, Theme.line())
            setPadding(Display.dpInt(ctx, 8f), Display.dpInt(ctx, 6f),
                Display.dpInt(ctx, 8f), Display.dpInt(ctx, 6f))
        }

    private fun pct(v: Float): String = "%.1f".format(v)
}
