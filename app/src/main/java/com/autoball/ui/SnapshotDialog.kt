package com.autoball.ui

import android.app.Activity
import android.widget.LinearLayout
import com.autoball.AB
import com.autoball.core.model.Script
import com.autoball.core.store.SnapshotStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 脚本快照回滚（R-109）。
 *
 * 列出该脚本的历史版本，可预览步数后一键回滚。
 * 回滚前会**先给当前状态留一份快照**——否则回滚本身也变成不可逆操作，
 * 用户点错了就再也回不来了。
 */
object SnapshotDialog {

    fun show(act: Activity, script: Script, onRestored: (Script) -> Unit) {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

        fun fill() {
            box.removeAllViews()
            val list = SnapshotStore.of(script.id)
            if (list.isEmpty()) {
                box.addView(Kit.hintBox(act,
                    "还没有快照。\n修改脚本并保存后会自动留历史版本，"
                    + "最多保留 10 份。"))
                return
            }
            box.addView(Kit.note(act,
                "每脚本最多 10 份；1 分钟内的连续小改动会合并成一份，避免历史被冲稀。"))
            list.forEach { s ->
                val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                    .format(Date(s.ts))
                box.addView(Kit.rowCard(act).apply {
                    addView(Kit.twoLine(act, time, "${s.stepCount} 步 · ${s.name}"))
                    addView(Kit.miniBtn(act, "↺") {
                        restore(act, script, s.id, onRestored)
                    })
                    addView(Kit.miniBtn(act, "✕") {
                        SnapshotStore.delete(s.id); fill()
                    })
                })
            }
            box.addView(Kit.button(act, "清空全部快照", true) {
                SnapshotStore.clearFor(script.id); fill()
            })
        }
        fill()

        Ui.dialog(act, "历史版本（${SnapshotStore.count(script.id)}）")
            .body(box)
            .width(Theme.DIALOG_W + 10f).maxHeight(0.78f)
            .negative("关闭") { }
            .show()
    }

    private fun restore(act: Activity, current: Script, snapId: String,
                        onRestored: (Script) -> Unit) {
        val old = SnapshotStore.load(snapId)
        if (old == null) { Ui.toast(act, "快照已失效"); return }
        Ui.dialog(act, "回滚到此版本")
            .body(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                addView(Kit.note(act,
                    "回滚到「${old.name}」（${old.flow?.actions?.size ?: 0} 步）。\n"
                    + "当前状态会先留一份快照，所以回滚后还能再退回来。"))
            })
            .width(Theme.DIALOG_W)
            .negative("取消") { }
            .positive("回滚") {
                // 先给"当前"留快照，保证回滚可逆
                runCatching { SnapshotStore.snapshot(current) }
                val restored = old.apply {
                    id = current.id          // 保持同一个脚本身份
                    groupId = current.groupId
                    tags = current.tags
                    slot = current.slot
                    targetPkg = current.targetPkg
                    runCount = current.runCount
                }
                AB.store.save(restored)
                onRestored(restored)
                Ui.toast(act, "已回滚")
                true
            }.show()
    }
}
