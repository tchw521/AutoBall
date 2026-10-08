# JS API 对照与复刻方案

> 参考自动精灵（zdjl.*，98 个 API）整理。归档 + 复刻依据。
> 更新：v1.27.0 之后，尚未实现。

---

## 一、决策

| 项 | 决定 | 理由 |
|---|---|---|
| 命名空间 | **`ab.*` 为主，`zdjl.*` 作别名** | 自有身份 + 兼容社区脚本（网上下载的自动精灵脚本可直接跑） |
| 复刻范围 | 触摸手势 / 图像识别 / 文件存储 / 动作控制变量 | 纯本地可实现的四类 |
| 不做 | 网络请求、云 OCR、AI 识别、定位、蓝牙/WiFi 开关 | 与「不联网」定位冲突或需系统级权限 |
| 归档位置 | 本文件 | 独立文档，不塞进需求档案 |

---

## 二、现状：我们已有的 19 个 API

`JsHost.call()` 当前支持的全局函数（**不是** `ab.*` 形式）：

```
click  press  longClick  swipe  sleep  globalAction  key  input  openApp
toast  screenshot  findNode  clickText  setVar  getVar  log  stop
isCanceled  backend
```

对照自动精灵的差距：

- 坐标**只接受像素**（float），不支持 `'50%'` / `'200dp'`
- 没有 `findLocation`（找图/找色/找字的**定位**能力，与「点击」解耦）
- 没有文件读写与本地存储
- 没有 `runAction`（动态建动作）/ `check`（条件求值）

---

## 三、能力对照表

### 3.1 触摸与手势（优先）

| zdjl API | 我们的实现 | 状态 |
|---|---|---|
| `click(x,y,dur)` | `click` | ✅ 需补单位解析 |
| `longClick(x,y)` | `longClick` | ✅ 需补单位解析 |
| `swipe(x1,y1,x2,y2,d)` | `swipe` | ✅ 需补单位解析 |
| `gesture(dur,[x,y],...)` | ❌ 无 | 待做（单指多点） |
| `gestures([[d,dur,[x,y]...],...])` | ❌ 无 | 待做（多指，需 MULTI_POINTER 能力） |
| `touchDown/Move/Up` | ❌ 无 | 待做（长按拖拽场景） |
| `keyPress/keyDown/keyUp` | `key`（仅 Android 键码） | ⚠️ 自动精灵用 `'a'`/`'ctrl'` 语义键名，需映射表 |

**坐标单位**（研究结论）：
- `'50%'` → x 乘**屏宽**、y 乘**屏高**，分别算
- `'200dp'` → `round(dp * density)`
- 数字 → 像素
- 三种靠**字符串后缀**区分，`number | string` 联合类型

### 3.2 图像识别（优先）

| zdjl API | 我们的实现 | 状态 |
|---|---|---|
| `findLocation({type:'text'\|'node'\|'image'\|'color'})` | ❌ 无 | **核心缺口** |
| `findNode(selector, opts)` | `findNode`（只查文字+可点击） | ⚠️ 需扩选择器字段 |
| `getScreenColor(x,y)` | ❌ 无（内部有 `ConditionEval.colorAt` 未暴露） | 待做，成本低 |
| `getScreenAreaColors(...)` | ❌ 无 | 待做 |
| `ocr(...)`, `recognitionScreen(...)` | ❌ 无 | 不做（需 OCR 模块/联网） |

**findLocation 语义**（研究结论）：
- 返回 `{x, y, x_100, y_100, x_dp, y_dp, similarity}`，一次给三种单位
- 第二参数传 `true` → 返回**数组**（findAll）
- 未命中返回 **`null`**（不要抛异常，脚本好写）
- image 的 template 同时支持路径 / base64

**关键价值**：`findLocation` 把「定位」和「点击」解耦——先找到坐标，脚本可以自己判断、记录、再决定点不点。我们现在只有 `clickText`/`clickImage`（耦合版），脚本拿不到坐标。

### 3.3 文件与本地存储（优先）

| zdjl API | 我们的实现 | 状态 |
|---|---|---|
| `readFile` / `writeFile` / `appendFile` | ❌ 无 | 待做 |
| `getStorage` / `setStorage` / `removeStorage` | ❌ 无 | 待做 |

**路径约定**（研究结论 + 我们的约束）：
- 自动精灵文档写 `/sdcard/xxx`，但 **Android 10+ 分区存储**下直接路径不可写
- 我们的策略：分离根 → `script/`（脚本数据）、`private/`（应用私有）、`cache/`（临时）、`public/`（走文档选择器）
- 默认**不依赖 `/sdcard/`**，脚本里写 `/sdcard/x.txt` 时映射到私有根，避免脚本因权限失败
- `getStorage` 用带 scope 的私有 JSON，不用 SharedPreferences（要支持 scope 隔离）

### 3.4 动作控制与变量（优先）

| zdjl API | 我们的实现 | 状态 |
|---|---|---|
| `runAction(obj)` | ❌ 无 | 待做 |
| `check(cond)` | ⚠️ `evalCondition`（未暴露给 JS） | 低成本待做 |
| `sleep(ms)` | `sleep` | ✅ |
| `getVar` / `setVar` / `getVars` / `clearVars` | `getVar`/`setVar` | ⚠️ 缺 `getVars`/`clearVars` |
| `printVars` / `showAddVar` / `showEditVar` | ❌ 无 | 不做（弹窗在无障碍服务里不安全） |
| `runActionAsync` 等异步族 | ❌ 无 | 见下 |

**runAction 的重入死锁**（研究结论，重要）：
自动精灵文档明确警告「避免在 runAction 中再调用 设置变量、运行JS代码 等依赖 JS 引擎的动作，会容易引起卡死」。
我们的 `RUN_JS` 本身就是 `ActionType` 之一，所以**必须做重入防护**：`runAction` 收到 RUN_JS / SET_VAR 类型时直接拒绝并报错，而不是尝试执行。

---

## 四、关键技术约束（落地前必读）

### 4.1 API 白名单在两处，已分裂 ⚠️

- **QuickJS**：C++ `autoball_quickjs.cpp` 的 `kHostApis[]` 数组
- **Rhino**：Kotlin `RhinoEngine.kt` 里的 JS prelude 字符串

**加一个 API 要改两处**，漏改就是 QuickJS 抛 `unknown host api`。这是现有隐患。

**解法（一次性投资）**：
1. C++ 加一个通用 `host(name, ...args)` 转发到 Kotlin
2. Kotlin 定义**唯一一份** `JS_PRELUDE` 常量（JS 代码），内部定义 `ab.*` 与 `zdjl.*`
3. QuickJS 与 Rhino 都先 eval 这份 prelude，再跑用户代码

之后新增 API **只改 Kotlin**，两边自动同步。

### 4.2 坐标存储单位是像素

`Action.x/y` 存 float 像素，运行时由 `CoordMapper` 按录制签名缩放。
JS 层收到的 `'50%'` / `'200dp'` 必须在 **JsHost 内换算成像素**再建 Action，不能透传。

### 4.3 无 NDK 时降级

`CMakeLists.txt`：QuickJS 源码缺失时编译 stub（`AUTOBALL_HAS_QUICKJS=0`），
JS 脚本不可用但动作流完整。Rhino 变体依赖网络拉 `org.mozilla:rhino`，CI 拉不到会失败。

### 4.4 引擎重入

见 3.4。JS 里调 `runAction` 执行 RUN_JS 会死锁，必须显式拒绝。

---

## 五、落地顺序

| 阶段 | 内容 | 依赖 |
|---|---|---|
| P0 | C++ 通用 `host()` + Kotlin 统一 prelude + `ab.*`/`zdjl.*` 命名空间 | 无 |
| P0 | 坐标单位解析（% / dp / px） | 无 |
| P1 | `findLocation`（color/text/image/node）、`getScreenColor`、`getScreenAreaColors` | 已有 `ConditionEval.matchColor/findColorPos/matchTemplate` 可复用 |
| P1 | 文件读写 + 本地存储（私有根映射） | 无 |
| P2 | `runAction`（含重入防护）、`check`、`getVars`/`clearVars` | 无 |
| P2 | `gesture` / `gestures` / touchDown-Move-Up | 需 MULTI_POINTER 后端能力 |
| P3 | 异步族 `xxxAsync` | 需先解决引擎单线程模型 |

---

## 六、参考

- 自动精灵官方文档：`zdjl.*` 98 个 + 全局函数 57 个 + console 14 个
- 研究细节：`/data/workspace/jsapi_research.md`（坐标 / findLocation / findNode / 存储 / runAction / 异步六项核实）

## v1.33.0 补充

### Promise 已可用

- QuickJS：`JS_Eval` 后宿主排空微任务队列（上限 100000 个 job）
- 脚本被包成 async IIFE，**支持顶层 await**（仅 QuickJS；Rhino 1.7.15 无 async/await）
- Rhino：注入同步 Promise 垫片（宿主 API 同步，终态必然已知，同步执行 then 不改变语义）
- `*Async` **仍是同步别名**：宿主调用本身同步阻塞，返回真 Promise 会让
  `Promise.all` 看起来并发、实际顺序执行，属误导

### require

```js
const utils = require('utils.js');   // 私有目录，路径映射同 readFile
```

只支持同步返回 exports 的模块，不支持 npm 包 / 网络加载。
