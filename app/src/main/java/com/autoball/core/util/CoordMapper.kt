package com.autoball.core.util

import com.autoball.core.model.Action
import com.autoball.core.model.DisplaySignature

/**
 * 坐标归一化映射（原计划 v0.8，提前到 v0.4）。
 *
 * 背景：录制时保存的是**绝对像素**，换机型或转屏后点位会偏。
 * 策略：动作流携带录制时的屏幕签名，运行前按当前屏幕缩放；
 * 转屏导致的宽高互换不做静默猜测，直接判定为不匹配并交上层提示重映射。
 */
object CoordMapper {

    class Scale(
        val sx: Float,
        val sy: Float,
        val active: Boolean,
        val reason: String? = null
    ) {
        companion object {
            val NONE = Scale(1f, 1f, false)
        }
    }

    /**
     * 计算缩放系数。
     * @return active=false 表示无需缩放或无法安全缩放
     */
    fun compute(sig: DisplaySignature?, curW: Int, curH: Int, curRot: Int): Scale {
        if (sig == null || sig.width <= 0 || sig.height <= 0) return Scale.NONE
        if (curW <= 0 || curH <= 0) return Scale.NONE
        if (sig.width == curW && sig.height == curH && sig.rotation == curRot) return Scale.NONE

        // 转屏：宽高互换且旋转不同 → 不做静默映射
        val rotated = (sig.width == curH && sig.height == curW)
        if (rotated && sig.rotation != curRot) {
            return Scale(1f, 1f, false, "录制时与当前的屏幕方向不同，请重新校准坐标")
        }
        return Scale(curW.toFloat() / sig.width, curH.toFloat() / sig.height, true)
    }

    /** 返回缩放后的动作副本，不污染原始脚本 */
    fun applyTo(a: Action, s: Scale): Action {
        if (!s.active) return a
        val b = Action.fromJson(a.toJson())
        b.x = a.x * s.sx
        b.y = a.y * s.sy
        b.x2 = a.x2 * s.sx
        b.y2 = a.y2 * s.sy
        if (b.path.isNotEmpty()) {
            val np = ArrayList<com.autoball.core.model.Pt>()
            for (p in b.path) np.add(com.autoball.core.model.Pt(p.x * s.sx, p.y * s.sy))
            b.path = np
        }
        if (b.strokes.isNotEmpty()) {
            val ns = ArrayList<MutableList<com.autoball.core.model.Pt>>()
            for (st in b.strokes) {
                val inner = ArrayList<com.autoball.core.model.Pt>()
                for (p in st) inner.add(com.autoball.core.model.Pt(p.x * s.sx, p.y * s.sy))
                ns.add(inner)
            }
            b.strokes = ns
        }
        return b
    }
}
