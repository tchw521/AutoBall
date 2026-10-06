package com.autoball.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.autoball.AB
import com.autoball.core.model.Action
import com.autoball.core.model.Pt
import com.autoball.core.model.Script
import com.autoball.core.store.ShareCode
import com.autoball.core.util.Display

/**
 * 市场页：他人分享的脚本 / 图像匹配 + OCR 按需下载模块。
 *
 * 重要约束：图像匹配与 OCR 绝不在初始包里（包体 ≤6MB）。
 * 这里只展示"模块清单 + 下载入口"，实际下载与校验由后续版本接入官方 CDN。
 */
class MarketPage(context: Context, private val host: PageHost) : FrameLayout(context) {

    /** 按需模块状态：未下载 / 已下载 */
    private data class Module(
        val id: String,
        val name: String,
        val desc: String,
        val sizeMb: Float,
        val installed: Boolean
    )

    private val modules = listOf(
        Module("img_match", "图像匹配", "找图、找色、相似度匹配，用于「点击图片」类动作", 1.8f, false),
        Module("ocr", "文字识别", "OCR 模型，用于「点击文字 / 识别屏幕」类动作", 12.0f, false),
        Module("ai_vision", "AI 视觉", "视觉理解，用于「AI点击」动作", 8.0f, false)
    )

    /** 内置示例脚本：与网络无关，作为市场页的可用内容 */
    private val samples = listOf(
        "示例：连续点击" to sampleTapFlow(),
        "示例：返回主页" to sampleBackFlow(),
        "示例：滑动并等待" to sampleSwipeFlow()
    )

    private val listBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        root.addView(TextView(context).apply {
            text = "市场"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textPri())
            setPadding(Display.dpInt(context, 16f), Display.dpInt(context, 18f),
                Display.dpInt(context, 16f), Display.dpInt(context, 10f))
        })

        val scroll = ScrollView(context)
        scroll.addView(listBox, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        rebuild()
    }

    private fun rebuild() {
        listBox.removeAllViews()
        listBox.setPadding(Display.dpInt(context, 16f), 0,
            Display.dpInt(context, 16f), Display.dpInt(context, 96f))

        listBox.addView(section("按需能力模块"))
        listBox.addView(TextView(context).apply {
            text = "以下能力不进入初始安装包，需要时再下载。下载前会展示模块名称、版本、大小与用途。"
            textSize = 11f
            setTextColor(Theme.textSec())
            setPadding(0, 0, 0, Display.dpInt(context, 8f))
        })
        for (m in modules) listBox.addView(moduleCard(m))

        listBox.addView(section("示例脚本"))
        listBox.addView(TextView(context).apply {
            text = "内置示例不联网，可直接保存到「脚本」页试用。"
            textSize = 11f
            setTextColor(Theme.textSec())
            setPadding(0, 0, 0, Display.dpInt(context, 8f))
        })
        for ((name, s) in samples) listBox.addView(sampleCard(name, s))

        listBox.addView(section("导入分享码"))
        listBox.addView(TextView(context).apply {
            text = "粘贴他人分享的脚本码，导入前会校验完整性与版本，损坏或被篡改的码会被拒绝。"
            textSize = 11f
            setTextColor(Theme.textSec())
            setPadding(0, 0, 0, Display.dpInt(context, 8f))
        })
        listBox.addView(TextView(context).apply {
            text = "导入分享码"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Theme.bubble(context, Color.parseColor(Theme.BLUE), 12f)
            setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 10f),
                Display.dpInt(context, 14f), Display.dpInt(context, 10f))
            setOnClickListener { host.showPage(2) }
        })
    }

    private fun section(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 14f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Theme.textPri())
        setPadding(0, Display.dpInt(context, 14f), 0, Display.dpInt(context, 6f))
    }

    private fun moduleCard(m: Module): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.bubble(context, Theme.card(), 16f)
            setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 12f),
                Display.dpInt(context, 14f), Display.dpInt(context, 12f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, Display.dpInt(context, 10f))
            layoutParams = lp
        }
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL }
        val mid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        mid.addView(TextView(context).apply {
            text = m.name; textSize = 15f; setTextColor(Theme.textPri())
        })
        mid.addView(TextView(context).apply {
            text = m.desc; textSize = 11f; setTextColor(Theme.textSec())
        })
        head.addView(mid)
        head.addView(TextView(context).apply {
            text = if (m.installed) "已安装" else "%.1fMB".format(m.sizeMb)
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Theme.bubble(context,
                Color.parseColor(if (m.installed) "#35D08A" else Theme.PURPLE), 12f)
            setPadding(Display.dpInt(context, 12f), Display.dpInt(context, 6f),
                Display.dpInt(context, 12f), Display.dpInt(context, 6f))
            setOnClickListener { onModuleClick(m) }
        })
        card.addView(head)
        return card
    }

    private fun onModuleClick(m: Module) {
        val act = context as? Activity ?: return
        if (m.installed) {
            AB.log.info("market", "「${m.name}」已安装")
            return
        }
        AlertDialog.Builder(act)
            .setTitle("下载「${m.name}」")
            .setMessage("大小：%.1fMB\n用途：${m.desc}\n\n该模块不进入初始安装包，下载后仅保存在本应用私有目录。"
                .format(m.sizeMb))
            .setPositiveButton("知道了") { d, _ ->
                AB.log.info("market", "「${m.name}」下载入口已就绪，实际下载通道在后续版本接入")
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun sampleCard(name: String, s: Script): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.bubble(context, Theme.card(), 16f)
            setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 12f),
                Display.dpInt(context, 12f), Display.dpInt(context, 12f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, Display.dpInt(context, 10f))
            layoutParams = lp
        }
        card.addView(TextView(context).apply {
            text = name
            textSize = 14f
            setTextColor(Theme.textPri())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        card.addView(TextView(context).apply {
            text = "保存"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Theme.bubble(context, Color.parseColor(Theme.BLUE), 12f)
            setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 7f),
                Display.dpInt(context, 14f), Display.dpInt(context, 7f))
            setOnClickListener {
                val copy = Script.fromJson(s.toJson())
                copy.id = Script.newId()
                copy.flow?.id = com.autoball.core.model.Flow.newId()
                AB.store.save(copy)
                AB.log.info("market", "已保存「${copy.name}」")
            }
        })
        card.addView(TextView(context).apply {
            text = "分享"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Theme.bubble(context, Color.parseColor("#35D08A"), 12f)
            setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 7f),
                Display.dpInt(context, 14f), Display.dpInt(context, 7f))
            val lp2 = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp2.setMargins(Display.dpInt(context, 6f), 0, 0, 0)
            layoutParams = lp2
            setOnClickListener {
                val code = ShareCode.encode(s)
                val act = context as? Activity
                if (act != null) {
                    val cm = act.getSystemService(Context.CLIPBOARD_SERVICE)
                            as? android.content.ClipboardManager
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("autoball", code))
                    AB.log.info("market", "分享码已复制（${code.length} 字符）")
                }
            }
        })
        return card
    }

    // ---------- 内置示例 ----------

    private fun sampleTapFlow(): Script {
        val s = Script.blank("示例：连续点击")
        val f = s.flow!!
        for (i in 0 until 3) {
            f.actions.add(Action().apply {
                id = Action.newId()
                type = com.autoball.core.model.ActionType.CLICK
                val p = Display.screenSize(context)
                x = p.x * 0.5f
                y = p.y * (0.35f + i * 0.15f)
                durationMs = 80
                waitMs = 500
            })
        }
        return s
    }

    private fun sampleBackFlow(): Script {
        val s = Script.blank("示例：返回主页")
        val f = s.flow!!
        f.actions.add(Action().apply {
            id = Action.newId()
            type = com.autoball.core.model.ActionType.KEY
            keyCode = android.view.KeyEvent.KEYCODE_BACK
            waitMs = 600
        })
        f.actions.add(Action().apply {
            id = Action.newId()
            type = com.autoball.core.model.ActionType.KEY
            keyCode = android.view.KeyEvent.KEYCODE_HOME
            waitMs = 300
        })
        return s
    }

    private fun sampleSwipeFlow(): Script {
        val s = Script.blank("示例：滑动并等待")
        val f = s.flow!!
        val p = Display.screenSize(context)
        f.actions.add(Action().apply {
            id = Action.newId()
            type = com.autoball.core.model.ActionType.SWIPE
            x = p.x * 0.5f; y = p.y * 0.75f
            x2 = p.x * 0.5f; y2 = p.y * 0.3f
            durationMs = 400
            waitMs = 800
        })
        f.actions.add(Action().apply {
            id = Action.newId()
            type = com.autoball.core.model.ActionType.CONTROL_FLOW
            controlOp = com.autoball.core.model.ControlOp.WAIT
            durationMs = 1000
        })
        return s
    }
}
