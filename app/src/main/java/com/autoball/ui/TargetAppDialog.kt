package com.autoball.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.core.model.Script
import com.autoball.core.util.Display

/**
 * 目标应用选择（脚本字段 `targetPkg` 的编辑入口）。
 *
 * 作用：运行前自动唤起指定应用，避免脚本在桌面或别的应用上白跑一遍。
 * 设为「不限」则不干预当前界面。
 *
 * 列表在弹窗打开时异步加载（`queryIntentActivities` 涉及跨进程查询，
 * 放主线程会明显卡顿），加载期间显示占位。
 */
object TargetAppDialog {

    fun show(activity: Activity, script: Script, onChanged: () -> Unit) {
        val ctx = activity
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val dlg = Ui.dialog(ctx, "目标应用")
            .body(box)
            .width(Theme.DIALOG_W + 40f)
            .maxHeight(0.72f)
            .negative("关闭")
            .show()

        box.addView(TextView(ctx).apply {
            text = "加载中…"
            textSize = 12f
            setTextColor(Theme.textTer())
            gravity = Gravity.CENTER
            setPadding(0, Display.dpInt(ctx, 20f), 0, Display.dpInt(ctx, 20f))
        })

        Thread {
            val apps = runCatching { Display.launchableApps(ctx) }.getOrDefault(emptyList())
            ctx.runOnUiThread { fill(ctx, box, script, apps, onChanged, dlg) }
        }.apply { isDaemon = true }.start()
    }

    private fun fill(ctx: Activity, box: LinearLayout, script: Script,
                     apps: List<Pair<String, String>>, onChanged: () -> Unit,
                     dlg: android.app.Dialog) {
        box.removeAllViews()

        box.addView(TextView(ctx).apply {
            text = "运行前自动唤起所选应用；设为「不限」则不干预当前界面。"
            textSize = 10.5f
            setTextColor(Theme.textTer())
            setPadding(Display.dpInt(ctx, 2f), 0, Display.dpInt(ctx, 2f),
                Display.dpInt(ctx, 8f))
        })

        val cur = script.targetPkg
        box.addView(optionRow(ctx, "不限", "保持当前界面", cur == null) {
            script.targetPkg = null
            onChanged()
            dlg.dismiss()
        })

        val recent = com.autoball.AB.store.getString("recent_pkgs", "")
            .split("|").filter { it.isNotBlank() }.distinct().take(4)
        if (recent.isNotEmpty()) {
            box.addView(TextView(ctx).apply {
                text = "最近使用"
                textSize = 10.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.textSec())
                setPadding(Display.dpInt(ctx, 2f), Display.dpInt(ctx, 8f),
                    Display.dpInt(ctx, 2f), Display.dpInt(ctx, 4f))
            })
            recent.forEach { pkg ->
                box.addView(optionRow(ctx, Display.appLabel(ctx, pkg), pkg, cur == pkg) {
                    pick(ctx, script, pkg, onChanged)
                    dlg.dismiss()
                })
            }
        }

        box.addView(TextView(ctx).apply {
            text = "全部应用"
            textSize = 10.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textSec())
            setPadding(Display.dpInt(ctx, 2f), Display.dpInt(ctx, 8f),
                Display.dpInt(ctx, 2f), Display.dpInt(ctx, 4f))
        })
        apps.forEach { (pkg, label) ->
            box.addView(optionRow(ctx, label, pkg, cur == pkg) {
                pick(ctx, script, pkg, onChanged)
                dlg.dismiss()
            })
        }
    }

    private fun pick(ctx: Activity, script: Script, pkg: String, onChanged: () -> Unit) {
        script.targetPkg = pkg
        // 记入最近使用，最多 4 个
        val old = com.autoball.AB.store.getString("recent_pkgs", "")
            .split("|").filter { it.isNotBlank() }.toMutableList()
        old.remove(pkg)
        old.add(0, pkg)
        com.autoball.AB.store.putString("recent_pkgs",
            old.take(4).joinToString("|"))
        onChanged()
    }

    private fun optionRow(ctx: Activity, title: String, sub: String,
                          on: Boolean, onClick: () -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.rect(
                if (on) Theme.surface2() else Color.TRANSPARENT, 9f, ctx)
            setPadding(Display.dpInt(ctx, 9f), Display.dpInt(ctx, 7f),
                Display.dpInt(ctx, 9f), Display.dpInt(ctx, 7f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, Display.dpInt(ctx, 2f), 0, Display.dpInt(ctx, 2f))
            layoutParams = lp
            setOnClickListener { onClick() }

            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(ctx).apply {
                text = title
                textSize = 12.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (on) Theme.pri() else Theme.textPri())
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            col.addView(TextView(ctx).apply {
                text = sub
                textSize = 10f
                setTextColor(Theme.textTer())
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(col, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (on) addView(TextView(ctx).apply {
                text = "✓"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Theme.pri())
            })
        }
}
