# Startup Profiler

## 功能作用

Startup Profiler 是一个 Android 应用启动时间测量与分析工具，核心功能包括：

- **启动时间实验**：通过 ADB 反复启动/停止目标 Activity，精确测量启动耗时
- **启动类型支持**：支持 Cold（冷启动）、Warm（温启动）、Hot（热启动）三种启动类型
- **编译模式覆盖**：支持调整编译模式以模拟不同场景：
  - `CURRENT`：保持当前编译状态
  - `RESET`：重置编译配置（模拟首次安装）
  - `VERIFY`：verify 模式
  - `SPEED_PROFILE`：speed-profile 编译
  - `SPEED`：speed 编译（全量 AOT）
- **实验参数可配**：Warm-up runs（预热次数）、Measured runs（测量次数）、Timeout（超时时间）均可配置
- **统计分析**：`StartupAnalyzer` 对多次测量结果进行统计计算（最小值、最大值、中位数、均值、P90、P95、标准差、MAD）
- **基线对比**：每次实验保留上次结果作为 baseline，支持回归检测
- **结果持久化**：通过 `SqliteStartupSessionStore` 保存实验会话和运行数据到 SQLite 数据库
- **导入/导出**：支持 JSON 格式的导入导出，CSV 格式导出

## 实现原理

### 采集流程

1. **设备选择 → 目标枚举**：通过 ADB `cmd package resolve-activity` 列出可启动的 Activity
2. **Agent 通信**：`SocketStartupAgentConnection` 通过 ADB 端口转发 + Socket 与设备上的 Agent 通信：
   - Agent 控制 App 进程的启动和停止（`am start` / `am force-stop`）
   - Agent 接收 App 内通过 `reportFullyDrawn()` 或首帧绘制完成的信号
3. **实验流程**：
   - 准备阶段：确保设备唤醒、设置编译模式（如需要）
   - Warm-up 阶段：执行配置次数的预热启动（不计入统计）
   - Measured 阶段：执行配置次数的测量启动，每次记录 Displayed Time 或 Fully Drawn Time
4. **超时处理**：每次启动有超时限制（可配 10-120 秒），超时则标记失败

### 数据分析

- `StartupAnalyzer` 对测量的启动时间进行统计：
  - 基础统计：count、min、max、median、mean
  - 百分位数：P90、P95
  - 离散度：standardDeviation、medianAbsoluteDeviation
- 识别异常值（outliers）
- 与 baseline 对比检测回归

### 数据结构

- **StartupRun**：单次运行结果，包含 id、type（COLD/WARM/HOT）、durationMs、compilationMode、startedAt、completedAt
- **StartupSession**：实验会话，包含 deviceSerial、packageName、componentName、config
- **StartupExperimentConfig**：实验配置，包含 requestedType、compilationMode、warmupRuns、measuredRuns、timeoutSeconds
- **StartupAnalysis**：分析结果，包含 runs、statistics、baseline、warnings

### 数据流

```
[Android Device] --ADB Socket--> [Agent] --Start/Stop App--> [StartupExperimentRunner]
    --> [StartupRun[]] --> [StartupAnalyzer] --> [StartupAnalysis]
    --> [SqliteStartupSessionStore] (持久化)
    --> [Compose UI: StartupProfilerScreen]
```

### 启动时间指标

- **Cold Start**：进程从零创建，包含 Application 初始化 + 首 Activity 创建
- **Warm Start**：进程存在但 Activity 被销毁，Activity 重建
- **Hot Start**：进程和 Activity 都在内存中，仅重新 onResume

### Compilation Mode 影响

不同的编译模式影响 ART 编译器的行为：
- **SPEED**：全 AOT 编译，启动最快但安装/OTA 慢
- **SPEED_PROFILE**：基于 Profile 的部分编译，兼顾安装速度和启动速度
- **RESET**：清除编译结果，模拟首次安装后的 JIT 解释执行

## 优化建议与改进点

> 以下内容不替换已有设计，仅作为可考虑的更好实现方式或优化点补充。每条标注 **影响**（高/中/低）与 **可行性**（高/中/低）。

### 1. reportFullyDrawn() 依赖的脆弱性【影响:高 / 可行性:中】

**当前实现问题**：Agent 接收 App 内通过 `reportFullyDrawn()` 或"首帧绘制完成"的信号作为结束时间点。但多数 App **未调用 `reportFullyDrawn()`**（需业务代码主动调用），退化为"首帧"——而首帧可能是空白启动屏，不代表用户可交互时间。两者语义差异巨大，混用会污染测量。

**更好的实现方式**：
- 明确区分 **三个时刻**：`displayedTime`（`am` 的 ActivityManager.reportedTime，即系统认定"显示完成"）、`firstFrameTime`（首帧绘制）、`fullyDrawnTime`（业务主动 `reportFullyDrawn()`）。在 `StartupRun` 中记录三者并标注实际采用的时间点 `measuredMarker`。
- 文档说明"未调用 `reportFullyDrawn()` 时退化为 displayedTime"，避免用户误以为测的是 fully drawn。
- 提供集成向导：建议 App 在关键启动节点调用 `reportFullyDrawn()`（或 `androidx.core.performance.addFullyDrawnBeforeCallback`），并在工具中展示"App 是否调用了 fully drawn"，帮助用户识别测量口径差异。

### 2. Hot Start 与 force-stop 的语义矛盾【影响:高 / 可行性:高】

**当前实现问题**：Hot Start 定义为"进程和 Activity 都在内存中，仅重新 onResume"，但采集流程用 `am force-stop` 控制停止。`am force-stop` 会**杀死进程**，杀死后的启动是 Cold Start 而非 Hot Start。当前的 Hot Start 实现路径不清晰，可能存在语义错配。

**更好的实现方式**：
- 明确三种启动类型的停止方式：
  - **Cold**：`am force-stop` 后 `am start`（进程从零创建）。
  - **Warm**：退出 Activity 但保留进程（`am finish-com` 或 Activity.finish()），再 `am start`。
  - **Hot**：仅触发 Activity 重建（如 `am start -f 0x10000000` Activity Only flag 或配置变更模拟），进程常驻。
- 文档显式标注三种类型各自使用的停止/启动命令，避免实现时一律用 `force-stop` 导致 Warm/Hot 实际测的是 Cold。
- 提供"类型自检"：每次 run 后校验 `processStartTime`（从 `/proc/<pid>/stat` 或 `ActivityManager.ProcessMemoryState`），若进程被重启而声称是 Hot，标记 `typeMismatch=true`。

### 3. 编译模式的破坏性与恢复【影响:中 / 可行性:高】

**当前实现问题**：`RESET`/`SPEED`/`SPEED_PROFILE` 等编译模式通过 `cmd dexopt` / `pm compile` 修改设备编译状态，是**破坏性操作**——会改变设备的全局编译缓存，影响后续其他基准测试，且不自动恢复。

**更好的实现方式**：
- 实验"准备阶段"记录 **原始编译状态快照**（如 `pm dump <pkg>` 的 compiler filter），实验结束后自动恢复到原始状态，并在文档强调"实验会修改设备编译缓存，结束后自动恢复"。
- 对 `RESET`（尤其破坏性强）在 UI 显示明确警告，要求用户二次确认。
- 提供"沙箱式编译模式"选项：通过 `pm compile --reset <pkg>` 在子实验维度隔离，避免 warm-up 与 measured 阶段编译状态互相污染（warm-up 本身会让 JIT 跑起来，污染"冷启动"测量）。

### 4. Baseline Profile / Startup Profile 的集成【影响:中 / 可行性:中】

**当前实现问题**：编译模式覆盖了 `CURRENT/RESET/VERIFY/SPEED_PROFILE/SPEED`，但未集成现代 Android 启动优化的核心手段：**Baseline Profile**（`baseline-prof.txt`，已随 APK 分发）与 **Startup Profile**。没有 Baseline Profile 的 SPEED_PROFILE 与有 Profile 的 SPEED_PROFILE 启动差异显著，缺少这一维度对比就少了一个关键优化杠杆。

**更好的实现方式**：
- 在 `StartupExperimentConfig` 增加 `baselineProfileMode`（`INSTALLED` / `DISABLED` / `CUSTOM`），支持安装/禁用 APK 自带 Profile，或注入自定义 Profile（`pm compile -m speed-profile --`, `class-profile-file`）。
- 提供 **A/B 对比预设**：`WITHOUT_PROFILE` vs `WITH_PROFILE`，量化 Baseline Profile 带来的启动收益——这是向团队推荐采用 Profile 的核心证据。
- 支持导出 `StartupProfile`（基于多次 warm run 的热点方法）作为 Profile 生成输入，形成"测量 → 生成 Profile → 再测量"的闭环。

### 5. 设备温度与频率控制【影响:中 / 可行性:高】

**当前实现问题**：准备阶段"确保设备唤醒"，但未控制 CPU/GPU 频率、温度、省电模式。多次连续启动会触发设备发热，CPU 降频，后期 run 比前期 run 慢，污染统计（系统性偏差而非随机噪声）。

**更好的实现方式**：
- 准备阶段额外控制：固定屏幕亮度、关闭省电模式（`settings put global lowpower 0`）、可选固定 CPU 频率（root 设备：`scaling_max_freq`/`scaling_min_freq`）。
- 每次 run 后记录 **设备温度** 与 **CPU 频率快照**，对频率下降/温度超阈值的 run 标记 `thermalThrottled=true`，在统计中单独标注。
- 在多次 run 之间插入温度回落检测（`sleepUntilTempBelow`），避免热累积系统性偏差。

### 6. 样本量与统计稳健性【影响:中 / 可行性:高】

**当前实现问题**：`StartupAnalyzer` 输出 min/max/median/mean/P90/P95/stddev/MAD，但未说明推荐样本量与样本量不足时的处理。启动时间受系统 GC、磁盘 IO 影响方差大，10-20 次测量未必足够。

**更好的实现方式**：
- 文档给出 **推荐样本量**（如 ≥30 次以获得稳定 P95），并在样本量 < 阈值时对 P90/P95 标注 `lowConfidence=true`。
- 提供 **异常值检测**：用 MAD（已有）做异常剔除（`|x - median| > 3*MAD` 的 run 标记为 outlier 并可选排除），输出剔除前后的统计对比，而非直接用 raw 样本。
- 对比 baseline 时用 **bootstrap 重采样估计 CI**，避免小样本下 t-检验失真。

### 7. 启动子阶段分解【影响:中 / 可行性:中】

**当前实现问题**：`StartupRun` 只记录总 `durationMs`，不分解启动内部阶段（Application init、ContentProvider、Activity onCreate/Start/Resume、首帧）。只知道"启动慢"但不知慢在哪个阶段。

**更好的实现方式**：
- 通过 Perfetto `activity` track 与 `am` 输出，分解启动为子阶段：`processFork`、`applicationOnCreate`、`activityCreate`、`activityStart`、`activityResume`、`firstFrame`。在 `StartupRun` 增加 `stageBreakdown`。
- 子阶段数据让优化有的放矢：是 Application 初始化慢（考虑延迟初始化）还是 Activity 创建慢（考虑布局精简）。
- 对比 baseline 时支持子阶段级 diff，自动定位"哪个阶段变慢了"。

### 8. Warm-up 次数的合理性默认【影响:低 / 可行性:高】

**当前实现问题**：Warm-up runs 可配，但默认值未说明。Warm-up 不足时 JIT 未充分预热、Class 未加载完毕，measured 阶段测的其实是"半冷启动"。

**更好的实现方式**：
- 文档说明默认 Warm-up 次数（建议 3-5 次）与"为什么需要"（预热 JIT、填充磁盘缓存、稳定设备状态）。
- 对 Cold Start 场景，**Warm-up 本身会让"冷启动"变"温启动"**（进程被杀但 OS 缓存部分保留），建议 Cold Start 测量时每次 run 之间执行更彻底的清理（`drop_caches` 或 `killAll` 后等待磁盘缓存失效），并记录 warm-up 是否真正生效（验证进程确实被重新创建）。
- 提供"Warm-up 有效性自检"：若 warm-up 后第一次 measured 与无 warm-up 时相似，提示"warm-up 可能未生效"。
