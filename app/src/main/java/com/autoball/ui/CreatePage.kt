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
import com.autoball.core.recorder.RecordOverlay
import com.autoball.core.store.ShareCode
import com.autoball.core.util.Display
import com.autoball.float.FloatManager
import com.autoball.service.FloatingService

/**
 * 制作页：开始录制 / 空白脚本 / 导入分享码（复刻自动精灵的三入口）。
 *
 * 录制中断或结束时弹出「放弃 / 继续录制 / 保存」三选一（需求 2.2）。
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
                    }
                }
                override fun onActionAdded(action: com.autoball.core.model.Action, count: Int) {
                    AB.log.info("record", "已记录 $count 个动作：${action.type.label}")
                }
                override fun onInterrupted(reason: String, count: Int, estimatedMs: Long) {
                    RecordOverlay.hide()
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
                        FloatManager.setRecording(false)
                        AB.log.info("record", "已保存为脚本「${s.name}」")
                    }
                    d.dismiss()
                }
                .setNeutralButton("继续录制") { d, _ ->
                    c?.resume()
                    RecordOverlay.show(activity, c!!)
                    d.dismiss()
                }
                .setNegativeButton("放弃") { d, _ ->
                    c?.discard()
                    RecordOverlay.hide()
                    FloatManager.setRecording(false)
                    d.dismiss()
                }
                .setCancelable(false)
                .show()
        }
    }

    init {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        root.addView(Ui.pageTitle(context, "制作"))

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.bubble(context, Theme.card(), 24f)
            setPadding(Display.dpInt(context, 16f), Display.dpInt(context, 16f),
                Display.dpInt(context, 16f), Display.dpInt(context, 16f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(Display.dpInt(context, 16f), Display.dpInt(context, 8f),
                Display.dpInt(context, 16f), Display.dpInt(context, 8f))
            layoutParams = lp
        }

        // 三个入口：统一改为底部半框（v3：占屏 1/4，右上圆形关闭）
        panel.addView(Ui.sheetOption(context, "●", Theme.ok(), "新建脚本",
            "开始录制 / 空白脚本 / 导入分享码") { showNewSheet() })
        panel.addView(Ui.sheetOption(context, "▶", Theme.pri2(), "继续上次录制",
            if (controller != null) "有进行中的录制" else "暂无进行中的录制") {
            controller?.let { RecordOverlay.show(context as? Activity ?: return@sheetOption, it) }
        })
        root.addView(panel)

        // 合规提示：本地、明确授权、可停止
        root.addView(TextView(context).apply {
            text = context.getString(com.autoball.R.string.compliance_notice)
            textSize = 11f
            setTextColor(Theme.textSec())
            setPadding(Display.dpInt(context, 20f), Display.dpInt(context, 16f),
                Display.dpInt(context, 20f), 0)
        })

        addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
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

    /** 「新建脚本」底部半框（v3：图标 + 标题 + 说明三入口，右上角圆形关闭） */
    fun showNewSheet() {
        val act = context as? Activity ?: return
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        box.addView(Ui.sheetOption(act, "●", Theme.ok(), "开始录制",
            "照着点一遍，动作自动记下来") {
            host.startRecording()
        })
        box.addView(Ui.sheetOption(act, "＋", Theme.pri(), "空白脚本",
            "从添加第一个动作开始") {
            val s = Script.blank("未命名脚本")
            AB.store.save(s)
            host.openScript(s)
        })
        box.addView(Ui.sheetOption(act, "🔗", Theme.pri2(), "导入分享码",
            "粘贴一串码，整脚本到手") {
            showImportDialog()
        })
        Ui.sheet(act, "新建脚本").body(box).show()
    }

    private fun showImportDialog() {
        val act = context as? Activity ?: return
        val et = android.widget.EditText(act).apply {
            hint = "粘贴分享码"
            setTextColor(Theme.textPri())
            setHintTextColor(Theme.textSec())
            setSingleLine(false)
            minLines = 3
            gravity = Gravity.TOP
        }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Display.dpInt(context, 20f), Display.dpInt(context, 12f),
                Display.dpInt(context, 20f), 0)
            addView(et)
        }
        AlertDialog.Builder(act).setTitle("导入分享码").setView(box)
            .setPositiveButton("导入") { d, _ ->
                val code = et.text.toString().trim()
                val s = ShareCode.decode(code)
                if (s == null) {
                    AB.log.error("import", "分享码格式不正确或已损坏")
                } else {
                    s.id = Script.newId()
                    AB.store.save(s)
                    AB.log.info("import", "已导入「${s.name}」")
                    host.openScript(s)
                }
                d.dismiss()
            }
            .setNegativeButton("取消", null).show()
    }
}
