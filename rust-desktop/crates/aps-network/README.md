# 脱敏网络证据适配器

`HarSession` 复用 `aps-har-engine` 进行有界流式摘要和条目传输。公共条目只包含
脱敏后的结构化身份：scheme、host、可选 port、路径占位符、查询键与全量脱敏的查询值、
允许列表中的 method/protocol、HTTP status、producer duration 与字节数、调用结果、
连接复用和 producer 时间戳。userinfo、path、query 值、fragment、cookie、body 与
除 content 元数据外的 header 值不会返回或持久化。这与 Kotlin `NetworkUrlRedactor`
的默认策略一致（allowlist API 可用于显式放行非敏感查询值）。

选定文件以只读方式打开。默认值为：输入 512 MiB、100,000 个条目、每页 200 行；
可配置的输入限制不得超过 2 GiB / 1,000,000 个条目。engine 还会将每个 JSON 值
限制为 2 MiB，将嵌套深度限制为 64。页面在发布前会消费完整的有效流；部分结果会
被丢弃。读取期间会检查取消状态。所有解析都应在 UI 线程之外调用。

`HarSession::open` 将选定来源绑定到 SHA-256 摘要。条目页面只在 session 所有的
缓存中保留有界的脱敏行（最多八页、512 KiB）；缓存键是 offset/limit，并且每个
返回页面都携带 source digest。缓存命中时仍会重新检查源元数据和摘要。Length/mtime
仍是快速变更检查，而摘要可以在发布时识别时间戳不变的替换；这里还不是持久化的
content-addressed HAR 索引。

## 条目证据

`NetworkEntry` 是列表与筛选使用的有界索引行。`HarSession::entry_detail(index)`
按需重新读取一条条目并返回：

- HAR `timings` 映射出的阶段（blocked/dns/connect/ssl/send/wait/receive 与 total）。
  HAR 阶段一律标记为 `INFERRED`；`-1` 仅对明文请求的 TLS 表示 `NOT_APPLICABLE`，
  其余保持 `UNAVAILABLE`，更小的负数记为 `INVALID` 而不是 0。
- 请求/响应 headers 的脱敏投影。仅 `content-type`、`content-length`、
  `content-encoding` 保留值，其余值替换为 `<redacted>`；名称与数量有上限。
- HAR `_error` 只保留为固定的 `HAR_ERROR` 类型，不复制原始错误消息。

`visit_details` 为导出提供同一套逐条证据。`analysis` 汇总调用结果、连接复用、
p90、阶段中位数/P95 与无效阶段计数；`domains` 基于脱敏 URL 的 host 分组；
`activity` 给出按完成时间分桶的字节与并发峰值/均值。

## 显式 Agent 观测

`HarSession::from_observed(ObservedCapture)` 接受独立的、不可变且以内存为后端的
Agent 来源。它绝不会合成 HAR 文件，也不会复用 HAR timing 作为声称的设备观测。
其 SHA-256 标识规范化的脱敏证据，而不是原始事件流。URL 在 Agent 传输边界即脱敏；
TLS version/cipher 只保留有界文本；message、connection id、instrumentation string
与自由文本诊断不会进入快照。经过校验的 process-start/boot identity 将采集绑定到
一个进程实例；kernel identity 会从 presentation exports 中排除。

观测来源最多保留 20,000 次调用，每次调用最多保留 64 条阶段记录。Outcomes 是真实的
`Completed`、`Failed`、`Cancelled` 或 `InFlight` 观测，独立于 HTTP status。
排序和分页前，会在整个 observed session 上应用 `EntryQuery::outcome`。HAR 的
outcome 只来自 `_error` 与 `time`，绝不由 4xx/5xx 响应推断。

桌面 Agent v2 采集需要明确同意、正在运行的 debuggable App、Network Agent 和
经过插桩的 OkHttp clients。它会校验 READY PID、process start ticks 和 device boot
UUID，然后定期并在结束时重新检查 identity。它只拥有自己动态分配的 ADB forward。
STOP 会冻结 sequence/time/loss boundary 并在截止时间内排空；取消会拒绝竞争中的
发布并恢复之前的 publication。移除 forward 时会校验 identity 并回读；如果移除
结果不明确，不会针对可能已复用的端口重试。

## 导出与边界

- JSON：schema 2，包含 session 摘要/分析、脱敏调用、headers、阶段与 TLS/失败元数据。
- CSV：脱敏整会话行，不依赖当前筛选或分页。
- 部分 HAR：保留可信的 producer `startedDateTime`、脱敏 URL、headers 与 timings；
  body/content 为 `-1` 或缺失，`_aps` 明确标记为部分证据。
- Raw bundle：脱敏报告 + manifest；原始事件字节不保留，因此 `raw-events.json` 为空，
  manifest 明确记录 `raw_events=not_retained`。

页面/导出包含允许列表中的 protocol、调用 outcome、连接复用、相对于设备的
request/phase endpoints（observed）、可为空的 durations、取消证据、phase truncation
和 capture-loss counters。缺失/不明确的 endpoints 保持未知。Body-end byte 小计不是
完整的 wire traffic。Dropped-event 和 sequence-gap 计数器可能重叠，不得相加。

WebView、Cronet、URLConnection、native socket 以及未插桩的客户端不在覆盖范围内。
这不是完整的原始流量导出，也不是完整设备网络活动的证据。会话自动持久化/重新打开
与 SQLite 目录尚未移植；Android Network Agent 插桩库仍需由被测应用提供。
