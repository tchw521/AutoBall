package com.autoball.ui

import android.app.Activity
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.core.model.Action
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
 * 存储格式：`Action.condition` 存一段紧凑 JSON
 * `{"k":"IMAGE","e":"...","sim":90,"fail":0,"r":[x1,y1,x2,y2]}`，
 * 保证分享码能完整携带，且旧版纯文本条件仍可解析（见 [Kind.from]）。
 *
 * 修复（v1.4）：原先把选择状态挂在 object 的字段上，弹窗关闭后不清理，
 * 下次打开另一个动作会带着上次残留的相似度与区域。改为全部用局部变量，
 * 弹窗之间互不干扰。
 */
object ConditionDialog {

    private enum class Kind(val label: String, val desc: String) {
        NONE("不检测", "无条件，直接执行本动作"),
        IMAGE("图片存在", "截屏后在指定区域内找图，相似度达标才执行"),
        TEXT("文字存在", "在节点树或 OCR 结果里能找到指定文字才执行"),
        COLOR("颜色存在", "指定点或区域内出现目标颜色才执行"),
        JS("JS 表达式", "脚本返回 true 才执行"),
        ;
        companion object {
            fun byName(s: String?): Kind =
                values().firstOrNull { it.name.equals(s?.trim(), true) } ?: NONE
        }
    }

    private val SIMS = intArrayOf(70, 80, 90, 95)
    private val ON_FAIL = arrayOf("跳过本动作", "等待重试", "停止脚本")

    /** 条件区域：百分比 0–100 的 [x1,y1,x2,y2] */
    private data class Cond(
        var kind: Kind = Kind.NONE,
        var expr: String = "",
        var sim: Int = 90,
        var fail: Int = 0,
        var region: FloatArray? = null
    )

    /** 从 Action.condition 还原；兼容旧的 "KIND: expr" 纯文本格式 */
    private fun parse(raw: String?): Cond {
        if (raw.isNullOrBlank()) return Cond()
        val t = raw.trim()
        if (t.startsWith("{")) {
            return runCatching {
                val o = JSONObject(t)
                val r = o.optJSONArray("r")
                Cond(
                    kind = Kind.byName(o.optString("k", "NONE")),
                    expr = o.optString("e", ""),
                    sim = o.optInt("sim", 90),
                    fail = o.optInt("fail", 0),
                    region = if (r != null && r.length() == 4)
                        FloatArray(4) { r.optDouble(it, 0.0).toFloat() } else null
                )
            }.getOrDefault(Cond(expr = t))
        }
        val name = t.substringBefore(":").trim()
        return Cond(kind = Kind.byName(name), expr = t.substringAfter(":", "").trim())
    }

    private fun serialize(c: Cond): String? {
        if (c.kind == Kind.NONE) return null
        return JSONObject().apply {
            put("k", c.kind.name)
            put("e", c.expr)
            put("sim", c.sim)
            put("fail", c.fail)
            c.region?.let { r ->
                put("r", org.json.JSONArray().apply { r.forEach { put(it.toDouble()) } })
            }
        }.toString()
    }

    fun show(activity: Activity, a: Action, onChanged: () -> Unit) {
        val ctx = activity
        val c = parse(a.condition)

        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var exprEdit: android.widget.EditText? = null

        fun commit() {
            exprEdit?.text?.toString()?.trim()?.let { c.expr = it }
            a.condition = serialize(c)
            onChanged()
        }

        fun rebuild() {
            box.removeAllViews()

            box.addView(Ui.adRow(ctx, "条件类型", c.kind.label, c.kind != Kind.NONE, c.kind.desc) {
                Ui.popMenu(ctx, box, Kind.values().map { it.label },
                    Kind.values().indexOf(c.kind)) { i ->
                    c.kind = Kind.values()[i]
                    rebuild()
                }
            })

            if (c.kind != Kind.NONE) {
                box.addView(Ui.adSec(ctx))
                val hint = when (c.kind) {
                    Kind.IMAGE -> "图片名或分享码"
                    Kind.TEXT -> "要找的文字"
                    Kind.COLOR -> "颜色，如 #FF0000"
                    Kind.JS -> "返回 true/false 的表达式"
                    Kind.NONE -> ""
                }
                val et = Ui.adText(ctx, c.expr, hint)
                box.addView(et)
                exprEdit = et

                // 取色 / 取图入口：这两个条件此前只能手填色值和路径，
                // 用户无从得知目标色的准确值、也生成不了模板图，等于用不起来。
                if (c.kind == Kind.COLOR) {
                    box.addView(Ui.adRow(ctx, "取色器", "点屏幕取当前颜色", false,
                        "自动隐藏本应用界面并截图，点一下屏幕即可取到准确色值") {
                        val act = activity ?: return@adRow
                        ScreenPicker.pick(ctx, act, ScreenPicker.Mode.COLOR,
                            hostDialog = null,
                            onColor = { hex ->
                                c.expr = hex
                                et.setText(hex)
                                commit()
                                Ui.toast(ctx, "已取色 $hex")
                            })
                    })
                }
                if (c.kind == Kind.IMAGE) {
                    box.addView(Ui.adRow(ctx, "取图器", "框选区域存为模板", false,
                        "框选要匹配的区域，自动裁剪存为模板图。\n" +
                        "注意：模板图存放在本机，不随分享码走——" +
                        "他人导入此脚本后该条件会判定为无法检测并跳过。") {
                        val act = activity ?: return@adRow
                        ScreenPicker.pick(ctx, act, ScreenPicker.Mode.IMAGE,
                            hostDialog = null,
                            onImage = { ref ->
                                c.expr = ref
                                et.setText(ref)
                                commit()
                                Ui.toast(ctx, "模板已保存")
                            })
                    })
                }

                box.addView(Ui.adSec(ctx))
                box.addView(Ui.adRow(ctx, "条件区域",
                    if (c.region == null) "整屏" else "已选区域", c.region != null,
                    "缩小检测范围可提速") {
                    RegionPicker.pick(ctx, activity, null) { x1, y1, x2, y2 ->
                        c.region = floatArrayOf(x1, y1, x2, y2)
                        rebuild()
                    }
                })
                box.addView(Ui.adRow(ctx, "相似度", "${c.sim}%", true,
                    "越高越严格，越容易漏检") {
                    Ui.popMenu(ctx, box, SIMS.map { "$it%" },
                        SIMS.indexOf(c.sim).coerceAtLeast(0)) { k ->
                        c.sim = SIMS[k]
                        rebuild()
                    }
                })
                box.addView(Ui.adRow(ctx, "条件不成立时", ON_FAIL[c.fail], true,
                    "决定条件不满足时脚本如何继续") {
                    Ui.popMenu(ctx, box, ON_FAIL.toList(), c.fail) { k ->
                        c.fail = k
                        rebuild()
                    }
                })
            }

            box.addView(TextView(ctx).apply {
                text = "条件在执行前检查；不成立则按上方策略处理。"
                textSize = 10.5f
                setTextColor(Theme.textTer())
                setPadding(Display.dpInt(ctx, 8f), Display.dpInt(ctx, 6f),
                    Display.dpInt(ctx, 8f), 0)
            })
        }

        rebuild()
        Ui.dialog(ctx, "运行条件")
            .body(box)
            .width(Theme.DIALOG_W)
            .negative("清除") { a.condition = null; onChanged() }
            .positive("确定") { commit(); true }
            .show()
    }
}
