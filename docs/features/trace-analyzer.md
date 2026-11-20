# Trace Analyzer

## 功能定位

Trace Analyzer 负责 Perfetto trace 的采集、导入、固定版本 Trace Processor 查询、诊断、搜索、外部 Perfetto UI 交接和报告导出。它是 CPU 调度、Binder/IPC、图形、输入、内存、ART、I/O、温控和跨域时间关联的原始证据入口。

当前状态：

- **已接入**：本地 trace 导入、设备采集编排、固定版本 Trace Processor、warm session/有界单次查询、诊断目录、最近会话、slice 搜索、结果导出和外部 viewer 入口。
- **条件能力**：设备数据源、Trace Processor 资源、Android 版本和 trace 模板决定可查询字段；缺失数据源不会被补造。
- **未完成验收**：Native Heap/ART/FrameTimeline 全设备矩阵、外部 Perfetto UI 交接/书签/取消和原生发布门禁仍需单独验证。

## 功能点

- 采集模板：System Overview、App Performance、Graphics Pipeline、Input Latency、Memory Profile、Custom。
- 导入 `.pftrace`、`.perfetto-trace` 等本地文件；输入有大小、路径和工具资源边界。
- 固定 Trace Processor `v59.1`，优先 warm native session；不可用时降级为有界单次查询并显示状态。
- 诊断 CPU scheduling、run queue、sched_waking、wakeup latency、CPU contention、线程状态和频率。
- 诊断 Binder latency、IPC 长尾、`system_server`、server saturation、wait chain 及 Binder/FrameTimeline overlap。
- 诊断 Frame jank、SurfaceFlinger、文件 I/O、fsync、block queue、page fault、SQLite、ART GC/allocation/JIT/dex2oat/class loading、thermal、DVFS、GPU frequency、power rail 和 input latency。
- 结果页保留 SQL/诊断 ID、时间戳、schema/tool 版本、来源 trace 和查询错误。
- 外部 Perfetto UI 用于高级原始探索；APS 自研页负责受控状态、摘要、诊断和跨工具关联。

## 原理与数据流

```text
ADB Perfetto capture or local trace
  -> source validation + pinned Trace Processor
  -> warm TraceSession or bounded one-shot query
  -> typed SQL adapter / diagnostic catalog
  -> rows, slices, metrics and findings
  -> GUI search, report, export and external viewer
```

当前 Rust 入口：

- `rust-desktop/crates/aps-app/src/trace`：Trace workspace、会话、采集、诊断、搜索和导出。
- `rust-desktop/crates/aps-perfetto`：Trace Processor 资源、session、schema 和查询适配。
- `rust-desktop/crates/aps-app/src/perfetto_tool.rs`：可执行工具和资源定位。

不允许任意 SQL、任意工具路径、自动下载或云端回退。typed adapter 只暴露业务所需字段，原始 trace 是最高层证据；查询摘要不是原始 trace 的替代品。

## 使用方法

1. 打开 Trace Analyzer，选择设备和模板，或导入本地 trace。
2. 检查工具资源、trace 大小、schema 和数据源状态。
3. 在摘要/诊断页选择 CPU、Binder、Frame、I/O、ART、thermal、GPU 或 input 目录。
4. 点击结果查看 SQL/查询身份、时间范围、线程/进程和原始字段。
5. 使用 slice 搜索、时间线和过滤器缩小范围；需要原始表结构时打开外部 Perfetto UI。
6. 从 Frame、Startup、CPU、Battery 等功能页按来源身份关联 Trace，而不是凭时间相近直接合并。
7. 导出原始 trace、结构化查询结果或诊断报告。

## 证据边界

- 查询为空可能表示数据源缺失、schema 不支持或时间窗口不匹配，不等于指标为零。
- Binder/Frame、power/frame 和 CPU/启动的 overlap 是有界关联候选，不是因果证明。
- power rail、DVFS、GPU frequency 是系统级证据，不自动归因到单个 App。
- 外部 viewer 能打开不证明 APS 诊断结果已经完成，也不证明设备采集/打包资源在所有平台可用。
- 采集模板、Trace Processor 版本、schema 和原始 trace 必须与报告一同保留。

## 故障排查与限制

- Trace Processor 启动失败：检查可执行文件、相邻 resources、版本校验和架构；不要改用 PATH 中未知版本。
- 查询返回 unavailable：查看诊断状态和 schema，确认 trace 是否启用了对应 ftrace/atrace/data source。
- 设备采集无数据：检查 USB/ADB 授权、模板、目标包、Android 版本和录制是否在目标动作前就绪。
- 结果与其他 profiler 不一致：先核对时钟、进程实例、window/surface identity 和会话 digest。
- 当前没有专用 Trace MCP；CLI/MCP 的文件分析是受限绑定输入，不等于开放任意 Trace 查询或设备采集。
