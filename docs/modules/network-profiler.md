# Network Profiler

## 功能作用

Network Profiler 用于采集、导入和分析 Android 应用的 HTTP 调用证据：

- 在线采集显式接入 Network Profiler EventListener 的 OkHttp Client。
- 离线导入 HAR 1.1/1.2，并保留来源 timing 和缺失语义。
- 展示 HTTP Call、Exchange、Connection、状态、缓存、TLS 和可用阶段。
- 分别汇总网络调用结果、HTTP 状态族、阶段分位数和可验证的连接复用率。
- 将完整会话保存到 SQLite，并导出 JSON、CSV、partial HAR 或 Raw Bundle。

## 当前实现

### 在线采集覆盖

应用通过 `debugImplementation` 引入 Agent，并在目标 `OkHttpClient.Builder` 上显式安装 `NetworkProfiler.eventListenerFactory()`。AndroidX Startup 启动进程内 loopback Server；桌面端通过 `run-as` 读取随机 Token，建立 ADB 端口转发并以协议 v2 握手。

在线来源只覆盖已安装该 Factory 的 OkHttp Client：

- Retrofit 使用这些 OkHttp Client 时可被间接观测。
- WebView、Cronet、URLConnection、native socket 及未接入的 OkHttp Client 不可见。
- `network-instrumentation` 目前提供覆盖描述和接入提示，不执行自动字节码插桩。
- `EXPLICIT_FACTORY` 只表示接入方式，不宣称应用网络被完整覆盖。

`NetworkCoverage` 保存已观测进程、Client、库、事件能力、时间窗和已知限制；`NetworkEvidenceCompleteness` 独立保存序列缺口、队列丢失、未闭合阶段和跳过记录。

### Agent 会话与事件传输

Agent 转发并采集 OkHttp 4.12 支持的 Call、Proxy、DNS、Connect、TLS、Connection、Request、Response、Cache 和 Failure 生命周期回调。包装已有 EventListener 时，相同回调会先传给原 Listener，再传给 Profiler。

事件进入容量为 20,000 的进程级有界队列；队列满时丢弃新事件并保留序列缺口。每次握手会：

- 清除会话开始前的残留事件。
- 固定起始序列号和 dropped baseline。
- 轮换 URL path 匿名化 salt。

桌面端仍以 750ms、每批最多 1,000 条轮询。停止采集时 Agent 固定结束序列号，桌面端以最多 5,000 条一批重复读取，直到收到 `STOPPED`；结束后的事件不会混入当前会话。Push 未实现，因为它只改善实时 UI 延迟，不改善已在 Agent 记录的阶段时间。

### 最小化网络证据

Agent 和 HAR 导入使用同一版本化原则，在数据持久化前最小化网络内容：

- URL 移除 user-info 和 fragment，query 只保留 key 并替换全部 value。
- 非根 path 使用带随机 salt 的稳定摘要，同一会话内可聚合但不保存原值。
- HAR Header 保留名称；只有 Content-Type、Content-Length、Content-Encoding 保留值，其余值替换为 `<redacted>`。
- 请求和响应正文永不采集。
- 在线会话持久化前把设备序列号替换为会话内匿名标识。

每个会话和导出产物都记录 `redactionPolicyVersion`。最小化不等同于匿名，产物仍应作为可能敏感数据处理。

### 时间语义

在线 Agent 保留原始 device monotonic 时间，同时把 Call、Exchange、Phase 和原始事件规范化为相对握手原点的会话时间。握手 RTT 的一半作为 Clock Mapping 误差上界，映射同时保存 Host monotonic 和墙钟锚点。

HAR Call 使用相对最早 `startedDateTime` 的偏移，来源时间域标为 `HAR_WALL_CLOCK`。UI、SQLite、JSON 和 HAR 导出统一消费会话相对时间；在线 monotonic 值不会再直接加到墙钟时间。

### Call、Exchange、Connection 与阶段

`NetworkEventAssembler` 按 `callId` 和事件序列组装证据，并根据重复请求/响应循环拆分 Exchange：

- 重定向、认证 challenge 和 route retry 可以形成多个 Exchange。
- Proxy、DNS、Connect、TLS、请求/响应 Header/Body、Server wait 和 Connection held 均保留各自证据等级。
- 未找到 end 的 start 保留为 `PARTIAL`，并计入证据完整度。
- `CONNECT` 是包含 TLS 的外层阶段，`TLS.parentKind=CONNECT`；阶段摘要不会将两者重复相加。
- 原始事件随会话保存，启发式 Exchange 边界可以被复核。

Agent 为 Connection 分配进程内稳定的 opaque identity。Exchange 只使用：

- `NEW`：本 Exchange 有新建连接证据。
- `REUSED`：同一 Connection identity 已在当前会话出现。
- `UNKNOWN`：证据不足。

复用率只以 `NEW + REUSED` 为分母。`CONNECTION_HELD` 只表示 Call/Exchange 的持有区间，不表示 Socket 生命周期或连接池健康度。HTTP/2 的多个 Call 可以引用同一 Connection。

TLS 新建连接保存 `tlsVersion`、`cipherSuite`、Connection identity 和可信度；复用连接不会伪造本次握手或 session resumed。证书链和安全告警不属于该 Profiler。

### HAR 导入

`HarParser` 校验 HAR 1.1/1.2、记录 creator、版本和内容 SHA-256。每个 entry 生成一个 Call 和 Exchange：

- `blocked/dns/connect/ssl/send/wait/receive` 保留原始 duration，不伪造阶段起止时间。
- timing 使用 `VALUE/NOT_APPLICABLE/UNAVAILABLE/INVALID` 保存来源状态。
- HAR 1.2 的 `ssl` 标记为嵌套于 `connect`，不会顺序重复累加。
- `-1` 不会单独被解释为连接复用。
- `response.bodySize` 与 `content.size` 分别保存为 wire body size 和 decoded content size。
- 未识别的数值 timing 扩展保存在 `sourceAttributes`；其他未知内容不会绕过脱敏策略。

有效的可选 timing 缺失不会使会话自动降级；跳过 entry 或非法 timing 才影响 `NetworkEvidenceCompleteness`。当前解析器仍会在最大 512MiB 限制内一次读取完整文件。

### 分析与展示

`NetworkAnalyzer` 当前计算：

- `COMPLETED/FAILED/CANCELLED/INCOMPLETE` 网络调用结果；`COMPLETED` 不表示 HTTP 2xx 或业务成功。
- HTTP 1xx–5xx 状态族，独立于网络调用结果。
- 来源定义的请求/响应 body bytes，以及 HAR 单独提供的 decoded content bytes。
- Call p50/p90/p95、慢调用和缺失 TOTAL timing 数量。
- 已知缓存结果中的命中率。
- `NEW/REUSED/UNKNOWN` 数量及已知样本连接复用率。
- 按来源和阶段能力分组的阶段 p50/p95 与缺失数。

“最大已观测阶段”只是 duration 贡献，不是 DNS、服务器或客户端根因。当前没有实现并发扫描线或 Waterfall；Call 重叠不能冒充实际连接或带宽并发。

UI 展示覆盖与完整度、网络调用结果、HTTP 状态族、连接复用、最大已观测阶段、Call/Exchange 阶段及 TLS 协议信息。

### 持久化与导出

SQLite schema v2 完整保存并加载 Session、Coverage、Completeness、Clock Mapping、Warning、Call、Exchange、Connection、Phase、Cache、Failure、TLS、最小化 Header 和有序原始事件。v2 使用独立表名保留旧表不被破坏，并由 round-trip 测试验证新会话证据等价。

- JSON schema v2 包含完整会话、摘要、Call/Exchange/Phase 和原始事件。
- CSV 明示为单行 Call 摘要。
- HAR 带 `_aps.partial=true`，是不能恢复正文及全部标准字段的有损互操作投影。
- Raw Bundle 包含 `network-session.json`、`raw-events.json` 和声明 schema、来源、事件数及脱敏策略的 manifest。

## 已实现的优化

1. 分离网络采集覆盖、网络证据完整度和单项阶段可信度。
2. 以会话序列边界隔离队列，检测缺口并在停止时分页排空。
3. 在 Agent、HAR、SQLite 和导出之间执行版本化的默认不可逆最小化。
4. 统一在线与 HAR 的会话相对时间，并保存带误差界限的 Clock Mapping。
5. 按重复事件重建 Call、Exchange 和 Connection，完整转发 OkHttp EventListener。
6. 只基于 Connection identity 报告 `NEW/REUSED/UNKNOWN` 和已知样本复用率。
7. 保存 TLS version 与 cipher suite，不扩展为证书安全扫描。
8. 保持 HAR timing、大小字段、嵌套和缺失状态的原始语义。
9. 分离网络调用结果、HTTP 状态和阶段 duration 贡献，删除伪并发结论。
10. 通过 SQLite/JSON round-trip 和真实 Raw Bundle 保留可复核证据。
