package com.autoball.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.view.View
import android.widget.TextView
import com.autoball.AB
import com.autoball.core.model.Script
import com.autoball.core.recorder.RecordController
import com.autoball.core.recorder.RecChrome
import com.autoball.core.recorder.RecordOverlay
import com.autoball.core.store.ShareCode
import com.autoball.core.util.Display
import com.autoball.float.FloatManager
import com.autoball.service.FloatingService

/**
 * 制作页：仅保留引导空态。
 *
 * 全部入口（开始录制 / 空白脚本 / 导入分享码）已收进底部导航中央按钮弹出的
 * 「新建脚本」底部半框，不再占用一个页面。
 *
 * Companion 中保留录制会话的启动与结束逻辑，供半框入口调用。
 */
class CreatePage(context: Context, private val host: PageHost) : FrameLayout(context) {

    companion object {

        @Volatile
        var controller: RecordController? = null

        /** 启动一次录制：显示采集层、隐藏悬浮窗、注册中断回调 */
        fun startRecording(activity: Activity, name: String) {
            if (!Display.canDrawOverlay(activity)) {
                Display.openOverlaySettings(activity)
                return
            }
            FloatingService.start(activity)
            val c = RecordController(activity.applicationContext)
            controller = c
            c.callback = object : RecordController.Callback {
                override fun onStateChanged(state: RecordController.State) {
                    if (state == RecordController.State.RECORDING) {
                        FloatManager.setRecording(true)
                        RecordOverlay.show(activity, c)
                        RecChrome.show(activity, c, "未命名脚本")
                        // 录的是别的应用上的操作：开始录制后让出屏幕回到桌面，
                        // 用户再打开目标应用，否则采集层只能采到本应用自己的界面
                        Handler(Looper.getMainLooper()).postDelayed({
                            runCatching {
                                activity.startActivity(Intent(Intent.ACTION_MAIN).apply {
                                    addCategory(Intent.CATEGORY_HOME)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                            }
                        }, 120)
                    } else if (state == RecordController.State.IDLE) {
                        FloatManager.setRecording(false)
                        RecordOverlay.hide()
                    RecChrome.hide()
                    }
                }
                override fun onActionAdded(action: com.autoball.core.model.Action, count: Int) {
                    AB.log.info("record", "已记录 $count 个动作：${action.type.label}")
                }
                override fun onInterrupted(reason: String, count: Int, estimatedMs: Long) {
                    RecordOverlay.hide()
                    RecChrome.hide()
                    showEndDialog(activity, reason, count, estimatedMs)
                }
            }
            c.start(name)
            AB.log.info("record", "采集层已显示，请在采集窗内操作")
        }

        /** 结束/中断弹窗：显示动作数、预计时长、完成度 */
        fun showEndDialog(activity: Activity, reason: String, count: Int, estimatedMs: Long) {
            val c = controller
            val msg = "原因：$reason\n\n动作数：$count\n预计时长：${estimatedMs}ms\n（完成度可在编辑页继续调整）"
            AlertDialog.Builder(activity)
                .setTitle("录制结束")
                .setMessage(msg)
                .setPositiveButton("保存") { d, _ ->
                    val flow = c?.save()
                    if (flow != null) {
                        val s = Script.blank("录制 ${count} 步")
                        s.kind = com.autoball.core.model.ScriptKind.FLOW
                        s.flow = flow
                        AB.store.save(s)
                        RecordOverlay.hide()
                    RecChrome.hide()
                        FloatManager.setRecording(false)
                        AB.log.info("record", "已保存为脚本「${s.name}」")
                    }
                    d.dismiss()
                }
                .setNeutralButton("继续录制") { d, _ ->
                    c?.resume()
                    RecordOverlay.show(activity, c!!)
                    RecChrome.show(activity, c, "未命名脚本")
                    d.dismiss()
                }
                .setNegativeButton("放弃") { d, _ ->
                    c?.discard()
                    RecordOverlay.hide()
                    RecChrome.hide()
                    FloatManager.setRecording(false)
                    d.dismiss()
                }
                .setCancelable(false)
                .show()
        }
    }

    init {
        // 制作页不再承载内容：所有入口都收进底部导航中央按钮弹出的「新建脚本」半框。
        // 这里只留一个引导空态，避免误以为功能缺失。
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(Ui.pageTitle(context, "制作"))
        root.addView(Ui.note(context,
            "点击底部中央的四角星按钮，即可选择「开始录制 / 空白脚本 / 导入分享码」。"))
        addView(root)
    }

    private fun option(title: String, sub: String, color: String, onClick: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Display.dpInt(context, 8f), 0, Display.dpInt(context, 8f))
            setOnClickListener { onClick() }
        }
        // 左 40dp 圆角图标块（纯色，零图标资源）
        row.addView(TextView(context).apply {
            background = Theme.bubble(context, Color.parseColor(color), 12f)
            layoutParams = LinearLayout.LayoutParams(Display.dpInt(context, 40f),
                Display.dpInt(context, 40f))
        })
        val mid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(Display.dpInt(context, 12f), 0, 0, 0)
            }
        }
        mid.addView(TextView(context).apply {
            text = title; textSize = 15f; setTextColor(Theme.textPri())
        })
        mid.addView(TextView(context).apply {
            text = sub; textSize = 11f; setTextColor(Theme.textSec())
        })
        row.addView(mid)
        return row
    }

}
