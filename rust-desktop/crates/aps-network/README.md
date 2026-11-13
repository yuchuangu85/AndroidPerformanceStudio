# 最小化网络证据适配器

`HarSession` 复用 `aps-har-engine` 进行有界流式摘要和原始条目传输。其公共行只
包含数值指标、允许列表中的 method 以及 `redacted://minimized-url`。不会返回或
持久化任何 URL 组件（包括 path、query names、userinfo 或 fragment）、timestamp、
header、body、cookie、producer string、任意 method 或原始解析器错误。这里有意不
提供全保真模式。这比 Kotlin URL redactor 更严格，与 ADR 0029 中默认拒绝
的边界一致；不声称 endpoint 显示一致性。

选定文件以只读方式打开。默认值为：输入 512 MiB、100,000 个条目、每页 200 行；
可配置的输入限制不得超过 2 GiB / 1,000,000 个条目。engine 还会将每个 JSON 值
限制为 2 MiB，将嵌套深度限制为 64。页面在发布前会消费完整的有效流；部分结果会
被丢弃。读取期间会检查取消状态。所有解析都应在 UI 线程之外调用。

`HarSession::open` 现在将选定来源绑定到 SHA-256 摘要。条目页面只在 session 所有的
缓存中保留有界的最小化行（最多八页、512 KiB）；缓存键是 offset/limit，并且每个
返回页面都携带 source digest。缓存命中时仍会重新检查源元数据和摘要。Length/mtime
仍是快速变更检查，而摘要可以在发布时识别时间戳不变的替换；这里还不是持久化的
content-addressed HAR 索引。

## 显式 Agent 观测

`HarSession::from_observed(ObservedCapture)` 接受独立的、不可变且以内存为后端的
Agent 来源。它绝不会合成 HAR 文件，也不会复用 HAR timing 作为声称的设备观测。
其 SHA-256 标识规范化的最小化证据，而不是原始事件流。原始 URL、headers、cookies、
bodies、TLS/cipher、任意 diagnostics、package name 或 device serial 都不会进入公共
request rows。经过校验的 process-start/boot identity 将采集绑定到一个进程实例；
kernel identity 会从 presentation exports 中排除。

观测来源最多保留 20,000 次调用，每次调用最多保留 64 条阶段记录。
Outcomes 是真实的 `Completed`、`Failed`、`Cancelled` 或 `InFlight` 观测，独立于
HTTP status。排序和分页前，会在整个 observed session 上应用 `EntryQuery::outcome`。
Minimized HAR 只接受 `All`：它会拒绝其他 outcome filters，而不是根据 4xx/5xx 响应
推断 transport failures。搜索仍仅限于 minimized fields。

桌面 Agent v2 采集需要明确同意、正在运行的 debuggable App、Network Agent 和
经过插桩的 OkHttp clients。它会校验 READY PID、process start ticks 和 device boot
UUID，然后定期并在结束时重新检查 identity。它只拥有自己动态分配的 ADB forward。
STOP 会冻结 sequence/time/loss boundary，并在截止时间内排空；取消会拒绝竞争中的
发布并恢复之前的 publication。移除 forward 时会校验 identity 并回读；如果移除
结果不明确，不会针对可能已复用的端口重试。

页面/导出包含允许列表中的 protocol、call outcome、相对于设备的 request/phase
endpoints、可为空的 durations、取消证据、phase truncation 和 capture-loss counters。
缺失/不明确的 endpoints 保持未知。Body-end byte 小计不是完整的 wire traffic。
Dropped-event 和 sequence-gap 计数器可能重叠，不得相加。WebView、Cronet、
URLConnection、native socket 以及未插桩的客户端不在覆盖范围内。这不是完整的
原始流量导出，也不是完整设备网络活动的证据。
