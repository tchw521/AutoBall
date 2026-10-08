package com.autoball.ui

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.widget.TextView
import android.content.ClipData
import android.content.ClipboardManager
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
        val passEt = android.widget.EditText(act).apply {
            hint = "口令（加密分享码才需要）"
            setTextColor(Theme.textPri())
            setHintTextColor(Theme.textSec())
            setSingleLine(true)
        }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Display.dpInt(act, 20f), Display.dpInt(act, 12f),
                Display.dpInt(act, 20f), 0)
            addView(et)
            // 加密分享码需要口令；普通码留空即可
            addView(passEt)
            addView(Kit.note(act, "若分享码设置了口令，请在此输入；否则留空。", 6f))
        }
        AlertDialog.Builder(act).setTitle("导入分享码").setView(box)
            .setPositiveButton("导入") { d, _ ->
                val code = et.text.toString().trim()
                d.dismiss()
                if (ShareCode.isBatch(code)) {
                    val list = ShareCode.decodeAll(code)
                    if (list.isNullOrEmpty()) {
                        AB.log.error("import", "分享码格式不正确或已损坏")
                        Ui.toast(act, "分享码格式不正确或已损坏")
                    } else {
                        list.forEach {
                            it.id = Script.newId()
                            AB.store.save(it)
                        }
                        AB.log.info("import", "已导入 ${list.size} 个脚本")
                        Ui.toast(act, "已导入 ${list.size} 个脚本")
                        host.refreshAll()
                    }
                } else {
                    val s = ShareCode.decode(code, passEt.text.toString().trim())
                    if (s == null) {
                        AB.log.error("import", "分享码格式不正确、已损坏或口令错误")
                        Ui.toast(act, "分享码格式不正确、已损坏或口令错误")
                    } else {
                        s.id = Script.newId()
                        AB.store.save(s)
                        AB.log.info("import", "已导入「${s.name}」")
                        Ui.toast(act, "已导入「${s.name}」")
                        host.openScript(s)
                    }
                }
            }
            .setNegativeButton("取消", null).show()
    }
    /** 展示生成的分享码（可直接复制） */
    fun showCopy(activity: Activity, name: String, code: String) {
        val tv = TextView(activity).apply {
            text = code
            textSize = 11f
            setTextColor(Theme.textSec())
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(Display.dpInt(activity, 14f), Display.dpInt(activity, 12f),
                Display.dpInt(activity, 14f), Display.dpInt(activity, 12f))
            background = Theme.rect(Theme.surface2(), 10f, activity, Theme.line())
            setHorizontallyScrolling(true)
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Display.dpInt(activity, 20f), Display.dpInt(activity, 12f),
                Display.dpInt(activity, 20f), 0)
            addView(TextView(activity).apply {
                text = "「$name」的分享码"
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Theme.textPri())
                setPadding(0, 0, 0, Display.dpInt(activity, 10f))
            })
            addView(tv)
        }
        AlertDialog.Builder(activity).setTitle("分享码").setView(box)
            .setPositiveButton("复制") { d, _ ->
                val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("autoball", code))
                Ui.toast(activity, "已复制分享码")
                d.dismiss()
            }
            .setNegativeButton("关闭", null).show()
    }

}
