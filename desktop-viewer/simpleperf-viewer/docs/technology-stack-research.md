# Android Performance Studio 跨平台客户端技术栈调研与架构决策

- 文档版本：v1.0
- 调研日期：2026-07-13
- 目标平台：Windows、Linux、macOS
- 决策状态：Accepted（进入技术验证阶段）
- 关联文档：`docs/requirements.md`、`docs/product-design.md`、`docs/development-plan.md`

## 1. 结论摘要

### 1.1 推荐方案

首选技术栈为：

```text
Kotlin/JVM + Compose Multiplatform Desktop
+ JetBrains Runtime / JDK 21 LTS
+ kotlinx.coroutines / Flow
+ Java ProcessBuilder（ADB、Simpleperf 子进程）
+ protobuf-java（Simpleperf Record 解析）
+ xerial sqlite-jdbc + 原生 JDBC/PreparedStatement
+ Compose Canvas / Skia（Timeline、CallTree、FlameGraph）
+ Gradle + Compose nativeDistributions/jpackage
+ Windows、Linux、macOS 原生 CI Runner 分平台构建
```

这不是为了“统一技术栈”而选择 Kotlin，而是因为本产品的主要复杂度在桌面 UI 之外：

1. 管理 ADB、Simpleperf 和符号工具等长生命周期本地子进程。
2. 流式解析 Simpleperf protobuf，并处理百万级 sample。
3. 使用 SQLite 做批量写入、索引、聚合和可取消查询。
4. 以 Canvas 实现高密度 Timeline 和 FlameGraph，而不是普通表单 UI。
5. 与 Android、NDK、protobuf、JVM 工具生态保持较低集成成本。

### 1.2 决策条件

当前 Compose Multiplatform 官方兼容矩阵将 Desktop/JVM 标为 Stable，并支持 Windows、Linux、macOS；但最新兼容页对 macOS 明确列出的是 `macOS 13 arm64`。因此：

- V0.1 默认支持现代 64 位系统，macOS 首发基线为 Apple Silicon。
- 如果“Intel Mac x64”是硬性商业要求，必须在第 1 周完成 Compose 实机 PoC；若无法满足，则切换到 Flutter Desktop。
- Compose Desktop 官方文档显示 Linux 无桌面无障碍支持。如果首版必须通过完整屏幕阅读器合规验收，应重新评估 Flutter 或 Qt。

这两个条件必须作为技术门禁，不能等到发布阶段才验证。

## 2. 选型目标与约束

### 2.1 必须满足

| 维度 | 要求 |
|---|---|
| 平台 | Windows、Linux、macOS 均为 V0.1 发布目标 |
| 数据规模 | 至少支持 100 万 samples 导入、查询和聚合，不 OOM |
| 工具链 | 可靠执行 ADB、Simpleperf、report-sample，并处理 stdout/stderr、超时、取消和清理 |
| 数据解析 | 支持 Simpleperf `SIMPLEPERF` 头、版本、长度前缀和 Record 流式解析 |
| 本地存储 | SQLite、WAL、事务批量写入、索引、迁移、可取消查询 |
| 可视化 | 自定义 Timeline、CallTree、FlameGraph，支持缩放、过滤、下钻和联动 |
| 离线 | 无服务端依赖，可打开历史会话和原始文件 |
| 交付 | 三个平台都有可安装或可直接运行的 64 位发行物 |
| 可维护性 | UI、分析引擎、平台工具链、存储和导出边界清晰 |

### 2.2 非目标

- 不为了跨平台而使用 Kotlin/Native；桌面端统一运行在 JVM 上。
- 不在 UI 层直接持有百万 sample 或为每个节点创建 Composable。
- 不在 V0.1 自实现 ADB 协议或直接解析 `perf.data`。
- 不把 WebView、Chromium 或浏览器图表库作为首选渲染内核。
- 不在数据热路径引入重量级 ORM。

## 3. 候选方案对比

以下评分是基于本项目约束的工程判断，不代表框架的通用优劣。权重为：Android/工具链适配 20%、大数据处理 20%、绘图 15%、三平台交付 15%、团队学习与维护 15%、生态与风险 15%。

| 方案 | 加权分 | 优势 | 主要问题 | 结论 |
|---|---:|---|---|---|
| Kotlin/JVM + Compose Multiplatform Desktop | **8.6/10** | Android/JVM 生态契合；进程、protobuf、SQLite 成熟；Canvas/Skia；jpackage 发行链完整 | 自带 JVM 有包体成本；macOS x64 与 Linux 无障碍需验证；图表需自研 | **首选** |
| Flutter Desktop | 7.8/10 | 三平台稳定；官方矩阵覆盖 macOS x64/arm64；CustomPainter 强；单 UI 代码库 | 团队需引入 Dart；Windows/Linux SQLite 和深度桌面集成更多依赖插件/FFI；发布链分散 | 首选降级方案 |
| Tauri 2 + Rust + TypeScript | 7.4/10 | Rust 解析性能强；sidecar 和 SQLite 适配好；安装包较轻；Web 可视化生态丰富 | Rust+TS 双栈；三平台使用不同系统 WebView；大数据跨 IPC 和安全能力配置复杂 | 有成熟 Rust/Web 团队时可选 |
| Qt 6 + C++/QML | 7.1/10 | 桌面成熟；原生进程、SQL、图形能力强；性能上限高 | C++ 开发成本；Qt LGPL/GPL/商业许可需法务决策；与现有 Android/Kotlin 能力复用低 | 性能或原生能力极端优先时可选 |
| Electron + TypeScript | 6.2/10 | Web 团队上手快；Chromium 行为一致；前端图表生态最大 | Chromium/Node 多进程带来内存和包体成本，和性能分析工具争夺资源；IPC/安全边界复杂 | 不推荐作为 V0.1 主栈 |

## 4. 首选方案详细设计

### 4.1 语言与运行时

采用 Kotlin/JVM，不采用 Kotlin/Native。

- 开发和 CI 使用 JBR/JDK 21 LTS。
- 发布包通过 `jlink`/`jpackage` 带上裁剪后的运行时，用户无需安装 JDK。
- 版本通过 Gradle Version Catalog 集中管理，只使用 stable 版本，不锁定 EAP。
- JVM 堆大小、GC 和临时目录必须可配置，并在会话诊断中记录。

JetBrains 官方说明 Compose Desktop 运行在 JVM 上，并为主要桌面平台提供硬件加速渲染；Compose native distributions 会将所需 Java runtime 打入自包含发行包。

### 4.2 UI：Compose Multiplatform Desktop

职责：

- 窗口、菜单、快捷键、文件选择、对话框和应用状态。
- Home、Device、Capture、Report、Settings 页面。
- Canvas 视图和可访问语义。
- 只消费不可变的轻量 ViewState、分页列表和 viewport render model。

绘图规则：

1. Timeline 和 FlameGraph 使用单个或少量 Canvas，不为每个 sample/frame 创建 Composable。
2. 只绘制当前 viewport 可见节点，先裁剪再绘制。
3. 采用 LOD：缩放级别决定时间桶和文本密度。
4. 文本测量、颜色、路径和命中区域建立缓存。
5. 聚合和数据库查询不运行在 UI dispatcher。
6. 用户改变过滤条件时取消上一次任务，只提交最新结果。

### 4.3 并发模型：Coroutines + Flow

| 工作负载 | 执行策略 |
|---|---|
| ADB/Simpleperf 进程与文件 I/O | `Dispatchers.IO` + supervisor scope |
| protobuf 解码、调用树聚合 | 有界 CPU dispatcher |
| SQLite 写入 | 每个 session 单写者队列 |
| SQLite 查询 | 只读连接池/有界查询 dispatcher |
| UI 状态 | `StateFlow`，主线程收集 |
| 进度/日志 | 有界 `Flow`/Channel，禁止无界缓存 |

取消必须从 UI 一直传播到 use case、进程和数据库查询。停止采集时先发正常终止，再在超时后强制结束，并记录清理结果。

### 4.4 平台工具链边界

新增独立 `platform-toolchain` 边界，避免 OS 判断散落到业务模块：

```text
platform-toolchain
├── HostPlatformDetector
├── ExecutableLocator
├── ProcessRunner
├── ToolchainManifest
├── PathNormalizer
├── AdbAdapter
├── SimpleperfAdapter
└── PackageResourceResolver
```

关键约束：

- 使用参数数组调用 `ProcessBuilder`，不拼接 shell 命令字符串。
- stdout/stderr 必须并行持续消费，避免管道阻塞。
- 每次执行记录 executable、参数、工作目录、环境变量白名单、开始/结束时间和退出码。
- ADB 与 host Simpleperf 支持“自动发现 + 用户配置”，路径优先级必须确定。
- 平台资源按 `windows-x64`、`windows-arm64`、`linux-x64`、`linux-arm64`、`macos-arm64` 分目录。
- 工具版本、hash、来源和许可证写入 `toolchain-manifest.json`。
- GUI 应用不能假设继承 shell 的 `PATH`。

### 4.5 Protobuf 解析

采用 Google `protobuf-java` 生成类，但 Simpleperf 文件外层 framing 由本项目实现。

```text
BufferedInputStream
→ 校验 SIMPLEPERF magic
→ 读取格式版本
→ 循环读取 record length
→ 读取有界 record payload
→ Record.parseFrom(payload)
→ normalize
→ batch writer
```

要求：

- 对 record 长度设置合理上限，拒绝损坏或恶意文件造成大分配。
- 记录 byte offset 和 record index，错误可定位。
- parser 与 protobuf generated model 隔离，业务层只依赖 `profile-model`。
- 使用有界批次连接解析与 SQLite 写入，形成 backpressure。
- 禁止对整个文件调用单体 `parseFrom()`。

### 4.6 SQLite

采用 xerial `sqlite-jdbc` + 原生 JDBC/PreparedStatement；V0.1 不引入 ORM。

选择原因：

- 上游发行物包含 Windows、macOS、Linux 的 SQLite native library，并按当前 OS 自动加载。
- JDBC 便于精确控制 transaction、prepared statement、WAL、索引创建时机和查询计划。
- 动态筛选、递归树聚合和批量导入不需要额外 ORM 抽象。

使用规则：

- 一个 session 一个 SQLite 文件。
- 原始文件是事实来源，SQLite 是可重建分析索引。
- 导入时启用 WAL 和批量事务；非必要索引在批量导入后创建。
- 所有 SQL 都使用参数绑定。
- schema migration 和 query plan 使用 Golden 测试。
- 分析查询只返回聚合模型或分页结果，不返回百万行到 UI。

### 4.7 模块边界

```text
desktop-app                 # Compose 入口、窗口、路由、平台发行配置
presentation               # 页面、ViewModel、轻量 ViewState
application                # 用例、状态机、任务编排
platform-toolchain         # OS/arch、路径、进程、外部工具和资源
device-adb                 # ADB 领域适配
capture-simpleperf         # record/stat/list/report-sample 领域适配
parser-simpleperf-proto    # framing + protobuf 流解析
profile-model              # 与框架无关的统一模型
storage-sqlite             # schema、DAO、查询、迁移
analysis-engine            # Top/CallTree/FlameGraph/诊断规则
visualization-model        # viewport/LOD/render model/命中测试
export-adapters            # JSON、CSV、session package、外部格式
test-fixtures              # perf.data/protobuf/SQLite/Golden 结果
```

依赖方向只能由 UI 指向 application，再指向领域接口；平台、存储和外部工具是接口实现。`analysis-engine` 不依赖 Compose。

## 5. 三平台构建与发布策略

Compose 的官方打包插件基于 jpackage，支持以下原生产物：

| 平台 | 首发产物 | 构建环境 | 验证重点 |
|---|---|---|---|
| Windows | `.msi`、`.exe` | Windows runner | 路径空格、中文路径、Web/杀软误报、代码签名、x64/arm64 |
| Linux | `.deb`、`.rpm`、portable app image/tar.gz | Ubuntu runner | glibc/发行版兼容、Wayland/X11、字体、执行权限、桌面入口 |
| macOS | `.dmg`、`.pkg` | macOS runner | Apple Silicon、签名、公证、Gatekeeper、文件权限 |

Compose 官方不支持跨 OS 交叉打包，因此 CI 必须有 Windows、Linux、macOS 三类原生 runner。发布流水线：

```text
unit/integration tests
→ per-OS package
→ install on clean runner/VM
→ launch smoke test
→ toolchain self-check
→ import same Golden protobuf
→ compare Top/Timeline checksum
→ sign/notarize
→ publish checksums + SBOM
```

三端不能只验证“应用能启动”，必须使用同一份 Golden 数据验证解析、SQLite 和聚合结果一致。

## 6. 备选方案适用条件

### 6.1 何时改选 Flutter Desktop

满足任一条件时进入 Flutter PoC：

- Intel Mac x64 是 V0.1 硬要求，而当前 Compose stable 不能通过实机验证。
- 团队已有成熟 Flutter/Dart 桌面经验，而 Kotlin/Compose 经验不足。
- UI 动效和多端视觉复用优先级显著高于 Android/JVM 工具链复用。
- Linux 屏幕阅读器支持是首版硬性合规要求。

即使选 Flutter，也应保持同样的 `toolchain/parser/storage/analysis` 边界，并将重分析放在 isolate 或 native worker 中。

### 6.2 何时改选 Tauri 2

- 团队已有 Rust + TypeScript 能力。
- 期望复用 WebGL/Canvas 可视化资产。
- 可以接受三个系统 WebView 的兼容测试和前后端 IPC。
- 希望解析、SQLite、压缩等重任务由 Rust core 执行。

### 6.3 何时改选 Qt 6

- 团队以 C++/Qt 为主。
- 极端性能、平台原生 API 或成熟企业桌面能力高于开发效率。
- 已完成 Qt LGPL/GPL 或商业许可证评审。

## 7. 必做技术验证

在正式功能开发前完成 5 个 PoC，任何一个不达标都必须重新评估选型：

| PoC | 验证内容 | 退出标准 |
|---|---|---|
| POC-01 三平台空壳 | 同一代码在 Windows/Linux/macOS 构建、安装、启动 | 三平台 clean VM 均通过 |
| POC-02 工具进程 | 连续执行 ADB、取消长进程、消费 stdout/stderr | 无死锁、僵尸进程和残留文件 |
| POC-03 百万记录 | 生成/导入 100 万 Record 并写入 SQLite | 不 OOM；耗时和峰值内存被记录且可接受 |
| POC-04 可视化 | 10 万 FlameGraph 节点与高密度 Timeline | 缩放/拖动可交互，UI 无长时间冻结 |
| POC-05 发行包 | 三平台打包并加载各自 SQLite/native/tool binary | 干净机器运行成功，hash/版本可追踪 |

### 7.1 P0 PoC 当前结果（2026-07-14）

- POC-03 已在 macOS arm64/JDK 21 上用规范化 schema v1 复测：100 万条合成记录以 10000 samples 为批次写入 SQLite，耗时 7541.260 ms，峰值堆增量 160926928 bytes，数据库大小 74149888 bytes；满足 <10 s、<512 MiB 的 P0 临时门槛。
- POC-04 已完成 CPU 侧 viewport/LOD 复测：百万记录 Timeline 索引构建 17.333 ms，240 帧交互模型准备 P95 为 0.174 ms；10 万节点 FlameGraph 的 240 帧模型准备 P95 为 0.389 ms。
- POC-02 已完成首轮：基于 `kotlinx-coroutines-core 1.11.0` 的 `ProcessRunner` 使用参数数组启动进程，并发持续消费 stdout/stderr；大输出、非零退出、超时和显式取消测试通过。ADB 按用户配置、`ANDROID_HOME`、兼容 `ANDROID_SDK_ROOT`、`PATH`、默认 SDK 目录发现，本机执行 `adb version` 与 `adb devices -l` 成功；设备解析覆盖空列表、多设备、offline、unauthorized、no permissions 和未知状态。
- WBS-023~026 已实现：设备端/bundled simpleperf 选择与 SHA-256 部署策略、Start/Stop/Cancel 采集状态机、标准 session 目录、`perf.data` 拉取，以及命令/双流/退出码/截断信息持久化。Stop 通过独立 ADB 命令向 simpleperf 发送 SIGINT 后继续拉取；Cancel 终止当前链路；两者最终都独立清理远端文件并保留证据。当前无连接设备，真实 App smoke 保留为 WBS-027 环境验收。
- WBS-030~033 已实现：host simpleperf 确定性发现、版本/hash 校验、官方 `report-sample --protobuf --show-callchain` 转换命令、固定 AOSP schema 代码生成，以及 `SIMPLEPERF`/LE version/LE record length 的有界流式解析与 offset 错误定位。
- WBS-034~039 已实现：protobuf normalizer、SQLite schema v1/迁移/共享 callsite、有界事务批量写入、过滤查询、质量统计，以及 protobuf 直导和 `perf.data` 转换导入。原始材料是事实来源，解析失败只清理可重建数据库。
- WBS-013 使用单次 bulk `getprop`，读取 `ro.product.model`、`ro.product.cpu.abilist`、`ro.build.version.sdk` 和 `ro.build.version.release`；旧设备仅在 ABI 列表缺失时回退 `ro.product.cpu.abi`，避免多次 shell 启动。
- WBS-014 通过 `id -u` 判断当前 shell 是否已 root，通过 `ro.build.type` 区分 user 与可执行 `adb root` 的 userdebug，通过 `simpleperf --version` 和 `simpleperf list` 验证设备端工具及可用事件。非 root 的 API 29+ 设备只标记为支持 profileable-from-shell，仍要求目标 App 声明 `<profileable android:shell="true" />`。
- WBS-015 使用 `cmd package list packages` 获取包名，使用带显式列定义的 `ps -A` 和 `ps -T -p` 获取进程/PID 与线程。命令继续通过参数数组执行，PID 使用正整数类型传递；刷新失败保留结构化进程错误，本地搜索不重复访问设备。
- WBS-020~022 将 Simpleperf record 参数建模为不可产生 frequency/period 冲突的 sealed type；高级参数 UI 可编辑 event、frequency/period、duration/manual stop、callgraph 和 scope，执行命令与可复制预览始终由同一参数对象生成。模板仅提供初始值。
- 在线采集成功后在原证据目录执行 host simpleperf 转换与 SQLite 索引，完成后直接打开报告；Home 同时识别会话目录、会话包、`perf.data` 和 protobuf，离线导入可选 mapping 与 symbols/binary_cache。
- Compose `Canvas` 已接入有界 `TimelineFrame`，确保 UI 消费的是像素列级模型而不是百万条 sample。
- 结果与复现方法见 `docs/p0-performance-poc.md`；GPU 实际绘制、窗口事件延迟以及 Windows/Linux 复测仍属于后续门禁。

额外门禁：

- Intel Mac 若在支持范围，增加 x64 PoC。
- Linux 增加 Ubuntu 20.04/22.04/24.04、X11/Wayland 最小矩阵。
- Windows 增加 x64 主路径和 arm64 安装/启动验证。

## 8. ADR 决议

### ADR-001：采用 Kotlin/JVM + Compose Multiplatform Desktop

- 状态：Accepted with validation gates
- 原因：Android/JVM 工具生态、进程控制、protobuf、SQLite、Canvas 与三平台打包的综合成本最低。
- 不选 Flutter：Dart/插件/FFI 和桌面发布链增加本项目的非核心复杂度。
- 不选 Tauri/Electron：不希望将 WebView/Chromium、IPC 和双语言栈引入性能分析工具核心。
- 不选 Qt：C++ 与许可成本高于当前需求带来的收益。

### ADR-002：三平台同属 V0.1 P0

- Windows、Linux、macOS 均必须完成安装、启动、离线导入和核心报告验证。
- 在线采集也必须在三平台通过；不能用“UI 可运行”替代端到端验收。
- 每个平台使用原生 CI runner 构建，不依赖交叉编译。

### ADR-003：平台差异集中在 platform-toolchain

- UI 和分析代码禁止直接判断 OS。
- 可执行文件、路径、权限、环境变量、退出语义和打包资源由平台层负责。

### ADR-004：大数据只通过查询/渲染模型进入 UI

- UI 不持有全部 sample。
- Timeline/FlameGraph 的重聚合在后台执行、可取消、可缓存。
- 渲染采用 viewport、LOD、渐进更新和稳定帧预算。

## 9. 官方与上游资料

- [Compose Multiplatform Desktop：Windows、macOS、Linux 与硬件加速](https://github.com/JetBrains/compose-multiplatform)
- [Compose Multiplatform 平台稳定性：Desktop/JVM Stable](https://kotlinlang.org/docs/multiplatform/supported-platforms.html)
- [Compose Multiplatform 兼容矩阵与 JDK 限制](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html)
- [Compose native distributions：自包含 runtime、DMG/PKG、MSI/EXE、DEB/RPM 与禁止跨平台打包](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html)
- [Compose Desktop 平台组件](https://kotlinlang.org/docs/multiplatform/compose-desktop-components.html)
- [Compose Desktop 无障碍支持状态](https://kotlinlang.org/docs/multiplatform/compose-desktop-accessibility.html)
- [Compose Canvas/DrawScope 绘图](https://developer.android.com/develop/ui/compose/graphics/draw/overview)
- [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-guide.html)
- [Protocol Buffers Java](https://protobuf.dev/getting-started/javatutorial/)
- [xerial sqlite-jdbc 跨平台 native library](https://github.com/xerial/sqlite-jdbc)
- [Flutter Desktop 官方支持](https://docs.flutter.dev/platform-integration/desktop)
- [Flutter 支持平台与 CPU 架构矩阵](https://docs.flutter.dev/reference/supported-platforms)
- [Tauri 架构与系统 WebView](https://v2.tauri.app/concept/architecture/)
- [Tauri Sidecar](https://v2.tauri.app/develop/sidecar/)
- [Tauri 分发](https://v2.tauri.app/distribute/)
- [Electron 进程模型](https://www.electronjs.org/docs/latest/tutorial/process-model)
- [Electron 打包](https://www.electronjs.org/docs/latest/tutorial/application-distribution)
- [Qt 开源与商业许可](https://www.qt.io/development/qt-framework/qt-licensing)
- [Qt QProcess](https://doc.qt.io/qt-6/qprocess.html)
