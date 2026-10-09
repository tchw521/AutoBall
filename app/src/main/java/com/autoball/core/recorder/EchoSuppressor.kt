package com.autoball.core.recorder

/**
 * 回声抑制：防止"补发的手势"被采集层再次当成用户手势，形成自触发死循环。
 *
 * 策略（研究报告 4）：**来源标记为主，时间窗为辅**
 * 1. 每次补发记录 echoId 与预计结束时间；
 * 2. 落在补发窗口内的事件**一律**判为回声（不论坐标，理由见 isEcho）；
 * 3. 连续 3 次仍收到回声 → 熔断，停止录制并报"疑似自触发"；
 * 5. dispatchGesture 返回 false 时不进入抑制，避免把系统拒绝误判成用户动作。
 */
class EchoSuppressor(
    private val echoBufferMs: Long = 60L,
    private val fuseThreshold: Int = 3
) {

    @Volatile
    private var echoEndAt = 0L

    @Volatile
    private var echoStartAt = 0L

    @Volatile
    private var echoCount = 0

    @Volatile
    private var echoId = 0L

    /** 补发前调用：登记本次回声的时间窗与坐标 */
    fun markDispatch(x: Float, y: Float, now: Long, durationMs: Long): Long {
        echoId++
        echoStartAt = now
        echoEndAt = now + durationMs
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
        if (echoStartAt == 0L) return false
        // 补发窗口内一律判为回声，**不看坐标**。
        //
        // 早前加了"坐标需接近补发终点"的判据，两条理由都不成立：
        // 1) 滑动补发的 DOWN 落在起点、UP 落在终点，按终点过滤会把起点那条
        //    判成合法手势 → 一次滑动录成两条动作；
        // 2) 更糟的是下面那条"最小物理静默"——补发恰好紧接在真实手势之后
        //    （accept 与 markDispatch 几乎同时发生），
        //    `now - lastAcceptedAt < 35ms` 恒成立，于是**点击的补发被当成
        //    新手势记录**：点一下录成两条。这是"录制翻倍"的直接来源。
        //
        // 补发是我们自己发起的，窗口内不存在用户真实手势，无需坐标过滤。
        val inWindow = now >= echoStartAt - 40L && now <= echoEndAt + echoBufferMs + 120L
        if (!inWindow) return false
        echoCount++
        return true
    }

    /** 接受了一次合法手势：清掉连续回声计数（熔断只在"连续"时才触发） */
    fun accept(now: Long) {
        echoCount = 0
    }

    /** 连续回声达到阈值 → 熔断 */
    fun shouldFuse(): Boolean = echoCount >= fuseThreshold

    fun reset() {
        echoEndAt = 0L
        echoStartAt = 0L
        echoCount = 0
    }
}
