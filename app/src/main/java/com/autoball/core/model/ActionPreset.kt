package com.autoball.core.model

/**
 * 动作预设——**全应用唯一的动作定义源**。
 *
 * 早前同样一份预设被定义两遍：
 * - `ActionEditor.OPTIONS`（编辑动作的类型列表）
 * - `ToolPanel.TOOLS`（录制窗「更多工具」快捷面板）
 *
 * 两处各写一份的直接后果是按键码（返回 4 / HOME 3 / 最近任务 187 / 下拉状态栏 1001）
 * 硬编码在两个文件里——改一处漏一处，两边行为就会不一致。
 * 按常驻需求 R-001（同一逻辑出现第三次即收口），这里提取为唯一数据源，
 * 上述两处改为引用本文件的 [ALL] 与 [byLabel]。
 *
 * 每个预设描述：显示名、所属分组、动作类型、选中后套用的参数。
 */
/** 按键码常量——此前散落在 UI 与工具面板两处 */
object KeyCode {
    const val HOME = 3
    const val BACK = 4
    const val RECENTS = 187
    const val EXPAND_STATUS = 1001
}

data class ActionPreset(
    val label: String,
    val group: String,
    val type: ActionType,
    /** 选中后套用的预设参数，省去手工填按键码等 */
    val preset: (Action) -> Unit = {},
    /** 用途说明，列表展示时用；空则只显示名称 */
    val hint: String = ""
) {
    /** 造一个新动作：落在屏幕中心（避免用户还得先选点）+ 套用预设参数 */
    fun newAction(): Action = Action().apply {
        id = Action.newId()
        type = this@ActionPreset.type
        x = 50f
        y = 50f
        optionLabel = this@ActionPreset.label
        preset(this)
    }

    companion object {

        // 分组名集中管理，避免各处硬编码中文串拼写不一致
        const val G_TOUCH = "基础触摸"
        const val G_RECOGNIZE = "识别定位"
        const val G_SYSTEM = "系统操作"
        const val G_ADVANCE = "高级"

        val ALL: List<ActionPreset> = listOf(
            // ---- 基础触摸 ----
            ActionPreset("点击", G_TOUCH, ActionType.CLICK,
                { it.durationMs = 60 }, "在指定位置点一下"),
            // 连击：次数由用户在「重复次数」里自己填，这里只给一个能看出效果的
            // 起始值（次数 × 间隔），不再固定为某个数——用户想点几次就填几次
            ActionPreset("连击", G_TOUCH, ActionType.CLICK, {
                it.durationMs = 60; it.repeat = 2; it.repeatIntervalMs = 200
            }, "连续点多次，次数与间隔都由自己填"),
            ActionPreset("滑动", G_TOUCH, ActionType.SWIPE,
                { it.durationMs = 500; it.x2 = 50f; it.y2 = 20f }, "从一个位置滑到另一个位置"),
            ActionPreset("单指手势", G_TOUCH, ActionType.GESTURE_SINGLE,
                { it.durationMs = 400; it.x2 = 50f; it.y2 = 20f }, "自定义单指轨迹"),
            ActionPreset("多指手势", G_TOUCH, ActionType.GESTURE_MULTI,
                { it.durationMs = 400 }, "双指缩放等，需 Shizuku 或 ROM 支持"),

            // ---- 识别定位 ----
            ActionPreset("点击图片", G_RECOGNIZE, ActionType.CLICK_IMAGE,
                { it.matchThreshold = 0.9f }, "按截图模板找位置再点击"),
            ActionPreset("节点匹配", G_RECOGNIZE, ActionType.CLICK_NODE,
                hint = "按控件节点查找，无障碍通道独有"),
            ActionPreset("点击颜色", G_RECOGNIZE, ActionType.CLICK_COLOR,
                { it.colorTolerance = 10 }, "在区域内找指定颜色并点击"),
            ActionPreset("点击文字", G_RECOGNIZE, ActionType.CLICK_TEXT,
                hint = "按屏幕文字查找并点击"),
            ActionPreset("AI点击", G_RECOGNIZE, ActionType.AI_CLICK,
                hint = "借助视觉模型理解界面"),
            ActionPreset("识别屏幕", G_RECOGNIZE, ActionType.RECOGNIZE_SCREEN,
                hint = "读取当前屏幕内容供后续判断"),

            // ---- 系统操作 ----
            ActionPreset("返回键", G_SYSTEM, ActionType.KEY,
                { it.keyCode = com.autoball.core.model.KeyCode.BACK }, "系统返回"),
            ActionPreset("返回桌面", G_SYSTEM, ActionType.KEY,
                { it.keyCode = com.autoball.core.model.KeyCode.HOME }, "回到主屏幕"),
            ActionPreset("最近任务", G_SYSTEM, ActionType.KEY,
                { it.keyCode = com.autoball.core.model.KeyCode.RECENTS }, "打开最近任务列表"),
            ActionPreset("下拉状态栏", G_SYSTEM, ActionType.KEY,
                { it.keyCode = com.autoball.core.model.KeyCode.EXPAND_STATUS }, "展开通知栏"),
            ActionPreset("屏幕截屏", G_SYSTEM, ActionType.RECOGNIZE_SCREEN,
                hint = "截取当前屏幕"),
            ActionPreset("打开应用", G_SYSTEM, ActionType.OPEN_APP,
                hint = "按包名启动应用"),
            ActionPreset("打开链接", G_SYSTEM, ActionType.OPEN_URL,
                hint = "用浏览器打开指定网址"),
            ActionPreset("输入内容", G_SYSTEM, ActionType.INPUT_TEXT,
                hint = "在当前焦点输入框输入文本"),

            // ---- 高级 ----
            ActionPreset("控制运行", G_ADVANCE, ActionType.CONTROL_FLOW,
                hint = "暂停 / 继续 / 停止 / 等待"),
            ActionPreset("设置变量", G_ADVANCE, ActionType.SET_VAR,
                hint = "写入变量，供后续动作或条件引用"),
            ActionPreset("运行JS代码", G_ADVANCE, ActionType.RUN_JS,
                hint = "执行一段 JS 代码"),
            ActionPreset("运行脚本", G_ADVANCE, ActionType.RUN_SCRIPT,
                hint = "调用另一个脚本"),
            ActionPreset("运行多个动作", G_ADVANCE, ActionType.RUN_ACTIONS,
                hint = "内联执行一组子动作，不单独存为脚本"),
            ActionPreset("系统提示", G_ADVANCE, ActionType.TOAST,
                hint = "弹出一条提示，便于调试")
        )

        /** 分组顺序（仅内部归类用；列表展示已改为平铺，不分组） */
        val GROUPS: List<String> = listOf(G_TOUCH, G_RECOGNIZE, G_SYSTEM, G_ADVANCE)

        /** 按分组取预设 */
        fun ofGroup(group: String): List<ActionPreset> = ALL.filter { it.group == group }

        /**
         * 列表展示用的**完整平铺列表**。
         *
         * 一比一复刻自动精灵：所有动作类型在一个列表里，不分组、不分页。
         * 分组 tab 会让人先猜"我要的在哪一类"，而类型总共就这么多，
         * 平铺 + 两列方框按钮一眼能扫完。
         */
        val FLAT: List<ActionPreset> = ALL

        /**
         * 回显用：优先按 optionLabel 精确匹配，否则退回同类型第一项。
         * 两处 UI 共用，保证"当初选的是哪一项"显示一致。
         */
        fun byLabel(a: Action): ActionPreset =
            ALL.firstOrNull { it.label == a.optionLabel }
                ?: ALL.firstOrNull { it.type == a.type }
                ?: ALL[0]
    }
}
