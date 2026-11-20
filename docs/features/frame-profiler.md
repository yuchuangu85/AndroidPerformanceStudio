# Frame Profiler

## 功能定位

Frame Profiler 用于回答“哪些帧超过了预算、卡顿集中在哪些区间、页面/窗口/交互状态是否相关”。当前 Rust 实现支持 `gfxinfo FrameStats`、可选 FrameMetrics/JankStats Agent 和 Perfetto FrameTimeline 三类来源；它们会保留来源差异，不把缺失的 platform jank 猜成应用根因。

当前状态：

- **已接入**：gfxinfo 导入/轮询、帧时长、deadline miss、p50/p95/p99、问题帧、连续 jank cluster、详情、过滤、CSV/JSON 导出和 Layout/Trace 关联入口。
- **条件能力**：FrameMetrics/JankStats Agent、FrameTimeline 和 SurfaceFlinger 关联依赖设备、目标 App、Agent、Android 版本与 Perfetto 数据源。
- **未完成验收**：完整真机 Agent/JankStats/FrameTimeline 矩阵、连续采集和所有原生 GUI/Release 门禁仍需单独验证。

## 功能点

- 解析 `dumpsys gfxinfo <package> framestats` 的 Window、PROFILEDATA、现代列名、尾部分隔符和未知字段。
- 计算帧开始/结束、总耗时、预期帧预算、deadline miss、严重程度和连续问题帧 cluster。
- 展示帧时间线、摘要卡片、慢帧列表、cluster、单帧详情和页面/窗口/Activity/UI state 归因。
- 支持阶段耗时、RenderThread、platform jank type、JankStats reason/rule、FrameTimeline vsync ID 和 SurfaceFlinger correlation（字段可用时）。
- 选中帧后关联 Layout Inspector，必要时打开对应 Perfetto/Trace 证据。
- 提供 `aps-frame-mcp` 只读工具：`frame_session_summary`、`frame_list`、`frame_detail`、`frame_problem_clusters`、`frame_attributions`、`frame_trace_overlaps`、`frame_session_compare`。
- 导出报告时保留来源 digest、窗口/帧身份、分析策略、截断和缺失状态。

## 原理与数据流

```text
gfxinfo / FrameMetrics Agent / FrameTimeline trace / imported report
  -> source-specific parser
  -> unified FrameSample / FrameSession
  -> duration + deadline + cluster analysis
  -> attribution / bounded overlap / comparison
  -> GUI, JSON/CSV, CLI/MCP and cross-tool handoff
```

当前 Rust 入口：

- `rust-desktop/crates/aps-app/src/frame`：采集、会话、详情、导出、Trace/Probe 关联和页面。
- `rust-desktop/crates/aps-frame`：gfxinfo/Frame model、分析、归因和稳定性边界。
- `rust-desktop/crates/aps-frame-mcp`：绑定输入源的只读 MCP。
- `rust-desktop/crates/aps-perfetto`：FrameTimeline 查询和 Trace 证据适配。

`gfxinfo` 只提供平台报告的帧统计；它不提供可信的 platform jank 标签，因此 deadline miss 不会被自动写成 SurfaceFlinger 原因。FrameTimeline、JankStats 和应用阶段属于独立证据通道，关联时按 frame ID、vsync ID 或有界时间窗口建立候选关系。

## 使用方法

1. 选择设备和目标进程，或导入 FrameStats/JSON/Frame archive。
2. 采集或等待解析完成，先检查来源标签、样本数量和丢失/截断警告。
3. 在摘要中查看 p50/p95/p99、慢帧比例和 cluster；不要只看单个最慢帧。
4. 进入时间线，按窗口、Activity、页面、交互状态、jank 类型或时间范围过滤。
5. 打开单帧详情查看阶段、vsync、RenderThread、JankStats 和可用的 Trace overlap。
6. 通过 Layout Inspector 查看同一时段布局证据；通过 Trace Analyzer 查询调度、Binder、GPU 或 SurfaceFlinger。
7. 导出 CSV/JSON 或使用 MCP 对已验证输入做只读分析。

## 输出与证据边界

- `duration` 是帧窗口的观测耗时，不是某一方法的完整耗时。
- deadline miss 是基于帧预算的分类，不等于平台 jank，也不等于用户一定看到了卡顿。
- platform jank、JankStats、RenderThread 和 FrameTimeline 可能来自不同通道，JSON 会分别保留分母和匹配方式。
- 时间重叠只能形成候选关联，不能证明某个 Binder、布局节点或方法造成了卡顿。
- 空的 platform jank、缺失的 TTFD/阶段或未匹配的 vsync 必须保持 unknown/unavailable。

## 故障排查与限制

- 没有帧：检查目标包名、gfxinfo 权限、采集窗口和 App 是否有实际绘制负载。
- 只有 gfxinfo：说明 Agent/FrameTimeline 条件未满足，不要把兼容结果描述为完整平台因果链。
- cluster 异常：检查 refresh rate、时间窗口、帧率切换和是否混入多个窗口/会话。
- Trace overlap 为空：检查 trace 是否含 FrameTimeline、时间基准映射、window/surface identity 和源 digest。
- MCP/CLI 只读取已存在且摘要绑定的输入，不启动设备采集；真实设备和 Release 验收参考功能对等审计。
