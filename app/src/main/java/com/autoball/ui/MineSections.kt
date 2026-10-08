package com.autoball.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.autoball.AB
import com.autoball.core.engine.JsEngines
import com.autoball.core.log.CrashGuard
import com.autoball.core.util.Display
import com.autoball.float.FloatManager
import com.autoball.service.AutoBallAccessibilityService
import com.autoball.service.FloatingService
import com.autoball.service.ShizukuClient

/**
 * 「我的」页的四个二级分类页。
 *
 * 一级页面只保留四个分类按钮，具体条目全部下沉到这里——
 * 原先二十多项全堆在一级页，找一项要滚很久。
 *
 * 四个分类：
 * - [perm] 执行授权：无障碍 / Shizuku / 悬浮窗 / 后台保活
 * - [disp] 悬浮与显示：主题 / 悬浮球 / 悬浮窗
 * - [data] 数据与日志：分组 / 导入导出 / 运行日志
 * - [about] 关于与合规：版本 / 更新日志 / 崩溃日志
 *
 * 行、卡片、滑块一律走 [Kit] 统一组件。
 */
object MineSections {

    /**
     * 二级页骨架：状态栏 + 子栏 + 可滚动内容区。
     * 返回 (根视图, 内容容器)——不用 View.setTag 传容器，
     * 因为 setTag(int) 的 key 必须是合法资源 id，随意取值有兼容风险。
     */
    private fun shell(ctx: Context, title: String,
                      host: PageHost): Pair<LinearLayout, LinearLayout> {
        val root = Kit.root(ctx)
        root.addView(Kit.statusBar(ctx))
        root.addView(Kit.subbar(ctx, title, onBack = { host.showPage(4) }))
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val sc = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false }
        sc.addView(body)
        root.addView(sc, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root to body
    }

    // ================= 执行授权 =================

    fun perm(ctx: Context, host: PageHost): View {
        val (root, b) = shell(ctx, "执行授权", host)
        b.addView(Kit.note(ctx,
            "两种方式任选其一即可运行脚本；两者都开启时按动作能力自动择优，失败会自动切换。"))

        val a11yOn = Display.accessibilityEnabled(ctx)
        b.addView(permRow(ctx,
            title = "无障碍服务",
            sub = if (a11yOn) "已开启 · 支持点击/滑动/控件节点/截图"
            else "未开启 · 最通用的执行通道",
            on = a11yOn,
            action = "去开启"
        ) { AutoBallAccessibilityService.openAccessibilitySettings(ctx) })

        val shizukuOn = ShizukuClient.instance.isInstalled() &&
                ShizukuClient.instance.isAuthorized()
        b.addView(permRow(ctx,
            title = "Shizuku 授权",
            sub = when {
                !ShizukuClient.instance.isInstalled() -> "未安装 Shizuku · 不影响无障碍通道"
                shizukuOn -> "已授权 · 支持按键/文本输入/原生取屏"
                else -> "已安装但未授权 · 请在 Shizuku 中允许"
            },
            on = shizukuOn,
            action = "重新检测"
        ) {
            Thread {
                ShizukuClient.instance.probe()
                AB.log.info("mine", "Shizuku 检测结果：" +
                    (ShizukuClient.instance.lastError ?: "无错误"))
                (ctx as? Activity)?.runOnUiThread { host.refreshAll() }
            }.apply { isDaemon = true }.start()
        })

        val overlayOn = Display.canDrawOverlay(ctx)
        b.addView(permRow(ctx,
            title = "悬浮窗权限",
            sub = if (overlayOn) "已授予 · 悬浮球与控制窗可用" else "未授予 · 悬浮球无法显示",
            on = overlayOn,
            action = "去开启"
        ) { Display.openOverlaySettings(ctx) })

        b.addView(Kit.groupHead(ctx, "后台运行"))
        val g = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        g.addView(Kit.note(ctx,
            "保活只能降低被回收的频率，不能承诺不被系统杀死。建议在系统设置中允许后台运行。"))
        g.addView(Kit.valueRow(ctx, "前往应用详情", "设置自启动与后台权限",
            "↗", Theme.pri()) { AutoBallAccessibilityService.openAppSettings(ctx) })
        if (Build.VERSION.SDK_INT >= 23) {
            g.addView(Kit.valueRow(ctx, "忽略电池优化", "需系统授权，减少休眠被杀",
                "⚡", Theme.warn()) {
                runCatching {
                    ctx.startActivity(Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:" + ctx.packageName)))
                }
            })
        }
        b.addView(g)
        return wrap(root)
    }

    // ================= 悬浮与显示 =================

    fun disp(ctx: Context, host: PageHost): View {
        val (root, b) = shell(ctx, "悬浮与显示", host)

        b.addView(Kit.groupHead(ctx, "外观"))
        val g0 = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        g0.addView(Kit.switchRow(ctx, "深色主题", init = Theme.isDark()) { v ->
            Theme.setDark(v)
            host.refreshAll()
        })
        b.addView(Kit.settingCard(ctx, g0))

        b.addView(Kit.groupHead(ctx, "悬浮球"))
        val g1 = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        g1.addView(Kit.switchRow(ctx, "悬浮球常驻",
            init = AB.store.getBool("float_persistent", true)) { v ->
            AB.store.putBool("float_persistent", v)
            if (v) FloatingService.start(ctx) else FloatManager.hideAll()
        })
        g1.addView(Kit.switchRow(ctx, "悬浮球自动贴边",
            init = AB.store.getBool("ball_snap_edge", true)) { v ->
            AB.store.putBool("ball_snap_edge", v)
        })
        g1.addView(Kit.sliderRow(ctx, "悬浮球大小",
            AB.store.getFloat("ball_size_dp", 48f), 36f, 64f, "dp") { v ->
            AB.store.putFloat("ball_size_dp", v)
            FloatManager.hideBall(); FloatManager.showBall(ctx)
        })
        g1.addView(Kit.sliderRow(ctx, "闲置透明度",
            AB.store.getFloat("ball_idle_alpha", 0.72f) * 100f, 30f, 100f, "%") { v ->
            AB.store.putFloat("ball_idle_alpha", v / 100f)
            FloatManager.hideBall(); FloatManager.showBall(ctx)
        })
        b.addView(Kit.settingCard(ctx, g1))

        b.addView(Kit.groupHead(ctx, "悬浮窗"))
        val g2 = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        g2.addView(Kit.valueRow(ctx, "悬浮窗设置", "皮肤 / 手势绑定 / 按钮尺寸",
            "◉", Theme.pri()) { host.openSubPage("float") })
        b.addView(Kit.settingCard(ctx, g2))
        return wrap(root)
    }

    // ================= 数据与日志 =================

    fun data(ctx: Context, host: PageHost): View {
        val (root, b) = shell(ctx, "数据与日志", host)

        b.addView(Kit.groupHead(ctx, "脚本数据"))
        val g1 = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        g1.addView(Kit.valueRow(ctx, "分组管理", "新建 / 重命名 / 换色 / 删除分组",
            "◫", Theme.pri()) { host.showPage(0) })
        g1.addView(Kit.valueRow(ctx, "导入脚本", "从分享码导入",
            "⤓", Theme.ok()) {
            val act = ctx as? Activity ?: return@valueRow
            ShareImportDialog.show(act, host)
        })
        g1.addView(Kit.valueRow(ctx, "导出全部", "把全部脚本导出为分享码",
            "⤒", Theme.pri2()) { exportAll(ctx) })
        b.addView(Kit.settingCard(ctx, g1))

        b.addView(Kit.groupHead(ctx, "日志"))
        val g2 = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        g2.addView(Kit.valueRow(ctx, "运行日志", "查看每一步的执行结果",
            "≡", Theme.warn()) { host.openSubPage("log") })
        b.addView(Kit.settingCard(ctx, g2))

        b.addView(Kit.note(ctx, "运行日志只记录动作类型、执行后端、耗时与结果；" +
            "不记录输入文本、控件文本与分享码原文。"))
        return wrap(root)
    }

    // ================= 关于与合规 =================

    fun about(ctx: Context, host: PageHost): View {
        val (root, b) = shell(ctx, "关于", host)

        b.addView(Kit.groupHead(ctx, "应用"))
        val g1 = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        g1.addView(Kit.valueRow(ctx, "脚本引擎", JsEngines.engineName() +
            if (JsEngines.engineName() == "quickjs") "（未内置源码时自动降级）" else "",
            "⚙", Theme.pri2()) { })
        g1.addView(Kit.valueRow(ctx, "版本", "v1.16.0", "ⓘ", Theme.pri2()) {
            val act = ctx as? Activity ?: return@valueRow
            ChangeLog.show(act)
        })
        g1.addView(Kit.valueRow(ctx, "更新日志", "查看", "≡", Theme.ok()) {
            val act = ctx as? Activity ?: return@valueRow
            ChangeLog.show(act)
        })
        b.addView(Kit.settingCard(ctx, g1))

        b.addView(Kit.groupHead(ctx, "诊断"))
        val g2 = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        g2.addView(Kit.valueRow(ctx, "崩溃日志",
            if (CrashGuard.hasSavedCrash()) "有记录 · 点击查看" else "无记录",
            "⚠", Theme.danger()) { showCrash(ctx) })
        b.addView(Kit.settingCard(ctx, g2))

        b.addView(Kit.note(ctx, ctx.getString(com.autoball.R.string.compliance_notice)))
        return wrap(root)
    }

    // ---------- 复用件 ----------

    private fun wrap(root: LinearLayout): View {
        val f = FrameLayout(root.context)
        f.addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT))
        return f
    }

    /** 权限行：左侧状态点 + 主副标题 + 右侧状态/动作按钮 */
    private fun permRow(ctx: Context, title: String, sub: String, on: Boolean,
                        action: String, onClick: () -> Unit): LinearLayout {
        val row = Kit.rowCard(ctx)
        row.addView(View(ctx).apply {
            background = Theme.oval(if (on) Theme.ok() else Theme.danger())
            layoutParams = LinearLayout.LayoutParams(Display.dpInt(ctx, 10f),
                Display.dpInt(ctx, 10f))
        })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(ctx).apply {
            text = title
            textSize = 13.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.textPri())
        })
        col.addView(TextView(ctx).apply {
            text = sub
            textSize = 11f
            setTextColor(Theme.textSec())
            setPadding(0, Display.dpInt(ctx, 2f), 0, 0)
        })
        row.addView(col, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = Display.dpInt(ctx, 10f)
            marginEnd = Display.dpInt(ctx, 8f)
        })
        row.addView(TextView(ctx).apply {
            text = action
            textSize = 11.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(if (on) Theme.textTer() else Theme.pri())
            gravity = Gravity.CENTER
            background = Theme.rect(Theme.surface2(), 9f, ctx, Theme.line())
            setPadding(Display.dpInt(ctx, 10f), Display.dpInt(ctx, 6f),
                Display.dpInt(ctx, 10f), Display.dpInt(ctx, 6f))
            setOnClickListener { onClick() }
        })
        return row
    }

    private fun exportAll(ctx: Context) {
        val all = AB.store.all()
        if (all.isEmpty()) {
            Ui.toast(ctx, "还没有脚本可导出")
            return
        }
        runCatching {
            val code = com.autoball.core.store.ShareCode.encodeAll(all)
            val act = ctx as? Activity
            if (act != null) ShareImportDialog.showCopy(act, "全部脚本（${all.size} 个）", code)
            else Ui.toast(ctx, "请在应用内导出")
        }.onFailure { Ui.toast(ctx, "导出失败：${it.message}") }
    }

    private fun showCrash(ctx: Context) {
        val act = ctx as? Activity ?: return
        android.app.AlertDialog.Builder(act)
            .setTitle("崩溃日志")
            .setMessage(if (CrashGuard.hasSavedCrash())
                CrashGuard.savedCrash().take(6000) else "暂无崩溃记录")
            .setPositiveButton("复制并清空") { d, _ ->
                runCatching {
                    val cm = act.getSystemService(Context.CLIPBOARD_SERVICE)
                            as? android.content.ClipboardManager
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText(
                        "autoball-crash", CrashGuard.savedCrash()))
                }
                CrashGuard.clear()
                d.dismiss()
            }
            .setNegativeButton("关闭", null)
            .show()
    }
}
