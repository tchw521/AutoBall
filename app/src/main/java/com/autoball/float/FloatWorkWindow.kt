package com.autoball.float

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.AB
import com.autoball.core.model.Script
import com.autoball.core.util.Display
import com.autoball.ui.Theme

/**
 * 工作台悬浮窗（统一组件）。
 *
 * 「开始录制」与「添加动作」都在**这里**进行，而不是应用内弹窗——
 * 因为两者都要操作别的应用：应用内弹窗会占住屏幕，用户根本切不到目标应用。
 * 悬浮窗贴在桌面/目标应用之上，点一下即可继续。
 *
 * 一比一复刻设计稿：
 * - 标题 = 当前脚本名
 * - 右上两个圆钮：⚙（**当前脚本**的全局设置）+ ✕（关闭）
 * - 中部状态文案：空脚本显示「脚本为空 请先添加一个动作」
 * - 底部两个大按钮：开始录制 / 添加动作
 *
 * 头部可拖动；拖动超过阈值不触发点击，避免误触。
 */
object FloatWorkWindow {

    interface Callback {
        fun onRecord(script: Script)
        fun onAddAction(script: Script)
        fun onSettings(script: Script)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null
    private var current: Script? = null

    fun isShown(): Boolean = view != null

    /**
     * 显示工作台悬浮窗。
     *
     * @param goHome 是否顺带回到桌面。录制与取坐标必须让出屏幕，
     *               否则采集层只能采到本应用自己的界面。
     */
    fun show(context: Context, script: Script, cb: Callback, goHome: Boolean = true) {
        if (!Display.canDrawOverlay(context)) {
            AB.log.warn("work", "未获得悬浮窗权限，工作台无法显示")
            android.widget.Toast.makeText(context, "请先授予悬浮窗权限", 0).show()
            return
        }
        current = script
        handler.post {
            if (view != null) return@post
            val ctx = context.applicationContext
            val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm = manager
            val v = buildView(ctx, script, cb)
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            p.gravity = Gravity.CENTER
            p.x = 0
            p.y = Display.screenSize(ctx).y / 6
            view = v
            params = p
            runCatching { manager.addView(v, p) }
            if (goHome) goHome(ctx)
        }
    }

    /**
     * 应用内形态：未授予悬浮窗权限时的降级。
     * 复用同一套 [buildView]，保证两种形态外观与操作完全一致。
     */
    fun showInApp(activity: android.app.Activity, script: Script, cb: Callback) {
        current = script
        val v = buildView(activity, script, cb)
        val d = android.app.AlertDialog.Builder(activity).setView(v).setCancelable(true).create()
        d.show()
        d.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    }

    fun hide() {
        handler.post {
            val v = view ?: return@post
            runCatching { wm?.removeView(v) }
            view = null
            params = null
        }
    }

    /** 脚本动作数变化后刷新状态文案 */
    fun refresh(script: Script) {
        handler.post {
            current = script
            (view?.getTag(R.id.work_state) as? TextView)?.apply {
                val n = script.flow?.actions?.size ?: 0
                text = if (n == 0) "脚本为空  请先添加一个动作"
                else "已有 $n 个动作，可继续添加或直接运行"
            }
        }
    }

    // ---------- 构建 ----------

    private fun buildView(ctx: Context, script: Script, cb: Callback): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg(ctx)
            elevation = Display.dp(ctx, 10f)
        }
        val w = Display.dpInt(ctx, 264f)

        // ---- 头：脚本名 + ⚙ + ✕（可拖动）----
        val head = FrameLayout(ctx).apply {
            setPadding(Display.dpInt(ctx, 14f), Display.dpInt(ctx, 12f),
                Display.dpInt(ctx, 10f), Display.dpInt(ctx, 10f))
        }
        head.addView(TextView(ctx).apply {
            text = script.name
            textSize = 14.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.textPri())
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                marginEnd = Display.dpInt(ctx, 72f)
            }
        })
        val btns = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
        }
        btns.addView(roundBtn(ctx, "⚙") { cb.onSettings(script) })
        btns.addView(roundBtn(ctx, "✕") { hide() })
        head.addView(btns)
        dragAttach(head)
        root.addView(head)

        // ---- 状态文案 ----
        val stateTv = TextView(ctx).apply {
            val n = script.flow?.actions?.size ?: 0
            text = if (n == 0) "脚本为空  请先添加一个动作"
            else "已有 $n 个动作，可继续添加或直接运行"
            textSize = 12.5f
            setTextColor(Theme.textSec())
            gravity = Gravity.CENTER
            setPadding(Display.dpInt(ctx, 14f), Display.dpInt(ctx, 16f),
                Display.dpInt(ctx, 14f), Display.dpInt(ctx, 16f))
        }
        stateTv.setTag(R.id.work_state, stateTv)
        root.addView(stateTv)

        // ---- 两个大按钮 ----
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Display.dpInt(ctx, 12f), 0,
                Display.dpInt(ctx, 12f), Display.dpInt(ctx, 12f))
            addView(bigBtn(ctx, "开始录制", "●", Theme.ok()) {
                hide(); cb.onRecord(script)
            }, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = Display.dpInt(ctx, 4f)
            })
            addView(bigBtn(ctx, "添加动作", "＋", Theme.pri()) {
                hide(); cb.onAddAction(script)
            }, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Display.dpInt(ctx, 4f)
            })
        })

        root.layoutParams = FrameLayout.LayoutParams(w,
            FrameLayout.LayoutParams.WRAP_CONTENT)
        return root
    }

    private fun panelBg(ctx: Context): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            if (Theme.isDark()) intArrayOf(0xF6242040.toInt(), 0xFA1B1730.toInt())
            else intArrayOf(0xFAFFFFFF.toInt(), 0xFAF5F3FF.toInt())).apply {
            cornerRadius = Display.dp(ctx, 18f)
            setStroke(Display.dpInt(ctx, 1f), Theme.line())
        }

    private fun roundBtn(ctx: Context, glyph: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = glyph
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.textSec())
            gravity = Gravity.CENTER
            background = Theme.bubbleRound(ctx, Theme.surface2())
            val sz = Display.dpInt(ctx, 28f)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginStart = Display.dpInt(ctx, 6f)
            }
            setOnClickListener { onClick() }
        }

    private fun bigBtn(ctx: Context, text: String, glyph: String,
                       color: Int, onClick: () -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Theme.rect(Theme.surface2(), 14f, ctx, Theme.line())
            setPadding(Display.dpInt(ctx, 8f), Display.dpInt(ctx, 14f),
                Display.dpInt(ctx, 8f), Display.dpInt(ctx, 14f))
            setOnClickListener { onClick() }
            addView(TextView(ctx).apply {
                this.text = glyph
                textSize = 18f
                setTextColor(color)
                gravity = Gravity.CENTER
            })
            addView(TextView(ctx).apply {
                this.text = text
                textSize = 12.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Theme.textPri())
                gravity = Gravity.CENTER
                setPadding(0, Display.dpInt(ctx, 6f), 0, 0)
            })
        }

    /** 头部拖动：超过 8dp 视为移动，不触发点击 */
    private fun dragAttach(head: View) {
        var sx = 0f; var sy = 0f; var px = 0; var py = 0; var moved = false
        head.setOnTouchListener { _, e ->
            val p = params ?: return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; px = p.x; py = p.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt()
                    val dy = (e.rawY - sy).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) {
                        moved = true
                        p.x = px + dx; p.y = py + dy
                        runCatching { wm?.updateViewLayout(view, p) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) return@setOnTouchListener true
                    head.performClick()
                }
            }
            true
        }
    }

    private fun goHome(ctx: Context) {
        runCatching {
            val i = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(i)
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
}
