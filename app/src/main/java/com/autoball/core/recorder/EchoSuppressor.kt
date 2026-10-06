package com.autoball.core.recorder

/**
 * 回声抑制：防止"补发的手势"被采集层再次当成用户手势，形成自触发死循环。
 *
 * 策略（研究报告 4）：**来源标记为主，时间窗为辅**
 * 1. 每次补发记录 echoId 与预计结束时间；
 * 2. 落在 [echoEnd - 60ms, echoEnd + 60ms] 且接近补发坐标的事件判为回声；
 * 3. 要求最小物理静默 35ms，避免把合法快速连击误杀；
 * 4. 连续 3 次仍收到回声 → 熔断，停止录制并报"疑似自触发"；
 * 5. dispatchGesture 返回 false 时不进入抑制，避免把系统拒绝误判成用户动作。
 */
class EchoSuppressor(
    private val echoBufferMs: Long = 60L,
    private val minQuietMs: Long = 35L,
    private val fuseThreshold: Int = 3
) {

    @Volatile
    private var echoEndAt = 0L

    @Volatile
    private var echoStartAt = 0L

    @Volatile
    private var echoX = -1f

    @Volatile
    private var echoY = -1f

    @Volatile
    private var echoCount = 0

    @Volatile
    private var lastAcceptedAt = 0L

    @Volatile
    private var echoId = 0L

    /** 补发前调用：登记本次回声的时间窗与坐标 */
    fun markDispatch(x: Float, y: Float, now: Long, durationMs: Long): Long {
        echoId++
        echoStartAt = now
        echoEndAt = now + durationMs
        echoX = x
        echoY = y
        return echoId
    }

    /** 补发失败：清除登记，后续事件不再被当作回声 */
    fun clearDispatch() {
        echoEndAt = 0L
        echoStartAt = 0L
    }

    /**
     * 判定是否为回声。
     * @return true 表示应忽略
     */
    fun isEcho(x: Float, y: Float, now: Long): Boolean {
        if (echoEndAt == 0L) return false
        val inWindow = now >= echoStartAt - minQuietMs && now <= echoEndAt + echoBufferMs
        if (!inWindow) return false
        // 坐标接近（补发同点或同路径终点）
        val near = kotlin.math.abs(x - echoX) < 120f && kotlin.math.abs(y - echoY) < 120f
        if (!near) return false
        // 最小物理静默：太接近上一次已接受事件，视为抖动而非回声
        if (now - lastAcceptedAt < minQuietMs) return false
        echoCount++
        return true
    }

    /** 接受了一次合法手势 */
    fun accept(now: Long) {
        lastAcceptedAt = now
        echoCount = 0
    }

    /** 连续回声达到阈值 → 熔断 */
    fun shouldFuse(): Boolean = echoCount >= fuseThreshold

    fun reset() {
        echoEndAt = 0L
        echoStartAt = 0L
        echoX = -1f
        echoY = -1f
        echoCount = 0
        lastAcceptedAt = 0L
    }
}
