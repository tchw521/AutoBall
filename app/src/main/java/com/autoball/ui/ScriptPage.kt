package com.autoball.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.autoball.AB
import com.autoball.core.model.Script
import com.autoball.core.util.Display

/**
 * 脚本页（v3 #p-script）：顶栏 + 左分组气泡栏 + 右列表卡片。
 *
 * 一比一对齐：
 * - 顶栏 h1 26px 800 + 副标题 12px --tx2 + 两个 36dp iconbtn
 * - 左分组栏 100px：气泡小框 .bub.c1..c7 / .all / .add，选中项左侧 3px 渐变竖条
 * - 右列表：chips 筛选条 + 卡片（40dp 徽标 js/rec、名称+tag、meta、38dp 运行按钮）
 */
class ScriptPage(
    context: Context,
    private val host: PageHost
) : FrameLayout(context) {

    companion object { const val TAG = "脚本" }

    private var groupIdx = 0
    private var chipIdx = 0
    private val CHIPS = arrayOf("全部", "最近运行", "已绑定手势", "已禁用")
    private val sel = HashSet<String>()
    private var multiMode = false

    private lateinit var groupBar: LinearLayout
    private lateinit var chipRow: LinearLayout
    private lateinit var listBox: LinearLayout
    private lateinit var multiBar: LinearLayout
    private lateinit var countTv: TextView

    /** 分组：0=全部，其后为真实分组 */
    private fun groups(): List<Pair<String?, Int>> {
        val used = AB.store.all().map { it.groupId }.distinct()
        val out = ArrayList<Pair<String?, Int>>()
        out.add(null to 0) // 全部（渐变实心）
        used.forEachIndexed { i, g -> out.add(g to (i + 1)) }
        return out
    }

    init { build() }

    @SuppressLint("ClickableViewAccessibility")
    private fun build() {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(topbar())

        // 主体：左分组 + 右列表
        val body = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        body.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)

        groupBar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (Theme.isDark()) Color.parseColor("#0D7C3AED")
            else Color.parseColor("#0A2F6BFF"))
            setPadding(0, Display.dpInt(context, 8f), 0, Display.dpInt(context, 12f))
        }
        val gScroll = ScrollView(context).apply {
            addView(groupBar)
            isVerticalScrollBarEnabled = false
        }
        body.addView(gScroll, LinearLayout.LayoutParams(
            Display.dpInt(context, Theme.GROUPBAR_W),
            LinearLayout.LayoutParams.MATCH_PARENT))

        val right = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        chipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 12f),
                Display.dpInt(context, 14f), Display.dpInt(context, 8f))
        }
        right.addView(chipRow)

        listBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val lScroll = ScrollView(context).apply {
            addView(listBox)
            isVerticalScrollBarEnabled = false
            setPadding(Display.dpInt(context, 14f), 0,
                Display.dpInt(context, 14f), Display.dpInt(context, 96f))
        }
        right.addView(lScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        body.addView(right, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        root.addView(body)

        multiBar = buildMultiBar()
        addView(root)
        addView(multiBar)

        renderGroups()
        renderChips()
        renderList()
    }

    private fun topbar(): LinearLayout {
        val b = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Display.dpInt(context, 18f), Display.dpInt(context, 6f),
                Display.dpInt(context, 18f), Display.dpInt(context, 12f))
            gravity = Gravity.BOTTOM
        }
        val l = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        l.addView(TextView(context).apply {
            text = "脚本"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textPri())
            includeFontPadding = false
        })
        l.addView(TextView(context).apply {
            val n = AB.store.all().size
            text = "共 $n 个脚本 · 本地运行"
            textSize = 12f
            setTextColor(Theme.textSec())
            setPadding(0, Display.dpInt(context, 3f), 0, 0)
        })
        b.addView(l, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        b.addView(iconBtn("＋") { NewScriptSheet.show(context as android.app.Activity, host) })
        b.addView(iconBtn("⋯") {
            Ui.toast(context, "排序 / 批量 / 导入")
        })
        return b
    }

    private fun iconBtn(glyph: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = glyph
        textSize = 17f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Theme.textSec())
        gravity = Gravity.CENTER
        background = Theme.rect(Theme.surface(), 12f, context, Theme.line())
        val s = Display.dpInt(context, 36f)
        layoutParams = LinearLayout.LayoutParams(s, s).apply {
            marginStart = Display.dpInt(context, 6f)
        }
        setOnClickListener { onClick() }
    }

    private fun renderGroups() {
        groupBar.removeAllViews()
        val gs = groups()
        gs.forEachIndexed { i, (name, _) ->
            val on = i == groupIdx
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(Display.dpInt(context, 7f), Display.dpInt(context, 3f),
                    Display.dpInt(context, 7f), Display.dpInt(context, 3f))
                layoutParams = lp
                setPadding(Display.dpInt(context, 9f), Display.dpInt(context, 7f),
                    Display.dpInt(context, 9f), Display.dpInt(context, 7f))
                background = Theme.rect(if (on) Theme.surface() else Color.TRANSPARENT,
                    12f, context)
                setOnClickListener { groupIdx = i; renderGroups(); renderList() }
                setOnLongClickListener {
                    if (i > 0) Ui.toast(context, "长按分组：重命名 / 删除")
                    true
                }
            }
            // 选中项左侧 3px 渐变竖条
            if (on) {
                row.addView(View(context).apply {
                    background = Theme.gradOval()
                    val lp = LinearLayout.LayoutParams(Display.dpInt(context, 3f),
                        Display.dpInt(context, 20f))
                    lp.marginEnd = Display.dpInt(context, 6f)
                    lp.leftMargin = -Display.dpInt(context, 9f)
                    layoutParams = lp
                })
            }
            val cnt = if (name == null) AB.store.all().size
            else AB.store.all().count { it.groupId == name }
            row.addView(bubble(if (i == 0) "全部" else name ?: "默认", i - 1, i == 0))
            row.addView(TextView(context).apply {
                text = cnt.toString()
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.textTer())
            })
            groupBar.addView(row)
        }
        // 「+」新增分组（虚线边框 .bub.add）
        groupBar.addView(TextView(context).apply {
            text = "＋ 分组"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textTer())
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Display.dpInt(context, 30f))
            lp.setMargins(Display.dpInt(context, 7f), Display.dpInt(context, 3f),
                Display.dpInt(context, 7f), Display.dpInt(context, 3f))
            layoutParams = lp
            background = GradientDrawable().apply {
                cornerRadius = Display.dp(context, 9f)
                setColor(Color.TRANSPARENT)
                setStroke(Display.dpInt(context, 1f), Theme.line2())
                // 虚线近似为细描边 + 低透明度
            }
            setOnClickListener { Ui.toast(context, "新建分组") }
        })
    }

    /** 气泡小框：全部=渐变实心；其余 .bub.cN */
    private fun bubble(name: String, colorIdx: Int, all: Boolean): TextView =
        TextView(context).apply {
            text = name
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setSingleLine(true)
            maxWidth = Display.dpInt(context, Theme.BUB_MAX)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(Display.dpInt(context, 9f), Display.dpInt(context, 4f),
                Display.dpInt(context, 9f), Display.dpInt(context, 4f))
            background = if (all) {
                Theme.grad(context, 9f)
            } else {
                val k = if (colorIdx < 0) 0 else colorIdx
                GradientDrawable().apply {
                    cornerRadius = Display.dp(context, 9f)
                    setColor(Theme.gTint(k))
                    setStroke(Display.dpInt(context, 1f), Theme.gEdge(k))
                }
            }
            setTextColor(if (all) Color.WHITE else Theme.gInk(if (colorIdx < 0) 0 else colorIdx))
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

    private fun renderChips() {
        chipRow.removeAllViews()
        CHIPS.forEachIndexed { i, t ->
            chipRow.addView(TextView(context).apply {
                text = t
                textSize = 11.5f
                setTypeface(null, if (i == chipIdx) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (i == chipIdx) Color.WHITE else Theme.textSec())
                setPadding(Display.dpInt(context, 11f), Display.dpInt(context, 5f),
                    Display.dpInt(context, 11f), Display.dpInt(context, 5f))
                background = if (i == chipIdx) Theme.grad(context, 9f)
                else Theme.rect(Theme.surface(), 9f, context, Theme.line())
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                if (i > 0) lp.marginStart = Display.dpInt(context, 6f)
                layoutParams = lp
                setOnClickListener { chipIdx = i; renderChips(); renderList() }
            })
        }
    }

    private fun filtered(): List<Script> {
        var l = AB.store.all()
        val gs = groups()
        if (groupIdx >= gs.size) groupIdx = 0
        if (groupIdx > 0) {
            val g = gs[groupIdx].first
            l = l.filter { it.groupId == g }
        }
        l = when (chipIdx) {
            1 -> l.sortedByDescending { it.runCount }
            2 -> l.filter { it.slot != com.autoball.core.model.BallSlot.NONE }
            3 -> l.filter { !it.enabled }
            else -> l
        }
        return l
    }

    private fun renderList() {
        listBox.removeAllViews()
        val list = filtered()
        if (list.isEmpty()) {
            listBox.addView(Ui.hint(context, "这个分组还没有脚本。点右上「＋」新建，或从分享码导入。"))
            return
        }
        list.forEach { s -> listBox.addView(card(s)) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun card(s: Script): LinearLayout {
        val rec = s.kind == com.autoball.core.model.ScriptKind.FLOW
        val c = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.cardBg(context)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, Display.dpInt(context, 10f))
            layoutParams = lp
            setPadding(Display.dpInt(context, 12f), Display.dpInt(context, 12f),
                Display.dpInt(context, 12f), Display.dpInt(context, 12f))
        }

        // 复选框（多选态出现）
        if (multiMode) {
            c.addView(Ui.check(context, sel.contains(s.id)).apply {
                setOnClickListener {
                    if (sel.contains(s.id)) sel.remove(s.id) else sel.add(s.id)
                    renderList(); updateMulti()
                }
            })
            val lp = c.getChildAt(0).layoutParams as LinearLayout.LayoutParams
            lp.marginEnd = Display.dpInt(context, 11f)
        }

        // 徽标 40dp
        c.addView(TextView(context).apply {
            text = if (rec) "录" else "JS"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable(Theme.orientation(),
                if (rec) intArrayOf(Theme.ok(), Color.parseColor("#0E9F5D"))
                else intArrayOf(Theme.pri2(), Color.parseColor("#5B8DEF"))
            ).apply { cornerRadius = Display.dp(context, 12f) }
            layoutParams = LinearLayout.LayoutParams(Display.dpInt(context, 40f),
                Display.dpInt(context, 40f))
        })

        val main = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val nameRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        nameRow.addView(TextView(context).apply {
            text = s.name
            textSize = 14.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textPri())
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (s.isDefault) nameRow.addView(Ui.tag(context, "默认", 0))
        if (s.slot != com.autoball.core.model.BallSlot.NONE) {
            nameRow.addView(Ui.tag(context, s.slot.label, 1))
        }
        main.addView(nameRow)
        main.addView(TextView(context).apply {
            text = "已运行 ${s.runCount} 次"
            textSize = 11.5f
            setTextColor(Theme.textSec())
            setPadding(0, Display.dpInt(context, 4f), 0, 0)
        })
        c.addView(main, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = Display.dpInt(context, 11f)
        })

        // 运行按钮 38dp
        c.addView(Ui.runButton(context) { host.runScript(s) })

        // 点击进入编辑；长按进入多选
        c.setOnClickListener { if (multiMode) toggle(s) else host.openScript(s) }
        c.setOnLongClickListener {
            if (!multiMode) { multiMode = true; sel.add(s.id); renderList(); updateMulti() }
            true
        }
        return c
    }

    private fun toggle(s: Script) {
        if (sel.contains(s.id)) sel.remove(s.id) else sel.add(s.id)
        renderList(); updateMulti()
    }

    private fun buildMultiBar(): LinearLayout {
        val b = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.rect(Theme.surface(), 16f, context, Theme.line2())
            setPadding(Display.dpInt(context, 12f), Display.dpInt(context, 10f),
                Display.dpInt(context, 12f), Display.dpInt(context, 10f))
            val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = Display.dpInt(context, 14f)
                rightMargin = Display.dpInt(context, 14f)
                bottomMargin = Display.dpInt(context, 96f)
                gravity = Gravity.BOTTOM
            }
            layoutParams = lp
            visibility = View.GONE
        }
        countTv = TextView(context).apply {
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textPri())
            setPadding(Display.dpInt(context, 2f), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        b.addView(countTv)
        listOf("全选" to {}, "移动" to {}, "启用" to {}, "删除" to {}).forEachIndexed { i, (t, _) ->
            val btn = TextView(context).apply {
                text = t
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (i == 2 || i == 3) Color.WHITE else Theme.textSec())
                setPadding(Display.dpInt(context, 11f), Display.dpInt(context, 7f),
                    Display.dpInt(context, 11f), Display.dpInt(context, 7f))
                background = if (i == 2) Theme.grad(context, 10f)
                else if (i == 3) Theme.rect(Theme.danger(), 10f, context)
                else Theme.rect(Theme.surface2(), 10f, context, Theme.line())
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                if (i > 0) lp.marginStart = Display.dpInt(context, 7f)
                layoutParams = lp
                setOnClickListener {
                    when (i) {
                        0 -> { sel.addAll(AB.store.all().map { it.id }); renderList(); updateMulti() }
                        3 -> {
                            sel.forEach { AB.store.delete(it) }
                            exitMulti(); renderList()
                        }
                    }
                }
            }
            b.addView(btn)
        }
        b.addView(TextView(context).apply {
            text = "取消"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textSec())
            setPadding(Display.dpInt(context, 11f), Display.dpInt(context, 7f),
                Display.dpInt(context, 11f), Display.dpInt(context, 7f))
            background = Theme.rect(Theme.surface2(), 10f, context, Theme.line())
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginStart = Display.dpInt(context, 7f)
            layoutParams = lp
            setOnClickListener { exitMulti(); renderList() }
        })
        return b
    }

    private fun updateMulti() {
        if (sel.isEmpty()) { exitMulti(); return }
        multiBar.visibility = View.VISIBLE
        countTv.text = "已选 ${sel.size} 项"
    }

    private fun exitMulti() {
        multiMode = false
        sel.clear()
        multiBar.visibility = View.GONE
    }

    fun refresh() { renderGroups(); renderChips(); renderList() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = true
}
