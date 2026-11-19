# aps-cpu

围绕现有 `aps-simpleperf-engine` 的与 UI 无关的只读会话适配器。

## CPU MCP

`aps-cpu-mcp` 是绑定当前 profile 及可选基线的只读 stdio MCP server。它注册：

- `cpu_session_summary`
- `cpu_top_functions`
- `cpu_call_tree`
- `cpu_timeline_summary`：时间桶样本数与按线程统计的峰值桶。
- `cpu_compare_top_functions`：加 `--baseline-cpu <before-profile>` 后查询当前减基线的热点权重、样本数差异。
- `cpu_flame_blocks` 和 `cpu_stack_blocks`：分别在归一化权重范围、单线程采样时间范围内返回有界块。
- `cpu_export_report`：返回可校验的内联 JSON/CSV 报告，不写宿主文件。

参数与示例见 [CPU MCP 文档](../../../docs/ai/mcp/cpu.md)。

构建：

```sh
cargo build --locked -p aps-cpu --bin aps-cpu-mcp
```

启动：

```sh
./target/debug/aps-cpu-mcp --cpu /absolute/path/to/profile.simpleperf.pb
```

原始 `perf.data` 需要先由 host 导入流程转换；MCP 不执行 simpleperf、ADB、Shell 或任意路径读取。工具查询带有事件/时间/方向/权重过滤和分页上限，输出绑定 source digest 并在每次调用重新校验源文件未改变。

- 输入：`SIMPLEPERF` protobuf 导出（`report_sample.py --protobuf`），而不是 `perf.data`。
- 以只读方式打开常规文件，检查元数据并强制执行流式输入字节预算（默认 128 MiB，
  但不超过引擎的 256 MiB 上限）。
- 为会话保留已解析记录，并缓存一个调用树和一个顶层函数过滤投影。更改 offset/limit
  时只复制请求的页面（最多 512 行）；更改过滤器时只重建一次投影。
- 过滤器：线程 ID、事件名称、半开纳秒时间范围、正向/反向树、采样/事件计数权重。
  事件计数权重要求底层只有一种事件类型。
- 目录包含缺少声明的已采样线程/事件 ID；原始名称绝不本地化。
- `ProfileSummary::thread_count` 统计声明线程 ID 与采样线程 ID 的可用并集，不同于
  只统计声明的旧版 `analyze()` 摘要。摘要事件名称包含回退名称。`total_event_count`
  是原始计数，不是不同事件单位之间可比较的指标；分析时请使用单事件查询。

## 限制和未完成的迁移

字节预算**不是** RAM 上限：已解码记录和聚合树可能显著扩展。主机应用负责独立的
按内容寻址的快照和会话元数据边界；此 crate 不会持久化 profile 字节。这里没有子进程
沙箱、持久化索引、摘要绑定、捕获、视口渲染、取消或 Compose/Kotlin 一致性验收。
关闭/drop 会释放会话；`clear_projection()` 只释放当前缓存的投影。原生 GUI、真实的
大 profile 性能和打包发布验收仍是独立门禁。

测试源文件位于 `tests/` 下，但根据用户先完成完整重写的指示，测试执行会被推迟。

## CPU 视图模型

`top_functions(query, offset, limit)` 最多返回 512 行，按自身权重、包含权重和稳定身份
排序。已知函数身份使用文件 ID、符号 ID 和执行类型；未知符号保留地址身份。递归时，
包含权重/采样数中每个样本只计算一次函数；自身权重始终属于最初采样的叶节点。这
有意不是递归调用树行权重的总和。线程数是实际不同的采样 ID 数量。

`flame_graph(query, viewport)` 和
`flame_graph_in_range(query, viewport, start_fraction, end_fraction)` 生成权重域几何图形。
子节点会消耗其实际包含权重，并留下父节点的自身权重间隙。水平缩放是归一化的权重
范围，而不是时间范围。同一深度的亚像素块共享像素聚合；选择聚合项不会假装识别
单个函数。像素聚合中的权重描述其源行，而不是精确的可见/裁剪权重。

`stack_chart(query, TimelineViewport)` 在原始 Simpleperf 时钟域中显示一个像素宽的
**采样时间戳点**。它绝不会将样本连接为声称存在的执行区间，也不会编造采样间隔/
时长。不同线程会共享行，除非明确过滤；像素碰撞会报告聚合源数量、时间极值，以及
仅在所有来源一致时才提供的可选线程/事件。这还没有与旧版面向时长的 Stack Chart
保持一致。

`ChartFrame::hit_test(x, y)` 按深度索引行，并对 x 进行二分查找；选择结果返回实际
绘制的 packet。它使用半开边界并拒绝 NaN。`ChartFrame::selected(id)` 解析精确的
块/聚合事实。一个自定义画布可以使用单一共享命中测试表面绘制完整 packet。

图表输出上限为 20,000 个块、16,384 像素宽、512 个深度，以及 16 MiB 的预留字符串
负载。`truncated` 和 `omitted_blocks` 会暴露因上限而省略的内容；屏幕外行不计为省略。
已解析记录和完整投影缓存仍保留在内存中，因此输出上限仍不能限制进程 RAM。事件
过滤器和单事件的事件计数语义会保留。

## CSV 和 Firefox 边界

`export_call_tree_csv(query)` 和 `export_top_functions_csv(query)` 返回 UTF-8 文本，最多
16 MiB，不写入文件系统。分页不会截断导出；活动过滤器/权重/方向仍然适用。引号
遵循现有 `ReportExportService` 的逗号/引号/CR/LF 转义契约。指标列保留
`inclusive_weight`/`exclusive_weight` 术语。原生树 CSV 暴露原生祖先 ID、
`self_sample_count`、`weight_type` 和 `direction`；不会伪造缺失的逐节点 `thread_count`，
也不会声称与旧版数字 ID CSV 一致。顶层函数 CSV 保留旧版开头的六列，并追加明确的
自身采样/权重元数据。原始符号/路径文本会保留，不会本地化或默默重写；在打开电子
表格时应将 CSV 内容视为不可信数据。

`CpuSession::export_firefox_profile()` 现在会生成有界的 AOSP Gecko v24
`application/json+gzip` 负载，包含逐线程采样表、帧/栈/字符串表、时间戳转换、类别
推断和未知符号地址规范化，并匹配现有 `GeckoProfileExportService` 契约。原生写入器
使用标准库中的已存储 DEFLATE 块，并将压缩输出限制为 64 MiB；GPUI 主机负责选择目标
以及 create-new 发布。浏览器导入、相同输入往返一致性和大型 profile 资源验收仍是
独立门禁。
