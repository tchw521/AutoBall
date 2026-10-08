package com.autoball.core.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import com.autoball.AB
import com.autoball.App
import com.autoball.core.util.Display
import com.autoball.ui.Kit
import com.autoball.ui.Theme
import com.autoball.ui.Ui
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 脚本内的用户交互（alert / confirm / prompt / select / toast）。
 *
 * 难点：JS 在**后台线程**同步执行，而弹窗必须在主线程显示。
 * 所以这里做的事是——把 UI 抛到主线程，用 latch 阻塞等待结果，再带回 JS。
 *
 * 三条路径（按 R-003，能力不足时**如实告知**，绝不伪造"用户已确认"）：
 * 1. 有前台 Activity → 应用内 AlertDialog
 * 2. 无 Activity 但有悬浮窗权限 → 悬浮窗弹窗（脚本正在操作别的应用时）
 * 3. 两者都没有 → 记日志 + 返回"未获得答复"的安全默认值
 *
 * 所有等待都带超时：脚本跑在后台，若用户不理会弹窗，不能把线程永久挂住。
 */
object JsUi {

    private val main = Handler(Looper.getMainLooper())

    /** 弹窗无人应答时的默认等待上限 */
    private const val DEFAULT_TIMEOUT_MS = 30_000L

    /** 结果：是否拿到答复 + 答复内容 */
    class Answer(val got: Boolean, val value: String? = null,
                         val picked: List<Int> = emptyList())

    fun toast(ctx: Context, msg: String, durationMs: Int) {
        main.post {
            runCatching { Ui.toast(ctx, msg) }
        }
        // toast 不阻塞脚本（自动精灵里也是瞬时的），仅按需要让出一点时间
        if (durationMs > 0) runCatching { Thread.sleep(durationMs.toLong().coerceAtMost(3000)) }
    }

    /** alert：只提示，返回 true 表示成功展示 */
    fun alert(ctx: Context, msg: String, title: String?, timeoutMs: Long): Boolean =
        show(ctx, title ?: "提示", timeoutMs) { host, done ->
            host.text(msg)
            host.positive("好") { done(Answer(true)); true }
        }?.got == true

    /** confirm：返回 true/false；拿不到答复时返回 false（安全默认） */
    fun confirm(ctx: Context, msg: String, title: String?, timeoutMs: Long): Boolean =
        show(ctx, title ?: "请确认", timeoutMs) { host, done ->
            host.text(msg)
            host.positive("确定") { done(Answer(true, "true")); true }
            host.negative("取消") { done(Answer(false, "false")) }
        }?.let { it.got && it.value == "true" } ?: false

    /** prompt：返回输入文本；拿不到答复时返回原默认值 */
    fun prompt(ctx: Context, msg: String, def: String, title: String?, timeoutMs: Long): String =
        show(ctx, title ?: "请输入", timeoutMs) { host, done ->
            host.text(msg)
            val et = Ui.adText(ctx, def, "选填")
            host.bodyExtra(et)
            host.positive("确定") { done(Answer(true, et.text.toString())); true }
            host.negative("取消") { done(Answer(false, def)) }
        }?.value ?: def

    /**
     * select：单选/多选列表。
     * 返回 JSON 串：result（是否有答复）+ items（选中下标数组）。
     * 拿不到答复时 result=false、items 为空。
     */
    fun select(ctx: Context, title: String, items: List<String>, selected: List<Int>,
               multi: Boolean, timeoutMs: Long): Answer =
        show(ctx, title, timeoutMs) { host, done ->
            val picked = selected.toMutableList()
            val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            fun rebuild() {
                box.removeAllViews()   // 复用同一个容器，swapBody 才能认出它
                items.forEachIndexed { i, label ->
                    val on = picked.contains(i)
                    box.addView(Kit.valueRow(ctx, label,
                        if (on) "已选中" else null, if (on) "✓" else "", Theme.pri()) {
                        if (multi) {
                            if (picked.contains(i)) picked.remove(i) else picked.add(i)
                        } else {
                            picked.clear(); picked.add(i)
                        }
                        if (!multi) done(Answer(true, null, picked.toList()))
                        else rebuild()
                    })
                }
                host.swapBody(box)
            }
            rebuild()
            if (multi) host.positive("确定") { done(Answer(true, null, picked.toList())); true }
            host.negative("取消") { done(Answer(false)) }
        } ?: Answer(false)

    // ---------- 通用显示 ----------

    /**
     * @param build 收集内容与按钮回调；**必须在 open 之前跑完**，
     *              因为 Ui.DialogBuilder 的按钮要在 show() 前设好。
     *              所以流程是：先 build 收集 → 再 open → 最后等结果。
     */
    private fun show(ctx: Context, title: String, timeoutMs: Long,
                     build: (Host, (Answer) -> Unit) -> Unit): Answer? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            AB.log.warn("jsui", "脚本在主线程调用弹窗，已跳过（会死锁）")
            return null
        }
        val latch = CountDownLatch(1)
        val ref = AtomicReference<Answer?>()
        val hostRef = AtomicReference<Host?>()
        val posted = main.post {
            val host = Host(ctx, title)
            hostRef.set(host)
            build(host) { ans ->
                ref.set(ans)
                latch.countDown()
            }
            if (host.open() == null) {
                AB.log.warn("jsui", "无法显示弹窗：既没有前台界面，也没有悬浮窗权限")
                ref.set(null); latch.countDown()
            }
        }
        if (!posted) return null
        val ok = latch.await(timeoutMs.coerceIn(1000, 120_000), TimeUnit.MILLISECONDS)
        if (!ok) {
            AB.log.warn("jsui", "弹窗超时未应答（${timeoutMs}ms），按未选择处理")
            main.post { runCatching { hostRef.get()?.close() } }
            return null
        }
        return ref.get()
    }

    /**
     * 弹窗宿主：屏蔽「应用内 Dialog」与「悬浮窗 Dialog」的差异（R-001）。
     *
     * 两者 API 形似但类型不同、生命周期也不同；
     * 又都要求**在 show 之前**设好内容与按钮，所以这里做成"收集器"：
     * 先把内容攒起来，open() 时一次性装配。
     */
    private class Host(private val ctx: Context, private val title: String) {

        private val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        private var alert: android.app.AlertDialog? = null
        private var floatDlg: com.autoball.float.FloatDialog? = null
        private var pendingPositive: Pair<String, () -> Boolean>? = null
        private var pendingNegative: Pair<String, () -> Unit>? = null

        fun text(msg: String) {
            box.addView(TextView(ctx).apply {
                this.text = msg
                textSize = 13f
                setTextColor(Theme.textPri())
                setPadding(Display.dpInt(ctx, 14f), Display.dpInt(ctx, 12f),
                    Display.dpInt(ctx, 14f), Display.dpInt(ctx, 8f))
            })
        }

        /** 追加一块内容（输入框、选项列表等） */
        fun bodyExtra(v: android.view.View) {
            (v.parent as? android.view.ViewGroup)?.removeView(v)
            box.addView(v)
        }

        /** select 多选时重建列表：清空后重加 */
        fun swapBody(v: android.view.View) {
            for (i in box.childCount - 1 downTo 0) {
                val c = box.getChildAt(i)
                // 只清掉"选项列表"这一层：用 tag 标记，避免把提示文字也删了
                if (c === lastList) box.removeViewAt(i)
            }
            lastList = v
            bodyExtra(v)
        }

        private var lastList: android.view.View? = null

        fun positive(t: String, onClick: () -> Boolean) { pendingPositive = t to onClick }
        fun negative(t: String, onClick: () -> Unit) { pendingNegative = t to onClick }

        fun open(): Any? {
            val act = App.get().topActivity()
            if (act != null) {
                val b = Ui.dialog(ctx, title).body(box).maxHeight(0.8f)
                pendingPositive?.let { (t, cb) -> b.positive(t, cb) }
                pendingNegative?.let { (t, cb) -> b.negative(t, cb) }
                alert = b.show()
                return alert
            }
            if (!Display.canDrawOverlay(ctx)) return null
            val f = com.autoball.float.FloatDialog.show(ctx, title).body(box)
            pendingPositive?.let { (t, cb) -> f.positive(t, cb) }
            pendingNegative?.let { (t, cb) -> f.negative(t, cb) }
            floatDlg = f
            return if (f.show()) f else null
        }

        fun close() {
            runCatching { alert?.dismiss() }
            runCatching { floatDlg?.dismiss() }
        }
    }

    /** JS 侧的 select 结果序列化 */
    fun selectJson(a: Answer, items: List<String>): String =
        org.json.JSONObject()
            .put("result", a.got)
            .put("items", JSONArray().apply { a.picked.forEach { put(it) } })
            .put("values", JSONArray().apply { a.picked.forEach { put(items.getOrNull(it) ?: "") } })
            .toString()
}
