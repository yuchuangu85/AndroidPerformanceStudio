# 启动分析 CLI

[CLI 总览](README.md) · [Startup MCP](../mcp/startup.md) · [启动分析](../../features/startup-profiler.md)

用桌面 Startup Profiler 导出的 **APS Startup CSV** 或 **Startup JSON（schemaVersion 1）** 分析已经采集的结果。CLI 不连接设备，不启动应用；这些命令也不会默认调用模型。

```sh
# 全报告摘要：各指标的观测数、缺失数、均值、P50 和 P95
aps analyze ./startup.csv --kind startup

# 只看请求为冷启动但观测模式未知，且缺少 TTID 的运行
aps analyze ./startup.json --scope startup-runs \
  --requested-mode cold --observed-mode unknown --missing-metric ttid --limit 8

# 保留结构化证据，或追加 --ai review --out ./startup-review.md 进行终端审批
aps analyze ./startup.json --kind startup --evidence-out ./startup-evidence.json
```

自动识别支持 APS 两种报告；`--kind startup` 显式选择 Startup 解析器。`--scope startup-runs` 也限定输入为 Startup，不和 `--kind trace` 或 `--frame-id` 混用。CSV 至少需要 `iteration,runId,requestedType,observedType,displayedTimeMs,fullyDrawnTimeMs` 列；可选 `thisTimeMs,totalTimeMs,waitTimeMs`。JSON 使用桌面报告的完整 v1 schema，不是任意 `runs` JSON 或 Macrobenchmark JSON。领域上限为 **64 MiB、100,000 次运行**，CSV/JSON 还受各自的结构预算限制。

- 不带 `--scope`：`evidence.items` 包含整份报告的 `startup-summary`；已有 CSV 摘要格式不变。
- `--scope startup-runs`：保留整份报告摘要，追加当页运行。过滤条件相交；`--requested-mode` 和 `--observed-mode` 分别接受 `cold|warm|hot|unknown`，`--missing-metric` 接受 `ttid|ttfd`。这些条件不改变整份报告摘要。
- `--offset` 从 0 开始，按匹配行计；`--limit` 默认 8、范围 1–16。用 `evidence.page.nextOffset` 继续读，值为 `null` 即结束。`evidence.page.total` 是匹配运行数，等于 offset 时返回空页，超过时拒绝。
- 行证据 ID 与 MCP 相同，由 `input.sourceDigest` 与零基 `runIndex` 绑定；`runIndex` 是文件行序，不是 `iteration` 或原始运行 ID。输出不包含原始日志、路径、设备标识、编译声明或自定义备注。

TTID（首次显示）和 TTFD（完全绘制）分别读取报告中的对应时长，单位均为毫秒；缺失显示为 `null`，不会用 `amTotalTimeMs` 补齐。结果是**导入的观测值**，不会据此验证实际启动模式、编译状态、阶段时钟或回归。原始 `am start -W`、logcat 和 Perfetto trace 不作为这个 Startup 报告接口的输入；Trace 可使用 [Trace CLI / MCP](../mcp/trace.md)。直接设备采集仍使用桌面 Startup Profiler；`aps capture startup` 未开放。
