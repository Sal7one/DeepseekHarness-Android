# DSH 手机 Agent：完整设计文档 v2

> 作者：@kevin｜2026-10-27｜状态：待 @WAWQAQ 审阅
> 本文取代 DESIGN.md（v1）。v1 中关于 dsh 安卓障碍的源码分析、手机实测数据（附录 A）仍然有效，本文直接引用。
> 标注约定：【实测】已在本机或目标手机上验证；【源码】读过源码；【推断】基于平台知识，需要在设备验收时确认。

---

## 0. 产品一句话
**用一句话让手机替你办事。** 以 DeepSeek Harness（dsh）为大脑，App 负责感知、执行、触发、入口和把关。

- 名称：暂定 **DSH Pocket**。dsh 的品牌规范要求社区项目用“DSH”前缀，不能直接用“DeepSeek Harness”作为项目名，也不能使用官方 Logo。说明文字里可以写“built on DeepSeek Harness”。【源码 BRAND_GUIDELINES.md】
- 目标用户：普通安卓用户，非开发者。**上手门槛是第一设计约束。**

---

## 1. 范围（已和 @WAWQAQ 对齐）
**做**：
- 文件
- App 启动和跳转
- 读屏和操作屏幕
- 系统开关
- 通知、短信、联系人、日历（敏感级）
- 媒体控制
- Shizuku 增强
- App 内浏览器
- 网页搜索
- 定时任务 + 条件触发
- 唤起层（悬浮球 / 系统助手 / 磁贴 / 分享）
- 任务视图（停止、接管）
- agent 独立屏幕（实验功能，需要 Shizuku）
- 用量和预算
- 分级审批、hardline 禁止清单、无人值守策略
- Doctor 自检
- 引导流程（onboarding）

**不做**：语音、本地模型、向量库、多助手、子 agent 配置界面、Telegram、内置播放器、Linux 工作区和编程工具链、SSH、多服务商界面、图片生成、NFC、壁纸、内置 Web 服务、操作日志和撤销、回收站、个人记忆、可视化工作流编辑器。

**dsh 原生复用，不做界面**：子 agent、技能、MCP、上下文压缩、web_search 和 web_fetch、schedule、browser-use provider 机制、agent-instructions（用户可以自行编辑的说明文件）。

---

## 2. 总体架构

```
┌─────────────────── 进程 :main ───────────────────┐
│ UI（Compose）：对话 / 唤起层浮窗 / 任务视图 / 规则 / 设置 / Doctor / 引导 │
│ AccessibilityService · NotificationListenerService · VoiceInteractionService │
│ CapabilityHub（设备能力的唯一入口：授权 → 执行 → 结果） │
│ TriggerHub（事件源 → 规则匹配 → 派发） │
└───────────┬──────────────── AIDL ───────────────┬───────────┘
            │ SDK 字节流（ParcelFileDescriptor 管道） │ 设备能力调用
┌───────────▼─────────── 进程 :engine（前台服务）───▼───────────┐
│ EngineService ── JNI ── libnode.so（Node 24 LTS，bionic）                │
│   └ dsh（锁定版本 + 安卓补丁）--profile android                            │
│       ├ @dsh-pocket/plugin-device：手机工具（调用 android-bridge.node）     │
│       ├ @dsh-pocket/plugin-guard：hardline、无人值守、预算、锁屏感知          │
│       └ @dsh-pocket/plugin-browser：WebView 实现的 browser-use provider     │
│ 子进程（仅工具命令）：/system/bin/sh、toybox、librg.so                       │
└────────────────────────────────────────────────────────────┘
```

**关键决策和理由**
| 决策 | 理由 |
|---|---|
| dsh 跑在独立的 `:engine` 进程，通过 JNI 嵌入 Node | 不走 proot（避免 5–12 秒的启动）；`android:process` 声明的进程不算幽灵进程；Node 一个进程只能初始化一次，独立进程正好可以整体重启；引擎崩溃不会带垮界面 |
| 界面和引擎之间只走 dsh 官方的 SDK JSON-RPC | 协议稳定、有文档，Mobile-Harness 已经验证过这条路【源码】；dsh 升级时界面不用改 |
| 设备能力放在 `:main` 进程 | 无障碍服务和通知监听服务由系统绑定到 App 的默认进程，能力只能在这里执行 |
| Node 访问设备能力：N-API → JNI → AIDL | 不开任何网络端口，没有可被其他 App 连接的攻击面 |
| 不 fork dsh | 锁定版本，用 pnpm patch 加独立插件维护（补丁清单见 v1 §1.3 和附录 A） |
| 不依赖 Google Play 服务 | 国产手机没有 GMS；所有能力都有系统 API 实现。唯一例外：有 GMS 时地理围栏优先用 Play 服务（后台启动豁免、更省电），没有时自动退回系统 API |

**命令行**：直接用系统自带的 `/system/bin/sh` 和 toybox，再加上内置的 `librg.so`（ripgrep）。dsh 的 shell 路径通过补丁改指向 `/system/bin/sh`。dsh 的持久 shell 依赖伪终端，所以需要 node-pty【源码】。
**需要编译的原生组件**（附录 A 实测确认）：`node-addon-system`（flock）、`node-pty`、`android-bridge`（新写）。`node-addon-require-builtin` 已去掉：dsh 只用它取 Node **内部**模块（`internal/modules/esm/loader` 等，`process.getBuiltinModule` 拿不到），现在引擎以 `--expose-internals` 启动（argv，NODE_OPTIONS 不允许该参数），js 补丁 02 让它直接 `require()`，不再加载靠反汇编探测偏移的原生插件。
**targetSdk 36，minSdk 29**：去掉工具链之后，不再需要用 targetSdk 28 来绕开“禁止执行数据目录文件”的限制。所有可执行文件都放在 nativeLibraryDir 里。

---

## 3. 引擎层
### 3.1 生命周期
1. 需要引擎时（发起对话、规则触发、定时到点），`:main` 通过 `startForegroundService` 拉起 `EngineService`。前台服务类型用 `specialUse`。
2. EngineService 在后台线程里调用 JNI 的 `node::Start`，参数是 `dsh --profile android`。stdio 换成管道，转发给 `:main`。
3. 冷启动期间显示进度条，目标是 ≤ 4 秒（验收项 AC1）。
4. **空闲 5 分钟**（没有运行中的会话、没有即将到点的定时任务）→ 调用 `shutdown` → 结束进程。
5. 引擎崩溃 → `:main` 检测到通道关闭后按 1/2/4 秒退避重启（`RestartPolicy`）；10 分钟内连续 3 次崩溃就熔断：不再自动重启，自动化启动直接失败，Doctor 提示；稳定运行 60 秒后复位。`session/resume` 只修复中断的那一轮（关闭悬空的工具调用）而不会继续【源码】，所以每个中断的根会话在重启后由 App 自动 resume 并发一条“引擎重启，检查已完成的步骤后继续”的续跑提示；每轮最多自动续跑 1 次，再次中断或熔断时显示带“继续”按钮的错误。两个进程都被杀时，下次启动把残留的“运行中”会话标为中断并给“继续”。
6. 运行中的轮次期间 `:main` 持有 PARTIAL_WAKE_LOCK（10 分钟超时，随会话事件续期；等待审批、回答、解锁时不持有），没有运行中的会话就释放（`TurnWakeLock`）。
7. 崩溃现场：Node stderr 写 `files/logs/engine.log`，每次启动轮转为 `engine.log.1..3`；`:main` 启动及每次异常退出后读取 `:engine` 的 ApplicationExitInfo，原生崩溃的 tombstone（protobuf）原样存为 `tombstone-*.pb`，可读字符串和退出原因写入 `exit-*.txt`（保留 3 份），在 Doctor / 关于页显示。debug 包开启 GWP-ASan（`gwpAsanMode="always"`）。
8. 运行环境统一在 `EngineRuntimeEnv` 组装：`NODE_EXTRA_CA_CERTS` 指向启动时从 `AndroidCAStore`（系统 CA 去掉用户停用的 + 用户安装的 CA）导出的 PEM（摘要不变就不重写）；`NODE_COMPILE_CACHE` 放在 cacheDir；`NODE_OPTIONS` 只在这里合并。
9. 从后台拉起前台服务被拒（`ForegroundServiceStartNotAllowedException`，通常是电池优化被重新打开）时，自动化降级为高优先级通知“点此执行：<规则名>”，点击即在前台启动并执行。

### 3.2 dsh 定制 profile `android`
- 在 `dsh-base` 上叠加以下内容：
  - 我们的三个插件；
  - 关闭 web-app、HMR、office、语音、inspector 等实验性 bundle；
  - 工作目录设为 App 私有目录 `files/home`；
  - shell 指向 `/system/bin/sh`；
  - 模型默认用 `deepseek-official`。
- **工具按需加载**：手机工具约 60 个，用 dsh 的 tool search 机制按需注入 schema，节省 token。具体机制在实现阶段核对 dsh 源码后确定。

### 3.3 构建（工程上已经核实可行）
- **libnode.so**：在本机 arm64 上原生交叉编译，用系统 clang-19 加 NDK sysroot，目标 `aarch64-linux-android29`，参考 Termux nodejs-lts 的补丁集，配置为 `--shared --dest-os=android`。
- **dsh 引擎包**：先对锁定版本执行 `pnpm install`，应用 `patches/`，再裁剪，打包成 `engine.tar.zst` 放进 APK 的 assets，首次启动时解压。附带摘要校验，App 升级时整体替换。
- **APK**：Gradle 构建；aapt2 是 x86_64 版本，借助本机已注册的 qemu binfmt 透明运行；也可以用 Docker 加 x86_64 镜像。

---

## 4. 设备能力层（CapabilityHub）
### 4.1 工具清单
每个工具都标注**风险等级**和**是否需要解锁**。

| 工具组 | 工具 | 风险 | 需要解锁 | 后端 |
|---|---|---|---|---|
| 文件 | `file_search` `file_read` `file_list` `file_stat` | 读 | 否 | 所有文件访问权限 + rg |
| | `file_write` `file_copy` `file_move` `file_rename` `file_mkdir` `archive_extract` `archive_create` | 写 | 否 | 同上 |
| | `file_delete` | 危险 | 否 | 同上；审批卡片列出完整文件清单 |
| App | `app_list` `app_info` | 读 | 否 | PackageManager |
| | `app_launch` `app_open_link` `app_send_intent` `share_to_app` | 写 | **是** | Intent |
| 屏幕 | `screen_read`（界面元素树） `screen_capture` | 读 | 是 | 无障碍 / 截图 |
| | `screen_tap` `screen_swipe` `screen_type` `screen_back` `screen_home` `screen_scroll` | 写 | 是 | 无障碍；有 Shizuku 时改用 `input` 命令。`screen_type` 逐级回退并回读校验：SET_TEXT → 无障碍输入连接（Android 13+）→ 自带“DSH 输入法” → Shizuku `input text`（ASCII）/ `ime set` 切到 DSH 输入法再切回（中文）→ 剪贴板粘贴 |
| 系统 | `device_status`（电量、网络、存储、位置） | 读 | 否 | 系统 API |
| | `sound_mode` `volume_set` `dnd_set` `brightness_set` `alarm_set` `flashlight` | 写 | 否 | AudioManager、NotificationManager、Settings |
| | `wifi_toggle` `bluetooth_toggle` `setting_put` | 写 | 否 | **仅 Shizuku** |
| 通知 | `notification_list` `notification_dismiss` | 读 / 写 | 否 | NotificationListener，按 App 白名单 |
| 通讯 | `contacts_search` `sms_read` `calendar_read` `call_log_read` | 敏感 | 否 | 运行时权限 |
| | `sms_send` `calendar_add` | 危险 | 否 | SmsManager / CalendarContract |
| 媒体 | `media_control`（播放、暂停、切歌、查询当前播放） | 写 | 否 | MediaSession |
| 剪贴板 | `clipboard_get` `clipboard_set` | 敏感 / 写 | 读取需要前台 | ClipboardManager |
| 浏览器 | dsh browser-use 工具（打开、点击、输入、读取） | 写 | 否 | App 内 WebView（可以在后台渲染）|
| 网页 | `web_search` `web_fetch` | 读 | 否 | dsh 原生；阻断内网地址 |
| Shell | `bash`（实际是 `/system/bin/sh`） | 写 | 否 | 先过 hardline 检查 |
| 规则 | `rule_create` `rule_list` `rule_update` `rule_delete` | 写 | 否 | TriggerHub |
| 独立屏幕 | `vscreen_start` `vscreen_stop`，之后的屏幕工具指向虚拟屏 | 写 | 待实测 | Shizuku 创建 VirtualDisplay（实验功能）|

### 4.2 优先级规则
**系统 API > Intent 或深链 > Shizuku 命令 > 无障碍模拟点击**。插件的 system prompt 和工具描述里会写明这条规则，引导模型优先选不需要解锁的路径。

### 4.2.1 人工接管
截图被拒（Android 14+ 返回 SECURE_WINDOW）或截图几乎全黑（`FLAG_SECURE` 页面）时，`screen_capture` 不再返回黑图：有人值守的会话暂停这次调用，显示“请你来操作”卡片和通知，用户亲自完成后点“继续”，工具返回“用户已手动完成”；点“停止”或无人值守会话直接返回 `secure_screen` 错误。

### 4.3 锁屏
- 需要解锁的工具在锁屏时被调用 → 不执行。任务进入“等待解锁”状态，推送通知“解锁后继续”。`:main` 监听到解锁事件（`ACTION_USER_PRESENT`，运行时注册）后，自动对该会话发送“已解锁，请继续”。
- 联网：申请“忽略电池优化”，确保深度休眠时也能调用模型。【推断，待设备验收】

---

## 5. 触发层（TriggerHub）
**规则模型**：`当 {事件} [且 {条件...}] 时，执行 {agent 指令 | 固定动作序列}`，并附带**授权能力白名单**（规则创建时由用户批准）。

| 事件 | 实现机制（尽量用系统托管的回调，避免常驻进程） |
|---|---|
| 定时（单次、周期、cron） | AlarmManager `setExactAndAllowWhileIdle`；需要 `SCHEDULE_EXACT_ALARM` 权限，引导用户授予 |
| 连上或断开 Wi-Fi（按 SSID） | `ConnectivityManager.registerNetworkCallback(PendingIntent)`，进程不在也能收到回调 |
| 蓝牙设备连接或断开 | 运行时注册的广播，在常驻监听服务里处理 |
| 充电开始或结束、电量阈值 | 运行时注册的广播，在常驻监听服务里处理 |
| 耳机插拔 | 运行时注册的广播，在常驻监听服务里处理 |
| 屏幕亮灭、解锁 | 运行时注册的广播，在常驻监听服务里处理 |
| 收到通知（按 App 和关键词） | NotificationListenerService（由系统常驻绑定）|
| 某个 App 被打开 | AccessibilityService 的窗口变化事件（已开无障碍时可用）|
| 进入或离开某个区域 | 有 Play 服务时用 `GeofencingClient`（其转换广播在后台启动前台服务的豁免列表里）；没有 GMS 时退回 `LocationManager.addProximityAlert`（不在豁免列表、后台定位每小时只更新几次，可能延迟数分钟，靠“点此执行”兜底；Doctor 标注）。都需要后台定位权限 |

- **常驻监听服务**：只有存在需要运行时广播的规则时才启动。它是一个很轻量的前台服务，带一条常驻通知“正在监听 N 条规则”，不加载 Node，所以几乎不耗电。
- **附加条件**：电量范围、时间段、星期几、当前前台 App、是否锁屏。
- **固定动作序列**：直接调用 CapabilityHub，不启动引擎、不调用模型，几毫秒就能完成。
- **agent 指令**：拉起引擎 → 新建一个无人值守会话 → 执行 → 结果写入会话，并推送通知。
- **防抖和限流**：同一条规则 60 秒内不重复触发；每天触发次数有上限，可以配置。

---

## 6. 把关层
1. **工具组开关**：在引导流程中按用户选择的场景预设，之后可以在设置里调整。
2. **分级审批**：
   - 读 → 自动执行；
   - 写 → 审批卡片，显示操作和影响范围，选项为“允许 / 拒绝 / 本会话都允许”；
   - 敏感 → 每次都审批；
   - 危险 → 每次都审批，并且要求生物识别或锁屏密码确认（BiometricPrompt）。
3. **hardline 禁止清单**：在 plugin-guard 里实现，任何授权都绕不过去。
4. **无人值守**：规则和定时任务的会话只能使用规则白名单内的能力，超出范围的调用返回 `tool_not_authorized`，同时推送通知。
5. **预算**：每个会话和每天各设 token 上限与费用上限，超出前拒绝执行。数据来源是 dsh 的 token-meter 事件。
6. **外部调用**：`RUN_TASK`（其他 App 让 DSH 执行一句指令）受“允许其他 App 调用”开关控制，默认关闭；关闭时拒绝并提示去哪里开启。外部任务不附带当前屏幕内容。
7. **密钥**：API key 用 Android Keystore 加密保存，不写日志，也不进系统备份（`dataExtractionRules` 排除）。

---

## 7. UI / UX
### 7.1 设计语言
- **视觉对齐 dsh**（token 已从 dsh 的 `ui-theme` 源码中提取，完整内容见 `dsh-design-tokens.md`）：
  - **主色是近黑和近白**：浅色模式下为 #0F1115，深色模式下为 #F9FAFB。**DeepSeek 蓝只作强调色**：浅色 #4176E6，深色 #7AAAFF，用于链接、焦点、选中态和信息类按钮。映射到 Material 3：近黑/白 → `primary`，蓝 → `secondary`；成功、警告、链接等 M3 没有对应槽位的颜色，放进扩展色 `DshColors`。
  - **字体**：用系统字体，中文是系统 CJK 字体，代码用等宽字体；Montserrat 只用于品牌字标。字重限定为 400、500、600、700。
  - **圆角**：4/8/12/16/20/28dp，卡片用 20dp；圆形和胶囊形保持正圆。
  - **描边和阴影**：普通边框和分隔线用 0.5px 的细线；浮起的表面（菜单、对话框、输入框）不加边框，用“细线描边 + 柔和阴影”来表现层级；遮罩不加模糊。
  - **动效**：100/200/300ms，标准缓动曲线；系统开启“减少动画”时关闭动效。
  - **图标**：dsh 使用自有的 SVG 线性图标（1px 和 1.3px 两种描边）。我们转换成 VectorDrawable，缺少的图标用 Material Symbols Outlined 的细线版补充，保持线性、细描边的风格一致。
- **交互遵循 Material 3 和安卓惯例**：边到边布局（edge-to-edge）、预测性返回手势、动态取色**关闭**（保持 dsh 的品牌色）、深色模式跟随系统、遵守无障碍规范（文字对比度、触控区域至少 48dp）。
- 组件用 Material 3 Compose 组件，通过主题定制成 dsh 的风格，不另起一套组件库。

### 7.2 信息架构（底部导航 3 个标签）
1. **对话**：会话列表 → 会话页
2. **自动化**：规则和定时任务的列表，以及最近的执行记录
3. **我的**：模型和 API key、权限中心、用量、Doctor、外观、关于

此外还有**唤起层浮窗**，它是独立于主界面的系统级入口。

### 7.3 核心页面
- **会话页**：
  - 消息流采用 dsh 风格的 Markdown 渲染；
  - agent 的每一步折叠显示为“步骤轨迹”，比如“🔍 搜索文件 → 找到 3 个”，点开能看到工具参数和结果，也能原样重跑；
  - 审批卡片内嵌在消息流里；
  - 输入框支持附件，生成过程中发送的消息会排队；
  - 出错时显示“继续”按钮。
- **任务视图**：从会话顶部或通知进入。显示实时轨迹、当前在操作哪个 App，以及“停止”“接管”两个按钮。接管后 agent 暂停，你操作完点“交还”，agent 再继续。
- **唤起层浮窗**：
  - 半屏底部面板，盖在当前 App 之上；
  - 顶部显示“当前页面：xxx”的上下文标签，可以取消；
  - 下面是输入框和最近的任务状态；
  - 发送后面板收起，进度显示在悬浮球或通知上；
  - 需要审批时面板自动弹出。
- **自动化页**：
  - 每条规则一张卡片，包含名称、触发条件摘要、开关、上次触发时间；
  - 新建只有一个入口：一句话描述，agent 生成规则后预览确认；
  - 详情页显示授权白名单和执行记录。
- **权限中心**：按能力列出每一项，显示状态（已授权 / 未授权 / 受系统限制）和一键“去开启”。

### 7.4 引导流程（目标：3 分钟内完成首次任务）
原则：**只在开头要最少的东西，其他权限等用到时再申请**（just-in-time），并且每次申请都先解释“为什么要”。

1. **欢迎页**：一句话介绍，配三个示例任务。
2. **连接 DeepSeek**：粘贴 API key，并提供“去 DeepSeek 开放平台获取”的链接。现场验证 key 是否可用。
3. **选择你想让它做的事**（多选卡片）：管理文件 / 操作 App / 自动化 / 读通知。根据选择，预先开启对应的工具组。
4. **必要的系统设置**（按上一步的选择动态生成清单，每一项一个按钮，完成后打勾）：
   - 通知权限（Android 13 及以上）
   - 忽略电池优化
   - 所有文件访问（选了“管理文件”时）
   - 无障碍（选了“操作 App”时）。先引导用户在“应用信息”里**允许受限设置**：Android 13 以后，侧载安装的 App 必须先完成这一步，才能开启无障碍和通知监听。这是侧载用户最常卡住的地方，**必须配图文引导**。
   - 精确闹钟（选了“自动化”时）
5. **厂商保活**：识别厂商（小米、OPPO、vivo、华为、荣耀、三星、摩托罗拉），给出对应的“自启动 / 后台运行”设置步骤和直达入口。
6. **试一试**：引导用户完成第一个任务，比如“帮我看看下载目录里最大的 5 个文件”。
7. **可选增强**（不强制，放进“我的”页面）：悬浮球（悬浮窗权限）、设为系统助手、Shizuku（安装、激活、授权的分步引导）。

### 7.5 Doctor 自检
检查以下各项，每项给出状态和“一键修复”入口：
- 各项权限和受限设置；
- 无障碍服务是否存活（部分厂商会在后台把它关掉）；
- 电池优化和厂商保活；
- Shizuku 状态；
- 引擎能否启动，以及启动耗时；
- 模型连通性和 key 是否有效；
- 存储空间；
- 规则监听服务是否在运行（被系统拒绝后台启动时说明原因）；
- 有自动化时，“不受电池优化限制”是**必需项**（红色），因为没有它后台无法启动引擎；
- 地点触发用的是 Play 服务还是系统 API；
- 引擎崩溃熔断状态、上次 `:engine` 异常退出记录（含 tombstone 摘要）。

---

## 8. 工程选型（参照 Google 官方示例 Now in Android 的最佳实践）
| 领域 | 选型 |
|---|---|
| 语言和构建 | Kotlin 2.x、Gradle Kotlin DSL、Version Catalog、convention plugins、KSP |
| UI | Jetpack Compose（BOM）、Material 3、Navigation Compose（类型安全路由）、Coil 3 |
| 架构 | 单向数据流 + MVVM（ViewModel + StateFlow），分为 Repository / UseCase 层 |
| 依赖注入 | Hilt |
| 存储 | Room（规则、执行记录、用量），DataStore（设置）；会话本身由 dsh 持久化 |
| 并发 | Coroutines / Flow |
| 序列化 | kotlinx.serialization（JSON-RPC 编解码）|
| 后台 | 前台服务、AlarmManager、WorkManager（只用于引擎包校验、清理之类的维护任务）|
| Markdown | multiplatform-markdown-renderer（Compose 原生实现）|
| 特权 | Shizuku API |
| 安全 | Android Keystore（加密 API key）、BiometricPrompt |
| 原生 | CMake + NDK sysroot；N-API（android-bridge）|
| JS 侧 | TypeScript、pnpm workspace、按 dsh 的插件规范（Cordis）编写 |
| 质量 | JUnit5、Turbine、Compose UI Test、Roborazzi 截图测试、Baseline Profile、detekt / ktlint |

### 8.1 模块划分
```
app/                       入口、导航、Hilt 装配
core/designsystem/         dsh 主题映射、基础组件
core/model/                领域模型
core/data/                 Repository、Room、DataStore
core/engine-client/        SDK JSON-RPC 客户端（Kotlin）
core/capability/           CapabilityHub 与各项能力实现
core/trigger/              TriggerHub 与事件源
engine/                    :engine 进程、JNI、libnode 加载（CMake）
feature/chat/  feature/task/  feature/quick/（唤起层）
feature/automation/  feature/settings/  feature/onboarding/  feature/doctor/
js/                        dsh 插件和补丁（pnpm workspace）→ 产出 engine.tar.zst
native/                    android-bridge、各原生组件的构建脚本
```

---

## 9. 验收标准
| 编号 | 验收项 |
|---|---|
| AC1 | 引擎冷启动 ≤ 4 秒（目标手机上取 10 次中位数）|
| AC2 | 文件任务：“找到下载目录里所有 PDF，按月份归档到不同文件夹”，审批后成功完成 |
| AC3 | App 任务：“打开设置里的蓝牙页面”，以及在任意 App 中读屏并点按 |
| AC4 | 锁屏任务：规则“连上 Wi-Fi 就静音”在锁屏和深度休眠状态下都能执行 |
| AC5 | 需要解锁的步骤在锁屏时被挂起，推送通知，解锁后自动继续 |
| AC6 | 无人值守的规则调用白名单之外的能力 → 返回 `tool_not_authorized`，并推送通知 |
| AC7 | hardline 中的命令在任何授权下都被拒绝 |
| AC8 | 引擎被杀掉后自动恢复，会话断点续跑 |
| AC9 | 引导流程：一位新用户在 3 分钟内完成首次任务（找 2 人试用）|
| AC10 | 唤起层：悬浮球、系统助手（摩托罗拉上）、磁贴、分享四个入口都能打开同一个面板，并带上当前页面的上下文 |
| AC11 | 预算：超出上限前拒绝执行，并在用量页显示 |
| AC12 | 独立屏幕（需要 Shizuku）：能在虚拟屏上打开 App 并操作；锁屏下的表现以实测结论为准 |

## 10. 手机侧一次性验证清单（构建出测试 APK 后，一次性交给 @kabi）
1. 安装测试 APK，走完引导流程，记录每一步的耗时和卡点。
2. 引擎冷启动 10 次的耗时。
3. AC2 至 AC12 逐项执行，记录结果并截图。
4. 锁屏加深度休眠（`adb shell dumpsys deviceidle force-idle`）时，触发 → 联网 → 调用模型 → 执行的整条链路。
5. Shizuku 虚拟屏在锁屏状态下的行为。
6. 在 App 自身的权限环境下（untrusted_app 域）复核 flock、硬链接回退、node-pty。
7. 内存占用（引擎空闲时、执行任务时）和 1 小时任务的耗电。

## 11. 主要风险
| 风险 | 对策 |
|---|---|
| 编译 libnode 遇到问题 | 参考 Termux 的补丁集；退路是把 Termux 编译的 node 可执行文件作为 `:engine` 进程的单个子进程（已实测能运行）|
| dsh 预览版引入不兼容改动 | 锁定版本，只依赖 SDK 协议和插件 API，升级前先跑回归测试 |
| 国产 ROM 的后台查杀、无障碍被关闭 | 厂商保活引导 + Doctor 检测 + 断点续跑 |
| 提示注入导致误操作手机 | 分级审批 + 危险操作需要生物识别 + 无人值守白名单 + hardline |
| 读屏和截图的 token 成本 | 优先读取界面元素树（裁剪掉无关节点），只在必要时截图；工具按需加载 |
