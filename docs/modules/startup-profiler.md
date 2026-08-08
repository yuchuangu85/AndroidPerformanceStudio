# Startup Profiler

## 功能边界

Startup Profiler 是桌面端 Android 启动证据采集与分析工具。它通过 ADB 准备启动状态、执行目标 Activity，并合并平台输出、Event Log 与可用时的应用内 Agent 里程碑。

当前能力：

- 按请求启动模式执行 Cold、Warm、Hot 实验，并记录独立的观测启动模式。
- 分别保存平台 `TotalTime`、初始显示时间（TTID）和完整显示时间（TTFD）；缺失 TTFD 时不回退为 TTID。
- 对可调试应用读取 Agent 生命周期/首帧里程碑；Agent 不可用时降级为平台证据。
- 支持 `CURRENT`、`RESET`、`VERIFY`、`SPEED_PROFILE`、`SPEED` 编译请求。
- 记录实验前后 compiler filter/Profile 原因、设备环境和可选的逐运行 Perfetto Trace。
- 计算 min、max、median、mean、P90、P95、标准差和 MAD；对低分辨率尾部分位数和 MAD 异常样本做标注但不删样本。
- 仅对可比运行组执行 TTID 两样本 BCa 中位数差判断，并展示可用的同域阶段差异。
- 通过 SQLite 保存会话和运行记录，支持 JSON 导入/导出与 CSV 导出。

“上一轮结果”只有在设备、目标、请求与观测启动模式、启动编译状态、指标语义和环境证据均可比时，才能作为回归 Baseline；当前界面保存上一轮结果不等于已经建立有效回归结论。

## 当前实现

### 采集流程

1. `DesktopStartupBackend` 通过 `cmd package query-activities` 枚举 Launcher Activity。
2. `StartupExperimentRunner` 在 Host 端执行 ADB 命令；Agent 不负责 `am start` 或 `force-stop`。
3. 非 `CURRENT` 编译请求在用户确认后通过 `cmd package compile` 准备目标包；运行前后用 `dumpsys package` 记录并验证 compiler filter 与 Profile 原因。
4. 每次运行按请求模式准备状态：
   - **Cold**：`am force-stop` 后重新启动，要求创建新进程。
   - **Warm**：保留进程，清理既有 Task 后创建新 Activity。
   - **Hot**：保留进程和 Activity，从后台恢复。
5. 运行后结合平台 `LaunchState`、启动前后 PID 和 Agent Activity 事件形成 `observedType`；与请求不符时产生警告。
6. `AmStartOutputParser`、Event Log 和 Agent 分别提供平台指标、TTID/TTFD 与诊断里程碑。

Cold、Warm、Hot 的定义以 AndroidX Macrobenchmark [`StartupMode`](https://developer.android.com/reference/androidx/benchmark/macro/StartupMode) 为基线。

### 启动指标

- **TTID**：系统从启动请求到目标窗口首帧完成显示的时间。
- **TTFD**：从启动请求到应用调用 `reportFullyDrawn()`、声明主要内容可用的时间。
- **Agent First Frame**：应用内诊断里程碑，不替代平台 TTID。
- **TotalTime/WaitTime/ThisTime**：保留 `am start -W` 的原始平台口径，不与 TTID/TTFD 合并成单一“启动耗时”。

Android 无法自动知道应用何时完整可交互；未调用 `reportFullyDrawn()` 时 TTFD 必须保持缺失。参见[官方启动指标说明](https://developer.android.com/topic/performance/vitals/launch-time)。

### 数据结构

- **StartupExperimentConfig**：请求启动模式、编译请求、可审计的 Profile 来源声明、仅用于 `SPEED_PROFILE` 的编译预热次数、测量次数、超时、实用变化阈值和可选 Trace 开关。
- **StartupRun**：请求/观测启动模式、平台指标、Agent 里程碑、阶段、警告、PID、指标来源、编译状态、环境、Trace 和原始证据。
- **StartupMilestone / StartupPhase**：带来源与置信度的诊断边界及其可计算区间。
- **StartupStatistics / StartupComparison**：前者保存描述性统计和尾部分辨率状态，后者保存可比性、业务阈值与 TTID 两样本 BCa 区间。

## 实施结果

- TTID、TTFD 与 Agent First Frame 的来源和缺失原因已追加到 UI、JSON、CSV 与 SQLite，UI/JSON 同时保留置信度；TTFD 缺失仍保持缺失并显示 `reportFullyDrawn` 集成提示。
- 非 `CURRENT` 编译模式增加持久修改确认；实验记录请求、命令输出、前后 compiler filter/Profile 原因与验证结果。`speed-profile` 还必须显式声明由 Baseline Profile Plugin、Macrobenchmark 或已准备构建变体提供；未声明来源的样本不会进入 A/B 回归组，工具不根据 `dumpsys` 原因猜测来源。
- 每次 measured run 记录型号、API、模拟器、电量/充电、Thermal Status、时间和读取失败；模拟器、低电量、严重热状态或缺失环境证据会阻止回归结论。
- P90/P95 低分辨率和 MAD 异常值只做诊断标记；原始样本不删除。上一轮实验仅在运行组可比时执行可配置的实用变化阈值（默认 5%）与 95% BCa TTID 中位数差判断。
- Perfetto 采集为显式可选项，每次 measured run 保存独立 Trace 文件以及失败/超时截断状态。Android App Startups 派生指标仍需后续接入 Trace Processor；当前不伪造缺失阶段。
- Warm/Hot 启动模式预置继续由每次运行的状态准备完成；`warmupRuns` 只在 `SPEED_PROFILE` 下作为编译预热，其他编译模式会忽略并告警。

## 优化项审计

审计基线为 Android 官方启动语义、AndroidX Macrobenchmark 的公开稳定行为和当前代码。**部分保留**只留下仍需实现且可验证的内容；错误前提和重复能力不进入实施范围。

| # | 结论 | 审计结果 |
|---|---|---|
| 1 | 部分保留 | TTID、TTFD 和 Agent 首帧已经分开；保留证据来源/缺失状态展示和 `reportFullyDrawn` 集成提示。 |
| 2 | 排除 | 当前没有对 Warm/Hot 执行 `force-stop`，且“Activity 重建即 Hot”违反官方定义；请求/观测类型自检已经存在。 |
| 3 | 部分保留 | 保留非 `CURRENT` 操作警告、实际编译状态验证和确定性准备；排除无法无损实现的历史状态恢复与编译沙箱。 |
| 4 | 部分保留 | 保留官方 Profile 产物状态验证和可比 A/B；排除 Host 端自研 Profile 注入及根据 warm runs 生成 Startup Profile。 |
| 5 | 部分保留 | 保留环境证据与运行门禁；排除修改亮度、省电模式、CPU/GPU 频率和跨设备自定义温度阈值。 |
| 6 | 部分保留 | 保留小样本/尾部分辨率提示、异常样本标注和两样本不确定性；排除固定“30 次稳定 P95”与自动删除异常值。 |
| 7 | 部分保留 | 现有里程碑和阶段模型继续使用；只新增可选的逐运行 Perfetto 证据及可信阶段比较。 |
| 8 | 部分保留 | 分离启动模式预置与编译预热；排除统一 3–5 次预热、强制 `drop_caches` 和相似度自检。 |

## 保留的优化项

### 1. 指标证据与缺失状态

- UI 和导出中明确标出 TTID、TTFD、Agent First Frame 的来源与可用性。
- 缺少 `reportFullyDrawn()` 时显示 TTFD 不可用，不生成估算值。
- 提供 `ComponentActivity.reportFullyDrawn`、`FullyDrawnReporter` 以及 Compose `ReportDrawn*` 的集成提示，不修改目标应用代码。

### 3. 启动编译状态验证

- 对所有非 `CURRENT` 请求显示会持久修改目标包编译产物的确认提示。
- 记录请求命令、执行结果、实验前后可观察到的 compiler filter 与 Profile 状态。
- 编译准备失败或实际状态与请求不符时，运行不得进入对应的可比运行组。
- 建议在专用物理测试设备上运行；不承诺从 `pm dump` 信息恢复此前 Profile 和编译产物。

Macrobenchmark 的公开流程同样是重置、编译、测量，而不是恢复未知历史状态。参见 [`CompilationMode`](https://developer.android.com/reference/kotlin/androidx/benchmark/macro/CompilationMode)。

### 4. 官方 Profile 产物的 A/B 验证

- 检测并记录目标构建是否实际处于 `speed-profile` 以及 Profile 的可验证来源。
- A/B 比较只接受由 Baseline Profile Gradle Plugin、Macrobenchmark 或明确构建变体准备的样本。
- Baseline Profile 属于设备端 ART 预编译输入；Startup Profile 属于构建期 DEX 布局输入，两者分别呈现。
- 不实现不稳定的 ADB 自定义 Profile 注入，也不把运行时热点列表导出成 Startup Profile。

参见[Baseline Profile 与 Startup Profile 的区别](https://developer.android.com/topic/performance/baselineprofiles/overview)及[官方 A/B 测量方式](https://developer.android.com/topic/performance/baselineprofiles/measure-baselineprofile)。

### 5. 启动环境证据与门禁

每个运行至少记录：

- 设备型号、序列化身份、API Level 和是否模拟器。
- 电量/充电状态与平台 Thermal Status。
- 采集时间及环境读取失败原因。

低电量、模拟器或热节流状态产生明确警告；需要回归判断时，将其排除出不兼容的可比运行组。工具不静默修改用户设备设置，也不锁定 CPU/GPU 时钟。Android 官方同样不建议为启动等用户体验测试锁频，参见[性能测量原则](https://developer.android.com/topic/performance/measuring-performance)。

### 6. 可比性与统计不确定性

- P90/P95 继续作为描述性结果；样本不足以分辨目标分位数时标记低分辨率，不规定任意的统一次数。
- MAD 只用于标注需检查的运行，原始样本始终保留且默认参与统计。
- Baseline 比较先验证可比运行组，再结合业务变化阈值和两样本中位数差的 BCa 置信区间。
- 区间跨越决策边界时结果为“不确定”，不把单一百分比变化显示为回归结论。

统计门禁复用 [ADR-0027](../adr/0027-gate-benchmark-regressions-with-two-sample-uncertainty.md)，不新增另一套规则。

### 7. Perfetto 启动阶段证据

- 每个 measured run 可选保存独立 Perfetto trace，并保留采集失败或截断信息。
- 使用 Android App Startups 派生指标补充平台阶段；现有 Agent `milestones/phases` 继续作为应用内证据。
- 只有处于同一时钟域或已有可靠时钟映射的阶段才能组合和比较。
- 阶段缺失时保持缺失，不按固定模板补齐 `Application.onCreate`、Activity 或首帧边界。

这与 Macrobenchmark 每次测量保存独立系统 Trace 的公开行为一致，参见[官方 Macrobenchmark 工作流](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)。

### 8. 分离启动模式预置与编译预热

- 用 **启动模式预置** 建立 Warm/Hot 所需进程和 Activity 状态，该运行不进入统计。
- 用 **编译预热** 形成明确的 JIT/Profile 状态；次数属于编译策略而不是启动模式配置。
- Cold 默认不进行通用启动预热。页缓存或 Shader 缓存策略必须单独声明能力、执行结果和失败原因。
- 继续通过观测启动模式验证每个 measured run，不用耗时相似度猜测预置是否成功。

## 兼容性约束

实施遵循以下兼容约束：

- 新环境、编译状态和 Trace 字段均以可选字段追加；JSON 保持 schema version 1，旧 JSON 可读取，SQLite 通过可空列迁移旧库。
- 现有 `StartupRun`、`milestones`、`phases` 语义不重命名；新增证据不得改变既有指标口径。
- 不可比或缺失证据通过显式状态表示，不用默认值伪造成功。
