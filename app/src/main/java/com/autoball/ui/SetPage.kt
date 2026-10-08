package com.autoball.ui

import android.app.Activity
import android.content.Context
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.AB
import com.autoball.core.engine.JsEngines
import com.autoball.core.util.Display

/**
 * 设置页（v3 #p-set）：**四分组** + 顶栏恢复默认按钮。
 *
 * 一比一对齐设计稿的 SGROUPS：
 * - 运行（6 项）：音量键控制 / 隐藏运行浮层 / 截图目录 / 来电暂停 / 防误触 / 结束回桌面
 * - 脚本权限（5 项）：网络 / 剪贴板 / 后台运行 / 忽略省电 / 模拟位置
 * - 编辑（4 项）：录制等待 / 吸附对齐 / 坐标网格 / 自动识别控件
 * - 通用（4 项）：流畅模式 / 降低毛玻璃 / 日志保留条数 / 主题
 *
 * 说明：权限类开关是**脚本运行时的行为约束**（是否允许脚本联网、读剪贴板等），
 * 不是 App 自身的权限申请——本工具本身不联网、不上报。
 */
class SetPage(context: Context, private val host: PageHost) : FrameLayout(context) {

    private val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** 截图目录 / 日志条数等文本项的输入弹窗 */
    private val SET_DEFAULTS = mapOf(
        "volKey" to true, "hideFloat" to false, "pauseCall" to true,
        "guardTouch" to true, "backHome" to false,
        "netPerm" to true, "clipPerm" to false, "bgRun" to true,
        "ignoreBattery" to true, "mockLoc" to false,
        "snapAlign" to true, "showGrid" to false, "autoFind" to false,
        "shotDir" to "/sdcard/AutoBall/shots/",
        "recWait" to "800ms", "logKeep" to "200 条"
    )

    init {
        val root = Kit.root(context)
        root.addView(Kit.topbar(context, "设置", "", listOf(
            Kit.themeBtn(context) { host.toggleTheme() },
            Kit.actionBtn(context, "↺") { resetDialog() }
        )).apply {
            setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 6f),
                Display.dpInt(context, 18f), Display.dpInt(context, 12f))
            addView(Kit.backBtn(context) { host.showPage(4) }, 0)
        })
        val sc = android.widget.ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        wrap.setPadding(0, 0, 0, Display.dpInt(context, 92f))
        sc.addView(wrap)
        root.addView(sc, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        render()
    }

    private fun render() {
        wrap.removeAllViews()

        // ================= 运行 =================
        // 合并原「运行」+「脚本权限」中真正影响执行的项：
        // 防误触、来电暂停、回桌面属于同一类"运行期行为"，不再各占一组
        wrap.addView(Kit.groupHead(context, "运行"))
        val g1 = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        g1.addView(sw("volKey", "音量键控制", "用音量上/下键快速开始与停止脚本运行",
            "🔊", Theme.pri2()))
        g1.addView(sw("hideFloat", "隐藏运行浮层", "运行时不显示悬浮控制球，界面更干净",
            "◌", Theme.pri()))
        g1.addView(sw("pauseCall", "来电时自动暂停", "通话接通即暂停，挂断后可手动继续",
            "☏", Theme.ok()))
        g1.addView(sw("backHome", "结束后回到桌面", "运行完成自动按 HOME",
            "⌂", Theme.textTer()))
        g1.addView(sw("ignoreBattery", "忽略省电优化", "避免系统休眠杀掉脚本进程",
            "⚡", Theme.warn()))
        wrap.addView(Kit.settingCard(context, g1))

        // ================= 脚本 =================
        // 合并原「编辑」+「脚本权限」中属于脚本能力的项
        wrap.addView(Kit.groupHead(context, "脚本"))
        val g2 = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        g2.addView(pick("recWait", "录制等待时间", "录制后每个动作默认补的等待时长",
            "◷", Theme.pri2(), listOf("0ms", "300ms", "800ms", "1500ms")))
        g2.addView(sw("netPerm", "允许脚本联网", "脚本运行时可发起网络请求",
            "◎", Theme.pri2()))
        g2.addView(sw("clipPerm", "允许读剪贴板", "脚本可读取系统剪贴板内容",
            "▤", Theme.pri()))
        g2.addView(txt("shotDir", "截图保存目录", "运行与监听动作的截图保存位置",
            "▣", Theme.warn()))
        wrap.addView(Kit.settingCard(context, g2))

        // ================= 界面与性能 =================
        // 主题、流畅模式、毛玻璃、日志条数：都是"影响观感/开销"的项
        wrap.addView(Kit.groupHead(context, "界面与性能"))
        val g3 = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        g3.addView(Kit.switchRow(context, "深色主题", "跟随设计稿双主题",
            "◐", Theme.pri(), Theme.isDark()) { host.toggleTheme() })
        g3.addView(Kit.switchRow(context, "流畅模式",
            "关闭液态流动与循环动画，降低掉帧",
            "⚡", Theme.ok(), Perf.perf()) {
            Perf.setPerf(it)
            host.recreateUi()
        })
        g3.addView(Kit.switchRow(context, "降低毛玻璃模糊",
            "用纯半透明代替实时背景模糊，低端机提升明显",
            "◍", Theme.pri2(), Perf.lowBlur()) {
            Perf.setLowBlur(it)
            host.recreateUi()
        })
        g3.addView(pick("logKeep", "日志保留条数", "超出后自动丢弃最早的记录",
            "≡", Theme.warn(), listOf("100 条", "200 条", "500 条", "1000 条")))
        wrap.addView(Kit.settingCard(context, g3))

        // ================= 数据与关于 =================
        wrap.addView(Kit.groupHead(context, "数据与关于"))
        val g4 = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        g4.addView(Kit.valueRow(context, "分组管理", "新建 / 重命名 / 换色 / 删除分组",
            "◫", Theme.pri()) { host.showPage(0) })
        g4.addView(Kit.valueRow(context, "导入脚本", "从分享码导入",
            "⤓", Theme.ok()) {
            val act = context as? android.app.Activity ?: return@valueRow
            ShareImportDialog.show(act, host)
        })
        g4.addView(Kit.valueRow(context, "导出全部", "把所有脚本导出为分享码",
            "⤒", Theme.pri2()) { exportAll() })
        g4.addView(Kit.valueRow(context, "运行日志", "查看每一步的执行结果",
            "≡", Theme.warn()) { host.openSubPage("log") })
        g4.addView(Kit.valueRow(context, "悬浮设置", "悬浮球 / 悬浮窗 / 手势绑定",
            "◉", Theme.pri()) { host.openSubPage("float") })
        g4.addView(Kit.valueRow(context, "版本", "v1.10.0 · QuickJS / Rhino 双构建",
            "ⓘ", Theme.pri2()) {
            val act = context as? android.app.Activity ?: return@valueRow
            ChangeLog.show(act)
        })
        wrap.addView(Kit.settingCard(context, g4))

        wrap.addView(Kit.tip(context,
            "所有设置立即生效并本地保存，不会上传。顶栏「↺」可恢复默认（主题保持不变）。"))
    }

    // ---------- 行构造 ----------

    private fun sw(key: String, name: String, desc: String, icon: String, color: Int) =
        Kit.switchRow(context, name, desc, icon, color,
            AB.store.getBool(key, SET_DEFAULTS[key] as? Boolean ?: false)) {
            AB.store.putBool(key, it)
        }

    private fun txt(key: String, name: String, desc: String, icon: String, color: Int) =
        Kit.valueRow(context, name, desc, icon, color,
            AB.store.getString(key, SET_DEFAULTS[key] as? String ?: "")) {
            textDialog(key, name)
        }

    private fun pick(key: String, name: String, desc: String, icon: String, color: Int,
                     opts: List<String>) =
        Kit.valueRow(context, name, desc, icon, color,
            AB.store.getString(key, SET_DEFAULTS[key] as? String ?: opts[0])) {
            val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            opts.forEach { o ->
                box.addView(Ui.sheetOption(context, "▸", color, o, "") {
                    AB.store.putString(key, o)
                    render()
                })
            }
            Ui.sheet(context as? Activity ?: return@valueRow, name).body(box).show()
        }

    private fun textDialog(key: String, title: String) {
        val act = context as? Activity ?: return
        val et = android.widget.EditText(act).apply {
            setText(AB.store.getString(key, SET_DEFAULTS[key] as? String ?: ""))
            setTextColor(Theme.textPri())
            textSize = 13f
            setSingleLine(true)
        }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Display.dpInt(act, 20f), Display.dpInt(act, 12f),
                Display.dpInt(act, 20f), 0)
            addView(et)
        }
        Ui.dialog(act, title).body(box)
            .negative("取消")
            .positive("确定") {
                AB.store.putString(key, et.text.toString().trim())
                render()
                true
            }.show()
    }

    /** 导出全部脚本为分享码 */
    private fun exportAll() {
        val act = context as? android.app.Activity ?: return
        val all = AB.store.all()
        if (all.isEmpty()) {
            Ui.toast(act, "还没有脚本可导出")
            return
        }
        runCatching {
            val code = com.autoball.core.store.ShareCode.encodeAll(all)
            ShareImportDialog.showCopy(act, "全部脚本（${all.size} 个）", code)
        }.onFailure {
            Ui.toast(act, "导出失败：${it.message}")
        }
    }

    /** 恢复默认：主题保持用户当前选择，其余全部回退 */
    private fun resetDialog() {
        val act = context as? Activity ?: return
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(act).apply {
            text = "恢复所有设置为默认值？主题与已保存的脚本不受影响。"
            textSize = 12.5f
            setTextColor(Theme.textSec())
            setLineSpacing(Display.dp(act, 2f), 1.6f)
        })
        Ui.dialog(act, "恢复默认设置").body(box)
            .negative("取消")
            .positiveDanger("恢复") {
                SET_DEFAULTS.forEach { (k, v) ->
                    when (v) {
                        is Boolean -> AB.store.putBool(k, v)
                        is String -> AB.store.putString(k, v)
                    }
                }
                Perf.resetDefaults()
                Ui.toast(act, "已恢复默认设置（主题保持不变）")
                host.recreateUi()
                true
            }.show()
    }
}
