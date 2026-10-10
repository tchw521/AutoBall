package com.autoball.ui

import android.app.Activity
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.core.model.Action
import com.autoball.core.model.Flow
import com.autoball.float.FloatDialog
import com.autoball.core.util.Display

/**
 * 全局监听钩子（UI 设计方案 v3 · listenDlg）。
 *
 * 与原 v1 实现的差异：
 * - 原为**动作级** 7 个阶段、每阶段只能挂 1 个动作；
 *   现按设计稿改为**脚本级** 9 个时机，**每个时机可挂多个动作**。
 * - 数据落在 `Flow.hooks`（随脚本一起序列化，分享码可携带）；
 *   动作级的 `Action.listeners` 保留不动，两者互不干扰。
 *
 * 九个时机对应脚本生命周期：脚本开始前 → 列表开头 → 每个动作运行前/后/结束后
 * → 运行成功/失败后 → 列表结尾 → 脚本结束后。
 */
object ListenerDialog {

    /**
     * 触发时机：声明顺序即展示顺序。
     *
     * **存储 key 必须用 [key]（小写），不能用 [name]（大写）**：
     * 运行时 FlowRunner / ScriptRunner 是按小写字符串（`lt` / `br` / `sb` …）
     * 取钩子的，而 `name` 是枚举名（大写）。早前这里用了 `st.name`，
     * 于是配好的全局监听动作**一次都不会执行**，且界面照常显示"已设置 N 项"
     * —— 典型的"界面能存、运行时读不到"的静默失效。
     */
    enum class Stage(val label: String, val desc: String, val hint: String) {
        SB("脚本开始前监听", "开跑前的准备动作",
            "脚本真正开始执行之前触发一次，常用于清场、截图留证或初始化状态"),
        LT("列表开头监听", "每一轮开头都执行一次",
            "每轮动作列表开始遍历时触发，配合重复次数可做到每轮都跑一次"),
        BR("每个动作运行前", "每个动作的前置检查",
            "每个动作执行之前触发，可用于前置校验、坐标校正或写入运行日志"),
        BA("每个动作运行后", "每个动作的收尾记录",
            "每个动作执行之后触发，常用于记录结果、统计耗时与异常兜底"),
        AE("每个动作运行结束后", "比「运行后」更靠后",
            "动作及其内部等待彻底结束后才触发，适合做收尾判断与状态确认"),
        OK("运行成功后", "全部跑通才触发",
            "脚本完整跑通且所有步骤都成功时触发，可用于发送完成通知或保存结果"),
        ER("运行失败后", "留住失败现场",
            "任意步骤失败时触发，建议放截图，方便事后排查当时界面是什么样"),
        EF("每次动作运行失败后", "逐个动作兜底",
            "每个动作重复失败时都触发（区别于上面「运行失败后」的脚本级一次）。\n"
            + "适合给关键步骤单独挂兜底，比如失败就重试、跳过或记录当时界面"),
        LE("列表结尾监听", "一轮结束的汇总点",
            "一轮动作列表遍历到末尾时触发，可用于汇总本轮数据或为下一轮做准备"),
        SE("脚本结束后监听", "成功失败都会执行",
            "脚本全部结束后触发一次（无论成败），用于释放资源或恢复手机状态"),
        ;

        /** 存储 / 触发用的 key：小写，与运行时一致 */
        val key: String get() = name.lowercase()

        companion object {
            fun byName(n: String): Stage? =
                values().firstOrNull { it.name.equals(n, true) }
        }
    }

    /**
     * 打开动作编辑器。
     *
     * **形态必须由外层弹窗的形态决定，不能看 ctx 是不是 Activity**：
     * 全局设置是悬浮窗（FloatDialog）弹出的，但调用方传进来的 ctx 常常
     * **就是 Activity**（工作台弹窗持有 activity 引用）。早前这里写
     * `ctx as? Activity != null → ActionEditor.show(act, ...)`，于是走成了
     * Activity 内的 AlertDialog——而此时用户正在桌面或别的应用上，Activity 在后台，
     * 对话框根本不显示。表现为「点「未设置」一点用都没有」，且不报错。
     *
     * 现在由 [asFloat] 显式指定，与外层弹窗保持一致。
     */
    private fun openEditor(ctx: android.content.Context, flow: Flow?,
                           existing: Action?, asFloat: Boolean,
                           onSave: (Action) -> Unit) {
        val act = ctx as? Activity
        if (!asFloat && act != null && !act.isFinishing && !act.isDestroyed)
            ActionEditor.show(act, existing, flow, onSave)
        else
            ActionEditor.showFloat(ctx, existing, flow, onSave)
    }

    /**
     * 点某一时机。
     *
     * **「未设置」必须直接打开添加动作页面**，不再先进一层空列表再点「＋ 添加动作」：
     * 用户点「未设置」表达的意图就是"我要加一个"，中间那层只有一个空列表和一行按钮，
     * 纯属多余一次点击。
     *
     * 已挂动作时才打开列表做增删改。
     */
    private fun onStageClick(ctx: android.content.Context, flow: Flow, st: Stage,
                             onChanged: () -> Unit, asFloat: Boolean,
                             rebuild: () -> Unit) {
        if (flow.hooks[st.key].isNullOrEmpty()) {
            openEditor(ctx, flow, null, asFloat) { a ->
                flow.hooks.getOrPut(st.key) { ArrayList() }.add(a)
                onChanged()
                rebuild()
            }
        } else {
            stageDetail(ctx, flow, st, onChanged, asFloat)
        }
    }

    /**
     * 悬浮窗形态的全局监听动作。
     *
     * 与 [show] 共用同一份行构建与提交逻辑，两处各写一遍必然出现参数口径不一致。
     */
    fun showFloat(ctx: android.content.Context, flow: Flow, onChanged: () -> Unit) {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fun rebuild() {
            box.removeAllViews()
            box.addView(Ui.note(ctx,
                "在指定时机自动执行附加动作。每个时机可挂多个，按顺序执行。"))
            for (st in Stage.values()) {
                val list = flow.hooks[st.key] ?: emptyList<Action>()
                box.addView(stageRow(ctx, st, list) {
                    onStageClick(ctx, flow, st, onChanged, true) { rebuild() }
                })
            }
        }
        rebuild()
        FloatDialog.show(ctx, "全局监听动作")
            .body(box)
            .width(Theme.DIALOG_W + 30f)
            .negative("关闭")
            .show()
    }

    fun show(activity: Activity, flow: Flow, onChanged: () -> Unit) {
        val ctx = activity
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        fun rebuild() {
            box.removeAllViews()
            box.addView(Ui.note(ctx,
                "在指定时机自动执行附加动作。每个时机可挂多个，按顺序执行。"))

            for (st in Stage.values()) {
                val list = flow.hooks[st.key] ?: emptyList<Action>()
                box.addView(stageRow(ctx, st, list) {
                    // 打开该时机的动作列表编辑
                    // Activity 形态；若 Activity 已不可用于弹窗（如被切到后台），
                    // stageDetail 内部会回退到悬浮窗形态。
                    onStageClick(activity, flow, st, onChanged, false) { rebuild() }
                })
            }
        }

        rebuild()
        Ui.dialog(ctx, "全局监听动作")
            .body(box)
            .width(Theme.DIALOG_W + 30f)
            .maxHeight(0.76f)
            .negative("关闭")
            .show()
    }

    private fun stageRow(ctx: android.content.Context, st: Stage, list: List<Action>,
                         onClick: () -> Unit): LinearLayout {
        val row = Ui.adRow(ctx, st.label,
            if (list.isEmpty()) "未设置" else "${list.size} 个动作",
            list.isNotEmpty(), st.hint) { onClick() }
        // 副标题：展示已挂动作类型
        if (list.isNotEmpty()) {
            val sub = TextView(ctx).apply {
                text = list.joinToString(" · ") { it.type.label }
                textSize = 9.5f
                setTextColor(Theme.textTer())
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(Display.dpInt(ctx, 20f), 0, Display.dpInt(ctx, 8f),
                    Display.dpInt(ctx, 4f))
            }
            val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            wrap.addView(row)
            wrap.addView(sub)
            return wrap
        }
        return row
    }

    /** 单个时机的动作列表：添加 / 替换 / 删除 */
    private fun stageDetail(ctx: android.content.Context, flow: Flow, st: Stage,
                            onChanged: () -> Unit, asFloat: Boolean) {
        val list = flow.hooks.getOrPut(st.key) { ArrayList() }
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        fun rebuild() {
            box.removeAllViews()
            box.addView(Ui.note(ctx, st.hint))
            if (list.isEmpty()) {
                box.addView(TextView(ctx).apply {
                    text = "还没有动作"
                    textSize = 11.5f
                    setTextColor(Theme.textTer())
                    gravity = Gravity.CENTER
                    setPadding(0, Display.dpInt(ctx, 14f), 0, Display.dpInt(ctx, 14f))
                })
            } else {
                list.forEachIndexed { i, a ->
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(Display.dpInt(ctx, 8f), Display.dpInt(ctx, 5f),
                            Display.dpInt(ctx, 8f), Display.dpInt(ctx, 5f))
                    }
                    row.addView(TextView(ctx).apply {
                        text = "${i + 1}"
                        textSize = 10f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(Theme.textTer())
                    })
                    row.addView(TextView(ctx).apply {
                        text = a.type.label
                        textSize = 12.5f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(Theme.textPri())
                        setPadding(Display.dpInt(ctx, 7f), 0, 0, 0)
                        layoutParams = LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    row.addView(Kit.miniBtn(ctx, "✕") {
                        list.removeAt(i)
                        // 必须用 st.key（小写）而不是 st.name（大写枚举名）：
                        // map 是以小写 key 存的，用大写 remove 删不掉，
                        // 于是留下一个空 list 条目——界面显示「未设置」，
                        // 但 hookSummary() 仍把它算作已配置的一项。
                        if (list.isEmpty()) flow.hooks.remove(st.key)
                        onChanged()
                        rebuild()
                    })
                    box.addView(row)
                }
            }
            box.addView(TextView(ctx).apply {
                text = "＋ 添加动作"
                textSize = 12.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Theme.pri())
                gravity = Gravity.CENTER
                background = Theme.dashed(ctx, 11f)
                setPadding(Display.dpInt(ctx, 10f), Display.dpInt(ctx, 9f),
                    Display.dpInt(ctx, 10f), Display.dpInt(ctx, 9f))
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, Display.dpInt(ctx, 8f), 0, 0)
                layoutParams = lp
                setOnClickListener {
                    openEditor(ctx, flow, null, asFloat) { a ->
                        list.add(a)
                        onChanged()
                        rebuild()
                    }
                }
            })
        }

        rebuild()
        val act = ctx as? Activity
        // 形态跟随外层弹窗；Activity 不可用时回退悬浮窗，避免后台弹窗不显示
        val useFloat = asFloat || act == null || act.isFinishing || act.isDestroyed
        if (!useFloat) {
            Ui.dialog(act!!, st.label).body(box).width(Theme.DIALOG_W + 20f)
                .maxHeight(0.7f).negative("关闭").show()
        } else if (!FloatDialog.show(ctx, st.label).body(box)
                .width(Theme.DIALOG_W + 20f).negative("关闭").show()
            && act != null && !act.isFinishing && !act.isDestroyed) {
            // 悬浮窗权限缺失时的兜底
            Ui.dialog(act, st.label).body(box).width(Theme.DIALOG_W + 20f)
                .maxHeight(0.7f).negative("关闭").show()
        }
    }
}
