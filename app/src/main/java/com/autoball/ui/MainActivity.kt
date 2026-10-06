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
    fun openCreate()
    fun refreshAll()
    fun startRecording()
}

class MainActivity : Activity(), PageHost {

    private lateinit var content: FrameLayout
    private lateinit var nav: LiquidNavView
    private val pages = arrayOfNulls<View>(5)
    private var current = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (Theme.isDark()) R.style.Theme_AutoBall else R.style.Theme_AutoBall_Light)
        super.onCreate(savedInstanceState)
        // Android 12+：窗口级背景模糊，为液态导航提供真实折射来源；低版本自动降级
        if (Build.VERSION.SDK_INT >= 31) {
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

    override fun openCreate() { showPage(2) }

    override fun refreshAll() {
        for (i in pages.indices) pages[i] = null
        val idx = current
        content.removeAllViews()
        showPage(idx)
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

    override fun onResume() {
        super.onResume()
        if (com.autoball.AB.store.getBool("float_persistent", true)
            && Display.canDrawOverlay(this)) {
            runCatching { FloatManager.showBall(this) }
        }
    }

    override fun onBackPressed() {
        if (current != 0) showPage(0) else super.onBackPressed()
    }
}
