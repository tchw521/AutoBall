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
        JS("JS 表达式", "脚本返回 true 才执行"),
        ;
        companion object {
            fun byName(s: String?): Kind =
                values().firstOrNull { it.name.equals(s?.trim(), true) } ?: NONE
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
    var value: String = ""
    var sim: Int = 90
    /** 颜色容差 0–255 */
    var tol: Int = 10
    /** 检测区域：屏幕百分比 [l,t,r,b]，null 表示整屏 */
    var region: FloatArray? = null
    /** 周围条件（多点找色）：全部满足才算定位成功 */
    val probes: MutableList<Probe> = ArrayList()

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
