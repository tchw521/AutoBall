package com.autoball.ui

import android.app.Activity
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.core.model.Action
import com.autoball.core.model.ActionCondition
import com.autoball.core.model.ConditionSet
import com.autoball.core.model.NodeSpec
import com.autoball.core.util.Display
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
 */
object ConditionDialog {

    fun show(activity: Activity, a: Action, onChanged: () -> Unit) {
        val ctx = activity
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

            fun textRow(hint: String, cur: String, set: (String) -> Unit) {
                val et = Ui.adText(ctx, cur, hint)
                inner.addView(et)
                readers.add { set(et.text.toString().trim()) }
            }

            fun fill() {
                readers.clear()
                inner.removeAllViews()
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
                if (c.kind == ActionCondition.Kind.COLOR) {
                    inner.addView(Ui.adRow(ctx, "取色器", "点屏幕取当前颜色", false,
                        "自动隐藏本应用界面并截图，点一下屏幕即可取到准确色值") {
                        ScreenPicker.pick(ctx, activity, ScreenPicker.Mode.COLOR,
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
                        ScreenPicker.pick(ctx, activity, ScreenPicker.Mode.IMAGE,
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
                    ScreenPicker.pick(ctx, activity, ScreenPicker.Mode.REGION,
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
                        editProbes(ctx, activity, c) { fill() }
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
                        "点开可设置重复上限与间隔") { editRetry(ctx, c) { fill() } })
                }
                textRow("条件描述（选填）", c.desc) { c.desc = it }
            }
            fill()

            var dlg: android.app.AlertDialog? = null
            val b = Ui.dialog(ctx, "编辑条件").body(inner)
                .width(Theme.DIALOG_W + 10f).maxHeight(0.8f)
            if (c.kind == ActionCondition.Kind.IMAGE) {
                // 「从屏幕测试找图」必须同屏可见——藏在菜单里用户根本发现不了。
                // 这是自动精灵特意放在标题栏右上角的原因。
                b.trailing("从屏幕测试找图…") { testFromScreen(ctx, activity, c, dlg) }
            }
            b.negative("删除本条") {
                set.items.remove(c)
                commit(); rebuild()
            }.positive("确定") {
                readers.forEach { runCatching { it() } }
                commit(); rebuild(); true
            }.onReady { d, _, _ -> dlg = d }
            dlg = b.show()
        }

        rebuild()

        Ui.dialog(ctx, "运行条件").body(box)
            .width(Theme.DIALOG_W + 10f).maxHeight(0.82f)
            .negative("清除") { a.condition = null; onChanged() }
            .positive("确定") { commit(); true }
            .show()
    }

    private fun one(c: ActionCondition): String {
        val tail = if (c.probes.isNotEmpty()) " · 周围${c.probes.size}点" else ""
        return when (c.kind) {
            ActionCondition.Kind.NONE -> "不检测"
            ActionCondition.Kind.JS -> "JS：${c.value}"
            ActionCondition.Kind.COLOR -> "颜色 ${c.value}$tail"
            ActionCondition.Kind.TEXT -> "文字「${c.value}」"
            ActionCondition.Kind.IMAGE -> "图片匹配 ${c.sim}%"
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
     */
    private fun testFromScreen(ctx: android.app.Activity, act: android.app.Activity,
                               c: ActionCondition, dlg: android.app.AlertDialog?) {
        if (c.value.isBlank()) {
            Ui.toast(ctx, "请先选择模板图"); return
        }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        dlg?.hide()
        com.autoball.float.FloatManager.hideAll()
        com.autoball.float.FloatWindows.hideAll()
        handler.postDelayed({
            runCatching {
                act.startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                    addCategory(android.content.Intent.CATEGORY_HOME)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
            handler.postDelayed({
                val sr = runCatching {
                    com.autoball.AB.router.screenshot(
                        com.autoball.core.backend.ExecContext("condtest"))
                }.getOrNull()
                val ok = sr as? com.autoball.core.backend.ScreenResult.Ok
                dlg?.show()
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
    private fun editRetry(ctx: android.app.Activity, c: ActionCondition,
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
        Ui.dialog(ctx, "重复检查直到成功").body(box)
            .width(Theme.DIALOG_W + 10f)
            .negative("取消")
            .positive("确定") {
                c.retryMax = Ui.adNumberValue(maxRow).trim().toIntOrNull()
                    ?.coerceAtLeast(0) ?: 0
                c.retryIntervalMs = Ui.adNumberValue(gapRow).trim().toLongOrNull()
                    ?.coerceIn(100L, 60_000L) ?: 1000L
                onChanged(); true
            }.show()
    }

    /** 位置周围条件编辑器：增删探针，每个探针含偏移与颜色 */
    private fun editProbes(ctx: Activity, act: Activity, c: ActionCondition,
                           onChanged: () -> Unit) {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

        fun fill() {
            box.removeAllViews()
            box.addView(Kit.note(act,
                "主坐标匹配成功后，再校验这些偏移点。偏移单位 dp，换机型保持一致。"))
            c.probes.forEachIndexed { i, p ->
                box.addView(Kit.rowCard(act).apply {
                    addView(Kit.twoLine(act, "偏移 (${p.dx.toInt()}, ${p.dy.toInt()})",
                        "${p.color.ifEmpty { "未取色" }} · 容差 ${p.tol}"))
                    addView(Kit.miniBtn(act, "取色") {
                        ScreenPicker.pick(act, act, ScreenPicker.Mode.COLOR,
                            onColor = { hex -> p.color = hex; onChanged(); fill() })
                    })
                    addView(Kit.miniBtn(act, "✕") { c.probes.removeAt(i); onChanged(); fill() })
                })
            }
            box.addView(Kit.button(act, "＋ 添加周围点", false) {
                c.probes.add(ActionCondition.Probe())
                onChanged(); fill()
            })
        }
        fill()

        Ui.dialog(act, "位置周围条件").body(box)
            .width(Theme.DIALOG_W + 10f).maxHeight(0.8f)
            .negative("清空") { c.probes.clear(); onChanged(); fill() }
            .positive("确定") { onChanged(); true }.show()
    }
}
