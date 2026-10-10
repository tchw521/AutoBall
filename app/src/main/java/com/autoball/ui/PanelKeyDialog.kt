package com.autoball.ui

import android.app.Activity
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.AB
import com.autoball.core.store.PanelKeyStore
import com.autoball.core.util.Display

/**
 * 悬浮窗自定义按键编辑器（R-118）。
 *
 * 此前只能从 6 套固定皮肤里选，按键种类与排列写死在 `FloatPanelView` 的
 * when 分支中——想"只留三个常用键"做不到。这里把按键变成可编辑列表。
 *
 * 全部用 Kit 组件拼装（常驻需求 R-001），不手搓行与按钮。
 */
object PanelKeyDialog {

    fun show(ctx: Activity, onChanged: () -> Unit) {
        val keys = PanelKeyStore.all().toMutableList()
        var cols = AB.store.getInt("panel_cols", 1).coerceIn(1, 4)
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        fun rebuild() {
            box.removeAllViews()

            box.addView(Kit.switchRow(ctx, "启用自定义布局",
                "启用后皮肤只作为初始模板，按键以本页为准",
                "⌨", Theme.pri(), PanelKeyStore.enabled()) {
                PanelKeyStore.setEnabled(it)
                rebuild()
            })

            box.addView(Kit.section(ctx, "排列"))
            box.addView(Kit.valueRow(ctx, "每行按键数", "当前 $cols 列（1 为纵向排列）",
                "▦", Theme.pri2()) {
                Ui.popMenu(ctx, box, listOf("1 列", "2 列", "3 列", "4 列"), cols - 1) { i ->
                    cols = i + 1
                    AB.store.putInt("panel_cols", cols)
                    com.autoball.float.FloatManager.refresh()
                    rebuild()
                }
            })

            box.addView(Kit.section(ctx, "按键（${keys.size}/${PanelKeyStore.MAX}）"))
            keys.forEachIndexed { i, k ->
                val sub = buildList {
                    add(PanelKeyStore.labelOf(k))
                    if (k.action == "SCRIPT") {
                        val n = runCatching { AB.store.get(k.scriptId)?.name }.getOrNull()
                        add(if (n == null) "脚本已删除" else "脚本：$n")
                    }
                }.joinToString(" · ")
                box.addView(Kit.valueRow(ctx, "${i + 1}. ${PanelKeyStore.labelOf(k)}", sub,
                    "●", Theme.G[k.color.coerceIn(0, Theme.G.size - 1)]) {
                    editKey(ctx, k, onChanged = { rebuild() }, onDelete = {
                        keys.removeAt(i)
                        rebuild()
                    })
                })
            }

            if (keys.size < PanelKeyStore.MAX) {
                box.addView(Kit.button(ctx, "＋ 添加按键", false) {
                    if (keys.size >= PanelKeyStore.MAX) {
                        Ui.toast(ctx, "最多 ${PanelKeyStore.MAX} 个按键")
                        return@button
                    }
                    keys.add(PanelKeyStore.Key())
                    rebuild()
                })
            }

            if (keys.isNotEmpty()) {
                box.addView(Kit.note(ctx,
                    "点某个按键可改动作、文案与颜色；长按可在列表里上下移动。"))
            }
        }
        rebuild()

        Ui.dialog(ctx, "自定义悬浮按键").body(box)
            .maxHeight(0.86f)
            .negative("恢复默认") {
                PanelKeyStore.save(PanelKeyStore.defaultKeys())
                Ui.toast(ctx, "已恢复默认按键")
                onChanged(); true
            }
            .positive("保存") {
                PanelKeyStore.save(keys)
                onChanged()
                Ui.toast(ctx, "已保存 ${keys.size} 个按键")
                true
            }
            .show()
    }

    /** 单个按键的编辑：动作、文案、颜色、脚本、删除 */
    private fun editKey(ctx: Activity, k: PanelKeyStore.Key,
                        onChanged: () -> Unit, onDelete: () -> Unit) {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val readers = ArrayList<() -> Unit>()

        // Kit.section 返回的是 TextView（不是容器），不能 addView——
        // 需要分区标题 + 输入框时必须自己包一层纵向容器
        fun textRow(label: String, hint: String, cur: String, set: (String) -> Unit) {
            val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            wrap.addView(Kit.section(ctx, label))
            val et = Ui.adText(ctx, cur, hint)
            wrap.addView(et)
            box.addView(wrap)
            readers.add { set(et.text.toString().trim()) }
        }

        box.addView(Kit.section(ctx, "动作"))
        box.addView(Kit.valueRow(ctx,
            PanelKeyStore.ACTION_IDS.firstOrNull { it.first == k.action }?.second ?: k.action,
            "点击可切换", "⌁", Theme.pri()) {
            Ui.popMenu(ctx, box, PanelKeyStore.ACTION_IDS.map { it.second },
                PanelKeyStore.ACTION_IDS.indexOfFirst { it.first == k.action }
                    .coerceAtLeast(0)) { i ->
                k.action = PanelKeyStore.ACTION_IDS[i].first
                onChanged()
            }
        })

        // 绑定脚本：只有 action=SCRIPT 时才需要选脚本，避免无关项干扰
        if (k.action == "SCRIPT") {
            val scripts = runCatching { AB.store.all() }.getOrDefault(emptyList())
            val names = scripts.map { it.name }
            box.addView(Kit.valueRow(ctx, "目标脚本",
                scripts.firstOrNull { it.id == k.scriptId }?.name ?: "未选择",
                "▷", Theme.ok()) {
                if (scripts.isEmpty()) {
                    Ui.toast(ctx, "还没有脚本可绑定")
                    return@valueRow
                }
                Ui.popMenu(ctx, box, names,
                    scripts.indexOfFirst { it.id == k.scriptId }.coerceAtLeast(0)) { i ->
                    k.scriptId = scripts[i].id
                    onChanged()
                }
            })
        }

        textRow("显示文案", "留空则用动作默认名称", k.label) { k.label = it }

        box.addView(Kit.section(ctx, "颜色"))
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, Display.dpInt(ctx, 6f), 0, Display.dpInt(ctx, 6f))
        }
        Theme.G.indices.forEach { i ->
            row.addView(Kit.colorDot(ctx, Theme.G[i], i == k.color) {
                k.color = i
                onChanged()
            })
        }
        box.addView(row)

        Ui.dialog(ctx, "编辑按键").body(box)
            .maxHeight(0.8f)
            .negative("删除本键") {
                onDelete()
                onChanged(); true
            }
            .positive("确定") {
                readers.forEach { runCatching { it() } }
                onChanged(); true
            }
            .show()
    }
}
