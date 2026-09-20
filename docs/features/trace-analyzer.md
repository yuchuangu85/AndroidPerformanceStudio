# Trace Analyzer

## 功能介绍

Trace Analyzer 负责 Perfetto 系统 Trace 的采集、导入、查询、可视化和证据导出。它适合分析 CPU 调度、Binder/IPC、图形渲染、应用生命周期、线程阻塞和跨进程时间线。

## 详细设计方案

```text
capture config
  -> Perfetto capture
  -> raw .perfetto-trace
  -> pinned Trace Processor
  -> typed SQL adapters
  -> analysis model / diagnostics
  -> native or embedded viewer / exports
```

### 模块职责

- `perfetto-viewer/perfetto-capture`：设备采集、配置、取消和原始 trace 管理。
- `perfetto-viewer/perfetto-model`：采集配置、session、query result 和 evidence model。
- `perfetto-viewer/perfetto-trace-processor`：Trace Processor 进程与 schema 能力。
- `perfetto-viewer/perfetto-analysis`：调度、Binder、graphics 和 startup 查询。
- `perfetto-viewer/perfetto-storage`：最近会话和原始文件索引。
- `perfetto-viewer/perfetto-presentation` / `perfetto-app`：工作区 UI、诊断和外部 Perfetto UI 入口。
- `platform-perfetto`：版本固定、工具校验、共享 capture/processor 边界。

## 实现方案

Trace Analyzer 保留原始 trace 作为最高层证据，typed SQL adapter 只暴露业务需要的字段。外部 Perfetto Web UI 用于高级原始探索；自研 UI 负责状态、可复核摘要、诊断和跨工具入口，不复制第三方浏览器前端。

## 内置诊断查询

当前诊断菜单复用统一 `PerfettoDiagnostics` 查询目录，包含：

- CPU scheduling hotspots；
- CPU frequency distribution；
- CPU contention 与 wakeup latency；
- Binder transaction latency；
- Binder server saturation；
- Binder wait chain；
- Frame jank；
- Frame/SurfaceFlinger correlation；
- thread state、input latency 和 memory counters。

查询结果始终保留 SQL、Trace Processor schema、原始 trace 和结构化结果之间的关系。

## 使用方案

1. 打开 Trace Analyzer，选择设备和采集模板。
2. 调整时长、buffer、数据源和目标进程。
3. 开始采集并等待 trace 拉回。
4. 运行内置分析或打开外部 Perfetto UI。
5. 查看调度、Binder、图形和应用阶段。
6. 保存最近会话、导出原始 trace 和结构化报告。

## 输出与限制

- 原始 trace、Trace Processor 查询结果和诊断报告是不同证据层。
- schema、设备 API、权限和 trace config 会影响可用字段。
- 跨工具跳转提供时间/对象关联，不自动证明因果关系。
- 当前多设备、统一跨会话时间线和更多系统数据源仍需按路线单独实现。
