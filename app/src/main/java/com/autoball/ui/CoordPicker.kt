package com.autoball.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import com.autoball.core.util.Display
import com.autoball.float.FloatManager
import com.autoball.float.FloatWindows

/**
 * 全屏选点（UI 设计方案 v3 · N20 / N15）。
 *
 * 行为：隐藏本软件一切界面 → 露出真实屏幕（可停在任意应用或桌面）
 * → 拖动 / 点击定位十字准星 → 百分比显示 + ±1% 微调 → 确定后回调。
 *
 * 与旧实现的根本区别：旧版用一枚小准星浮标，看不到全局；本版是全屏选点层，
 * 完全对齐 v3 —— 百分比坐标（0–100%）、方向微调、底部确定/取消条。
 *
 * 说明：坐标内部以**百分比**保存（分辨率无关），回调时按当前屏幕换算为绝对像素，
 * 以兼容动作模型；动作流自 v0.4 起另有屏幕签名缩放。
 */
object CoordPicker {

    @Volatile
    private var view: PickLayer? = null
    @Volatile
    private var swipeView: SwipeLayer? = null
    @Volatile
    private var wm: WindowManager? = null
    @Volatile
    private var hostActivity: Activity? = null
    @Volatile
    private var hostDialog: android.app.Dialog? = null
    @Volatile
    private var wasBallShown = false

    private val handler = Handler(Looper.getMainLooper())

    fun pick(context: Context, activity: Activity?, onPicked: (Float, Float) -> Unit) {
        pick(context, activity, null, onPicked)
    }

    /**
     * @param hostDialog 触发选点的弹窗（可为空）；选点期间隐藏，取完自动恢复
     * @param onPicked   回调绝对像素坐标（由百分比按当前屏幕换算）
     */
    fun pick(context: Context, activity: Activity?, hostDialog: android.app.Dialog?,
             onPicked: (Float, Float) -> Unit) {
        if (!Display.canDrawOverlay(context)) {
            Display.openOverlaySettings(context)
            return
        }
        removeNow()

        val ctx = context.applicationContext
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val sw = Display.screenSize(ctx)

        // 1) 隐藏本应用的一切遮挡，露出真实屏幕
        wasBallShown = FloatManager.isBallShown()
        FloatManager.hideAll()
        // 关键：连同工作台悬浮窗与悬浮弹窗一起让出。
        // 早前只隐藏了悬浮球/悬浮窗，从动作编辑框发起选点时，
        // 那个编辑框仍盖在屏幕上，用户根本看不到目标应用。
        FloatWindows.hideAll()
        hostActivity = activity
        this.hostDialog = hostDialog
        runCatching { hostDialog?.hide() }
        handler.postDelayed({
            runCatching {
                activity?.startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }, 80)

        // 2) 全屏选点层（背景透明，真实屏幕可见）
        val layer = PickLayer(ctx, sw.x, sw.y) { px, py ->
            onPicked(px, py)
            close()
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSPARENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        runCatching { manager.addView(layer, p) }
        view = layer
        wm = manager
    }

    /**
     * **真实滑动拾取**（滑动 / 手势动作的起终点）。
     *
     * 与 [pick] 的根本区别：[pick] 是"拖着准星找一个点"，
     * 这里是**用户在屏幕上真实滑一次**，抬手即定稿——起点 = 按下处，终点 = 抬起处。
     * 滑动参数要的就是"手指真实走过的那段"，用两个独立点去拼既别扭也不准
     * （尤其斜向滑动，用户很难凭空想出终点在哪）。
     *
     * 屏幕上会画出：起点「滑动开始点」、终点「滑动结束点」+ 准星、两者间的虚线箭头。
     * 底部「上一个点」= 清除本次滑动重来（滑歪了是最常见的情况）。
     *
     * @param onPicked 回调**绝对像素** (x1, y1, x2, y2)，由百分比按当前屏幕换算
     */
    fun pickSwipe(context: Context, activity: Activity?, hostDialog: android.app.Dialog?,
                  onPicked: (Float, Float, Float, Float) -> Unit) {
        if (!Display.canDrawOverlay(context)) {
            Display.openOverlaySettings(context)
            return
        }
        removeNow()

        val ctx = context.applicationContext
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val sw = Display.screenSize(ctx)

        wasBallShown = FloatManager.isBallShown()
        FloatManager.hideAll()
        FloatWindows.hideAll()
        hostActivity = activity
        this.hostDialog = hostDialog
        runCatching { hostDialog?.hide() }
        handler.postDelayed({
            runCatching {
                activity?.startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }, 80)

        val layer = SwipeLayer(ctx, sw.x, sw.y) { x1, y1, x2, y2 ->
            onPicked(x1, y1, x2, y2)
            close()
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSPARENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        runCatching { manager.addView(layer, p) }
        view = null          // 类型不同，单独记录
        swipeView = layer
        wm = manager
    }

    /** 完成：移除选点层，恢复弹窗与悬浮球，并把本应用带回前台 */
    fun close() {
        handler.post {
            removeNow()
            val act = hostActivity
            hostActivity = null
            val dlg = hostDialog
            hostDialog = null
            if (act != null) {
                runCatching {
                    act.startActivity(Intent(act, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            runCatching { dlg?.show() }
            if (wasBallShown) {
                val app = act ?: runCatching { com.autoball.App.get() }.getOrNull()
                if (app != null) FloatManager.showBall(app)
            }
        }
    }

    private fun removeNow() {
        val v = view
        if (v != null) runCatching { wm?.removeView(v) }
        view = null
        val sv = swipeView
        if (sv != null) runCatching { wm?.removeView(sv) }
        swipeView = null
        wm = null

        // 选点结束，恢复此前让出屏幕的本应用窗口
        FloatWindows.restore()
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    /** 全屏选点层：十字准星 + 百分比读数 + 底部操作条 */
    private class PickLayer(
        context: Context,
        private val screenW: Int,
        private val screenH: Int,
        private val onConfirm: (Float, Float) -> Unit
    ) : FrameLayout(context) {

        private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF0EA5E9")
            style = Paint.Style.STROKE
            strokeWidth = Display.dp(context, 1.6f)
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFE5484D")
            style = Paint.Style.FILL
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC0EA5E9")
            style = Paint.Style.STROKE
            strokeWidth = Display.dp(context, 2f)
        }
        private val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E61C1832")
            style = Paint.Style.FILL
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = Display.dp(context, 12f)
            textAlign = Paint.Align.CENTER
        }

        /** 百分比坐标 */
        private var pxPct = 50f
        private var pyPct = 50f

        /** 底部条；调节时自动淡出，避免挡住落点 */
        private var barView: View? = null

        init {
            setWillNotDraw(false)
            setBackgroundColor(Color.TRANSPARENT)   // 关键：露出真实屏幕
            buildBar()
        }

        private fun buildBar() {
            val bar = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 10f),
                    Display.dpInt(context, 14f), Display.dpInt(context, 14f))
                background = Theme.dialogBg()
                (background as android.graphics.drawable.GradientDrawable).cornerRadius =
                    Display.dp(context, 16f)
            }

            // **底部条必须极简**：原来这里有「四向微调箭头(38dp×1行) + 坐标读数 + 按钮」，
            // 合计约 150dp，屏幕下方一大片被挡住——而底部恰恰是最常取点的区域
            // （Dock 栏、导航栏、底部按钮）。取点时根本看不到自己点在哪。
            // 现在只留「一行提示 + 一行按钮」≈ 70dp。
            // 坐标读数不必再单独显示：onDraw 已在准星旁画了百分比小牌。
            val tipTv = TextView(context).apply {
                text = "滑动屏幕来调节位置"
                textSize = 11.5f
                setTextColor(Theme.textSec())
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, Display.dpInt(context, 6f))
            }
            bar.addView(tipTv)

            val btns = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val cancel = Ui.button(context, "取消", false)
            cancel.setOnClickListener { close() }
            btns.addView(cancel, LinearLayout.LayoutParams(0,
                Display.dpInt(context, 39f), 1f).apply {
                marginEnd = Display.dpInt(context, 9f)
            })
            val ok = Ui.button(context, "确定", true)
            ok.setOnClickListener { confirm() }
            btns.addView(ok, LinearLayout.LayoutParams(0,
                Display.dpInt(context, 39f), 1f))
            bar.addView(btns, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))

            // 半透明：底栏压在最常取点的屏幕下方区域，实心会挡住落点
            PickerBar.attach(bar)
            barView = bar
            addView(bar, LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.BOTTOM })
        }

        private fun confirm() {
            val px = pxPct / 100f * screenW
            val py = pyPct / 100f * screenH
            Ui.toast(context, "已选择位置 (${pxPct.toInt()}%, ${pyPct.toInt()}%)")
            onConfirm(px, py)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val x = pxPct / 100f * w
            val y = pyPct / 100f * h
            canvas.drawLine(x, 0f, x, h, cross)
            canvas.drawLine(0f, y, w, y, cross)
            canvas.drawCircle(x, y, Display.dp(context, 3f), dot)
            canvas.drawCircle(x, y, Display.dp(context, 16f), ring)
            // 坐标读数小牌
            val tw = Display.dp(context, 82f)
            val th = Display.dp(context, 20f)
            val lx = (x + Display.dp(context, 20f)).coerceAtMost(w - tw)
            val ly = (y - Display.dp(context, 28f)).coerceAtLeast(0f)
            canvas.drawRoundRect(lx, ly, lx + tw, ly + th,
                Display.dp(context, 6f), Display.dp(context, 6f), plate)
            canvas.drawText("${pxPct.toInt()}% , ${pyPct.toInt()}%",
                lx + tw / 2f, ly + th - Display.dp(context, 6f), text)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    barView?.let { PickerBar.setDragging(it, true) }
                    val w = width.toFloat(); val h = height.toFloat()
                    if (w > 0f && h > 0f) {
                        pxPct = ((event.x / w) * 100).coerceIn(0f, 100f)
                        pyPct = ((event.y / h) * 100).coerceIn(0f, 100f)
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    barView?.let { PickerBar.setDragging(it, false) }
                    return true
                }
            }
            return true
        }

    }

    /**
     * 滑动起终点拾取层（两点逐个定位）。
     *
     * **为什么不用"真实滑一次定两点"**：手指滑过屏幕时手会挡住视野，
     * 抬起位置也容易偏；更关键是用户常常需要单独微调其中一个点。
     * 改为逐个点定位：先定「滑动开始点」，点「下一个点」再定「滑动结束点」。
     *
     * **遮挡是这里的头号问题**：底部条原来有提示+读数+三个按钮约 150dp，
     * 而底部（Dock 栏、导航栏、底部按钮）恰恰是最常取点的区域。
     * 现在只留「一行提示 + 一行两按钮」≈ 70dp，并且按钮随状态切换：
     * 调起点时 [取消][下一个点]，调终点时 [上一个点][确定]——
     * 两个按钮比三个窄，遮挡进一步减小。
     */
    private class SwipeLayer(
        context: Context,
        private val screenW: Int,
        private val screenH: Int,
        private val onConfirm: (Float, Float, Float, Float) -> Unit
    ) : FrameLayout(context) {

        private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF0EA5E9")
            style = Paint.Style.STROKE
            strokeWidth = Display.dp(context, 2f)
            pathEffect = android.graphics.DashPathEffect(
                floatArrayOf(Display.dp(context, 7f), Display.dp(context, 5f)), 0f)
        }
        private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF0EA5E9")
            style = Paint.Style.FILL
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC0EA5E9")
            style = Paint.Style.STROKE
            strokeWidth = Display.dp(context, 2f)
        }
        private val startDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF22C55E")
            style = Paint.Style.FILL
        }
        private val endDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFE5484D")
            style = Paint.Style.FILL
        }
        private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFE5484D")
            style = Paint.Style.STROKE
            strokeWidth = Display.dp(context, 1.4f)
        }
        private val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E61C1832")
            style = Paint.Style.FILL
        }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = Display.dp(context, 11f)
            textAlign = Paint.Align.CENTER
        }

        /** 两点百分比坐标；cur=0 正在调起点，cur=1 正在调终点 */
        private val px = floatArrayOf(50f, 50f)
        private val py = floatArrayOf(62f, 32f)
        private var cur = 0

        private lateinit var tipTv: TextView
        private lateinit var leftBtn: TextView
        private lateinit var rightBtn: TextView

        /** 底部条；调节时自动淡出 */
        private var barView: View? = null

        init {
            setWillNotDraw(false)
            setBackgroundColor(Color.TRANSPARENT)
            buildBar()
        }

        private fun buildBar() {
            val bar = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 8f),
                    Display.dpInt(context, 14f), Display.dpInt(context, 10f))
                background = Theme.dialogBg()
                (background as android.graphics.drawable.GradientDrawable).cornerRadius =
                    Display.dp(context, 16f)
            }
            tipTv = TextView(context).apply {
                textSize = 11.5f
                setTextColor(Theme.textSec())
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, Display.dpInt(context, 6f))
            }
            bar.addView(tipTv)

            val btns = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            leftBtn = Ui.button(context, "取消", false)
            btns.addView(leftBtn, LinearLayout.LayoutParams(0,
                Display.dpInt(context, 39f), 1f).apply {
                marginEnd = Display.dpInt(context, 6f)
            })
            rightBtn = Ui.button(context, "下一个点", true)
            btns.addView(rightBtn, LinearLayout.LayoutParams(0,
                Display.dpInt(context, 39f), 1f).apply {
                marginStart = Display.dpInt(context, 3f)
            })
            bar.addView(btns, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
            // 半透明：底栏压在最常取点的屏幕下方区域，实心会挡住落点
            PickerBar.attach(bar)
            barView = bar
            addView(bar, LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.BOTTOM })
            syncBtns()
        }

        /** 按钮文案与行为随当前调的是哪个点而变 */
        private fun syncBtns() {
            if (cur == 0) {
                tipTv.text = "滑动屏幕调节「滑动开始点」"
                leftBtn.text = "取消"
                leftBtn.setOnClickListener { close() }
                rightBtn.text = "下一个点"
                rightBtn.setOnClickListener { cur = 1; syncBtns(); invalidate() }
            } else {
                tipTv.text = "滑动屏幕调节「滑动结束点」"
                leftBtn.text = "上一个点"
                leftBtn.setOnClickListener { cur = 0; syncBtns(); invalidate() }
                rightBtn.text = "确定"
                rightBtn.setOnClickListener { confirm() }
            }
        }

        private fun confirm() {
            Ui.toast(context, "已选择滑动 (${px[0].toInt()}%, ${py[0].toInt()}%) → " +
                "(${px[1].toInt()}%, ${py[1].toInt()}%)")
            onConfirm(px[0] / 100f * screenW, py[0] / 100f * screenH,
                px[1] / 100f * screenW, py[1] / 100f * screenH)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val ax = px[0] / 100f * w; val ay = py[0] / 100f * h
            val bx = px[1] / 100f * w; val by = py[1] / 100f * h

            // 虚线 + 箭头（方向 = 起点→终点）
            canvas.drawLine(ax, ay, bx, by, dash)
            val ang = Math.atan2((by - ay).toDouble(), (bx - ax).toDouble())
            val al = Display.dp(context, 11f)
            val spread = 0.42f
            for (sgn in doubleArrayOf(spread.toDouble(), -spread.toDouble())) {
                val a2 = ang + Math.PI + sgn
                canvas.drawLine(bx, by,
                    (bx + al * Math.cos(a2)).toFloat(),
                    (by + al * Math.sin(a2)).toFloat(), arrow)
            }

            // 起点：绿点 + 标签（正在调它时加外圈强调）
            canvas.drawCircle(ax, ay, Display.dp(context, 7f), startDot)
            if (cur == 0) canvas.drawCircle(ax, ay, Display.dp(context, 17f), ring)
            tag(canvas, w, ax, ay - Display.dp(context, 20f), "滑动开始点")
            // 终点：红点 + 准星 + 标签
            canvas.drawCircle(bx, by, Display.dp(context, 7f), endDot)
            canvas.drawCircle(bx, by, Display.dp(context, 17f), ring)
            val cl = Display.dp(context, 13f)
            canvas.drawLine(bx - cl, by, bx + cl, by, cross)
            canvas.drawLine(bx, by - cl, bx, by + cl, cross)
            tag(canvas, w, bx, by + Display.dp(context, 34f), "滑动结束点")
        }

        /** 坐标标签小牌（自动避开屏幕左右边界） */
        private fun tag(canvas: Canvas, w: Float, cx: Float, cyIn: Float, text: String) {
            val tw = Display.dp(context, 78f)
            val th = Display.dp(context, 19f)
            val lx = (cx - tw / 2f).coerceIn(0f, w - tw)
            val ly = cyIn.coerceIn(0f, (height - th).toFloat())
            canvas.drawRoundRect(lx, ly, lx + tw, ly + th,
                Display.dp(context, 6f), Display.dp(context, 6f), plate)
            canvas.drawText(text, lx + tw / 2f, ly + th - Display.dp(context, 5.5f), label)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    barView?.let { PickerBar.setDragging(it, true) }
                    px[cur] = ((event.x / w) * 100).coerceIn(0f, 100f)
                    py[cur] = ((event.y / h) * 100).coerceIn(0f, 100f)
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    barView?.let { PickerBar.setDragging(it, false) }
                }
            }
            return true
        }
    }

    /** 提示文案，用于表单「?」帮助 */
    fun helpText(field: String): String = when (field) {
        "点击位置" -> "屏幕百分比坐标。点「拾取」后本软件会让出屏幕，在任意应用或桌面上拖动准星定位，确定即取回真实坐标。"
        "按下时间" -> "按下到抬起的时长，单位毫秒。≥350ms 会被识别为长按。"
        "滑动时长" -> "滑动过程持续时间，越短越快。建议 300ms 左右。"
        "文本内容" -> "输入到当前焦点输入框的文本；无障碍后端要求输入框已获得焦点。"
        "目标应用" -> "应用包名，例如 com.android.settings。"
        "运行等待" -> "本动作执行后等待的时间，用于等待界面响应。"
        "重复次数" -> "本动作连续执行的次数。"
        "运行条件" -> "可选。例如 \$count > 3，条件不满足时跳过该动作。"
        else -> "该字段用于配置动作参数。"
    }

    fun helpView(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 11f
        setTextColor(Theme.textSec())
        setPadding(0, 0, 0, Display.dpInt(context, 6f))
    }
}
