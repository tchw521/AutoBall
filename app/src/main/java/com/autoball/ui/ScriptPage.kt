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

    private var curGroupId = "all"
    private var chipIdx = 0
    private val CHIPS = arrayOf("全部", "最近运行", "已绑定手势", "已禁用")
    private var query = ""
    private lateinit var clearBtn: TextView
    private val sel = HashSet<String>()
    private var multiMode = false

    private lateinit var groupBar: LinearLayout
    private lateinit var chipRow: LinearLayout
    private lateinit var listBox: LinearLayout
    private lateinit var multiBar: LinearLayout
    private lateinit var countTv: TextView

    /** 分组：0=全部，其后为真实分组 */
    /** 分组条目：全部 / 自定义分组 / 按应用分组，三段并存 */
    private data class GroupEntry(
        val id: String, val name: String, val kind: String,
        val pkg: String?, val colorIdx: Int
    )

    /**
     * 分组列表。
     *
     * 此前是从「脚本实际用到的 groupId」反推，导致两个问题：
     * 1. 新建的空分组因为没有任何脚本归属，立刻从列表消失；
     * 2. 持久化下来的分组名与配色根本没被读取（读的是 id 不是 name）。
     *
     * 改为以持久化分组为准，并额外生成「按应用」虚拟分组
     * （来自脚本的 targetPkg，与设计稿 .gb-head「按应用」一致）。
     */
    private fun groupEntries(): List<GroupEntry> {
        val out = ArrayList<GroupEntry>()
        out.add(GroupEntry("all", "全部", "all", null, 0))
        AB.store.groups().forEach {
            out.add(GroupEntry(it.id, it.name, "custom", null, it.colorIndex))
        }
        val pkgs = AB.store.all().mapNotNull { sc -> sc.targetPkg }
            .filter { it.isNotBlank() }.distinct()
        pkgs.forEach { pkg ->
            out.add(GroupEntry("pkg:$pkg", Display.appLabel(context, pkg), "app", pkg, 6))
        }
        return out
    }

    /** 该分组下的脚本数 */
    private fun countOf(e: GroupEntry): Int {
        val all = AB.store.all()
        return when (e.kind) {
            "all" -> all.size
            "app" -> all.count { it.targetPkg == e.pkg }
            else -> all.count { it.groupId == e.id }
        }
    }

    /**
     * 已渲染卡片：id + 渲染签名。
     *
     * 必须声明在 `init` 之前——Kotlin 属性与 init 块按声明顺序执行，
     * 而 `build()` 会调用 `renderList()`。此前本字段声明在文件后半部分，
     * 导致构造时读到 null（启动即崩）。
     */
    private val shown = ArrayList<Pair<String, String>>()

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
        right.addView(searchBar())
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
        val n = AB.store.all().size
        return Kit.topbar(context, "脚本", "共 $n 个脚本 · 本地运行", listOf(
            Kit.actionBtn(context, "＋") {
                NewScriptSheet.show(context as android.app.Activity, host)
            },
            Kit.actionBtn(context, "⋯") {
                Ui.menu(context, this@ScriptPage,
                    listOf("添加脚本" to false, "导入分享码" to false,
                           "多选管理" to false, "全部导出" to false)) { i ->
                    when (i) {
                        0 -> NewScriptSheet.show(context as android.app.Activity, host)
                        1 -> {
                            val act = context as? android.app.Activity ?: return@menu
                            ShareImportDialog.show(act, host)
                        }
                        2 -> { multiMode = true; renderList() }
                        3 -> exportAll()
                    }
                }
            }
        ))
    }

    /** 分组栏分区标题（设计稿 .gb-head） */
    private fun gbHead(title: String): TextView = TextView(context).apply {
        text = title
        textSize = 10.5f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Theme.textTer())
        setPadding(Display.dpInt(context, 12f), Display.dpInt(context, 8f),
            Display.dpInt(context, 12f), Display.dpInt(context, 4f))
    }

    /** 按 id 打开分组菜单（重命名 / 换色 / 删除） */
    private fun groupMenuById(anchor: View, gid: String) {
        val gs = AB.store.groups()
        val i = gs.indexOfFirst { it.id == gid }
        if (i >= 0) groupMenu(anchor, i)
    }

    private fun renderGroups() {
        groupBar.removeAllViews()
        val gs = groupEntries()
        // 分区标题：自定义分组 / 按应用（设计稿 .gb-head）
        groupBar.addView(gbHead("自定义分组"))
        var lastKind = "custom"
        gs.forEachIndexed { i, e ->
            val on = e.id == curGroupId
            if (e.kind == "app" && lastKind != "app") {
                groupBar.addView(gbHead("按应用"))
                lastKind = "app"
            }
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
                setOnClickListener { curGroupId = e.id; renderGroups(); renderList() }
                setOnLongClickListener {
                    if (e.kind == "custom") groupMenuById(this, e.id)
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
            val cnt = countOf(e)
            row.addView(bubble(e.name, e.colorIdx, e.kind == "all"))
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
            setOnClickListener { newGroupDialog() }
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
        var l: List<Script> = AB.store.all()
        val e = groupEntries().firstOrNull { it.id == curGroupId }
            ?: groupEntries()[0].also { curGroupId = "all" }
        l = when (e.kind) {
            "all" -> l
            "app" -> l.filter { it.targetPkg == e.pkg }
            else -> l.filter { it.groupId == e.id }
        }
        l = when (chipIdx) {
            1 -> l.sortedByDescending { it.runCount }
            2 -> l.filter { it.slot != com.autoball.core.model.BallSlot.NONE }
            3 -> l.filter { !it.enabled }
            else -> l
        }
        val kw = query.trim()
        if (kw.isNotEmpty()) {
            l = l.filter {
                it.name.contains(kw, true) ||
                    (it.jsCode.contains(kw, true)) ||
                    (it.flow?.name?.contains(kw, true) == true)
            }
        }
        return l
    }

    /** 搜索框：输入即过滤，清空按钮一键还原 */
    private fun searchBar(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.rect(Theme.surface(), 12f, context, Theme.line())
            setPadding(Display.dpInt(context, 12f), Display.dpInt(context, 6f),
                Display.dpInt(context, 10f), Display.dpInt(context, 6f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(Display.dpInt(context, 14f), Display.dpInt(context, 2f),
                Display.dpInt(context, 14f), 0)
            layoutParams = lp
        }
        row.addView(TextView(context).apply {
            text = "⌕"
            textSize = 14f
            setTextColor(Theme.textTer())
        })
        val et = android.widget.EditText(context).apply {
            hint = "搜索脚本"
            setHintTextColor(Theme.textTer())
            setTextColor(Theme.textPri())
            textSize = 13f
            background = null
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Display.dpInt(context, 8f)
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(c: CharSequence?, a: Int, b: Int, n: Int) {}
                override fun onTextChanged(c: CharSequence?, a: Int, b: Int, n: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) {
                    query = e?.toString() ?: ""
                    clearBtn.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                    renderList()
                }
            })
        }
        row.addView(et)
        clearBtn = TextView(context).apply {
            text = "✕"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textTer())
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(Display.dpInt(context, 6f), 0, Display.dpInt(context, 2f), 0)
            setOnClickListener {
                et.setText("")
                query = ""
                visibility = View.GONE
                renderList()
            }
        }
        row.addView(clearBtn)
        return row
    }

    /**
     * 列表渲染：增量刷新。
     *
     * 原实现每次都 `removeAllViews()` 全量重建，勾选一项、输入一个搜索字
     * 都要重建全部卡片（每张卡片含十余个子 View），脚本多了会明显卡顿。
     * 改为按 id 与「渲染签名」比对：
     * - 顺序与签名都没变 → 直接复用，不动；
     * - 只有个别卡片签名变了（如运行次数、多选勾选）→ 只重建那几张；
     * - 数量或顺序变化 → 回退全量重建。
     */
    private fun renderList() {
        val list = filtered()
        if (list.isEmpty()) {
            listBox.removeAllViews()
            shown.clear()
            listBox.addView(Ui.hint(context, "这个分组还没有脚本。点右上「＋」新建，或从分享码导入。"))
            return
        }
        // 首个孩子可能是空态提示
        if (listBox.childCount == 1 && listBox.getChildAt(0) !is LinearLayout) {
            listBox.removeAllViews()
            shown.clear()
        }

        val sameShape = shown.size == list.size &&
            shown.indices.all { shown[it].first == list[it].id }
        if (sameShape) {
            // 只重建签名变化的卡片，其余原样保留（保持滚动位置与点击态）
            for (i in list.indices) {
                val ns = sig(list[i])
                if (ns != shown[i].second) {
                    listBox.removeViewAt(i)
                    listBox.addView(card(list[i]), i)
                    shown[i] = list[i].id to ns
                }
            }
            return
        }
        listBox.removeAllViews()
        shown.clear()
        list.forEach { sc ->
            listBox.addView(card(sc))
            shown.add(sc.id to sig(sc))
        }
    }

    /** 渲染签名：影响卡片外观的字段都纳入，变了才重建 */
    private fun sig(s: Script): String = buildString {
        append(s.name).append('|')
        append(s.kind.name).append('|')
        append(s.runCount).append('|')
        append(s.enabled).append('|')
        append(s.slot.name).append('|')
        append(s.isDefault).append('|')
        append(multiMode).append('|')
        append(sel.contains(s.id)).append('|')
        append(s.targetPkg ?: "")
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

        // 卡片菜单：三点
        c.addView(TextView(context).apply {
            text = "⋯"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textTer())
            gravity = Gravity.CENTER
            val sz = Display.dpInt(context, 26f)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginEnd = Display.dpInt(context, 2f)
            }
            setOnClickListener {
                Ui.menu(context, this,
                    listOf("编辑" to false, "运行" to false, "生成分享码" to false,
                           "加密分享码" to false, "定时与循环" to false,
                           "绑定手势" to false, "重命名" to false, "删除" to true)) { i ->
                    when (i) {
                        0 -> host.openScript(s)
                        1 -> host.runScript(s)
                        2 -> {
                            val act = context as? android.app.Activity
                            if (act != null) ShareImportDialog.showCopy(
                                act, s.name, com.autoball.core.store.ShareCode.encode(s))
                        }
                        3 -> askPassThenShare(
                            context as? android.app.Activity ?: return@setOnClickListener, s)
                        4 -> ScheduleDialog.show(
                            context as? android.app.Activity ?: return@setOnClickListener, s) {
                            AB.store.save(s); renderList()
                        }
                        5 -> host.openSubPage("float")
                        6 -> renameDialog(s)
                        7 -> { AB.store.delete(setOf(s.id)); renderList() }
                    }
                }
            }
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
                        0 -> {
                            sel.clear()
                            sel.addAll(filtered().map { it.id }.toSet())
                            renderList(); updateMulti()
                        }
                        1 -> moveToGroupDialog()
                        2 -> {
                            val ids = sel.toSet()
                            AB.store.all().forEach {
                                if (it.id in ids) { it.enabled = true; AB.store.save(it) }
                            }
                            AB.log.info("script", "已启用 ${ids.size} 个脚本")
                            Ui.toast(context, "已启用 ${ids.size} 个脚本")
                            renderList(); updateMulti()
                        }
                        3 -> {
                            AB.store.delete(sel.toSet())
                            AB.log.info("script", "已删除 ${sel.size} 个脚本")
                            Ui.toast(context, "已删除 ${sel.size} 个脚本")
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

    // ---------- 分组管理 ----------

    /** 分组长按：重命名 / 换色 / 删除 */
    private fun groupMenu(anchorView: View, i: Int) {
        val gs = AB.store.groups()
        val g = gs.getOrNull(i) ?: return
        Ui.menu(context, anchorView,
            listOf("重命名" to false, "更换颜色" to false, "删除分组" to true)) { k ->
            when (k) {
                0 -> groupNameDialog(g)
                1 -> groupColorDialog(g)
                2 -> groupDeleteDialog(g)
            }
        }
    }

    private fun groupNameDialog(g: com.autoball.core.model.Group) {
        val act = context as? android.app.Activity ?: return
        val et = android.widget.EditText(act).apply {
            setText(g.name)
            setTextColor(Theme.textPri())
            textSize = 14f
            setSingleLine(true)
        }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Display.dpInt(act, 20f), Display.dpInt(act, 12f),
                Display.dpInt(act, 20f), 0)
            addView(et)
        }
        Ui.dialog(act, "重命名分组").body(box)
            .negative("取消")
            .positive("确定") {
                val n = et.text.toString().trim()
                if (n.isEmpty()) { Ui.toast(act, "名称不能为空"); false }
                else { g.name = n; AB.store.saveGroup(g); renderGroups(); true }
            }.show()
    }

    private fun groupColorDialog(g: com.autoball.core.model.Group) {
        val act = context as? android.app.Activity ?: return
        val grid = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val colors = List(7) { idx ->
            "配色 ${idx + 1}" to ""
        }
        val gv = Ui.actionGrid(act, colors) { idx ->
            g.colorIndex = idx
            AB.store.saveGroup(g)
            renderGroups()
        }
        grid.addView(gv)
        Ui.dialog(act, "选择分组配色").body(grid)
            .width(Theme.DIALOG_W + 60f)
            .negative("取消").show()
    }

    private fun groupDeleteDialog(g: com.autoball.core.model.Group) {
        val act = context as? android.app.Activity ?: return
        val n = AB.store.all().count { it.groupId == g.id }
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(act).apply {
            text = if (n > 0) "该分组下有 $n 个脚本，删除后它们会移动到「默认分组」，不会丢失。"
            else "确定删除分组「${g.name}」？"
            textSize = 12.5f
            setTextColor(Theme.textSec())
            setLineSpacing(Display.dp(act, 2f), 1.6f)
        })
        Ui.dialog(act, "删除分组").body(box)
            .negative("取消")
            .positiveDanger("删除") {
                AB.store.deleteGroup(g.id)
                curGroupId = "all"
                renderGroups(); renderList()
                true
            }.show()
    }

    private fun newGroupDialog() {
        val act = context as? android.app.Activity ?: return
        val et = android.widget.EditText(act).apply {
            hint = "分组名称"
            setHintTextColor(Theme.textTer())
            setTextColor(Theme.textPri())
            textSize = 14f
            setSingleLine(true)
        }
        var colorIdx = AB.store.groups().size % 7
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Display.dpInt(act, 20f), Display.dpInt(act, 12f),
                Display.dpInt(act, 20f), 0)
            addView(et)
        }
        Ui.dialog(act, "新建分组").body(box)
            .negative("取消")
            .positive("创建") {
                val n = et.text.toString().trim()
                if (n.isEmpty()) { Ui.toast(act, "名称不能为空"); false }
                else {
                    val g = com.autoball.core.model.Group().apply {
                        id = "g" + System.nanoTime().toString(36)
                        name = n
                        kind = "custom"
                        this.colorIndex = colorIdx
                    }
                    AB.store.saveGroup(g)
                    renderGroups()
                    true
                }
            }.show()
    }

    // ---------- 批量 ----------

    /** 批量移动到分组 */
    private fun moveToGroupDialog() {
        val act = context as? android.app.Activity ?: return
        val gs = AB.store.groups()
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        gs.forEach { g ->
            box.addView(Ui.sheetOption(act, "▸", Theme.gInk(g.colorIndex), g.name,
                "移动到这里") {
                AB.store.move(sel.toSet(), g.id)
                Ui.toast(act, "已移动 ${sel.size} 个脚本")
                exitMulti(); renderList()
            })
        }
        Ui.sheet(act, "移动到分组").body(box).show()
    }

    /** 全部导出：把所有脚本打包成一段分享码 */
    private fun exportAll() {
        val act = context as? android.app.Activity ?: return
        val all = AB.store.all()
        if (all.isEmpty()) { Ui.toast(act, "还没有脚本"); return }
        val code = runCatching {
            com.autoball.core.store.ShareCode.encodeAll(all)
        }.getOrElse {
            Ui.toast(act, "导出失败：${it.message}")
            return
        }
        ShareImportDialog.showCopy(act, "全部 ${all.size} 个脚本", code)
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

    private fun renameDialog(s: Script) {
        val act = context as? android.app.Activity ?: return
        val et = android.widget.EditText(act).apply {
            setText(s.name)
            setTextColor(Theme.textPri())
            textSize = 14f
            setSingleLine(true)
        }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Display.dpInt(act, 20f), Display.dpInt(act, 12f),
                Display.dpInt(act, 20f), 0)
            addView(et)
        }
        Ui.dialog(act, "重命名").body(box)
            .negative("取消")
            .positive("确定") {
                val n = et.text.toString().trim()
                if (n.isEmpty()) { Ui.toast(act, "名称不能为空"); false }
                else { s.name = n; AB.store.save(s); renderList(); true }
            }.show()
    }

    fun refresh() { shown.clear(); renderGroups(); renderChips(); renderList() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = true
    /**
     * 加密分享（自动精灵同款）。
     * 口令不随码传输，导入方须手动输入相同口令。
     */
    private fun askPassThenShare(act: android.app.Activity, s: com.autoball.core.model.Script) {
        val ed = android.widget.EditText(act).apply {
            hint = "口令（导入时需输入相同口令）"
            setText(s.sharePass)
            setSingleLine(true)
            textSize = 13f
            setPadding(Display.dpInt(act, 12f), Display.dpInt(act, 10f),
                Display.dpInt(act, 12f), Display.dpInt(act, 10f))
        }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(ed)
            addView(Kit.note(act, "留空则生成普通分享码（不加密）。", 6f))
        }
        Ui.dialog(act, "加密分享")
            .body(box)
            .negative("取消") { }
            .positive("生成") {
                val pass = ed.text.toString().trim()
                s.sharePass = pass
                com.autoball.AB.store.save(s)
                ShareImportDialog.showCopy(act, s.name,
                    com.autoball.core.store.ShareCode.encode(s, pass))
                true
            }.show()
    }

}
