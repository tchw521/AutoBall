package com.autoball.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.autoball.AB

/**
 * 性能降级（UI 设计方案 v3 · perf / lowBlur）。
 *
 * 设计稿给出了两级开关，本类把它落到安卓真机上：
 *
 * 1. **[perf] 流畅模式**：关闭循环动画（导航栏液态呼吸、流光）。
 *    这些动画每帧都要 invalidate，是常驻导航栏的主要掉帧来源。
 * 2. **[lowBlur] 降低毛玻璃**：关闭窗口级背景模糊与导航的渐变折射层，
 *    改用纯半透明。实时模糊在低端 SoC 上开销很高。
 *
 * **自动降级**：内存 ≤4GB 或 CPU 核心 ≤4 的设备首次启动即自动开启两项
 * （与 v3 原型的 `autoDegrade` 一致）。用户可在设置页改回，改过之后
 * 不再自动覆盖——尊重手动选择。
 */
object Perf {

    private const val K_PERF = "perf"
    private const val K_LOWBLUR = "low_blur"
    private const val K_AUTO_DONE = "perf_auto_done"

    @Volatile
    private var perfCache: Boolean? = null
    @Volatile
    private var blurCache: Boolean? = null

    /**
     * 首次读取时做一次低端机检测。
     *
     * 注意：`ActivityManager.MemoryInfo` 的 `totalMem` 是设备标称内存，
     * 各家 ROM 上报略有出入，但用于「≤4GB 就降级」这个粗粒度判断足够。
     */
    fun init(ctx: Context) {
        if (AB.store.getBool(K_AUTO_DONE, false)) return
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memGb = if (am != null) {
            val mi = ActivityManager.MemoryInfo()
            runCatching { am.getMemoryInfo(mi) }
            mi.totalMem / (1024.0 * 1024.0 * 1024.0)
        } else 8.0
        val cores = Runtime.getRuntime().availableProcessors()
        // 用户开启了「减少动效」也一并降级
        val reduceMotion = runCatching {
            android.provider.Settings.Global.getFloat(
                ctx.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)

        if (memGb <= 4.0 || cores <= 4 || reduceMotion) {
            AB.store.putBool(K_PERF, true)
            AB.store.putBool(K_LOWBLUR, true)
            AB.log.info("perf", "检测到低端配置（内存 %.1fGB / %d 核），已自动开启降级"
                .format(memGb, cores))
        }
        AB.store.putBool(K_AUTO_DONE, true)
    }

    /** 流畅模式：关闭循环动画 */
    fun perf(): Boolean {
        val v = perfCache
        if (v != null) return v
        val r = AB.store.getBool(K_PERF, false)
        perfCache = r
        return r
    }

    fun setPerf(v: Boolean) {
        AB.store.putBool(K_PERF, v)
        perfCache = v
    }

    /** 降低毛玻璃 */
    fun lowBlur(): Boolean {
        val v = blurCache
        if (v != null) return v
        val r = AB.store.getBool(K_LOWBLUR, false)
        blurCache = r
        return r
    }

    fun setLowBlur(v: Boolean) {
        AB.store.putBool(K_LOWBLUR, v)
        blurCache = v
    }

    /** 恢复默认（设置页「恢复默认」用）；主题不在本类管辖范围 */
    fun resetDefaults() {
        setPerf(false)
        setLowBlur(false)
        AB.store.putBool(K_AUTO_DONE, true)
    }
}
