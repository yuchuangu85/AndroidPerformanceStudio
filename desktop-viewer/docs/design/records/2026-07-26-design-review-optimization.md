# Design Review — Optimization Points

> 评审日期：2026-07-26
> 评审范围：`AndroidPerformanceStudio` 根构建、各功能域 composite build，以及 `perfetto-viewer` 内部实现
> 说明：本文区分代码事实、基于代码的风险推断和需要额外验证的外部行为；工作量与优先级仅作为排序参考。

## 项目架构概览

当前仓库由一个统一桌面 Shell 和 10 个功能域组成，但这些功能域并不都是同一种 Gradle 模块形态：

```text
desktop-app/                         # 根构建中的统一 Shell：导航、首页、统一设置
layout-inspector/                    # 根构建中的多项目模块，共 12 个子项目
simpleperf-viewer/       includeBuild # CPU Profiler，同时提供部分共享基础模块
perfetto-viewer/         includeBuild # Perfetto Trace 分析，本次评审重点
memory-profiler/         includeBuild # Heap dump
frame-profiler/          includeBuild # 帧耗时/Jank
startup-profiler/        includeBuild # 启动耗时
battery-profiler/        includeBuild # batterystats
network-profiler/        includeBuild # HTTP 流量
gpu-inspector-integration/ includeBuild # AGI 集成
benchmark-regression/    includeBuild # Macrobenchmark 回归
```

更准确地说，仓库包含 10 个功能域：Layout Inspector 直接属于根构建，其余 9 个功能域通过 `includeBuild` 组成 composite build。每个功能域内部又包含多个 Gradle 模块，不应将“功能域”和“Gradle 模块”混为一谈。

当前跨 composite build 共享的基础模块主要由 `simpleperf-viewer` 提供：

- `profile-model`
- `platform-toolchain`
- `device-adb`

其他 included build 通过 `com.androidperformancestudio:<module>:0.1.0-SNAPSHOT` 坐标引用它们，并由 Gradle composite dependency substitution 解析到 `simpleperf-viewer` 中的对应项目。

---

## 1. 【中等】Perfetto 存在至少 8 个未纳入构建的历史模块目录

`perfetto-viewer/settings.gradle.kts` 当前只包含 `perfetto-*` 命名的 9 个活跃模块：

- `perfetto-app`
- `perfetto-presentation`
- `perfetto-capture`
- `perfetto-trace-processor`
- `perfetto-ui-server`
- `perfetto-model`
- `perfetto-analysis`
- `perfetto-storage`
- `perfetto-export`

以下目录未被 `settings.gradle.kts` include，因此默认不会参与 Perfetto 构建：

| 未纳入构建的目录 | 当前活跃实现或实际提供方 |
|---|---|
| `capture-perfetto/` | `perfetto-capture/` |
| `presentation/` | `perfetto-presentation/` |
| `storage-perfetto/` | `perfetto-storage/` |
| `trace-processor-bridge/` | `perfetto-trace-processor/` |
| `trace-analysis/` | `perfetto-analysis/` |
| `export-adapters/` | `perfetto-export/` |
| `platform-toolchain/` | `simpleperf-viewer/platform-toolchain/` |
| `device-adb/` | `simpleperf-viewer/device-adb/` |

前 6 个目录包含与活跃模块相同 FQCN 的旧实现，例如两份 `PerfettoCaptureSession` 和两份 `TraceProcessorSession`。这些目录并非完整镜像：新模块通常包含更多实现和测试，两边代码已经发生分叉。

Git 历史显示，旧命名和 `perfetto-*` 命名目录在最初引入 Perfetto 功能时同时出现，并不是一次后续 rename 后遗漏删除，因此不能简单描述为“最近迁移留下的旧目录”。

### 影响

- IDE、代码搜索和源码索引可能显示相同 FQCN 的多份定义。
- 维护者可能误改不参与构建的实现。
- 未纳入 settings 的源码不会受到常规编译、测试和静态检查保护。
- `platform-toolchain/` 和 `device-adb/` 的本地旧实现已经与实际共享实现分叉，容易误导依赖关系判断。

### 建议

1. 先确认没有脚本、文档示例或手工构建流程引用这 8 个目录。
2. 对照活跃模块检查是否存在尚未迁移的有效实现。
3. 验证 Perfetto 模块测试和根构建检查通过后，删除确认无用的目录。
4. 增加结构测试，确保未纳入 settings 的历史模块不会再次出现。

该问题属于明确的仓库卫生和可维护性问题，但当前没有证据表明它会直接造成运行时故障，因此不标为 P0，也不将删除风险描述为“无”。

---

## 2. 【中等】共享 toolchain/ADB 已存在，但所有权和使用方式不一致

Perfetto 的活跃模块当前通过以下坐标依赖共享基础设施：

```kotlin
implementation("com.androidperformancestudio:platform-toolchain:0.1.0-SNAPSHOT")
implementation("com.androidperformancestudio:device-adb:0.1.0-SNAPSHOT")
```

这些坐标实际由 `simpleperf-viewer` 中的 `platform-toolchain` 和 `device-adb` 项目提供。`perfetto-viewer` 目录下的同名模块没有被 settings include，是历史死代码，而不是当前活跃依赖。

真正存在的问题是共享边界的使用不一致：

- `PerfettoWorkspace.discoverPerfettoDevices()` 仍直接执行 `adb devices -l` 并内联解析输出。
- Layout Inspector 在 `layout-inspector/adb-gateway` 中维护另一套进程执行和 ADB 输出解析实现。
- `simpleperf-viewer/device-adb` 已提供更完整的 ADB 设备模型和解析器。
- Perfetto 声明了 `device-adb` 依赖，但活跃源码没有使用 `com.androidperformancestudio.adb` 下的类型，可能存在未使用依赖。
- `platform-toolchain` 已被 Memory、Frame、Startup、Battery、Perfetto 等多个功能域复用，但其代码所有权仍位于 `simpleperf-viewer`。

### 建议

按以下顺序处理：

1. 删除 Perfetto 内未纳入构建的 `platform-toolchain/` 和 `device-adb/` 旧副本。
2. 让 Perfetto 设备发现复用现有 `device-adb` API，删除 `PerfettoMainPage` 中的内联解析。
3. 检查并删除 Perfetto 中未使用的 `device-adb` 依赖，或补齐实际使用关系。
4. 评估 Layout Inspector 与其他 profiler 的 ADB 需求是否能共享稳定的最小契约，避免直接把 Layout Inspector 专用 gateway 暴露给所有功能域。
5. 如果共享模块将长期服务多个功能域，再把 `profile-model`、`platform-toolchain`、`device-adb` 迁移到中立的独立 included build；不要再次创建一套重复实现。

“直接让 perfetto-viewer 依赖根构建中的 `layout-inspector/adb-gateway`”会加强功能域间耦合，并不适合作为默认方案。

---

## 3. 【中等】PerfettoWorkspace 同时管理 11 个状态和多类副作用

`PerfettoMainPage.kt` 当前包含 11 个独立的 Compose mutable state：

```kotlin
var captureState by remember { mutableStateOf<PerfettoCaptureState>(...) }
var sessions by remember { mutableStateOf<List<TraceSession>>(emptyList()) }
var recentFiles by remember { mutableStateOf<List<Path>>(emptyList()) }
var activeTraceFile by remember { mutableStateOf<Path?>(null) }
var adbPath by remember { mutableStateOf("adb") }
var devices by remember { mutableStateOf<List<PerfettoDevice>>(emptyList()) }
var selectedDeviceSerial by remember { mutableStateOf<String?>(null) }
var analysisSession by remember { mutableStateOf<TraceProcessorSession?>(null) }
var diagnosticQuery by remember { mutableStateOf<DiagnosticQuery?>(null) }
var diagnosticResult by remember { mutableStateOf<String?>(null) }
var diagnosticError by remember { mutableStateOf<String?>(null) }
```

同时存在 6 个 `LaunchedEffect` 和 1 个 `DisposableEffect`，负责：

- 收集 capture session 状态。
- 根据 ADB 路径刷新设备。
- 加载历史 session。
- 响应外部传入的 trace 文件。
- 在 capture 完成后保存 session、更新活动 trace 和最近文件。
- 在活动 trace 变化后停止 Trace Processor session、清理诊断结果。
- 页面销毁时停止 capture、UI server 和 analysis session。

这里原先的“17 个 MutableState”计数不准确；正确情况是 11 个 mutable state 加 6 个 `LaunchedEffect`。

### 风险

- 一个 Composable 同时承担 UI 渲染、文件选择、设备发现、持久化、进程生命周期和业务状态协调。
- 多个回调和 effect 可以修改相同状态，后续扩展时较难确认状态转换顺序。
- `TraceProcessorSession`、`PerfettoUiServer` 等资源生命周期与 Compose 状态紧密耦合，测试成本较高。

目前没有测试或故障记录证明已经出现竞态，因此应将竞态描述为风险推断，而不是既成故障。

### 建议

- 引入可测试的 `PerfettoWorkspaceState`，集中表达页面状态。
- 抽取 `PerfettoWorkspaceStateHolder` 或同等职责对象，负责设备刷新、session 持久化、trace 切换和资源生命周期。
- 让 Composable 主要负责渲染状态和转发用户意图。
- 在重构前为以下行为补充回归测试：capture 完成、trace 切换、诊断 session 清理、设备刷新和页面销毁。
- 是否使用 `StateFlow`、普通 state holder 或 reducer，应在确定桌面 Compose 生命周期和线程模型后决定，不预设 Workspace 必须“只做 collect”。

---

## 4. 【中等】TraceProcessorSession 同时启动 HTTP server 并通过 CLI 查询

当前实现存在明确的混合模式：

- `start()` 启动 `trace_processor server http --port 9001 <trace>`。
- `query()` 不复用该 server，而是为每次 SQL 查询重新执行 `trace_processor query <trace> <sql>`。
- `query()` 又要求 session 已处于 `isRunning` 状态，因此诊断前必须先启动一个并不处理该查询的 HTTP server。

### 已确认的影响

- 每次诊断查询都会创建新的子进程。
- HTTP server 和 CLI query 具有两套独立的执行、超时和错误处理路径。
- 固定默认端口 `9001` 增加了端口冲突的可能性。

### 需要进一步验证的部分

- 新 CLI 子进程是否每次完整重新加载 trace，以及不同 trace 大小下的实际耗时，需要基准测试确认。
- 代码注释称 9001 server 用于 Perfetto UI 原生解析加速，但普通 `launchTraceInUi()` 路径只启动 `PerfettoUiServer` 并向浏览器提供 trace 文件，没有显式连接该 Trace Processor server。需要确认 Perfetto UI 是否会自动发现并使用它。
- 固定的 Perfetto v57.2 工具究竟支持 `server http`、`--httpd` 或哪些 protobuf RPC 契约，需要针对实际打包二进制验证，不能只依据命令名称推断。

### 建议

先明确两个独立需求：

1. 内置诊断查询是否需要复用长生命周期 Trace Processor。
2. 浏览器中的 Perfetto UI 是否确实需要本地 Trace Processor 加速。

然后在以下方向中选择：

- **RPC 模式**：如果 v57.2 的 protobuf RPC 契约稳定且收益明确，为诊断实现 RPC client，复用已经加载 trace 的 server。
- **CLI 模式**：如果不引入 protobuf/RPC 支持，则让诊断直接使用 CLI，不再为了满足 `query()` 前置条件而启动 HTTP server；UI 加速作为独立能力管理。

在选择前应先增加小、中、大 trace 的查询基准，并验证打包二进制的真实命令行契约。不要在未验证的情况下直接将替代命令写死为 `trace_processor --httpd`。

---

## 5. 【低】共享 model 已存在，但模块命名和所有权不够中立

`StudioResult`、`StudioError` 和 `ErrorCategory` 并非来源不明，也没有在各 feature 中重复定义。它们明确位于：

```text
simpleperf-viewer/profile-model/src/main/kotlin/com/androidperformancestudio/model/StudioResult.kt
```

`profile-model` 已作为 composite build 中的共享模块，被 Perfetto、Memory、Frame、Startup、Battery 等功能域引用。Perfetto 的 `perfetto-model` 还通过 `api` 依赖将其传递给内部模块。

当前问题是：一个实际承担全局错误模型和结果模型的模块仍命名为 `profile-model`，且所有权位于 `simpleperf-viewer` 功能域内。这会让共享边界看起来属于 CPU profiler，而不是整个应用。

### 建议

- 短期保留现有实现，补充共享模块所有权和 composite dependency substitution 文档。
- 评估将通用错误/结果类型与 CPU profile 领域模型分离，避免整个 `profile-model` 被当作全局基础模块。
- 只有在模块职责和迁移收益明确时，才创建中立的 `shared-model` included build；迁移现有类型，不再定义第二份同名模型。

---

## 6. 【低】首页使用固定 4 列布局，但已经支持垂直滚动

当前首页包含 10 张功能卡片，`HOME_GRID_COLUMN_COUNT = 4`，通过 `entries.chunked(4)` 排列为 `4-4-2`。

页面已经使用 `verticalScroll`，因此功能增加不会直接导致内容不可访问，但会继续增加页面高度。当前更明确的技术限制是列数固定，没有根据窗口宽度自适应。

### 建议

按以下顺序评估：

1. 优先让列数或卡片最小宽度响应窗口尺寸，避免窄窗口仍强制显示 4 列。
2. 当功能数量或信息架构确实需要时，再增加“核心工具/扩展分析”等分组。
3. 只有在用户研究表明入口过多影响发现效率时，再考虑“更多工具”折叠区或二级导航。

首页分组属于产品和 UX 决策，不能只根据当前 10 张卡片从源码得出唯一结论。

---

## 7. 【信息】跨 Feature 路由已有类型约束，但 route 与 payload 尚未原子化

当前导航并非完全依赖无类型的可变字段：

- 目的地由 `AppDestination` enum 表达。
- Frame Profiler → Layout Inspector 使用强类型 `InspectorCorrelationHint` data class。
- `AppNavigator` 的状态 setter 为 private，外部通过 `openLayoutInspector()` 和 `openPerfettoTrace()` 等方法修改导航。
- 路由参数的清理行为已有 `AppNavigatorTest` 覆盖。

当前可以改进的部分是：

- `destination` 与 `inspectorCorrelationHint`、`perfettoTraceFile`、`perfettoTraceNotice` 分开存储，类型系统不能完全排除 destination 与 payload 不匹配的组合。
- `openPerfettoTrace(path, sourceTool)` 的 `sourceTool` 是任意字符串，调用方目前传入 `"GPU Inspector"` 或 `"Benchmark Regression"`。
- Layout Inspector → Memory Profiler 等跨 feature 回调仍由 Shell 临时保存附加状态，没有统一的路由载荷模型。

### 建议

- 当跨 feature 载荷继续增加时，用 sealed route 将 destination 和 payload 建模为一个原子状态，例如 `Perfetto(trace, source)`、`LayoutInspector(hint)`。
- 将 `sourceTool: String` 改成 enum 或 sealed source type，并在 UI 层完成本地化文案转换。
- 保留 Shell 作为跨 feature 协调边界，避免功能域直接相互依赖。
- 如果路由规模保持当前水平，可先维持现有实现，不为类型化而引入过度抽象。

---

## 修订后的优先级

| 优先级 | 条目 | 主要收益 | 前置验证 | 风险 |
|---|---|---|---|---|
| P1 | 清理 Perfetto 未纳入构建的历史模块 | 消除重复源码和误修改风险 | 确认无脚本/手工流程引用；运行 Perfetto 与根构建检查 | 低 |
| P1 | 明确 Trace Processor query/server 模式 | 避免不必要进程与潜在大 trace 查询开销 | CLI 契约验证；不同 trace 大小基准 | 中 |
| P1 | 让 Perfetto 复用现有 ADB 设备解析 | 删除内联重复逻辑，统一错误和设备状态处理 | 对齐 Perfetto 所需的设备字段与刷新语义 | 中 |
| P2 | 收敛 PerfettoWorkspace 状态与资源生命周期 | 提高可测试性和状态转换可读性 | 先补 capture、trace 切换、清理等回归测试 | 中 |
| P2 | 调整共享 model/toolchain/ADB 的模块所有权 | 让跨功能域依赖关系更清晰 | 明确模块职责，避免大范围坐标迁移 | 中 |
| P3 | 首页响应式列数与后续信息分组 | 改善不同窗口尺寸和功能扩展体验 | UI 尺寸测试与产品信息架构确认 | 低 |
| P3 | 将路由和 payload 原子化 | 提升跨 feature 参数完整性 | 跨 feature 场景继续增加时再实施 | 低 |

## 验证要求

执行以上优化时至少应验证：

- `perfetto-viewer` 的测试、静态检查和 `checkAll`。
- 根 `desktop-app` 的导航、结构和发布工作流测试。
- composite build 的依赖替换仍解析到唯一的 `profile-model`、`platform-toolchain` 和 `device-adb` 提供方。
- 删除历史目录后，仓库中不存在活跃 FQCN 的重复源码定义。
- Trace Processor 模式调整后，大 trace 查询不会产生功能或性能回退。
- ADB 复用后，在线、离线、未授权和无权限设备状态仍能正确呈现。
