package com.autoball.core.model

/**
 * 动作表单各字段的**帮助文案**，按动作类型动态返回（R-001 统一组件）。
 *
 * 触发三次法则：此前这些说明散在 `ActionEditor.buildForm` 的十余处 `help = "…"` 里，
 * 且大多是**一段通用话术套所有类型**——选「滑动」时位置字段仍写着
 * "点击位置…"，与实际含义不符。自动精灵里每个字段的说明都随类型变化
 * （例如滑动的位置提示是"滑动动作能在屏幕上的两个位置间滑动"）。
 *
 * 现在全部收口到本文件：新增动作类型时改一处即可，不会出现
 * "换了类型说明没跟着换"的静默错误。
 *
 * 两条写作原则：
 * 1. **说清这个类型下该字段的实际含义**，不要写成放之四海皆准的废话。
 * 2. 能力不足的类型（如 AI点击）如实说明，不做假装支持的描述。
 */
object ActionHelp {

    /** 动作类型行：说明当前这一类的整体用途 */
    fun type(a: Action): String {
        val opt = ActionPreset.byLabel(a)
        val base = when (a.type) {
            ActionType.CLICK -> "在指定位置点一下。\n长按可把「按下时间」加长到 500～800 毫秒。"
            ActionType.SWIPE -> "在屏幕上的两个位置之间滑动，常用于翻页与切换。"
            ActionType.CLICK_IMAGE -> "先在当前屏幕找模板图，找到后点它的中心。\n找不到则本动作失败。"
            ActionType.CLICK_TEXT -> "先在当前屏幕找文字，找到后点它所在位置。\n依赖无障碍节点树或 OCR。"
            ActionType.CLICK_COLOR -> "先在指定区域找目标颜色，找到后点该位置。\n适合纯色按钮。"
            ActionType.CLICK_NODE -> "按控件属性（文字/ID/描述/类名）找控件，找到后点它。\n比找色找图更稳，需无障碍授权。"
            ActionType.AI_CLICK -> "需云端视觉能力。本应用不联网，**该动作当前不可用**。"
            ActionType.GESTURE_SINGLE -> "单指复杂轨迹（多点连续），可录制或手填路径。"
            ActionType.GESTURE_MULTI -> "多指手势（如双指缩放）。需 Shizuku 或系统支持多指。"
            ActionType.KEY -> "模拟系统按键：返回、HOME、最近任务、下拉状态栏等。"
            ActionType.OPEN_APP -> "启动指定应用；已在前台则直接切到它。"
            ActionType.OPEN_URL -> "用浏览器打开一个链接。"
            ActionType.INPUT_TEXT -> "向当前获得焦点的输入框输入文字。"
            ActionType.RECOGNIZE_SCREEN -> "截屏并识别屏幕内容，结果写入变量供后续判断。"
            ActionType.RUN_SCRIPT -> "调用本机另一个脚本，结束后回到本动作继续。"
            ActionType.RUN_ACTIONS -> "内联执行一组动作，共用同一个执行上下文。"
            ActionType.CONTROL_FLOW -> "控制脚本运行：暂停 / 继续 / 停止 / 跳转 / 等待。"
            ActionType.TOAST -> "弹出一条提示，仅用于调试与观察，不影响目标应用。"
            ActionType.SET_VAR -> "设置一个变量，后续动作与运行条件都能读取。"
            ActionType.RUN_JS -> "执行一段 JS 代码，可调用全部宿主 API。"
        }
        return base + "\n\n当前预设：${opt.label}（${opt.group}）"
    }

    /** 主坐标字段 */
    fun point(a: Action): String = when (a.type) {
        ActionType.SWIPE, ActionType.GESTURE_SINGLE, ActionType.GESTURE_MULTI ->
            "滑动动作能在屏幕上的两个位置间滑动。\n" +
            "这里填**起点**；点右侧「⋯」可在全屏框选起点与终点。"
        ActionType.CLICK_IMAGE ->
            "找到图片后**点击图片中心**；这里填的是找不到时的备用位置。\n" +
            "百分比坐标，换机型与转屏都不会点偏。"
        ActionType.CLICK_TEXT, ActionType.CLICK_NODE ->
            "找到目标后点击它的位置；这里填的是找不到时的备用位置。\n" +
            "百分比坐标，换机型与转屏都不会点偏。"
        ActionType.CLICK_COLOR ->
            "找到目标颜色后点击该位置；这里填的是找不到时的备用位置。\n" +
            "百分比坐标，换机型与转屏都不会点偏。"
        ActionType.AI_CLICK ->
            "AI点击需要云端视觉能力，本应用不联网，该坐标不会生效。"
        else ->
            "百分比坐标，换机型与转屏都不会点偏。\n" +
            "点右侧「⋯」在全屏选点：按住拖动可微调，底部显示实时坐标。"
    }

    /** 终点坐标字段 */
    fun pointEnd(a: Action): String = when (a.type) {
        ActionType.GESTURE_MULTI ->
            "多指手势的结束位置；各指按各自起点到终点的路径运动。"
        else ->
            "滑动的**终点**。点右侧「⋯」可一次框选起点与终点两个坐标。\n" +
            "起点在上、终点在下即为上滑，反过来则是下滑。"
    }

    /** 区域字段（区域随机点击） */
    fun area(a: Action): String =
        "框选一块区域，每次运行都在区域内**随机取一点**点击。\n" +
        "与「坐标随机微调」不同：那个是围绕固定点抖动，这个是整块区域任意落点。"

    /** 时长字段（按下时间 / 滑动时长） */
    fun duration(a: Action): String = when (a.type) {
        ActionType.SWIPE, ActionType.GESTURE_SINGLE, ActionType.GESTURE_MULTI ->
            "从起点滑到终点所用的时间。\n" +
            "太快（<150ms）常被识别成点击，建议 300～600 毫秒。"
        ActionType.CLICK ->
            "手指按下的持续时间。\n" +
            "填到 500～800 毫秒即为长按；留空则用脚本全局设置的默认时长。"
        else ->
            "该动作的持续时间。\n留空则用脚本全局设置的默认时长。"
    }

    /** 运行等待 */
    fun wait(a: Action): String = when (a.type) {
        ActionType.RECOGNIZE_SCREEN, ActionType.CLICK_IMAGE,
        ActionType.CLICK_TEXT, ActionType.CLICK_NODE, ActionType.CLICK_COLOR ->
            "本动作执行完后再等待多久才继续。\n" +
            "识别类动作建议留 300～800 毫秒，让界面渲染完再进入下一步。"
        ActionType.OPEN_APP, ActionType.OPEN_URL ->
            "打开应用/链接后等待多久再继续。\n" +
            "冷启动较慢，建议 1000～3000 毫秒。"
        else ->
            "该动作执行完后再等待多久才继续下一个。\n" +
            "单位可在右侧切换（毫秒 / 秒 / 分钟）。留空表示不额外等待。"
    }

    /** 重复次数 */
    fun repeat(a: Action): String = when (a.type) {
        ActionType.CLICK -> "该动作重复执行几次，**想点几次就填几次，不设上限**。\n" +
            "留空或填 0 按 1 次。连击就是"次数 × 间隔"：如 10 次 × 200 毫秒。\n" +
            "次数越多总耗时越长，脚本停止按钮随时可中断。"
        ActionType.SWIPE -> "重复滑动几次。\n连续翻页常填 3～10，配合间隔使用。"
        ActionType.KEY -> "重复按几次键。\n连续返回常用 2～3 次。"
        else -> "该动作重复执行几次。留空按 1 次。"
    }

    /** 重复间隔 */
    fun interval(a: Action): String =
        "每次重复之间的间隔。\n留空则不等待——连击时建议填 100～500 毫秒，间隔太小会被系统判为误触。"

    /** 文本字段：按类型语义差别很大 */
    fun text(a: Action): String = when (a.type) {
        ActionType.CLICK_TEXT -> "要查找并点击的文字，支持包含匹配。\n依赖无障碍节点树或 OCR 能力。"
        ActionType.INPUT_TEXT -> "要输入的内容。\n留空则运行时弹窗提示你输入。"
        ActionType.TOAST -> "提示文字内容，仅显示在屏幕上，不影响目标应用。"
        ActionType.SET_VAR -> "变量的值。\n可用 \${变量名} 引用其它变量。"
        ActionType.RECOGNIZE_SCREEN -> "可选：限定识别范围的描述。留空则识别整屏。"
        else -> "留空则运行时提示输入。"
    }

    /** 目标应用包名 */
    fun pkg(a: Action): String =
        "包名，如 com.tencent.mm。\n点右侧「⋯」可从已安装应用里选；留空则打开当前应用。"

    /** 链接 */
    fun url(a: Action): String =
        "以 http:// 或 https:// 开头，用浏览器打开。\n" +
        "与应用内跳转不同：这是交给系统浏览器处理的。"

    /** 按键码 */
    fun key(a: Action): String =
        "常用：${android.view.KeyEvent.KEYCODE_HOME}=HOME  " +
        "${android.view.KeyEvent.KEYCODE_BACK}=返回  " +
        "${android.view.KeyEvent.KEYCODE_APP_SWITCH}=最近任务\n" +
        "选预设动作时会自动填好，一般无需手工输入。"

    /** JS 代码 */
    fun code(a: Action): String =
        "可调用 ab.click / ab.swipe / ab.key / ab.wait / ab.findNode 等宿主 API。\n" +
        "QuickJS 支持 await；Rhino 变体不支持 async/await。"

    /** 目标脚本 */
    fun scriptRef(a: Action): String =
        "选择要调用的本机脚本。\n被调用脚本结束后回到本动作继续执行。"

    /** 子动作 */
    fun subActions(a: Action): String =
        "内联执行的一组动作，共用同一个执行上下文。\n" +
        "适合把「点+等+点」打包成一个可复用的步骤。"

    /** 控制方式 */
    fun control(a: Action): String =
        "暂停：脚本停在这里，可手动继续。\n" +
        "继续/停止：配合暂停使用。\n" +
        "跳转：跳到指定步骤；等待：纯延时。"

    /** 变量名 */
    fun varName(a: Action): String = when (a.type) {
        ActionType.RECOGNIZE_SCREEN ->
            "识别结果写入该变量，后续可用运行条件判断。\n" +
            "同时写入 \${变量名}_w / _h 两个尺寸变量。"
        else ->
            "变量名。后续动作可用 \${变量名} 引用，运行条件里也能判断。"
    }
}
