package com.autoball.ui

import android.app.Activity
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.core.model.Action
import com.autoball.core.model.ActionCondition
import com.autoball.core.model.ConditionSet
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

    private val SIMS = intArrayOf(70, 80, 90, 95)
    private val TOLS = intArrayOf(5, 10, 24, 48)

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

        // rebuild 与 editCond 互相调用，局部函数不支持前向引用。
        // 用 lateinit lambda 变量声明在前，editCond 里就能引用它。
        lateinit var rebuild: () -> Unit

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

        fun editCond(c: ActionCondition) {
            val inner = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            var exprEdit: android.widget.EditText? = null

            fun fill() {
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
                val hint = when (c.kind) {
                    ActionCondition.Kind.IMAGE -> "模板图（请用取图器）"
                    ActionCondition.Kind.TEXT -> "要找的文字"
                    ActionCondition.Kind.COLOR -> "颜色，如 #FF0000"
                    ActionCondition.Kind.JS -> "返回 true/false 的表达式"
                    else -> ""
                }
                val et = Ui.adText(ctx, c.value, hint)
                inner.addView(et)
                exprEdit = et

                // 取色 / 取图入口：这两个条件此前只能手填色值和路径，
                // 用户无从得知目标色的准确值、也生成不了模板图，等于用不起来。
                if (c.kind == ActionCondition.Kind.COLOR) {
                    inner.addView(Ui.adRow(ctx, "取色器", "点屏幕取当前颜色", false,
                        "自动隐藏本应用界面并截图，点一下屏幕即可取到准确色值") {
                        ScreenPicker.pick(ctx, activity, ScreenPicker.Mode.COLOR,
                            onColor = { hex ->
                                c.value = hex
                                et.setText(hex)
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
                                et.setText(ref)
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
                    inner.addView(Ui.adRow(ctx, "相似度", "${c.sim}%", true,
                        "越高越严格，越容易漏检") {
                        Ui.popMenu(ctx, inner, SIMS.map { "$it%" },
                            SIMS.indexOf(c.sim).coerceAtLeast(0)) { k ->
                            c.sim = SIMS[k]; fill()
                        }
                    })
                }
                if (c.kind == ActionCondition.Kind.COLOR) {
                    inner.addView(Ui.adRow(ctx, "颜色容差", "${c.tol}", true,
                        "越大越宽松；抗锯齿与渐变会让像素色值有偏差") {
                        Ui.popMenu(ctx, inner, TOLS.map { "$it" },
                            TOLS.indexOf(c.tol).coerceAtLeast(0)) { k ->
                            c.tol = TOLS[k]; fill()
                        }
                    })
                }
            }
            fill()

            Ui.dialog(ctx, "编辑条件").body(inner)
                .width(Theme.DIALOG_W + 10f).maxHeight(0.8f)
                .negative("删除本条") {
                    set.items.remove(c)
                    commit(); rebuild()
                }
                .positive("确定") {
                    exprEdit?.text?.toString()?.trim()?.let { c.value = it }
                    commit(); rebuild(); true
                }.show()
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
        }
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
