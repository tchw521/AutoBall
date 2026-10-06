package com.autoball.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.widget.ScrollView
import android.widget.TextView
import com.autoball.core.util.Display

/** 更新日志与说明（需求 3.7） */
object ChangeLog {

    private val entries = listOf(
        "v0.3.0" to listOf(
            "执行层重写：无障碍与 Shizuku 两种授权并行对等，任意一种即可运行完整脚本",
            "新增动作级能力路由：按动作所需能力自动选择后端，失败自动切换到另一种",
            "新增运行前能力体检：提示哪些动作会降级、哪些无法执行",
            "新增动作流执行器：不依赖脚本引擎也能跑完整流程",
            "新增 JS 宿主接口：click / press / longClick / swipe / sleep / globalAction / log / screenshot / findNode 等",
            "脚本引擎默认 QuickJS（单 ABI），无 NDK 环境自动降级为纯 Java 引擎",
            "录制改为小区域可移动采集窗，降低 Android 12+ 遮挡拦截风险",
            "新增回声抑制三重判定，防止补发手势被误采集成自触发"
        ),
        "v0.2.0" to listOf(
            "完成 20 类动作的数据模型与动态表单",
            "完成悬浮球四手势与悬浮窗六套皮肤",
            "完成脚本分组、分享码导入导出"
        ),
        "v0.1.0" to listOf(
            "基础工程与无障碍点击链路打通",
            "悬浮球常驻与前台服务"
        )
    )

    fun show(activity: Activity) {
        val sb = StringBuilder()
        for ((ver, items) in entries) {
            sb.append(ver).append('\n')
            for (i in items) sb.append("  · ").append(i).append('\n')
            sb.append('\n')
        }
        sb.append("已知限制\n")
        sb.append("  · 坐标为绝对像素，换机型或转屏会偏移，归一化支持规划在 v0.8\n")
        sb.append("  · 多指手势在无障碍通道依赖并行 stroke，部分厂商 ROM 会降级为单指\n")
        sb.append("  · 图像匹配与 OCR 不进入初始安装包，需按需下载\n")

        val tv = TextView(activity).apply {
            text = sb.toString()
            textSize = 12f
            setTextColor(Theme.textPri())
            setPadding(Display.dpInt(activity, 20f), Display.dpInt(activity, 16f),
                Display.dpInt(activity, 20f), Display.dpInt(activity, 16f))
        }
        val scroll = ScrollView(activity).apply { addView(tv) }
        val d = AlertDialog.Builder(activity)
            .setTitle("更新日志")
            .setView(scroll)
            .setPositiveButton("知道了", null)
            .create()
        d.show()
        d.window?.setLayout(
            (activity.window.decorView.width * 0.82f).toInt(),
            (activity.window.decorView.height * 0.7f).toInt())
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.parseColor(Theme.BLUE))
    }
}
