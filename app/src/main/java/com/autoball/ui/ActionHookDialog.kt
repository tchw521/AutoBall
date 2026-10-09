package com.autoball.ui

import android.app.Activity
import android.widget.LinearLayout
import com.autoball.core.model.Action
import com.autoball.core.model.ActionHookStage

/**
 * 动作级「监听动作」窗口（统一组件）。
 *
 * 入口：编辑动作页的「监听动作」行右侧「未设置 / 已设置」按钮（图 57）。
 * 列表为 7 个触发阶段，每阶段右侧「未设置」按钮点开后
 * 复用**同一个** [ActionEditor]（统一组件）编辑该阶段要跑的动作。
 *
 * 三点设计：
 * 1. **每个阶段的说明不同**（[ActionHookStage.hint]）。用一句通用话术套七个阶段
 *    等于没说——用户真正需要知道的是"这个钩子到底挂在哪个时刻"。
 * 2. 已设置的行显示该动作的类型，不点开也能看出挂了什么。
 * 3. 取消 / 确定都只是关闭；改动**即时生效**，与自动精灵一致。
 *    不做"确定才提交"是因为嵌套两层弹窗后再要求回外层确认，容易让人以为没保存。
 */
object ActionHookDialog {

    /**
     * @param a 被编辑的动作（钩子挂在这个动作上）
     * @param onChanged 关闭后刷新外层表单的「监听动作」摘要
     */
    fun show(activity: Activity, a: Action, onChanged: () -> Unit) {
        val ctx = activity
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        fun fill() {
            box.removeAllViews()
            box.addView(Ui.note(ctx,
                "这些钩子只作用于**当前这一个动作**。\n" +
                "整段脚本的钩子在「脚本全局设置 → 全局监听动作」。"))
            for (st in ActionHookStage.values()) {
                val hook = a.listeners[st.key]
                box.addView(Ui.adRow(ctx, st.label,
                    summaryOf(hook), hook != null, st.hint) {
                    // 统一走 ActionEditor：与添加动作是同一套表单，
                    // 不另写一份，参数口径也不会不一致
                    ActionEditor.show(ctx, hook) { na ->
                        a.listeners[st.key] = na
                        onChanged()
                        fill()
                    }
                })
                if (hook != null) {
                    box.addView(Ui.note(ctx, "  已挂：${hook.type.label}${hook.desc?.let { " · $it" } ?: ""}"))
                }
            }
        }
        fill()

        Ui.dialog(ctx, "监听动作").body(box)
            .width(Theme.DIALOG_W + 10f).maxHeight(0.8f)
            .negative("清除全部") {
                ActionHookStage.values().forEach { a.listeners.remove(it.key) }
                onChanged()
                fill()
            }
            .positive("确定") { onChanged(); true }
            .show()
    }

    private fun summaryOf(hook: Action?): String {
        if (hook == null) return "未设置"
        return "已设置 · ${hook.type.label}"
    }
}
