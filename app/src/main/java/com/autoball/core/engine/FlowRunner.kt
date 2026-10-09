package com.autoball.core.engine

import com.autoball.AB
import com.autoball.App
import com.autoball.core.RunControl
import com.autoball.core.backend.BackendRouter
import com.autoball.core.backend.ExecContext
import com.autoball.core.log.RunLog
import com.autoball.core.model.*
import com.autoball.core.util.Condition
import com.autoball.core.util.ConditionEval
import com.autoball.core.util.CoordMapper
import com.autoball.core.util.Display

/**
 * 动作流执行器：**不经 JS 引擎**，直接把结构化动作交给后端执行。
 *
 * 与 JS 脚本共用同一套 Action 语义与同一个后端路由，
 * 因此"点击 / 录制 / JS"三种来源在运行时是同一条链路。
 */
class FlowRunner(
    private val router: BackendRouter,
    private val control: RunControl,
    private val ctx: ExecContext,
    private val log: RunLog,
    /** 运行 JS 片段（RUN_JS 动作）；无引擎时返回 false 并记录 */
    private val jsEval: ((String, ExecContext) -> Boolean)? = null,
    /** 运行子脚本（RUN_SCRIPT 动作）；由协调器注入以防重入 */
    private val runScript: ((String) -> Boolean)? = null,
    /** 坐标缩放（多分辨率适配）：由调用方按录制签名计算后传入 */
    private val scale: CoordMapper.Scale = CoordMapper.Scale.NONE,
    /** 进度回调（动作序号从 0 开始）：用于悬浮窗显示当前步骤名 */
    private val onProgress: ((index: Int, total: Int, action: com.autoball.core.model.Action) -> Unit)? = null,
    /** 手势矩阵变形参数（v3 morph）；null 表示不做变换 */
    private val morph: Morph.Params? = null,
    /** 屏幕中心（变换原点），由调用方按当前屏幕注入 */
    private val center: Pair<Float, Float> = 0f to 0f
) {

    class Outcome(
        val ok: Boolean,
        val executed: Int,
        val failed: Int,
        val message: String?
    )

    fun run(flow: Flow): Outcome {
        var executed = 0
        var failed = 0
        val speed = if (flow.speed > 0f) flow.speed else 1f
        val hooks = flow.hooks

        /**
         * 执行**动作级**监听钩子（R-139）。
         *
         * 此前 `Action.listeners` 只是被 UI 写入、从未被运行时读取——
         * 又一个"界面显示已设置、执行时完全不生效"的静默失效。
         * 而且它早前复用脚本级 9 时机的 key（SB/LT/BR…），
         * 两类语义完全不同的钩子会互相覆盖；现改用 [ActionHookStage] 独立 key。
         */
        fun fireAction(a: com.autoball.core.model.Action, st: com.autoball.core.model.ActionHookStage) {
            val ha = a.listeners[st.key] ?: return
            if (!ha.enabled) return
            if (control.canceled) return
            runCatching { execOne(ha) }
                .onFailure { log.warn(ctx.runId, "监听动作[${st.label}]异常：${it.message}") }
        }

        /** 执行某个时机的全部监听动作（v3 9 钩子） */
        fun fire(stage: String) {
            val list = hooks[stage] ?: return
            for (ha in list) {
                if (control.canceled) return
                if (!ha.enabled) continue
                runCatching { execOne(ha) }
                    .onFailure { log.warn(ctx.runId, "监听动作[$stage]异常：${it.message}") }
            }
        }

        val total = flow.actions.count { it.enabled }
        var round = 0
        var shown = 0
        while (true) {
            fire("lt")            // 列表开头
            if (control.canceled) return Outcome(false, executed, failed, "已停止")
            for (a in flow.actions) {
                if (control.canceled) {
                    return Outcome(false, executed, failed, "已停止")
                }
                if (a.enabled) {
                    onProgress?.invoke(shown, total, a)
                    shown++
                }
                control.checkStep()
                control.checkPause()
                if (!a.enabled) continue
                // 跳转模式：跳过目标步骤之前的动作
                val jt = jumpTo
                if (jt != null) {
                    if (a.id != jt) continue
                    jumpTo = null
                }

                fire("br")        // 每个动作运行前
                if (control.canceled) return Outcome(false, executed, failed, "已停止")

                // 运行条件：走 ConditionEval 按类型真实求值。
                // 早前直接用 Condition.eval() 把整段 JSON 当字符串判空——
                // 非空即真，等于所有识别类条件（图片/文字/颜色）**从不生效**，
                // 而界面上还显示「已设置」。
                /**
                 * 「重复检查直到成功」：不成立时按间隔重试。
                 *
                 * 只对 NOT_SATISFIED 重试——UNKNOWN 是**能力缺失**，
                 * 重试多少次还是 UNKNOWN，只会白白拖慢脚本。
                 */
                fun evalWithRetry(a: com.autoball.core.model.Action,
                                  set: com.autoball.core.model.ConditionSet)
                        : ConditionEval.Outcome {
                    val first = ConditionEval.eval(a.condition, ctx.vars, probe())
                    if (first != ConditionEval.Outcome.NOT_SATISFIED) return first
                    val cfg = set.items.firstOrNull { it.retry } ?: return first
                    var n = 0
                    while (true) {
                        if (control.canceled) return first
                        n++
                        if (cfg.retryMax > 0 && n > cfg.retryMax) {
                            log.warn(ctx.runId,
                                "重复检查 ${cfg.retryMax} 次仍未满足，跳过该动作")
                            return first
                        }
                        if (!control.sleep(cfg.retryIntervalMs)) return first
                        val out = ConditionEval.eval(a.condition, ctx.vars, probe())
                        if (out != ConditionEval.Outcome.NOT_SATISFIED) return out
                    }
                }

                // 等待只执行一次：「等待前检查」决定它发生在判定之前还是之后
                var waited = false
                fun doWait(): Boolean {
                    if (waited) return true
                    waited = true
                    // speed 来自用户输入的倍速框：填 0 会除零 →
                    // Float 得 Infinity → toLong() 得 Long.MAX_VALUE → **睡到天荒地老**，
                    // 表现为"脚本卡住不动"，且看不出原因（日志里没有这一句）。
                    val sp = if (speed > 0.01f) speed else 1f
                    return control.sleep((a.preDelayMs / sp).toLong())
                }

                if (a.condition != null) {
                    fireAction(a, com.autoball.core.model.ActionHookStage.BEFORE_COND)
                    val set = com.autoball.core.model.ConditionSet.parse(a.condition)
                    // 默认「等待后检查」：先跑完等待再判定，界面更可能已稳定
                    val before = set.items.any { it.checkBefore }
                    if (!before && !doWait()) {
                        return Outcome(false, executed, failed, "已停止")
                    }
                    val out = evalWithRetry(a, set)
                    when (out) {
                        ConditionEval.Outcome.NOT_SATISFIED -> {
                            ctx.log("跳过 ${a.type.label}（${ConditionEval.describe(a.condition)}）")
                            // 条件失败 → 触发 cf，然后 re（条件跳过也进"结束后"）
                            fireAction(a, com.autoball.core.model.ActionHookStage.COND_FAIL)
                            fireAction(a, com.autoball.core.model.ActionHookStage.AFTER_END)
                            continue
                        }
                        ConditionEval.Outcome.UNKNOWN -> {
                            // 能力不足无法判定：明确记日志，按"不满足"处理。
                            // 静默当作成立会导致脚本在错误界面上乱点。
                            val why = ConditionEval.describe(a.condition)
                            log.warn(ctx.runId, "条件无法判定（$why），按不满足跳过")
                            AB.log.warn(ctx.runId,
                                "运行条件需要对应能力：${howToFix(a.condition)}")
                            fireAction(a, com.autoball.core.model.ActionHookStage.COND_FAIL)
                            fireAction(a, com.autoball.core.model.ActionHookStage.AFTER_END)
                            continue
                        }
                        ConditionEval.Outcome.SATISFIED -> {
                            fireAction(a, com.autoball.core.model.ActionHookStage.COND_OK)
                        }
                    }
                }
                if (!doWait()) {
                    return Outcome(false, executed, failed, "已停止")
                }

                fireAction(a, com.autoball.core.model.ActionHookStage.BEFORE_RUN)

                val reps = a.repeat.coerceAtLeast(1)
                var okAll = true
                for (r in 0 until reps) {
                    var ok = execOne(a)
                    // 失败自动重试一次（v3 retry）
                    if (!ok && flow.retryOnce && r == reps - 1) {
                        log.warn(ctx.runId, "动作失败，自动重试一次：${a.type.label}")
                        if (control.sleep(200)) ok = execOne(a)
                    }
                    executed++
                    // 每次重复都触发：成功后每次成功触发、失败后每次失败触发
                    fireAction(a, if (ok) com.autoball.core.model.ActionHookStage.AFTER_OK
                    else com.autoball.core.model.ActionHookStage.AFTER_FAIL)
                    if (!ok) { failed++; okAll = false }
                    if (r < reps - 1) {
                        if (!control.sleep((a.repeatIntervalMs / speed).toLong())) {
                            return Outcome(false, executed, failed, "已停止")
                        }
                    }
                }
                // 每步失败处理（R-113）：全局开关之外，允许单步覆盖策略。
                // 有的步骤失败无所谓（找图没找到），有的必须停（付款前校验）。
                if (!okAll && a.type != ActionType.RUN_JS) fire("er")
                if (!okAll && !handleFail(a, flow)) {
                    return Outcome(false, executed, failed,
                        "动作失败，已按「${a.failOp.label}」处理")
                }

                onProgress?.invoke(-1, total, a)
                // 自动精灵：运行条件可引用前序动作的执行状态。
                // 写入三个变量供后续动作的「运行条件」表达式使用：
                //   $ok    全局：截至目前是否全部成功
                //   $last  上一个动作是否成功（1/0）
                //   $stepN 第 N 个动作是否成功（如 $step3）
                runCatching { ctx.vars["last"] = if (okAll) "1" else "0" }
                runCatching { ctx.vars["ok"] = if (failed == 0) "1" else "0" }
                runCatching { ctx.vars["step${executed - failed}"] = if (okAll) "1" else "0" }
                fire("ba")        // 每个动作运行后
                // 「有动作失败立即暂停」的全局分支已下移到 [handleFail] 统一处理：
                // 单步失败策略优先于全局开关，这样关键步骤可以单独设终止、
                // 次要步骤设继续，而不必全脚本一刀切。
                if (!okAll && a.type != ActionType.RUN_JS) {
                    // 单个动作失败不中止整体，交由上层策略决定是否继续
                    log.warn(ctx.runId, "动作失败：${a.type.label}")
                }
                if (!control.sleep((a.waitMs / speed).toLong())) {
                    return Outcome(false, executed, failed, "已停止")
                }
                // 动作间默认等待（v3 全局设置）
                if (flow.defaultWaitMs > 0 &&
                    !control.sleep((flow.defaultWaitMs / speed).toLong())) {
                    return Outcome(false, executed, failed, "已停止")
                }
                fire("ae")        // 每个动作运行结束后
                // 动作级「结束后」：重复全部完成后只触发一次
                fireAction(a, com.autoball.core.model.ActionHookStage.AFTER_END)
            }

            fire("le")            // 列表结尾
            if (!flow.loop) break
            round++
            if (flow.loopCount > 0 && round >= flow.loopCount) break
            if (control.canceled) break
        }

        val msg = if (failed == 0) null else "完成，其中 $failed 个动作失败"
        if (failed > 0) {
            log.errorWithVars(ctx.runId, msg ?: "存在失败动作", ctx.vars)
        }
        return Outcome(failed == 0, executed, failed, msg)
    }

    /** 单步执行：供调试使用 */
    fun runSingle(action: Action): Boolean = execOne(action)

    /**
     * 处理单步失败，返回是否继续执行后续动作。
     *
     * 优先级：单步策略 > 全局「失败立即暂停」。
     * 默认 [FailOp.NEXT] 时仍沿用全局设置，保证老脚本行为不变。
     */
    private fun handleFail(a: Action, flow: Flow): Boolean {
        val op = if (a.failOp == FailOp.NEXT && flow.failStop) FailOp.PAUSE else a.failOp
        return when (op) {
            FailOp.NEXT -> true
            FailOp.PAUSE -> {
                log.errorWithVars(ctx.runId,
                    "动作失败，暂停脚本：${a.type.label}", ctx.vars)
                control.pause()
                false
            }
            FailOp.STOP -> {
                log.errorWithVars(ctx.runId,
                    "动作失败，终止脚本：${a.type.label}", ctx.vars)
                false
            }
            FailOp.JUMP -> {
                val target = a.failJumpTo
                if (target.isNullOrBlank()) {
                    log.error(ctx.runId, "跳转目标未设置，终止脚本")
                    return false
                }
                // 目标步骤在后面 → 跳过中间步骤；在前面 → 由上层循环处理
                jumpTo = target
                log.warn(ctx.runId, "动作失败，跳转到步骤 $target")
                true
            }
        }
    }

    /** [FailOp.JUMP] 的目标步骤 id；非空时跳过中间步骤 */
    private var jumpTo: String? = null

    /**
     * 对坐标类动作施加矩阵变形。
     *
     * 只处理有坐标的类型（点击/长按/滑动…），其余原样返回——
     * 对「等待」「返回键」这类动作做抖动没有意义。
     */
    /**
     * 每步坐标随机微调（R-114）。
     *
     * 与全局手势变形（morph）的分工：morph 是整段脚本的仿射矩阵，
     * 这里只作用于本步——用于"关键步骤必须精确、次要步骤可以抖"的混搭。
     *
     * 只处理带坐标的动作；对「等待」「按键」抖动没有意义。
     */
    private fun jitterAction(a: Action): Action {
        val r = a.jitterDp
        if (r <= 0) return a
        // ActionType.hasCoord 已由 fieldGroups 推导（含点击族与滑动族），不要另写一份
        if (!a.type.hasCoord) return a
        val px = Display.dp(App.get(), (r * 2).toFloat()) / 2f
        fun j(v: Float): Float =
            // Math.random() 是 Double，必须先转 Float——
            // 否则整个表达式是 Double，coerceIn(0f,100f) 没有 Double 重载会编译失败
            (v + (Math.random().toFloat() * 2f - 1f) * px / screenW() * 100f)
                .coerceIn(0f, 100f)
        val c = a.copy()
        c.x = j(a.x); c.y = j(a.y)
        if (a.x2 != 0f || a.y2 != 0f) { c.x2 = j(a.x2); c.y2 = j(a.y2) }
        return c
    }

    private fun screenW(): Float =
        Display.screenSize(App.get()).x.toFloat().coerceAtLeast(1f)

    private fun morphAction(a: Action): Action {
        val p = morph ?: return a
        if (!a.type.hasCoord) return a
        val (cx, cy) = center
        val (nx, ny) = Morph.point(p, a.x, a.y, cx, cy)
        val out = a.copy()
        out.x = nx; out.y = ny
        if (a.x2 != 0f || a.y2 != 0f) {
            val (nx2, ny2) = Morph.point(p, a.x2, a.y2, cx, cy)
            out.x2 = nx2; out.y2 = ny2
        }
        if (a.durationMs > 0) out.durationMs = Morph.duration(p, a.durationMs)
        return out
    }

    /**
     * 条件探测能力。
     *
     * 只实现**当前真实具备**的能力：颜色匹配直接用截图像素算。
     * 图像 / 文字匹配依赖按需下载的识别模块，缺了就返回 null（→ UNKNOWN），
     * 绝不假装判定成功。
     */
    private fun probe(): ConditionEval.Probe = object : ConditionEval.Probe {
        override fun screen(): com.autoball.core.backend.ScreenResult.Ok? {
            val sr = runCatching { router.screenshot(ctx) }.getOrNull() ?: return null
            return sr as? com.autoball.core.backend.ScreenResult.Ok
        }

        override fun findColor(hex: String, tol: Int, region: FloatArray?): Boolean? {
            val sr = screen() ?: return null
            val pos = ConditionEval.findColorPos(sr, hex, tol, region)
            lastMatchPos = pos
            return pos != null
        }

        override fun findText(text: String, region: FloatArray?): Boolean? {
            // 需要 OCR 或节点树——两者都是按需能力，未安装时无法判定
            return null
        }

        override fun findImage(path: String, threshold: Float, region: FloatArray?,
                               res: com.autoball.core.model.ActionCondition.MultiRes,
                               fast: Boolean, minCount: Int): Boolean? {
            // 模板由取图器存本机；取不到就是没有配过或被清了，无法判定
            val tpl = com.autoball.core.store.TemplateStore.load(path) ?: return null
            val sr = screen() ?: return null
            // 传 meta：模板按策略投影到当前屏幕，否则跨分辨率必然匹配失败
            return ConditionEval.matchTemplatePos(
                sr, tpl, threshold.coerceIn(0.5f, 1f), region,
                com.autoball.core.store.TemplateStore.metaOf(path),
                res, fast, minCount) != null
        }

        override fun evalJs(expr: String): Boolean? =
            jsEval?.let { fn -> runCatching { fn(expr, ctx) }.getOrNull() }

        /**
         * 节点存在（R-116）。无障碍**独有**能力——Shizuku 后端直接返回 null
         * 表示无法判定，绝不返回 false（那会让条件在 Shizuku 下恒不成立）。
         */
        override fun findNode(spec: com.autoball.core.model.NodeSpec?): Boolean? {
            if (!router.accessibility.isAvailable()) {
                log.warn(ctx.runId, "条件「节点存在」需要无障碍通道，当前不可用")
                return null
            }
            return runCatching { router.accessibility.hasNode(spec) }.getOrNull()
        }

        /**
         * 位置周围条件：以**最近一次颜色匹配的命中点**为基准偏移。
         *
         * 基准点由 [matchColorWithPos] 写入 [lastMatchPos]——先在主区域找到
         * 主色，再校验它周围若干偏移点的颜色，这正是「多点找色」的做法。
         * 主色没命中过就无法判定（返回 null），绝不猜。
         */
        override fun probeAt(dxDp: Float, dyDp: Float, hex: String, tol: Int): Boolean? {
            val sr = screen() ?: return null
            val (mx, my) = lastMatchPos ?: return null
            val px = (mx + Display.dp(App.get(), dxDp)).toInt()
            val py = (my + Display.dp(App.get(), dyDp)).toInt()
            if (px < 0 || py < 0 || px >= sr.width || py >= sr.height) return false
            return ConditionEval.colorAt(sr, px, py, hex, tol)
        }
    }

    /** 最近一次颜色匹配的命中点（像素），供周围条件探针作基准 */
    private var lastMatchPos: Pair<Int, Int>? = null

    /** 无法判定时的修复指引，写进日志帮用户定位 */
    private fun howToFix(raw: String?): String {
        val k = runCatching {
            org.json.JSONObject(raw ?: return "").optString("k", "")
        }.getOrDefault("")
        return when (k) {
            "IMG" -> "图片匹配模块未安装（市场 → 扩展模块）"
            "TEXT" -> "文字识别需要 OCR 模块或无障碍节点通道"
            "COLOR" -> "需要截图能力：请开启无障碍或 Shizuku 任一授权"
            "JS" -> "JS 引擎不可用"
            else -> "未知条件类型"
        }
    }

    private fun execOne(a: Action): Boolean {
        return when (a.type) {
            ActionType.SET_VAR -> {
                val n = a.varName
                if (n.isNullOrEmpty()) return true
                ctx.setVar(n, a.varValue ?: a.text ?: "")
                log.info(ctx.runId, "设置变量 $n")
                true
            }
            ActionType.CONTROL_FLOW -> execControl(a)
            ActionType.RUN_ACTIONS -> {
                if (a.subActions.isEmpty()) return true
                val sub = FlowRunner(router, control, ctx, log, jsEval, runScript, scale)
                val nested = Flow().apply {
                    actions = a.subActions
                    speed = 1f
                }
                val o = sub.run(nested)
                o.failed == 0
            }
            ActionType.RUN_JS -> {
                val code = a.code
                if (code.isNullOrEmpty()) return true
                val fn = jsEval
                if (fn == null) {
                    log.warn(ctx.runId, "JS 引擎不可用，跳过运行JS代码")
                    false
                } else fn(code, ctx)
            }
            ActionType.RUN_SCRIPT -> {
                val sid = a.scriptId
                if (sid.isNullOrEmpty()) return true
                val fn = runScript
                if (fn == null) { log.warn(ctx.runId, "无法运行子脚本"); false } else fn(sid)
            }
            else -> {
                val target = CoordMapper.applyTo(morphAction(jitterAction(a)), scale)
                val r = router.execute(target, ctx)
                log.add(ctx.runId,
                    if (r.ok) RunLog.Level.OK else RunLog.Level.ERROR,
                    target.type.label, r.message, a.id, r.backend.label, r.latencyMs)
                if (r.degraded) log.warn(ctx.runId, "降级执行：${r.message}")
                r.ok
            }
        }
    }

    private fun execControl(a: Action): Boolean {
        return when (a.controlOp) {
            ControlOp.PAUSE -> { control.pause(); true }
            ControlOp.RESUME -> { control.resume(); true }
            ControlOp.STOP -> { control.cancel(); true }
            ControlOp.WAIT -> control.sleep(a.durationMs)
            ControlOp.GOTO -> {
                log.warn(ctx.runId, "跳转指令在动作流中按顺序执行，暂不支持回跳")
                true
            }
        }
    }
}
