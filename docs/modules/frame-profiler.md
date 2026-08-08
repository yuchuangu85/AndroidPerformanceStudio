# Frame Profiler

## 功能作用

Frame Profiler 用于采集、导入和分析 Android 帧时序证据：

- 在线优先读取显式安装的 FrameMetrics Agent；Agent 不可用时回退到 `dumpsys gfxinfo <package> framestats`。
- 离线导入 `gfxinfo framestats` 文本。
- 分别报告帧截止时间未命中与平台 Jank 信号，不把两种口径合并为一个 Jank 率。
- 展示 FrameMetrics／framestats 已报告的帧阶段、帧预算、窗口和状态证据。
- 将选中帧与 Layout Inspector 做时间相关，或与显式关联的 Perfetto Trace 做 FrameTimeline 相关。
- 使用 SQLite 保存会话与帧证据，并导出 CSV／JSON 分析结果。

## 采集边界

当前可采集来源只有：

1. **FrameMetrics Agent**：Android 7.0（API 24）及以上，设备端有界缓存并由桌面端按游标读取。JankStats 仅作为独立的平台 Jank 辅助信号。
2. **gfxinfo**：ADB 轮询系统保留的有限 framestats 窗口，不能证明窗口之间没有遗漏。

`FrameSource.PERFETTO` 只是模型枚举；Frame Profiler 不把 Trace 推断成 FrameMetrics 样本。Perfetto FrameTimeline 是应用、SurfaceFlinger 或 GPU 帧归因所需的独立证据。

## 判定语义

- **帧截止时间未命中（Frame Deadline Miss）**：仅当该帧有可靠耗时和预算时，比较 `resolvedDurationNs` 与 `expectedDurationNs`。
- **平台 Jank 信号（Platform Jank Signal）**：保留 Android／JankStats 给出的布尔值、类型和状态，不覆盖截止时间判定。
- 严重度按错过的 VSync 数分级；耗时达到 700 ms 的截止时间未命中帧标记为 `FROZEN`。
- 「最大已报告阶段」只是当前数据源所报告阶段中的最大值，用于确定排查方向，不表示已定位根因。

## 已实现的优化

### 1. gfxinfo 有限窗口与轮询完整度

- 轮询间隔由 1 秒缩短为 250 ms，降低高刷新率下约 120 帧窗口被覆盖的风险。
- 用 5 秒／最多 1,024 个身份的有界水位去重，避免 `seenFrames` 随会话无限增长。
- 当接近完整窗口且与上次轮询没有重叠时，只提示「可能被覆盖」；不伪造精确丢帧数。
- FrameMetrics Agent 仍是高完整度场景的首选来源。

### 2. 旧 framestats schema 的动态帧预算

- 有 `FrameDeadline` 时优先使用逐帧平台 deadline，有 `FrameInterval` 时使用逐帧 interval。
- 只有旧 schema 缺少两者时，才按同一 Window 相邻 VSync 为每帧推断预算。
- 刷新周期发生变化时保留逐帧预算并给出提示，不再用整段数据的单一中位数回填。

### 3. 分离平台 Jank 信号与截止时间未命中

- 摘要分别输出 `deadlineMissRate` 与 `platformJankRate`，各自使用有证据的帧作为分母。
- 单帧、聚类、CSV、JSON 和界面不再让平台信号覆盖 deadline 事实。
- 阶段最大值不再自动生成 `SLOW_*` 平台类型。

### 4. 关联现有 Perfetto Workspace

- API 31 及以上的 Agent 透传 `FrameMetrics.FRAME_TIMELINE_VSYNC_ID`；旧来源保留 intended VSync 时间戳作为次级关联键。
- 用户显式选择本地 Perfetto Trace 后，可把选中帧、VSync ID／时间戳和 Trace 一起打开到现有 Perfetto Workspace。
- 只有 Trace 中存在相应 FrameTimeline 证据时，才允许归因到应用、SurfaceFlinger 或 GPU；当前关联不宣称自动定位根因。

### 5. 按 header 能力解析 framestats

- 解析器按列名而非 Android API Level 硬切 schema。
- 支持同一文件中的多个 Window／`PROFILEDATA` 区段，并保留 Window 身份。
- 未识别的数值列保存为 `gfxinfo.column.<列名>` 状态并告警，不静默丢弃。
- 行宽不匹配或不可解析的行显式计入 warning。

### 6. 会话来源与证据等价持久化

- 在线会话记录设备、API Level、Agent 协议、数据源能力和实际观测到的刷新率证据。
- 离线导入记录绝对路径、SHA-256 和导入时间；文件未明确携带设备来源时标记 provenance 不完整，不从文件名猜测设备。
- SQLite schema v2 保存并恢复会话来源，以及每帧的 source、package、process、平台 Jank 类型、丢失计数、刷新率、FrameTimeline VSync ID 等证据。
- round-trip 测试要求 `FrameCaptureSession` 与 `FrameSample` 保存前后等价；旧表通过增量列迁移保留。

### 7. 收敛帧阶段归因措辞

- 分析、聚类、CSV、JSON 和界面统一使用「最大已报告阶段」。
- 界面仅给出进一步检查 Trace／布局证据的排查方向，不把最长阶段称为瓶颈或根因。
- Layout Inspector 入口仍只打开当前前台布局做时间与复杂度相关，不声称恢复选中帧的历史布局快照。

## 数据流

```text
[FrameMetrics Agent | gfxinfo text]
    -> FrameSample[]
    -> FrameJankAnalyzer
       -> deadline evidence
       -> platform jank evidence
       -> largest reported stage
    -> Compose UI / SQLite v2 / CSV / JSON
    -> optional Layout Inspector or Perfetto correlation
```

## 主要代码位置

- 模型：`desktop-viewer/frame-profiler/frame-model`
- Agent 协议：`desktop-viewer/frame-profiler/frame-agent-protocol`
- Android Agent：`desktop-viewer/layout-inspector/shared-kernel/android-agent-frame`
- 采集：`desktop-viewer/frame-profiler/capture-frame`
- framestats 解析：`desktop-viewer/frame-profiler/parser-frame`
- 分析：`desktop-viewer/frame-profiler/analysis-frame`
- 持久化与导出：`storage-sqlite`、`export-adapters`
- 页面：`presentation`、`frame-app`
