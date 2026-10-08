# AutoBall 开发档案

> 记录每个版本做了什么、为什么这么做、踩过什么坑。
> 详细版本说明见 `CHANGELOG.md`；本文件侧重**决策理由与教训**。

---

## 工程基线

- **定位**：本地运行、明确授权、随时可停的辅助自动化工具
- **三执行方式**：点击（动作流）/ 录制 / JS 脚本 —— 运行时归一为同一链路
- **双授权并行**：无障碍 + Shizuku 对等存在，按动作能力路由，失败自动回退
- **零第三方运行时依赖**：不用 androidx / 协程 / 序列化库，JSON 用 org.json
- **目标**：minSdk 24 / targetSdk 34，JDK 17 + AGP 8.1.4，双引擎 QuickJS / Rhino

---

## 架构要点（改代码前必读）

### 执行链路

```
Action(20类) → BackendRouter(按能力位选后端) → 无障碍 / Shizuku
                     ↓ 失败自动回退另一个后端
                  ActionResult(含 degraded 降级标记)
```

- `Action.x / y` 存**百分比**（0–100），不是像素。换机型与转屏不会点偏。
- 录制采集到的是像素，**补发之后**才转百分比——顺序不能反，补发要用真实像素。
- 能力位：只有带坐标的动作才做矩阵变形，对「等待」「按键」抖动只会打乱时序。

### 三条易错链路

| 链路 | 易错点 |
|---|---|
| 转屏 | 先让所有缓存失效**再**重算；窗口内按屏幕算的固定像素值必须重建，只改宽度不够 |
| 悬浮窗 | 新窗口要后 addView 才能压在旧窗口上；输入框需窗口可聚焦，不能加 `FLAG_NOT_FOCUSABLE` |
| 录制 | 采集层只接管触摸，不能带控制条（会被系统判为不可信遮挡）；回声抑制防自触发 |

---

## 版本决策记录

### v1.22.0 运行条件真实求值

**发现**：`Action.condition` 存 JSON，但 `Condition.eval()` 只当字符串判空 → 非空即真 → 所有识别类条件**从不生效**，界面却显示「已设置」。

**决策**：新增 `ConditionEval` 按 kind 分派。能力不足返回 `UNKNOWN` 并**按不满足跳过 + 记日志**，绝不静默当作成立——静默成立会让脚本在错误界面上继续乱点。

### v1.21.0 Ui.kt 拆分

**决策**：1237 行混了四类职责，拆为 `UiSheets` / `UiDialogs` / `UiOverlays` / `UiBits`，`Ui` 保留为**门面**。

**理由**：现有 148 处 `Ui.xxx()` 调用点一个都不改。纯重构若顺带改 148 处，回归风险远大于收益。等因需求改到某文件时再逐步直连新库。

### v1.21.0 时长单位下拉

三处时间字段原本各写各的（运行等待固定秒、按下时间固定毫秒）。单位写死 → 想等 2 分钟得填 120。

**细节**：切换单位时把已填数值按旧单位换算——否则「500 毫秒」切到「秒」变「500 秒」，静默放大 1000 倍极难发现。

### v1.18.0 动作预设收口

同一套预设此前定义**两遍**（`ActionEditor.OPTIONS` + `ToolPanel.TOOLS`），按键码（3/4/187/1001）硬编码两处，改一处漏一处。

**决策**：提取为 `ActionPreset` 唯一数据源，含 `ALL` / `GROUPS` / `byLabel()` / `newAction()` / `KeyCode`。新增动作类型现只需改一处。

### v1.17.0 定时触发

**决策**：用「进入应用时补触发」，不用后台常驻。
**理由**：后台保活耗电、常被回收，与「手动启动、随时可停」定位冲突。效果是到点后首次打开应用时自动跑一次。

### v1.20.0 消息触发

两个坑：**排除本应用自己的通知**（否则运行提示自我触发形成死循环）；**通知回调在 Service 进程里没有 Activity**，直接弹 UI 会崩 → 由主界面实现 `NotifyHost`，resume/pause 注册注销，触发时经 `runOnUiThread` 转交。

---

## v1.29.0 自动精灵对齐 + JS API 地基

### R-121 JS API 白名单收敛（三处 → 一处）

此前 API 白名单分裂在三处，加一个 API 要改三遍，漏改就是运行时
"unknown host api"：
1. C++ `autoball_quickjs.cpp` 的 `kHostApis[]`（每个 API 一个 magic）
2. `RhinoEngine.BRIDGE`（Rhino 的 prelude）
3. `JsHost.call()` 的 when 分支

**决策**：C++ 只注册一个通用 `host(name, ...args)`，全部 API 名称与包装
集中在 Kotlin 的 `JsBridge.PRELUDE`。两个引擎共用同一份字符串。
从此新增 API 只改 Kotlin，不必碰 C++（也没有 NDK 的重新编译成本）。

命名空间取 `ab.*` 为主 + `zdjl.*` 别名指向同一对象——社区脚本可直接跑。
全局函数（`click()` 等）保留，保证早期脚本不失效。

### R-122 坐标单位必须在宿主侧换算

`Action` 的坐标字段**只存百分比**。若 JS 传来的像素直接透传，
`CoordMapper` 会再按录制签名缩放一次 → 双重缩放。
所以 `'50%'` / `'200dp'` / 像素统一在 `JsHost.coord()` 里换算完再建 Action。

### R-125 runAction 的重入死锁

`RUN_JS` 本身就是 ActionType 之一。JS 里 `runAction({type:'运行JS代码'})`
等于在同一 QuickJS Context 内重入 → 必卡死（自动精灵文档也警告过）。
**显式拒绝 RUN_JS / SET_VAR 并报错**，而不是尝试执行或静默跳过。

### 顺带修：取区回调把像素当百分比（真 bug）

`RegionPicker` 回调的是**像素**，而 `Action` 存百分比。
`ActionEditor` 的「结束位置」直接把像素存进百分比字段，还把右边界当宽度相加
（`x2 = l + r`，而 r 本来就是 right）。两个错误叠加 →
用户截图里出现 `点击(612.0%, 1344.0%)` 这种荒谬值。

修法：统一 `pctOf()` 换算（像素 ÷ 屏幕尺寸 × 100），并加 0–100 夹紧。
**凡是取点/取区控件的回调值，存进 Action 前都要过这一层。**

---

## v1.32.0 JS API 对齐：三个新坑

### 1. KDoc 里的裸方括号会让编译器报 "Closing bracket expected"

注释里写 `返回 { result, items: [下标...] }` 直接编译失败——
KDoc 把 `[...]` 当**文档链接**解析，内容不是合法标识符就报错。

注意：形如 `[Action]`、`[nodeCenter]` 的**符号链接是合法的**且已大量使用，
只有非标识符内容（含逗号、省略号、中文）才出问题。
排查时别一刀切地删所有方括号。

### 2. public 函数不能暴露 private 返回类型

`private class Answer` 被 `fun select(...): Answer` 用作返回类型 →
"'public' function exposes its 'private-in-class' return type"。
内部类要出现在公开签名里，就得跟着公开。

### 3. lambda 里的智能转换可能失效

`when (v) { is JSONArray -> (0 until v.length()).map { v.optString(it) } }`
报 receiver type mismatch——`v` 在 lambda 内没被智能转换。
改成块体 + 显式 `val arr = v as? JSONArray` 就稳。
**在 lambda 里用 when 智能转换的变量时，先取局部变量。**

---

## 构建验证：v1.31.0 首次编译（32 处错误）

八个版本（v1.28–v1.31）未编译验证的后果一次性暴露。错误分四类：

### 1. 前向引用（Kotlin 局部函数）

`ConditionDialog` 里 `rebuild` 与 `editCond` 互相调用，但 Kotlin 的**局部函数**
不支持前向引用（声明顺序即解析顺序）。`rebuild` 已改成 lateinit lambda，
`editCond` 忘了改 —— 于是 2 处 Unresolved reference。
**两个互相调用的局部逻辑都必须声明为 lateinit lambda 变量。**

### 2. 字符串模板未转义（3 处）

`"可用 $ok / $last / $stepN"` 里 `$ok` 被 Kotlin 当成模板引用 →
变量不存在 → 编译失败。必须写 `\$ok`。
这是我在同一轮修过一次又犯的（ConditionDialog 里也出现过）。

### 3. 按记忆调用不存在的 API（4 处）

- `ActionPreset.byLabel(String)` —— 实际收的是 **Action**，按 optionLabel 反查
- `AB.store.setInt()` —— 实际叫 `putInt`
- `ExecContext()` —— 需要 runId 参数
- `JsBridge` 未 import（跨包引用）

**结论：调 Kit / Ui / 各 Store 的方法前必须先 grep 签名，不能凭记忆。**

### 4. 主构造参数作用域

`class ScheduleBoard(ctx: Context, ...)` —— 不加 `val/var` 的话，
`ctx` 只在 init 块可见，类体方法里用会报 Unresolved reference（26 处）。
需要在类体里用的构造参数必须写成 `private val ctx: Context`。

### 5. 缩进错位导致的隐性 bug

`Script.kt` 里 `notifyEnabled = ...` 少写了 `s.` 前缀 ——
语法上合法（赋值给了别的变量），语义上是错的。
**确认是编译错误才发现的，静态检查看不出来。**

---

### CI 取产物的可行路径

Actions 的 **artifact 下载对外部 token 返回 403**（policy_default_denied），
job logs 同理。可行路径：
- 编译错误 → `ci-errors` 分支（工作流会自动提交）
- APK → `apk` 分支（按 `v{版本}/{engine}/app-{buildType}.apk` 组织）
- 大文件（>1MB）要用 **git blobs API**，contents API 会返回空内容

---

## v1.31.0 组件统一：色值收敛 + 自定义按键

### T-02 浮层色值收敛（16 处 → 0）

`FloatPanelView` 把 `#F21E1836` / `#F2141022` 这类**深色调**写死在代码里，
切到浅色主题时浮层仍是深色块，与整体割裂——这是真 bug 不是洁癖。

新增令牌：`floatStops()` / `floatEdge()` / `runEdge()` / `C_KEY_EDGE`。
槽位色**复用已有的 `Theme.G` 调色板**（7 色，深浅各一套），
不另造一个色表——否则改主题时要同时维护两处。

### R-118 自定义按键：皮肤退化为"初始模板"

关键设计：不新增一套并行的自定义数据，而是让固定皮肤**退化为初始模板**。
选皮肤 = 把该模板的按键写进自定义列表，用户再自行增删。
这样固定皮肤与自定义布局共用一份数据，不用维护两套渲染路径。

`PanelKeyStore` 用 String id 存动作（不直接引用 UI 枚举）——
store 层不依赖 UI 类型，悬浮窗在窗口里重建时不会因类型不匹配失效。

### 新增 Kit.colorDot（三次法则触发）

分组配色、标签配色、悬浮按键配色三处都要"一排色点选一个"，
各写一遍就有三种不同的选中标记与尺寸。抽为 `Kit.colorDot(ctx, color, selected)`。

### 又踩的两个坑

1. `Kit.section()` 返回的是 **TextView**，不能 `addView`。
   需要"分区标题 + 输入框"时必须自己包一层纵向容器。
2. `Theme.pill` 不存在——色点组件是 `Kit.pill(ctx, text, onClick)`，
   且它带文字，不适合做纯色点。最终新抽 `Kit.colorDot`。

---

## v1.28.0 代码审查：六个真缺陷

### 1. 快照与回滚形同虚设（最严重）

`ScriptStore.all()` 只浅拷贝**列表**，Script 对象与缓存共享引用。
编辑页 `bind()` 直接持有这个共享对象并原地修改 → `save()` 里
`list[idx] === s` → `SnapshotStore.snapshot(list[idx])` 拍到的是**已改后**的新状态。
回滚到"上一版"等于回到当前版，**功能完全失效**。

修法：`Script.copy()` / `Flow.copy()`，编辑页与 JS 页在 `bind()` 持有副本。

教训：注释写着"调用方可自由修改，不影响缓存"——**注释与实现不符时，按实现为准去查**。

### 2. 动作复制丢失字段 + 浅拷贝

`EditPage.copyAt/pasteAt` 与 `ActionTemplateStore.clone()` 各自手写逐字段拷贝，
三处都漏掉 `colorHex` / `nodeSpec` / `imageRef` / `failOp` / `failJumpTo` /
`jitterDp` 等十余项；`nodeSpec` 还是可变 data class，浅拷贝会连带改到原动作。
后果：复制一个"点击节点"动作，粘贴出来节点选择器是空的。

修法：统一走 `Action.copy(newId)`，深拷贝嵌套结构。这正是 R-001 三次法则的触发点。

### 3. `optionLabel` 只写不读

序列化写了，反序列化没读 → 保存再打开就丢，界面回退成动作类型名，
用户分不清当初选的是「长按」还是「点击」（两者都是 CLICK 类型）。

### 4. 定时脚本"当天只触发一次"在重启后失效

`lastFiredDay` 从不持久化。进程被回收后归零 → 当天会**重复触发一次**。
`markFired` 后虽有 `store.save()`，但 JSON 里没这个字段，等于没存。

### 5. `FloatWindows` 两处状态不一致

- `add()` 失败时已入栈却未回滚 → 留下"没真正挂上"的幽灵条目
- `update()` 里 `?.let { it.view.layoutParams }` 是**无副作用的死代码**，
  拖动窗口后 `hideAll → restore` 会把窗口弹回旧位置

### 6. 导航栏 onDraw 每帧解析与分配

导航栏带呼吸动画，是常驻重绘视图。`onDraw` 里 3 处 `Color.parseColor`、
2 个 `LinearGradient` + 1 个 `RadialGradient` **每帧新建**。
已全部改为预解析常量 + 按尺寸/主题缓存渐变。

注意：缓存渐变时坐标必须落在**画布绝对坐标**（以圆心 cx,cy 为基准），
只用 r 推相对值会让高光跑到视图左上角。

---

## 踩过的坑（同类错误已犯多次，务必自查）

### 编译期

1. **删除代码时连带误删仍在使用的成员** —— 出过 3 次（删 `sliderRow` 误删 `permRow`、删 import、删字段）
2. **属性声明顺序** —— `init { build() }` 用到后面才声明的字段 → 构造期空指针
3. **尾随 lambda 绑错参数** —— 出过 3 次，改用命名参数
4. **参数名遮蔽** —— `max` 遮蔽 `SeekBar.max`，改名 `maxVal`
5. **androidx 不可用** —— `PathParser` 属 androidx，零依赖约束下不能用，改自绘几何图形
6. **`ScrollView` 没有 `maxHeight` 属性** —— 那是 `View` 的，改用 LayoutParams 固定高度

### 运行期

1. **启动崩溃：无限递归** —— 构造 → select → showPage → select …
   解法：把「视觉选中」和「通知外部」拆开，同步选中不回传
2. **前台服务超时被杀** —— Android 14+ 强制 `foregroundServiceType`；用 `runCatching` 吞掉异常后服务停在"已承诺前台却没进前台"，数秒后被判超时
3. **单位混用：像素当 dp** —— 窗口被放大约 2.6 倍，两侧被裁到屏幕外，看起来像"只剩一个按钮"
4. **负 margin 被 ROM 裁剪** —— 中央按钮上半截被切。改用容器预留空间，子 View 全在边界内
5. **SharedPreferences 键迁移** —— 旧版本存 String、新版读 Int → `ClassCastException` 必崩。需类型兜底 + 迁移
6. **二级弹窗被外层遮挡** —— 另开 Dialog 争同一层级。改为就地换页，共用同一 Dialog

### 推送构建

- **增量推送漏文件** —— `push_delta.py` 只推 git diff 的文件，但后续 commit 又改了 `build.gradle.kts`（版本号），远端未同步 → CI 读旧版本号，产物存错目录取不到。已加全量比对。

---

## 验证状态

- 沙盒**无 Android SDK**，所有版本只做静态检查（括号与引用平衡），**未经编译验证**
- 真机待验证三项：无障碍点击闭环、Shizuku 通道、录制穿透
- 这三项在国产 ROM 上差异最大，静态检查发现不了

## 仓库说明

- 代码经 GitHub API 推送（`git` 直连在沙盒被拦）
- 仓库无 `gradlew` / `gradle-wrapper.jar`，CI 直接装 Gradle，不受影响；本地需 `gradle wrapper` 生成
- 网页显示 "repository is empty" 是登录会话不同步，刷新即可，代码与构建正常
