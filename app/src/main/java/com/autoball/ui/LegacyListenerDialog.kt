package com.autoball.ui

import android.app.Activity
import android.widget.LinearLayout
import com.autoball.core.model.Action

/**
 * 动作级监听钩子（v1 遗留，7 阶段、每阶段 1 个动作）。
 *
 * 设计稿 v3 的 9 个脚本级钩子在 [ListenerDialog]，落在 `Flow.hooks`。
 * 本类保留是为了兼容已存在的 `Action.listeners` 数据——老脚本里可能已挂了
 * 动作级钩子，直接丢弃会让用户的配置静默消失。
 */
object LegacyListenerDialog {

    enum class Stage(val label: String, val desc: String) {
        BEFORE_CONDITION("检查运行条件前", "判断条件表达式之前执行"),
        CONDITION_OK("运行条件满足后", "条件成立、即将执行本动作之前"),
        CONDITION_FAIL("运行条件失败后", "条件不成立、本动作被跳过时执行"),
        BEFORE_ACTION("动作开始运行前", "注入之前执行"),
        ACTION_OK("动作运行成功后", "本动作执行成功之后"),
        ACTION_FAIL("动作运行失败后", "本动作执行失败之后"),
        AFTER_ACTION("动作运行结束后", "无论成功失败都会执行"),
    }

    fun show(activity: Activity, a: Action, onChanged: () -> Unit) {
        val ctx = activity
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        box.addView(Ui.note(ctx,
            "本动作的前后钩子（旧版）。脚本级 9 个时机请在编辑页「监听动作」里设置。"))

        for (st in Stage.values()) {
            val cur = a.listeners[st.name]
            box.addView(Ui.adRow(ctx, st.label, cur?.type?.label ?: "未设置",
                cur != null, st.desc) {
                ActionEditor.show(ctx, cur) { picked ->
                    a.listeners[st.name] = picked
                    onChanged()
                    show(activity, a, onChanged)
                }
            })
        }

        Ui.dialog(ctx, "动作级监听").body(box)
            .width(Theme.DIALOG_W + 20f).maxHeight(0.72f)
            .negative("关闭").show()
    }
}
