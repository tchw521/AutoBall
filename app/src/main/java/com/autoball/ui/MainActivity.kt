package com.autoball.ui

import android.app.Activity
import android.content.Context
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
}

class MainActivity : Activity(), PageHost {

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
        root.addView(Ui.versionBar(this, "v1.14.0", "查看更新日志") {
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
        // 转屏后窗口宽度规则变了（竖屏 1/2 ↔ 横屏 1/4），通知悬浮窗重算
        com.autoball.float.FloatWorkWindow.onConfigChanged(this)
    }

    override fun onResume() {
        super.onResume()
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
