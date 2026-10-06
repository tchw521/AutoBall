package com.autoball.ui

import android.app.Activity
import android.widget.LinearLayout
import com.autoball.AB
import com.autoball.core.model.Script

/**
 * 「新建脚本」底部半框（UI 设计方案 v3 底部半框）。
 *
 * 三个入口：开始录制 / 空白脚本 / 导入分享码。
 * 由底部导航中央按钮与「制作」标签共同触发——不再是跳一个页面，而是直接弹出。
 */
object NewScriptSheet {

    fun show(activity: Activity, host: PageHost) {
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        box.addView(Ui.sheetOption(activity, "●", Theme.ok(), "开始录制",
            "照着点一遍，动作自动记下来") {
            host.startRecording()
        })
        box.addView(Ui.sheetOption(activity, "＋", Theme.pri(), "空白脚本",
            "从添加第一个动作开始") {
            val s = Script.blank("未命名脚本")
            AB.store.save(s)
            host.openScript(s)
        })
        box.addView(Ui.sheetOption(activity, "🔗", Theme.pri2(), "导入分享码",
            "粘贴一串码，整脚本到手") {
            ShareImportDialog.show(activity, host)
        })

        Ui.sheet(activity, "新建脚本").body(box).show()
    }
}
