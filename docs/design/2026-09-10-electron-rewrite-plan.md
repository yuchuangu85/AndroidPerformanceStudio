# 用 Electron 重写 Android Performance Studio 桌面端：实施方案

- 状态：Proposal（待决策）
- 作者：engineering
- 日期：2026-09-10
- 分支：`feature/electron-rewrite`
- 关联：`DESIGN.md`、`CONTEXT.md`、`docs/requirements/layout-complexity-inspector-three-solutions-plan.md`
- 跟踪 issue：[#21 PRD: Rewrite the desktop app on Electron without a JVM](https://github.com/yuchuangu85/AndroidPerformanceStudio/issues/21)
- ADR：`docs/adr/0036-rewrite-the-desktop-app-on-electron-without-jvm.md`

## 1. 目标

把当前基于 Kotlin / Compose Multiplatform Desktop 的桌面工作台重写为基于 **Electron（Node.js + Chromium + Web 前端）** 的应用，并保持：

1. 9 项 Profiler 能力与 Layout Inspector / Trace Analyzer / Source Workspace / AI 分析等既有能力不退化。
2. 六种原生安装包（macOS DMG/PKG、Windows MSI/EXE、Linux DEB/RPM）继续可发布。
3. 既有的版本化数据产物（Capture Artifact、Layout Snapshot v1、HPROF/Perfetto/simpleperf 产物、SQLite 会话库、用户设置）可被新应用读取。
4. 只读、本地优先、明确授权、AI 边界等既有产品约束不因换栈而松动。

非目标（本次重写不做）：

- 不重写 Android 设备端 Debug Agent（仍然是 Kotlin/Android 库与 APK）。
- 不重写 `third_party/firefox-profiler`、`third_party/perfetto` 第三方前端。
- 不新增后端云服务；仍为本地应用。

## 2. 现状盘点（基于当前 `main`）

| 维度 | 现状 |
| --- | --- |
| 应用框架 | Kotlin 2.3.21 + Compose Multiplatform 1.11.1，JDK 21 |
| 代码规模 | Kotlin 约 **128,070 行 / 798 文件**；其中 main 约 83,801 行，test 365 文件 |
| 模块 | `desktop-app` 外壳 + 16 个特性/共享模块（composite build） |
| 设备侧 | `layout-inspector/shared-kernel/android-agent-*`（Android 库，AGP 9.2.0） |
| 外部运行时 | `adb`；设备端 `simpleperf`（应用自带并推送）+ ART `am profile`；宿主 `llvm-symbolizer`；`trace_processor_shell`（固定 v57.2，随包发布，SHA-256 校验）；外部 AGI |
| JVM 关键库 | protobuf-java 4.35.1（compose-inspection、simpleperf proto）、sqlite-jdbc(xerial)、kotlinx-serialization、Compose **Fluent Design v0.1.0** + Material3 |
| i18n | Compose Resources：47 个文件、en/zh 共 **2,936 条** `<string>` |
| 已内置 Web 资产 | Firefox Profiler dist、Perfetto UI dist（`third_party/*/dist`） |
| 已有 Web 服务 | `perfetto-ui-server`（JDK `HttpServer`，本机端口 8090，供系统浏览器打开 Perfetto UI） |
| 数据序列化 | kotlinx.serialization JSON，版本化协议（`CaptureArtifact`、`LayoutSnapshot` v1） |
| 持久化 | 各特性 `storage-sqlite`（JDBC/SQLite）；应用数据在 `~/.android-performance-studio/`（`source-workspaces.db`、`analysis-sessions.db`、`source-cache/`）；设置走 `java.util.prefs`，OpenAI Key 在 macOS Keychain |
| 打包 | Gradle `compose.desktop` + jpackage，六种格式、5 个平台/架构组合、共 10 个资产，`release.yml` 流水线；版本默认 0.4.6，可用 `-PappVersion` 覆盖 |
| 导航 | `AppDestination` 13 个目的地 + `AppNavigator`，首页展示其中 9 个；已访问页面**保活**（retainedCompositions），无 URL/深链路由 |

### 2.1 规模分布

| 模块 | LOC | 占比 |
| --- | ---: | ---: |
| simpleperf-viewer（CPU Profiler） | 46,238 | 36.1% |
| layout-inspector | 23,652 | 18.5% |
| memory-profiler | 15,590 | 12.2% |
| startup-profiler | 5,758 | 4.5% |
| desktop-app（外壳） | 5,660 | 4.4% |
| frame-profiler | 5,458 | 4.3% |
| battery-profiler | 4,751 | 3.7% |
| perfetto-viewer | 4,354 | 3.4% |
| platform-core | 4,092 | 3.2% |
| network-profiler | 3,816 | 3.0% |
| source-workspace | 1,820 | 1.4% |
| ui-components | 1,540 | 1.2% |
| gpu-inspector-integration | 1,469 | 1.1% |
| benchmark-regression | 1,461 | 1.1% |
| ai-core | 1,405 | 1.1% |
| platform-perfetto | 1,006 | 0.8% |

**3 个模块占约 67%**，其余 13 个合计约 20k 行——重写的工作量与风险高度集中。

### 2.2 关键结论：Electron 不是"推倒重来"，而是"换壳 + 收口"

- **可视化层本就是 Web**：Perfetto UI 与 Firefox Profiler 都是 Web 应用，现在通过"本机 HTTP 服务 + 系统浏览器/iframe"桥接。Electron 可直接用 `BrowserView`/`<webview>`/`BrowserWindow` 承载，去掉 JDK HTTP Server 与"跳到系统浏览器"的割裂体验。
- **协议已经与 UI 解耦**：LayoutSnapshot v1、CaptureArtifact 等是版本化 JSON，可在 Node/TS 侧按同一契约实现，并用 Kotlin 生成的 golden fixture 做等价性验证。
- **与仓库自有规划对齐**：`docs/requirements/layout-complexity-inspector-three-solutions-plan.md` 中"方案 C：Web UI"的技术选型正是 **React + TypeScript + Vite**。Electron 重写可让桌面端与 Web UI 共用同一套前端，避免第三次实现。

## 3. 范围界定

### 3.1 需要迁移（重写为 TS/Node）

- `desktop-app` 外壳：导航、设置、主题、i18n、菜单、文档启动、版本
- `ui-components`：Compose 控件库 → Web 设计系统
- `platform-core`（`profiler-contracts`、`host-toolchain`、`adb-core`）：契约、工具链发现、进程执行、adb
- `platform-perfetto`：trace_processor 定位/会话/查询
- `ai-core`：OpenAI 传输、凭据存储、分析会话仓储
- `source-workspace`：Local/GitHub/AOSP 快照、缓存、索引、定位
- 9 个 Profiler 特性 + Layout Inspector + GPU + Benchmark 的 model / parser / storage / analysis / presentation
- 打包与发布流水线、CI 测试

### 3.2 不迁移（保持 Kotlin）

- `layout-inspector/shared-kernel/android-agent-*`、sample app：设备端 Agent，仍用 AGP 构建并随调试包分发。
- `third_party/*`：第三方前端与 Perfetto trace processor 二进制。

## 4. 核心架构决策（已确认）

> 决策已确认并记录于 ADR：`docs/adr/0036-rewrite-the-desktop-app-on-electron-without-jvm.md`。

### D1 — 迁移策略：渐进式（已确认）

- 采用 strangler：新 Electron 应用与旧 Compose 应用在过渡期**共存**，按特性逐个迁移并做等价验收；每个特性在 Electron 侧完成前仍可用旧应用对照。
- **重写期间不对外发布**：旧 Compose 应用冻结为"参考实现（oracle）"，仅用于生成 golden 与人工对照；新应用只在本地/CI 冒烟打包，不产生 GitHub Release。

### D2 — 无 JVM：纯 TS 重写，性能不足处用 Rust（已确认）

- **不保留 JVM sidecar**。所有逻辑用 TypeScript/Node 重写；可复用的原生工具（`adb`、`trace_processor_shell`）继续用，不引入 JVM。
- **性能回退策略**：为关键解析/算法设性能门槛；仅当 TS 不达标时，才用 **Rust** 重写该模块（`napi-rs` 原生扩展或 WASM 集成），而不是一开始就多语言。
- Rust 候选（按风险排序）：HPROF/bitmap 解析、simpleperf protobuf 与 ART `.trace` 解析、支配树/泄漏分析、flame graph 布局、大树与百万 sample 查询。
- Rust 的代价：需要各平台原生构建与预编译产物，并与 electron-builder 打包/签名集成；因此**默认 TS，Rust 只作兜底**。
- 性能门槛（Phase 0 用旧实现基线标定，初始目标）：
  - HPROF：给定大小堆转储的解析 + 直方图，端到端 ≤ 旧 JVM 实现的 1.5×；
  - simpleperf：百万 sample 导入与查询 ≤ 旧基线的 1.5×；
  - 层级：10,000 节点加载 ≤ 3s 且交互不卡顿；
  - 常规 UI 交互（滚动/命中/缩放）≥ 55fps。
- **触发 Rust 的流程**：先写 TS 版本 + 基准测试 → 未过门槛 → 记录 ADR 增补 → 该模块换 Rust 实现，接口不变。

### D3 — 前端技术栈（已确认）

- 渲染进程：**React 19 + TypeScript + Vite**（`electron-vite`）。
- 样式/设计系统：以现有 `ViewerTheme`/token 为基准重建为 CSS 变量 + 轻量组件库（Tailwind 可选，不作为硬依赖）。
- 状态：Zustand（局部界面状态）+ 显式 controller/事件通道承载异步任务。
- 图表：uPlot / ECharts（时序）、自绘 Canvas（flame graph / call tree / 层级画布）。
- 大树虚拟化：TanStack Virtual（10,000 节点目标）。
- 国际化：复用现有 `strings.xml` 抽取为 JSON 词条，保持 en/zh 双语文案一致。

### D4 — 进程模型与安全（已确认）

- `main`：设备/adb 编排、子进程、文件系统、SQLite、凭据、菜单。
- `utilityProcess` / `worker_threads`：重解析（HPROF、protobuf）与大批量计算，避免阻塞主进程。
- `renderer`：纯 UI，**`nodeIntegration: false`、`contextIsolation: true`、`sandbox: true`**。
- `preload`：`contextBridge` 暴露窄接口，IPC 参数用 Zod/JSON Schema 校验。
- 第三方 Web UI（Perfetto、Firefox Profiler）放在 `BrowserView` 或独立 `BrowserWindow`，不授予 Node 能力；本地 trace 通过自定义协议/本机回环加载。
- 凭据：使用 Electron 内建 `safeStorage`（Keychain/DPAPI/libsecret），替代 JVM 侧凭据存储，避免原生依赖。

### D5 — 打包与发布（已确认：重写期间不发布）

- 打包：**electron-builder**，覆盖 DMG/PKG/MSI/NSIS(EXE)/DEB/RPM，但**仅用于本地验证与 CI 冒烟**。
- **重写期间不发布**：不改动 `release.yml` 现有发布行为，不接入 `electron-updater`，不创建 GitHub Release。
- 代码签名/公证/自动更新留到特性对齐、通过发布门禁后的独立阶段再做。
- 产物校验（SHA-256 等）在正式发布阶段再启用。

## 5. 目标架构

### 5.1 目录结构（建议）

```text
electron-viewer/
  apps/desktop/                 # Electron 主进程 + preload + 渲染入口 + 打包配置
    src/main/                   # 主进程：窗口、菜单、IPC、服务编排
    src/preload/                # contextBridge 窄接口
    src/renderer/               # React 应用（外壳 + 路由）
  packages/
    contracts/                  # 版本化 JSON 协议与 Zod schema（对齐 Kotlin 序列化模型）
    platform-adb/               # SDK 发现、adb 调用、forward/exec-out/screencap
    platform-perfetto/          # trace_processor_shell 定位/会话/SQL
    platform-host/              # 主机工具链、进程执行、文件对话框
    storage/                    # better-sqlite3 访问层 + schema 迁移
    native/                     # （可选）Rust 加速层的 napi-rs / WASM 封装
    source-workspace/           # 本地/GitHub/AOSP 快照、缓存、索引、定位
    ai-core/                    # AI 传输、凭据、分析会话
    ui/                         # 设计系统与通用控件
    features/
      layout-inspector/
      trace-analyzer/
      cpu-profiler/
      memory-profiler/
      frame-profiler/
      startup-profiler/
      battery-profiler/
      network-profiler/
      gpu-inspector/
      benchmark-regression/
  e2e/                          # Playwright + Electron 端到端
  fixtures/golden/              # 复用并扩展 Kotlin 侧 golden fixtures
```

采用 pnpm workspaces；`desktop-viewer/` 保持不变直到对应特性迁移完成。

### 5.2 数据与协议复用

- 以 `CaptureArtifact`、`StudioResult`、`LayoutSnapshot v1` 为契约基线，先在 `packages/contracts` 落地，配契约测试。
- Node 侧解析结果必须能通过 Kotlin 侧生成的 golden 文件断言（数值、字段、错误分类一致）。
- SQLite：`better-sqlite3` 打开既有数据库文件，先做**只读兼容**，再补齐写入与迁移；schema 版本与迁移脚本以现有 `SQLiteSchema*.kt` 为准。
- 用户设置：现状使用 `java.util.prefs.Preferences`（键如 `application.theme`、`application.language`、`application.androidSdkPath`，以及 layout-inspector / simpleperf / source-workspace 的独立 node），并非 JSON 文件。落地平台为 macOS plist、Windows 注册表、Linux `~/.java/.userPrefs` XML。需实现一次性迁移读取（按平台解析对应存储）后写入 Electron 自己的配置目录；不破坏旧应用（可并存）。

### 5.3 模块迁移映射（含规模与难度）

LOC 为含测试的 Kotlin 行数（不含 `build/`）；难度为**迁移难度**（越高越难）。

| Kotlin 模块 | LOC | 代码占比 | Electron 归属 | 难度 | 关键依赖 / 说明 |
| --- | ---: | ---: | --- | --- | --- |
| `platform-core` | 4,092 | 3.2% | `packages/contracts`、`packages/platform-adb`、`packages/platform-host` | 低 | 契约 + `ProcessBuilder`（参数向量、不过 shell）→ Node `spawn` |
| `platform-perfetto` | 1,006 | 0.8% | `packages/platform-perfetto` | 低 | spawn `trace_processor_shell` + SQL |
| `ai-core` | 1,405 | 1.1% | `packages/ai-core` | 低 | `java.net.http`→fetch、JDBC→better-sqlite3、Keychain→`safeStorage` |
| `benchmark-regression` | 1,461 | 1.1% | `features/benchmark-regression` | 低 | 文件系统 + SQLite；另有 CLI 需一并迁移 |
| `perfetto-viewer` | 4,354 | 3.4% | `features/trace-analyzer` | 中 | 已是 Web UI；删除 `jdk.httpserver` |
| `ui-components` | 1,540 | 1.2% | `packages/ui` | 中 | 只移植设计 token 与 i18n 机制，不移植代码 |
| `gpu-inspector-integration` | 1,469 | 1.1% | `features/gpu-inspector` | 中 | 外部 AGI 工具链 + `Desktop.open` |
| `source-workspace` | 1,820 | 1.4% | `packages/source-workspace` | 中 | SQLite + 内容寻址缓存 + Local/GitHub/AOSP provider |
| `desktop-app` | 5,660 | 4.4% | `apps/desktop` | 高 | AWT/Swing 对话框、Java Prefs、菜单、打包 |
| `frame-profiler` | 5,458 | 4.3% | `features/frame-profiler` | 高 | ADB + SQLite + FrameTimeline/gfxinfo 解析 |
| `startup-profiler` | 5,758 | 4.5% | `features/startup-profiler` | 高 | ADB + SQLite + 启动实验编排 |
| `battery-profiler` | 4,751 | 3.7% | `features/battery-profiler` | 高 | ADB bugreport + SQLite |
| `network-profiler` | 3,816 | 3.0% | `features/network-profiler` | 高 | ADB + SQLite + 网络插桩 |
| `layout-inspector` | 23,652 | 18.5% | `features/layout-inspector` | 很高 | 最大 UI + agent 协议 + 分析引擎 + ADB gateway |
| `memory-profiler` | 15,590 | 12.2% | `features/memory-profiler` | 很高 | HPROF/bitmap 解析，依赖 `-Xmx4g` 堆与 JDBC |
| `simpleperf-viewer` | 46,238 | 36.1% | `features/cpu-profiler` | 很高 | 全仓最大模块：capture + parser + SQLite + flame graph UI + method recording |

> 代码高度集中：`simpleperf-viewer`(36%) + `layout-inspector`(18.5%) + `memory-profiler`(12.2%) ≈ **67%**；其余 13 个模块合计约 20k 行。迁移的**风险与工作量由这 3 个模块主导**，优先级排序应据此展开。

### 5.4 必须继承的行为契约（不可退化）

这些是迁移时的"兼容基线"，Electron 侧要逐条对齐：

- **导航语义**：`AppDestination` 13 个目的地、首页 9 个卡片；已访问页面**保活**以保留各自状态（Electron 用 keep-alive / 路由级状态实现等效，而非每次重建）。跨特性交接载荷（Frame→Layout Inspector、Frame/GPU/Benchmark→Trace Analyzer、Layout Inspector→Memory/Source）需保留。
- **设置键名契约**（迁移读取时按此匹配）：`application.theme`、`application.language`、`application.androidSdkPath`、`simpleperf.tooltipMode`、`simpleperf.engine`、`view.hideInvisibleHierarchyViews`、`view.hideInvisibleFindings`、`view.hideHierarchyIndices`、`view.showHierarchyIds`、`view.showHierarchyLayerVisibilityButtons`、`view.showVisibleViewBounds`、`view.canvasHitTestOrder.zOrder`、`canvas.bounds.normal`、`canvas.bounds.hovered`、`canvas.bounds.selected`、`archive.snapshotSizeMultiplier`，以及 AI node `com/androidperformancestudio/ai` 的 `model`、`endpoint`。
- **应用数据**：默认目录 `~/.android-performance-studio/`；迁移后应继续读写同一批 SQLite/缓存，避免用户数据分叉。
- **凭据**：现状 macOS 用 Keychain（其余平台仅内存、不持久化）。Electron 用 `safeStorage` 统一，注意"未配置即不持久化"的既有语义。
- **ADB**：发现优先级 `显式可执行文件 → 显式 SDK → ADB/ADB_PATH → ANDROID_HOME → ANDROID_SDK_ROOT → PATH → 各平台默认 SDK 目录`；调用必须保持**参数向量、不过 shell**，并保留设备/包名校验。
- **Perfetto**：`trace_processor_shell` 固定 **v57.2** 且校验 SHA-256，**不允许 PATH 回退**。
- **协议兼容**：主版本破坏性、次版本未知字段忽略（与 Kotlin 侧一致）。
- **文档**：`docs-user`（en）/ `docs-user-zh`（zh）随包分发并可在应用内打开（Electron 可直接用 `file://` 或自定义协议，无需 JVM 回环服务）。

### 5.5 盘点补充：容易被低估的实现细节

- **死目录**：`desktop-viewer/platform-adb/` 与 `desktop-viewer/import-core/` 为空目录，不在 `settings.gradle.kts` 中，迁移时忽略。
- **规模口径**：main Kotlin 约 **80,494 行**；含测试 128,070 行。含测试口径下 simpleperf 46.2k、layout-inspector 23.7k、memory 15.6k。
- **protobuf**：`protobuf-java 4.35.1` 用于 Compose 检查与 simpleperf proto 解析；TS 侧需 protobufjs / ts-proto 或自解析，是重要风险点。
- **simpleperf 自带设备二进制**：应用按 ABI 自带 `simpleperf` 并 push 到设备，同时依赖宿主 `simpleperf`/`llvm-symbolizer`，并用 `java.awt.Robot` 截图。Node 侧需等价的二进制资产管理与截图方案。
- **trace_processor 调用契约**：先 `trace_processor_shell server http --ip-address 127.0.0.1 --port <N> <trace>`，再 `trace_processor_shell query --remote 127.0.0.1:<N> <sql>`，结果按 **CSV** 解析。Node 侧 spawn + 解析即可，属低风险。
- **没有 `.sql` 文件**：所有 SQLite schema/查询都是 Kotlin 字符串字面量（`SQLiteSchema.kt`、`SQLiteSchemaV2.kt`、各 `Sqlite*Store.kt`）；Trace Processor 的 SQL 是嵌入式 `TraceQuery(sql, schema v57.2)`（PerfettoDiagnostics、memory/frame/startup adapter）。
- **设计系统不是纯 Material3**：`ui-components` 还依赖 **Compose Fluent Design v0.1.0 + fluent-icons-extended**；Web 侧无现成等价，需要自建设计系统。
- **ADB 使用并不统一**：`network-profiler` 用自己的 `ProcessAdbCommandRunner`；`gpu-inspector` 不依赖 `adb-core`；`benchmark-regression` 完全无 `platform-core` 依赖。迁移时按特性分别对齐。
- **无 JNI、无 expect/actual**：全部为 JVM-only Kotlin（Agent 是 Android 库，非 KMP），没有 Kotlin/Native 平台代码，降低了移植不确定性。
- **无 WebView/JCEF**：现有实现刻意采用"系统浏览器 + 本机 HTTP"；Electron 可直接内嵌 Perfetto / Firefox Profiler，但需重新处理本地 trace 的同源加载与 CSP。

## 6. 分阶段实施计划

### Phase 0 — 决策与地基（1–2 周）

- 输出 ADR-0036：无 JVM + Rust 回退、D1–D5 选型。
- 以旧 Compose 实现标定性能基线，确定 Rust 候选模块与门槛。
- 搭建 `electron-viewer/` monorepo：pnpm、electron-vite、TS strict、ESLint、electron-builder、CI（test + package）。
- 落地 `packages/contracts` 与 golden fixture 测试骨架。
- **验收**：空壳 Electron 应用可在三平台启动并打包；契约测试跑通。

### Phase 1 — 外壳与平台基座（2–3 周）

- 窗口外壳、导航、设置（含旧设置迁移）、主题、i18n、菜单、文档启动。
- `platform-adb`、`platform-perfetto`、`storage`、`platform-host`。
- **验收**：能发现设备、执行 adb 命令、启动 trace_processor、读写既有 SQLite；en/zh 与明暗主题可用。

### Phase 2 — 特性迁移（主体，可并行）

迁移顺序按"价值/风险"排序，每迁一个就用 golden 与人工对照验收：

1. **Trace Analyzer**（承载 Perfetto UI，风险最低、演示价值高）
2. **Layout Inspector**（核心能力，最大工作量）
3. **CPU Profiler / simpleperf**（protobuf 解析 + Firefox Profiler 承载）
4. **Memory Profiler**（HPROF，TS 性能门槛的关键验证点，可能首个 Rust 候选）
5. **Frame / Startup / Battery / Network**
6. **Benchmark Regression / GPU Inspector**
7. **AI 分析 + Source Workspace**

### Phase 3 — 打包验证与兼容（重写期间不发布）

- electron-builder 六格式**仅本地/CI 冒烟**（不签名、不公证、不发布）。
- 设置/SQLite 数据共存与迁移验证；旧版本回退路径。
- 功能对齐矩阵与发布门禁；正式发布、签名/公证、自动更新留待独立阶段。

### Phase 4 — 收敛

- 将 `desktop-viewer/` 标记为只读归档或移除、清理 Gradle 桌面构建与文档。
- 更新 `README.md`、`DESIGN.md`、`CONTEXT.md`、`docs/README.md`。

## 7. 测试与验收

- 单元测试：Vitest，覆盖 parser / 规则 / 契约 / 存储迁移。
- 契约等价：用 Kotlin 生成的 golden 文件断言 TS 实现（数值与错误分类一致）。
- 端到端：Playwright + Electron，覆盖"设备采集 → 分析 → 导出"主路径与离线导入。
- 性能门禁：10,000 节点层级与百万 sample 会话加载/交互延迟，沿用现有基线。
- 三平台安装/升级/卸载验证，沿用 `docs/TODO.md` 中既有发布门禁。

## 8. 风险与缓解

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| HPROF / simpleperf protobuf 重写正确性 | 高 | golden 等价测试；protobufjs/ts-proto；必要时按 D2 转 Rust |
| TS 性能不达标 | 中 | Phase 0 标定基线；超门槛模块用 napi-rs Rust 重写，接口不变 |
| Rust 多平台原生构建/签名成本 | 中 | 仅对少数热点引入；预编译产物随包分发 |
| 旧应用作为 oracle 的维护 | 中 | 冻结不发布，仅保证可构建与可对照 |
| Electron 包体积与内存 | 中 | 按需加载、`utilityProcess`、避免大依赖 |
| 原生依赖（better-sqlite3）跨平台构建 | 中 | electron-rebuild + CI 预编译；或 `node:sqlite`（Node 24）评估 |
| 代码签名/公证流程 | 中 | Phase 0 即打通最小打包链路，不拖到最后 |
| Perfetto/Firefox Web UI 与 Electron 安全模型冲突 | 中 | 独立无 Node 的 BrowserView；CSP 与网络白名单 |
| 旧数据/设置不兼容 | 中 | 迁移读取 + 只读兼容 + 回退路径 |
| 旧设置存于 `java.util.prefs`（跨平台三种底层存储） | 中 | 按平台实现迁移读取；迁移失败回退默认值并提示 |
| protobuf/HPROF 二进制解析在 TS 侧无现成等价 | 高 | protobufjs/ts-proto；HPROF 配 golden 等价测试，必要时转 Rust |
| Fluent 设计系统需在 Web 重建 | 中 | 以 token 为先、控件逐步替换，避免一次性重做全部组件 |

## 9. 工作量估算（粗略）

- **纯 TS 全量重写（已确认路线）：约 9–15 人月**；正确性与性能风险集中在 memory-profiler、simpleperf-viewer、layout-inspector。
- 若个别热点转 Rust（napi-rs）：预计额外 **0.5–1.5 人月/模块**，外加多平台原生构建成本。
- 前端（Renderer）约占 50–60%，Node 平台与解析层约占 30–40%，打包/发布约 10%。
- 重写期间不发布，因此工期不含签名/公证/自动更新的投入。

> 已按模块盘点修正：`simpleperf-viewer`、`layout-inspector`、`memory-profiler` 三者占 67%，是工期与风险的主要来源；平台层与轻量特性相对可预测。

## 10. 决策记录

- [x] D1 渐进式迁移；旧应用冻结为 oracle，重写期间不发布。
- [x] D2 无 JVM，纯 TS 重写；性能不达标用 Rust（ADR-0036）。
- [x] D3 React 19 + TypeScript + Vite。
- [x] D4 进程模型与安全（contextIsolation/sandbox/safeStorage）。
- [x] D5 electron-builder 仅本地验证，不发布。
- [ ] 待定：是否与"方案 C Web UI"共用前端（建议 Phase 2 后再评估）。
- [ ] 待定：正式发布的签名/公证/自动更新方案（Phase 3 之后再规划）。

## 11. 立即下一步

1. ✅ 完成模块级盘点与工作量修正。
2. ✅ 确认 D1–D5 并写入 ADR-0036。
3. 下一步：Phase 0 脚手架（`electron-viewer/` + CI 冒烟）+ 性能基线标定，随后从 Trace Analyzer 开始 Phase 2。

## 附录 A：SQLite 库与表清单（兼容迁移基线）

没有 `.sql` 文件，表结构写在 Kotlin 字面量里；迁移时按库逐一对齐。

| 归属 | 存储实现 | 主要表 |
| --- | --- | --- |
| ai-core | `AnalysisSessionRepository.kt` | `analysis_session`、`analysis_finding`、`analysis_evidence`、`analysis_candidate` |
| source-workspace | `SqliteSourceWorkspaceRepository.kt` | `source_workspace`、`source_snapshot`、`source_file`、`source_symbol`、`resolution_candidate` |
| simpleperf-viewer | `storage-sqlite/`（16 文件，最大 DB 面） | `SQLiteSchema.kt` / `SQLiteSchemaV2.kt` + 各 `SQLite*Queries.kt` / `SQLiteSampleStore.kt` / `SQLiteProfileRecordWriter.kt` |
| memory-profiler | `MemorySessionStore.kt` | 会话/快照相关表 |
| frame-profiler | `SqliteFrameSessionStore.kt` | `frame_session`、`frame_sample`、`frame_state` |
| startup-profiler | `SqliteStartupSessionStore.kt` | 启动会话相关表 |
| network-profiler | `SqliteNetworkStore.kt` | 网络会话相关表 |
| benchmark-regression | `SqliteBenchmarkStore.kt` | `benchmark_run`、`benchmark_metric` |
| battery-profiler | `SqliteBatterySessionStore.kt` | `battery_sessions`、`battery_runs`、`battery_snapshots`、`battery_deltas`、`battery_resources`、`battery_energy` |

> Trace Processor 的 SQL **不是** SQLite：是嵌入式的 `TraceQuery(sql, schema v57.2)`，位于 `PerfettoDiagnostics.kt` 及 memory/frame/startup 的 TraceProcessorAdapter 中，通过 `trace_processor_shell` 执行、结果按 CSV 解析。

## 附录 B：特性 → 共享模块依赖矩阵

| 特性 | ui-components | ai-core | source-workspace | platform-core | platform-perfetto |
| --- | :-: | :-: | :-: | :-: | :-: |
| layout-inspector | ✓ | ✓ | – | ✓ | – |
| simpleperf-viewer | ✓ | – | – | ✓ | – |
| perfetto-viewer | ✓ | – | – | ✓ | ✓ |
| memory-profiler | ✓ | – | – | ✓ | ✓ |
| frame-profiler | ✓ | – | – | ✓ | ✓ |
| startup-profiler | ✓ | – | – | ✓ | ✓ |
| battery-profiler | ✓ | – | – | ✓ | – |
| network-profiler | ✓ | – | – | ✓（无 adb-core） | – |
| gpu-inspector-integration | ✓ | – | – | ✓（无 adb-core） | – |
| benchmark-regression | ✓ | – | – | – | – |
| desktop-app（外壳） | ✓ | ✓ | ✓ | ✓ | ✓ |

- `ui-components`、`ai-core`、`source-workspace` 是无内部依赖的叶子库；特性之间**从不互相依赖**。
- `platform-core` 内部：`profiler-contracts` → `host-toolchain` → `adb-core`。

