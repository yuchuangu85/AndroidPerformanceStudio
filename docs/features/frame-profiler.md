# Frame Profiler

## 功能介绍

Frame Profiler 用于采集或导入 `gfxinfo` FrameStats，识别帧时长、卡顿区间和连续 jank cluster，并支持选中帧后跳转 Layout Inspector 做布局关联。

## 详细设计方案

```text
device gfxinfo / imported FrameStats
  -> parser-frame
  -> frame model
  -> duration + jank analysis
  -> timeline / clusters / frame detail
  -> CSV/JSON and Layout Inspector handoff
```

### 模块职责

- `frame-profiler/capture-frame`：设备采集、ADB 调用和取消。
- `frame-profiler/parser-frame`：FrameStats 文本解析和兼容字段处理。
- `frame-profiler/frame-model`：帧、区间、cluster、session 和证据模型。
- `frame-profiler/analysis-frame`：帧时长、jank、cluster 和异常规则。
- `frame-profiler/frame-agent-protocol`：可选设备端高保真 Agent 协议。
- `frame-profiler/presentation` / `frame-app`：时间线、概览、详情和跨工具跳转。

## 实现方案

Frame Profiler 将原始 gfxinfo/Agent 数据转成稳定的 frame model，再由分析层计算 deadline miss、duration bucket 和 cluster。UI 不直接解析 shell 输出，导出层保留原始与派生证据。

## FrameTimeline / Jank attribution

帧模型保留 `activityName`、Fragment、页面、交互状态、`windowId`、`states`、RenderThread、platform jank type、JankStats、FrameTimeline vsync ID 和 SurfaceFlinger correlation。`FrameAttributionAnalyzer` 会按 Activity、window 和 UI state 聚合：

- 总帧数；
- deadline miss；
- platform jank；
- p95/worst duration；
- jank type 集合。

该聚合结果在 Frame Profiler 页面和 JSON 导出中保留，适合把 Jank 与页面状态关联；它不把单帧卡顿直接归因到某一行代码。

## 使用方案

1. 选择设备和目标进程，开始采集或导入 FrameStats。
2. 查看总帧数、慢帧、丢帧区间和时间线。
3. 选择异常帧，查看 start/duration/原因和上下文。
4. 通过 Layout Inspector 关联同一时段的 View 层级或截图。
5. 导出 CSV/JSON 报告。

## 输出与限制

- gfxinfo 是平台帧统计，不等同于完整 SurfaceFlinger/FrameTimeline 因果链。
- 屏幕刷新率、设备负载和采集窗口会影响阈值解释。
- 更深层的调度、RenderThread、GPU、SurfaceFlinger 证据应通过 Trace Analyzer 关联。

## FrameTimeline 与 JankStats 联合证据

`FrameJankStatsCorrelator` 依次按 frame ID、vsync ID 和有界时间窗口，把应用侧 JankStats observation 与平台 FrameTimeline sample 关联。结果保留 Activity、Fragment、页面、交互状态、RenderThread、SurfaceFlinger jank type、JankStats reason/rule 身份及匹配方式。JSON 汇总分别报告 platform jank 与 JankStats rate，避免把两种证据通道伪装成同一指标。
