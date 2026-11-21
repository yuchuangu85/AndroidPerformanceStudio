# Startup Profiler MCP

[总览](README.md) · [统一编排 MCP](orchestrator.md) · [启动分析 CLI](../cli/startup.md)

使用统一 stdio MCP，无需新的启动程序。宿主启动时绑定一份 **APS Startup CSV** 或桌面导出的 **Startup JSON v1**：

```sh
aps mcp --input /absolute/path/to/startup.json
```

1. `aps_analyze_file({})` 读取全报告摘要。引用 `evidence.items[].id`，并保存 `input.sourceDigest`。
2. 绑定 Startup 报告时，`tools/list` 会在 `aps_follow_up` 的 `query` 中列出 `startup_runs`。用同一摘要分页或筛选，例如：

```json
{
  "query": "startup_runs",
  "sourceDigest": "<aps_analyze_file 返回的 64 位 SHA-256>",
  "requestedMode": "cold",
  "observedMode": "unknown",
  "missingMetric": "ttid",
  "offset": 0,
  "limit": 8
}
```

除 `query` 与 `sourceDigest` 外其余字段可省略。两种 mode 均接受 `cold|warm|hot|unknown`，`missingMetric` 接受 `ttid|ttfd`。多项过滤相交。比如可以问：「先列出这份报告中 TTID/TTFD 的观测数，再找几条请求为冷启动但无法确认实际模式的记录，引用证据 ID。」

- 结果包含 `items`、`total`、`offset`、`nextOffset` 和 `remainingQueries`。每页 1–16 条（默认 8）；`total` 是筛选后的匹配数，`nextOffset:null` 表示没有下一页。每次源绑定共 8 次追问。
- 行证据按原文件顺序，包含 `runIndex`（零基）、`iteration`、请求/观测模式、TTID/TTFD 及独立的 Activity Manager 毫秒时长。相同源的 CLI 与 MCP 行证据 ID 一致。缺失指标保留为 `null`，请求模式不能取代观测模式，Activity Manager 耗时不能取代 TTID/TTFD。
- 工件变更后停止取证，不支持通过工具参数换路径。工具不返回原始运行 ID、日志、Trace 路径、包名、设备标识或编译/环境声明。导入结果不能证明实时采集或跨会话可比，也不重建启动阶段。
- 没有启动 APK、设备采集、任意事件日志查询或单独的 Startup Server。APS 不调用模型；是否把有界证据发送给模型由外部客户端决定。

验证：`cd rust-desktop && cargo test --locked -p aps-cli --test orchestrator_stdio startup`。覆盖 CLI/MCP 摘要与行证据一致、分页、缺失指标、非法参数和源替换；不代表真实设备或模型验收。
