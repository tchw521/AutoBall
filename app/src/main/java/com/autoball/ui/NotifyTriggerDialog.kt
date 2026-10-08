package com.autoball.ui

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.AB
import com.autoball.core.model.Script
import com.autoball.core.util.Display
import com.autoball.service.NotifyService

/**
 * 消息触发配置（复刻自动精灵「收到指定消息时启动脚本」）。
 *
 * 三个条件：来源应用 + 关键词 + 开关。都留空表示"任意应用收到任意消息都触发"——
 * 这很容易误触发，所以 UI 上明确提示。
 *
 * 前置：需用户在系统设置授予「通知使用权」。未授权时给跳转入口。
 */
object NotifyTriggerDialog {

    fun show(act: Activity, s: Script, onSaved: () -> Unit) {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

        val granted = NotifyService.isEnabled()
        if (!granted) {
            box.addView(Kit.note(act,
                "尚未授予「通知使用权」，消息触发不会生效。点击下方按钮前往系统设置开启。", 0f))
            box.addView(Kit.button(act, "前往开启通知使用权", true) {
                runCatching {
                    act.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    )
                }
            })
            box.addView(Kit.note(act, "", 6f))
        }

        box.addView(Kit.section(act, "触发条件"))
        box.addView(Kit.switchRow(act, "启用消息触发",
            "收到匹配的消息时自动运行本脚本", "✉", Theme.warn(), s.notifyEnabled) {
            s.notifyEnabled = it
        })

        // 来源应用
        var pkg = s.notifyPkg
        box.addView(Kit.valueRow(act, "来源应用",
            if (pkg.isEmpty()) "任意应用" else appLabel(act, pkg), "📱", Theme.pri2(),
            if (pkg.isEmpty()) "任意应用" else appLabel(act, pkg)) {
            pickApp(act) { p -> pkg = p }
        })

        // 关键词
        val kwEt = android.widget.EditText(act).apply {
            setText(s.notifyKeyword)
            hint = "留空 = 任意消息"
            setSingleLine(true)
            textSize = 13f
            setPadding(Display.dpInt(act, 12f), Display.dpInt(act, 10f),
                Display.dpInt(act, 12f), Display.dpInt(act, 10f))
        }
        val kwBox = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(act).apply {
                text = "关键词"
                textSize = 11f
                setTextColor(Theme.textTer())
                setPadding(Display.dpInt(act, 12f), Display.dpInt(act, 2f), 0, 0)
            })
            addView(kwEt)
        }
        box.addView(kwBox)

        box.addView(Kit.note(act,
            "运行时会注入两个变量：\$notifyPkg（来源包名）、\$notifyText（消息正文），" +
            "可在脚本里用运行条件判断是否继续。", 6f))
        box.addView(Kit.note(act,
            "注意：来源与关键词都留空会被任意应用的任意消息触发，容易误跑。", 0f))

        Ui.dialog(act, "消息触发")
            .body(box)
            .width(Theme.DIALOG_W + 20f)
            .maxHeight(0.78f)
            .negative("取消") { }
            .positive("确定") {
                s.notifyPkg = pkg
                s.notifyKeyword = kwEt.text.toString().trim()
                AB.store.save(s)
                onSaved()
                true
            }.show()
    }

    private fun appLabel(ctx: android.content.Context, pkg: String): String {
        return runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    private fun pickApp(act: Activity, onPick: (String) -> Unit) {
        val apps = runCatching {
            val pm = act.packageManager
            val it = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(it, 0)
                .mapNotNull { it.activityInfo }
                .distinctBy { it.packageName }
                .sortedBy { pm.getApplicationLabel(it.applicationInfo).toString() }
        }.getOrDefault(emptyList())
        if (apps.isEmpty()) { Ui.toast(act, "未获取到应用列表"); return }

        val pm = act.packageManager
        val labels = listOf("任意应用") + apps.map {
            pm.getApplicationLabel(it.applicationInfo).toString()
        }
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        labels.forEachIndexed { i, lb ->
            box.addView(TextView(act).apply {
                text = lb
                textSize = 13f
                setTextColor(Theme.textPri())
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Display.dpInt(act, 12f), Display.dpInt(act, 10f),
                    Display.dpInt(act, 12f), Display.dpInt(act, 10f))
                background = Theme.rect(Theme.surface(), 10f, act, Theme.line())
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, Display.dpInt(act, 3f), 0, Display.dpInt(act, 3f))
                layoutParams = lp
                setOnClickListener {
                    onPick(if (i == 0) "" else apps[i - 1].packageName)
                    Ui.toast(act, "已选择：$lb")
                }
            })
        }
        Ui.dialog(act, "选择来源应用").body(box).negative("关闭") { }.show()
    }
}
