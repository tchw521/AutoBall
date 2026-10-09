package com.autoball.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.autoball.AB
import com.autoball.core.model.Action
import com.autoball.core.model.ActionType
import com.autoball.core.model.Flow
import com.autoball.core.model.Pt
import com.autoball.core.model.Script
import com.autoball.core.model.ScriptKind
import com.autoball.core.util.Display

/**
 * 社区页（v3 #p-com）：分段控制 + 卡片流。
 *
 * 一比一对齐：
 * - .topbar：h1 26px/800 + 副标题 + 36dp 图标按钮
 * - .seg：4 段，容器内 4px 间距，圆角 12，选中段圆角 9 + 主色渐变
 * - .pcard：圆角 16 + 阴影；
 *   .phead（30dp 头像 + 作者）、.ptitle（标题 + .tag.slot/.tag.def）、
 *   .pdesc（12px/1.65）、.pfoot（3 个 38dp .mini + 右侧 .imp 渐变按钮）
 *
 * 重要约束：图像匹配与 OCR 绝不在初始包里。这里只展示清单与状态，不下载。
 */
class MarketPage(context: Context, private val host: PageHost) : FrameLayout(context) {

    private data class Module(
        val id: String,
        val name: String,
        val desc: String,
        val sizeMb: Float,
        val installed: Boolean
    )

    private val modules = listOf(
        Module("img_match", "图像匹配", "找图、找色、相似度匹配，用于「点击图片」类动作。", 1.8f, false),
        Module("ocr", "文字识别", "OCR 模型，用于「点击文字 / 识别屏幕」类动作。", 12.0f, false),
        Module("ai_vision", "AI 视觉", "视觉理解，用于「AI点击」动作。", 8.0f, false)
    )

    /** 内置示例：与网络无关，作为社区页的可用内容 */
    private val samples = listOf(
        Triple("连击示例", "每 500ms 点一次屏幕中央，重复 10 次。", sampleTapFlow()),
        Triple("返回主页示例", "从任意界面按返回键回到桌面。", sampleBackFlow()),
        Triple("滑动等待示例", "上滑一次，等待 1 秒后点击。", sampleSwipeFlow())
    )

    private var segIdx = 0
    /** 与设计稿一致：推荐 / 最新 / 热门 / 我的收藏 */
    private val SEGS = arrayOf("推荐", "最新", "热门", "我的收藏")
    private val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private lateinit var segRow: LinearLayout

    init { build() }

    private fun build() {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(topbar())

        segRow = Kit.segment(context, SEGS.toList(), segIdx) { i ->
            segIdx = i
            renderSeg()
            renderList()
        }
        root.addView(segRow)

        val pad = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Display.dpInt(context, 18f), 0,
                Display.dpInt(context, 18f), Display.dpInt(context, 96f))
        }
        pad.addView(box)
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(pad)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        renderList()
    }

    private fun topbar(): LinearLayout {
        val importBtn = Kit.pill(context, "🔗") {
            val act = context as? Activity ?: return@pill
            ShareImportDialog.show(act, host)
        }
        return Kit.topbar(context, "社区", "示例脚本 · 扩展模块 · 分享码", listOf(importBtn))
    }

    private fun renderSeg() {
        val parent = segRow.parent as? LinearLayout
        val idx = parent?.indexOfChild(segRow) ?: -1
        parent?.removeView(segRow)
        segRow = Kit.segment(context, SEGS.toList(), segIdx) { i ->
            segIdx = i
            renderSeg()
            renderList()
        }
        if (parent != null && idx >= 0) parent.addView(segRow, idx)
    }

    private fun renderList() {
        box.removeAllViews()
        when (segIdx) {
            0 -> renderFeatured()     // 推荐：内置示例 + 官方模块
            1 -> renderLatest()        // 最新：我的脚本按更新时间倒序
            2 -> renderHot()           // 热门：按运行次数倒序
            3 -> renderMine()          // 我的收藏：生成分享码
        }
        if (box.childCount == 0) {
            box.addView(Kit.hintBox(context, "这里还没有内容。"))
        }
        box.addView(Theme.hairline(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Display.dpInt(context, 1f)).apply {
                setMargins(0, Display.dpInt(context, 16f), 0, 0)
            }
        })
        box.addView(TextView(context).apply {
            text = "所有脚本均在本地运行，社区内容不上传任何数据。"
            textSize = 11.5f
            setTextColor(Theme.textTer())
            setPadding(0, Display.dpInt(context, 10f), 0, 0)
        })
    }

    /** 推荐：内置示例在前，官方模块在后 */
    private fun renderFeatured() { renderSamples(); renderModules() }

    /** 最新：本地脚本按更新时间倒序（新建/导入的排最前） */
    private fun renderLatest() {
        mineSorted { it.updatedAt }
    }

    /** 热门：按运行次数倒序 */
    private fun renderHot() {
        mineSorted { it.runCount.toLong() }
    }

    /**
     * 按给定键倒序渲染本地脚本。
     *
     * 设计稿的「最新/热门」针对社区分享；本工具不联网没有远端内容，
     * 这里退化为对本地脚本排序——语义一致（最新 = 最近改动，
     * 热门 = 跑得最多），且不需要假造远端数据。
     */
    private fun mineSorted(key: (Script) -> Long) {
        val list = AB.store.all().sortedByDescending(key)
        if (list.isEmpty()) {
            box.addView(Kit.hintBox(context,
                "还没有脚本。点右上「＋」新建，或录一个试试。"))
            return
        }
        list.forEach { s -> mineCard(s) }
    }

    /** 相对时间：社区卡片右上角（设计稿 ptime） */
    private fun ago(ts: Long): String {
        val d = (System.currentTimeMillis() - ts) / 1000L
        return when {
            d < 60 -> "刚刚"
            d < 3600 -> "${d / 60} 分钟前"
            d < 86400 -> "${d / 3600} 小时前"
            d < 86400 * 30 -> "${d / 86400} 天前"
            else -> "${d / (86400 * 30)} 个月前"
        }
    }

    private fun renderSamples() {
        samples.forEachIndexed { i, (name, desc, flow) ->
            box.addView(card(
                author = "内置",
                title = name,
                desc = desc,
                tag = null,
                stats = "内置 · ${flow.actions.size} 步 · 无需联网",
                timeAgo = "示例",
                onImport = {
                    val s = Script.blank(name)
                    s.kind = ScriptKind.FLOW
                    s.flow = flow
                    AB.store.save(s)
                    Ui.toast(context, "已导入「$name」")
                    host.openScript(s)
                },
                onPreview = { Ui.toast(context, desc) }
            ))
        }
    }

    private fun renderModules() {
        modules.forEach { m ->
            box.addView(card(
                author = "官方",
                title = m.name,
                desc = m.desc,
                tag = if (m.installed) "已装" else null,
                stats = "扩展模块 · 约 ${m.sizeMb}MB · 按需下载",
                timeAgo = "官方",
                onImport = {
                    Ui.toast(context,
                        if (m.installed) "「${m.name}」已安装"
                        else "「${m.name}」约 ${m.sizeMb}MB，按需下载将在后续版本开放")
                },
                onPreview = { Ui.toast(context, m.desc) }
            ))
        }
    }

    private fun renderMine() {
        val list = AB.store.all()
        if (list.isEmpty()) {
            box.addView(Kit.hintBox(context,
                "还没有可分享的脚本。先新建一个，再去「我的」页授权。"))
            return
        }
        list.forEach { s -> mineCard(s) }
    }

    private fun mineCard(s: Script) {
        box.addView(card(
            author = "我",
            title = s.name,
            desc = "共 ${s.flow?.actions?.size ?: 0} 个动作 · 已运行 ${s.runCount} 次",
            tag = if (s.isDefault) "默认" else null,
            stats = "${s.flow?.actions?.size ?: 0} 步 · 已运行 ${s.runCount} 次",
            timeAgo = ago(s.updatedAt),
            onImport = {
                val act = context as? Activity
                if (act != null) {
                    runCatching {
                        ShareImportDialog.showCopy(act, s.name,
                            com.autoball.core.store.ShareCode.encode(s))
                    }
                }
            },
            onPreview = { host.openScript(s) }
        ))
    }

    /**
     * .pcard：phead（头像 + 作者）/ ptitle（标题 + 标签）/ pdesc /
     * pfoot（3 个 mini + 右侧 imp）
     */
    private fun card(author: String, title: String, desc: String, tag: String?,
                     onImport: () -> Unit, onPreview: () -> Unit,
                     stats: String = "", timeAgo: String = ""): LinearLayout {
        val c = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.rect(Theme.surface(), Theme.RADIUS, context, Theme.line())
            setPadding(Display.dpInt(context, 13f), Display.dpInt(context, 13f),
                Display.dpInt(context, 13f), Display.dpInt(context, 13f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, Display.dpInt(context, 10f))
            layoutParams = lp
        }

        // .phead
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, Display.dpInt(context, 9f))
        }
        head.addView(TextView(context).apply {
            text = author.take(1)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Theme.grad(context, 10f)
            layoutParams = LinearLayout.LayoutParams(Display.dpInt(context, 30f),
                Display.dpInt(context, 30f))
        })
        head.addView(TextView(context).apply {
            text = author
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textPri())
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Display.dpInt(context, 9f)
            }
        })
        if (timeAgo.isNotEmpty()) {
            head.addView(TextView(context).apply {
                text = timeAgo
                textSize = 10.5f
                setTextColor(Theme.textTer())
            })
        }
        c.addView(head)

        // .ptitle（标题 + 标签）
        val tRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, Display.dpInt(context, 5f))
        }
        tRow.addView(TextView(context).apply {
            text = title
            textSize = 14.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textPri())
        })
        tag?.let {
            tRow.addView(Ui.tag(context, it, 1).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = Display.dpInt(context, 6f)
                }
            })
        }
        c.addView(tRow)

        // .pdesc
        c.addView(TextView(context).apply {
            text = desc
            textSize = 12f
            setTextColor(Theme.textSec())
            setLineSpacing(Display.dp(context, 2f), 1.65f)
            setPadding(0, 0, 0, Display.dpInt(context, 10f))
        })

        // 统计行：步数 / 运行次数 / 更新时间（设计稿 pstats）

        if (stats.isNotEmpty()) {

        c.addView(TextView(context).apply {

        text = stats

        textSize = 11f

        setTextColor(Theme.textTer())

        setPadding(0, Display.dpInt(context, 6f), 0, 0)

        })

        }

        // .pfoot：3 个 mini + imp
        val foot = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val miniGlyphs = listOf("▶" to onPreview, "★" to { Ui.toast(context, "已收藏") },
            "⋯" to { Ui.toast(context, "更多") })
        miniGlyphs.forEach { (g, act) ->
            foot.addView(Kit.roundBtn(context, g, act))
        }
        foot.addView(TextView(context).apply {
            text = "导入"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Theme.grad(context, 9f)
            setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 6f),
                Display.dpInt(context, 14f), Display.dpInt(context, 6f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = Display.dpInt(context, 14f)
            }
            setOnClickListener { onImport() }
        })
        c.addView(foot)
        return c
    }

    // ---------- 内置示例流 ----------

    private fun sampleTapFlow(): Flow = Flow().apply {
        actions.add(Action().apply {
            type = ActionType.CLICK
            x = 50f; y = 50f
            repeat = 10
            repeatIntervalMs = 500
            comment = "连击屏幕中央"
        })
    }

    private fun sampleBackFlow(): Flow = Flow().apply {
        actions.add(Action().apply {
            type = ActionType.KEY
            keyCode = android.view.KeyEvent.KEYCODE_BACK
            comment = "按返回键"
        })
        actions.add(Action().apply {
            type = ActionType.CONTROL_FLOW
            controlOp = com.autoball.core.model.ControlOp.WAIT
            waitMs = 600
        })
        actions.add(Action().apply {
            type = ActionType.KEY
            keyCode = android.view.KeyEvent.KEYCODE_HOME
            comment = "回桌面"
        })
    }

    private fun sampleSwipeFlow(): Flow = Flow().apply {
        actions.add(Action().apply {
            type = ActionType.SWIPE
            x = 50f; y = 70f; x2 = 50f; y2 = 25f
            durationMs = 400
            comment = "上滑"
        })
        actions.add(Action().apply {
            type = ActionType.CONTROL_FLOW
            controlOp = com.autoball.core.model.ControlOp.WAIT
            waitMs = 1000
        })
        actions.add(Action().apply {
            type = ActionType.CLICK
            x = 50f; y = 45f
            comment = "点击"
        })
    }
}
