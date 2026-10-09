package com.autoball.core.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 运行条件的**唯一数据模型**（R-001：消除三处重复的 JSON 解析）。
 *
 * 此前 `ConditionDialog`（UI）、`ConditionEval`（求值）、`ShareCode`（内联模板图）
 * 各自手写一遍 JSON 解析，且**字段名不一致**——UI 写 `e`，求值读 `v`，
 * 结果所有识别类条件永远读到空值 → 返回 UNKNOWN → 条件形同虚设。
 * 这类"界面显示已设置、实际从不生效"的静默失效最难发现。
 *
 * 现在三处共用本类，字段名只在这里定义一次。
 */
class ActionCondition {

    enum class Kind(val label: String, val desc: String) {
        NONE("不检测", "无条件，直接执行本动作"),
        IMAGE("图片存在", "截屏后在指定区域内找图，相似度达标才执行"),
        TEXT("文字存在", "在节点树或 OCR 结果里能找到指定文字才执行"),
        COLOR("颜色存在", "指定点或区域内出现目标颜色才执行"),
        NODE("节点存在", "无障碍控件树里能找到指定控件才执行（比找色/找图更稳）"),
        AI("AI云识别", "需云端视觉能力；本应用不联网，选中后按「无法判定」跳过该动作"),
        VAR("变量判断", "指定变量满足比较条件才执行（可用 \$ok / \$last / \$stepN）"),
        JS("JS 表达式", "脚本返回 true 才执行"),
        ;
        companion object {
            fun byName(s: String?): Kind =
                values().firstOrNull { it.name.equals(s?.trim(), true) } ?: NONE
        }
    }

    /**
     * 变量比较符（R-116）。
     *
     * 自动精灵的条件菜单里有「变量」一类，我们能读变量（`$ok` / `$last` /
     * `$stepN` 等运行时变量已注入）却没做成条件类型，只能绕道写 JS 表达式。
     * 补上后普通用户不必碰 JS。
     */
    enum class Cmp(val label: String, val symbol: String) {
        EXISTS("已定义", "存在"),
        EQ("等于", "=="),
        NE("不等于", "!="),
        GT("大于", ">"),
        GE("大于等于", ">="),
        LT("小于", "<"),
        LE("小于等于", "<="),
        CONTAINS("包含", "包含"),
        ;
        companion object {
            fun byName(s: String?): Cmp =
                values().firstOrNull { it.name.equals(s?.trim(), true) } ?: EXISTS
        }
    }

    /** 位置周围条件的一个探针：主坐标偏移 (dx,dy) 处的颜色需匹配 */
    class Probe(
        /** 相对主坐标的偏移，单位 dp（会按屏幕换算，换机型保持一致） */
        var dx: Float = 0f,
        var dy: Float = 0f,
        var color: String = "",
        var tol: Int = 10
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("dx", dx.toDouble()); put("dy", dy.toDouble())
            put("c", color); put("tol", tol)
        }

        companion object {
            fun fromJson(o: JSONObject): Probe = Probe(
                dx = o.optDouble("dx", 0.0).toFloat(),
                dy = o.optDouble("dy", 0.0).toFloat(),
                color = o.optString("c", ""),
                tol = o.optInt("tol", 10))
        }
    }

    var kind: Kind = Kind.NONE
    /**
     * 主值。按 kind 语义不同：
     * - IMAGE：模板图 id；TEXT：要找的文字；COLOR：#RRGGBB
     * - VAR：**变量名**（可带 `$` 前缀，如 `ok` / `$ok`）
     * - NODE：不用（用 [nodeSpec]）；JS：表达式
     */
    var value: String = ""
    var sim: Int = 90
    /** 颜色容差 0–255 */
    var tol: Int = 10
    /** 检测区域：屏幕百分比 [l,t,r,b]，null 表示整屏 */
    var region: FloatArray? = null
    /** 周围条件（多点找色）：全部满足才算定位成功 */
    val probes: MutableList<Probe> = ArrayList()

    /** 节点选择器（kind=NODE）。复用 [NodeSpec]，不另造一套字段 */
    var nodeSpec: NodeSpec? = null

    // =================================================================
    // 以下为自动精灵「运行条件」弹窗里的高级项（R-135）
    // =================================================================

    /**
     * 匹配第几（选填，1 起）。
     *
     * 语义：区域内至少存在 N 个**互不重叠**的匹配才算成立。
     * 之所以不实现成"取第 N 个的坐标"：条件只判断存在性，
     * 而"至少 N 个"才是"第 N 个存在"的准确含义。
     */
    var matchIndex: Int = 0

    /**
     * 快速搜图：抽稀步长翻倍，速度约 4 倍、精度略降。
     * 大区域找小图时用得上，代价是可能漏检。
     */
    var fast: Boolean = false

    /** 搜图模式。当前内核只有一种实现，其余选项选中后会明确降级说明 */
    var searchMode: SearchMode = SearchMode.DEFAULT

    /** 多分辨率适配：决定模板图如何投影到当前屏幕（与 R-132 的 ratio 联动） */
    var multiRes: MultiRes = MultiRes.BOTH

    /** 滤镜：需图像处理模块，当前未内置——选中即明确告知，不假装支持 */
    var filter: String = ""

    /** 条件反相：成立变不成立。无法判定（UNKNOWN）**不**反相，仍按不满足处理 */
    var invert: Boolean = false

    /** 等待前检查：在动作等待之前先判定条件，而不是等完再判 */
    var checkBefore: Boolean = false

    /** 重复检查直到成功：不成立时按间隔重试，直到成立或用尽上限 */
    var retry: Boolean = false
    /** 重复上限；0 表示不限（只受脚本停止控制） */
    var retryMax: Int = 0
    /** 重复间隔（毫秒） */
    var retryIntervalMs: Long = 1000L

    /** 条件描述：只给作者自己看的备注 */
    var desc: String = ""

    enum class SearchMode(val label: String, val desc: String) {
        DEFAULT("默认", "标准直方图归一化匹配，通用性最好"),
        CONTOUR("轮廓", "按边缘轮廓匹配；当前内核未实现，按「默认」执行"),
        DEFAULT_OLD("默认(旧)", "早期版本算法；当前内核未实现，按「默认」执行"),
        HOG_OLD("HOG(旧)", "梯度直方图；当前内核未实现，按「默认」执行"),
        ;
        companion object {
            fun byName(s: String?): SearchMode =
                values().firstOrNull { it.name.equals(s?.trim(), true) } ?: DEFAULT
        }
    }

    /**
     * 多分辨率适配策略。
     *
     * 模板图是**像素尺寸**，跨设备必须缩放才能匹配（R-132）。
     * 不同界面缩放规律不同（有的按宽度等比、有的按高度、有的整体拉伸），
     * 所以给用户选择权，而不是替他定死一种。
     */
    enum class MultiRes(val label: String, val desc: String) {
        DENSITY("基于像素密度缩放", "按屏幕密度比例缩放模板；适合图标、按钮等随密度变化的界面"),
        WIDTH("基于屏幕宽缩放", "只按屏幕宽度等比缩放，保持模板宽高比"),
        HEIGHT("基于屏幕高缩放", "只按屏幕高度等比缩放，保持模板宽高比"),
        BOTH("基于屏幕宽和高缩放", "宽高分别按屏幕比例缩放；横竖屏不同比例时可能变形"),
        TRY_ALL("尝试以上全部", "依次尝试各策略取最佳；最稳但最慢"),
        OFF("关闭", "不做任何缩放，按模板原图匹配（仅同机型可用）"),
        ;
        companion object {
            fun byName(s: String?): MultiRes =
                values().firstOrNull { it.name.equals(s?.trim(), true) } ?: BOTH
        }
    }
    /** 变量比较符（kind=VAR） */
    var cmp: Cmp = Cmp.EXISTS
    /** 变量比较的右值（kind=VAR；EXISTS 时不用） */
    var cmpValue: String = ""

    fun toJson(): JSONObject = JSONObject().apply {
        put("k", kind.name)
        put("v", value)
        put("sim", sim)
        put("tol", tol)
        region?.let { r ->
            put("r", JSONArray().apply { r.forEach { put(it.toDouble()) } })
        }
        if (probes.isNotEmpty()) {
            put("p", JSONArray().apply { probes.forEach { put(it.toJson()) } })
        }
        nodeSpec?.let { put("ns", it.toJson()) }
        if (kind == Kind.VAR) {
            put("cmp", cmp.name)
            put("cv", cmpValue)
        }
        if (matchIndex > 0) put("mi", matchIndex)
        if (fast) put("fast", true)
        if (searchMode != SearchMode.DEFAULT) put("sm", searchMode.name)
        if (multiRes != MultiRes.BOTH) put("mr", multiRes.name)
        if (filter.isNotEmpty()) put("fl", filter)
        if (invert) put("inv", true)
        if (checkBefore) put("cbw", true)
        if (retry) {
            put("rt", true)
            put("rtm", retryMax)
            put("rti", retryIntervalMs)
        }
        if (desc.isNotEmpty()) put("ds", desc)
    }

    companion object {

        /**
         * 解析单条条件。
         *
         * 兼容历史格式：旧版用 `e` 存表达式（而求值读 `v`），
         * 这里两个字段都读，保证老脚本不失效。
         */
        fun fromJson(o: JSONObject): ActionCondition = ActionCondition().apply {
            kind = Kind.byName(o.optString("k", "NONE"))
            value = o.optString("v", "").ifEmpty { o.optString("e", "") }
            sim = o.optInt("sim", 90)
            tol = o.optInt("tol", 10)
            region = o.optJSONArray("r")?.let { a ->
                if (a.length() == 4) floatArrayOf(
                    a.optDouble(0, 0.0).toFloat(), a.optDouble(1, 0.0).toFloat(),
                    a.optDouble(2, 0.0).toFloat(), a.optDouble(3, 0.0).toFloat()
                ) else null
            }
            o.optJSONArray("p")?.let { a ->
                for (i in 0 until a.length()) {
                    runCatching { Probe.fromJson(a.getJSONObject(i)) }
                        .getOrNull()?.let { probes.add(it) }
                }
            }
            nodeSpec = o.optJSONObject("ns")?.let { runCatching { NodeSpec.fromJson(it) }.getOrNull() }
            cmp = Cmp.byName(o.optString("cmp"))
            cmpValue = o.optString("cv", "")
            matchIndex = o.optInt("mi", 0)
            fast = o.optBoolean("fast", false)
            searchMode = SearchMode.byName(o.optString("sm"))
            multiRes = MultiRes.byName(o.optString("mr", "BOTH"))
            filter = o.optString("fl", "")
            invert = o.optBoolean("inv", false)
            checkBefore = o.optBoolean("cbw", false)
            retry = o.optBoolean("rt", false)
            retryMax = o.optInt("rtm", 0)
            retryIntervalMs = o.optLong("rti", 1000L)
            desc = o.optString("ds", "")
        }
    }
}

/**
 * 条件集合：支持多条 + AND/OR（自动精灵「多条件全部满足」的扩展）。
 *
 * 存储格式 `{"op":"AND","items":[...]}`。
 * **兼容**：没有 `items` 字段的旧单条 JSON 会被包成只含一条的集合，
 * 老脚本升级后行为不变。纯文本（非 JSON）条件也保留原样透传。
 */
class ConditionSet {

    enum class Op(val label: String, val desc: String) {
        AND("全部满足", "每一条都成立才执行；最严格"),
        OR("任一满足", "只要有一条成立就执行；最宽松"),
        ;
        companion object {
            fun byName(s: String?): Op =
                values().firstOrNull { it.name.equals(s?.trim(), true) } ?: AND
        }
    }

    var op: Op = Op.AND
    val items: MutableList<ActionCondition> = ArrayList()

    fun isEmpty(): Boolean = items.isEmpty() ||
        items.all { it.kind == ActionCondition.Kind.NONE }

    fun toJson(): JSONObject = JSONObject().apply {
        put("op", op.name)
        put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
    }

    companion object {

        fun parse(raw: String?): ConditionSet {
            val set = ConditionSet()
            if (raw.isNullOrBlank()) return set
            val t = raw.trim()
            if (!t.startsWith("{")) {
                // 纯文本：旧式 JS 表达式
                set.items.add(ActionCondition().apply {
                    kind = ActionCondition.Kind.JS
                    value = t
                })
                return set
            }
            val o = runCatching { JSONObject(t) }.getOrNull() ?: return set
            val arr = o.optJSONArray("items")
            if (arr != null) {
                set.op = Op.byName(o.optString("op", "AND"))
                for (i in 0 until arr.length()) {
                    runCatching { ActionCondition.fromJson(arr.getJSONObject(i)) }
                        .getOrNull()?.let { set.items.add(it) }
                }
            } else {
                // 旧单条格式：包成一条，保持行为不变
                set.items.add(ActionCondition.fromJson(o))
            }
            return set
        }

        fun serialize(set: ConditionSet): String? {
            val real = set.items.filter { it.kind != ActionCondition.Kind.NONE }
            if (real.isEmpty()) return null
            return ConditionSet().apply {
                op = set.op
                items.addAll(real)
            }.toJson().toString()
        }
    }
}

/** 单个动作执行失败后的处理策略 */
enum class FailOp(val label: String, val desc: String) {
    NEXT("继续下一步", "忽略失败，接着执行后面的动作"),
    PAUSE("暂停脚本", "立即暂停，可手动继续"),
    STOP("终止脚本", "整个脚本停在这里，不再执行"),
    JUMP("跳转到步骤", "跳到指定步骤继续；找不到目标就终止"),
    ;
    companion object {
        fun byName(s: String?): FailOp =
            values().firstOrNull { it.name.equals(s?.trim(), true) } ?: NEXT
    }
}
