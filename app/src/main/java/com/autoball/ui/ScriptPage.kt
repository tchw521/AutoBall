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
import com.autoball.core.engine.ScriptLauncher
import com.autoball.core.model.Script
import com.autoball.core.model.ScriptKind
import com.autoball.core.util.Display

/**
 * 脚本页：左侧分组栏（自定义分组 + 按应用分组并存）+ 右侧脚本卡片列表。
 *
 * 长按卡片弹出菜单；多选模式底部出现操作条（全选 / 移动 / 删除 / 取消）。
 * 无 RecyclerView（零第三方依赖），列表用 ScrollView + 动态构建，脚本量级下无性能问题。
 */
class ScriptPage(context: Context, private val host: PageHost) : FrameLayout(context) {

    private var currentGroup = "default"
    private var multiMode = false
    private val selectedIds = LinkedHashSet<String>()

    private val groupBar = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val listBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val actionBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        visibility = View.GONE
    }
    private val title = TextView(context)

    init {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        title.text = "脚本"
        title.textSize = 20f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Theme.textPri())
        title.setPadding(Display.dpInt(context, 16f), Display.dpInt(context, 18f),
            Display.dpInt(context, 16f), Display.dpInt(context, 10f))
        root.addView(title)

        val body = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val groupScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(Display.dpInt(context, 88f),
                LinearLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.TRANSPARENT)
        }
        groupScroll.addView(groupBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        body.addView(groupScroll)

        val listScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        listScroll.addView(listBox, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        body.addView(listScroll)

        // 列表底部留 96dp，避免被悬浮导航遮住
        listBox.setPadding(Display.dpInt(context, 10f), 0,
            Display.dpInt(context, 10f), Display.dpInt(context, 96f))

        root.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        buildActionBar()
        root.addView(actionBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        rebuildGroups()
        rebuildList()
    }

    private fun buildActionBar() {
        actionBar.setBackgroundColor(Theme.card())
        actionBar.setPadding(Display.dpInt(context, 8f), Display.dpInt(context, 8f),
            Display.dpInt(context, 8f), Display.dpInt(context, 8f))
        actionBar.addView(barBtn("全选") {
            val all = visibleScripts()
            selectedIds.clear()
            if (selectedIds.size == all.size) selectedIds.clear() else all.forEach { selectedIds.add(it.id) }
            rebuildList()
        })
        actionBar.addView(barBtn("移动") { moveSelected() })
        actionBar.addView(barBtn("删除") {
            AB.store.delete(selectedIds)
            exitMulti()
            rebuildList()
        })
        actionBar.addView(barBtn("取消") { exitMulti() })
    }

    private fun barBtn(text: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Theme.bubble(context, Color.parseColor(Theme.BLUE), 12f)
            setPadding(Display.dpInt(context, 16f), Display.dpInt(context, 8f),
                Display.dpInt(context, 16f), Display.dpInt(context, 8f))
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(Display.dpInt(context, 4f), 0, Display.dpInt(context, 4f), 0)
            layoutParams = lp
            setOnClickListener { onClick() }
        }

    // ---------- 数据 ----------

    private fun visibleScripts(): List<Script> {
        val all = AB.store.all()
        return if (currentGroup == "__all") all
        else all.filter { it.groupId == currentGroup }
            .sortedByDescending { it.updatedAt }
    }

    private fun rebuildGroups() {
        groupBar.removeAllViews()
        val groups = AB.store.groups()

        groupBar.addView(groupChip("全部", currentGroup == "__all", 0) { currentGroup = "__all"; rebuildGroups(); rebuildList() })
        groups.forEachIndexed { i, g ->
            groupBar.addView(groupChip(g.name, currentGroup == g.id, i) {
                currentGroup = g.id; rebuildGroups(); rebuildList()
            })
        }
        // 按应用分组：有目标应用的脚本自动生成
        val pkgs = AB.store.all().mapNotNull { it.targetPkg }.distinct()
        if (pkgs.isNotEmpty()) {
            val divider = TextView(context).apply {
                text = "按应用"
                textSize = 10f
                setTextColor(Theme.textSec())
                setPadding(Display.dpInt(context, 10f), Display.dpInt(context, 12f), 0, Display.dpInt(context, 4f))
            }
            groupBar.addView(divider)
            pkgs.forEach { pkg ->
                groupBar.addView(groupChip(pkg, currentGroup == pkg, 0) {
                    currentGroup = pkg; rebuildGroups(); rebuildList()
                })
            }
        }
    }

    private fun groupChip(name: String, selected: Boolean, colorIdx: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = name
            textSize = 11f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(if (selected) Color.WHITE else Theme.textSec())
            maxLines = 1
            background = Theme.bubble(context,
                if (selected) Theme.GROUP_COLORS[colorIdx % Theme.GROUP_COLORS.size]
                else Color.parseColor(if (Theme.isDark()) "#2A2347" else "#EDEEF5"), 10f)
            setPadding(Display.dpInt(context, 10f), Display.dpInt(context, 8f),
                Display.dpInt(context, 10f), Display.dpInt(context, 8f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(Display.dpInt(context, 6f), Display.dpInt(context, 4f),
                Display.dpInt(context, 6f), Display.dpInt(context, 4f))
            layoutParams = lp
            setOnClickListener { onClick() }
        }

    private fun rebuildList() {
        listBox.removeAllViews()
        val list = visibleScripts()
        val count = TextView(context).apply {
            text = "共 ${list.size} 个脚本"
            textSize = 11f
            setTextColor(Theme.textSec())
            setPadding(Display.dpInt(context, 6f), Display.dpInt(context, 4f), 0, Display.dpInt(context, 8f))
        }
        listBox.addView(count)

        if (list.isEmpty()) {
            listBox.addView(TextView(context).apply {
                text = "这个分组还没有脚本，点下方「制作」新建"
                textSize = 13f
                setTextColor(Theme.textSec())
                gravity = Gravity.CENTER
                setPadding(0, Display.dpInt(context, 48f), 0, 0)
            })
            return
        }
        for (s in list) listBox.addView(card(s))
    }

    private fun card(s: Script): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = if (multiMode && selectedIds.contains(s.id))
                Theme.bubble(context, Color.parseColor("#3A2E6B"), 16f, Color.parseColor("#8FDBFF"))
            else Theme.bubble(context, Theme.card(), 16f)
            setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 12f),
                Display.dpInt(context, 12f), Display.dpInt(context, 12f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, Display.dpInt(context, 10f))
            layoutParams = lp
        }

        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(TextView(context).apply {
            text = s.name
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textPri())
            maxLines = 1
        })

        val badges = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        badges.addView(badge(if (s.kind == ScriptKind.JS) "JS" else "录",
            if (s.kind == ScriptKind.JS) Theme.PURPLE else Theme.BLUE))
        if (s.isDefault) badges.addView(badge("默认", "#35D08A"))
        if (s.slot != com.autoball.core.model.BallSlot.NONE) badges.addView(badge(s.slot.label, "#FFB020"))
        left.addView(badges)

        card.addView(left)

        card.addView(TextView(context).apply {
            text = if (multiMode) (if (selectedIds.contains(s.id)) "☑" else "☐") else "运行"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Theme.bubble(context, Color.parseColor(Theme.BLUE), 12f)
            setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 7f),
                Display.dpInt(context, 14f), Display.dpInt(context, 7f))
            setOnClickListener {
                if (multiMode) {
                    if (selectedIds.contains(s.id)) selectedIds.remove(s.id) else selectedIds.add(s.id)
                    rebuildList()
                } else {
                    ScriptLauncher.launch(context.applicationContext, s)
                }
            }
        })

        card.setOnLongClickListener {
            if (!multiMode) showCardMenu(s)
            true
        }
        return card
    }

    private fun badge(text: String, color: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 10f
            setTextColor(Color.WHITE)
            background = Theme.bubble(context, Color.parseColor(color), 8f)
            setPadding(Display.dpInt(context, 7f), Display.dpInt(context, 2f),
                Display.dpInt(context, 7f), Display.dpInt(context, 2f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, Display.dpInt(context, 4f), Display.dpInt(context, 5f), 0)
            layoutParams = lp
        }

    // ---------- 菜单 ----------

    private fun showCardMenu(s: Script) {
        val act = context as? Activity ?: return
        val items = arrayOf("编辑", "重命名", "移动分组", "多选", "在此分组新建", "删除")
        AlertDialog.Builder(act).setTitle(s.name).setItems(items) { _, which ->
            when (which) {
                0 -> host.openScript(s)
                1 -> askText("重命名", s.name) { v -> s.name = v; AB.store.save(s); rebuildList() }
                2 -> moveOne(s)
                3 -> enterMulti(s)
                4 -> createInGroup()
                5 -> confirm("删除「${s.name}」？") { AB.store.delete(listOf(s.id)); rebuildList() }
            }
        }.show()
    }

    private fun enterMulti(first: Script) {
        multiMode = true
        selectedIds.clear()
        selectedIds.add(first.id)
        actionBar.visibility = View.VISIBLE
        rebuildList()
    }

    private fun exitMulti() {
        multiMode = false
        selectedIds.clear()
        actionBar.visibility = View.GONE
        rebuildList()
    }

    private fun moveSelected() {
        val groups = AB.store.groups()
        val act = context as? Activity ?: return
        val names = groups.map { it.name }.toTypedArray()
        AlertDialog.Builder(act).setTitle("移动到").setItems(names) { _, w ->
            AB.store.move(selectedIds, groups[w].id)
            exitMulti(); rebuildList()
        }.show()
    }

    private fun moveOne(s: Script) {
        val groups = AB.store.groups()
        val act = context as? Activity ?: return
        val names = groups.map { it.name }.toTypedArray()
        AlertDialog.Builder(act).setTitle("移动到").setItems(names) { _, w ->
            s.groupId = groups[w].id
            AB.store.save(s)
            rebuildList()
        }.show()
    }

    private fun createInGroup() {
        askText("新建脚本", "未命名脚本") { v ->
            val s = Script.blank(v)
            s.groupId = currentGroup
            AB.store.save(s)
            host.openScript(s)
        }
    }

    // ---------- 通用输入弹窗 ----------

    private fun askText(title: String, def: String, onOk: (String) -> Unit) {
        val act = context as? Activity ?: return
        val et = android.widget.EditText(act).apply {
            setText(def)
            setTextColor(Theme.textPri())
            setPadding(Display.dpInt(context, 16f), Display.dpInt(context, 12f),
                Display.dpInt(context, 16f), Display.dpInt(context, 12f))
        }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Display.dpInt(context, 20f), Display.dpInt(context, 12f),
                Display.dpInt(context, 20f), 0)
            addView(et)
        }
        AlertDialog.Builder(act).setTitle(title).setView(box)
            .setPositiveButton("确定") { d, _ -> onOk(et.text.toString()); d.dismiss() }
            .setNegativeButton("取消", null).show()
    }

    private fun confirm(msg: String, onOk: () -> Unit) {
        val act = context as? Activity ?: return
        AlertDialog.Builder(act).setMessage(msg)
            .setPositiveButton("确定") { d, _ -> onOk(); d.dismiss() }
            .setNegativeButton("取消", null).show()
    }

    fun refresh() { rebuildGroups(); rebuildList() }
}
