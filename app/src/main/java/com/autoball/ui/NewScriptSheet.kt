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

    /** 新建并落库一个空脚本（工作台弹窗需要一个真实存在的脚本对象） */
    private fun newScript(): Script {
        val s = Script.blank("未命名脚本")
        AB.store.save(s)
        return s
    }

    fun show(activity: Activity, host: PageHost) {
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        // 录制与手动添加共用同一个工作台弹窗：进来后脚本还是空的，
        // 由用户决定是录制还是加动作，不在入口处就分叉
        box.addView(Ui.sheetOption(activity, "●", Theme.ok(), "开始录制",
            "照着点一遍，动作自动记下来") {
            ScriptWorkDialog.show(activity, newScript(), host)
        })
        box.addView(Ui.sheetOption(activity, "＋", Theme.pri(), "空白动作",
            "从添加第一个动作开始") {
            ScriptWorkDialog.show(activity, newScript(), host)
        })
        // 导入分享码不在这里——它是 JS 脚本页的一个按钮，
        // 与「编写 JS 代码」同处一屏，用户找得到
        box.addView(Ui.sheetOption(activity, "{ }", Theme.pri2(), "编写 JS 代码",
            "手写脚本，调用宿主 API") {
            host.openSubPage("js")
        })

        Ui.sheet(activity, "新建脚本").body(box).show()
    }
}
