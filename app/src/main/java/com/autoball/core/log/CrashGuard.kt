package com.autoball.core.log

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.ScrollView
import android.widget.TextView
import com.autoball.App
import java.io.File

/**
 * 崩溃捕获。
 *
 * 目的：真机闪退时用户拿不到 logcat，这里把完整堆栈写进私有目录，
 * 下次启动主动弹窗展示并支持复制，避免只能靠猜。
 *
 * 合规：日志只落在本应用私有目录，不上传。
 */
object CrashGuard {

    private const val FILE = "crash.log"
    private const val MAX_KEEP = 6

    @Volatile
    private var lastCrash: String? = null

    fun install() {
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val sb = StringBuilder()
            sb.append("时间：").append(java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())).append('\n')
            sb.append("线程：").append(t.name).append('\n')
            sb.append("异常：").append(e.javaClass.name).append('\n')
            sb.append("信息：").append(e.message ?: "（无）").append('\n')
            sb.append("设备：").append(android.os.Build.MANUFACTURER).append(' ')
                .append(android.os.Build.MODEL).append(" / Android ")
                .append(android.os.Build.VERSION.RELEASE).append(" (API ")
                .append(android.os.Build.VERSION.SDK_INT).append(")\n\n")
            for (st in e.stackTrace) sb.append("    at ").append(st.toString()).append('\n')
            var c = e.cause
            var depth = 0
            while (c != null && depth < 5) {
                sb.append("\nCaused by: ").append(c.javaClass.name).append(": ")
                    .append(c.message ?: "").append('\n')
                for (st in c.stackTrace) sb.append("    at ").append(st.toString()).append('\n')
                c = c.cause
                depth++
            }
            val text = sb.toString()
            lastCrash = text
            write(text)
            old?.uncaughtException(t, e)
        }
    }

    private fun file(): File = File(App.get().filesDir, FILE)

    private fun write(text: String) {
        runCatching {
            val f = file()
            val prev = if (f.exists()) f.readText() else ""
            val keep = prev.split("\n=====\n").filter { it.isNotBlank() }.take(MAX_KEEP - 1)
            val out = (listOf(text) + keep).joinToString("\n=====\n")
            f.writeText(out)
        }
    }

    /** 上次运行是否发生过崩溃 */
    fun hasSavedCrash(): Boolean = runCatching { file().exists() && file().length() > 0 }.getOrElse { false }

    fun savedCrash(): String = runCatching { file().readText() }.getOrElse { "读取失败：${it.message}" }

    fun clear() = runCatching { file().delete() }

    /** 记录非致命异常（页面构建失败等），同样可查看 */
    fun report(where: String, e: Throwable) {
        val sb = StringBuilder()
        sb.append("位置：").append(where).append('\n')
        sb.append("异常：").append(e.javaClass.name).append(": ").append(e.message ?: "").append('\n')
        for (st in e.stackTrace) sb.append("    at ").append(st.toString()).append('\n')
        write(sb.toString())
    }

    /** 启动后展示上次崩溃，便于定位 */
    fun showIfSaved(activity: Activity) {
        if (!hasSavedCrash()) return
        val text = savedCrash()
        val tv = TextView(activity).apply {
            this.text = text
            textSize = 11f
            setTextColor(0xFFF2F3FF.toInt())
            setPadding(40, 32, 40, 32)
            setHorizontallyScrolling(true)
        }
        val scroll = ScrollView(activity).apply { addView(tv) }
        activity.runOnUiThread {
            runCatching {
                AlertDialog.Builder(activity)
                    .setTitle("上次运行发生闪退")
                    .setView(scroll)
                    .setPositiveButton("复制并清空") { d, _ ->
                        runCatching {
                            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("autoball-crash", text))
                        }
                        clear()
                        d.dismiss()
                    }
                    .setNegativeButton("清空") { d, _ -> clear(); d.dismiss() }
                    .setNeutralButton("稍后处理", null)
                    .show()
            }
        }
    }
}
