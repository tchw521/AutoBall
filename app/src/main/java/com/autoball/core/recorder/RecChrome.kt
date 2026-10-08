package com.autoball.core.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.autoball.core.model.Action
import com.autoball.core.util.Display
import com.autoball.ui.ActionEditor
import com.autoball.ui.Theme

/**
 * 录制悬浮三件套（UI 设计方案 v3）：
 *
 * - `.recpill`：录制中提示条（居中偏上）—— 红点闪烁 + 「录制中」+ 计时 + 停止。
 * - `.recwin`：录制小窗（205dp 宽白卡）—— 头部（脚本名 / 全局设置 / 关闭）、
 *   动作列表（空态 112dp，列表最大 168dp）、底部按钮组。
 * - `.reccap`：录制胶囊（62×54 右侧贴边）—— 小窗最小化后的形态，点一下展开。
 *
 * 三个各自独立成窗，窗口之外触摸照常穿透到目标应用，不会像全屏层那样被判为不可信遮挡。
 */
object RecChrome {

    /** 当前录制对应的脚本流程，供全局设置写入 */
    var flowRef: com.autoball.core.model.Flow? = null

    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var win: FrameLayout? = null
    @Volatile private var cap: FrameLayout? = null
    @Volatile private var pill: LinearLayout? = null
    @Volatile private var wm: WindowManager? = null
    @Volatile private var winP: WindowManager.LayoutParams? = null
    @Volatile private var capP: WindowManager.LayoutParams? = null
    @Volatile private var pillP: WindowManager.LayoutParams? = null

    @Volatile private var controller: RecordController? = null
    @Volatile private var collapsed = false
    @Volatile private var startMs = 0L
    @Volatile private var pausedMs = 0L
    @Volatile private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            updatePill()
            handler.postDelayed(this, 500)
        }
    }

    fun show(context: Context, ctrl: RecordController, name: String) {
        hide()
        val ctx = context.applicationContext
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        controller = ctrl
        startMs = System.currentTimeMillis()
        pausedMs = 0
        running = true
        collapsed = false

        pill = buildPill(ctx)
        win = buildWin(ctx, name)
        cap = buildCap(ctx)

        pillP = params(ctx, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, Display.dpInt(ctx, 74f))
        winP = params(ctx, Display.dpInt(ctx, 205f),
            WindowManager.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, Display.dpInt(ctx, 146f))
        capP = params(ctx, Display.dpInt(ctx, 62f), Display.dpInt(ctx, 54f),
            Gravity.TOP or Gravity.END, Display.dpInt(ctx, -20f), Display.dpInt(ctx, 180f))

        runCatching { manager.addView(pill, pillP) }
        runCatching { manager.addView(win, winP) }
        runCatching { manager.addView(cap, capP) }
        wm = manager

        cap?.visibility = View.GONE
        handler.post(tick)
        renderWin()
    }

    fun hide() {
        running = false
        handler.removeCallbacks(tick)
        val m = wm ?: return
        listOf(win, cap, pill).forEach { v ->
            if (v != null) runCatching { m.removeView(v) }
        }
        win = null; cap = null; pill = null
        winP = null; capP = null; pillP = null
        wm = null
        controller = null
    }

    fun setPaused(p: Boolean) {
        if (p) pausedMs = System.currentTimeMillis()
        else if (pausedMs > 0) {
            startMs += System.currentTimeMillis() - pausedMs
            pausedMs = 0
        }
        renderWin()
    }

    fun renderWin() { handler.post { fillWin() } }

    // =====================================================================
    // .recpill：录制中提示条
    // =====================================================================

    private fun buildPill(ctx: Context): LinearLayout {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#DE180E12"))
                cornerRadius = Display.dp(ctx, 22f)
                setStroke(Display.dpInt(ctx, 1f), Color.parseColor("#29FFFFFF"))
            }
            setPadding(Display.dpInt(ctx, 12f), Display.dpInt(ctx, 8f),
                Display.dpInt(ctx, 14f), Display.dpInt(ctx, 8f))
        }
        val dot = View(ctx).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E5484D")); shape = GradientDrawable.OVAL
            }
            layoutParams = LinearLayout.LayoutParams(Display.dpInt(ctx, 9f),
                Display.dpInt(ctx, 9f))
        }
        box.addView(dot)
        box.addView(text(ctx, "录制中", 12f, Color.WHITE, true).apply {
            setPadding(Display.dpInt(ctx, 8f), 0, 0, 0)
        })
        box.addView(text(ctx, "00:00", 12f, Color.WHITE, false).apply {
            tag = "tm"
            setPadding(Display.dpInt(ctx, 6f), 0, 0, 0)
        })
        box.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#40FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(Display.dpInt(ctx, 1f),
                Display.dpInt(ctx, 14f)).apply {
                setMargins(Display.dpInt(ctx, 8f), 0, Display.dpInt(ctx, 8f), 0)
            }
        })
        box.addView(text(ctx, "停止", 12f, Color.parseColor("#FF9B9E"), true).apply {
            setOnClickListener { controller?.interrupt("用户停止") }
        })
        return box
    }

    private fun updatePill() {
        val p = pill ?: return
        val tv = p.findViewWithTag<TextView>("tm") ?: return
        val ms = if (pausedMs > 0) pausedMs - startMs else System.currentTimeMillis() - startMs
        tv.text = String.format("%02d:%02d", (ms / 60000) % 60, (ms / 1000) % 60)
    }

    // =====================================================================
    // .recwin：录制小窗（205dp 白卡）
    // =====================================================================

    private fun buildWin(ctx: Context, name: String): FrameLayout {
        val root = FrameLayout(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#F7FFFFFF"), Color.parseColor("#EDFFFFFF"))).apply {
                cornerRadius = Display.dp(ctx, 16f)
                setStroke(Display.dpInt(ctx, 1f), Color.parseColor("#E6FFFFFF"))
            }
            elevation = Display.dp(ctx, 10f)
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col)

        // .rw-head
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Display.dpInt(ctx, 10f), Display.dpInt(ctx, 7f),
                Display.dpInt(ctx, 8f), Display.dpInt(ctx, 6f))
        }
        head.addView(text(ctx, name, 12f, Color.parseColor("#241C16"), true).apply {
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            tag = "name"
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(iconBtn(ctx, "⚙") {
            // 录制中的脚本流程；为空时退化为空流程，仍可设置
            val act = ctx as? android.app.Activity ?: return@iconBtn
            val fl = flowRef ?: com.autoball.core.model.Flow()
            GlobalSettingsDialog.show(act, fl) { flowRef = fl }
        })
        head.addView(iconBtn(ctx, "✕") { hide() })
        col.addView(head)

        col.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#1A5A3C28"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })

        // .rw-body
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Display.dpInt(ctx, 10f), Display.dpInt(ctx, 6f),
                Display.dpInt(ctx, 10f), Display.dpInt(ctx, 10f))
            tag = "body"
        }
        col.addView(body)
        attachDrag(ctx, root, head, winP)
        return root
    }

    private fun fillWin() {
        val root = win ?: return
        val body = root.findViewWithTag<LinearLayout>("body") ?: return
        val actions = controller?.currentFlow()?.actions ?: return
        body.removeAllViews()

        if (actions.isEmpty()) {
            body.addView(LinearLayout(context(body)).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Display.dpInt(context(body), 112f))
                addView(text(ctx = context(body), t = "开始操作手机\n每一步都会自动记录",
                    size = 12.5f, color = Color.parseColor("#9A8B7C"), bold = true).apply {
                    gravity = Gravity.CENTER
                })
            })
        } else {
            val sc = ScrollView(context(body)).apply {
                isVerticalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val list = LinearLayout(context(body)).apply {
                orientation = LinearLayout.VERTICAL
            }
            actions.forEachIndexed { i, a ->
                list.addView(rwItem(context(body), i, a))
            }
            sc.addView(list)
            body.addView(sc.apply {
                // .rw-list 最大 168dp
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    height = kotlin.math.min(Display.dpInt(context(body), 168f),
                        actions.size * Display.dpInt(context(body), 42f))
                }
            })
        }

        // 底部按钮组
        val row = LinearLayout(context(body)).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, Display.dpInt(context(body), 8f), 0, 0)
        }
        val paused = controller?.state == RecordController.State.PAUSED
        row.addView(miniBtn(context(body), if (paused) "继续" else "暂停",
            Color.parseColor("#8A7867")) {
            if (paused) controller?.resume() else controller?.pause()
        })
        row.addView(miniBtn(context(body), "撤销", Color.parseColor("#8A7867")) {
            controller?.undo(); renderWin()
        })
        row.addView(miniBtn(context(body), "等待1s", Color.parseColor("#8A7867")) {
            controller?.insertWait(1000); renderWin()
        })
        row.addView(miniBtn(context(body), "保存", Color.parseColor("#12B76A")) {
            controller?.interrupt("保存并结束")
        })
        body.addView(row)
    }

    /** .rw-item：20dp 序号 + 26dp 图标 + 标题 */
    private fun rwItem(ctx: Context, i: Int, a: Action): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Display.dpInt(ctx, 8f), Display.dpInt(ctx, 6f),
                Display.dpInt(ctx, 8f), Display.dpInt(ctx, 6f))
            background = GradientDrawable().apply {
                cornerRadius = Display.dp(ctx, 11f)
            }
            addView(text(ctx, (i + 1).toString(), 10.5f,
                Color.parseColor("#8A7867"), true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1A5A3C28"))
                    cornerRadius = Display.dp(ctx, 7f)
                }
                layoutParams = LinearLayout.LayoutParams(Display.dpInt(ctx, 20f),
                    Display.dpInt(ctx, 20f))
            })
            addView(text(ctx, a.type.label.take(1), 12f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                background = Theme.gradOval()
                layoutParams = LinearLayout.LayoutParams(Display.dpInt(ctx, 26f),
                    Display.dpInt(ctx, 26f)).apply {
                    marginStart = Display.dpInt(ctx, 9f)
                }
            })
            addView(text(ctx, ActionEditor.describe(a), 12.5f,
                Color.parseColor("#241C16"), true).apply {
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = Display.dpInt(ctx, 9f)
                }
            })
        }

    // =====================================================================
    // .reccap：录制胶囊（62×54，右侧贴边）
    // =====================================================================

    private fun buildCap(ctx: Context): FrameLayout {
        val root = FrameLayout(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#F5FFFFFF"), Color.parseColor("#EDFFFFFF"))).apply {
                cornerRadius = Display.dp(ctx, 27f)
                setStroke(Display.dpInt(ctx, 1f), Color.parseColor("#33FFFFFF"))
            }
            elevation = Display.dp(ctx, 8f)
            setPadding(Display.dpInt(ctx, 6f), 0, 0, 0)
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
        }
        row.addView(View(ctx).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#12B76A")); shape = GradientDrawable.OVAL
            }
            layoutParams = LinearLayout.LayoutParams(Display.dpInt(ctx, 9f),
                Display.dpInt(ctx, 9f))
        })
        val cw = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = Display.dpInt(ctx, 5f)
            }
        }
        cw.addView(text(ctx, "0", 15f, Color.parseColor("#241C16"), true).apply {
            tag = "cnt"
            gravity = Gravity.CENTER
        })
        cw.addView(text(ctx, "步", 9f, Color.parseColor("#9A8B7C"), true).apply {
            gravity = Gravity.CENTER
        })
        row.addView(cw)
        root.addView(row)
        root.setOnClickListener { expand() }
        attachDrag(ctx, root, null, capP)
        return root
    }

    /** 收起为胶囊 */
    fun collapse() {
        if (collapsed) return
        collapsed = true
        val n = controller?.currentFlow()?.actions?.size ?: 0
        cap?.findViewWithTag<TextView>("cnt")?.text = n.toString()
        win?.visibility = View.GONE
        pill?.visibility = View.GONE
        cap?.visibility = View.VISIBLE
    }

    /** 展开为小窗 */
    fun expand() {
        if (!collapsed) return
        collapsed = false
        cap?.visibility = View.GONE
        win?.visibility = View.VISIBLE
        pill?.visibility = View.VISIBLE
        renderWin()
    }

    // =====================================================================
    // 通用
    // =====================================================================

    private fun context(v: View): Context = v.context

    private fun text(ctx: Context, t: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(ctx).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    /** .rw-ib：28dp 圆角 9 */
    private fun iconBtn(ctx: Context, glyph: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = glyph
            textSize = 13f
            setTextColor(Color.parseColor("#8A7867"))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = Display.dp(ctx, 9f)
            }
            layoutParams = LinearLayout.LayoutParams(Display.dpInt(ctx, 28f),
                Display.dpInt(ctx, 28f))
            setOnClickListener { onClick() }
        }

    /** 小窗底部按钮 */
    private fun miniBtn(ctx: Context, t: String, color: Int, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = t
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(color)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A5A3C28"))
                cornerRadius = Display.dp(ctx, 8f)
            }
            setPadding(Display.dpInt(ctx, 8f), Display.dpInt(ctx, 5f),
                Display.dpInt(ctx, 8f), Display.dpInt(ctx, 5f))
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Display.dpInt(ctx, 3f)
                marginEnd = Display.dpInt(ctx, 3f)
            }
            setOnClickListener { onClick() }
        }

    /** 让窗口跟手拖动；handle 为空表示整个 root 可拖 */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(ctx: Context, root: View, handle: View?,
                           p: WindowManager.LayoutParams?) {
        var sx = 0f; var sy = 0f; var moving = false
        val target = handle ?: root
        val onTouch = View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; moving = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (!moving && kotlin.math.abs(dx) + kotlin.math.abs(dy) >
                        Display.dp(ctx, 4f)) moving = true
                    if (moving) {
                        val lp = p ?: return@OnTouchListener true
                        lp.x += dx.toInt(); lp.y += dy.toInt()
                        sx = e.rawX; sy = e.rawY
                        runCatching { wm?.updateViewLayout(root, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { moving = false; true }
                else -> true
            }
        }
        if (handle == null) root.setOnTouchListener(onTouch) else handle.setOnTouchListener(onTouch)
    }

    private fun params(ctx: Context, w: Int, h: Int, gravity: Int, x: Int, y: Int)
            : WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(w, h, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            this.gravity = gravity
            this.x = x
            this.y = y
        }
    }
}
