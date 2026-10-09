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
import com.autoball.core.model.ActionHelp
import com.autoball.core.model.ActionHookStage
import com.autoball.core.model.ActionPreset
import com.autoball.core.model.ActionType
import com.autoball.core.model.ControlOp
import com.autoball.core.model.FailOp
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

    /**
     * 像素 → 百分比。
     *
     * 取点/取区控件回调的是**像素**，而 [Action] 的坐标字段一律存百分比
     * （换机型、转屏都不会点偏）。每次用到回调值都要过这一层，
     * 直接存像素会出现 "612%" 这种荒谬值。
     */
    /**
     * 识别屏幕的实时预览（R-120）。
     *
     * 该动作此前只有参数、没有任何反馈——用户配完不知道会识别出什么，
     * 只能跑一遍脚本再看日志。这里直接调后端跑一次并弹窗展示。
     *
     * 走后台线程：识别涉及截图与遍历节点树，放主线程会卡住界面。
     */
    private fun previewRecognize(ctx: android.content.Context, a: Action) {
        Ui.toast(ctx, "正在识别当前屏幕…")
        Thread {
            val text = try {
                val be = com.autoball.core.backend.AccessibilityBackend()
                val ectx = com.autoball.core.backend.ExecContext("preview")
                val r = be.execute(a, ectx)
                if (r.ok) {
                    ectx.getVar(a.varName ?: "screen")?.takeIf { it.isNotBlank() }
                        ?: r.message ?: "(识别完成，但没有拿到文本)"
                } else {
                    // ActionResult 的失败原因是 cause（不是 reason）
                    "(识别失败：${r.cause ?: r.message ?: "未知原因"})"
                }
            } catch (e: Throwable) { "(预览失败：${e.message ?: "未知"})" }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Ui.dialog(ctx, "识别结果")
                    .body(android.widget.TextView(ctx).apply {
                        this.text = text
                        setTextIsSelectable(true)
                        textSize = 12.5f
                        setTextColor(Theme.textPri())
                        setPadding(Display.dpInt(ctx, 12f), Display.dpInt(ctx, 10f),
                            Display.dpInt(ctx, 12f), Display.dpInt(ctx, 10f))
                    })
                    .maxHeight(0.7f)
                    .positive("关闭") { true }
                    .show()
            }
        }.start()
    }

    private fun pctOf(px: Float, py: Float): Pair<Float, Float> {
        val sz = com.autoball.core.util.Display.screenSize(com.autoball.App.get())
        val w = sz.x.toFloat().coerceAtLeast(1f)
        val h = sz.y.toFloat().coerceAtLeast(1f)
        return (px / w * 100f).coerceIn(0f, 100f) to (py / h * 100f).coerceIn(0f, 100f)
    }

    /** 一行摘要：用于步骤列表与日志（百分比坐标，与自动精灵一致） */
    fun describe(a: Action): String {
        val p = { v: Float -> "%.1f%%".format(v) }
        return when (a.type) {
            ActionType.CLICK, ActionType.CLICK_IMAGE, ActionType.CLICK_TEXT,
            ActionType.CLICK_COLOR, ActionType.CLICK_NODE, ActionType.AI_CLICK ->
                "${a.optionLabel ?: "点击"}(${p(a.x)}, ${p(a.y)})"
            ActionType.CLICK_AREA ->
                "区域随机(${p(a.x)}, ${p(a.y)})~(${p(a.x2)}, ${p(a.y2)})"
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

    /**
     * 当前表单视图。
     *
     * 必须是成员而不是局部变量：局部**函数**不能前向引用后面才声明的局部变量，
     * 而 `showFormPage()` 需要在 `buildForm()` 之前定义（与 showTypePage 相互调用）。
     */
    private var boxRef: View? = null

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
        // 与 showForm 同样的两屏切换（表单 / 类型列表），共用同一个弹窗：
        // 悬浮窗形态此前根本没有类型页，点「动作类型」会掉进上一次
        // showForm 遗留的闭包里（见 buildForm 注释）
        var page = 0
        var host: android.widget.ScrollView? = null
        var titleTv: TextView? = null
        var rebuild: () -> Unit = {}
        // 先声明再赋值：局部**函数**不能前向引用尚未声明的局部变量
        var formView: View? = null
        var submit: () -> Unit = {}
        fun showFormPage() {
            page = 0
            titleTv?.text = "编辑动作"
            rebuild()          // 先按当前 a.type 重排字段，再挂回去
            val b = formView ?: return
            host?.removeAllViews()
            host?.addView(b, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        fun showTypePage() {
            val h = host ?: return
            val t = titleTv ?: return
            page = 1
            showTypeList(ctx, a, h, t) { showFormPage() }
        }
        val (box, sb, rb) = buildForm(ctx, a) { showTypePage() }
        formView = box
        submit = sb
        rebuild = rb

        val d = com.autoball.float.FloatDialog.show(ctx, "编辑动作")
            .body(box)
            .width(Theme.DIALOG_W + 24f)
            .onReady { _, content, tv -> host = content; titleTv = tv }
            .negative("取消") { }
            .positive("确定") {
                if (page != 0) {
                    // 停在类型列表页时「确定」当作返回表单，
                    // 避免误把没确认过的类型写回
                    showFormPage(); false
                } else {
                    submit()
                    if (a.type == ActionType.CLICK && a.durationMs <= 0L) a.durationMs = 60L
                    onSave(a)
                    true
                }
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
        // 当前显示哪一屏：表单 或 类型列表（就地换页，共用同一个 Dialog）
        var page = 0
        var dlg: Dialog? = null
        var host: android.widget.ScrollView? = null
        var titleTv: TextView? = null
        var rebuild: () -> Unit = {}
        var submit: () -> Unit = {}

        fun showFormPage() {
            page = 0
            titleTv?.text = "编辑动作"
            rebuild()      // 类型可能刚变过，字段必须按新类型重排
            val b = boxRef ?: return
            host?.removeAllViews()
            host?.addView(b, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        fun showTypePage() {
            page = 1
            val h = host ?: return
            val t = titleTv ?: return
            showTypeList(activity, a, h, t) { showFormPage() }
        }
        val (box, sb, rb) = buildForm(activity, a) { showTypePage() }
        boxRef = box
        submit = sb
        rebuild = rb

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

    /**
     * 构建表单，返回 (视图, 提交回调)。
     *
     * 抽出来让应用内弹窗与悬浮窗两种形态共用同一份表单与取值——
     * 两处各写一遍必然出现参数口径不一致。
     */
    /**
     * @param onPickType 点「动作类型」时的切页回调。**必须显式传入**：
     *   早前用的是成员变量 `onPickType`，而 showFloat（悬浮窗形态）从不给它赋值，
     *   于是点到的是**上一次 showForm 留下的闭包**——
     *   那个闭包持有旧 Activity 的宿主容器和**旧动作对象**，
     *   选中新类型后改的是旧对象，当前正在编辑的动作纹丝不动。
     *   表现就是"动作类型选了不生效"，而且完全静默（连报错都没有）。
     * @return (表单视图, 提交回调, 重建回调)。第三个必须返回：
     *   切换类型后表单字段要按新类型重排，只把旧 box 加回宿主
     *   显示的仍是旧类型的字段。
     */
    private fun buildForm(ctx: android.content.Context, a: Action,
                          onPickType: (() -> Unit)? = null)
            : Triple<View, () -> Unit, () -> Unit> {
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
                help = ActionHelp.type(a)))

            // ---- 坐标类字段 ----
            val g = a.type.fieldGroups
            if (g.contains(com.autoball.core.model.FieldGroup.POINT)) {
                // 标签也随类型变：「滑动」下叫「开始位置」更准确
                val posLabel = if (a.type == ActionType.SWIPE ||
                    a.type == ActionType.GESTURE_SINGLE ||
                    a.type == ActionType.GESTURE_MULTI) "开始位置" else "点击位置"
                box.addView(zsRow(ctx, posLabel,
                    valueView(ctx, "(${pct(a.x)}, ${pct(a.y)})", true),
                    null,
                    pick = {
                        CoordPicker.pick(ctx, ctx as? Activity, dialog) { px, py ->
                            a.x = px; a.y = py; rebuild()
                        }
                    },
                    help = ActionHelp.point(a)))
            }
            if (g.contains(com.autoball.core.model.FieldGroup.POINT_END)) {
                box.addView(zsRow(ctx, "结束位置",
                    valueView(ctx, "(${pct(a.x2)}, ${pct(a.y2)})",
                        a.x2 != 0f || a.y2 != 0f),
                    null,
                    pick = {
                        RegionPicker.pick(ctx, ctx as? Activity, dialog) { l, t, r, b ->
                            // RegionPicker 回调的是**像素**，而 Action 存的是百分比。
                            // 此前直接存像素，于是出现了 "点击(612.0%, 1344.0%)" 这种值，
                            // 且 `l + r` 把右边界当宽度相加——两个错误叠加。
                            val (px, py) = pctOf(l, t)
                            val (qx, qy) = pctOf(r, b)
                            a.x = px; a.y = py; a.x2 = qx; a.y2 = qy; rebuild()
                        }
                    },
                    help = ActionHelp.pointEnd(a)))
            }

            if (g.contains(com.autoball.core.model.FieldGroup.AREA)) {
                box.addView(zsRow(ctx, "随机区域",
                    valueView(ctx, "(${pct(a.x)}, ${pct(a.y)})~(${pct(a.x2)}, ${pct(a.y2)})",
                        a.x2 > a.x || a.y2 > a.y),
                    null,
                    pick = {
                        RegionPicker.pick(ctx, ctx as? Activity, dialog) { l, t, r, b ->
                            val (px, py) = pctOf(l, t)
                            val (qx, qy) = pctOf(r, b)
                            a.x = px; a.y = py; a.x2 = qx; a.y2 = qy; rebuild()
                        }
                    },
                    help = ActionHelp.area(a)))
            }

            // ---- 数值字段：一律「选填」，不给默认值 ----
            if (g.contains(com.autoball.core.model.FieldGroup.PRESS_DURATION) ||
                g.contains(com.autoball.core.model.FieldGroup.DURATION)) {
                val lbl = if (a.type == ActionType.SWIPE ||
                    a.type == ActionType.GESTURE_SINGLE ||
                    a.type == ActionType.GESTURE_MULTI) "滑动时长" else "按下时间"
                // 统一时长组件：数值 + 单位下拉（毫秒/秒/分钟），内部按毫秒存
                box.addView(DurationField.row(ctx, lbl, a.durationMs,
                    ActionHelp.duration(a)) { ms ->
                        a.durationMs = ms
                    })
            }

            // 运行等待：自动精灵带单位下拉，此前固定按秒——想等 2 分钟得填 120
            box.addView(DurationField.row(ctx, "运行等待", a.waitMs,
                ActionHelp.wait(a)) { ms ->
                    a.waitMs = ms
                })

            // 重复次数
            val repEt = numField(ctx, a.repeat.takeIf { it > 0 }?.toString() ?: "", "选填")
            readers["repeat"] = { a.repeat = repEt.text.toString().trim().toIntOrNull() ?: 0 }
            box.addView(zsRow(ctx, "重复次数", repEt, "次", null,
                help = ActionHelp.repeat(a)))

            if (a.repeat > 1) {
                val ivEt = numField(ctx,
                    a.repeatIntervalMs.takeIf { it > 0 }?.toString() ?: "", "选填")
                readers["interval"] = {
                    a.repeatIntervalMs = ivEt.text.toString().trim().toLongOrNull() ?: 0L
                }
                box.addView(zsRow(ctx, "重复间隔", ivEt, "毫秒", null,
                    help = ActionHelp.interval(a)))
            }

            // ---- 文本 / 包名 / 代码等 ----
            if (g.contains(com.autoball.core.model.FieldGroup.TEXT)) {
                val et = textField(ctx, a.text ?: "", "选填")
                readers["text"] = { a.text = et.text.toString() }
                box.addView(zsRow(ctx,
                    if (a.type == ActionType.CLICK_TEXT) "目标文字" else "输入内容",
                    et, null, null,
                    help = ActionHelp.text(a)))
            }
            // 识别屏幕：结果要存进变量，否则后续动作拿不到（R-120）
            if (a.type == ActionType.RECOGNIZE_SCREEN) {
                val et = textField(ctx, a.varName ?: "", "screen")
                readers["varName"] = { a.varName = et.text.toString().trim().ifEmpty { null } }
                box.addView(zsRow(ctx, "存到变量", et, null, null,
                    help = ActionHelp.varName(a)))
                // 实时预览：这个动作此前只有参数、没有反馈，
                // 用户无法确认"到底识别出了什么"，等于盲配
                // pick 必须显式命名：zsRow 的尾随 lambda 会绑到 pick 参数，
                // 但这里已经传了 null 占位，再跟尾随 lambda 会编译失败
                box.addView(zsRow(ctx, "预览识别结果",
                    valueView(ctx, "点此立即试一次", false), null,
                    pick = { previewRecognize(ctx, a) },
                    help = "按当前配置立刻识别一次并显示结果（不保存到脚本）。\n" +
                        "会隐藏本应用界面并回到桌面，所以请在目标界面上先摆好再点。"))
            }
            if (g.contains(com.autoball.core.model.FieldGroup.PACKAGE)) {
                val et = textField(ctx, a.pkg ?: "", "选填")
                readers["pkg"] = { a.pkg = et.text.toString() }
                box.addView(zsRow(ctx, "目标应用", et, null, null,
                    help = ActionHelp.pkg(a)))
            }
            if (g.contains(com.autoball.core.model.FieldGroup.SCRIPT_REF)) {
                val names = com.autoball.AB.store.all().associateBy { it.id }
                box.addView(zsRow(ctx, "目标脚本",
                    valueView(ctx,
                        a.scriptId?.let { names[it]?.name } ?: "未选择",
                        a.scriptId != null),
                    null,
                    pick = {
                        val list = com.autoball.AB.store.all()
                        if (list.isEmpty()) {
                            Ui.toast(ctx, "还没有可调用的脚本")
                        } else {
                            Ui.popMenu(ctx, box, list.map { it.name },
                                list.indexOfFirst { it.id == a.scriptId }.coerceAtLeast(0)) { i ->
                                a.scriptId = list[i].id
                                rebuild()
                            }
                        }
                    },
                    help = ActionHelp.scriptRef(a)))
            }
            if (g.contains(com.autoball.core.model.FieldGroup.URL)) {
                val et = textField(ctx, a.url ?: "", "https://…")
                readers["url"] = { a.url = et.text.toString().trim() }
                box.addView(zsRow(ctx, "链接地址", et, null, null,
                    help = ActionHelp.url(a)))
            }
            if (g.contains(com.autoball.core.model.FieldGroup.SUB_ACTIONS)) {
                // 子动作不在这里逐条编辑——那需要一个完整的子列表编辑器。
                // 这里只显示数量与入口，编辑走编辑页的步骤列表。
                box.addView(zsRow(ctx, "子动作",
                    valueView(ctx,
                        if (a.subActions.isEmpty()) "未添加" else "${a.subActions.size} 个",
                        a.subActions.isNotEmpty()),
                    null,
                    pick = {
                        Ui.toast(ctx, "子动作请在编辑页的步骤列表中管理")
                    },
                    help = ActionHelp.subActions(a)))
            }
            if (g.contains(com.autoball.core.model.FieldGroup.KEYCODE)) {
                val et = numField(ctx, a.keyCode.takeIf { it != 0 }?.toString() ?: "", "选填")
                readers["key"] = { a.keyCode = et.text.toString().trim().toIntOrNull() ?: 0 }
                box.addView(zsRow(ctx, "按键码", et, null, null,
                    help = ActionHelp.key(a)))
            }
            if (g.contains(com.autoball.core.model.FieldGroup.CODE)) {
                val et = textField(ctx, a.code ?: "", "JS 代码")
                readers["code"] = { a.code = et.text.toString() }
                box.addView(zsRow(ctx, "JS 代码", et, null, null,
                    help = ActionHelp.code(a)))
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
                    help = ActionHelp.control(a)))
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
                help = "不检测 / 图片存在 / 文字存在 / 颜色存在 / 节点 / 变量 / JS 表达式。\n" +
                    "条件不成立时可跳过、等待重试或停止脚本。\n" +
                    "能力不足时按不满足跳过，不会静默当作成立。"))

            // ---- 监听动作（自动精灵同款：动作级钩子）----
            // 脚本级 9 个时机在「脚本全局设置」里；这里是**本动作**的钩子，
            // 两者粒度不同，不能互相替代。
            val lc = ActionHookStage.countOf(a)
            box.addView(zsRow(ctx, "监听动作",
                valueView(ctx, ActionHookStage.summaryOf(a), lc > 0),
                null,
                pick = {
                    (ctx as? Activity)?.let { act ->
                        ActionHookDialog.show(act, a) { rebuild() }
                    }
                },
                help = "本动作执行前后挂载的动作（截图、日志、兜底），共 7 个时机。\n" +
                    "与「脚本全局设置 → 全局监听动作」的区别：那作用于整段脚本，" +
                    "这里只作用于当前动作。\n" +
                    "每个时机的说明不同，打开后点各行的「?」查看。"))

            // ---- 每步失败处理（R-113）----
            box.addView(Ui.adSec(ctx))
            box.addView(zsRow(ctx, "失败后",
                valueView(ctx, a.failOp.label, a.failOp != FailOp.NEXT),
                null,
                pick = {
                    Ui.popMenu(ctx, box, FailOp.values().map { it.label },
                        FailOp.values().indexOf(a.failOp)) { i ->
                        a.failOp = FailOp.values()[i]
                        rebuild()
                    }
                },
                help = "本步执行失败时如何处理。\n"
                    + "全局「有动作失败立即暂停」只作用于未单独设置的步骤——"
                    + "关键步骤可单独设终止，次要步骤设继续。"))

            if (a.failOp == FailOp.JUMP) {
                val steps = flowRef?.actions ?: emptyList()
                val names = steps.mapIndexed { i, s2 ->
                    "${i + 1}. ${s2.optionLabel ?: s2.type.label}" }
                box.addView(zsRow(ctx, "跳转到",
                    valueView(ctx, jumpLabel(steps, a.failJumpTo),
                        a.failJumpTo != null),
                    null,
                    pick = {
                        if (steps.isEmpty()) {
                            Ui.toast(ctx, "脚本还没有步骤")
                        } else {
                            Ui.popMenu(ctx, box, names,
                                steps.indexOfFirst { it.id == a.failJumpTo }
                                    .coerceAtLeast(0)) { i ->
                                a.failJumpTo = steps[i].id
                                rebuild()
                            }
                        }
                    },
                    help = "失败后跳到这一步继续。目标在后面则跳过中间步骤。"))
            }

            // ---- 每步坐标随机微调（R-114）----
            box.addView(Ui.adSec(ctx))
            val jitEt = numField(ctx,
                a.jitterDp.takeIf { it > 0 }?.toString() ?: "", "选填")
            readers["jitter"] = { a.jitterDp = jitEt.text.toString().trim().toIntOrNull() ?: 0 }
            box.addView(zsRow(ctx, "坐标随机", jitEt, "dp", null,
                help = "本步坐标的随机偏移半径（dp）。\n"
                    + "与全局手势变形的区别：那个作用于整段脚本的仿射矩阵，"
                    + "这里只作用于本步，可做到「关键步骤精确、次要步骤抖动」。"))

            // ---- 动作描述（备注，自动精灵在末尾一行）----
            val descEt = textField(ctx, a.desc ?: "", "选填")
            readers["desc"] = { a.desc = descEt.text.toString().trim() }
            box.addView(zsRow(ctx, "动作描述", descEt, null, null,
                help = "仅作备注，不影响执行；便于日后回看脚本时理解每一步在做什么。"))
        }

        rebuild()
        return Triple(box, { readers.values.forEach { it() } }, { rebuild() })
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
        // 列表**直接交给外层 host 滚动**（host 本身就是 ScrollView）。
        //
        // 早前在这里又套了一层定高 ScrollView，形成嵌套滚动：
        // 内层滑到边界后外层不动，项多时手感很差，而且内层定高是"屏高百分比"，
        // 与窗口固定高度对不上。既然 host 已经能滚，就别再套一层。
        listBox.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Display.dpInt(ctx, 8f)
        }
        box.addView(listBox)
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
    /** 跳转目标的可读标签；目标已被删除时显示「已失效」而不是空白 */
    private fun jumpLabel(steps: List<Action>, id: String?): String {
        if (id == null) return "未设置"
        val i = steps.indexOfFirst { it.id == id }
        return if (i < 0) "已失效" else "${i + 1}. ${steps[i].optionLabel ?: steps[i].type.label}"
    }

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
