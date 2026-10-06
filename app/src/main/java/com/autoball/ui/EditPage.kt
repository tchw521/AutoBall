package com.autoball.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.autoball.AB
import com.autoball.core.engine.ScriptLauncher
import com.autoball.core.model.Action
import com.autoball.core.model.Script
import com.autoball.core.recorder.GestureCompiler
import com.autoball.core.util.Display

/**
 * 编辑页：名称 / 循环 / 倍速；动作步骤列表（上移下移 + 单步删除）；实时 JS 预览。
 *
 * 需求 2.6：动作步骤长按可拖动排序——零依赖下用「↑ ↓」按钮实现等价能力，避免自造拖动排序的稳定性风险。
 */
class EditPage(context: Context, private val host: PageHost) : FrameLayout(context) {

    private var script: Script? = null
    private val nameEd = EditText(context)
    private val loopBox = TextView(context)
    private val speedEd = EditText(context)
    private val listBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val preview = TextView(context)

    private var loop = false

    init {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        root.addView(TextView(context).apply {
            text = "编辑"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Theme.textPri())
            setPadding(Display.dpInt(context, 16f), Display.dpInt(context, 18f),
                Display.dpInt(context, 16f), Display.dpInt(context, 10f))
        })

        // 名称
        val nameRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL }
        nameRow.addView(TextView(context).apply {
            text = "名称"; textSize = 12f; setTextColor(Theme.textSec())
            layoutParams = LinearLayout.LayoutParams(Display.dpInt(context, 56f),
                LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        nameEd.apply {
            setTextColor(Theme.textPri()); setHintTextColor(Theme.textSec())
            hint = "脚本名称"
        }
        nameRow.addView(nameEd, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(pad(nameRow))

        // 循环 / 倍速
        val optRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL }
        loopBox.apply {
            text = "循环：关"; textSize = 12f; setTextColor(Color.WHITE)
            background = Theme.bubble(context, Color.parseColor("#3A2E6B"), 12f)
            setPadding(Display.dpInt(context, 14f), Display.dpInt(context, 6f),
                Display.dpInt(context, 14f), Display.dpInt(context, 6f))
            setOnClickListener {
                loop = !loop
                text = "循环：" + if (loop) "开" else "关"
                background = Theme.bubble(context,
                    Color.parseColor(if (loop) Theme.BLUE else "#3A2E6B"), 12f)
                save()
            }
        }
        optRow.addView(loopBox)
        speedEd.apply {
            setText("1.0"); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(Theme.textPri())
            layoutParams = LinearLayout.LayoutParams(Display.dpInt(context, 64f),
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        optRow.addView(speedEd)
        optRow.addView(TextView(context).apply {
            text = "倍速"; textSize = 12f; setTextColor(Theme.textSec())
            setPadding(Display.dpInt(context, 6f), 0, 0, 0)
        })
        root.addView(pad(optRow))

        // 动作列表
        val listScroll = ScrollView(context)
        listScroll.addView(listBox, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(listScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // 底部工具条：运行 / 添加 / 录制 / JS
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Theme.card())
            setPadding(Display.dpInt(context, 8f), Display.dpInt(context, 8f),
                Display.dpInt(context, 8f), Display.dpInt(context, 8f))
        }
        bar.addView(toolBtn("运行", Theme.BLUE) { script?.let { ScriptLauncher.launch(context.applicationContext, it) } })
        bar.addView(toolBtn("添加动作", Theme.PURPLE) { addAction() })
        bar.addView(toolBtn("录制", "#35D08A") { host.startRecording() })
        bar.addView(toolBtn("JS 预览", "#FFB020") { togglePreview() })
        bar.addView(toolBtn("保存", "#22D3EE") { save(); host.refreshAll() })
        root.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        preview.apply {
            textSize = 11f
            setTextColor(Theme.textSec())
            setPadding(Display.dpInt(context, 12f), Display.dpInt(context, 8f),
                Display.dpInt(context, 12f), Display.dpInt(context, 8f))
            visibility = View.GONE
            setBackgroundColor(Color.parseColor(if (Theme.isDark()) "#1B1730" else "#F2F3FA"))
        }
        root.addView(preview, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Display.dpInt(context, 160f)))

        addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        script = AB.store.all().firstOrNull()
        bind(script)
    }

    fun bind(s: Script?) {
        script = s
        if (s == null) {
            nameEd.setText("")
            listBox.removeAllViews()
            listBox.addView(TextView(context).apply {
                text = "还没有脚本，先到「制作」新建一个"
                textSize = 13f; setTextColor(Theme.textSec()); gravity = Gravity.CENTER
                setPadding(0, Display.dpInt(context, 40f), 0, 0)
            })
            return
        }
        nameEd.setText(s.name)
        loop = s.flow?.loop ?: false
        loopBox.text = "循环：" + if (loop) "开" else "关"
        loopBox.background = Theme.bubble(context,
            Color.parseColor(if (loop) Theme.BLUE else "#3A2E6B"), 12f)
        speedEd.setText((s.flow?.speed ?: 1f).toString())
        rebuildActions()
    }

    private fun rebuildActions() {
        listBox.removeAllViews()
        listBox.setPadding(Display.dpInt(context, 12f), Display.dpInt(context, 6f),
            Display.dpInt(context, 12f), Display.dpInt(context, 12f))
        val actions = script?.flow?.actions ?: run {
            listBox.addView(TextView(context).apply {
                text = "脚本为空，请先添加一个动作"
                textSize = 13f; setTextColor(Theme.textSec()); gravity = Gravity.CENTER
                setPadding(0, Display.dpInt(context, 40f), 0, 0)
            })
            return
        }
        actions.forEachIndexed { i, a ->
            listBox.addView(actionRow(a, i, actions.size))
        }
        updatePreview()
    }

    private fun actionRow(a: Action, index: Int, size: Int): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.bubble(context, Theme.card(), 14f)
            setPadding(Display.dpInt(context, 12f), Display.dpInt(context, 8f),
                Display.dpInt(context, 10f), Display.dpInt(context, 8f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, Display.dpInt(context, 8f))
            layoutParams = lp
        }
        row.addView(TextView(context).apply {
            text = "${index + 1}"
            textSize = 11f; setTextColor(Theme.textSec())
            layoutParams = LinearLayout.LayoutParams(Display.dpInt(context, 22f),
                LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        val mid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        mid.addView(TextView(context).apply {
            text = a.type.label
            textSize = 14f; setTextColor(Theme.textPri())
        })
        mid.addView(TextView(context).apply {
            text = describe(a)
            textSize = 11f; setTextColor(Theme.textSec())
        })
        row.addView(mid)

        row.addView(miniBtn("↑") { move(index, -1) })
        row.addView(miniBtn("↓") { move(index, 1) })
        row.addView(miniBtn("✕") {
            script?.flow?.actions?.removeAt(index)
            save(); rebuildActions()
        })
        row.setOnLongClickListener {
            ActionEditor.show(context as? Activity ?: return@setOnLongClickListener true, a) { updated ->
                script?.flow?.actions?.set(index, updated)
                save(); rebuildActions()
            }
            true
        }
        return row
    }

    private fun describe(a: Action): String = when (a.type) {
        com.autoball.core.model.ActionType.CLICK,
        com.autoball.core.model.ActionType.CLICK_IMAGE,
        com.autoball.core.model.ActionType.CLICK_TEXT,
        com.autoball.core.model.ActionType.CLICK_COLOR,
        com.autoball.core.model.ActionType.CLICK_NODE,
        com.autoball.core.model.ActionType.AI_CLICK ->
            "(${a.x.toInt()}, ${a.y.toInt()}) · ${a.durationMs}ms"
        com.autoball.core.model.ActionType.SWIPE ->
            "(${a.x.toInt()}, ${a.y.toInt()}) → (${a.x2.toInt()}, ${a.y2.toInt()})"
        com.autoball.core.model.ActionType.INPUT_TEXT -> a.text ?: ""
        com.autoball.core.model.ActionType.OPEN_APP -> a.pkg ?: ""
        com.autoball.core.model.ActionType.OPEN_URL -> a.url ?: ""
        com.autoball.core.model.ActionType.KEY -> "keyCode ${a.keyCode}"
        com.autoball.core.model.ActionType.SET_VAR -> "${a.varName} = ${a.varValue}"
        com.autoball.core.model.ActionType.CONTROL_FLOW -> a.controlOp.label
        else -> "等待 ${a.waitMs}ms · 重复 ${a.repeat} 次"
    }

    private fun move(index: Int, delta: Int) {
        val list = script?.flow?.actions ?: return
        val to = index + delta
        if (to < 0 || to >= list.size) return
        val item = list.removeAt(index)
        list.add(to, item)
        save()
        rebuildActions()
    }

    private fun addAction() {
        val s = script ?: return
        val act = context as? Activity ?: return
        ActionEditor.show(act, null) { a ->
            s.flow?.actions?.add(a)
            save()
            rebuildActions()
        }
    }

    private fun togglePreview() {
        preview.visibility = if (preview.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        updatePreview()
    }

    private fun updatePreview() {
        val f = script?.flow ?: return
        preview.text = GestureCompiler.toJs(f)
    }

    private fun save() {
        val s = script ?: return
        s.name = nameEd.text.toString().ifBlank { "未命名脚本" }
        s.flow?.loop = loop
        s.flow?.speed = speedEd.text.toString().toFloatOrNull() ?: 1f
        s.flow?.name = s.name
        s.updatedAt = System.currentTimeMillis()
        AB.store.save(s)
    }

    // ---------- 小部件 ----------

    private fun pad(v: View): View {
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(Display.dpInt(context, 16f), 0, Display.dpInt(context, 16f),
            Display.dpInt(context, 8f))
        v.layoutParams = lp
        return v
    }

    private fun toolBtn(text: String, color: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Theme.bubble(context, Color.parseColor(color), 12f)
            setPadding(Display.dpInt(context, 10f), Display.dpInt(context, 7f),
                Display.dpInt(context, 10f), Display.dpInt(context, 7f))
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(Display.dpInt(context, 3f), 0, Display.dpInt(context, 3f), 0)
            layoutParams = lp
            setOnClickListener { onClick() }
        }

    private fun miniBtn(text: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(Theme.textSec())
            gravity = Gravity.CENTER
            setPadding(Display.dpInt(context, 8f), Display.dpInt(context, 4f),
                Display.dpInt(context, 8f), Display.dpInt(context, 4f))
            setOnClickListener { onClick() }
        }

    private fun confirmDelete(a: Action) {
        val act = context as? Activity ?: return
        AlertDialog.Builder(act).setMessage("删除该动作？")
            .setPositiveButton("删除") { d, _ ->
                script?.flow?.actions?.remove(a); save(); rebuildActions(); d.dismiss()
            }.setNegativeButton("取消", null).show()
    }
}
