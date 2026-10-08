package com.autoball.ui

import android.app.Activity
import android.widget.EditText
import android.widget.LinearLayout
import com.autoball.core.store.TagStore

/**
 * 标签管理（R-102）：新建 / 重命名 / 删除，以及给单个脚本打标签。
 *
 * 删除与重命名会同步改所有脚本上的引用——否则改名后旧标签变成孤儿，
 * 脚本上挂着已不存在的标签，筛选时永远选不中。
 */
object TagDialog {

    /** 管理全部标签 */
    fun manage(ctx: android.content.Context, host: PageHost) {
        val act = ctx as? Activity ?: return
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

        fun fill() {
            box.removeAllViews()
            val list = TagStore.all()
            if (list.isEmpty()) {
                box.addView(Kit.hintBox(act, "还没有标签。点下方按钮新建，"
                    + "再在脚本的「⋯ 菜单 → 标签」里打上。"))
            }
            list.forEach { name ->
                box.addView(Kit.rowCard(act).apply {
                    addView(Kit.twoLine(act, name,
                        "${TagStore.scriptsOf(name).size} 个脚本"))
                    addView(Kit.miniBtn(act, "✎") { inputName(act, name) { n ->
                        TagStore.rename(name, n); fill(); host.refreshAll() } })
                    addView(Kit.miniBtn(act, "✕") {
                        TagStore.delete(name); fill(); host.refreshAll() })
                })
            }
            box.addView(Kit.button(act, "＋ 新建标签", true) {
                inputName(act, "") { n ->
                    TagStore.add(n); fill(); host.refreshAll()
                }
            })
        }
        fill()

        Ui.dialog(act, "标签管理").body(box)
            .width(Theme.DIALOG_W + 10f).maxHeight(0.78f)
            .negative("关闭") { }.show()
    }

    /** 给单个脚本打标签（多选） */
    fun editFor(ctx: android.content.Context, script: com.autoball.core.model.Script,
                onChanged: () -> Unit) {
        val act = ctx as? Activity ?: return
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val all = TagStore.all()
        if (all.isEmpty()) {
            box.addView(Kit.hintBox(act, "还没有标签，先在「我的 → 数据与日志 → 标签管理」里新建。"))
        }
        var checked = all.map { script.tags.contains(it) }.toMutableList()

        fun fill() {
            box.removeAllViews()
            all.forEachIndexed { i, name ->
                box.addView(Kit.switchRow(act, name, null, "🏷", Theme.pri2(),
                    checked[i]) { on ->
                    checked[i] = on
                    if (on) {
                        if (!script.tags.contains(name)) script.tags.add(name)
                    } else script.tags.remove(name)
                })
            }
            box.addView(Kit.button(act, "＋ 新建标签", true) {
                inputName(act, "") { n ->
                    TagStore.add(n)
                    script.tags.add(n)
                    all.toMutableList().add(n)
                    checked.add(true)
                    fill()
                }
            })
        }
        fill()

        Ui.dialog(act, "「${script.name}」的标签").body(box)
            .width(Theme.DIALOG_W + 10f).maxHeight(0.78f)
            .negative("取消") { onChanged() }
            .positive("确定") {
                com.autoball.AB.store.save(script)
                onChanged(); true
            }.show()
    }

    private fun inputName(act: Activity, init: String, onOk: (String) -> Unit) {
        val et = EditText(act).apply {
            setText(init); hint = "标签名"; setSingleLine(true); textSize = 13f
        }
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        box.addView(et)
        Ui.dialog(act, if (init.isEmpty()) "新建标签" else "重命名").body(box)
            .width(Theme.DIALOG_W)
            .negative("取消") { }
            .positive("确定") {
                val n = et.text.toString().trim()
                if (n.isEmpty()) { Ui.toast(act, "请输入名称"); false }
                else { onOk(n); true }
            }.show()
    }
}
