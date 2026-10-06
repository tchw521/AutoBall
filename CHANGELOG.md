# AutoBall 开发日志

每次迭代的版本记录。已发布 APK 见 GitHub Actions 的 Artifacts。

---

## v0.4.2 · 修复前台服务崩溃 + 跨应用坐标拾取 + 中央按钮

**修复（点「开始录制」即闪退）**

- **根因：`ForegroundServiceDidNotStartInTimeException`**。Android 14+ 强制要求前台服务声明
  `foregroundServiceType`，v0.4.1 为规避 SecurityException 移除了该声明，结果
  `startForeground()` 抛 `MissingForegroundServiceTypeException`；而当时用 `runCatching`
  把异常静默吞掉，服务停留在"已承诺前台但未进前台"的状态，系统数秒后判定超时直接杀进程。
  修复：manifest 恢复 `specialUse` 类型声明，代码改用与类型匹配的三参
  `startForeground(id, notification, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)`；
  **异常不再静默吞掉**——失败即 `stopSelf()` 止损。
- 后台启动前台服务（Android 12+ 会拒绝）改为返回失败并记录，不再抛出。

**修复（底部导航中央图标）**

- 中央 56dp 按钮带 `-26dp` 负 margin 顶出上沿，但导航自身仍是默认裁剪，
  上半截被切掉。已关闭导航与外层容器的裁剪。
- 「制作」标签此前被设为不可见；现改为常显在圆钮正下方、导航栏内部，不与选中胶囊冲突。

**改进（坐标拾取）**

- 旧版用**全屏透明层**取点，只能在本应用内操作，看不到也点不到其他应用。
- 新版：拾取时自动隐藏悬浮球与悬浮窗、并把本应用收到后台，只保留一个**可拖动的十字准星**，
  拖到目标应用或桌面上的真实位置后点一下即取回坐标，随后自动回到本应用并恢复悬浮球。
- 准星不遮挡目标界面，底部实时显示坐标数值。

---

## v0.4.1 · 修复启动闪退

**修复（启动即闪退）**

- **根因：底部导航构造时触发无限递归**。`LiquidNavView` 初始化调用 `select(0)`，
  `select()` 回传 `onSelect()` → `MainActivity.showPage()` → 又调 `nav.select()`，
  形成 `select → showPage → select` 的死循环，**启动瞬间 StackOverflowError**。
  修复：把「视觉选中」与「通知外部」拆开，只有用户真实点击才回调页面切换。
- 前台服务类型：manifest 声明了 `foregroundServiceType="specialUse"`，但调用的是两参
  `startForeground()`，Android 14 会抛 `SecurityException`。已移除类型声明（悬浮层与
  本地执行不属于任何特殊类型），并对 `startForeground` 加保护，失败只记录不连带崩进程。
- 通知图标：原使用彩色渐变 `ic_launcher` 作为通知小图标，部分 ROM 会因
  "Bad notification posted" 崩溃。新增纯 alpha 白色图标 `ic_stat` 专供通知使用。
- `onDraw` 在尺寸为 0 时创建 `LinearGradient` 会抛 `IllegalArgumentException`，已加保护。

**新增**

- 全局崩溃捕获：堆栈写入私有目录，下次启动主动弹窗展示并支持复制（真机无 logcat 时唯一可用手段）
- 「我的」页新增崩溃日志入口
- 页面构建失败降级为错误页，不再拖垮整个 App

---

## v0.4.0 · 多分辨率适配 + 构建稳定化

**新增**

- **坐标归一化**：动作流携带录制时的屏幕签名（宽/高/密度/方向），运行前按当前屏幕自动缩放所有坐标、路径与多指轨迹
- **转屏保护**：录制方向与当前不同时不静默换算，改为提示"请重新校准坐标"，避免点偏后用户不知道原因
- **坐标映射可视化日志**：缩放生效时在运行日志中记录实际倍率

**修复**

- 修正 Shizuku AIDL 桩：原写法使用带自定义 transaction id 的方言语法，标准 AIDL 编译器无法解析，会直接让构建失败
- 修正 QuickJS 源码拉取：此前只复制部分头文件，缺 `cutils.h` / `libregexp.h` / `libunicode.h` 等会导致 CMake 编译 `quickjs.c` 失败；现改为复制全部 `.c`/`.h` 并排除示例与构建脚本
- Shizuku 官方 AIDL 拉取增加语法校验，不合规自动回退到内置桩
- 构建脚本不再依赖 gradle-wrapper.jar，CI 直接使用安装的 Gradle 8.5

**构建产物（首次编译成功）**

| 产物 | 大小 | 说明 |
|---|---|---|
| `AutoBall-v0.4.0-quickjs-debug.apk` | 1.73 MB | 默认产物，含 QuickJS 原生引擎（arm64 918KB / armeabi-v7a 581KB） |
| `AutoBall-v0.4.0-rhino-debug.apk` | 1.67 MB | 无 NDK 逃生口，JS 走纯 Java 引擎 |

- 两个 debug 包均已签名，可直接安装
- release 包未配置签名，产出的是 `unsigned` 版本，**不可直接安装**（下一版改为回退到 debug 签名）

**已知限制**

- 坐标缩放仅在动作流中生效；JS 脚本内手写坐标仍按绝对像素执行
- 分屏 / 多窗口场景下屏幕尺寸变化未做特殊处理
- 原生库当前同时打包 arm64-v8a 与 armeabi-v7a，约占 1.5MB；下一版启用 ABI 拆分可进一步压缩

---

## v0.3.0 · 双授权并行执行架构

**架构**

- 无障碍与 Shizuku 两条执行通道**并行对等**：任意一种授权可用即可运行完整脚本
- 动作级能力位路由：脚本声明"需要什么能力"，运行时按能力匹配后端，而非绑定具体接口
- 单个后端执行失败自动回退到另一个，降级原因写入运行日志
- 运行前能力体检：明确告知哪些动作可完整执行、哪些会降级、哪些无法执行

**执行**

- 动作流执行器：不依赖 JS 引擎即可跑完整流程（20 类动作全覆盖）
- JS 宿主接口：`click / press / longClick / swipe / sleep / globalAction / key / input / openApp / toast / screenshot / findNode / clickText / setVar / getVar / log / stop`
- 引擎默认 QuickJS（JNI 桥接，三层超时与中断），无 NDK 时自动降级为纯 Java 引擎
- 统一运行协调器：同一时间仅允许一个脚本运行，运行期间再次触发解释为停止，避免自递归

**录制**

- 采集层改为可移动的小区域窗口，降低 Android 12+ 遮挡拦截风险
- 抬手立即补发保证目标应用真实响应
- 回声抑制三重判定：来源标记为主 + 时间窗为辅 + 连续回声熔断
- 中断/结束弹窗三选一：放弃 / 继续录制 / 保存

**界面**

- 液态通透凝胶底部导航（脱离底边 14dp、26dp 胶囊圆角、中央 56dp 四角星凸起）
- 五个页面：脚本 / 编辑 / 制作 / 市场 / 我的
- 悬浮球四手势、拖到底部关闭、悬浮窗六套皮肤、坐标拾取器、20 类动作动态表单

**已知限制**

- 坐标为绝对像素（v0.4 起已支持自动缩放）
- 多指手势在无障碍通道依赖并行 stroke，部分厂商 ROM 会降级为单指
- 保活只能降低被回收频率，不能承诺不被系统杀死

---

## v0.2.0 · 录制与动作流

- 透明采集层接管触摸，手势补发与回声抑制
- 动作流结构化存储，支持逐条删改
- 录制中断可续录

---

## v0.1.0 · 骨架

- 无障碍点击链路打通
- 悬浮球常驻与前台服务
