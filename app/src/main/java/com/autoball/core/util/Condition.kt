package com.autoball.core.util

/**
 * 运行条件的轻量求值器。
 *
 * 刻意不依赖 JS 引擎——动作流必须能在没有引擎的情况下完整执行（需求 2.1）。
 * 支持：`$var == 值`、`$var != 值`、数值比较 `> >= < <=`，以及 `true/false/空`。
 */
object Condition {

    fun eval(expr: String?, vars: Map<String, String>): Boolean {
        if (expr.isNullOrBlank()) return true
        val e = expr.trim()
        when (e.lowercase()) {
            "true", "1" -> return true
            "false", "0", "" -> return false
        }

        val ops = listOf("==", "!=", ">=", "<=", ">", "<")
        for (op in ops) {
            val idx = e.indexOf(op)
            if (idx > 0) {
                val left = resolve(e.substring(0, idx).trim(), vars)
                val right = resolve(e.substring(idx + op.length).trim(), vars)
                return compare(left, right, op)
            }
        }
        // 无操作符：变量非空即为真
        val single = resolve(e, vars)
        return single.isNotEmpty() && single != "false" && single != "0"
    }

    private fun resolve(token: String, vars: Map<String, String>): String {
        val t = token.trim().trim('"', '\'')
        return if (t.startsWith("$")) vars[t.substring(1)] ?: "" else t
    }

    private fun compare(l: String, r: String, op: String): Boolean {
        val ln = l.toDoubleOrNull()
        val rn = r.toDoubleOrNull()
        return if (ln != null && rn != null) {
            when (op) {
                "==" -> ln == rn
                "!=" -> ln != rn
                ">" -> ln > rn
                ">=" -> ln >= rn
                "<" -> ln < rn
                "<=" -> ln <= rn
                else -> false
            }
        } else {
            when (op) {
                "==" -> l.equals(r, true)
                "!=" -> !l.equals(r, true)
                ">" -> l > r
                ">=" -> l >= r
                "<" -> l < r
                "<=" -> l <= r
                else -> false
            }
        }
    }
}
