package com.autoball.core.engine

import com.autoball.core.RunControl
import com.autoball.core.backend.BackendRouter
import com.autoball.core.backend.ExecContext
import com.autoball.core.backend.ScreenResult
import com.autoball.core.log.RunLog
import com.autoball.core.model.*
import com.autoball.core.util.Condition
import org.json.JSONArray
import org.json.JSONObject

/**
 * JS 宿主 API：click / longClick / swipe / sleep / globalAction / log / toast /
 * screenshot / findNode / setVar / getVar / stop / press / key / input / openApp
 *
 * 引擎通过"方法名 + JSON 参数数组"回调进来，返回 JSON 结果串。
 * 这样 QuickJS 与 Rhino 共用同一份实现，API 表面完全一致。
 */
class JsHost(
    private val router: BackendRouter,
    private val ctx: ExecContext,
    private val control: RunControl,
    private val log: RunLog
) {

    /**
     * 最近一次触摸位置（供 getMousePosition 使用）。
     *
     * 自动精灵里这是"调试用指针位置"；真机上没有鼠标，
     * 所以返回本引擎最后一次派发的坐标，语义更接近"上次点在哪"。
     */
    private var lastTouch: Pair<Float, Float>? = null

    /** 最近一次 findLocation(type=image) 的相似度，用于回填 similarity 字段 */
    private var lastImageSimilarity: Float = 0f

    companion object {
        /** sleep 上限：避免 sleep(MAX_INT) 让脚本永不响应停止 */
        const val MAX_SLEEP_MS = 300_000L
    }

    /**
     * 坐标解析（R-122）：支持像素数字、`'50%'`、`'200dp'` 三种写法。
     *
     * 自动精灵的坐标就支持这三种，脚本里写百分比能保证多分辨率适配。
     * 换算必须在**宿主侧**完成：[Action] 的坐标字段一律存百分比，
     * 若把像素透传进去，`CoordMapper` 会再按录制签名缩放一次，双重缩放。
     */
    private fun coord(v: Any?, isX: Boolean): Float {
        val app = com.autoball.App.get()
        val sz = com.autoball.core.util.Display.screenSize(app)
        val total = (if (isX) sz.x else sz.y).toFloat().coerceAtLeast(1f)
        return when (v) {
            is Number -> v.toFloat()
            is String -> {
                val t = v.trim()
                when {
                    t.endsWith("%", true) ->
                        (t.dropLast(1).toFloatOrNull() ?: 0f) / 100f * total
                    t.endsWith("dp", true) ->
                        com.autoball.core.util.Display.dp(app, t.dropLast(2).toFloatOrNull() ?: 0f)
                    else -> t.toFloatOrNull() ?: 0f
                }
            }
            else -> 0f
        }
    }

    private fun screen(): com.autoball.core.backend.ScreenResult.Ok? {
        val sr = runCatching { router.screenshot(ctx) }.getOrNull() ?: return null
        return sr as? com.autoball.core.backend.ScreenResult.Ok
    }

    /** 返回 JSON：{"ok":bool,"value":<任意>,"error":string?} */
    fun call(name: String, argsJson: String): String {
        // 通用转发（R-121）：prelude 里的 __host('click', x, y) 走这里。
        // args[0] 是真实 API 名，其余是参数——拆开后递归，逻辑仍只写一份。
        if (name == "host") {
            val arr = JSONArray(argsJson)
            val real = arr.optString(0, "")
            if (real.isEmpty()) return err("缺少 API 名")
            val rest = JSONArray()
            for (i in 1 until arr.length()) rest.put(arr.get(i))
            return call(real, rest.toString())
        }
        return try {
            val args = JSONArray(argsJson)
            val v = when (name) {
                "click" -> {
                    val a = tapAction(coord(args.opt(0), true), coord(args.opt(1), false), 80)
                    lastTouch = a.x to a.y
                    exec(a)
                }
                "press" -> {
                    val a = tapAction(coord(args.opt(0), true), coord(args.opt(1), false),
                        args.optLong(2, 80).coerceAtLeast(10))
                    lastTouch = a.x to a.y
                    exec(a)
                }
                "longClick" -> {
                    val a = tapAction(coord(args.opt(0), true), coord(args.opt(1), false),
                        args.optLong(2, 600).coerceAtLeast(350))
                    lastTouch = a.x to a.y
                    exec(a)
                }
                "swipe" -> {
                    val a = Action().apply {
                        type = ActionType.SWIPE
                        x = coord(args.opt(0), true); y = coord(args.opt(1), false)
                        x2 = coord(args.opt(2), true); y2 = coord(args.opt(3), false)
                        durationMs = args.optLong(4, 300).coerceAtLeast(30)
                    }
                    // 取终点：滑动后手指停在终点，调试时更关心"停在哪"
                    lastTouch = a.x2 to a.y2
                    exec(a)
                }
                "sleep" -> {
                    val ms = args.optLong(0, 0).coerceIn(0, MAX_SLEEP_MS)
                    val ok = control.sleep(ms)
                    if (ok) true else throw CancelException("已停止")
                }
                "globalAction" -> {
                    val a = Action().apply {
                        type = ActionType.KEY
                        keyCode = when (args.optString(0, "").lowercase()) {
                            "back" -> android.view.KeyEvent.KEYCODE_BACK
                            "home" -> android.view.KeyEvent.KEYCODE_HOME
                            "recents", "recent" -> android.view.KeyEvent.KEYCODE_APP_SWITCH
                            "notifications" -> android.view.KeyEvent.KEYCODE_NOTIFICATION
                            else -> args.optInt(0, android.view.KeyEvent.KEYCODE_BACK)
                        }
                    }
                    exec(a)
                }
                "key" -> exec(Action().apply {
                    type = ActionType.KEY
                    keyCode = args.optInt(0, android.view.KeyEvent.KEYCODE_BACK)
                })
                "input" -> exec(Action().apply {
                    type = ActionType.INPUT_TEXT
                    text = args.optString(0, "")
                })
                "openApp" -> exec(Action().apply {
                    type = ActionType.OPEN_APP
                    pkg = args.optString(0, "")
                })
                "toast" -> {
                    val a = Action().apply { type = ActionType.TOAST; text = args.optString(0, "") }
                    exec(a)
                }
                "screenshot" -> {
                    val sr = router.screenshot(ctx)
                    when (sr) {
                        is ScreenResult.Ok -> {
                            log.info(ctx.runId, "截图 ${sr.width}x${sr.height} (${sr.backend.label})")
                            JSONObject().put("w", sr.width).put("h", sr.height).put("backend", sr.backend.name)
                        }
                        is ScreenResult.Unavailable -> throw CancelException(sr.reason)
                    }
                }
                /**
                 * findNode（对齐自动精灵）：返回完整节点对象
                 * {text, className, packageName, boundLeft/Top/Right/Bottom, children[]}。
                 *
                 * 第二参 options 支持 {findAll, withChildren}；
                 * 选择器支持字符串（按文字）或对象 {text,id,className}。
                 * 无障碍不可用时返回 **null**（无法判定），不是空数组——
                 * 两者语义不同，脚本 `if (!node) throw` 才能区分"没权限"和"没找到"。
                 */
                "findNode" -> {
                    if (!router.accessibility.isAvailable()) {
                        log.warn(ctx.runId, "findNode 需要无障碍通道，Shizuku 后端不支持")
                        null
                    } else {
                        val q = args.opt(0)
                        val spec = when (q) {
                            is JSONObject -> NodeSpec(
                                text = q.optString("text").takeIf { it.isNotBlank() },
                                id = q.optString("idResName").takeIf { it.isNotBlank() }
                                    ?: q.optString("id").takeIf { it.isNotBlank() },
                                className = q.optString("className").takeIf { it.isNotBlank() },
                                desc = q.optString("desc").takeIf { it.isNotBlank() },
                                clickableOnly = q.optBoolean("clickableOnly", false))
                            else -> NodeSpec(text = args.optString(0, ""))
                        }
                        val opt = args.opt(1)
                        val oo = opt as? JSONObject
                        val findAll = oo?.optBoolean("findAll", false) ?: false
                        val withKids = oo?.optBoolean("withChildren", false) ?: false
                        val list = router.accessibility.nodeSnapshots(
                            Action().apply { nodeSpec = spec }, findAll, withKids)
                            ?: return err("findNode 需要无障碍服务")   // 无法判定
                        fun toJson(n: com.autoball.core.backend.AccessibilityBackend.NodeSnapshot)
                                : JSONObject = JSONObject()
                            .put("text", n.text ?: "")
                            .put("desc", n.desc ?: "")
                            .put("className", n.className ?: "")
                            .put("packageName", n.packageName ?: "")
                            .put("boundLeft", n.boundLeft).put("boundTop", n.boundTop)
                            .put("boundRight", n.boundRight).put("boundBottom", n.boundBottom)
                            .put("clickable", n.clickable)
                            .put("x", (n.boundLeft + n.boundRight) / 2)
                            .put("y", (n.boundTop + n.boundBottom) / 2)
                            .put("children", JSONArray().apply {
                                n.children.forEach { put(toJson(it)) } })
                        if (findAll) JSONArray().apply { list.forEach { put(toJson(it)) } }
                        else if (list.isEmpty()) null else toJson(list[0])
                    }
                }
                "clickText" -> {
                    val a = Action().apply {
                        type = ActionType.CLICK_TEXT
                        text = args.optString(0, "")
                    }
                    exec(a)
                }
                // ---- 用户交互（自动精灵 alert/confirm/prompt/select/toast）----
                // 脚本跑在后台线程，弹窗要切主线程并阻塞等待；
                // 没有前台界面也没有悬浮窗权限时**如实返回未答复**（R-003）
                "alert" -> JsUi.alert(com.autoball.App.get(),
                    args.optString(0, ""), args.opt(1).asTitle(),
                    args.opt(1).asDuration(30_000L))
                "confirm" -> JsUi.confirm(com.autoball.App.get(),
                    args.optString(0, ""), args.opt(1).asTitle(),
                    args.opt(1).asDuration(30_000L))
                "prompt" -> JsUi.prompt(com.autoball.App.get(),
                    args.optString(0, ""), args.optString(1, ""),
                    args.opt(2).asTitle(), args.opt(2).asDuration(30_000L))
                "select" -> {
                    val o = args.optJSONObject(0) ?: args.opt(0).let {
                        runCatching { JSONObject(it.toString()) }.getOrNull()
                    }
                    val items = parseStringList(o?.opt("items")).ifEmpty {
                        parseStringList(o?.opt("items"))   // 兼容直接传数组
                    }
                    val sel = o?.optJSONArray("selectItems")
                        ?.let { a -> (0 until a.length()).mapNotNull { items.indexOf(a.optString(it)).takeIf { it >= 0 } } }
                        ?: emptyList()
                    val multi = o?.optBoolean("multi", false) ?: false
                    val ans = JsUi.select(com.autoball.App.get(),
                        o?.optString("title", "请选择") ?: "请选择", items, sel, multi,
                        o?.optLong("duration", 30_000L) ?: 30_000L)
                    JsUi.selectJson(ans, items)
                }
                "toast" -> {
                    JsUi.toast(com.autoball.App.get(), args.optString(0, ""),
                        args.optInt(1, 0))
                    true
                }
                // ---- console（R-131 调试闭环）----
                // show/hide/clear 走悬浮窗：脚本运行时用户在别的应用里，
                // 应用内日志页根本看不到，等于盲调。
                "console" -> {
                    val lv = args.optString(0, "log")
                    val msg = args.optString(1, "")
                    when (lv) {
                        "show" -> com.autoball.float.FloatConsole.show(com.autoball.App.get())
                        "hide" -> { com.autoball.float.FloatConsole.hide(); true }
                        "clear" -> { log.clear(); true }
                        "error" -> log.error(ctx.runId, "JS: $msg")
                        "warn" -> log.warn(ctx.runId, "JS: $msg")
                        else -> log.info(ctx.runId, "JS: $msg")
                    }
                }
                "setVar" -> {
                    // 第三参 scope="global"：全局作用域（跨动作保留）
                    ctx.setVar(args.optString(0, ""), args.optString(1, ""),
                        args.optString(2, "").equals("global", true))
                    true
                }
                "getVar" -> {
                    val global = args.optString(1, "").equals("global", true)
                    ctx.getVar(args.optString(0, ""), global) ?: ""
                }
                "deleteVar" -> {
                    ctx.deleteVar(args.optString(0, ""),
                        args.optString(1, "").equals("global", true))
                    true
                }
                "clearVars" -> {
                    ctx.clearVars(args.optString(0, "").equals("global", true))
                    true
                }
                /** printVars：弹窗展示所有变量（自动精灵里是 UI，这里同样弹窗） */
                "printVars" -> {
                    val txt = ctx.vars.entries.joinToString("\n") { (k, v) -> "$k = $v" }
                        .ifEmpty { "（当前没有变量）" }
                    JsUi.alert(com.autoball.App.get(), txt, "变量", 30_000L)
                }
                "log" -> {
                    val msg = args.optString(0, "")
                    log.info(ctx.runId, "JS: $msg")
                    true
                }
                "getVars" -> {
                    val o = JSONObject()
                    ctx.vars.forEach { (k, v2) -> o.put(k, v2) }
                    o
                }
                // 定位与点击解耦（R-123）：脚本先拿到坐标，再决定点不点、点几次
                // 第二参传 true：返回全部匹配（自动精灵 findLocation(q, true)）
                "findLocation" -> findLocation(args.optString(0, ""),
                    args.optBoolean(1, false))
                "getScreenColor" -> {
                    val sr = screen() ?: throw CancelException("截图不可用")
                    val px = coord(args.opt(0), true).toInt()
                    val py = coord(args.opt(1), false).toInt()
                    if (px < 0 || py < 0 || px >= sr.width || py >= sr.height)
                        throw CancelException("坐标超出屏幕")
                    "#%06X".format(0xFFFFFF and sr.pixels[py * sr.width + px])
                }
                "getScreenAreaColors" -> {
                    val sr = screen() ?: throw CancelException("截图不可用")
                    val o = runCatching { JSONObject(args.optString(0, "{}")) }.getOrNull()
                    val x0 = coord(o?.opt("x") ?: 0, true).toInt().coerceIn(0, sr.width - 1)
                    val y0 = coord(o?.opt("y") ?: 0, false).toInt().coerceIn(0, sr.height - 1)
                    val w = coord(o?.opt("width") ?: 10, true).toInt().coerceIn(1, sr.width - x0)
                    val h = coord(o?.opt("height") ?: 10, false).toInt().coerceIn(1, sr.height - y0)
                    val sample = (o?.optInt("sample", 1) ?: 1).coerceAtLeast(1)
                    val rows = JSONArray()
                    var y = y0
                    while (y < y0 + h) {
                        val row = JSONArray()
                        var x = x0
                        while (x < x0 + w) {
                            row.put("#%06X".format(0xFFFFFF and sr.pixels[y * sr.width + x]))
                            x += sample
                        }
                        rows.put(row); y += sample
                    }
                    rows
                }
                // ---- 文件与本地存储（R-124）----
                // 路径一律映射到应用私有目录：目标 SDK 34 下无法写 /sdcard，
                // 但脚本照原样写也能跑通（语义一致），且 ../ 会被拒绝。
                "readFile" -> com.autoball.core.store.ScriptFiles
                    .read(args.optString(0, "")) ?: ""
                "writeFile" -> com.autoball.core.store.ScriptFiles
                    .write(args.optString(0, ""), args.optString(1, ""))
                "appendFile" -> com.autoball.core.store.ScriptFiles
                    .append(args.optString(0, ""), args.optString(1, ""))
                "getStorage" -> com.autoball.core.store.ScriptFiles
                    .getStorage(args.optString(0, ""), args.optString(1, "")) ?: ""
                "setStorage" -> {
                    com.autoball.core.store.ScriptFiles
                        .setStorage(args.optString(0, ""), args.optString(1, ""), args.optString(2, ""))
                    true
                }
                "removeStorage" -> {
                    com.autoball.core.store.ScriptFiles
                        .removeStorage(args.optString(0, ""), args.optString(1, ""))
                    true
                }
                // ---- 手势（R-126）----
                "gesture" -> gesture(args)
                "gestures" -> gestures(args)
                // 状态式：按下 → 移动(可多次) → 抬起，抬起时才真正派发
                "touchDown" -> {
                    downX = coord(args.opt(0), true)
                    downY = coord(args.opt(1), false)
                    downPts.clear()
                    downPts.add(com.autoball.core.model.Pt(downX, downY))
                    true
                }
                "touchMove" -> {
                    downPts.add(com.autoball.core.model.Pt(
                        coord(args.opt(0), true), coord(args.opt(1), false)))
                    true
                }
                "touchUp" -> {
                    if (downPts.size < 1) throw CancelException("touchUp 前未 touchDown")
                    if (downPts.size == 1) {
                        // 只按下没移动 → 当成一次点击补上终点，否则点数不足无法派发
                        downPts.add(com.autoball.core.model.Pt(downX, downY))
                    }
                    val ms = args.optLong(0, 300).coerceAtLeast(30)
                    val a = Action().apply {
                        id = Action.newId()
                        type = ActionType.GESTURE_SINGLE
                        path = ArrayList(downPts)
                        durationMs = ms
                    }
                    downPts.clear()
                    exec(a)
                }
                /**
                 * runAction（R-125）：动态构造一个动作并立即执行。
                 *
                 * **必须拒绝 RUN_JS / SET_VAR**（自动精灵文档也这样警告）：
                 * 这两类动作要回到 JS 引擎执行，而当前正**在**引擎里——
                 * 同一个 QuickJS Context 不可重入，会直接卡死。宁可报错也不要挂起。
                 */
                // ---- 按键（自动精灵用名字而非数字键码）----
                "keyPress" -> {
                    val names = (0 until args.length())
                        .map { args.optString(it, "") }.filter { it.isNotBlank() }
                    val codes = keyNamesToCodes(*names.toTypedArray())
                    if (codes.isEmpty()) throw CancelException("未知按键名：${names.joinToString()}")
                    codes.forEach { c ->
                        exec(Action().apply {
                            id = Action.newId(); type = ActionType.KEY; keyCode = c })
                    }
                    true
                }
                "keyDown" -> exec(Action().apply {
                    id = Action.newId(); type = ActionType.KEY
                    keyCode = keyCodeOf(args.optString(0, "")) })
                // 按键动作本身就是"按下+抬起"，抬起无独立语义
                "keyUp" -> true
                // ---- 剪贴板 / 设备信息 ----
                "getClipboard" -> {
                    val cm = com.autoball.App.get().getSystemService(
                        android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    cm?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                }
                "setClipboard" -> {
                    val cm = com.autoball.App.get().getSystemService(
                        android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    cm?.setPrimaryClip(android.content.ClipData
                        .newPlainText("autoball", args.optString(0, "")))
                    cm != null
                }
                "getDeviceInfo" -> JSONObject()
                    .put("model", android.os.Build.MODEL)
                    .put("brand", android.os.Build.BRAND)
                    .put("device", android.os.Build.DEVICE)
                    .put("sdk", android.os.Build.VERSION.SDK_INT)
                    .put("release", android.os.Build.VERSION.RELEASE)
                    .put("manufacturer", android.os.Build.MANUFACTURER)
                "getAppVersion" -> runCatching {
                    val app = com.autoball.App.get()
                    app.packageManager.getPackageInfo(app.packageName, 0).versionName
                }.getOrDefault("unknown")
                "getInstalledAppInfo" -> {
                    val app = com.autoball.App.get()
                    val info = runCatching {
                        app.packageManager.getPackageInfo(args.optString(0, ""), 0) }.getOrNull()
                    if (info == null) null else JSONObject()
                        .put("packageName", info.packageName)
                        .put("versionName", info.versionName ?: "")
                        .put("versionCode", if (android.os.Build.VERSION.SDK_INT >= 28)
                            info.longVersionCode else info.versionCode.toLong())
                }
                "vibrator" -> {
                    val app = com.autoball.App.get()
                    if (app.checkSelfPermission(android.Manifest.permission.VIBRATE)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        log.warn(ctx.runId, "vibrator 需要 VIBRATE 权限")
                        false
                    } else {
                        val v = app.getSystemService(
                            android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                        val ms = args.optLong(0, 200)
                        if (v == null) false else {
                            if (android.os.Build.VERSION.SDK_INT >= 26)
                                v.vibrate(android.os.VibrationEffect.createOneShot(
                                    ms, args.optInt(1, 255).coerceIn(1, 255)))
                            else @Suppress("DEPRECATION") v.vibrate(ms)
                            true
                        }
                    }
                }
                // ---- 网络（脚本主动发起，与应用自身不联网定位无关）----
                "requestUrl" -> requestUrl(args.opt(0))
                // ---- 依赖 OCR / 云端：如实报错，不静默返回空（R-003）----
                "ocr" -> throw CancelException("本应用未内置 OCR 模块，ocr() 不可用")
                "recognitionScreen" -> {
                    // 退化为无障碍节点树文本（本地即得，不需要 OCR）
                    val a = Action().apply {
                        id = Action.newId(); type = ActionType.RECOGNIZE_SCREEN
                        varName = (args.opt(0) as? JSONObject)
                            ?.optString("varName", "screen") ?: "screen"
                    }
                    exec(a)
                    ctx.getVar(a.varName ?: "screen") ?: ""
                }
                /** playMedia：播放音频，同步等到播完（上限 120s，期间响应停止） */
                "playMedia" -> playMedia(args.optString(0, ""))
                "getMousePosition" -> {
                    val p = lastTouch
                    if (p == null) null else JSONObject()
                        .put("x", p.first.toDouble()).put("y", p.second.toDouble())
                }
                "runAction" -> runAction(args.optString(0, ""))
                "check" -> evalCondition(args.optString(0, ""))
                "stop" -> { control.cancel(); true }
                "isCanceled" -> control.canceled
                "backend" -> router.status().firstOrNull { it.second.name == "READY" }?.first?.name ?: "NONE"
                else -> throw CancelException("未知 API: $name")
            }
            JSONObject().put("ok", true).put("value", v ?: JSONObject.NULL).toString()
        } catch (ce: CancelException) {
            JSONObject().put("ok", false).put("error", ce.message).toString()
        } catch (e: Throwable) {
            JSONObject().put("ok", false).put("error", e.message ?: "宿主异常").toString()
        }
    }

    private fun err(msg: String): String =
        JSONObject().put("ok", false).put("error", msg).toString()

    /**
     * findLocation（R-123）：按类型在屏幕里找，返回坐标（三种单位一次给全）。
     *
     * type 取值：color / node / image / text。找不到返回 **null**（不是抛异常），
     * 脚本写 `if (!loc) throw ...` 更顺手。
     * 能力不具备（如无 OCR）同样返回 null 并记日志——按 R-003，
     * 绝不伪造一个坐标让脚本点错地方。
     */
    private fun findLocation(q: String, all: Boolean = false): Any? {
        val o = runCatching { JSONObject(q) }.getOrNull()
        val type = o?.optString("type") ?: "text"
        val region = o?.optJSONArray("region")?.let { a ->
            if (a.length() == 4) floatArrayOf(
                a.optDouble(0, 0.0).toFloat(), a.optDouble(1, 0.0).toFloat(),
                a.optDouble(2, 100.0).toFloat(), a.optDouble(3, 100.0).toFloat()
            ) else null
        }
        val pt: Pair<Float, Float>? = when (type) {
            "color" -> {
                val sr = screen() ?: return null
                val hex = o?.optString("color") ?: o?.optString("value") ?: return null
                val tol = o?.optInt("tol", 10) ?: 10
                com.autoball.core.util.ConditionEval.findColorPos(sr, hex, tol, region)
                    ?.let { it.first.toFloat() to it.second.toFloat() }
            }
            "node" -> {
                if (!router.accessibility.isAvailable()) {
                    log.warn(ctx.runId, "findLocation(node) 需要无障碍通道")
                    return null
                }
                val spec = NodeSpec(text = o?.optString("text"), id = o?.optString("id"))
                router.accessibility.nodeCenter(Action().apply {
                    this.type = ActionType.CLICK_NODE
                    nodeSpec = spec
                })?.let { it.x to it.y }
            }
            "image" -> {
                val sr = screen() ?: return null
                val id = o?.optString("template") ?: o?.optString("value") ?: return null
                val tpl = com.autoball.core.store.TemplateStore.load(id) ?: return null
                // R-130：现在返回**模板实际所在位置**，不再是区域中心
                val m = com.autoball.core.util.ConditionEval.matchTemplatePos(
                    sr, tpl, (o?.optDouble("similarity", 0.9) ?: 0.9).toFloat(), region,
                    com.autoball.core.store.TemplateStore.metaOf(id))
                if (m == null) null else {
                    // 扫描超时：结果是局部最优，坐标可能不准——必须告知（R-003）
                    if (!m.complete) log.warn(ctx.runId,
                        "模板匹配扫描超时，返回局部最优（相似度 ${"%.2f".format(m.similarity)}）")
                    lastImageSimilarity = m.similarity
                    m.x to m.y
                }
            }
            else -> {
                log.warn(ctx.runId, "findLocation 暂不支持 type=$type（缺 OCR/识别模块）")
                null
            }
        }
        if (pt == null) return null
        val one = toLoc(pt, if (type == "image") lastImageSimilarity else 1f)
        if (!all) return one
        // all=true：node 走真实多匹配；color/image 目前只支持首个命中（如实记录）
        val arr = JSONArray()
        if (type == "node" && router.accessibility.isAvailable()) {
            val list = router.accessibility.nodeSnapshots(Action().apply {
                nodeSpec = NodeSpec(text = o?.optString("text"),
                    id = o?.optString("id"), className = o?.optString("className"))
            }, true, false) ?: emptyList()
            // 多匹配结果覆盖单命中，避免同一个点重复出现
            list.forEach { n -> arr.put(toLoc(
                ((n.boundLeft + n.boundRight) / 2).toFloat() to
                ((n.boundTop + n.boundBottom) / 2).toFloat())) }
            if (list.isEmpty()) arr.put(one)
        } else {
            if (type != "node") log.info(ctx.runId, "findLocation(all) 对 type=$type 只返回首个命中")
            arr.put(one)
        }
        return arr
    }

    /**
     * 坐标对象：一次给全像素 / 百分比 / dp 三种单位（自动精灵同款字段名）。
     *
     * similarity 只在模板匹配时有意义；取色/取节点时填 1f（完全命中），
     * 不填 null——脚本常写 `if (loc.similarity > 0.9)`，null 会让比较静默失败。
     */
    private fun toLoc(pt: Pair<Float, Float>, similarity: Float = 1f): JSONObject {
        val app = com.autoball.App.get()
        val sz = com.autoball.core.util.Display.screenSize(app)
        val d = com.autoball.core.util.Display.dp(app, 1f).coerceAtLeast(1f)
        return JSONObject()
            .put("similarity", similarity.toDouble())
            .put("x", pt.first.toDouble())
            .put("y", pt.second.toDouble())
            .put("x_100", (pt.first / sz.x.toFloat().coerceAtLeast(1f) * 100f).toDouble())
            .put("y_100", (pt.second / sz.y.toFloat().coerceAtLeast(1f) * 100f).toDouble())
            .put("x_dp", (pt.first / d).toDouble())
            .put("y_dp", (pt.second / d).toDouble())
    }

    /** options 里取 title（自动精灵 alert(msg, {title, duration})） */
    private fun Any?.asTitle(): String? = when (this) {
        is JSONObject -> optString("title", "").takeIf { it.isNotBlank() }
        else -> null
    }

    /** options 里取 duration；没给就用默认 */
    private fun Any?.asDuration(def: Long): Long = when (this) {
        is JSONObject -> optLong("duration", def)
        is Number -> toLong()
        else -> def
    }

    /** items 可能是 JSONArray、数组或换行串 */
    private fun parseStringList(v: Any?): List<String> {
        val arr = v as? JSONArray
        if (arr != null) {
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) out.add(arr.optString(i))
            return out
        }
        val it = v as? Iterable<*>
        if (it != null) return it.mapNotNull { x -> x?.toString() }
        val str = v as? String
        if (str != null) return str.split("\n").map { x -> x.trim() }.filter { x -> x.isNotEmpty() }
        return emptyList()
    }

    /** 单键名 → 键码 */
    private fun keyCodeOf(name: String): Int =
        keyNamesToCodes(name).firstOrNull() ?: throw CancelException("未知按键名：$name")

    /**
     * 按键名 → Android 键码（自动精灵用 'a'/'enter'/'ctrl' 这类名字）。
     *
     * 组合键按"依次按下"处理（'ctrl','a' → 先 ctrl 再 a）；
     * 字母映射到 KEYCODE_A..Z，与文档 `keyPress('a')` 一致。
     */
    private fun keyNamesToCodes(vararg names: String): List<Int> =
        names.mapNotNull { n ->
            val t = n.trim().lowercase()
            when {
                t.length == 1 && t[0] in 'a'..'z' ->
                    android.view.KeyEvent.KEYCODE_A + (t[0] - 'a')
                t.length == 1 && t[0] in '0'..'9' ->
                    android.view.KeyEvent.KEYCODE_0 + (t[0] - '0')
                else -> when (t) {
                    "enter" -> android.view.KeyEvent.KEYCODE_ENTER
                    "tab" -> android.view.KeyEvent.KEYCODE_TAB
                    "space" -> android.view.KeyEvent.KEYCODE_SPACE
                    "backspace", "del" -> android.view.KeyEvent.KEYCODE_DEL
                    "esc", "escape" -> android.view.KeyEvent.KEYCODE_ESCAPE
                    "shift" -> android.view.KeyEvent.KEYCODE_SHIFT_LEFT
                    "ctrl" -> android.view.KeyEvent.KEYCODE_CTRL_LEFT
                    "alt" -> android.view.KeyEvent.KEYCODE_ALT_LEFT
                    "up" -> android.view.KeyEvent.KEYCODE_DPAD_UP
                    "down" -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
                    "left" -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
                    "right" -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                    "back" -> android.view.KeyEvent.KEYCODE_BACK
                    "home" -> android.view.KeyEvent.KEYCODE_HOME
                    "-" -> android.view.KeyEvent.KEYCODE_MINUS
                    "=" -> android.view.KeyEvent.KEYCODE_EQUALS
                    "[" -> android.view.KeyEvent.KEYCODE_LEFT_BRACKET
                    "]" -> android.view.KeyEvent.KEYCODE_RIGHT_BRACKET
                    "\\" -> android.view.KeyEvent.KEYCODE_BACKSLASH
                    ";" -> android.view.KeyEvent.KEYCODE_SEMICOLON
                    "'" -> android.view.KeyEvent.KEYCODE_APOSTROPHE
                    "," -> android.view.KeyEvent.KEYCODE_COMMA
                    "." -> android.view.KeyEvent.KEYCODE_PERIOD
                    "/" -> android.view.KeyEvent.KEYCODE_SLASH
                    else -> t.toIntOrNull()   // 也允许直接给 Android 键码
                }
            }
        }

    /**
     * requestUrl：脚本主动发起的 HTTP 请求（平台 HttpURLConnection，不引三方库）。
     *
     * 说明：本应用自身不联网、不上传任何数据；这里是**脚本作者**发起的请求，
     * 与自动精灵 requestUrl 同语义。返回 {code, headers, body}。
     */
    private fun requestUrl(spec: Any?): String {
        val o = when (spec) {
            is JSONObject -> spec
            is String -> runCatching { JSONObject(spec) }.getOrNull()
            else -> null
        }
        val url = o?.optString("url") ?: o?.optString("u")
            ?: throw CancelException("requestUrl 需要 url")
        val method = (o?.optString("method", "GET") ?: "GET").uppercase()
        val timeout = (o?.optInt("timeout", 10_000) ?: 10_000).coerceIn(1000, 60_000)
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeout
            readTimeout = timeout
            o?.optJSONObject("headers")?.let { h ->
                val it = h.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    setRequestProperty(k, h.optString(k))
                }
            }
            val body = o?.optString("body", "") ?: ""
            if (body.isNotEmpty() && method != "GET") {
                doOutput = true
                outputStream.use { it.write(body.toByteArray()) }
            }
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.bufferedReader().readText() } ?: ""
            val headers = JSONObject()
            conn.headerFields.forEach { (k, v) -> if (k != null) headers.put(k, v.joinToString(",")) }
            JSONObject().put("code", code).put("headers", headers).put("body", text).toString()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 把 JS 传来的动作对象转成 [Action]。
     *
     * type 兼容两种写法：中文（自动精灵风格的「点击」）与英文枚举名（"CLICK"）。
     * 坐标走 [coord]，支持 `'50%'` / `'200dp'` / 像素。
     */
    private fun runAction(js: String): Boolean {
        val o = runCatching { JSONObject(js) }.getOrNull()
            ?: throw CancelException("runAction 需要一个对象参数")
        val t = o.optString("type", o.optString("t", ""))
        // byLabel 收的是 Action（按 optionLabel 反查），这里只有字符串——
        // 直接按 label / 枚举名两条路找（R-125 兼容中文与英文写法）
        val preset = ActionPreset.ALL.firstOrNull { it.label == t }
        val type: ActionType = preset?.type
            ?: ActionType.fromName(t)
            ?: ActionType.values().firstOrNull { it.label == t }
            ?: throw CancelException("未知动作类型：$t")
        if (type == ActionType.RUN_JS || type == ActionType.SET_VAR) {
            throw CancelException("runAction 不支持 ${type.label}：会造成 JS 引擎重入死锁")
        }
        val a = preset?.newAction() ?: Action().apply {
            id = Action.newId()
            this.type = type
            x = 50f; y = 50f
        }
        a.type = type
        // 坐标：posData 优先，其次顶层 x/y
        val pos = o.optJSONObject("posData") ?: o.optJSONObject("pos")
        val sx = pos?.opt("x") ?: o.opt("x")
        val sy = pos?.opt("y") ?: o.opt("y")
        if (sx != null) a.x = coord(sx, true)
        if (sy != null) a.y = coord(sy, false)
        val ex = pos?.opt("x2") ?: o.opt("x2")
        val ey = pos?.opt("y2") ?: o.opt("y2")
        if (ex != null) a.x2 = coord(ex, true)
        if (ey != null) a.y2 = coord(ey, false)
        if (o.has("duration")) a.durationMs = o.optLong("duration", 80).coerceAtLeast(10)
        o.optString("text", "").takeIf { it.isNotEmpty() }?.let { a.text = it }
        o.optString("pkg", "").takeIf { it.isNotEmpty() }?.let { a.pkg = it }
        o.optString("url", "").takeIf { it.isNotEmpty() }?.let { a.url = it }
        if (o.has("keyCode")) a.keyCode = o.optInt("keyCode", 4)
        if (o.has("repeat")) a.repeat = o.optInt("repeat", 1).coerceAtLeast(1)
        return exec(a)
    }

    /** touchDown 以来的轨迹点（像素）。JsHost 是每次运行新建的，不会跨脚本残留 */
    private val downPts = ArrayList<com.autoball.core.model.Pt>()
    private var downX = 0f
    private var downY = 0f

    /**
     * 单指手势：`gesture(duration, [x1,y1], [x2,y2], ...)`
     *
     * 坐标走 [coord]，支持 `'50%'` / `'200dp'` / 像素。
     * 存进 `Action.path` 的是**像素**——后端直接拿它构造 Path，
     * 与录制产生的动作一致（CoordMapper 的缩放也按像素算）。
     */
    /** 播放音频：本地路径 / file:// / http(s)://，同步等待播完（上限 120s） */
    private fun playMedia(src: String): Boolean {
        if (src.isBlank()) throw CancelException("playMedia 需要文件路径")
        val mp = android.media.MediaPlayer()
        return try {
            if (src.startsWith("http://", true) || src.startsWith("https://", true)) {
                mp.setDataSource(src)
            } else {
                val p = src.removePrefix("file://")
                val f = java.io.File(p)
                if (!f.exists()) throw CancelException("音频文件不存在：$p")
                mp.setDataSource(f.absolutePath)
            }
            mp.prepare()
            mp.start()
            val t0 = System.currentTimeMillis()
            // isPlaying 播完自动转 false；流媒体拿不到时长，靠超时兜底
            while (mp.isPlaying && System.currentTimeMillis() - t0 < 120_000) {
                if (control.canceled) break
                Thread.sleep(50)
            }
            runCatching { if (mp.isPlaying) mp.stop() }
            true
        } catch (e: Exception) {
            log.warn(ctx.runId, "playMedia 失败：${e.message}")
            false
        } finally {
            runCatching { mp.release() }
        }
    }

    private fun gesture(args: org.json.JSONArray): Boolean {
        val dur = args.optLong(0, 400).coerceAtLeast(30)
        val pts = ArrayList<com.autoball.core.model.Pt>()
        var i = 1
        while (i < args.length()) {
            val arr = args.opt(i)
            if (arr is org.json.JSONArray && arr.length() >= 2) {
                // 标准写法：gesture(400, [x1,y1], [x2,y2])
                pts.add(com.autoball.core.model.Pt(
                    coord(arr.opt(0), true), coord(arr.opt(1), false)))
                i++
            } else if (arr is Number && i + 1 < args.length() && args.opt(i + 1) is Number) {
                // 兼容扁平写法：gesture(400, x1, y1, x2, y2)
                pts.add(com.autoball.core.model.Pt(
                    coord(arr, true), coord(args.opt(i + 1), false)))
                i += 2
            } else {
                i++
            }
        }
        if (pts.size < 2) throw CancelException("gesture 至少需要两个点")
        return exec(Action().apply {
            id = Action.newId()
            type = ActionType.GESTURE_SINGLE
            path = pts
            durationMs = dur
        })
    }

    /**
     * 多指手势：`gestures([delay, duration, [x,y], ...], [delay2, ...], ...)`
     *
     * 每根手指是一条数组，**先延迟再时长**——与自动精灵的参数顺序一致。
     * 延迟不传给后端（无障碍的 StrokeDescription 支持起始延迟，但各 ROM 行为不一），
     * 这里统一从 0 开始，避免部分 ROM 上手指迟迟不落下。
     */
    private fun gestures(args: org.json.JSONArray): Boolean {
        val fingers = ArrayList<MutableList<com.autoball.core.model.Pt>>()
        var maxDur = 300L
        for (i in 0 until args.length()) {
            val f = args.optJSONArray(i) ?: continue
            // f = [delay?, duration, [x,y], ...]：只取"第一个数字"作为时长
            var dur = 300L
            for (k in 0 until f.length()) {
                val e = f.opt(k)
                if (e !is Number) break
                dur = e.toLong()   // 连续多个数字时取最后一个（即 duration 覆盖 delay）
            }
            maxDur = kotlin.math.max(maxDur, dur.coerceAtLeast(30))
            val pts = ArrayList<com.autoball.core.model.Pt>()
            for (k in 0 until f.length()) {
                val e = f.opt(k)
                if (e is org.json.JSONArray && e.length() >= 2) {
                    pts.add(com.autoball.core.model.Pt(
                        coord(e.opt(0), true), coord(e.opt(1), false)))
                }
            }
            if (pts.size >= 2) fingers.add(pts)
        }
        if (fingers.isEmpty()) throw CancelException("gestures 至少需要一根手指的两个点")
        return exec(Action().apply {
            id = Action.newId()
            type = ActionType.GESTURE_MULTI
            strokes = fingers
            durationMs = maxDur
        })
    }

    private fun tapAction(x: Float, y: Float, dur: Long) = Action().apply {
        type = ActionType.CLICK
        this.x = x; this.y = y
        durationMs = dur
    }

    private fun exec(a: Action): Boolean {
        val r = router.execute(a, ctx)
        log.add(ctx.runId,
            if (r.ok) RunLog.Level.OK else RunLog.Level.ERROR,
            a.type.label, r.message, null, r.backend.label, r.latencyMs)
        if (!r.ok) throw CancelException(r.cause ?: "执行失败")
        return true
    }

    /** 节点定位是无障碍独有能力；Shizuku 后端下显式返回 null，不静默伪造 */
    private fun resolveNodePoint(a: Action): Pt? {
        if (!router.accessibility.isAvailable()) {
            log.warn(ctx.runId, "findNode 需要无障碍服务的节点能力，Shizuku 后端不支持")
            return null
        }
        return router.accessibility.nodeCenter(a)
    }

    /** 供 RUN_JS 动作与条件表达式复用 */
    fun evalCondition(expr: String): Boolean = Condition.eval(expr, ctx.vars)

    class CancelException(msg: String) : RuntimeException(msg)
}
