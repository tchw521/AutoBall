package com.autoball.core.recorder

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.AB
import com.autoball.core.util.Display
import com.autoball.ui.Theme
import com.autoball.ui.Ui

/**
 * 脚本全局设置（录制小窗头部「⚙」唤起）。
 *
 * 沿用紧凑弹窗规格（206dp、行高 23）：坐标基准 / 转屏适配 / 失败重试 /
 * 超时 / 日志级别 / 运行前体检。
 */
object GlobalSettingsDialog {

    private val RETRY = listOf("不重试", "1 次", "2 次", "3 次")
    private val TIMEOUT = listOf("30 秒", "60 秒", "5 分钟", "不限")
    private val LOGLEVEL = listOf("仅失败", "正常", "详细")

    fun show(ctx: Context) {
        val act = ctx as? android.app.Activity
        if (act == null) {
            android.widget.Toast.makeText(ctx, "请在应用内打开", 0).show()
            return
        }
        var retryIdx = AB.store.getInt("gs_retry", 1)
        var timeoutIdx = AB.store.getInt("gs_timeout", 1)
        var logIdx = AB.store.getInt("gs_log", 1)

        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        var dlg: android.app.Dialog? = null

        fun rebuild() {
            box.removeAllViews()
            box.addView(Ui.adRow(act, "坐标基准", "百分比", true,
                "换机型与转屏都不会点偏") { })
            box.addView(Ui.adRow(act, "转屏适配",
                if (AB.store.getBool("auto_rotate", true)) "开" else "关",
                AB.store.getBool("auto_rotate", true), "旋转后按新宽高换算") {
                AB.store.putBool("auto_rotate", !AB.store.getBool("auto_rotate", true))
                rebuild()
            })
            box.addView(Ui.adSec(act))
            box.addView(Ui.adRow(act, "失败重试", RETRY[retryIdx], retryIdx > 0,
                "动作执行失败后自动重试") {
                Ui.popMenu(act, box, RETRY, retryIdx) { i ->
                    retryIdx = i; AB.store.putInt("gs_retry", i); rebuild()
                }
            })
            box.addView(Ui.adRow(act, "脚本超时", TIMEOUT[timeoutIdx], true,
                "超时后自动停止，避免卡死") {
                Ui.popMenu(act, box, TIMEOUT, timeoutIdx) { i ->
                    timeoutIdx = i; AB.store.putInt("gs_timeout", i); rebuild()
                }
            })
            box.addView(Ui.adRow(act, "日志级别", LOGLEVEL[logIdx], true,
                "详细会记录每一步的后端与耗时") {
                Ui.popMenu(act, box, LOGLEVEL, logIdx) { i ->
                    logIdx = i; AB.store.putInt("gs_log", i); rebuild()
                }
            })
            box.addView(Ui.adSec(act))
            box.addView(Ui.adRow(act, "运行前体检",
                if (AB.store.getBool("preflight", true)) "开" else "关",
                AB.store.getBool("preflight", true), "缺少能力时提前提示") {
                AB.store.putBool("preflight", !AB.store.getBool("preflight", true))
                rebuild()
            })
            box.addView(TextView(act).apply {
                text = "这些设置对全部脚本生效，可在「设置」里再次调整。"
                textSize = 10.5f
                setTextColor(Theme.textTer())
                setPadding(Display.dpInt(act, 8f), Display.dpInt(act, 6f),
                    Display.dpInt(act, 8f), 0)
                gravity = Gravity.CENTER
            })
        }
        rebuild()

        dlg = Ui.dialog(act, "脚本全局设置")
            .body(box)
            .width(com.autoball.ui.Theme.DIALOG_W)
            .negative("关闭")
            .positive("完成") { true }
            .show()
    }
}
