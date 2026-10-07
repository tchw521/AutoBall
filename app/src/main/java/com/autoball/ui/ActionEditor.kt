package com.autoball.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.autoball.core.model.Action
import com.autoball.core.model.ActionType
import com.autoball.core.model.ControlOp
import com.autoball.core.util.Display

/**
 * 添加 / 编辑动作（v3 #adDlg：206dp 宽的紧凑弹窗）。
 *
 * 结构对齐设计稿：
 * - 类型行（.adrow）：点选后在 .gpop 下拉里挑 20 类动作
 * - 位置行：弹出全屏选点 / 区域选择
 * - .adsec 分组分隔
 * - 开关行（是否启用 / 失败后停止…）
 * - 输入行（.adrow.wide.inp）
 * - 底部 .gf：取消 / 确定
 *
 * 弹窗内所有下拉都走 Ui.popMenu（层级 100，压在弹窗之上）。
 */
object ActionEditor {

    /** 一行摘要：用于步骤列表与日志 */
    fun describe(a: Action): String = when (a.type) {
        ActionType.CLICK -> "(${a.x.toInt()}, ${a.y.toInt()})"
        ActionType.SWIPE, ActionType.GESTURE_SINGLE ->
            "(${a.x.toInt()}, ${a.y.toInt()}) → (${a.x2.toInt()}, ${a.y2.toInt()}) ${a.durationMs}ms"
        ActionType.GESTURE_MULTI -> "${a.strokes.size} 指手势"
        ActionType.INPUT_TEXT -> "输入「${a.text ?: ""}」"
        ActionType.OPEN_APP -> a.pkg ?: "未指定应用"
        ActionType.OPEN_URL -> a.url ?: "未指定链接"
        ActionType.KEY -> "按键 ${a.keyCode}"
        ActionType.RUN_JS -> "JS ${a.code?.length ?: 0} 字符"
        ActionType.SET_VAR -> "${a.varName} = ${a.varValue}"
        ActionType.TOAST -> "提示「${a.text ?: ""}」"
        ActionType.CONTROL_FLOW -> a.controlOp.label
        ActionType.CLICK_TEXT -> "文字「${a.text ?: ""}」"
        ActionType.CLICK_NODE -> "节点 ${a.nodeSpec?.text ?: a.nodeSpec?.id ?: ""}"
        ActionType.CLICK_IMAGE -> "图片 ${a.imageRef ?: "未设置"}"
        ActionType.CLICK_COLOR -> "颜色 ${a.colorHex ?: "未设置"}"
        ActionType.AI_CLICK -> "AI 点击"
        ActionType.RECOGNIZE_SCREEN -> "识别屏幕"
        ActionType.RUN_SCRIPT -> "子脚本"
        ActionType.RUN_ACTIONS -> "${a.subActions.size} 个子动作"
    }.let { base ->
        val extra = ArrayList<String>()
        if (a.repeat > 1) extra.add("重复 ${a.repeat}")
        if (a.condition != null) extra.add("有条件")
        if (a.listeners.isNotEmpty()) extra.add("监听 ${a.listeners.size}")
        if (extra.isEmpty()) base else "$base · ${extra.joinToString(" ")}"
    }

    @Volatile
    private var gridDlg: android.app.Dialog? = null

    fun show(activity: Activity, existing: Action?, onSave: (Action) -> Unit) {
        val a = existing ?: Action().apply {
            id = Action.newId()
            type = ActionType.CLICK
            x = 50f; y = 50f
        }
        showForm(activity, a, onSave)
    }

    // =====================================================================
    // 紧凑表单弹窗
    // =====================================================================

    private fun showForm(activity: Activity, a: Action, onSave: (Action) -> Unit) {
        val ctx = activity
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val rows = ArrayList<() -> Unit>()     // 每次刷新重绘所有行
        var dialog: Dialog? = null
        gridDlg = null

        fun rebuild() {
            box.removeAllViews()
            rows.forEach { it() }
        }

        // ---- 类型 ----
        rows.add {
            box.addView(Ui.adRow(ctx, "动作类型", a.type.label, true,
                "共 ${ActionType.values().size} 类动作，按需挑选") {
                val act = ctx as? Activity ?: return@adRow
                val items = ActionType.values().map { it.label to "" }
                val grid = Ui.actionGrid(act, items) { i ->
                    a.type = ActionType.values()[i]
                    gridDlg?.dismiss()
                    rebuild()
                }
                val box2 = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(Display.dpInt(act, 14f), Display.dpInt(act, 6f),
                        Display.dpInt(act, 14f), 0)
                    addView(grid)
                    addView(TextView(act).apply {
                        text = "坐标均为百分比，换机型与转屏都不会点偏。"
                        textSize = 10.5f
                        setTextColor(Theme.textTer())
                        setPadding(0, Display.dpInt(act, 6f), 0, Display.dpInt(act, 6f))
                    })
                }
                gridDlg = Ui.dialog(act, "选择动作类型").body(box2)
                    .width(Theme.DIALOG_W + 60f).maxHeight(0.7f)
                    .negative("取消").show()
            })
        }

        // ---- 按类型分组的字段 ----
        val groups = a.type.fieldGroups
        if (groups.isNotEmpty()) box.addView(Ui.adSec(ctx))

        if (groups.contains(com.autoball.core.model.FieldGroup.POINT)) {
            rows.add {
                box.addView(Ui.adRow(ctx, "位置", "(${a.x.toInt()}%, ${a.y.toInt()}%)", true,
                    "百分比坐标，换机型不会点偏") {
                    dialog?.let { d ->
                        CoordPicker.pick(ctx, activity, d) { px, py ->
                            a.x = px; a.y = py
                            rebuild()
                        }
                    }
                })
            }
        }
        if (groups.contains(com.autoball.core.model.FieldGroup.POINT_END)) {
            rows.add {
                box.addView(Ui.adRow(ctx, "结束位置",
                    "(${a.x2.toInt()}%, ${a.y2.toInt()}%)", a.x2 != 0f || a.y2 != 0f,
                    "框选起点与终点，一次填满两个坐标") {
                    dialog?.let { d ->
                        RegionPicker.pick(ctx, activity, d) { l, t, r, b ->
                            a.x = l; a.y = t; a.x2 = l + r; a.y2 = t + b
                            rebuild()
                        }
                    }
                })
            }
        }
        if (groups.contains(com.autoball.core.model.FieldGroup.PRESS_DURATION)) {
            val row = Ui.adNumber(ctx, a.durationMs.toString(), "ms", "按下时长")
            rows.add { box.addView(row) }
        }
        if (groups.contains(com.autoball.core.model.FieldGroup.DURATION)) {
            val row = Ui.adNumber(ctx, a.durationMs.toString(), "ms", "滑动时长")
            rows.add { box.addView(row) }
        }
        if (groups.contains(com.autoball.core.model.FieldGroup.TEXT)) {
            val et = Ui.adText(ctx, a.text ?: "", "要输入或匹配的内容")
            rows.add { box.addView(et) }
        }
        if (groups.contains(com.autoball.core.model.FieldGroup.PACKAGE)) {
            val et = Ui.adText(ctx, a.pkg ?: "", "包名，如 com.tencent.mm")
            rows.add { box.addView(et) }
        }
        if (groups.contains(com.autoball.core.model.FieldGroup.URL)) {
            val et = Ui.adText(ctx, a.url ?: "", "https://…")
            rows.add { box.addView(et) }
        }
        if (groups.contains(com.autoball.core.model.FieldGroup.KEYCODE)) {
            val row = Ui.adNumber(ctx, a.keyCode.toString(), "code", "按键码")
            rows.add { box.addView(row) }
        }
        if (groups.contains(com.autoball.core.model.FieldGroup.CODE)) {
            val et = Ui.adText(ctx, a.code ?: "", "JS 代码")
            rows.add { box.addView(et) }
        }
        if (groups.contains(com.autoball.core.model.FieldGroup.VAR_NAME)) {
            val et = Ui.adText(ctx, a.varName ?: "", "变量名")
            rows.add { box.addView(et) }
        }
        if (groups.contains(com.autoball.core.model.FieldGroup.CONTROL)) {
            rows.add {
                box.addView(Ui.adRow(ctx, "控制", a.controlOp.label, true,
                "暂停 / 继续 / 停止 / 跳转 / 等待") {
                    Ui.popMenu(ctx, box, ControlOp.values().map { it.label },
                        ControlOp.values().indexOf(a.controlOp)) { i ->
                        a.controlOp = ControlOp.values()[i]
                        rebuild()
                    }
                })
            }
        }

        // ---- 公共项 ----
        box.addView(Ui.adSec(ctx))
        val waitRow = Ui.adNumber(ctx, a.waitMs.toString(), "ms", "运行后等待")
        rows.add { box.addView(waitRow) }
        val repRow = Ui.adNumber(ctx, a.repeat.toString(), "次", "重复次数")
        rows.add { box.addView(repRow) }

        rows.add {
            box.addView(Ui.adRow(ctx, "运行条件", a.condition ?: "未设置", a.condition != null,
                "条件成立才执行本动作") {
                ConditionDialog.show(ctx, a) { rebuild() }
            })
        }
        rows.add {
            box.addView(Ui.adRow(ctx, "监听动作",
                if (a.listeners.isEmpty()) "未设置" else "已设置 ${a.listeners.size} 项",
                a.listeners.isNotEmpty(), "在指定时机自动执行附加动作") {
                ListenerDialog.show(ctx, a) { rebuild() }
            })
        }

        // ---- 备注 ----
        box.addView(Ui.adSec(ctx))
        val noteEt = Ui.adText(ctx, a.comment ?: "", "备注（选填）")
        rows.add { box.addView(noteEt) }

        // ---- 说明 ----
        box.addView(TextView(ctx).apply {
            text = "坐标均为百分比，换机型与转屏都不会点偏。"
            textSize = 10.5f
            setTextColor(Theme.textTer())
            setPadding(Display.dpInt(ctx, 8f), Display.dpInt(ctx, 6f),
                Display.dpInt(ctx, 8f), 0)
        })

        rebuild()

        dialog = Ui.dialog(ctx, "添加动作")
            .body(box)
            .width(Theme.DIALOG_W)
            .maxHeight(0.6f)
            .negative("取消")
            .positive("确定") {
                collect(box, a)
                onSave(a)
                true
            }
            .show()
    }

    /** 从动态生成的表单里按出现顺序回填字段 */
    private fun collect(box: LinearLayout, a: Action) {
        val numbers = ArrayList<String>()
        val texts = ArrayList<String>()
        fun walk(v: View) {
            if (v is LinearLayout && v.tag is EditText) numbers.add(Ui.adNumberValue(v))
            if (v is EditText && v.tag == null) texts.add(v.text.toString())
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(box)
        var ni = 0
        var ti = 0
        val groups = a.type.fieldGroups
        if (groups.contains(com.autoball.core.model.FieldGroup.PRESS_DURATION) ||
            groups.contains(com.autoball.core.model.FieldGroup.DURATION)) {
            a.durationMs = numbers.getOrNull(ni++)?.toLongOrNull() ?: a.durationMs
        }
        if (groups.contains(com.autoball.core.model.FieldGroup.TEXT)) {
            a.text = texts.getOrNull(ti++)?.ifBlank { null }
        }
        if (groups.contains(com.autoball.core.model.FieldGroup.PACKAGE)) {
            a.pkg = texts.getOrNull(ti++)?.ifBlank { null }
        }
        if (groups.contains(com.autoball.core.model.FieldGroup.URL)) {
            a.url = texts.getOrNull(ti++)?.ifBlank { null }
        }
        if (groups.contains(com.autoball.core.model.FieldGroup.KEYCODE)) {
            a.keyCode = numbers.getOrNull(ni++)?.toIntOrNull() ?: a.keyCode
        }
        if (groups.contains(com.autoball.core.model.FieldGroup.CODE)) {
            a.code = texts.getOrNull(ti++)?.ifBlank { null }
        }
        if (groups.contains(com.autoball.core.model.FieldGroup.VAR_NAME)) {
            a.varName = texts.getOrNull(ti++)?.ifBlank { null }
            a.varValue = texts.getOrNull(ti++)
        }
        // 公共项
        a.waitMs = numbers.getOrNull(ni++)?.toLongOrNull() ?: a.waitMs
        a.repeat = (numbers.getOrNull(ni++)?.toIntOrNull() ?: a.repeat).coerceAtLeast(1)
        // 备注是最后一个文本
        a.comment = texts.lastOrNull()?.ifBlank { null }
    }
}
