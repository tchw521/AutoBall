package com.autoball.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.autoball.AB
import com.autoball.core.backend.BackendId
import com.autoball.core.engine.JsEngines
import com.autoball.core.model.BallSlot
import com.autoball.core.util.Display
import com.autoball.float.FloatManager
import com.autoball.service.AutoBallAccessibilityService
import com.autoball.service.FloatingService
import com.autoball.service.ShizukuClient
import com.autoball.core.log.CrashGuard
import com.autoball.ui.ChangeLog

/**
 * 我的页：权限开关与状态、主题切换、悬浮球设置、运行日志、更新日志与免责说明。
 *
 * 权限入口按引导顺序排列：无障碍 → 悬浮窗 → 通知 → 后台运行 → 厂商自启动。
 * 每次只跳转一个用户可理解的目的，失败不崩溃。
 */
class MinePage(context: Context, private val host: PageHost) : FrameLayout(context) {

    private val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = android.widget.ScrollView(context).apply { isVerticalScrollBarEnabled = false }
    private var logView: TextView? = null

    init {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        root.addView(topbar())
        scroll.addView(box, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        rebuild()
        AB.log.onChange = { post { refreshLog() } }
    }

    private fun rebuild() {
        box.removeAllViews()
        box.setPadding(Display.dpInt(context, 16f), 0,
            Display.dpInt(context, 16f), Display.dpInt(context, 96f))

        // ---- 执行授权（双通道并行）----
        box.addView(section("执行授权"))
        box.addView(TextView(context).apply {
            text = "两种方式任选其一即可运行脚本；两者都开启时按动作能力自动择优，失败会自动切换。"
            textSize = 11f
            setTextColor(Theme.textSec())
            setPadding(0, 0, 0, Display.dpInt(context, 8f))
        })

        val a11yOn = Display.accessibilityEnabled(context)
        box.addView(permRow(
            title = "无障碍服务",
            sub = if (a11yOn) "已开启 · 支持点击/滑动/控件节点/截图" else "未开启 · 最通用的执行通道",
            on = a11yOn,
            action = "去开启"
        ) {
            AutoBallAccessibilityService.openAccessibilitySettings(context)
        })

        val shizukuOn = ShizukuClient.instance.isInstalled() && ShizukuClient.instance.isAuthorized()
        box.addView(permRow(
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
                val ch = ShizukuClient.instance.probe()
                AB.log.info("mine", "Shizuku 检测结果：${ch.name}（${ShizukuClient.instance.lastError ?: "无错误"}）")
                post { rebuild() }
            }.apply { isDaemon = true }.start()
        })

        box.addView(statusLine())

        // ---- 悬浮与显示 ----
        box.addView(section("悬浮与显示"))
        val overlayOn = Display.canDrawOverlay(context)
        box.addView(permRow(
            title = "悬浮窗权限",
            sub = if (overlayOn) "已授予 · 悬浮球与控制窗可用" else "未授予 · 悬浮球无法显示",
            on = overlayOn,
            action = "去开启"
        ) { Display.openOverlaySettings(context) })

        box.addView(Ui.switchRow(context, "悬浮球常驻", AB.store.getBool("float_persistent", true)) { v ->
            AB.store.putBool("float_persistent", v)
            if (v) FloatingService.start(context) else FloatManager.hideAll()
        })
        box.addView(switchRow("深色主题", Theme.isDark()) { v ->
            Theme.setDark(v)
            host.refreshAll()
        })
        box.addView(switchRow("悬浮球自动贴边", AB.store.getBool("ball_snap_edge", true)) { v ->
            AB.store.putBool("ball_snap_edge", v)
        })
        box.addView(sliderRow("悬浮球大小(dp)",
            AB.store.getFloat("ball_size_dp", 48f), 36f, 64f) { v ->
            AB.store.putFloat("ball_size_dp", v)
            FloatManager.hideBall()
            FloatManager.showBall(context)
        })
        box.addView(sliderRow("闲置透明度",
            AB.store.getFloat("ball_idle_alpha", 0.72f), 0.3f, 1f) { v ->
            AB.store.putFloat("ball_idle_alpha", v)
        })

        // ---- 手势槽位 ----
        box.addView(section("悬浮球手势"))
        for (slot in listOf(BallSlot.SINGLE, BallSlot.DOUBLE, BallSlot.TRIPLE, BallSlot.LONG)) {
            box.addView(slotRow(slot))
        }

        // ---- 悬浮窗皮肤 ----
        box.addView(section("悬浮窗皮肤"))
        val skins = com.autoball.float.FloatPanelView.Skin.values()
        box.addView(TextView(context).apply {
            text = skins.joinToString(" · ") { it.label }
            textSize = 11f
            setTextColor(Theme.textSec())
            setPadding(0, 0, 0, Display.dpInt(context, 6f))
        })
        val skinRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (s in skins) {
            skinRow.addView(TextView(context).apply {
                text = s.label.take(2)
                textSize = 11f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = Theme.bubble(context,
                    if (AB.store.getString("panel_skin", skins[1].name) == s.name)
                        Color.parseColor(Theme.BLUE) else Color.parseColor("#3A2E6B"), 10f)
                setPadding(Display.dpInt(context, 10f), Display.dpInt(context, 6f),
                    Display.dpInt(context, 10f), Display.dpInt(context, 6f))
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(Display.dpInt(context, 2f), 0, Display.dpInt(context, 2f), 0)
                layoutParams = lp
                setOnClickListener {
                    AB.store.putString("panel_skin", s.name)
                    rebuild()
                }
            })
        }
        box.addView(skinRow)

        // ---- 运行日志 ----
        box.addView(section("运行日志"))
        box.addView(TextView(context).apply {
            text = "只记录动作类型、执行后端、耗时与结果；不记录输入文本、控件文本与分享码原文。"
            textSize = 11f
            setTextColor(Theme.textSec())
            setPadding(0, 0, 0, Display.dpInt(context, 6f))
        })
        logView = TextView(context).apply {
            textSize = 11f
            setTextColor(Theme.textPri())
            background = Theme.bubble(context,
                Color.parseColor(if (Theme.isDark()) "#1B1730" else "#F2F3FA"), 12f)
            setPadding(Display.dpInt(context, 10f), Display.dpInt(context, 8f),
                Display.dpInt(context, 10f), Display.dpInt(context, 8f))
        }
        box.addView(logView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Display.dpInt(context, 180f)))
        refreshLog()

        box.addView(TextView(context).apply {
            text = "清空日志"
            textSize = 12f
            setTextColor(Theme.textSec())
            gravity = Gravity.CENTER
            setPadding(0, Display.dpInt(context, 8f), 0, 0)
            setOnClickListener { AB.log.clear() }
        })

        // ---- 关于 ----
        box.addView(section("关于"))
        box.addView(infoRow("脚本引擎", JsEngines.engineName() + if (JsEngines.engineName() == "quickjs")
            "（未内置源码时自动降级为纯 Java 引擎）" else ""))
        box.addView(infoRow("版本", "v0.7.0"))
        box.addView(infoRow("更新日志", "查看").apply {
            setOnClickListener { ChangeLog.show(context as? Activity ?: return@setOnClickListener) }
        })

        // 崩溃日志：真机拿不到 logcat，这里可直接查看与复制
        box.addView(infoRow("崩溃日志",
            if (CrashGuard.hasSavedCrash()) "有记录 · 点击查看" else "无记录").apply {
            setOnClickListener {
                val act = context as? Activity ?: return@setOnClickListener
                android.app.AlertDialog.Builder(act)
                    .setTitle("崩溃日志")
                    .setMessage(if (CrashGuard.hasSavedCrash()) CrashGuard.savedCrash().take(6000)
                                else "暂无崩溃记录")
                    .setPositiveButton("复制并清空") { d, _ ->
                        runCatching {
                            val cm = act.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as? android.content.ClipboardManager
                            cm?.setPrimaryClip(android.content.ClipData.newPlainText(
                                "autoball-crash", CrashGuard.savedCrash()))
                        }
                        CrashGuard.clear(); rebuild(); d.dismiss()
                    }
                    .setNegativeButton("关闭", null)
                    .show()
            }
        })

        box.addView(TextView(context).apply {
            text = context.getString(com.autoball.R.string.compliance_notice)
            textSize = 11f
            setTextColor(Theme.textSec())
            setPadding(0, Display.dpInt(context, 14f), 0, 0)
        })

        // ---- 电池优化引导 ----
        box.addView(section("后台运行"))
        box.addView(TextView(context).apply {
            text = "保活只能降低被回收的频率，不能承诺不被系统杀死。建议在系统设置中允许后台运行。"
            textSize = 11f
            setTextColor(Theme.textSec())
            setPadding(0, 0, 0, Display.dpInt(context, 6f))
        })
        box.addView(TextView(context).apply {
            text = "前往应用详情"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Theme.bubble(context, Color.parseColor(Theme.BLUE), 12f)
            setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 9f),
                Display.dpInt(context, 14f), Display.dpInt(context, 9f))
            setOnClickListener { AutoBallAccessibilityService.openAppSettings(context) }
        })
        if (Build.VERSION.SDK_INT >= 23) {
            box.addView(TextView(context).apply {
                text = "忽略电池优化（需系统授权）"
                textSize = 12f
                setTextColor(Theme.textSec())
                gravity = Gravity.CENTER
                setPadding(0, Display.dpInt(context, 10f), 0, 0)
                setOnClickListener {
                    runCatching {
                        context.startActivity(Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:" + context.packageName)))
                    }
                }
            })
        }
    }

    private fun statusLine(): TextView {
        val st = AB.router.status()
        val txt = st.joinToString("  ") { (id, h) -> "${id.label}: ${h.name}" }
        return TextView(context).apply {
            text = "当前后端状态  $txt"
            textSize = 11f
            setTextColor(Theme.textSec())
            setPadding(0, Display.dpInt(context, 6f), 0, 0)
        }
    }

    private fun slotRow(slot: BallSlot): View {
        val scripts = AB.store.all().filter { it.slot == slot }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.bubble(context, Theme.card(), 14f)
            setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 10f),
                Display.dpInt(context, 12f), Display.dpInt(context, 10f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, Display.dpInt(context, 8f))
            layoutParams = lp
        }
        val mid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        mid.addView(TextView(context).apply {
            text = slot.label
            textSize = 14f
            setTextColor(Theme.textPri())
        })
        mid.addView(TextView(context).apply {
            text = if (scripts.isEmpty()) "未绑定" else scripts.joinToString { it.name }
            textSize = 11f
            setTextColor(Theme.textSec())
        })
        row.addView(mid)
        row.addView(TextView(context).apply {
            text = "绑定"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Theme.bubble(context, Color.parseColor(Theme.PURPLE), 12f)
            setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 6f),
                Display.dpInt(context, 14f), Display.dpInt(context, 6f))
            setOnClickListener { showSlotPicker(slot) }
        })
        return row
    }

    private fun showSlotPicker(slot: BallSlot) {
        val act = context as? Activity ?: return
        val all = AB.store.all()
        if (all.isEmpty()) {
            AB.log.warn("mine", "还没有脚本可绑定")
            return
        }
        val names = arrayOf("（不绑定）") + all.map { it.name }.toTypedArray()
        AlertDialog.Builder(act).setTitle("绑定「${slot.label}」").setItems(names) { _, w ->
            all.forEach { if (it.slot == slot) { it.slot = BallSlot.NONE; AB.store.save(it) } }
            if (w > 0) {
                val s = all[w - 1]
                s.slot = slot
                AB.store.save(s)
                AB.log.info("mine", "「${slot.label}」已绑定「${s.name}」")
            }
            rebuild()
        }.show()
    }

    private fun refreshLog() {
        val v = logView ?: return
        val list = AB.log.snapshot().takeLast(60)
        v.text = if (list.isEmpty()) "暂无日志" else list.joinToString("\n") { it.line() }
    }

    // ---------- 小部件 ----------

    /** 顶栏（v3 .topbar）：h1 26px 800 + 副标题 + 右上图标按钮 */
    private fun topbar(): LinearLayout {
        val b = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Display.dpInt(context, 18f), Display.dpInt(context, 6f),
                Display.dpInt(context, 18f), Display.dpInt(context, 12f))
            gravity = Gravity.BOTTOM
        }
        val l = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        l.addView(TextView(context).apply {
            text = "我的"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textPri())
            includeFontPadding = false
        })
        l.addView(TextView(context).apply {
            text = if (Theme.isDark()) "深色主题" else "浅色主题"
            textSize = 12f
            setTextColor(Theme.textSec())
            setPadding(0, Display.dpInt(context, 3f), 0, 0)
        })
        b.addView(l, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // 主题切换按钮：点击后图标旋转（v3 .iconbtn.tbtn）
        b.addView(TextView(context).apply {
            text = if (Theme.isDark()) "☾" else "☀"
            textSize = 17f
            setTextColor(if (Theme.isDark()) Color.parseColor("#A78BFA")
            else Color.parseColor("#F79009"))
            gravity = Gravity.CENTER
            background = Theme.rect(Theme.surface(), 12f, context, Theme.line())
            val sz = Display.dpInt(context, 36f)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setOnClickListener {
                animate().rotationBy(-90f).setDuration(320).start()
                Theme.toggleDark()
                host.refreshAll()
            }
        })
        return b
    }

    /** 分区标题（v3 .sec：11px 700 --tx3，上下 14/8） */
    private fun section(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 11f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Theme.textTer())
        letterSpacing = 0.03f
        setPadding(Display.dpInt(context, 2f), Display.dpInt(context, 14f),
            Display.dpInt(context, 2f), Display.dpInt(context, 8f))
    }

    private fun permRow(title: String, sub: String, on: Boolean, action: String, onClick: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.bubble(context, Theme.card(), 14f)
            setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 10f),
                Display.dpInt(context, 12f), Display.dpInt(context, 10f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, Display.dpInt(context, 8f))
            layoutParams = lp
        }
        val dot = TextView(context).apply {
            background = Theme.bubbleRound(context,
                Color.parseColor(if (on) "#35D08A" else "#FF5B6E"))
            layoutParams = LinearLayout.LayoutParams(Display.dpInt(context, 10f),
                Display.dpInt(context, 10f))
        }
        row.addView(dot)
        val mid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(Display.dpInt(context, 10f), 0, 0, 0)
            }
        }
        mid.addView(TextView(context).apply {
            text = title; textSize = 14f; setTextColor(Theme.textPri())
        })
        mid.addView(TextView(context).apply {
            text = sub; textSize = 11f; setTextColor(Theme.textSec())
        })
        row.addView(mid)
        row.addView(TextView(context).apply {
            text = action
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Theme.bubble(context, Color.parseColor(if (on) "#3A2E6B" else Theme.BLUE), 12f)
            setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 6f),
                Display.dpInt(context, 14f), Display.dpInt(context, 6f))
            setOnClickListener { onClick() }
        })
        return row
    }

    /** 开关行（v3 .row + .sw：行 radius 14 / padding 13x14；开关 42x24，滑块 18 @3） */
    private fun switchRow(title: String, init: Boolean, onChange: (Boolean) -> Unit): View {
        var on = init
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.rect(Theme.surface(), Theme.ROW_R, context, Theme.line())
            setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 13f),
                Display.dpInt(context, 14f), Display.dpInt(context, 13f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, Display.dpInt(context, Theme.ROW_MB))
            layoutParams = lp
        }
        row.addView(TextView(context).apply {
            text = title
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textPri())
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val track = android.widget.FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                Display.dpInt(context, Theme.SW_W), Display.dpInt(context, Theme.SW_H))
            background = Theme.rect(if (on) Theme.pri2() else Theme.line2(), 12f, context)
        }
        val knob = View(context).apply {
            background = Theme.oval(Color.WHITE)
        }
        track.addView(knob, android.widget.FrameLayout.LayoutParams(
            Display.dpInt(context, Theme.SW_KNOB), Display.dpInt(context, Theme.SW_KNOB)).apply {
            leftMargin = if (on) Display.dpInt(context, 21f) else Display.dpInt(context, 3f)
            topMargin = Display.dpInt(context, 3f)
        })
        row.addView(track)
        row.setOnClickListener {
            on = !on
            track.background = Theme.rect(if (on) Theme.pri2() else Theme.line2(), 12f, context)
            (knob.layoutParams as android.widget.FrameLayout.LayoutParams).leftMargin =
                if (on) Display.dpInt(context, 21f) else Display.dpInt(context, 3f)
            knob.requestLayout()
            onChange(on)
        }
        return row
    }

    private fun sliderRow(title: String, init: Float, minVal: Float, maxVal: Float,
                          onChange: (Float) -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.bubble(context, Theme.card(), 14f)
            setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 8f),
                Display.dpInt(context, 12f), Display.dpInt(context, 8f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, Display.dpInt(context, 8f))
            layoutParams = lp
        }
        val label = TextView(context).apply {
            text = "$title：%.2f".format(init)
            textSize = 12f
            setTextColor(Theme.textPri())
        }
        row.addView(label)
        val ratio = ((init - minVal) / (maxVal - minVal) * 100).toInt().coerceIn(0, 100)
        val bar = android.widget.SeekBar(context)
        bar.max = 100
        bar.progress = ratio
        bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                val v = minVal + (maxVal - minVal) * p / 100f
                label.text = "$title：%.2f".format(v)
                if (fromUser) onChange(v)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) { }
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) { }
        })
        row.addView(bar)
        return row
    }

    /** 值行（v3 .row：左标题 + 右值） */
    private fun infoRow(k: String, v: String): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Theme.rect(Theme.surface(), Theme.ROW_R, context, Theme.line())
        setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 13f),
            Display.dpInt(context, 14f), Display.dpInt(context, 13f))
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, Display.dpInt(context, Theme.ROW_MB))
        layoutParams = lp
        addView(TextView(context).apply {
            text = k
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textPri())
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(context).apply {
            text = v
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textSec())
        })
    }
}
