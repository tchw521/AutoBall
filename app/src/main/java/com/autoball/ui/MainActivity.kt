package com.autoball.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.AB
import com.autoball.R
import com.autoball.core.log.CrashGuard
import com.autoball.core.model.Script
import com.autoball.core.util.Display
import com.autoball.float.FloatManager
import com.autoball.service.FloatingService

/** 页面与 Activity 之间的最小契约（无 Fragment / 无导航组件） */
interface PageHost {
    fun context(): Context
    fun showPage(index: Int)
    fun openScript(script: Script)
    /** 打开脚本并定位到指定动作（运行日志跳转失败步骤） */
    fun openScriptAt(script: Script, actionId: String?)
    fun openCreate()
    fun refreshAll()
    fun startRecording()
    fun runScript(script: Script)
    fun toggleTheme()
    /** 打开二级页：float（悬浮设置）/ log（运行日志）/ js（JS 脚本）/ set（设置） */
    fun openSubPage(key: String)
    /** 重建整个界面（切换性能模式等影响全局绘制的设置时用） */
    fun recreateUi()
    /** 导出脚本为 .aball 文件（系统「保存到…」选择器） */
    fun exportFile()
    /** 从 .aball 文件导入 */
    fun importFile()
    /** 导出单个脚本为 .aball 文件 */
    fun exportScriptFile(script: Script)
}

class MainActivity : Activity(), PageHost, com.autoball.NotifyHost {

    companion object {
        private const val REQ_EXPORT = 9001
        private const val REQ_IMPORT = 9002
    }

    private lateinit var content: FrameLayout
    private lateinit var nav: LiquidNavView
    private val pages = arrayOfNulls<View>(5)
    private var current = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (Theme.isDark()) R.style.Theme_AutoBall else R.style.Theme_AutoBall_Light)
        super.onCreate(savedInstanceState)
        Perf.init(this)
        // Android 12+：窗口级背景模糊，为液态导航提供真实折射来源；
        // 低版本或开启「降低毛玻璃」时不启用——实时模糊在低端机上开销很高
        if (Build.VERSION.SDK_INT >= 31 && !Perf.lowBlur()) {
            runCatching { window.setBackgroundBlurRadius(32) }
        }
        buildUi()
        showPage(0)
        // 上次闪退的堆栈：真机拿不到 logcat，这里主动展示便于定位
        CrashGuard.showIfSaved(this)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            // 让中央按钮顶出导航上沿的部分不被裁剪
            clipChildren = false
            clipToPadding = false
        }

        content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(content)

        val navWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                // 脱离屏幕底边 14dp
                setMargins(Display.dpInt(this@MainActivity, 12f), 0,
                    Display.dpInt(this@MainActivity, 12f), Display.dpInt(this@MainActivity, 14f))
            }
            clipChildren = false
            clipToPadding = false
        }
        nav = LiquidNavView(this, { idx -> showPage(idx) }, { openCreate() }).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                Display.dpInt(this@MainActivity, LiquidNavView.heightDp()))
        }
        navWrap.addView(nav)
        root.addView(navWrap)

        // 底部版本条（v3 .verbar）：点一下看更新日志
        root.addView(Ui.versionBar(this, "v1.21.0", "查看更新日志") {
            ChangeLog.show(this)
        })

        setContentView(root, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun showPage(index: Int) {
        current = index
        content.removeAllViews()
        // 单个页面构建失败不应拖垮整个 App：降级为可读的错误页，
        // 配合 CrashGuard 已落盘的堆栈定位问题
        val v = runCatching {
            pages[index] ?: createPage(index).also { pages[index] = it }
        }.getOrElse { err ->
            pages[index] = null
            CrashGuard.report("构建页面 $index 失败", err)
            errorPage(index, err)
        }
        content.addView(v, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        nav.select(index)
    }

    private fun errorPage(index: Int, err: Throwable): View = TextView(this).apply {
        text = "「${LiquidNavView.TABS[index]}」页加载失败\n\n${err.javaClass.simpleName}: " +
            (err.message ?: "") + "\n\n详情已写入崩溃日志，可在「我的」页查看或重启后弹窗查看。"
        textSize = 13f
        setTextColor(0xFFFF6B7A.toInt())
        gravity = Gravity.CENTER
        setPadding(48, 48, 48, 48)
    }

    private fun createPage(index: Int): View = when (index) {
        0 -> ScriptPage(this, this)
        1 -> EditPage(this, this)
        2 -> CreatePage(this, this)
        3 -> MarketPage(this, this)
        else -> MinePage(this, this)
    }

    override fun context(): Context = this

    override fun openScript(script: Script) {
        val page = EditPage(this, this)
        page.bind(script)
        pages[1] = page
        showPage(1)
    }

    /** 中央四角星：直接弹出「新建脚本」底部半框，不再跳一个空页面 */
    override fun openCreate() { NewScriptSheet.show(this, this) }

    override fun refreshAll() {
        for (i in pages.indices) pages[i] = null
        val idx = current
        content.removeAllViews()
        showPage(idx)
    }

    // ---------- 脚本文件导入导出（R-105） ----------

    private var pendingWrite: ((android.net.Uri) -> String?)? = null

    override fun exportFile() {
        val all = AB.store.all()
        if (all.isEmpty()) { Ui.toast(this, "还没有脚本可导出"); return }
        val (intent, writer) = com.autoball.core.store.ScriptFile.exportIntent(all)
        pendingWrite = writer
        runCatching { startActivityForResult(intent, REQ_EXPORT) }
            .onFailure { Ui.toast(this, "无法打开文件选择器") }
    }

    override fun exportScriptFile(script: Script) {
        val (intent, writer) =
            com.autoball.core.store.ScriptFile.exportIntent(listOf(script))
        pendingWrite = writer
        runCatching { startActivityForResult(intent, REQ_EXPORT) }
            .onFailure { Ui.toast(this, "无法打开文件选择器") }
    }

    override fun importFile() {
        runCatching {
            startActivityForResult(
                com.autoball.core.store.ScriptFile.importIntent(), REQ_IMPORT)
        }.onFailure { Ui.toast(this, "无法打开文件选择器") }
    }

    @Deprecated("不使用 androidx 的结果 API；沿用平台回调以保持零依赖")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) { pendingWrite = null; return }
        val uri = data?.data
        if (uri == null) { pendingWrite = null; return }
        when (requestCode) {
            REQ_EXPORT -> {
                val err = pendingWrite?.invoke(uri)
                pendingWrite = null
                Ui.toast(this, err ?: "已导出")
            }
            REQ_IMPORT -> {
                val err = com.autoball.core.store.ScriptFile.import(this, uri) { list ->
                    Ui.toast(this, "已导入 ${list.size} 个脚本")
                    for (i in pages.indices) pages[i] = null
                    content.removeAllViews()
                    buildUi()
                }
                if (err != null) Ui.toast(this, err)
            }
        }
    }

    override fun recreateUi() {
        for (i in pages.indices) pages[i] = null
        content.removeAllViews()
        buildUi()
        showPage(current)
    }

    override fun openScriptAt(script: Script, actionId: String?) {
        showPage(1)
        (pages[1] as? EditPage)?.let {
            it.bind(script)
            if (actionId != null) it.focusStep(actionId)
        }
    }

    override fun runScript(script: Script) {
        // 统一入口：与悬浮球手势一致，全局单实例、重复触发转为停止
        if (com.autoball.core.engine.ScriptLauncher.isRunning()) {
            com.autoball.core.engine.ScriptLauncher.stop()
            Ui.toast(this, "已停止")
            return
        }
        Ui.toast(this, "开始运行「${script.name}」")
        com.autoball.core.engine.ScriptLauncher.launch(this, script)
    }

    /**
     * 消息触发入口：由 NotifyService 在收到匹配通知时调用。
     * 会把来源包名与消息正文注入为 $notifyPkg / $notifyText 供脚本使用。
     */
    override fun runScriptWithVars(s: Script, vars: Map<String, String>) {
        runOnUiThread {
            if (com.autoball.core.engine.ScriptLauncher.isRunning()) {
                AB.log.warn("notify", "已有脚本在运行，忽略本次触发")
                return@runOnUiThread
            }
            Ui.toast(this, "消息触发：${s.name}")
            com.autoball.core.engine.ScriptLauncher.launch(this, s, vars)
        }
    }

    override fun toggleTheme() {
        Theme.toggleDark()
        subPage = null
        for (i in pages.indices) pages[i] = null
        recreate()
    }

    @Volatile
    private var subPage: String? = null

    override fun openSubPage(key: String) {
        subPage = key
        showSub(key)
    }

    private fun showSub(key: String) {
        val v = runCatching {
            when (key) {
                "float" -> FloatSetPage(this, this)
                "perm" -> MineSections.perm(this, this)
                "disp" -> MineSections.disp(this, this)
                "data" -> MineSections.data(this, this)
                "about" -> MineSections.about(this, this)
                "log" -> LogPage(this, this)
                // 定时计划日历（R-119）
                "schedule" -> ScheduleBoard(this) { sc ->
                    // sc 是 store 缓存里的共享对象；编辑前必须取副本，
                    // 否则原地修改会污染缓存并让 save() 的"保存前快照"失效（见 v1.28）
                    val fresh = com.autoball.AB.store.get(sc.id)?.copy() ?: return@ScheduleBoard
                    ScheduleDialog.show(this@MainActivity, fresh) {
                        com.autoball.AB.store.save(fresh)
                        showPage(0)
                    }
                }
                "stat" -> StatPage(this, this)
                "set" -> SetPage(this, this)
                "js" -> {
                    val p = JsPage(this@MainActivity, this@MainActivity)
                    com.autoball.AB.store.all()
                        .firstOrNull { sc -> sc.kind == com.autoball.core.model.ScriptKind.JS }
                        ?.let { sc -> p.bind(sc) }
                    p
                }
                else -> error("未知页面：$key")
            }
        }.getOrElse { err ->
            CrashGuard.report("构建二级页 $key 失败", err)
            TextView(this).apply {
                text = "页面加载失败：${err.message}"
                textSize = 13f
                setTextColor(0xFFFF6B7A.toInt())
                gravity = Gravity.CENTER
            }
        }
        content.removeAllViews()
        content.addView(v, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    override fun startRecording() {
        // 录制需要悬浮窗权限以显示采集层
        if (!Display.canDrawOverlay(this)) {
            Display.openOverlaySettings(this)
            return
        }
        FloatingService.start(this)
        CreatePage.startRecording(this, "录制脚本 " + (com.autoball.AB.store.all().size + 1))
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 顺序很重要：先让屏幕尺寸缓存失效，再重算窗口。
        // 否则 screenSize() 返回转屏前的旧值，横屏算出的"屏宽 1/4"
        // 其实是竖屏宽度的错误值。
        com.autoball.core.util.Display.invalidateScreen()
        // 转屏后窗口宽度规则变了（竖屏 1/2 ↔ 横屏 1/4），通知悬浮窗重算
        com.autoball.float.FloatWorkWindow.onConfigChanged(this)
        com.autoball.float.FloatManager.onConfigChanged(this)
    }

    /**
     * 定时补触发：进入应用时检查有没有"到点了但当天还没跑"的脚本。
     *
     * 不做后台常驻——那需要保活、耗电，还常被系统回收，与本工具
     * 「用户手动启动、随时可停」的定位冲突。到点后首次进入应用时补跑一次。
     */
    private fun checkScheduled() {
        val today = AB.store.all().filter {
            com.autoball.ui.ScheduleDialog.shouldFire(it)
        }
        if (today.isEmpty()) return
        today.forEach { s ->
            com.autoball.ui.ScheduleDialog.markFired(s)
            AB.store.save(s)
            AB.log.info("schedule", "定时任务到点，补触发：${s.name}")
            runScript(s)
        }
    }

    override fun onPause() {
        super.onPause()
        if (AB.notifyHost === this) AB.notifyHost = null
    }

    override fun onResume() {
        super.onResume()
        AB.notifyHost = this
        checkScheduled()
        if (com.autoball.AB.store.getBool("float_persistent", true)
            && Display.canDrawOverlay(this)) {
            runCatching { FloatManager.showBall(this) }
        }
    }

    override fun onBackPressed() {
        if (subPage != null) {
            subPage = null
            showPage(4)
            return
        }
        if (current != 0) showPage(0) else super.onBackPressed()
    }
}
