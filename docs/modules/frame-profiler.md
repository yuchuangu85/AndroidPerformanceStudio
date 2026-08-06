# Frame Profiler

## 功能作用

Frame Profiler 是一个 Android 帧性能分析工具，核心功能包括：

- **在线帧数据采集**：通过两种方式实时采集帧数据：
  - **FrameMetrics Agent**：通过设备上的 Agent 回调获取 `FrameMetrics` API 数据，提供完整的帧阶段分解
  - **gfxinfo 轮询**：通过 ADB 执行 `dumpsys gfxinfo <package> framestats` 获取帧统计数据
- **离线导入**：支持导入 `gfxinfo framestats` 导出的文本文件
- **Jank（卡顿）分析**：`FrameJankAnalyzer` 分析每一帧的耗时，判断是否超过预期帧时长（如 16.67ms @ 60Hz），并按 Jank 类型分类
- **帧阶段分解**：支持将每帧分解为 8 个阶段：
  - Input、Animation、Layout/Measure、Draw、Sync、Command、Swap、GPU
- **帧详情展示**：可视化每一帧的耗时柱状图，标注 Jank 帧，支持选中单帧查看详情
- **布局反向关联**：选中单帧后可跳转到 Layout Inspector 查看对应时刻的布局（`FrameLayoutInspectionRequest`）
- **多种数据源**：支持 `JankStats`、`FrameMetrics`、`gfxinfo`、`Perfetto` 四种数据源

## 实现原理

### 采集流程

1. **设备选择 → 进程枚举**：通过 ADB 列出可调试进程
2. **打开采集会话**：
   - `FrameMetricsAgentCaptureSession`：通过 ADB 端口转发 + Socket 与设备上的 Agent 通信，Agent 在 App 进程内监听 `FrameMetrics` 回调
   - `GfxInfoPollingCaptureSession`：循环执行 `dumpsys gfxinfo <package> framestats`，解析输出中的帧数据行
3. **轮询**：以 `POLL_INTERVAL_MILLIS`（1 秒）间隔轮询新帧数据
4. **停止并分析**：停止采集后，将所有帧数据传给 `FrameJankAnalyzer` 进行分析

### 数据分析

- **Jank 判定**：`FrameJankAnalyzer` 对每帧计算 `resolvedDurationNs()`（实际耗时），与 `expectedDurationNs`（基于刷新率的预期时长）对比：
  - 超过预期：标记为 `DEADLINE_MISSED`
  - 超过预期 2x：标记为严重 Jank
- **平台 Jank 分类**：依赖数据源的能力（`FrameSourceCapabilities`），FrameMetrics 可提供 `platformJankTypes`（如 SLOW_INPUT、SLOW_DRAW 等）
- **帧阶段贡献**：`FrameStages` 记录各阶段耗时（单位纳秒），用于定位性能瓶颈在哪个阶段

### 数据结构

- **FrameSample**：单帧数据，包含 frameId、source、packageName、intendedVsync/actualVsync/present 时间戳、expectedDurationNs、stages（各阶段耗时）、platformJankTypes、droppedBeforeSample（丢失帧数）
- **FrameCaptureSession**：采集会话元数据，包含 id、source、startedAt、packageName
- **FrameSourceCapabilities**：数据源能力描述（是否支持实时采集、阶段分解、Jank 分类、预期帧截止时间、App 状态标签）

### 数据流

```
[Android Device] --FrameMetrics Agent/ADB gfxinfo--> [OnlineFrameCapture]
    --> [FrameSample[]] --> [FrameJankAnalyzer] --> [FrameAnalysisResult]
    --> [Compose UI: FrameProfilerScreen]
```

### Export

- **CSV**：帧分析结果 CSV 导出
- **JSON**：帧分析结果 JSON 导出
- **FrameStats 导入**：支持从 `dumpsys gfxinfo framestats` 输出文本导入

## 优化建议与改进点

> 以下内容不替换已有设计，仅作为可考虑的更好实现方式或优化点补充。每条标注 **影响**（高/中/低）与 **可行性**（高/中/低）。

### 1. gfxinfo 轮询的丢帧风险【影响:高 / 可行性:中】

**当前实现问题**：`GfxInfoPollingCaptureSession` 以 `POLL_INTERVAL_MILLIS`（1 秒）循环执行 `dumpsys gfxinfo <package> framestats`。`gfxinfo framestats` 只保留最近 ~120 帧的窗口，刷新率越高窗口越短；轮询间隔与窗口不匹配时连续 Jank 帧会被覆盖丢失。

**更好的实现方式**：
- 把轮询间隔从固定 1 秒改为 **根据刷新率自适应**：高刷新率设备（90/120/144Hz）窗口更短，应把轮询缩短到 250-500ms，并在配置中暴露 `maxFrameWindow` 与 `pollIntervalMillis` 的关系公式。
- 文档显式说明 gfxinfo 路径的"采样窗口"局限，并对"丢帧计数 (`droppedBeforeSample`)"做累计而非每窗口覆盖。
- 对精度要求高的场景，主推 **FrameMetrics Agent 路径**（设备端 push，不依赖窗口轮询），gfxinfo 仅作为 Agent 不可用时的降级方案。

### 2. 预期帧时长（expectedDurationNs）的来源【影响:高 / 可行性:高】

**当前实现问题**：`expectedDurationNs` 基于"刷新率"推导（如 16.67ms @ 60Hz），但未说明刷新率如何获取。设备可能在不同场景动态切换刷新率（LTPO 屏幕、省电模式、游戏模式），固定 16.67ms 会导致高刷场景误报 Jank、低刷场景漏报。

**更好的实现方式**：
- 通过 `dumpsys display` / `SurfaceFlinger` 获取当前实时刷新率（`--display` / `latency`），或在 FrameMetrics Agent 端读取 `Display.getRefreshRate()` / `DisplayInfo`，把每帧对应的刷新率作为 `FrameSample.refreshRateHz` 记录。
- `expectedDurationNs` 改为 **逐帧动态计算**（按该帧时刻的刷新率），而非会话级常量。
- 支持多刷新率场景的"分桶 Jank 判定"：60Hz 帧与 120Hz 帧分别用各自阈值。

### 3. Jank 阈值的硬编码 2x【影响:中 / 可行性:高】

**当前实现问题**：严重 Jank 判定为"超过预期 2x"，阈值写死。不同业务对严重程度容忍度不同（游戏 vs IM）。

**更好的实现方式**：
- 把 `severeJankMultiplier`（默认 2.0）作为可配置项放入 `FrameExperimentConfig` 或分析策略对象。
- 提供"自定义 Jank 分级"：如 mild（>1.2x）/ moderate（>1.5x）/ severe（>2.0x）/ frozen（>3.0x 或缺失 vsync），便于按业务调优。

### 4. vsync 与 Choreographer 统计的缺失【影响:中 / 可行性:中】

**当前实现问题**：`FrameSample` 有 `intendedVsync`/`actualVsync`/`present` 时间戳，但未提及 vsync 偏移（vsync offset）、Choreographer 丢帧、SurfaceFlinger 帧合成的统计。这些对定位"是 App 渲染慢还是 SF 合成慢"至关重要。

**更好的实现方式**：
- 在 FrameMetrics 路径下透传 `FrameMetrics` 的完整阶段（含 `intendedVsync`、`vsyncMin`、`oldestInputEventNs`、`frameIntervalNs`、`wallStartVsync` 等），保留 vsync 信息用于绘制 timeline。
- 考虑集成 **Perfetto frame timeline / SurfaceFlinger tracks**：`view_faults` / `gpu_frame_missed` / `sf_frame_missed` 等 counter 能区分"App 丢帧 vs SF 丢帧 vs GPU 丢帧"，是对 FrameMetrics 的有力补充。

### 5. framestats 解析的已知局限【影响:中 / 可行性:高】

**当前实现问题**：`gfxinfo framestats` 输出的字段（Frame 状态标志位 + 各阶段 ns）解析依赖 Android 版本；不同 Android 版本字段数量与含义有差异（如 Android 11+ 增加了 `DealY` / GPU 阶段细化），且 Compose 下的阶段语义与 View 下不同。

**更好的实现方式**：
- 在解析器中显式按 **Android API Level 分版本适配**，并记录 `parsedFromApiLevel`，对未知字段不静默丢弃而是标记 `unknownStages`。
- 文档补充 framestats 各 flag 位含义（如 `FLAG_UNDESIRED_DURATION`、`FRAME_PIPELINE` 等）的映射表，便于排查。
- 对 Compose 场景，建议优先走 FrameMetrics 路径（gfxinfo 的阶段分解对 Compose 失真）。

### 6. 在线采集的会话级元数据【影响:低 / 可行性:高】

**当前实现问题**：`FrameCaptureSession` 含 id/source/startedAt/packageName，但缺少设备刷新率、屏幕分辨率、GPU 型号、是否启用 HWUI AOT/编译模式等会影响帧性能的环境变量。

**更好的实现方式**：
- 扩展会话元数据：`displayInfo`（resolution、refreshRate、density）、`gpuRenderer`、`hwuiConfig`（GLSL/Vulkan pipeline）、`cpuFreqPolicy`。
- 这些变量用于跨会话对比时做"环境可比性"检查（类比 benchmark-regression 的兼容性检查），避免不同设备/编译模式的帧数据被错误对比。

### 7. 帧阶段贡献的可视化与归因【影响:低 / 可行性:中】

**当前实现问题**：`FrameStages` 记录各阶段耗时，但未说明如何把"某阶段慢"映射到可操作的优化建议（如 Draw 慢 → 过度绘制、Sync 慢 → 阻塞主线程）。

**更好的实现方式**：
- 提供内置的"阶段→可能根因"映射建议表（如 Layout/Measure 慢 → 提示检查 `flatten` / 嵌套层级；Command 慢 → 提示检查 DisplayList；GPU 慢 → 提示检查 shader/纹理）。
- 与 Layout Inspector 联动已有 `FrameLayoutInspectionRequest`，可进一步增加"自动跳转到嫌疑布局节点"的启发式（如某帧 Layout 阶段异常 → 自动选中该时刻布局树中 depth 最大的节点）。

### 8. 离线导入的来源指纹【影响:低 / 可行性:高】

**当前实现问题**：离线导入 framestats 文本时未记录来源（哪台设备、哪个 Android 版本、何时导出），后续难以追溯。

**更好的实现方式**：
- 导入时允许用户填写/自动从文件名/内容推断 `sourceDevice`、`sourceApiLevel`、`exportedAt`，存入 `FrameCaptureSession`。
- 对内容缺失设备信息的导入文件，标记 `attributionIncomplete=true` 并在 UI 提示"此会话缺少设备上下文，对比结果仅供参考"。
