package com.autoball.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.autoball.AB
import com.autoball.core.model.*
import com.autoball.core.util.Display

/**
 * 添加/编辑动作：20 类下拉 → 动态表单（需求 2.3，弹窗三级 240dp）。
 *
 * 表单随类型动态变化，公共项为运行等待 / 重复次数 / 运行条件（选填）。
 */
object ActionEditor {

    fun show(activity: Activity, existing: Action?, onSave: (Action) -> Unit) {
        showTypeDialog(activity, existing, onSave)
    }

    private fun showTypeDialog(activity: Activity, existing: Action?, onSave: (Action) -> Unit) {
        val names = ActionType.values()
        val labels = names.map { it.label }.toTypedArray()
        val d = AlertDialog.Builder(activity)
            .setTitle(if (existing == null) "选择动作类型" else "修改动作类型")
            .setItems(labels) { _, w ->
                val a = existing ?: Action().apply { id = Action.newId() }
                a.type = names[w]
                showFormDialog(activity, a, onSave)
            }
            .setNegativeButton("取消", null)
            .create()
        d.show()
        sizeDialog(activity, d, 240f)
    }

    private fun showFormDialog(activity: Activity, a: Action, onSave: (Action) -> Unit) {
        val ctx = activity
        // 先声明再赋值：拾取坐标时需要把本弹窗临时隐藏，让出屏幕给目标应用
        var dialog: AlertDialog? = null
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Display.dpInt(ctx, 18f), Display.dpInt(ctx, 12f),
                Display.dpInt(ctx, 18f), Display.dpInt(ctx, 4f))
        }

        box.addView(title(ctx, a.type.label))

        val groups = a.type.fieldGroups
        var xEd: EditText? = null
        var yEd: EditText? = null
        var x2Ed: EditText? = null
        var y2Ed: EditText? = null
        var durEd: EditText? = null
        var textEd: EditText? = null
        var pkgEd: EditText? = null
        var urlEd: EditText? = null
        var varNameEd: EditText? = null
        var codeEd: EditText? = null
        var keySpinner: Spinner? = null
        var scriptSpinner: Spinner? = null
        var controlSpinner: Spinner? = null

        if (groups.contains(FieldGroup.POINT)) {
            box.addView(label(ctx, "点击位置"))
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            xEd = numEdit(ctx, a.x.toInt().toString(), "X")
            yEd = numEdit(ctx, a.y.toInt().toString(), "Y")
            row.addView(xEd, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(yEd, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(row)
            box.addView(pickButton(ctx, { dialog }, "拾取坐标") { px, py ->
                xEd?.setText(px.toInt().toString())
                yEd?.setText(py.toInt().toString())
            })
            box.addView(help(ctx, "点击位置"))
        }

        if (groups.contains(FieldGroup.POINT_END)) {
            box.addView(label(ctx, "结束位置"))
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            x2Ed = numEdit(ctx, a.x2.toInt().toString(), "X2")
            y2Ed = numEdit(ctx, a.y2.toInt().toString(), "Y2")
            row.addView(x2Ed, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(y2Ed, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(row)
            box.addView(pickButton(ctx, { dialog }, "拾取结束点") { px, py ->
                x2Ed?.setText(px.toInt().toString())
                y2Ed?.setText(py.toInt().toString())
            })
        }

        if (groups.contains(FieldGroup.PRESS_DURATION)) {
            durEd = numEdit(ctx, a.durationMs.toString(), "按下时间(ms)")
            box.addView(label(ctx, "按下时间"))
            box.addView(durEd)
            box.addView(help(ctx, "按下时间"))
        }

        if (groups.contains(FieldGroup.DURATION)) {
            durEd = numEdit(ctx, a.durationMs.toString(), "滑动/手势时长(ms)")
            box.addView(label(ctx, "时长"))
            box.addView(durEd)
            box.addView(help(ctx, "滑动时长"))
        }

        if (groups.contains(FieldGroup.TEXT)) {
            textEd = EditText(ctx).apply {
                setText(a.text ?: "")
                hint = "文本内容"
                setTextColor(Theme.textPri())
                setHintTextColor(Theme.textSec())
                inputType = InputType.TYPE_CLASS_TEXT
            }
            box.addView(label(ctx, if (a.type == ActionType.SET_VAR) "变量值" else "文本内容"))
            box.addView(textEd)
        }

        if (groups.contains(FieldGroup.VAR_NAME)) {
            varNameEd = EditText(ctx).apply {
                setText(a.varName ?: "")
                hint = "变量名"
                setTextColor(Theme.textPri())
                setHintTextColor(Theme.textSec())
            }
            box.addView(label(ctx, "变量名"))
            box.addView(varNameEd)
        }

        if (groups.contains(FieldGroup.PACKAGE)) {
            pkgEd = EditText(ctx).apply {
                setText(a.pkg ?: "")
                hint = "包名，如 com.android.settings"
                setTextColor(Theme.textPri())
                setHintTextColor(Theme.textSec())
            }
            box.addView(label(ctx, "目标应用"))
            box.addView(pkgEd)
            box.addView(help(ctx, "目标应用"))
        }

        if (groups.contains(FieldGroup.URL)) {
            urlEd = EditText(ctx).apply {
                setText(a.url ?: "")
                hint = "https://"
                setTextColor(Theme.textPri())
                setHintTextColor(Theme.textSec())
            }
            box.addView(label(ctx, "链接地址"))
            box.addView(urlEd)
        }

        if (groups.contains(FieldGroup.KEYCODE)) {
            val keys = arrayOf("返回", "主页", "最近任务", "通知栏", "电源")
            val codes = intArrayOf(
                android.view.KeyEvent.KEYCODE_BACK,
                android.view.KeyEvent.KEYCODE_HOME,
                android.view.KeyEvent.KEYCODE_APP_SWITCH,
                android.view.KeyEvent.KEYCODE_NOTIFICATION,
                android.view.KeyEvent.KEYCODE_POWER)
            keySpinner = Spinner(ctx).apply {
                adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, keys)
                val idx = codes.indexOf(a.keyCode)
                setSelection(if (idx >= 0) idx else 0)
            }
            box.addView(label(ctx, "按键"))
            box.addView(keySpinner)
            box.addView(help(ctx, "按键"))
        }

        if (groups.contains(FieldGroup.SCRIPT_REF)) {
            val scripts = AB.store.all()
            val names = scripts.map { it.name }.toTypedArray()
            scriptSpinner = Spinner(ctx).apply {
                adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                    if (names.isEmpty()) arrayOf("没有可用脚本") else names)
            }
            box.addView(label(ctx, "目标脚本"))
            box.addView(scriptSpinner)
        }

        if (groups.contains(FieldGroup.CONTROL)) {
            val ops = ControlOp.values().map { it.label }.toTypedArray()
            controlSpinner = Spinner(ctx).apply {
                adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, ops)
                setSelection(ControlOp.values().indexOf(a.controlOp))
            }
            box.addView(label(ctx, "控制方式"))
            box.addView(controlSpinner)
        }

        if (groups.contains(FieldGroup.CODE)) {
            codeEd = EditText(ctx).apply {
                setText(a.code ?: "")
                hint = "JS 代码"
                setTextColor(Theme.textPri())
                setHintTextColor(Theme.textSec())
                setSingleLine(false)
                minLines = 4
                gravity = Gravity.TOP
            }
            box.addView(label(ctx, "JS 代码"))
            box.addView(codeEd)
        }

        if (groups.contains(FieldGroup.SUB_ACTIONS)) {
            box.addView(help(ctx, "子动作"))
        }

        // ---- 公共项 ----
        box.addView(divider(ctx))
        val waitEd = numEdit(ctx, a.waitMs.toString(), "运行等待(ms)")
        val repeatEd = numEdit(ctx, a.repeat.toString(), "重复次数")
        val condEd = EditText(ctx).apply {
            setText(a.condition ?: "")
            hint = "运行条件（选填）"
            setTextColor(Theme.textPri())
            setHintTextColor(Theme.textSec())
        }
        box.addView(label(ctx, "运行等待"))
        box.addView(waitEd)
        box.addView(help(ctx, "运行等待"))
        box.addView(label(ctx, "重复次数"))
        box.addView(repeatEd)
        box.addView(help(ctx, "重复次数"))
        box.addView(label(ctx, "运行条件"))
        box.addView(condEd)
        box.addView(help(ctx, "运行条件"))

        val scroll = ScrollView(ctx).apply { addView(box) }
        val d = AlertDialog.Builder(activity)
            .setTitle(a.type.label)
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                xEd?.let { a.x = it.text.toString().toFloatOrNull() ?: a.x }
                yEd?.let { a.y = it.text.toString().toFloatOrNull() ?: a.y }
                x2Ed?.let { a.x2 = it.text.toString().toFloatOrNull() ?: a.x2 }
                y2Ed?.let { a.y2 = it.text.toString().toFloatOrNull() ?: a.y2 }
                durEd?.let { a.durationMs = it.text.toString().toLongOrNull() ?: a.durationMs }
                textEd?.let { a.text = it.text.toString() }
                pkgEd?.let { a.pkg = it.text.toString() }
                urlEd?.let { a.url = it.text.toString() }
                varNameEd?.let { a.varName = it.text.toString() }
                codeEd?.let { a.code = it.text.toString() }
                keySpinner?.let {
                    a.keyCode = when (it.selectedItemPosition) {
                        0 -> android.view.KeyEvent.KEYCODE_BACK
                        1 -> android.view.KeyEvent.KEYCODE_HOME
                        2 -> android.view.KeyEvent.KEYCODE_APP_SWITCH
                        3 -> android.view.KeyEvent.KEYCODE_NOTIFICATION
                        else -> android.view.KeyEvent.KEYCODE_POWER
                    }
                }
                scriptSpinner?.let {
                    val scripts = AB.store.all()
                    if (scripts.isNotEmpty()) a.scriptId = scripts[it.selectedItemPosition].id
                }
                controlSpinner?.let { a.controlOp = ControlOp.values()[it.selectedItemPosition] }
                a.waitMs = waitEd.text.toString().toLongOrNull() ?: a.waitMs
                a.repeat = repeatEd.text.toString().toIntOrNull() ?: a.repeat
                val c = condEd.text.toString().trim()
                a.condition = if (c.isEmpty()) null else c
                if (a.type == ActionType.SET_VAR && a.varValue == null) a.varValue = a.text
                onSave(a)
            }
            .setNegativeButton("取消", null)
            .create()
        dialog = d
        d.show()
        sizeDialog(activity, d, 252f)
    }

    // ---------- 小部件 ----------

    private fun sizeDialog(activity: Activity, d: AlertDialog, widthDp: Float) {
        val w = activity.window?.decorView?.width ?: 0
        val target = Display.dpInt(activity, widthDp)
        val finalW = if (w > 0) kotlin.math.min(target, (w * 0.74f).toInt()) else target
        d.window?.setLayout(finalW, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun title(ctx: Activity, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 16f
        setTextColor(Theme.textPri())
        setPadding(0, 0, 0, Display.dpInt(ctx, 8f))
    }

    private fun label(ctx: Activity, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        setTextColor(Theme.textSec())
        setPadding(0, Display.dpInt(ctx, 8f), 0, Display.dpInt(ctx, 2f))
    }

    private fun help(ctx: Activity, field: String): TextView =
        CoordPicker.helpView(ctx, CoordPicker.helpText(field))

    private fun divider(ctx: Activity): TextView = TextView(ctx).apply {
        setBackgroundColor(Color.parseColor("#22FFFFFF"))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
            setMargins(0, Display.dpInt(ctx, 12f), 0, Display.dpInt(ctx, 4f))
        }
    }

    private fun numEdit(ctx: Activity, value: String, hint: String): EditText = EditText(ctx).apply {
        setText(value)
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        setTextColor(Theme.textPri())
        setHintTextColor(Theme.textSec())
    }

    /** hostProvider 用延迟取值：调用时弹窗尚未 create，直接传引用会拿到 null */
    private fun pickButton(ctx: Activity, hostProvider: () -> android.app.Dialog?, text: String,
                           onPicked: (Float, Float) -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Theme.bubble(ctx, Color.parseColor(Theme.PURPLE), 10f)
            setPadding(Display.dpInt(ctx, 12f), Display.dpInt(ctx, 6f),
                Display.dpInt(ctx, 12f), Display.dpInt(ctx, 6f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, Display.dpInt(ctx, 6f), 0, 0)
            layoutParams = lp
            setOnClickListener { CoordPicker.pick(ctx, hostProvider(), onPicked) }
        }
}
