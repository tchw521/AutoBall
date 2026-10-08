package com.autoball.ui

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.core.model.Action
import com.autoball.core.model.ActionType
import com.autoball.core.model.Script
import com.autoball.core.util.Display
import com.autoball.float.FloatDialog
import com.autoball.float.FloatWindows

/**
 * 「更多工具」快捷面板——复刻自动精灵录制窗里的同名面板。
 *
 * 自动精灵的这一屏是 3 列宫格，点一下就把对应动作追加到脚本末尾：
 * 返回键 / 返回桌面 / 最近任务 / 屏幕截屏 / 下拉状态栏 / 打开App /
 * 图像匹配 / 节点匹配 / 颜色匹配 / 文字输入 / 文字匹配 /
 * 连续点击 / 随机点击 / 覆盖点击 / 定长滑动
 *
 * 与 [ActionEditor] 的分工：这里只做**高频、参数可缺省**的动作，
 * 一点即插入；需要精细配坐标与条件的仍走动作编辑器。
 *
 * 形态用 [FloatDialog]（悬浮窗层），不把用户拽回应用界面。
 */
object ToolPanel {

    private data class Tool(
        val label: String,
        val type: ActionType,
        /** 缺省参数：新动作落在屏幕中心，避免用户还得先选点 */
        val preset: (Action) -> Unit = {}
    )

    private val TOOLS = listOf(
        Tool("返回键", ActionType.KEY) { it.keyCode = 4; it.optionLabel = "返回键" },
        Tool("返回桌面", ActionType.KEY) { it.keyCode = 3; it.optionLabel = "返回桌面" },
        Tool("最近任务", ActionType.KEY) { it.keyCode = 187; it.optionLabel = "最近任务" },
        Tool("屏幕截屏", ActionType.RECOGNIZE_SCREEN) { it.optionLabel = "屏幕截屏" },
        Tool("下拉状态栏", ActionType.KEY) { it.keyCode = 1001; it.optionLabel = "下拉状态栏" },
        Tool("打开App", ActionType.OPEN_APP) { it.optionLabel = "打开应用" },
        Tool("图像匹配", ActionType.CLICK_IMAGE) {
            it.optionLabel = "图像匹配"; it.matchThreshold = 0.9f
        },
        Tool("节点匹配", ActionType.CLICK_NODE) { it.optionLabel = "节点匹配" },
        Tool("颜色匹配", ActionType.CLICK_COLOR) {
            it.optionLabel = "颜色匹配"; it.colorTolerance = 10
        },
        Tool("文字输入", ActionType.INPUT_TEXT) { it.optionLabel = "输入文字" },
        Tool("文字匹配", ActionType.CLICK_TEXT) { it.optionLabel = "文字匹配" },
        Tool("连续点击", ActionType.CLICK) {
            it.optionLabel = "连续点击"; it.durationMs = 60
            it.repeat = 5; it.repeatIntervalMs = 200
        },
        Tool("随机点击", ActionType.CLICK) {
            it.optionLabel = "随机点击"; it.durationMs = 60
        },
        Tool("覆盖点击", ActionType.CLICK) {
            it.optionLabel = "点击"; it.durationMs = 60
        },
        Tool("定长滑动", ActionType.SWIPE) {
            it.optionLabel = "定长滑动"; it.durationMs = 500
            it.x2 = 50f; it.y2 = 20f
        }
    )

    fun show(ctx: Context, script: Script, onAdded: (Action) -> Unit) {
        val flow = script.flow
        if (flow == null) {
            Ui.toast(ctx, "该脚本还没有动作流")
            return
        }
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        var row: LinearLayout? = null
        TOOLS.forEachIndexed { i, t ->
            if (i % 3 == 0) {
                row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                box.addView(row)
            }
            row!!.addView(cell(ctx, t.label) {
                val a = Action().apply {
                    id = Action.newId()
                    type = t.type
                    x = 50f; y = 50f
                    t.preset(this)
                }
                flow.actions.add(a)
                onAdded(a)
                Ui.toast(ctx, "已添加：${t.label}")
            }, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(Display.dpInt(ctx, 3f), Display.dpInt(ctx, 3f),
                    Display.dpInt(ctx, 3f), Display.dpInt(ctx, 3f))
            })
        }

        box.addView(TextView(ctx).apply {
            text = "点一下即追加到脚本末尾，默认落在屏幕中心；需要精确坐标请点「添加动作」。"
            textSize = 10.5f
            setTextColor(Theme.textTer())
            setPadding(0, Display.dpInt(ctx, 6f), 0, Display.dpInt(ctx, 2f))
        })

        val d = FloatDialog.show(ctx, "更多工具")
            .body(box)
            .width(FloatWindows.widthDp(ctx) + 40f)
            .negative("关闭") { }
        if (!d.show()) {
            // 无悬浮窗权限：回退到应用内弹窗
            val act = ctx as? android.app.Activity ?: return
            Ui.dialog(act, "更多工具").body(box).negative("关闭") { }.show()
        }
    }

    private fun cell(ctx: Context, label: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Theme.rect(Theme.surface2(), 10f, ctx, Theme.line())
            setPadding(Display.dpInt(ctx, 4f), Display.dpInt(ctx, 9f),
                Display.dpInt(ctx, 4f), Display.dpInt(ctx, 9f))
            setOnClickListener { onClick() }
            addView(TextView(ctx).apply {
                text = label.take(2)
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Theme.pri())
                gravity = Gravity.CENTER
            })
            addView(TextView(ctx).apply {
                text = label
                textSize = 9.5f
                setTextColor(Theme.textTer())
                gravity = Gravity.CENTER
                setPadding(0, Display.dpInt(ctx, 3f), 0, 0)
            })
        }
}
