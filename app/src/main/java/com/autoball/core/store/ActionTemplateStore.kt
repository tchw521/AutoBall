package com.autoball.core.store

import com.autoball.App
import com.autoball.core.model.Action
import com.autoball.core.model.ActionType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 动作模板库（R-103）。
 *
 * 常见的多步序列（如「点击 → 等待 0.5s → 点击」）反复手写很烦，
 * 单步复制粘贴（v1.22.0）只解决了一步，多步序列仍要一个个加。
 *
 * 模板 = 命名的一组动作 + 说明。插入时**深拷贝**并重新分配 id——
 * 复用了 EditPage 复制粘贴的同一套思路，避免插入后编辑影响原模板。
 */
object ActionTemplateStore {

    data class Template(
        var id: String = "",
        var name: String = "",
        var desc: String = "",
        val actions: MutableList<Action> = ArrayList()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("desc", desc)
            put("actions", JSONArray().apply { actions.forEach { put(it.toJson()) } })
        }

        companion object {
            fun fromJson(o: JSONObject): Template = Template().apply {
                id = o.optString("id", "")
                name = o.optString("name", "未命名模板")
                desc = o.optString("desc", "")
                o.optJSONArray("actions")?.let { a ->
                    for (i in 0 until a.length()) {
                        runCatching { Action.fromJson(a.getJSONObject(i)) }
                            .getOrNull()?.let { actions.add(it) }
                    }
                }
            }
        }
    }

    private fun file(): File = File(App.get().filesDir, "action_templates.json")

    fun all(): List<Template> {
        if (!file().exists()) return BUILT_IN
        return runCatching {
            val o = JSONObject(file().readText())
            val list = ArrayList<Template>()
            o.optJSONArray("items")?.let { a ->
                for (i in 0 until a.length())
                    list.add(Template.fromJson(a.getJSONObject(i)))
            }
            list
        }.getOrDefault(BUILT_IN)
    }

    fun save(list: List<Template>) {
        runCatching {
            file().writeText(JSONObject().apply {
                put("items", JSONArray().apply { list.forEach { put(it.toJson()) } })
            }.toString())
        }
    }

    fun add(name: String, desc: String, actions: List<Action>): Template {
        val t = Template(
            id = "at_" + System.currentTimeMillis().toString(36),
            name = name, desc = desc)
        t.actions.addAll(actions.map { clone(it) })
        val list = all().toMutableList()
        list.add(t)
        save(list)
        return t
    }

    fun delete(id: String) { save(all().filter { it.id != id }) }

    /**
     * 插入用拷贝：重新分配 id，避免插入后编辑影响原模板。
     *
     * 原先与 EditPage 的复制/粘贴各自手写一份逐字段拷贝（R-001 三次法则），
     * 三处都漏掉过 colorHex / nodeSpec / imageRef / failOp 等字段，
     * 且嵌套结构是浅拷贝。现统一走 [Action.copy]。
     */
    fun clone(a: Action): Action = a.copy(newId = true)

    /** 内置模板：覆盖最常见的组合，用户可直接用也可自建 */
    val BUILT_IN: List<Template> = listOf(
        Template("at_builtin_click_wait", "点击 + 等待", "点一下，等 0.5 秒再做下一步").apply {
            actions.add(Action().apply {
                id = Action.newId(); type = ActionType.CLICK
                x = 50f; y = 50f; durationMs = 60; waitMs = 500
            })
        },
        Template("at_builtin_triple", "连续点三下", "间隔 300 毫秒点三次").apply {
            actions.add(Action().apply {
                id = Action.newId(); type = ActionType.CLICK
                x = 50f; y = 50f; durationMs = 60
                repeat = 3; repeatIntervalMs = 300
            })
        },
        Template("at_builtin_swipe_up", "上滑", "从屏幕下方滑到上方，常用于刷列表").apply {
            actions.add(Action().apply {
                id = Action.newId(); type = ActionType.SWIPE
                x = 50f; y = 80f; x2 = 50f; y2 = 20f; durationMs = 500
            })
        },
        Template("at_builtin_back_home", "返回 + 回桌面", "先返回一次，再回主屏幕").apply {
            actions.add(Action().apply {
                id = Action.newId(); type = ActionType.KEY; keyCode = 4; waitMs = 300
            })
            actions.add(Action().apply {
                id = Action.newId(); type = ActionType.KEY; keyCode = 3
            })
        }
    )
}
