package com.autoball.core.model

/**
 * 20 类动作类型（需求 2.3）。
 *
 * required：该动作必须拥有的能力，缺失则无法执行（触发降级或中止）。
 * optional：有则更好，缺失不影响基本语义。
 */
enum class ActionType(
    val label: String,
    val required: Set<Cap>,
    val optional: Set<Cap> = emptySet()
) {
    CLICK("点击", setOf(Cap.POINTER_CLICK)),
    SWIPE("滑动", setOf(Cap.SINGLE_SWIPE)),
    CLICK_IMAGE("点击图片", setOf(Cap.POINTER_CLICK, Cap.SCREENSHOT), setOf(Cap.IMAGE_MATCH)),
    CLICK_TEXT("点击文字", setOf(Cap.POINTER_CLICK), setOf(Cap.NODE_QUERY, Cap.OCR)),
    CLICK_COLOR("点击颜色", setOf(Cap.POINTER_CLICK, Cap.SCREENSHOT)),
    CLICK_NODE("点击节点", setOf(Cap.NODE_QUERY, Cap.POINTER_CLICK)),
    AI_CLICK("AI点击", setOf(Cap.POINTER_CLICK, Cap.SCREENSHOT), setOf(Cap.AI_VISION)),
    GESTURE_SINGLE("单指手势", setOf(Cap.SINGLE_SWIPE)),
    GESTURE_MULTI("多指手势", setOf(Cap.MULTI_POINTER)),
    KEY("按键", setOf(Cap.SYSTEM_KEY)),
    OPEN_APP("打开应用", setOf(Cap.APP_START)),
    OPEN_URL("打开链接", setOf(Cap.APP_START)),
    INPUT_TEXT("输入内容", setOf(Cap.TEXT_INPUT), setOf(Cap.NODE_QUERY)),
    RECOGNIZE_SCREEN("识别屏幕", setOf(Cap.SCREENSHOT), setOf(Cap.OCR, Cap.NODE_QUERY)),
    RUN_SCRIPT("运行脚本", emptySet()),
    RUN_ACTIONS("运行多个动作", emptySet()),
    CONTROL_FLOW("控制运行", emptySet()),
    TOAST("系统提示", setOf(Cap.UI_FEEDBACK)),
    SET_VAR("设置变量", emptySet()),
    RUN_JS("运行JS代码", emptySet());

    /**
     * 是否带屏幕坐标。
     *
     * 只有带坐标的动作才需要做矩阵变形——对「等待」「按键」这类动作
     * 做抖动没有意义，反而会打乱时序。
     */
    val hasCoord: Boolean
        get() = fieldGroups.contains(FieldGroup.POINT) ||
                fieldGroups.contains(FieldGroup.AREA)

    /** 该类型表单需要展示哪些字段组（驱动 UI 动态表单） */
    val fieldGroups: Set<FieldGroup>
        get() = when (this) {
            CLICK, CLICK_TEXT, AI_CLICK ->
                setOf(FieldGroup.POINT, FieldGroup.PRESS_DURATION, FieldGroup.TIMING)
            // 节点匹配**必须**有选择器入口：此前 CLICK_NODE 的字段组与 CLICK
            // 完全相同，nodeSpec 在 UI 上无从配置，运行时只能用空选择器
            CLICK_NODE ->
                setOf(FieldGroup.NODE_SPEC, FieldGroup.POINT, FieldGroup.PRESS_DURATION,
                    FieldGroup.TIMING)
            // 点击图片**必须**有模板图入口：没有它用户选不了图，
            // imageRef 永远为空，运行时必然找不到目标（R-150）
            CLICK_IMAGE ->
                setOf(FieldGroup.TEMPLATE, FieldGroup.POINT, FieldGroup.PRESS_DURATION,
                    FieldGroup.TIMING)
            // 点击颜色：颜色 + 容差，此前 UI 完全配不了容差，
            // 只能吃预设里的默认值（10），偏色一点就匹配不上
            CLICK_COLOR ->
                setOf(FieldGroup.COLOR, FieldGroup.POINT, FieldGroup.PRESS_DURATION,
                    FieldGroup.TIMING)
            SWIPE, GESTURE_SINGLE, GESTURE_MULTI ->
                setOf(FieldGroup.POINT, FieldGroup.POINT_END, FieldGroup.DURATION,
                    FieldGroup.TIMING)
            INPUT_TEXT -> setOf(FieldGroup.TEXT)
            OPEN_APP -> setOf(FieldGroup.PACKAGE)
            OPEN_URL -> setOf(FieldGroup.URL)
            KEY -> setOf(FieldGroup.KEYCODE)
            RUN_SCRIPT -> setOf(FieldGroup.SCRIPT_REF)
            RUN_JS -> setOf(FieldGroup.CODE)
            RUN_ACTIONS -> setOf(FieldGroup.SUB_ACTIONS)
            CONTROL_FLOW -> setOf(FieldGroup.CONTROL)
            SET_VAR -> setOf(FieldGroup.VAR_NAME, FieldGroup.TEXT)
            RECOGNIZE_SCREEN -> setOf(FieldGroup.TEXT)
            TOAST -> setOf(FieldGroup.TEXT)
        }

    companion object {
        fun fromName(name: String?): ActionType? =
            if (name == null) null else values().firstOrNull { it.name == name }

        fun labels(): Array<String> = values().map { it.label }.toTypedArray()
    }
}

enum class FieldGroup {
    POINT, POINT_END, PRESS_DURATION, DURATION, TEXT, PACKAGE, URL,
    KEYCODE, SCRIPT_REF, CODE, SUB_ACTIONS, CONTROL, VAR_NAME,
    /** 区域（左上 x,y + 右下 x2,y2，百分比）——区域随机点击用 */
    AREA,
    /** 模板图：选一张截图模板（写 imageRef）+ 相似度 */
    TEMPLATE,
    /** 目标颜色（写 colorHex）+ 容差（写 colorTolerance） */
    COLOR,
    /** 节点选择器（写 nodeSpec）：文字 / ID / 描述 / 类名 / 仅可点击 */
    NODE_SPEC,
    /** 时延：执行前等待（preDelayMs）与手势派发超时（timeoutMs） */
    TIMING
}

/** 控制运行动作的子类型 */
enum class ControlOp(val label: String) {
    PAUSE("暂停"), RESUME("继续"), STOP("停止"), GOTO("跳转"), WAIT("等待")
}

/** 滑动/手势的插值方式（导出与压缩用） */
enum class Interpolation { LINEAR, EASE_IN_OUT, NONE }
