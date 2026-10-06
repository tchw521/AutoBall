package com.autoball.core.model

/**
 * 能力位：描述一个执行后端"能做什么"。
 *
 * 双后端并行（无障碍 / Shizuku）时，脚本不再绑定某个具体 API，
 * 而是声明它"需要什么能力"，由 [com.autoball.core.backend.BackendRouter] 按能力位路由。
 * 这样即使只有一种授权可用，脚本也能跑——缺失的能力按降级策略显式处理，而不是静默失败。
 */
enum class Cap {
    POINTER_CLICK,      // 坐标点击
    LONG_PRESS,         // 长按
    SINGLE_SWIPE,       // 单指滑动/单指手势
    MULTI_POINTER,      // 真正的多指手势
    TEXT_INPUT,         // 文本输入
    SCREENSHOT,         // 截图 / 取色
    NODE_QUERY,         // 控件节点查找（无障碍独有）
    GLOBAL_BACK,        // 全局动作：返回/主页/最近任务
    SYSTEM_KEY,         // 系统按键
    APP_START,          // 启动应用 / 打开链接
    UI_FEEDBACK,        // Toast 等提示
    HIGH_THROUGHPUT,    // 连续高频注入
    IMAGE_MATCH,        // 图像匹配（按需下载模块）
    OCR,                // 文字识别（按需下载模块）
    AI_VISION           // AI 视觉（按需下载模块）
}

/** 能力集：granted 为稳定可用，experimental 为可用但不保证成功 */
class CapabilitySet(
    val granted: Set<Cap>,
    val experimental: Set<Cap> = emptySet()
) {
    fun has(cap: Cap): Boolean = granted.contains(cap) || experimental.contains(cap)

    fun supports(required: Set<Cap>): Boolean = granted.containsAll(required)

    /** 返回缺失的必需能力，用于向用户解释"为什么这个动作会降级" */
    fun missing(required: Set<Cap>): Set<Cap> = required.filter { !granted.contains(it) }.toSet()

    fun score(required: Set<Cap>, optional: Set<Cap>): Int {
        var s = 0
        for (c in required) {
            if (granted.contains(c)) s += 100
            else if (experimental.contains(c)) s += 40
            else return 0
        }
        for (c in optional) {
            if (granted.contains(c)) s += 10
            else if (experimental.contains(c)) s += 3
        }
        return s
    }

    fun toJson(): org.json.JSONArray {
        val arr = org.json.JSONArray()
        granted.forEach { arr.put(it.name) }
        return arr
    }

    override fun toString(): String = "CapabilitySet(granted=$granted, experimental=$experimental)"
}
