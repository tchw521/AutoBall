package com.autoball.ui

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.widget.LinearLayout
import com.autoball.AB
import com.autoball.core.model.Script
import com.autoball.core.store.ShareCode
import com.autoball.core.util.Display

/**
 * 导入分享码（独立组件，供底部半框与页面共用）。
 */
object ShareImportDialog {

    fun show(activity: Activity, host: PageHost) {
        val act = activity
        val et = android.widget.EditText(act).apply {
            hint = "粘贴分享码"
            setTextColor(Theme.textPri())
            setHintTextColor(Theme.textSec())
            setSingleLine(false)
            minLines = 3
            gravity = Gravity.TOP
        }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Display.dpInt(act, 20f), Display.dpInt(act, 12f),
                Display.dpInt(act, 20f), 0)
            addView(et)
        }
        AlertDialog.Builder(act).setTitle("导入分享码").setView(box)
            .setPositiveButton("导入") { d, _ ->
                val code = et.text.toString().trim()
                val s = ShareCode.decode(code)
                if (s == null) {
                    AB.log.error("import", "分享码格式不正确或已损坏")
                } else {
                    s.id = Script.newId()
                    AB.store.save(s)
                    AB.log.info("import", "已导入「${s.name}」")
                    host.openScript(s)
                }
                d.dismiss()
            }
            .setNegativeButton("取消", null).show()
    }
}
