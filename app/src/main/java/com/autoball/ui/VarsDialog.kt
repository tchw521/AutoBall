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

        if (vars.isEmpty()) {
            box.addView(TextView(act).apply {
                text = "该脚本还没有变量。\n添加「设置变量」动作即可在这里看到。"
                textSize = 12f
                setTextColor(Theme.textSec())
                gravity = Gravity.CENTER
                setLineSpacing(Display.dp(act, 2f), 1.5f)
                setPadding(Display.dpInt(act, 14f), Display.dpInt(act, 22f),
                    Display.dpInt(act, 14f), Display.dpInt(act, 22f))
            })
        } else {
            vars.forEach { (k, v) ->
                box.addView(Ui.adRow(act, k, v.ifEmpty { "空" }, v.isNotEmpty(),
                    "由「设置变量」动作写入，运行时可被后续动作引用") { })
            }
        }

        Ui.dialog(act, "变量（${vars.size}）")
            .body(box)
            .width(Theme.DIALOG_W)
            .negative("关闭") { }
            .show()
    }
}
