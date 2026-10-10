package com.autoball.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.core.model.Action
import com.autoball.core.model.ActionCondition
import com.autoball.core.model.ConditionSet
import com.autoball.core.model.NodeSpec
import com.autoball.core.util.Display
import com.autoball.float.FloatDialog
import com.autoball.float.FloatManager
import com.autoball.float.FloatWindows
import org.json.JSONObject

/**
 * 运行条件（v3 #condDlg）：206dp 紧凑弹窗。
 *
 * 条件类型：
 * - 不检测：无条件，直接执行
 * - 图片存在：截图后在指定区域找图，相似度达标才继续
 * - 文字存在：OCR / 节点树里能找到指定文字
 * - 颜色存在：指定点或区域内出现目标颜色
 * - JS 表达式：脚本返回 true 才执行
 *
 * 数据模型统一在 [ConditionSet] / [ActionCondition]（R-001）。
 * 此前 UI 写 `e`、求值读 `v`，字段不一致导致所有识别类条件读到空值；
 * 现在三处（本弹窗 / ConditionEval / ShareCode）共用同一模型。
 *
 * 支持多条条件 + AND/OR，以及「位置周围条件」（多点找色）。
 *
 * 修复（v1.4）：原先把选择状态挂在 object 的字段上，弹窗关闭后不清理，
 * 下次打开另一个动作会带着上次残留的相似度与区域。改为全部用局部变量，
 * 弹窗之间互不干扰。
 *
 * # 两种形态（R-004）
 *
 * [show] 为 Activity 形态，[showFloat] 为悬浮窗形态。
 *
 * **形态必须由外层显式指定，不能看 ctx 是不是 Activity**：
 * 「编辑动作」在悬浮窗形态下打开时，传进来的 ctx 常常**就是 Activity**
 * （工作台弹窗持有 activity 引用），于是早前写成
 * `(ctx as? Activity)?.let { ... } ?: toast("暂不支持悬浮窗形态")` 时，
 * 看似走了 Activity 分支，实际弹出的是 Activity 内的 AlertDialog——
 * 而此时用户正在桌面或别的应用上，Activity 在后台，对话框**根本不显示**。
 * 表现为「点「未设置」一点用都没有」，且不报错。
 *
 * 底层原因是 [Ui.dialog] 只有 AlertDialog 实现，离不开 Activity；
 * 现在按 asFloat 走 [FloatDialog]，与外层弹窗保持同一层。
 */
object ConditionDialog {

    // =====================================================================
    // 对外入口
    // =====================================================================

    /** Activity 形态（应用页面内打开） */
    fun show(activity: Activity, a: Action, onChanged: () -> Unit,
             directAdd: Boolean = false) {
        showInternal(activity, a, onChanged, directAdd, asFloat = false)
    }

    /**
     * 悬浮窗形态：不把用户拽回应用界面。
     *
     * 这是主路径——添加/编辑动作多在"正操作着别的应用"时进行，
     * 跳回应用会把目标应用切走，等于白操作一遍。
     * 无悬浮窗权限时回退到 Activity 弹窗。
     */
    fun showFloat(ctx: Context, a: Action, onChanged: () -> Unit,
                  directAdd: Boolean = false) {
        showInternal(ctx, a, onChanged, directAdd, asFloat = true)
    }

    // =====================================================================
    // 弹窗出口：按形态二选一
    // =====================================================================

    /**
     * 弹窗句柄：测试找图前要把界面让出去（否则会拍到本应用自己的浮窗），
     * 测完再恢复。两种形态的"让出"方式不同，故抽象成一对闭包。
     */
    private class DlgHandle {
        var hide: () -> Unit = {}
        var show: () -> Unit = {}
    }

    /**
     * 统一弹窗出口。
     *
     * @param asFloat 由外层弹窗形态决定，不靠 ctx 类型推断
     */
    private fun openDialog(ctx: Context, title: String, body: android.view.View,
                     asFloat: Boolean, widthDp: Float, maxH: Float,
                     neg: Pair<String, (() -> Unit)?>? = null,
                     pos: Pair<String, (() -> Boolean)?>? = null,
                     /** 标题栏右侧小动作；仅 Activity 形态支持（悬浮窗标题栏没有该槽位） */
                     trailing: Pair<String, () -> Unit>? = null,
                     handleOut: DlgHandle? = null) {
        val act = ctx as? Activity
        val actUsable = act != null && !act.isFinishing && !act.isDestroyed
        val useFloat = asFloat || !actUsable

        // 让出屏幕（测试找图前）：必须先 markShown 再 hideAll，
        // 否则 FloatManager.restore() 不知道之前显示过什么、恢复时什么都不做。
        fun stashFloats() {
            FloatManager.markShown()
            FloatManager.hideAll()
            FloatWindows.hideAll()
        }
        fun unstashFloats() {
            FloatWindows.restore()
            FloatManager.restore()
        }

        if (useFloat) {
            val d = FloatDialog.show(ctx, title).body(body).width(widthDp)
            neg?.let { d.negative(it.first, it.second) }
            pos?.let { d.positive(it.first, it.second) }
            if (d.show()) {
                // [FloatDialog] 没有单实例 hide/show：测试找图时整体隐藏再恢复。
                // 浮窗与弹窗一起让出屏幕，避免截图拍到自己。
                if (handleOut != null) {
                    handleOut.hide = { stashFloats() }
                    handleOut.show = { unstashFloats() }
                }
                return
            }
            if (!actUsable) {
                Ui.toast(ctx, "需要悬浮窗权限才能在当前界面编辑运行条件")
                return
            }
        }

        val b = Ui.dialog(act!!, title).body(body).width(widthDp).maxHeight(maxH)
        neg?.let { b.negative(it.first, it.second) }
        pos?.let { b.positive(it.first, it.second) }
        trailing?.let { b.trailing(it.first, it.second) }
        var dlg: android.app.AlertDialog? = null
        b.onReady { d, _, _ -> dlg = d }
        val shown = b.show()
        if (handleOut != null) {
            val d = dlg ?: shown
            handleOut.hide = { runCatching { d.hide() }; stashFloats() }
            handleOut.show = { unstashFloats(); runCatching { d.show() } }
        }
    }

    // =====================================================================
    // 主体
    // =====================================================================

    private fun showInternal(ctx: Context, a: Action, onChanged: () -> Unit,
                             directAdd: Boolean, asFloat: Boolean) {
        val set = ConditionSet.parse(a.condition)

        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        fun commit() {
            a.condition = ConditionSet.serialize(set)
            onChanged()
        }

        /** 区域的可读文本（百分比，保留一位小数） */
        fun regionText(r: FloatArray?): String {
            if (r == null || r.size < 4) return "整屏"
            return "%.1f%%, %.1f%% → %.1f%%, %.1f%%".format(r[0], r[1], r[2], r[3])
        }

        // rebuild 与 editCond 互相调用，而 Kotlin 局部**函数**不支持前向引用
        // （声明顺序即解析顺序）。所以两个都声明为 lateinit lambda 变量：
        // 先声明名字，再赋值，彼此就能互相引用了。
        lateinit var rebuild: () -> Unit
        lateinit var editCond: (ActionCondition) -> Unit

        rebuild = {
            box.removeAllViews()

            val real = set.items.filter { it.kind != ActionCondition.Kind.NONE }
            if (real.size > 1) {
                box.addView(Ui.adRow(ctx, "多条件关系", set.op.label, true, set.op.desc) {
                    Ui.popMenu(ctx, box, ConditionSet.Op.values().map { it.label },
                        ConditionSet.Op.values().indexOf(set.op)) { i ->
                        set.op = ConditionSet.Op.values()[i]
                        commit(); rebuild()
                    }
                })
                box.addView(Ui.adSec(ctx))
            }

            real.forEachIndexed { idx, c ->
                box.addView(Ui.adRow(ctx, "条件 ${idx + 1}", one(c), true,
                    "点开可修改本条；长按右侧 ✕ 可删除") {
                    editCond(c)
                })
            }

            box.addView(Kit.button(ctx, "＋ 添加条件", false) {
                val nc = ActionCondition()
                set.items.add(nc)
                editCond(nc)
            })

            box.addView(Kit.note(ctx,
                "多条条件时可选「全部满足」或「任一满足」。"
                + "无法判定（如缺少截图能力）时按不满足跳过，不会静默当作成立。"))
        }

        editCond = { c ->
            val inner = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            // 每类条件的输入项数量不同（变量有 3 项、节点有 4 项），
            // 用 readers 列表统一回读，避免"只回读第一个输入框"的漏字段问题
            val readers = ArrayList<() -> Unit>()
            val handle = DlgHandle()

            fun textRow(hint: String, cur: String, set: (String) -> Unit) {
                val et = Ui.adText(ctx, cur, hint)
                inner.addView(et)
                readers.add { set(et.text.toString().trim()) }
            }

            fun fill() {
                readers.clear()
                inner.removeAllViews()

                // 「从屏幕测试找图」必须同屏可见——藏在菜单里用户根本发现不了。
                // Activity 形态放在标题栏右上角（自动精灵就是这么放的）；
                // 悬浮窗形态的标题栏没有尾部槽位，故作为一行放进表单里，
                // 两处都保证"第一眼就能看见"。
                if (c.kind == ActionCondition.Kind.IMAGE && asFloat) {
                    inner.addView(Ui.adRow(ctx, "从屏幕测试找图", "点此测试", false,
                        "隐藏界面 → 回桌面 → 截图 → 按当前条件求值，"
                        + "走的是与运行时完全相同的判定路径") {
                        testFromScreen(ctx, c, handle)
                    })
                    inner.addView(Ui.adSec(ctx))
                }

                inner.addView(Ui.adRow(ctx, "条件类型", c.kind.label,
                    c.kind != ActionCondition.Kind.NONE, c.kind.desc) {
                    Ui.popMenu(ctx, inner,
                        ActionCondition.Kind.values().map { it.label },
                        ActionCondition.Kind.values().indexOf(c.kind)) { i ->
                        c.kind = ActionCondition.Kind.values()[i]
                        fill()
                    }
                })

                if (c.kind == ActionCondition.Kind.NONE) return

                inner.addView(Ui.adSec(ctx))

                // ---- 两类新条件有各自的表单，其余走通用的单值输入 ----
                if (c.kind == ActionCondition.Kind.VAR) {
                    // 注意：`$ok` 里的 $ 在 Kotlin 字符串中是模板起始符，必须转义
                    textRow("变量名，如 ok 或 \$ok", c.value) { c.value = it }
                    inner.addView(Ui.adRow(ctx, "比较", c.cmp.label, true,
                        "两边都能转成数字时按数字比较（如 10 > 9），否则按文本比较") {
                        Ui.popMenu(ctx, inner,
                            ActionCondition.Cmp.values().map { it.label },
                            ActionCondition.Cmp.values().indexOf(c.cmp)) { i ->
                            c.cmp = ActionCondition.Cmp.values()[i]
                            fill()
                        }
                    })
                    if (c.cmp != ActionCondition.Cmp.EXISTS) {
                        textRow("比较值", c.cmpValue) { c.cmpValue = it }
                    }
                    inner.addView(Kit.note(ctx,
                        "可用运行时变量：ok（目前全成功）、last（上一步成功）、stepN（第 N 步成功）"))
                } else if (c.kind == ActionCondition.Kind.NODE) {
                    val sp = c.nodeSpec ?: NodeSpec().also { c.nodeSpec = it }
                    textRow("控件文字（包含匹配）", sp.text ?: "") { sp.text = it.ifEmpty { null } }
                    textRow("控件 ID，如 com.x:id/ok", sp.id ?: "") { sp.id = it.ifEmpty { null } }
                    textRow("内容描述", sp.desc ?: "") { sp.desc = it.ifEmpty { null } }
                    textRow("类名，如 android.widget.Button", sp.className ?: "") {
                        sp.className = it.ifEmpty { null }
                    }
                    inner.addView(Kit.note(ctx,
                        "四项可任意组合，留空的不参与匹配；全留空则无法判定。"
                        + "节点查找是无障碍独有能力，Shizuku 通道下会按不满足处理。"))
                } else {
                    val hint = when (c.kind) {
                        ActionCondition.Kind.IMAGE -> "模板图（请用取图器）"
                        ActionCondition.Kind.TEXT -> "要找的文字"
                        ActionCondition.Kind.COLOR -> "颜色，如 #FF0000"
                        ActionCondition.Kind.JS -> "返回 true/false 的表达式"
                        else -> ""
                    }
                    textRow(hint, c.value) { c.value = it }
                }

                // 取色 / 取图入口需回写输入框，故保留一个引用
                val et: android.widget.EditText? = (0 until inner.childCount)
                    .map { inner.getChildAt(it) }
                    .filterIsInstance<android.widget.EditText>()
                    .firstOrNull()

                // 取色 / 取图入口：这两个条件此前只能手填色值和路径，
                // 用户无从得知目标色的准确值、也生成不了模板图，等于用不起来。
                // ScreenPicker 的 activity 参数可为空（仅用于回桌面/恢复），
                // 悬浮窗形态传 null 即可。
                val act = ctx as? Activity
                if (c.kind == ActionCondition.Kind.COLOR) {
                    inner.addView(Ui.adRow(ctx, "取色器", "点屏幕取当前颜色", false,
                        "自动隐藏本应用界面并截图，点一下屏幕即可取到准确色值") {
                        ScreenPicker.pick(ctx, act, ScreenPicker.Mode.COLOR,
                            onColor = { hex ->
                                c.value = hex
                                et?.setText(hex)
                                Ui.toast(ctx, "已取色 $hex")
                            })
                    })
                }
                if (c.kind == ActionCondition.Kind.IMAGE) {
                    inner.addView(Ui.adRow(ctx, "取图器", "框选区域存为模板", false,
                        "框选要匹配的区域，自动裁剪存为模板图。\n" +
                        "模板图会压缩后随分享码一起走（长边 160px）。") {
                        ScreenPicker.pick(ctx, act, ScreenPicker.Mode.IMAGE,
                            onImage = { ref ->
                                c.value = ref
                                et?.setText(ref)
                                Ui.toast(ctx, "模板已保存")
                            })
                    })
                }

                // 检测区域：在截图上拖框（原先只能手填四个数值，等于从不生效）
                inner.addView(Ui.adRow(ctx, "检测区域", regionText(c.region),
                    c.region != null,
                    "缩小检测范围可提速；在截图上拖框即可，不用手填数值") {
                    ScreenPicker.pick(ctx, act, ScreenPicker.Mode.REGION,
                        onRegionPct = { r ->
                            c.region = r
                            Ui.toast(ctx, "已选区域 ${regionText(r)}")
                            fill()
                        })
                })

                // 位置周围条件（多点找色）：主色命中后校验周围偏移点的颜色
                if (c.kind == ActionCondition.Kind.COLOR) {
                    inner.addView(Ui.adSec(ctx))
                    inner.addView(Ui.adRow(ctx, "位置周围条件",
                        if (c.probes.isEmpty()) "未设置" else "${c.probes.size} 个周围点",
                        c.probes.isNotEmpty(),
                        "单点找色在界面里同色干扰多时容易误命中；"
                        + "加上周围几个点的相对颜色约束就能精确定位。") {
                        editProbes(ctx, c, asFloat) { fill() }
                    })
                }

                if (c.kind == ActionCondition.Kind.IMAGE) {
                    // 相似度用滑块：50–100 需要微调，四档固定值不够用
                    inner.addView(Ui.adSlider(ctx, "相似度", c.sim, 50, 100, "%",
                        "越高越严格、越不容易误命中；但太高会漏检。一般 85–95") {
                        c.sim = it
                    })
                }
                if (c.kind == ActionCondition.Kind.COLOR) {
                    inner.addView(Ui.adSlider(ctx, "容差", c.tol, 0, 120, "",
                        "越大越宽松；抗锯齿与渐变会让像素色值有偏差") {
                        c.tol = it
                    })
                }

                // ---- 找图高级项（R-135，对齐自动精灵运行条件弹窗）----
                if (c.kind == ActionCondition.Kind.IMAGE) {
                    inner.addView(Ui.adSec(ctx))
                    textRow("匹配第几（选填）",
                        if (c.matchIndex > 0) c.matchIndex.toString() else "") {
                        c.matchIndex = it.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    }
                    inner.addView(Ui.adCheck(ctx, "快速搜图", c.fast,
                        "抽稀步长翻倍，速度约快 4 倍。代价是可能漏检，"
                        + "仅在大区域找小图、明显变慢时才值得开") {
                        c.fast = it
                    })
                    inner.addView(Ui.adRow(ctx, "搜图模式", c.searchMode.label,
                        c.searchMode != ActionCondition.SearchMode.DEFAULT,
                        c.searchMode.desc + "（当前内核只有「默认」一种实现）") {
                        Ui.popMenu(ctx, inner,
                            ActionCondition.SearchMode.values().map { it.label },
                            ActionCondition.SearchMode.values().indexOf(c.searchMode)) { i ->
                            c.searchMode = ActionCondition.SearchMode.values()[i]
                            fill()
                        }
                    })
                    inner.addView(Ui.adRow(ctx, "多分辨率适配", c.multiRes.label,
                        c.multiRes != ActionCondition.MultiRes.BOTH, c.multiRes.desc) {
                        Ui.popMenu(ctx, inner,
                            ActionCondition.MultiRes.values().map { it.label },
                            ActionCondition.MultiRes.values().indexOf(c.multiRes)) { i ->
                            c.multiRes = ActionCondition.MultiRes.values()[i]
                            fill()
                        }
                    })
                    inner.addView(Ui.adRow(ctx, "滤镜",
                        c.filter.ifEmpty { "未设置" }, c.filter.isNotEmpty(),
                        "图像预处理（灰度/二值化等）需要图像处理模块，当前未内置。\n"
                        + "留空即不处理——不做假装支持的选项。") {
                        Ui.toast(ctx, "滤镜需要图像处理模块，当前版本未内置")
                    })
                }

                // ---- 通用高级项 ----
                inner.addView(Ui.adSec(ctx))
                inner.addView(Ui.adCheck(ctx, "条件反相", c.invert,
                    "成立变不成立：例如「出现图片」反相后是「图片消失才执行」。\n"
                    + "注意：能力不足无法判定时仍按不满足跳过，不会反相成成立") {
                    c.invert = it
                })
                inner.addView(Ui.adCheck(ctx, "等待前检查", c.checkBefore,
                    "默认先跑完本动作的等待时间再判定条件（界面更可能已稳定）。\n"
                    + "勾上则先判定、再等待") {
                    c.checkBefore = it
                })
                inner.addView(Ui.adCheck(ctx, "重复检查直到成功", c.retry,
                    "条件不成立时按间隔反复检查，直到成立或用尽上限。\n"
                    + "判定为「无法判定」时不重试——能力缺失重试也没用") {
                    c.retry = it
                    fill()
                })
                if (c.retry) {
                    inner.addView(Ui.adRow(ctx, "重复设置",
                        if (c.retryMax > 0) "${c.retryMax} 次 / ${c.retryIntervalMs}ms"
                        else "不限 / ${c.retryIntervalMs}ms", true,
                        "点开可设置重复上限与间隔") {
                        editRetry(ctx, c, asFloat) { fill() }
                    })
                }
                textRow("条件描述（选填）", c.desc) { c.desc = it }
            }
            fill()

            openDialog(ctx, "编辑条件", inner, asFloat,
                widthDp = Theme.DIALOG_W + 10f, maxH = 0.8f,
                neg = "删除本条" to {
                    set.items.remove(c)
                    commit(); rebuild()
                },
                pos = "确定" to {
                    readers.forEach { runCatching { it() } }
                    commit(); rebuild(); true
                },
                // Activity 形态保留标题栏右上角的测试入口（与自动精灵一致）；
                // 悬浮窗形态没有该槽位，已在表单里加了一行（见 fill）
                trailing = if (!asFloat && c.kind == ActionCondition.Kind.IMAGE)
                    ("从屏幕测试找图…" to { testFromScreen(ctx, c, handle) }) else null,
                handleOut = handle)
        }

        // 「未设置」点开直接进添加条件页：先显示空列表再点「＋ 添加条件」纯属多一次点击。
        // 直接新建一条并打开编辑；kind 为 NONE 的空项在 serialize 时会被过滤，
        // 所以用户中途返回不会留下脏数据。
        if (directAdd && set.items.none { it.kind != ActionCondition.Kind.NONE }) {
            val nc = ActionCondition()
            set.items.add(nc)
            editCond(nc)
            return
        }

        rebuild()

        openDialog(ctx, "运行条件", box, asFloat,
            widthDp = Theme.DIALOG_W + 10f, maxH = 0.82f,
            neg = "清除" to { a.condition = null; onChanged() },
            pos = "确定" to { commit(); true })
    }

    private fun one(c: ActionCondition): String {
        val tail = if (c.probes.isNotEmpty()) " · 周围${c.probes.size}点" else ""
        return when (c.kind) {
            ActionCondition.Kind.NONE -> "不检测"
            ActionCondition.Kind.JS -> "JS：${c.value}"
            ActionCondition.Kind.COLOR -> "颜色 ${c.value}$tail"
            ActionCondition.Kind.TEXT -> "文字「${c.value}」"
            ActionCondition.Kind.IMAGE -> "图片匹配 ${c.sim}%"
            ActionCondition.Kind.AI -> "AI云识别（不可用）"
            ActionCondition.Kind.NODE -> {
                val sp = c.nodeSpec
                val what = sp?.text ?: sp?.id ?: sp?.desc ?: sp?.className ?: "未设置"
                "节点「$what」"
            }
            ActionCondition.Kind.VAR -> {
                val n = c.value.trimStart('$')
                if (c.cmp == ActionCondition.Cmp.EXISTS) "变量 $n 已定义"
                else "变量 $n ${c.cmp.symbol} ${c.cmpValue}"
            }
        }
    }

    /**
     * 从屏幕测试找图：隐藏界面 → 回桌面 → 截图 → 按当前条件求值。
     *
     * 走的是**与运行时完全相同的求值路径**（同一个 [ConditionEval.eval]），
     * 否则"测试通过"不代表脚本里也能过——那测试就白做了。
     *
     * 此前只在结尾 `dlg?.show()` 恢复弹窗，**从未恢复 FloatManager / FloatWindows**——
     * 于是测一次之后悬浮球与悬浮窗就永久消失，要重启应用才回来。
     * 现在两种形态都统一走 [DlgHandle] 的 hide/show 成对恢复。
     */
    private fun testFromScreen(ctx: Context, c: ActionCondition, handle: DlgHandle) {
        if (c.value.isBlank()) {
            Ui.toast(ctx, "请先选择模板图"); return
        }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handle.hide()
        handler.postDelayed({
            runCatching {
                // 回桌面：非 Activity 上下文必须带 NEW_TASK，否则抛异常
                ctx.startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
            handler.postDelayed({
                val sr = runCatching {
                    com.autoball.AB.router.screenshot(
                        com.autoball.core.backend.ExecContext("condtest"))
                }.getOrNull()
                val ok = sr as? com.autoball.core.backend.ScreenResult.Ok
                handle.show()
                if (ok == null) {
                    val why = (sr as? com.autoball.core.backend.ScreenResult.Unavailable)?.reason
                        ?: "截图失败"
                    Ui.toast(ctx, "无法测试：$why")
                    return@postDelayed
                }
                val probe = object : com.autoball.core.util.ConditionEval.Probe {
                    override fun screen(): com.autoball.core.backend.ScreenResult? = ok
                    override fun findColor(hex: String, tol: Int, region: FloatArray?): Boolean? = null
                    override fun findText(text: String, region: FloatArray?): Boolean? = null
                    override fun findImage(path: String, threshold: Float, region: FloatArray?,
                                           res: com.autoball.core.model.ActionCondition.MultiRes,
                                           fast: Boolean, minCount: Int): Boolean? {
                        val tpl = com.autoball.core.store.TemplateStore.load(path) ?: return null
                        val m = com.autoball.core.util.ConditionEval.matchTemplatePos(
                            ok, tpl, threshold, region,
                            com.autoball.core.store.TemplateStore.metaOf(path),
                            res, fast, minCount)
                        lastSim = m?.similarity
                        return m != null
                    }
                    override fun evalJs(expr: String): Boolean? = null
                }
                val one = com.autoball.core.model.ConditionSet().apply { items.add(c) }
                val raw = com.autoball.core.model.ConditionSet.serialize(one)
                val out = com.autoball.core.util.ConditionEval.eval(raw, emptyMap(), probe)
                val sim = lastSim?.let { "（相似度 ${(it * 100).toInt()}%）" } ?: ""
                Ui.toast(ctx, when (out) {
                    com.autoball.core.util.ConditionEval.Outcome.SATISFIED -> "找到匹配 $sim"
                    com.autoball.core.util.ConditionEval.Outcome.NOT_SATISFIED ->
                        "未找到匹配 $sim\n可降低相似度或改用其他多分辨率策略"
                    com.autoball.core.util.ConditionEval.Outcome.UNKNOWN -> "无法判定：模板图缺失"
                })
            }, 420)
        }, 80)
    }

    /** 测试时回填的相似度，供提示文案使用 */
    @Volatile private var lastSim: Float? = null

    /** 重复检查设置：上限 + 间隔 */
    private fun editRetry(ctx: Context, c: ActionCondition, asFloat: Boolean,
                          onChanged: () -> Unit) {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val maxRow = Ui.adNumber(ctx,
            if (c.retryMax > 0) c.retryMax.toString() else "", "次",
            "0 或留空表示不限次数")
        val gapRow = Ui.adNumber(ctx, c.retryIntervalMs.toString(), "毫秒", "")
        box.addView(maxRow)
        box.addView(gapRow)
        box.addView(Kit.note(ctx,
            "上限填 0 表示一直重试到条件成立或脚本被停止——请谨慎，"
            + "条件永远不成立时脚本不会自动结束。"))
        openDialog(ctx, "重复检查直到成功", box, asFloat,
            widthDp = Theme.DIALOG_W + 10f, maxH = 0.7f,
            neg = "取消" to null,
            pos = "确定" to {
                c.retryMax = Ui.adNumberValue(maxRow).trim().toIntOrNull()
                    ?.coerceAtLeast(0) ?: 0
                c.retryIntervalMs = Ui.adNumberValue(gapRow).trim().toLongOrNull()
                    ?.coerceIn(100L, 60_000L) ?: 1000L
                onChanged(); true
            })
    }

    /** 位置周围条件编辑器：增删探针，每个探针含偏移与颜色 */
    private fun editProbes(ctx: Context, c: ActionCondition, asFloat: Boolean,
                           onChanged: () -> Unit) {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val act = ctx as? Activity

        fun fill() {
            box.removeAllViews()
            box.addView(Kit.note(ctx,
                "主坐标匹配成功后，再校验这些偏移点。偏移单位 dp，换机型保持一致。"))
            c.probes.forEachIndexed { i, p ->
                box.addView(Kit.rowCard(ctx).apply {
                    addView(Kit.twoLine(ctx, "偏移 (${p.dx.toInt()}, ${p.dy.toInt()})",
                        "${p.color.ifEmpty { "未取色" }} · 容差 ${p.tol}"))
                    addView(Kit.miniBtn(ctx, "取色") {
                        ScreenPicker.pick(ctx, act, ScreenPicker.Mode.COLOR,
                            onColor = { hex -> p.color = hex; onChanged(); fill() })
                    })
                    addView(Kit.miniBtn(ctx, "✕") { c.probes.removeAt(i); onChanged(); fill() })
                })
            }
            box.addView(Kit.button(ctx, "＋ 添加周围点", false) {
                c.probes.add(ActionCondition.Probe())
                onChanged(); fill()
            })
        }
        fill()

        openDialog(ctx, "位置周围条件", box, asFloat,
            widthDp = Theme.DIALOG_W + 10f, maxH = 0.8f,
            neg = "清空" to { c.probes.clear(); onChanged(); fill() },
            pos = "确定" to { onChanged(); true })
    }
}
