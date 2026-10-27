# 在安卓上运行 DeepSeek Harness 的手机 Agent：完整系统设计

> 作者：@kevin｜日期：2026-10-27｜状态：方案待评审
> 依据：Mobile-Harness v1.0.7 源码、dsh 0.2.1-rc.2 源码与 npm 包（本机实测）、DSHA build190 源码、rikkahub-agent-pure 源码、Rungic README、目标手机实测（见 §3）。
> 本文中标【实测】的结论都在本机或目标手机上跑过；标【源码】的读过源码；标【推断】的是根据经验判断，尚未验证。

---

## 0. 一页结论

- **要做什么**：一个安卓 App。它以**官方原版 DeepSeek Harness（dsh）**为大脑，以原生安卓能力为手脚。使用体验要做到 Pure 的水准（原生、轻、能长时间运行、能控制手机），能力上要拿到 dsh 的完整生态（插件、技能、子 agent、定时任务、工作流、MCP）。
- **推荐方案**：方案 A，即“原生外壳 + 独立引擎进程里嵌入 Node”。
  - dsh 跑在 App 自己声明的 `:engine` 进程里。Node 以 libnode.so 的形式通过 JNI 嵌入，**不经过 proot，也不算幽灵进程**。
  - App 和引擎之间只走 dsh 官方的 SDK JSON-RPC 协议。
  - 设备能力做成 dsh 插件，通过进程间通信（Binder）调用 App 内的能力服务。
  - 完整的 Linux 生态（apt 等）作为**按需启用的 proot 工作区**，只有 agent 执行命令时才用到。
- **同一套 App 同时支持四种“引擎位置”**：本机嵌入（A，默认）、本机 proot（B，兼容兜底）、系统 Linux 虚拟机（C，用户手动启用）、远程服务器（D）。之所以能做到，是因为 App 和引擎之间只有 SDK 协议这一条接口。
- **对 dsh 不 fork**：锁定版本，所有安卓适配都以 pnpm patch 加独立插件的形式维护，并争取推回上游。

---

## 1. 从第一性原理拆需求

### 1.1 用户真正要的东西
| 编号 | 需求 | 来源 |
|---|---|---|
| R1 | 用 DeepSeek 模型，而且要用官方 harness 的 agent 能力和生态（“蹭 DeepSeek”） | 用户 |
| R2 | 在手机上**独立**运行，不依赖电脑或服务器（但允许用户选择连远程） | 用户 |
| R3 | 能**控制手机**：读屏、点按、打开应用、读通知、文件、Intent 等 | Pure 的长处 |
| R4 | 能**长时间无人值守运行**：不爆上下文、不失控烧钱、被系统杀掉后能恢复 | Pure 的长处 |
| R5 | 有真正的 Linux 能力：bash、git、python、apt | DSHA 的长处 |
| R6 | 原生体验：启动快、包体小、省电 | Pure 的长处 |
| R7 | 免 root | 用户 |
| R8 | 安全：高权限操作必须经过审批、能审计、能撤销 | 第一性原理和 dsh 的 SAFETY.md |

### 1.2 物理约束（不可协商）
1. **dsh 是 Node.js 程序**，要求 Node 24（npm 包的依赖链要求如此）。【源码】
2. 安卓 App 的进程是 bionic 进程，受 SELinux（`untrusted_app` 域）、seccomp 和 W^X 约束：targetSdk ≥ 29 时，**App 数据目录里的文件不能被 execve 执行**，只有 APK 解压出来的 `nativeLibraryDir` 里的文件可以执行。【推断，属于 Android 10 起的公开行为】
3. Android 12 起，App 派生出来的子进程算作“幽灵进程”，全局上限约 32 个，在后台高 CPU 占用时会被杀。**App 用 `android:process` 声明的进程不算幽灵进程**，它由系统的 ActivityManager 管理，可以挂前台服务。【推断，属于公开行为】
4. 第三方 App **不能调用 AVF 创建虚拟机**（相关权限只授予系统或特权应用），只有系统自带的 Terminal 能用。【推断】
5. Google Play 要求新应用的 targetSdk ≥ 35，2026 年 8 月之后要求 36；无障碍服务和 `specialUse` 类型的前台服务都需要通过政策审核。

### 1.3 dsh 在安卓上的真实障碍（源码逐项核对）
| 障碍 | 位置 | 影响 | 处理 |
|---|---|---|---|
| `process.platform === 'android'`：flock 直接抛 `ERR_FLOCK_UNSUPPORTED_PLATFORM` | `native/system/.../flock.ts` | **会话持久化依赖它**（`session-persistence-jsonl/lease.ts`），一旦失败，会话写不了 | 用 NDK 编译 `node-addon-system-android-arm64`（system.node + landlock-run），并给 flock.ts 加上 android 分支 |
| 沙箱的平台链里没有 android | `sandbox-local` 的 `PLATFORM_CHAINS` | 要求沙箱的会话会直接失败（fail closed） | 加一条 `android: [landlock]` 链，前提是 probe 能通过；不能通过时走“仅审批”模式，并在界面上明确标出 |
| 默认 shell 写死成 `/bin/bash`，ENOEXEC 时回退到 `/bin/sh` | `terminal-bash/config.ts`、`subprocess-local/spawn-runner.ts` | 安卓上没有 `/bin` 目录 | 通过配置把 shell 指向内置的 bash；`/bin/sh` 的回退改成可配置（patch） |
| node-pty 没有安卓版本 | 依赖 | 交互式终端不可用 | 用 NDK 编译 node-pty |
| ssh 插件要求 platform 为 linux 或 darwin | `packages/ssh` | 远程 SSH 工具不可用 | patch，把 android 也算作 POSIX |
| Documents 目录的解析没有 android 分支 | `workspace-controller/default-directory.ts` | 默认工作目录无法解析 | 配置里显式指定工作目录 |
| `@vscode/ripgrep` 下载的是 linux 版二进制 | fs-search | 搜索不可用 | 在 nativeLibraryDir 内置 rg，并把路径注入进去 |
| 完整安装 841MB（其中 libreoffice-kit-wasm 146MB，还有 inspector、sherpa-onnx 等） | npm 安装【实测】 | 包体太大 | 生产构建时裁剪：去掉实验性 bundle 和 office/voice 组件，并提供“按需下载扩展包”（见 §6.3） |
| `node-addon-require-builtin` 没有 android-arm64 binding | cordis-plugin-loader【手机实测】 | **dsh 的 sdk、web 模式启动直接失败**（`--version` 不受影响） | 用 NDK 编译它的 android-arm64 binding |
| `link(2)` 硬链接被 SELinux 拒绝（EACCES） | `attachment-local/store.ts`【源码 + 手机实测】 | 附件存储失败 | patch：硬链接失败时退回到 copy + rename |
| koffi | 只在 win32 路径上使用【源码】 | 无影响 | — |

> 结论：dsh 的核心是纯 JS，安卓适配的工作量**集中在 5 个原生组件（system.node、landlock-run、node-pty、require-builtin、android-bridge）和大约 7 处平台判断或 patch 上**，完全可以用 patch 维护，**不需要 fork**。

---

## 2. 现有方案的长处和短处（学什么、避开什么）
| 项目 | 值得学的 | 要避开的 |
|---|---|---|
| **DSHA** | 免 root、开箱即用；离线 rootfs；受管更新，先在隔离区试运行，失败自动回切；备份和恢复；组件级自检；看门狗 | 整个 dsh 跑在 proot 里（启动 5–12 秒）；dsh 本身是幽灵进程；没有隔离；插件自动启用不经审核；局域网访问是明文 HTTP；APK 279MB |
| **Pure** | 大脑放在 App 进程内，不受幽灵进程影响；前台服务只在任务运行时开；申请忽略电池优化；针对各厂商的保活引导；Doctor 自检；用量账本和预算；工具结果截断后写入磁盘；按需加载工具定义；后台任务不自动批准 | 自己实现 harness，接不上 dsh 生态；单人维护；AGPL 协议（**只能借鉴思路，不能复制代码**） |
| **Mobile-Harness** | 原生 Compose 外壳，通过 `dsh --profile sdk` 的 JSON-RPC 驱动 dsh（已经是“协议优先”的做法）【源码 DshRuntimeBridge.kt】；同时支持多个 agent（Claude Code / dsh / Antigravity）；Keystore 存密钥；网页预览；能在手机上直接打包 APK；在线版和离线版两种安装包（89MB / 889MB），工具链按需下载；非 Play 版本用 targetSdk 28 规避 W^X，Play 版本用 36 | dsh 跑在 proot Ubuntu 20.04 里（同样慢，同样是幽灵进程）；只做写代码，不控制手机；每次 prompt 都单独起一次 SDK 会话 |
| **Rungic** | 原生容器带来的性能；agent 有独立的工作屏幕；“提出建议 → 用户批准某一版计划 → 执行”的系统维护闭环；MCP 化的桌面控制 | 需要 root 和刷内核 |
| **Termux** | bionic 原生软件包生态；用 targetSdk 28 规避 W^X；插件化的 API | targetSdk 28 上不了 Google Play |
| **nodejs-mobile** | 证明了 Node 可以通过 JNI 嵌入 App 进程 | 停留在 Node 18；Node 在一个进程里只能初始化一次 |
| **VS Code Remote / LSP / ACP** | 界面和引擎之间只走协议，引擎可以在本机、容器或远程 | — |
| **Claude Code / Codex CLI** | 分级审批策略（询问 / 工作区可写 / 完全放开）；沙箱与审批两层防护；可审计的会话日志 | — |
| **Chrome 多进程** | 崩溃隔离：引擎崩了界面不崩，引擎可以重启 | — |
| **Shizuku** | 由用户授权的 ADB 级能力，免 root | 每次重启后要重新激活（无线调试） |

---

## 3. 目标设备实测数据
- 设备：motorola XT2603-1，Android 16（SDK 36），内核 6.12.38-android16 GKI，arm64-v8a，内存 16GB。设备支持 `android.software.virtualization_framework`。已安装 DSHA（`dsh.android.client`）。【实测，由 @kabi 采集】
- 主机基线：在 glibc 版 Node 24.18 下运行 `dsh --profile sdk`，initialize 耗时约 5.3–6.5 秒，之后建会话 → 发 prompt → 调用 DeepSeek API（返回认证失败，符合预期）全链路通过。【实测】
- 手机侧可行性测试（详见附录 A）：bionic 版 Node 24 ✅；dsh JS 主体 ✅；还差一个原生 binding；Landlock ❌（内核没有开启）；硬链接 ❌。【实测】

---

## 4. 候选方案

四个方案的界面层和设备能力层完全相同（见 §5），**区别只在 dsh 引擎跑在哪里、怎么跑**。

### 方案 A：原生外壳 + `:engine` 进程内嵌 Node（推荐默认）
```
┌ 进程 :main（Kotlin/Compose）────────────────┐
│ 界面 · 审批 · 账单 · 设置 · 通知              │
│ CapabilityService（无障碍/Shizuku/通知/文件/…） │
└──────┬──────────────────────────────┬───────┘
       │ SDK JSON-RPC（经 Binder 转发）    │ Binder（设备能力调用）
┌──────▼──────────────────────────────▼───────┐
│ 进程 :engine（前台服务，specialUse）           │
│ JNI → libnode.so（Node 24 LTS，bionic）       │
│   └ dsh --profile android（锁定版本 + patch）   │
│       + dsh-android-device 插件 ──→ N-API → JNI → Binder │
│       + dsh-android-guard 插件（预算/截断/审批策略）    │
└──────┬──────────────────────────────────────┘
       │ spawn（仅工具命令）
       ├ 内置工具链：bash/toybox/git/rg/…（nativeLibraryDir 中的 lib*.so）
       └ proot 工作区（可选）：Ubuntu rootfs，用于 apt/pip/编译
```
- **好处**：
  - 冷启动只有 Node 自身的启动加 dsh 的初始化，不经过 ptrace；
  - dsh 不是幽灵进程；
  - 引擎崩溃不影响界面，重启 `:engine` 进程就能恢复，dsh 的会话日志本来就支持断点续跑；
  - 设备能力走 Binder，不开任何网络端口。
- **代价**：
  - 要自己用 NDK 编译 Node 24 的共享库（可以参考 Termux 的 nodejs-lts 编译脚本，Node 官方也支持 `--dest-os=android` 和 `--shared`）；
  - 要维护 4 个原生组件；
  - 内置工具链的编译和打包也是一份工作量。
- **主要风险**：Node 在同一个进程里只能初始化一次。所以“重启引擎”就等于“重启 `:engine` 进程”，设计上已经这样处理（见 §7.2）。

### 方案 B：原生外壳 + proot Ubuntu 里的 glibc 版 dsh（DSHA 式，作为兼容兜底）
- dsh 原样跑在 proot 里，用 glibc 版 Node；App 通过 SDK 协议和它通信。
- **好处**：和上游 linux-arm64 环境 100% 一致，第三方插件不用适配。
- **代价**：启动慢（DSHA 实测 5–12 秒）；ptrace 带来的性能损耗；proot 和其子进程都是幽灵进程；rootfs 需要几百 MB。
- **现成参照**：Mobile-Harness 就是这个方案的实现（原生外壳 + proot 里的 `dsh --profile sdk`），说明这条路能走通。
- **用途**：当某个 dsh 插件在 bionic 上跑不了时，用户可以按会话切到这个引擎。

### 方案 C：dsh 跑在系统 Linux 虚拟机里（AVF Terminal），App 作为客户端
- 用户在系统自带的“Linux 终端”里运行一键脚本，在虚拟机中启动 `dsh --profile sdk`，通过 socat 暴露出一个带鉴权的本地端口；App 通过端口转发连接过去。
- **好处**：真正的内核级隔离（符合 dsh SAFETY.md 的建议）；原生 glibc 环境，Landlock、bwrap 都可用；性能好。
- **代价**：第三方 App 不能自己创建虚拟机，需要用户手动操作；虚拟机内存有上限；虚拟机内的 agent 要控制手机，必须回调 App（用同一套能力协议，但走网络、需要鉴权）。目标设备支持 AVF。

### 方案 D：远程引擎（服务器或电脑）
- dsh 跑在用户自己的机器上，手机 App 是界面加设备能力端，通过 TLS 和设备配对连接，可选 Tailscale 或 WireGuard。
- **好处**：最省电、最稳定，算力也不受限。
- **代价**：需要一台服务器；设备能力的调用跨越网络，必须做强鉴权和审计。

### 方案 E（不推荐）：Pure 式，用 Kotlin 重写 agent 循环
- 用户的目标是借 dsh 的生态，这个方案和目标相悖，只作为对照列出。

### 方案对比
| 维度 | A 内嵌 | B proot | C 虚拟机 | D 远程 |
|---|---|---|---|---|
| 冷启动 | 快（预计 2–4 秒，等原生组件补齐后实测） | 慢（5–12 秒） | 中 | 取决于网络 |
| 幽灵进程风险 | 低（只有工具命令） | 高 | 低（虚拟机作为一个整体） | 无 |
| dsh 插件兼容性 | 高，需要少量 patch | 最高 | 最高 | 最高 |
| 隔离 | 审批 + 路径守卫（实测内核没有 Landlock） | 弱 | 强 | 强（物理隔离） |
| 包体 | 中（约 120–180MB，推断） | 大 | 小（App 本身） | 小 |
| 免 root、免电脑 | ✔ | ✔ | ✔，但需要用户手动操作 | ✘，需要服务器 |
| 工程量 | 大 | 中 | 小 | 中 |

**推荐**：A 作为默认引擎，B、C、D 作为同一个 App 里可选的引擎提供方（Engine Provider）。四者共用 §5 的全部模块。引擎提供方抽象一开始就做进设计里，不留作“以后再加”。

---

## 5. 完整系统架构（适用于所有方案）

### 5.1 模块清单
| 模块 | 语言 | 职责 |
|---|---|---|
| `app-ui` | Kotlin/Compose | 会话、流式渲染、审批面板、任务/定时/工作流、账本统计、设置、Doctor |
| `engine-client` | Kotlin | SDK JSON-RPC 客户端，包括 initialize、session/prompt/resume/wait、处理 approval/request 和 user-question/ask；对引擎提供方做抽象 |
| `engine-host` | Kotlin + C++ | `:engine` 进程、前台服务、JNI 启动 libnode、stdio 转发到 Binder、进程看门狗 |
| `engine-providers` | Kotlin | Embedded（A）、Proot（B）、AvfVm（C）、Remote（D） |
| `capability-service` | Kotlin | 设备能力的唯一入口；按能力分级授权，记审计日志 |
| `cap-accessibility` | Kotlin | 无障碍：读取界面树、点按、输入、全局手势 |
| `cap-shizuku` | Kotlin | 通过 Shizuku 获得 ADB 级能力：`input`、`am`、`pm`、`settings`、截屏、关闭幽灵进程限制 |
| `cap-system` | Kotlin | 通知监听、Intent、文件（SAF）、剪贴板、日历/联系人（按需权限）、电量/网络/位置 |
| `cap-screen` | Kotlin | MediaProjection 截屏；虚拟屏（通过 Shizuku 创建 VirtualDisplay，实验性） |
| `toolchain` | NDK | bash、toybox、git、rg、curl、python（可选），打包成 nativeLibraryDir 里的 lib*.so，App 内建立映射 |
| `workspace-proot` | Kotlin + C | 按需下载、校验、安装 Ubuntu rootfs；proot 启动器 |
| `dsh-android-runtime` | TS + patch | 安卓适配用的 pnpm patch 集合，加 `--profile android` 的 bundle |
| `dsh-android-device` | TS（dsh 插件） | 把设备能力注册成 dsh 工具，每个工具声明风险等级 |
| `dsh-android-guard` | TS（dsh 插件） | 预算、工具结果上限、后台审批策略、无人值守模式 |
| `native-addons` | C/C++ | `node-addon-system-android-arm64`（flock、landlock-run）、`node-pty`、`android-bridge.node`（N-API ↔ JNI） |
| `updater` | Kotlin | 引擎包和 rootfs 的受管更新（试运行后再切换，失败自动回切） |
| `backup` | Kotlin | 备份和恢复会话、设置、插件；密钥不进入备份 |
| `doctor` | Kotlin | 组件自检、厂商保活检查、权限清单、Landlock 和幽灵进程限制状态 |

### 5.2 App ↔ 引擎协议
- **只用 dsh 官方的 SDK JSON-RPC**（`initialize`、`session/prompt`、`session/resume`、`session/wait`、`session/working-directory/*`、`shutdown`，加上服务端请求 `approval/request` 和 `user-question/ask`，以及 `session.event` 和 `session.status` 通知）。【源码，并已在主机上实测】
- 在 A 方案下，stdio 管道就在 `:engine` 进程内部；它和 `:main` 之间用 Binder 加 ParcelFileDescriptor 管道转发字节流，**不开 TCP 端口**。
- 在 C、D 方案下，走 TLS 或 WebSocket，首次通过扫码配对交换公钥，之后每次连接都做双向鉴权。
- **扩展需求不改协议**：账单和预算这类数据，由插件写入会话事件（session event），App 从 `session.event` 里读出来。这样上游协议不用改，B、C、D 方案也能直接复用。

### 5.3 设备能力在 dsh 里的形态
- `dsh-android-device` 插件按能力组注册工具，例如 `phone_screen_read`、`phone_tap`、`phone_type`、`phone_app_launch`、`phone_notifications_list`、`phone_intent_send`、`phone_file_pick`、`phone_screenshot`、`phone_settings_get` 等。每个工具都声明 `risk`（read / write / sensitive / dangerous）。
- 调用链路：dsh 工具 → `android-bridge.node`（N-API）→ JNI → Binder → `capability-service` → 授权检查 → 执行 → 写审计日志 → 返回。
- 在 C、D 方案下，同一套能力改走鉴权后的 WebSocket，并以 MCP 服务器的形式暴露，这样 dsh 原生的 mcp-client 就能直接使用。

### 5.4 安全模型（多层防护）
1. **审批**：dsh 原生的审批策略（`ask`、`workspace-write` 等）通过 `approval/request` 交给 App 处理。审批界面展示工具名、参数、风险等级，并提供“仅本次 / 本会话 / 拒绝”三个选项。
2. **能力授权**：`capability-service` 按“能力组 × 会话 × 有效期”授权。sensitive 级别（短信、联系人、支付类 App）**每次都要审批**；dangerous 级别（卸载应用、改系统设置、发送消息）**每次都要审批，而且要求解锁设备**。
3a. **硬性禁止清单**（借鉴 Pure 的 hardline 层）：一小组无法挽回的命令（`rm -rf /`、写块设备、关机重启、fork 炸弹、编码载荷直接交给 shell 执行等）在审批之前就拒绝，任何授权都不能绕过。这一层只防粗心失误，不防刻意绕过。
3. **后台和无人值守**：沿用 Pure 的做法，**后台路径永远不自动批准**。定时任务或工作流只能使用预先授权的白名单能力；超出白名单的请求**明确拒绝**，返回结构化的 `tool_not_authorized`，让模型换个办法（学 Pure，避免无人应答时整个流程卡死），同时推送通知告知用户。
4. **沙箱**：**目标手机的内核没有开启 Landlock（实测 ENOSYS）**，所以安卓上没有内核级的文件沙箱。本机引擎的防护靠这几样：①审批；②dsh 的路径守卫，把工作区限定在 App 私有目录之下；③App 的 UID 隔离（引擎读不到其他 App 的数据）；④敏感能力全部由 Binder 侧把关（引擎本身没有权限，必须经过 capability-service）。在界面上明确标为“审批保护（无内核沙箱）”。需要硬隔离的会话，就切到方案 C（虚拟机）或 D（远程）。Landlock probe 继续保留：遇到开启了 Landlock 的内核时自动启用。
5. **插件**：第三方 dsh 插件的安装必须经过 App 内的审阅，展示来源、包摘要和权限声明；**不自动启用**（这一点正好避开 DSHA 的问题）。
6. **密钥**：API Key 存在 Android Keystore 加密的存储里，通过 dsh 的 credentials 引用（例如 `DEEPSEEK_API_KEY`）在运行时注入；不进入备份，也不写进日志。
7. **审计**：所有设备能力调用和审批决定都写入只追加的审计日志，可以导出。

### 5.5 长时间运行与成本（结合 dsh 现有能力和 Pure 的经验）
- dsh 已有的：compaction（含 tool-result-pruner 和 image-offload）、token-meter、subagent、schedule（cron）、workflow/ralph、goal。【源码】
- `dsh-android-guard` 补充的：
  - 按会话和编排树设置 token 预算与费用预算，超出时在派发前拒绝，并给出原因；
  - 工具结果设上限，超出部分写入磁盘，只保留开头和结尾；
  - 可以按天查看的用量账本（数据来自 token-meter 的事件）；
  - 无人值守模式下的审批策略（见 §5.4 第 3 条）。

### 5.6 后台存活（把能做的全做上）
1. `:engine` 是独立的 App 进程，挂 `specialUse` 类型的前台服务，**只在有会话在跑或有定时任务待触发时才挂**（学 Pure）；空闲超过 N 分钟后自动退出进程，释放内存。
2. 持有 PARTIAL_WAKE_LOCK，并设超时；申请忽略电池优化；通过 Doctor 页面为小米、vivo、OPPO、华为、摩托罗拉等厂商提供保活引导。
3. 关闭幽灵进程限制：如果有 Shizuku 或无线调试，提供一键执行 `device_config put activity_manager max_phantom_processes 2147483647` 和 `settings put global settings_enable_monitor_phantom_procs false`，并给出状态检测。
4. 定时任务：用 AlarmManager 的 `setExactAndAllowWhileIdle` 唤醒 `:engine`，再交给 dsh 的 schedule 执行。
5. 被杀后恢复：看门狗检测到 `:engine` 死掉后，重启进程并调用 `session/resume`。dsh 会自动修复中断的最后一轮对话。【源码】

### 5.7 工具链与 Linux 工作区
- **内置工具链**（不需要 proot，原生运行）：bash、toybox、git、ripgrep、curl、jq、python3（可选扩展包）。用 NDK 编译，放进 jniLibs，命名为 `lib<tool>.so`；首次启动时在 `files/bin` 下建立软链接，并写入 PATH。
- **proot 工作区**（可选）：启动参数带 `--link2symlink`，用软链接模拟硬链接（Pure 和 proot-distro 的做法）。需要 apt、pip 编译或者 glibc 二进制时启用。dsh 的 bash 工具可以按会话选择在“原生”还是“工作区”里执行。

### 5.8 数据、备份与更新
- 数据目录：`files/dsh-home`（DSH_HOME）、`files/workspaces/*`、`files/audit`、`files/ledger`。
- 备份：会话、设置、插件、审计日志、账本，打包成 tar.zst；密钥只保留引用，不保留值。备份前先校验，恢复时先预检（学 DSHA）。
- 更新：App 本身走 APK 签名更新；引擎包（dsh 加 patch）和 rootfs 作为独立资产，**先在隔离区试运行，通过后再切换，失败自动回切**（学 DSHA）。

---

## 6. 构建与发布

### 6.1 构建流水线
1. `node-android`：基于 Node 24 LTS 源码，参考 Termux 的 nodejs-lts 补丁，`--dest-os=android --shared` 产出 libnode.so（arm64，可选 x86_64）。
2. `native-addons`：NDK 编译 system.node、landlock-run、node-pty、android-bridge.node。
3. `dsh-bundle`：先对锁定版本的 dsh 执行 `pnpm install` 并应用 `patches/`，然后裁剪（去掉实验性 bundle、libreoffice-wasm、sherpa-onnx、其他平台的预编译文件），再做 tsdown 打包和压缩，产出 `engine.tar.zst` 加摘要。
4. `toolchain`：NDK 编译工具链，产出 jniLibs。
5. `app`：Gradle 打包；App Bundle 加 `extractNativeLibs=true`。
6. CI 门禁：在目标设备上运行 §8 的验收用例（由 @kabi 执行，或通过 adb 自动执行）。

### 6.2 发布渠道
- **GitHub Releases + F-Droid**：完整功能，包括无障碍和 Shizuku。
- **Google Play**：只有在政策允许的情况下才提供受限版本（限制无障碍用途），不作为主渠道。

### 6.3 包体策略
- APK 只放：引擎核心、内置工具链基础集、App 本身。
- office 技能、语音输入、python、proot rootfs 等作为“扩展包”，按需下载，并校验签名和摘要。

---

## 7. 关键设计决策（ADR 摘要）
1. **不 fork dsh**：锁定版本，用 patch 加插件维护；升级 dsh 时跑一遍 §8 的回归测试。安卓兼容补丁推回上游，目标是让 patch 越来越少。
2. **`:engine` 独立进程**：Node 一个进程只能初始化一次，所以把引擎放进可以单独重启的进程里，同时获得崩溃隔离，也不会被算作幽灵进程。
3. **协议唯一**：App 和引擎之间只走 SDK JSON-RPC，这是四种引擎提供方能共存的前提。
4. **能力走 Binder，不开端口**：本机场景零网络暴露。
5. **许可证**：dsh 是 MIT，DSHA 是 MIT，可以复用；Pure 是 AGPL，**只借鉴思路，不复制代码**；本项目建议采用 MIT 或 Apache-2.0。

---

## 8. 验收标准（一次性全部达成）
| 编号 | 验收项 | 判定 |
|---|---|---|
| AC1 | 冷启动到可以发送 prompt 的时间（A 方案） | 目标设备上 ≤ 4 秒，测 10 次取中位数 |
| AC2 | 完整对话：DeepSeek 调用 + bash 工具 + 文件读写 + 子 agent | 全部成功 |
| AC3 | 设备能力：读屏、点按、打开应用、读通知、截屏 | 每项在审批后成功；审批被拒时 agent 收到可读的拒绝原因 |
| AC4 | 长任务：屏幕关闭后连续运行 2 小时、50 次以上工具调用 | 不中断；中途手动杀掉 `:engine` 后能自动恢复 |
| AC5 | 预算：设定上限后 | 超出前拒绝，并写入账本 |
| AC6 | 安全：后台定时任务请求非白名单能力 | 返回 tool_not_authorized 并推送通知，不会执行；hardline 命令在任何授权下都被拒 |
| AC7 | 隔离：工作区之外写文件 / 越权调用能力 | 在 A 方案下被路径守卫或审批拦下；在 C 方案下被虚拟机隔离 |
| AC8 | 备份和恢复 | 换机恢复后会话可以 resume；备份里不包含密钥 |
| AC9 | 更新：故意放一个坏的引擎包 | 试运行失败，自动回切，原数据不受影响 |
| AC10 | 引擎提供方切换（A ↔ B ↔ D） | 同一个 App 可以连接，并完成 AC2 |

---

## 9. 风险登记
| 风险 | 可能性 | 影响 | 对策 |
|---|---|---|---|
| dsh 预览版引入不兼容改动 | 高 | 中 | 锁定版本，只依赖协议和插件 API，升级前跑回归 |
| 编译 libnode 遇到困难 | 中 | 高 | 先复用 Termux 的补丁集；退路是“bionic 版 node 可执行文件作为 `:engine` 进程的单个子进程”，此时幽灵进程数为 1 |
| 没有内核沙箱（Landlock 不可用，已实测） | 已发生 | 中 | 多层防护（§5.4）+ 提供硬隔离的引擎选项 C / D |
| 国产 ROM 的后台查杀 | 高 | 中 | 厂商保活引导 + Doctor + 断点续跑 |
| Play 政策 | 高 | 低 | 以 GitHub/F-Droid 为主渠道 |
| 设备能力被滥用（提示注入） | 中 | 高 | 风险分级审批，后台不自动批准，审计日志，敏感能力要求解锁设备 |

---

## 附录 A：手机侧可行性实测（2026-10-27，由 @kabi 在 adb shell 下执行）
运行环境：`uid=2000(shell)`，`u:r:shell:s0`。**这不是 App 的 untrusted_app 域**，App 域的结论要在验收阶段用测试版 APK 复核。
| 项目 | 结果 | 对设计的影响 |
|---|---|---|
| Termux 打包的 bionic 版 Node 24.22.1 | ✅ 正常运行。`platform=android`，没有 glibc，OpenSSL 3.6.5，ICU 78.3 | 证明 bionic 版 Node 24 可行，A 方案的前提成立 |
| `dsh --version` | ✅ 0.2.1-rc.2，耗时 0.27 秒 | JS 主体在 bionic 上能加载 |
| `dsh --profile sdk` | ❌ 启动失败：缺少 `node-addon-require-builtin-android-arm64` | 新增一个要编译的原生组件（已写进 §1.3） |
| flock 系统调用 | ✅ 正常 | 只需改 JS 层的平台判断，再编译 android 版 system.node |
| link(2) 硬链接 | ❌ Permission denied | 附件存储需要 patch（已写进 §1.3） |
| symlink | ✅ 正常 | — |
| Landlock | ❌ ENOSYS（内核没有开启），landlock-run 的 probe 返回 125 | 安卓上没有内核沙箱，安全模型已按此修订（§5.4） |
| no_new_privs | ✅ | — |
| node-pty | ❌ 没有 android-arm64 版本 | 需要编译（已在计划内） |
| 传输备注 | adb push 不能创建 symlink，kabi 把 30 个 symlink 展开后重新传输 | 打包时要避免 symlink，或者在设备上重建 |

另外，主机基线（glibc 版 Node 24.18）：`--profile sdk` 的 initialize 约 5.3–6.5 秒，建会话、发 prompt、调用 DeepSeek（认证失败，符合预期）全链路通过。
