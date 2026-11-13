# Rust 项目 TODO：Agent 化 Android 性能工作流

文档日期：2026-11-13

来源：Android 开发者峰会 2026 文章《新工具，新性能｜助力您更高效地构建高品质 Android 应用》，以及 Android CLI、Android Skills、Android Performance Analyzer、AndroidX Tracing、Compose Recomposition Tracing、R8 Analyzer 和 Android Bench 的官方资料。

本文将外部技术趋势转换为 Rust/GPUI 重写项目的可执行待办。它不表示相关能力已经实现，也不替代 [FUNCTIONAL_PARITY.md](FUNCTIONAL_PARITY.md) 的 Compose 行为对齐、真机、原生 GUI、Provider、打包和发布门禁。

## 目标

将 Rust 项目从“性能数据查看器”逐步演进为证据约束的 Android 性能 Agent 平台：

```text
Tool -> Capture Artifact -> Evidence -> Source Candidate -> Agent Finding -> Re-measurement
```

核心约束：

- Agent 只能调用已注册、带 schema、权限、预算、取消和审计的工具。
- 性能结论必须引用可复核的 Evidence ID；源码位置必须来自确定性 Source Resolver。
- 原始 Artifact、解析结果、AI Finding、报告和 Skill 执行摘要必须保持不同身份。
- 缺失、采样、截断、降级、不可比和未验证状态必须保持为 `UNKNOWN`、`PARTIAL`、`DEGRADED` 或 `UNAVAILABLE`，不能由 Agent 补猜。
- system trace、process-controlled trace、Heap、CPU、GPU、FrameTimeline、Compose 和 R8 证据不能伪装成同一种指标。

## P0：建立 APS Profiler 工具协议

### 1. 受限的性能工具入口

- [ ] 在 Rust 侧定义统一的性能工具协议，优先复用 `aps-protocol`、`aps-contracts` 和现有 `aps-benchmark`/`aps-perfetto` 边界。
- [ ] 规划并逐步实现以下只读或显式授权的工具能力：
  - [ ] `aps profiler capture`
  - [ ] `aps profiler inspect`
  - [ ] `aps profiler query`
  - [ ] `aps profiler compare`
  - [ ] `aps profiler resolve-source`
  - [ ] `aps profiler report`
- [ ] 所有命令使用结构化输入和结构化 JSON 输出，不把 stdout 文本或 Skill 提示词当作稳定分析协议。
- [ ] 工具输入优先使用已登记的 Artifact ID、Session ID、Evidence ID 和 Source Candidate ID，不直接接受任意文件系统路径作为可信身份。
- [ ] 不开放任意 shell、任意 ADB、任意 SQL 或隐式网络访问；设备写操作、源码上传、Provider 网络和脚本执行必须经过独立权限和审批。

### 2. 工具输出的证据元数据

每次工具调用都应能关联以下字段：

```text
toolId
toolVersion
sessionId
artifactId
artifactSha256
deviceTarget
processIdentity
clockDomain
requestedCapabilities
availableCapabilities
coverage
completeness
evidenceIds
sourceCandidateIds
warnings
failureReason
```

- [ ] 区分 `prepared`、`recording`、`stopping`、`artifact-pulled`、`parsed`、`analysis-ready`、`degraded` 和 `failed` 状态。
- [ ] CLI 返回码为成功不能直接等价于 Artifact 完整或分析可用。
- [ ] 采集停止、文件拉取、解析、分析、修复验证分别记录状态和证据。
- [ ] 为取消、超时、断连、部分拉取、解析失败和清理失败保留可恢复状态。

现有基础：

- [基准测试 CLI](../desktop-viewer/benchmark-regression/benchmark-cli/src/main/kotlin/com/androidperformancestudio/benchmark/cli/Main.kt)
- [Rust Perfetto 适配器](crates/aps-perfetto/README.md)
- [Capture Artifact 契约](../desktop-viewer/platform-core/profiler-contracts/src/main/kotlin/com/androidperformancestudio/contracts/CaptureArtifact.kt)

## P0：Agent 和 Skill 接入

- [ ] 实现 Rust 侧 `aps.trace-analysis` Skill manifest 和受限执行器。
- [ ] 实现 Rust 侧 `aps.benchmark-regression` Skill manifest 和受限执行器。
- [ ] Skill manifest 至少声明：输入 schema、输出 schema、上下文类型、Evidence 类型、权限、预算、版本、来源和 Artifact 格式。
- [ ] 保存 `skillId`、`skillVersion`、`skillSource` 和执行摘要；不把 Skill 文本内容当作长期兼容 API。
- [ ] 没有 Agent 或 Skill 时，采集、解析、比较和导出仍可独立完成。
- [ ] 保持 Kotlin AI Platform 和 Rust `aps-ai` 的 manifest、权限、审计、Artifact 和 Finding 语义一致；不能仅因两侧都能调用模型就宣称能力 parity。
- [ ] 在真实 Provider、跨平台 sandbox、打包和权限证据完成前，不把 Rust Agent/Skill 能力标记为完整发布能力。

现有架构约束见 [AI 架构](../docs/ai/architecture.md)。

## P0：Perfetto Skill 和查询边界

- [ ] 将固定的 Perfetto query/preset 暴露为稳定的 Query ID，而不是让 Agent 直接提交任意 SQL。
- [ ] 每个 Query ID 记录 Trace Processor 版本、schema 版本、SQL 身份、返回列、单位、分页限制和覆盖范围。
- [ ] 输出必须包含 trace digest、query ID、schema version、row limit、truncated/omitted 状态和 completeness。
- [ ] 保持当前 pinned Trace Processor、SHA-256 校验、warm session、one-shot fallback、超时、输出上限和显式 degraded 状态。
- [ ] 检查 Kotlin `queryRaw` 与 Rust 固定 query catalog 的边界差异，不能让 Rust 重写无意中扩大为任意 SQL 执行器。
- [ ] 复用仓库中的 Perfetto skill 研究和固定诊断 catalog，但在 APS AI Platform 中重新声明版本、权限和执行边界。

## P1：源代码感知 Trace 证据

### 1. Trace span 结构

- [ ] 为性能 Evidence 增加可选的 Trace span 元数据：

```text
spanId
name
process/thread
start/end
clockDomain
sourceFile
sourceLine
sourceColumn
parameters
coroutineCorrelationId
artifactId
```

- [ ] 区分 system trace、in-process trace 和合并后的派生结果。
- [ ] 记录 system trace 与 process-controlled trace 的时间基准、目标进程、采集范围、合并 manifest、未合并 packet 数和合并完整度。
- [ ] 没有可靠 Clock Mapping 时，不按相近时间戳直接拼接两个 Trace。
- [ ] `sourceFile/sourceLine` 只能作为源码解析输入，不能直接升级为可信源码位置。

### 2. Source Resolver 绑定

- [ ] 将 Trace source metadata 绑定到 Build Evidence、不可变 Source Snapshot、内容 hash 和确定性 Source Candidate。
- [ ] 保持 Analysis Confidence 与 Resolution Confidence 分开。
- [ ] 无法绑定到确定版本、mapping 或源码快照时，显示 unresolved/weak candidate，不由 AI 生成路径或行号。
- [ ] 对 source snippet、路径、参数和生产数据继续使用最小化、显式授权和有界传输策略。

## P1：Compose 重组证据

- [ ] 新增 Compose recomposition evidence adapter，复用 Layout Inspector、FrameTimeline、Perfetto 和已有 Evidence envelope，不新增重复 Profiler。
- [ ] 按 Compose Runtime、runtime-tracing 和目标 API 版本声明 capability，不把实验性/RC 能力标记为稳定支持。
- [ ] 区分以下证据：
  - [ ] 组合跟踪
  - [ ] 重组跟踪
  - [ ] 状态读写因果关系
  - [ ] 错过帧截止时间
  - [ ] 平台卡顿信号
  - [ ] 用户感知延迟
- [ ] 明确诊断采集与真实性能测量采集的不同模式、开销和结论模板。
- [ ] 不把重组次数、skip count 或 state write 单独解释为掉帧根因。
- [ ] 完成 Compose 版本、ABI、动态注入、隐私、断连、清理、设备矩阵和真实目标应用验收。

注意：Rust 项目自身是 Compose Desktop UI；AndroidX Compose runtime tracing 主要服务于被分析的 Android 目标应用，不应驱动 Rust 桌面依赖的无关升级。

## P1：R8 和构建证据

- [ ] 增加 R8 Analyzer JSON/textproto 的受限导入适配器。
- [ ] 将以下信息纳入构建证据包：

```text
applicationId
variant
versionCode/versionName
AGP/R8 version
mapping hash
APK/AAB/AAR/JAR digest
DEX metadata
keep-rule report
seeds/usage/shrinker metadata
native symbol/build-id metadata
```

- [ ] 关联 `mapping.txt`、APK/AAB digest、variant 和被分析 Artifact。
- [ ] 缺少 mapping、variant 或构建身份时保持低置信度或 unresolved。
- [ ] R8 优化结果只形成构建证据，不直接生成启动、CPU、Frame 或内存性能结论。
- [ ] 优先调用官方分析器并解析其结构化结果，不在 APS 内重新实现 R8。
- [ ] 验证当前 AGP 9.4.1 工程实际执行 `analyzeReleaseR8Config` 的任务、输出和失败语义；版本存在不等于功能已经接入。

## P1：Agent 任务评估

借鉴 Android Bench 的通过/完成思路，但与应用性能回归统计分开。

- [ ] 建立 APS Agent Bench 任务集：
  - [ ] 从 Trace 识别主线程阻塞。
  - [ ] 识别 FrameTimeline 错过截止时间。
  - [ ] 将 CPU 热点关联到源码候选。
  - [ ] 判断泄漏嫌疑是否有充分证据。
  - [ ] 比较 Macrobenchmark 前后变化。
  - [ ] 生成带 Evidence ID 的报告。
  - [ ] 对不完整 Trace 保留 `UNKNOWN/PARTIAL`。
- [ ] 记录通过率和完成率。
- [ ] 记录 Evidence 引用正确率、Source Candidate 正确率、虚构路径/行号数、Artifact hash 保持率、取消/断连处理正确率、时间、token 成本和人工修正次数。
- [ ] 使用 `aps-benchmark` 的群组兼容性、样本数量、置信区间和失败时阻断的门禁评估应用性能变化，不用 Agent Bench 分数替代性能指标。
- [ ] 长期目标是同一输入、同一 Artifact、同一 Source Snapshot 下比较不同 Agent/Skill/Provider。

## P2：生产信号

- [ ] 设计独立的 `ProductionSignalArtifact`，不要把 Android Vitals、Crashlytics、ANR、OOM、GWP-ASan、Arm MTE 或生产 Heap Dump 直接塞进 Trace 模型。
- [ ] 第一阶段只支持显式导入的 JSON/结构化文件，不自动读取凭证、不自动上传源码、不默认联网。
- [ ] 至少记录：

```text
signalSource
applicationVersion
device/API cohort
issueType
occurrenceCount
affectedCohort
mapping/build identity
redaction state
source confidence
```

- [ ] 线上信号与本地 Trace 只能通过明确的版本、设备群组、实验身份或用户授权关系关联，不能按时间相近推断为同一事件。
- [ ] 后续再评估显式授权的 Firebase/Vitals Provider、网络权限、脱敏和导出边界。

## P2：真实设备和 CI

- [ ] 先完成本地设备上的 `capture -> artifact -> analysis -> compare` 闭环，再评估远程设备。
- [ ] 补齐 Android CLI 风格的 session 生命周期：`prepared`、`recording`、`stopping`、`artifact-pulled`、`parsed`、`analysis-ready`、`degraded`、`failed`。
- [ ] 对设备断连、进程重启、取消、stop-and-save、文件拉取失败和清理不确定性保留精确恢复路径。
- [ ] 真实 Macrobenchmark、Frame Agent/JankStats、LeakCanary Agent、Native Heap、ART、Bitmap 和 FrameTimeline 仍需单独真机验收。
- [ ] 远程设备、CI 和跨平台主机不能替代本地证据完整度、设备身份和 Artifact hash 验证。

## 暂不采用

- [ ] 不迁移到 Lightbuild；当前属于早期实验，Rust 工作区、Gradle 复合构建和现有发布链路仍是主线。
- [ ] 不复制 Android Studio 的 ACP/BYOA UI；APS 应提供性能工具和证据能力，而不是重新实现 IDE。
- [ ] 不把官方 Skill 文本当作稳定的长期数据协议。
- [ ] 不开放任意 Perfetto SQL、任意命令解释器或隐式设备写操作。
- [ ] 不把重组、卡顿、截止时间错过、Trace 持续时间和 R8 优化结果合并为单一性能分数。
- [ ] 不在真实设备、Provider、sandbox、打包和发布证据不足时宣称 Rust Agent/Skill 能力完成。

## 分阶段验收

### P0 完成条件

- [ ] 至少一个 Trace 工作流和一个基准测试工作流可由结构化工具调用。
- [ ] 工具输出包含 Artifact、Evidence、版本、能力、完整度、限制和失败状态。
- [ ] 无 Agent/Skill 时仍可完成导入、解析、比较和报告导出。
- [ ] 离线 fixture、取消、超时、断连、输入变更和 Artifact hash mismatch 有测试或明确的未验证状态。

### P1 完成条件

- [ ] Trace source metadata 可绑定到确定性 Source Candidate，无法绑定时 fail closed。
- [ ] system trace 与 in-process trace 的来源、时钟和合并状态可审计。
- [ ] Compose 重组只作为诊断证据，并有版本/API/开销标记。
- [ ] R8 结果绑定到构建证据，不伪装成运行时性能结论。
- [ ] APS Agent Bench 至少覆盖 Evidence 引用、源码候选、部分数据和失败恢复。

### P2 完成条件

- [ ] 真实设备和目标应用完成 capture/stop/save/import/analyze/compare 闭环。
- [ ] 生产信号导入有脱敏、版本、mapping、cohort 和来源完整度。
- [ ] Provider、sandbox、跨平台主机、打包、签名和发布资格分别取得独立证据。

## 参考资料

- Android CLI：<https://developer.android.com/tools/agents/android-cli>
- Android Skills：<https://developer.android.com/tools/agents/android-skills>
- Android profiler Skill：<https://github.com/android/skills/tree/main/profilers/android-profiler>
- Android Performance Analyzer：<https://developer.android.com/android-performance-analyzer>
- AndroidX Tracing：<https://developer.android.com/topic/performance/tracing/in-process-tracing>
- Compose tracing：<https://developer.android.com/develop/ui/compose/tooling/tracing>
- AGP 9.3 R8 analyzer：<https://developer.android.com/build/releases/agp-9-3-0-release-notes>
- Android CLI analyze：<https://developer.android.com/tools/agents/android-cli/commands/analyze>
- Android Bench：<https://developer.android.com/bench>
