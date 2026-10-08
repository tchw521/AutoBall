package com.autoball.ui

import android.app.Activity
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.core.model.Script
import com.autoball.core.util.Display

/**
 * 查看变量（自动精灵录制窗「更多 → 查看变量」）。
 *
 * 列出脚本里出现过的变量：设置变量动作写入的、以及 JS 中赋值的。
 * 纯展示，不做编辑——运行期变量值依赖上下文，静态编辑没有意义。
 */
object VarsDialog {

    fun show(act: Activity, script: Script) {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

        val vars = LinkedHashMap<String, String>()
        script.flow?.actions?.forEach { a ->
            if (!a.varName.isNullOrEmpty()) vars[a.varName!!] = a.varValue ?: ""
        }

        val listBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

        fun fill() {
            listBox.removeAllViews()
            if (vars.isEmpty()) {
                listBox.addView(TextView(act).apply {
                    text = "还没有变量。\n可手动添加，或由「设置变量」动作在运行时写入。"
                    textSize = 12f
                    setTextColor(Theme.textSec())
                    gravity = Gravity.CENTER
                    setLineSpacing(Display.dp(act, 2f), 1.5f)
                    setPadding(Display.dpInt(act, 14f), Display.dpInt(act, 18f),
                        Display.dpInt(act, 14f), Display.dpInt(act, 18f))
                })
            } else {
                vars.forEach { (k, v) ->
                    listBox.addView(Ui.adRow(act, k, v.ifEmpty { "空" }, v.isNotEmpty(),
                        "运行时可被后续动作与运行条件引用（\$k）") {
                        editVar(act, k, v) { nk, nv -> vars.remove(k); vars[nk] = nv; fill() }
                    })
                }
            }
        }
        fill()
        box.addView(listBox)

        // 自动精灵的变量面板可手动添加变量，这里补齐——
        // 否则只能等脚本跑起来才有值，调试时很不方便
        box.addView(Kit.button(act, "+ 添加变量", false) {
            editVar(act, "", "") { k, v -> vars[k] = v; fill() }
        })
        box.addView(Kit.note(act,
            "手动添加的变量会写回脚本，作为「设置变量」动作的初始值。", 6f))

        Ui.dialog(act, "变量（${vars.size}）")
            .body(box)
            .width(Theme.DIALOG_W)
            .negative("关闭") { }
            .positive("保存") {
                writeBack(script, vars); true
            }
            .show()
    }
    private fun editVar(act: Activity, key: String, value: String,
                        onDone: (String, String) -> Unit) {
        val kEt = android.widget.EditText(act).apply {
            setText(key); hint = "变量名"; setSingleLine(true); textSize = 13f
            isEnabled = key.isEmpty()
        }
        val vEt = android.widget.EditText(act).apply {
            setText(value); hint = "值"; setSingleLine(true); textSize = 13f
        }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Display.dpInt(act, 12f), Display.dpInt(act, 8f),
                Display.dpInt(act, 12f), Display.dpInt(act, 4f))
            addView(TextView(act).apply {
                text = "变量名"; textSize = 11f; setTextColor(Theme.textTer())
            })
            addView(kEt)
            addView(TextView(act).apply {
                text = "值"; textSize = 11f; setTextColor(Theme.textTer())
                setPadding(0, Display.dpInt(act, 8f), 0, 0)
            })
            addView(vEt)
        }
        Ui.dialog(act, if (key.isEmpty()) "添加变量" else "编辑变量")
            .body(box)
            .negative("取消") { }
            .positive("确定") {
                val k = kEt.text.toString().trim()
                if (k.isEmpty()) { Ui.toast(act, "变量名不能为空"); false }
                else { onDone(k, vEt.text.toString()); true }
            }.show()
    }

    /** 把变量写回脚本：已存在的更新，新增的补一个「设置变量」动作 */
    private fun writeBack(script: Script, vars: MutableMap<String, String>) {
        val acts = script.flow?.actions ?: return
        vars.forEach { (k, v) ->
            val exist = acts.firstOrNull { it.varName == k }
            if (exist != null) {
                exist.varValue = v
            } else {
                acts.add(com.autoball.core.model.Action().apply {
                    id = com.autoball.core.model.Action.newId()
                    type = com.autoball.core.model.ActionType.SET_VAR
                    varName = k
                    varValue = v
                    optionLabel = "设置变量"
                })
            }
        }
        com.autoball.AB.store.save(script)
    }

}
