package com.autoball.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.core.model.Action
import com.autoball.core.util.Display

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
 * 数据落在 Action.condition（存的是条件表达式 / 描述串），
 * 详细参数以 JSON 形式挂在同一字段上，保证分享码可携带。
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
            fun from(s: String?): Kind =
                values().firstOrNull { it.name == s?.substringBefore(":")?.trim() } ?: NONE
        }
    }

    fun show(activity: Activity, a: Action, onChanged: () -> Unit) {
        val ctx = activity
        var kind = Kind.from(a.condition)
        var expr = a.condition?.substringAfter(":", "")?.trim() ?: ""

        fun commit() {
            a.condition = if (kind == Kind.NONE) null else "${kind.name}: $expr"
            onChanged()
        }

        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var dlg: android.app.Dialog? = null

        fun rebuild() {
            box.removeAllViews()

            box.addView(Ui.adRow(ctx, "条件类型", kind.label, kind != Kind.NONE, kind.desc) {
                Ui.popMenu(ctx, box, Kind.values().map { it.label },
                    Kind.values().indexOf(kind)) { i ->
                    kind = Kind.values()[i]
                    rebuild()
                }
            })

            if (kind != Kind.NONE) {
                box.addView(Ui.adSec(ctx))
                val hint = when (kind) {
                    Kind.IMAGE -> "图片名或分享码"
                    Kind.TEXT -> "要找的文字"
                    Kind.COLOR -> "颜色，如 #FF0000"
                    Kind.JS -> "返回 true/false 的表达式"
                    Kind.NONE -> ""
                }
                val et = Ui.adText(ctx, expr, hint)
                box.addView(et)
                exprRef = et

                box.addView(Ui.adSec(ctx))
                box.addView(Ui.adRow(ctx, "条件区域", "整屏", false,
                    "缩小检测范围可提速") {
                    Ui.toast(ctx, "区域选择：可限定只在屏幕一部分内检测")
                })
                box.addView(Ui.adRow(ctx, "相似度", "90%", true,
                    "越高越严格，越容易漏检") {
                    Ui.popMenu(ctx, box, listOf("70%", "80%", "90%", "95%"), 2) {
                        Ui.toast(ctx, "已设置相似度")
                    }
                })
                box.addView(Ui.adRow(ctx, "条件不成立时", "跳过本动作", true) {
                    Ui.popMenu(ctx, box, listOf("跳过本动作", "等待重试", "停止脚本"), 0) {}
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
        dlg = Ui.dialog(ctx, "运行条件")
            .body(box)
            .width(Theme.DIALOG_W)
            .negative("清除") { a.condition = null; onChanged() }
            .positive("确定") { expr = exprRef?.text?.toString()?.trim() ?: expr; commit(); true }
            .show()
    }

    @Volatile
    private var exprRef: android.widget.EditText? = null
}
