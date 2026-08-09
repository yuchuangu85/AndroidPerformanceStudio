# Android Performance Studio Profiler 架构复核与调整建议

> 日期：2026-08-09  
> 状态：已经架构访谈确认并形成 ADR  
> 输入线索：[ChatGPT 分享页：开源 Android Studio Profiler](https://chatgpt.com/share/6a77975c-aff4-83ea-99a5-2b40550676df)  
> 范围：Profiler 的采集、设备、数据处理和跨功能会话架构；不把分享页当作一手事实来源

## 1. 结论

**当前方案需要调整，但不需要推倒重写，也不应直接移植 Android Studio 的 `profilers-ui`，更不应现在就全面引入 `perfd/perfa`。**

分享页提出的主方向——Compose Desktop 自建 UI，优先复用 Perfetto、Trace Processor、simpleperf、heapprofd、ART/HPROF 和 ADB——与当前工程总体方向一致。当前代码已经具备其中大部分采集和分析能力。

真正需要调整的是公共底座和依赖方向：

1. 将 Perfetto 从一个独立“功能页面”提升为跨功能的共享数据引擎；
2. 完成 `platform-adb` 和 Host Process/Toolchain 的公共化，停止其他 Profiler 反向依赖 `simpleperf-viewer`；
3. 现在就建立最小的产物、来源、时间域和能力元数据契约，但继续推迟统一时间线 UI；
4. FrameTimeline、heapprofd、Java heap graph 等 Perfetto 数据优先交给 Trace Processor，而不是继续扩展自研 protobuf wire parser；
5. 只在官方采集源不能满足产品语义时保留自研 Agent，例如 HTTP 请求明细、应用启动自定义里程碑、低版本实时 FrameMetrics 和 Layout/Compose Inspection。

推荐目标不是：

```text
Fork Android Studio / 搬入 profilers-ui / 全量部署 perfd-perfa
```

而是：

```text
Feature UI + Feature Model
            │
            ├── Shared Capture/Artifact Contracts
            ├── Shared Perfetto + Trace Processor Platform
            ├── Shared ADB + Host Toolchain Platform
            └── simpleperf / HPROF / ART / Agent feature adapters
```

## 2. 研究方法与证据等级

本结论将信息分为三类：

| 等级 | 用途 |
|---|---|
| 官方一手来源 | AOSP Gitiles 固定提交、Perfetto 官方文档、Android Developers 官方文档，用于确认外部事实 |
| 当前仓库源码 | 用于判断项目已经实现什么、依赖关系和缺口 |
| 分享页 | 只作为调查线索和架构观点，不作为事实证明 |

复核时固定了以下 AOSP 提交，避免把会移动的分支状态写成永久事实：

| 仓库 | 分支快照 | 提交 |
|---|---|---|
| `platform/tools/base` | `mirror-goog-studio-main` | [`b977c298d35231c5ec08f68a182acec5b6b97303`](https://android.googlesource.com/platform/tools/base/+/b977c298d35231c5ec08f68a182acec5b6b97303/) |
| `platform/tools/adt/idea` | `mirror-goog-studio-main` | [`6eae3ba5b05fe88d8d62da31d23b930a5fc6a133`](https://android.googlesource.com/platform/tools/adt/idea/+/6eae3ba5b05fe88d8d62da31d23b930a5fc6a133/) |
| `platform/system/extras` | `main` | [`fc2494a2abd7ab21774d03deb09c1362bbb0bba8`](https://android.googlesource.com/platform/system/extras/+/fc2494a2abd7ab21774d03deb09c1362bbb0bba8/) |

## 3. 分享页信息的官方复核

### 3.1 已核验：Android Studio Profiler 的源码确实跨多个工程

固定提交中的 `tools/base` 确实包含：

- [`profiler/native`](https://android.googlesource.com/platform/tools/base/+/b977c298d35231c5ec08f68a182acec5b6b97303/profiler/native/)
  - [`agent`](https://android.googlesource.com/platform/tools/base/+/b977c298d35231c5ec08f68a182acec5b6b97303/profiler/native/agent/)
  - [`perfa`](https://android.googlesource.com/platform/tools/base/+/b977c298d35231c5ec08f68a182acec5b6b97303/profiler/native/perfa/)
  - [`perfd`](https://android.googlesource.com/platform/tools/base/+/b977c298d35231c5ec08f68a182acec5b6b97303/profiler/native/perfd/)
  - [`trace_processor_daemon`](https://android.googlesource.com/platform/tools/base/+/b977c298d35231c5ec08f68a182acec5b6b97303/profiler/native/trace_processor_daemon/)
- [`transport`](https://android.googlesource.com/platform/tools/base/+/b977c298d35231c5ec08f68a182acec5b6b97303/transport/)
- [`transport/proto`](https://android.googlesource.com/platform/tools/base/+/b977c298d35231c5ec08f68a182acec5b6b97303/transport/proto/)

固定提交中的 `tools/adt/idea` 也确实包含：

- [`profilers`](https://android.googlesource.com/platform/tools/adt/idea/+/6eae3ba5b05fe88d8d62da31d23b930a5fc6a133/profilers/)
- [`profilers-ui`](https://android.googlesource.com/platform/tools/adt/idea/+/6eae3ba5b05fe88d8d62da31d23b930a5fc6a133/profilers-ui/)
- [`profilers-android`](https://android.googlesource.com/platform/tools/adt/idea/+/6eae3ba5b05fe88d8d62da31d23b930a5fc6a133/profilers-android/)

因此，“没有一个可直接添加为 Gradle 依赖的完整独立 SDK，而是多个模块构成的技术栈”这一判断成立。

### 3.2 已核验：不应直接搬 `profilers-ui`

当前固定提交的 [`profilers-ui/BUILD`](https://android.googlesource.com/platform/tools/adt/idea/+/6eae3ba5b05fe88d8d62da31d23b930a5fc6a133/profilers-ui/BUILD) 明确依赖：

- `@intellij//:intellij-sdk`
- `@intellij//:com.intellij.java`
- `adt-ui`、`adt-ui-compose`、`inspectors-common` 等 Studio 模块

这直接证明 `profilers-ui` 不是一个与 IntelliJ 无关的 Compose Desktop UI 库。当前项目继续自建 Compose Desktop Presentation 层是正确方向。

### 3.3 已核验：Perfetto 可以作为共享数据与 SQL 分析平台

Perfetto 官方文档将 Trace Processor 定义为从 trace 构造表并执行 SQL/PerfettoSQL 的分析组件：

- [Trace Processor (C++)](https://perfetto.dev/docs/analysis/trace-processor)
- [Trace analysis overview](https://perfetto.dev/docs/analysis/getting-started)
- [PerfettoSQL standard library](https://perfetto.dev/docs/analysis/stdlib-docs)

CPU Profiling 官方文档还说明 `linux.perf` 使用 `perf_event_open`，调用栈采样可以和 ftrace 等其他数据源出现在同一条 trace 时间线上：

- [Recording performance counters and CPU profiling with Perfetto](https://perfetto.dev/docs/getting-started/cpu-profiling)

所以“不要复制 Perfetto Web UI，而应复用 Trace Processor 和 SQL，再映射到自己的 Kotlin 模型”是有一手来源支撑的架构建议。

### 3.4 已核验：heapprofd 和 ART heap dump 已有标准 Trace Processor 表

Perfetto 官方 Native Heap 文档明确列出：

- 调用栈表：`stack_profile_mapping`、`stack_profile_frame`、`stack_profile_callsite`
- 分配表：`heap_profile_allocation`

来源：[Memory: Callstack-based Allocation Profiling](https://perfetto.dev/docs/data-sources/native-heap-profiler)

ART Heap Dump 官方文档明确列出：

- `heap_graph_class`
- `heap_graph_object`
- `heap_graph_reference`

来源：[Memory: ART Heap Dumps for Java/Kotlin Heap](https://perfetto.dev/docs/data-sources/java-heap-profiler)

这意味着项目在分析 Perfetto native heap 或 `java_hprof` trace 时，应优先复用 Trace Processor 已完成的序列状态、interning、symbolization 和 schema 处理。

这不表示传统 HPROF 文件不再需要解析。`am dumpheap` 生成的 HPROF、Android Studio 式 Classifier/Instance/Reference 分析仍然需要当前 HPROF 模型和分析器。

### 3.5 已核验：FrameTimeline 应成为 Android 12+ 的首选帧证据

Perfetto 官方文档明确说明：

- FrameTimeline 需要 Android 12（S）或更高版本；
- 它由 SurfaceFlinger 检测 jank，并提供 Expected/Actual Timeline。

来源：[Android Jank detection with FrameTimeline](https://perfetto.dev/docs/data-sources/frametimeline)

因此 Frame Profiler 的有界 Capture/Import 在 Android 12+ 应优先使用 Perfetto FrameTimeline；`FrameMetrics` 和 `gfxinfo framestats` 仍有合理用途：

- Android 7.0–11 的兼容路径；
- 实时低开销观察；
- 应用内额外阶段/状态标签；
- Perfetto 不可用时的显式降级。

### 3.6 已核验：simpleperf 是应继续复用的官方采集工具

Android 官方提供 [Simpleperf NDK 文档](https://developer.android.com/ndk/guides/simpleperf)。当前 AOSP 固定提交还包含：

- [`simpleperf`](https://android.googlesource.com/platform/system/extras/+/fc2494a2abd7ab21774d03deb09c1362bbb0bba8/simpleperf/)
- [`cmd_report_sample.cpp`](https://android.googlesource.com/platform/system/extras/+/fc2494a2abd7ab21774d03deb09c1362bbb0bba8/simpleperf/cmd_report_sample.cpp)
- [`cmd_report_sample.proto`](https://android.googlesource.com/platform/system/extras/+/fc2494a2abd7ab21774d03deb09c1362bbb0bba8/simpleperf/cmd_report_sample.proto)
- [`view_the_profile.md`](https://android.googlesource.com/platform/system/extras/+/fc2494a2abd7ab21774d03deb09c1362bbb0bba8/simpleperf/doc/view_the_profile.md)

当前项目继续以 simpleperf 负责专业 CPU Sampling 是正确的。是否把 simpleperf 数据转换/合并进统一 Perfetto 时间线应作为 adapter 能力，而不是废弃现有 simpleperf parser 和报告模型。

### 3.7 已核验：Standalone Profiler 不能作为本项目的跨平台产品底座

Android Developers 当前页面仍说明可以通过 `profiler.exe` 或 `profiler.sh` 启动 Standalone Profiler，同时明确写明 macOS 不支持：

- [Run the standalone profiler](https://developer.android.com/studio/profile/standalone-profiler)

它适合用作架构参考和差分测试对象，但不适合直接作为当前 macOS/Windows/Linux Compose Desktop 产品的运行时依赖。

### 3.8 分享页中需要谨慎或修正的内容

分享页不是官方文档，其中至少有以下问题：

1. 它列出的 `perfd` 子目录包含 `energy`、`network`、`statsd`。在本次固定的 `mirror-goog-studio-main` 提交中，`perfd` 目录实际包含 `commands`、`common`、`cpu`、`event`、`graphics`、`memory`、`profileable`、`samplers`、`sessions`，没有前述三个目录。不能把历史分支或搜索摘要当成当前结构。
2. “某年某月开始弃用某种录制方式”等时间敏感陈述没有必要成为本项目的架构前提，除非进一步锁定官方提交或发布说明。
3. `report-sample` 源码存在已经核验，但“任何输出都可直接作为 Android Studio 某版本的 trace 打开”仍需针对具体 Studio 版本做格式兼容测试。
4. “不要重新实现采集器”应理解为默认原则，而不是绝对规则。Android Studio 的某些 Inspector/Agent 能力不是稳定公共 SDK，产品若需要应用层语义仍可能需要受控的自研 adapter。

## 4. 当前工程与推荐方向的匹配度

### 4.1 已经正确，不应重写

| 领域 | 当前实现 | 判断 |
|---|---|---|
| CPU Sampling | `simpleperf-viewer` 的 capture/parser/model/analysis/UI | 保留；符合官方 simpleperf 路线 |
| Method Recording | ART trace capture/parser | 保留；与 Sampling 分开建模正确 |
| System Activities | `perfetto-viewer` capture/Trace Processor/UI | 保留并下沉为共享能力 |
| Java Heap | `am dumpheap` + HPROF parser + Classifier/Instance/Reference 分析 | 保留；是 Android Studio Class List 对等所需能力 |
| Native Heap | heapprofd/Perfetto capture | 采集保留，解析方式需要调整 |
| Startup | `am start`、事件、可选 Agent、可选 Perfetto trace | 多证据策略保留，增加 Trace Processor 分析 |
| Battery | batterystats snapshot/diff + raw evidence | 保留；Perfetto power rail 不能替代 UID 归因语义 |
| Network | OkHttp instrumentation/HAR | 保留；Perfetto 不能直接提供完整 HTTP header/body/phase 语义 |
| Layout/Compose Inspection | View/Accessibility/AOSP Inspector Agent 路径 | 与 Profiler 数据栈分离，不因分享页而改变 |

### 4.2 现有代码已经证明需要共享平台层

仓库原先在 [ADR-0003](../../docs/adr/0003-modular-architecture-no-unified-timeline.md) 和 [CONTEXT.md](../../CONTEXT.md) 中决定“当前不为统一 Session 建抽象”；另一方面已经出现：

- `CanonicalProfileRecord`
- `ProfileSourceId`
- `ProfileClockDomain`
- `ProfileTimePoint(errorBoundNanos)`
- 跨模块共享的 `StudioResult`

这些类型位于 `simpleperf-viewer/profile-model`，而不是中立公共模块。结果是：

- `memory-profiler/settings.gradle.kts` 为复用 Host Toolchain 和 ADB，`includeBuild("../simpleperf-viewer")`；
- `perfetto-viewer/settings.gradle.kts` 同样 `includeBuild("../simpleperf-viewer")`；
- Frame、Startup、Battery、Memory 等模块依赖 `device-adb`、`platform-toolchain` 和 `profile-model` 的 Simpleperf 坐标。

这已经不是未来的投机设计，而是当前真实依赖。因此 [ADR-0006](../adr/0006-capture-artifact-contract-before-profiler-session.md) 已取代 ADR-0003：

> 继续推迟统一时间线产品 UI和统一业务数据模型；现在建立稳定、最小、与功能无关的产物/来源/时间域/设备契约。

不能继续把“暂不做统一 UI”解释成“公共元数据也不抽取”。

### 4.3 `platform-adb` 迁移尚未完成

原 [ADR-0004](../../docs/adr/0004-shared-device-session-layer.md) 要求跨 Profiler 的 DeviceSession 薄层。访谈后，[ADR-0007](../adr/0007-stateless-device-target-and-shared-adb.md) 以无状态 Device Target 和共享 ADB 原语取代该抽象。仓库已经创建 `platform-adb/adb-core`，但当前仍存在：

- `simpleperf-viewer/device-adb` 承担事实上的共享设备发现和目标选择；
- `simpleperf-viewer/platform-toolchain` 被多个其他 Profiler 依赖；
- Network capture 仍有自己的 `ProcessAdbCommandRunner`；
- Layout、Perfetto、Frame、Startup、Battery、Memory 各有业务级 runner 包装。

业务 adapter 可以存在，但可执行文件定位、超时/取消、文本与二进制输出、设备选择、push/pull/forward、错误分类不应重复实现。

### 4.4 Perfetto 目前仍是“一个功能”，还不是共享数据引擎

`perfetto-viewer` 已有捕获、工具定位、Trace Processor、SQL Diagnostics 和 UI，但：

- Memory 的 native heap 不能复用它；
- Frame 没有可见的 Perfetto FrameTimeline parser/capture source；
- Startup 只保存可选 Perfetto trace，主要分析仍来自 `am start`/event/Agent；
- Trace Processor query 结果主要是原始文本，不是可跨模块消费的类型化结果；
- Trace Processor 生命周期、动态端口和并发还没有成为公共服务。

因此项目虽然“有 Perfetto Viewer”，但尚未达到分享页建议的“Perfetto 作为 Data Engine”。

### 4.5 Native Heap 的自研 wire parser 应降级为 fallback

`NativeHeapTraceParser.kt` 自身将实现描述为：

> Best-effort summary reader ... Reads the protobuf wire format directly

并明确承认 raw trace + Perfetto 才是完整 sequence-state、symbolization、call tree 和 guardrail diagnostics 的权威来源。

这与官方已经提供 `heap_profile_allocation`、`stack_profile_*` 表的事实相结合，说明默认路径应改成：

```text
heapprofd trace
    -> pinned Trace Processor
    -> typed SQL rows
    -> NativeHeapAnalysis
```

现有 wire parser 可以暂时保留为“Trace Processor 不可用”的显式降级路径，但 UI 必须显示来源和能力缺失，且要用同一批 trace 做差分测试。

### 4.6 Frame Profiler 的 `PERFETTO` 枚举尚未形成完整路径

`FrameSource` 已包含 `PERFETTO`，`FrameCaptureSession` 也包含 `perfettoTraceFile`；但当前 `parser-frame` 只有 `GfxInfoFrameStatsParser`，在线 capture 主要是 Agent `FrameMetrics` 和 `gfxinfo`。

这说明领域模型已经预留正确方向，但缺少：

- Android 12+ FrameTimeline capture config；
- `android.frames.timeline` / per-frame metrics 的 typed SQL adapter；
- Expected/Actual Timeline、jank type、SurfaceFlinger/app correlation 映射；
- API/设备能力驱动的 source selection。

### 4.7 Perfetto 子工程存在需要清理的双轨模块

`perfetto-viewer/settings.gradle.kts` 当前包含：

- `perfetto-capture`
- `perfetto-trace-processor`
- `perfetto-analysis`
- `perfetto-storage`
- `perfetto-presentation`
- `perfetto-export`

仓库中同时还跟踪了名称不同但包含相同包名/类名的目录：

- `capture-perfetto`
- `trace-processor-bridge`
- `trace-analysis`
- `storage-perfetto`
- `presentation`
- `export-adapters`

这些旧目录当前没有被该 settings 文件包含，属于遗留/双轨实现候选。调整共享 Perfetto 平台前，应先完成实现对比、迁移遗漏检查和安全删除，避免未来误接线。

## 5. 调整后的目标架构

```text
┌──────────────────────────────────────────────────────────────┐
│                       desktop-app                            │
│  路由、窗口、统一设置；不承载采集或解析业务                 │
├──────────────────────────────────────────────────────────────┤
│ Feature Applications / Presentations                         │
│ CPU | Memory | Frame | Startup | Battery | Network | Layout  │
│ 每个功能保留自己的强类型模型、分析规则和 Compose UI          │
├──────────────────────────────────────────────────────────────┤
│ platform-core / profiler-contracts                           │
│ CaptureArtifact | Provenance | Capability | Completeness     │
│ Device/Process identity | TimeDomain | ClockMapping          │
│ 只定义跨功能 envelope，不统一所有业务 record                 │
├───────────────────────┬──────────────────────────────────────┤
│ platform-perfetto     │ platform-cpu / platform-memory       │
│ capture config        │ simpleperf tool/report adapter       │
│ tool locator/verify   │ HPROF artifact adapter               │
│ Trace Processor       │ ART method recording adapter         │
│ typed SQL query API   │                                      │
├───────────────────────┴──────────────────────────────────────┤
│ platform-core / host-toolchain / adb-core                     │
│ discovery | target | command | push/pull | forward | cancel  │
├──────────────────────────────────────────────────────────────┤
│ Android official sources + bounded feature Agents            │
│ Perfetto | simpleperf | heapprofd | ART/HPROF | dumpsys      │
└──────────────────────────────────────────────────────────────┘
```

### 5.1 公共契约应保持“小而稳定”

建议公共层只包含：

```kotlin
CaptureArtifact(
    id,
    kind,
    path,
    sha256,
    contractVersion,
    format,
    formatVersion,
    provenance,
    capturedAt,
    device,
    process,
    clockDomains,
    clockMappings,
    requestedCapabilities,
    availableCapabilities,
    completeness,
    limitations,
    warnings,
)
```

`path` 只是存放位置，不属于身份；Capability 使用功能所有的命名空间 ID，Completeness 只相对本次请求的能力判定。Import 记录来源行为，不伪装成原始 producer。契约同时定义版本化 JSON envelope，但不规定 sidecar 位置或引入共享数据库。

不要把 `HeapObject`、`HttpCall`、`FrameSample`、`BatterySnapshot` 等强行塞进一个通用 record 树。跨功能层解决身份、时间和来源；各功能层继续拥有业务语义。

### 5.2 AOSP Transport 不应立刻成为内部总线

`tools/base/transport/proto` 很值得作为兼容性和协议设计参考，但现在直接采用会引入：

- Android Studio 私有演进节奏；
- perfd/perfa 生命周期耦合；
- 大量当前产品未使用的服务和消息；
- 与现有文件导入优先架构不一致的 live transport 模型。

只有在 Live Telemetry、Java/Kotlin allocation tracking 或 JVMTI 能力无法通过现有公开工具满足时，再做单独的 AOSP Transport/perfd/perfa PoC，并明确兼容版本、设备支持、许可证、签名和升级策略。

## 6. 分阶段调整方案

### P0：修正依赖方向和公共契约

1. 将现有 `platform-adb` composite 提升并重命名为 `platform-core`，初始只包含 `profiler-contracts`、`host-toolchain` 和 `adb-core`。
2. 将 `ProfileSourceId`、时间域和误差界限等通用概念从 `simpleperf-viewer/profile-model` 移出；Simpleperf 专用 Frame/Sample/Metadata 继续留在原模块。
3. 将其他 Profiler 对 `simpleperf-viewer` 的依赖替换为对中立公共模块的依赖。
4. 执行 ADR-0007：统一 Host Process 执行以及 ADB executable、device discovery、target catalog、command、push/pull/forward、timeout/cancel/error semantics，不建 `DeviceSession`。
5. 执行 ADR-0006：现在建立 Capture Artifact envelope，继续推迟 Profiler Session、统一时间线 UI 和统一业务模型。

验收条件：

- Memory/Perfetto/Frame/Startup/Battery 不再因为 ADB 或通用结果类型 `includeBuild("../simpleperf-viewer")`；
- 业务模块没有直接启动 `adb` 的 `ProcessBuilder`；
- Capture 和 Import 形成的 Artifact 都会产生一致的 provenance/capability/completeness 元数据；Live Observation 在停止、快照或导出后才形成 Artifact。

### P1：提取共享 Perfetto 平台

1. 合并并清理 `perfetto-viewer` 双轨模块，只保留一套实现。
2. 提取可被其他 composite build 消费的独立、单 JVM module `platform-perfetto`：
   - pinned `trace_processor` locator + SHA-256；
   - capture config builder；
   - trace lifecycle；
   - typed query API；
   - SQL module/version compatibility checks；
   - artifact-scoped `TraceAnalysisContext`、动态端口、并发和取消。
3. Trace Processor 输出不直接泄漏为 UI 字符串；由 adapter 转为强类型 DTO。
4. 将 Perfetto Web UI 保留为“查看原始 trace/高级探索”入口，自研 UI消费 typed query。

验收条件：

- 同一个 trace 可被 Frame、Memory、Startup 分析 adapter 复用；
- 一套 Trace Processor 版本和定位策略；
- 每个 SQL adapter 都有固定 trace fixture 和 schema 兼容测试。

### P2：迁移高收益数据源

#### Native Memory

```text
heapprofd -> Trace Processor SQL -> NativeHeapAnalysis
```

- 使用 `heap_profile_allocation` + `stack_profile_*`；
- 仅在 Trace Processor 不可用或版本不兼容时降级到 wire parser，并标记 `PARTIAL`、fallback 原因和缺失能力；trace 损坏或 SQL 失败不静默降级；
- 增加 symbolization、allocation/deallocation、sequence loss 差分测试。

#### Frame

```text
有界 Capture/Import + Android 12+ -> Perfetto FrameTimeline（权威证据）
Live Observation                  -> FrameMetrics Agent
Live Fallback                     -> gfxinfo framestats
```

- UI 明确显示 source/capabilities；
- 不把不同来源可见字段伪装成完全等价；
- 把 FrameTimeline 的 app/SurfaceFlinger/jank classification 映射到现有 `FrameSample`。

#### Startup

- `am start` 继续提供 platform reported timing；
- Agent 继续提供应用自定义里程碑；
- Perfetto 提供 sched、binder、main thread、frame 等根因证据；
- 三类证据通过统一 time mapping 关联，不互相替代。

#### Java Heap

- 传统 HPROF 继续用于 Android Studio Classifier/Instance parity；
- Perfetto `java_hprof` 作为另一种输入 adapter；
- 两种输入最终映射到同一 Memory presentation 能力集，但必须保留“不支持/不可证明”字段，而不是补造数值。

### P3：建立可聚合但不强制统一 UI 的 Session Bundle

建立一个版本化 `.aps-session` manifest，可引用多个不可变产物：

```text
session.json
artifacts/
  system.perfetto-trace
  cpu.simpleperf
  heap.hprof
  network.events
  screenshots/
```

manifest 至少记录：

- artifact SHA-256、producer/tool version；
- device/build/process/package identity；
- monotonic/boottime/wall-clock mapping；
- capture start/end 和 error bound；
- capability、completeness、fallback reason；
- privacy/redaction policy。

这一步只解决“数据以后能聚合”，不要求现在就做 Android Studio 式单窗口统一时间线。

### P4：按能力缺口评估 `perfd/perfa`

仅在以下需求进入实现阶段时启动 PoC：

- 连续 Live Telemetry 的低开销、统一传输；
- Java/Kotlin allocation 逐次记账；
- 需要 JVMTI/ART 注入才能得到的数据；
- 多数据源严格同步且现有 CLI 工具无法满足。

PoC 必须先回答：

- 支持哪些 Android/API/ABI/build type；
- 如何构建、签名、分发和校验 device binary；
- 与 Android Studio/AOSP commit 的版本锁定；
- 失败时如何降级；
- 是否真的优于继续使用 Perfetto/simpleperf/ART 公共入口。

## 7. 对 Compose Inspector 方案的影响

**本分享页讨论的是 Profiler 技术栈，不是 Compose Inspector 协议，因此没有提供证据推翻当前 Compose 方案。**

当前最佳 Compose 方案仍应是：

```text
CI 从固定 AOSP commit 构建官方 UI Inspector Agent Bundle
    +
按目标 Compose 版本解析官方 inspector.jar
    +
桌面发行包内置/验证/缓存
    +
FULL -> SEMANTICS_ONLY -> VIEW_ONLY 显式降级
```

可以共享本次建议中的通用设施：

- signed/hash-verified tool artifact cache；
- ADB push/pull/forward/attach 能力；
- producer/version/provenance manifest；
- capability/fallback UI contract。

但不应把 Compose Inspector Agent 塞进 `perfd/perfa`，也不能用 Perfetto、UIAutomator 或原生 View dump 冒充完整 Composable tree。

## 8. 不建议实施的方案

1. **直接 Fork/搬入 `profilers-ui`**：已经由官方 BUILD 证明与 IntelliJ SDK 深度耦合。
2. **现在全面引入 `perfd/perfa`**：会把尚未需要的 live transport/JVMTI 复杂度提前带入。
3. **把所有领域模型统一成一张通用事件表**：会丢失 Heap、HTTP、Frame、Battery 的强类型语义。
4. **继续让公共层位于 `simpleperf-viewer`**：依赖方向错误，模块名和职责不一致。
5. **继续手写 Perfetto protobuf wire parser 作为默认路径**：无法经济地覆盖官方 Trace Processor 已处理的序列状态和符号化。
6. **以 Perfetto 替代所有 Agent**：应用层 HTTP 语义、启动自定义里程碑和 Layout/Compose Inspector 仍需要专用采集器。
7. **立刻做统一时间线 UI**：公共元数据和数据引擎应先稳定；UI聚合可以继续后置。

## 9. 最终决策建议

架构访谈已形成三份 ADR：

1. [ADR-0006：先建立 Capture Artifact 契约，后置 Profiler Session](../adr/0006-capture-artifact-contract-before-profiler-session.md)  
   取代 ADR-0003，只延后跨产物 Session、统一业务模型和聚合 UI。
2. [ADR-0007：使用无状态 Device Target 和共享 ADB 原语](../adr/0007-stateless-device-target-and-shared-adb.md)  
   取代 ADR-0004，收口 Host Process、ADB 与 Toolchain，不建立跨功能 `DeviceSession`。
3. [ADR-0008：Perfetto 作为共享 trace 数据引擎](../adr/0008-perfetto-as-shared-trace-engine.md)  
   规定 capture、Trace Processor、typed query 边界、版本和工具校验的唯一归属。

一句话总结：

> **保留当前各 Profiler 的强类型模块和 Compose Desktop UI，把 ADB、工具链、产物元数据与 Perfetto/Trace Processor 下沉成真正的共享平台；暂不搬 Android Studio UI，也暂不全面引入 perfd/perfa。**
