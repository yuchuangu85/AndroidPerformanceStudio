# Network Profiler

## 功能作用

Network Profiler 是一个 Android 网络请求分析工具，核心功能包括：

- **在线网络采集**：通过设备上的 `NetworkProfilerAgent` 实时捕获 App 的 HTTP 网络请求
- **Agent 方式采集**：Agent 在 App 进程内通过 OkHttp EventListener 回调获取网络事件的精确时间戳（DNS、Connect、TLS、Request、Response 等各阶段耗时）
- **HAR 导入**：支持导入 HAR（HTTP Archive）格式文件进行离线分析
- **网络调用分析**：`NetworkAnalyzer` 分析 HTTP 调用的：
  - 请求方法（GET/POST 等）
  - URL（脱敏处理后）
  - 各阶段耗时分解（DNS 解析、TCP 连接、TLS 握手、请求发送、服务器等待、响应接收）
  - 状态码和结果（成功/失败/取消/不完整）
  - 请求/响应体积（字节）
  - 缓存状态（Hit/Miss/Conditional Hit）
- **会话管理**：通过 `SqliteNetworkStore` 持久化采集结果
- **多格式导出**：支持 JSON、HAR、CSV、Raw Bundle 多种导出格式

## 实现原理

### 采集流程

1. **Agent 部署**：`NetworkAgentCapture` 通过 ADB 与设备上的 Agent 建立连接
2. **事件捕获**：Agent 在 App 进程中通过 OkHttp EventListener 注册回调，捕获：
   - `callStart` / `callEnd`：请求开始和结束
   - `dnsStart` / `dnsEnd`：DNS 解析耗时
   - `connectStart` / `connectEnd`：TCP 连接耗时
   - `secureConnectStart` / `secureConnectEnd`：TLS 握手耗时
   - `requestHeadersStart` / `requestHeadersEnd`：请求头发送耗时
   - `requestBodyStart` / `requestBodyEnd`：请求体发送耗时
   - `responseHeadersStart` / `responseHeadersEnd`：响应头接收耗时
   - `responseBodyStart` / `responseBodyEnd`：响应体接收耗时
3. **轮询传输**：桌面端以 750ms 间隔轮询 Agent，拉取累积的网络事件
4. **事件组装**：`NetworkEventAssembler` 将原始事件组装成完整的 `HttpCall` 对象（含 `HttpExchange` 和 `NetworkPhase`）
5. **HAR 导入**：`HarParser` 解析 HAR JSON 文件，提取 entries 并转换为内部数据模型

### 数据分析

- **时序分析**：每条请求按阶段分解耗时，识别瓶颈阶段（DNS 慢 / TCP 慢 / TLS 慢 / 服务器慢）
- **成功率统计**：按状态码分类（2xx/3xx/4xx/5xx）
- **数据量统计**：请求/响应体积统计
- **并发分析**：同时进行的请求数

### 数据结构

- **HttpCall**：单次 HTTP 调用，包含 callId、method、redactedUrl、startedNs/endedNs、exchanges、outcome、source
- **HttpExchange**：单次 HTTP 交换，包含 statusCode、requestBytes/responseBytes、phases、failure
- **NetworkPhase**：单个网络阶段，包含 kind（DISPATCHER_QUEUE/PROXY_SELECT/DNS/CONNECT/TLS/REQUEST_HEADERS/REQUEST_BODY/SERVER_WAIT/RESPONSE_HEADERS/RESPONSE_BODY/CONNECTION_HELD/TOTAL）、startNs/endNs、confidence
- **NetworkCoverage**：覆盖率信息，包括 observedLibraries、instrumentationMode、supportedEventKinds、unsupportedStacks、droppedEvents、completeness

### 数据流

```
[Android Device] --ADB Socket--> [NetworkAgentCapture] --Poll--> [NetworkEventAssembler]
    --> [HttpCall[]] --> [NetworkAnalyzer] --> [Summary]
    --> [SqliteNetworkStore] (持久化)
    --> [Compose UI: NetworkProfilerScreen]

[HAR File] --> [HarParser] --> [HttpCall[]] --> [同上]
```

### 采集覆盖

`NetworkInstrumentationCoverage` 描述 Agent 的覆盖范围：
- **EXPLICIT_FACTORY**：App 使用显式的 OkHttpClient Factory 注册，覆盖所有请求
- **INSTRUMENTED_PARTIAL**：只有部分 OkHttpClient 被 Instrument，部分请求可能未捕获

### Export

- **JSON**：完整的 Session + Calls + Summary
- **HAR**：标准 HAR 格式（含 timings）
- **CSV**：表格化导出
- **Raw Bundle**：原始事件数据打包

## 优化建议与改进点

> 以下内容不替换已有设计，仅作为可考虑的更好实现方式或优化点补充。每条标注 **影响**（高/中/低）与 **可行性**（高/中/低）。

### 1. OkHttp 单库覆盖的局限【影响:高 / 可行性:中】

**当前实现问题**：Agent 通过 OkHttp `EventListener` 回调采集，依赖 App 使用 OkHttp 且使用了被 Instrument 的 `OkHttpClient` 工厂。现代 Android 项目越来越多用 **Retrofit（底层 OkHttp，可覆盖）**、**Ktor（底层可换 OkHttp/CIO）**、**Java `HttpURLConnection`**、**Cronet**、**Volley（可换 stack）**，这些都不在 OkHttp EventListener 覆盖范围内。

**更好的实现方式**：
- 在 Agent 端增加 **多 HTTP 栈支持**：
  - `HttpURLConnection`：通过 `URL.openConnection` hook / `sun.net.www.protocol` 注册拦截。
  - Cronet：hook `CronetEngine.newUrlRequest`。
  - Volley：hook `HurlStack` / `OkHttpStack`。
- 文档显式列出"已覆盖/未覆盖"的 HTTP 库矩阵，对未覆盖栈在 `NetworkCoverage` 中标记 `unsupportedStacks`（文档已提及该字段，建议补充具体语义示例）。
- 对完全无法 Instrument 的栈，退而求其次：解析 `atrace`/`systrace` 中的网络标签或 Perfetto `network` track，至少提供粗粒度时序。

### 2. 轮询拉取 → Push 推送【影响:中 / 可行性:中】

**当前实现问题**：桌面端以 750ms 间隔轮询 Agent 拉取累积事件。轮询在高频请求场景下产生延迟（平均 375ms 的检测延迟）与无谓的空轮询 IO。

**更好的实现方式**：
- 改为 **长连接 push 模型**：Agent 端事件就绪即通过 socket 推送（事件流），桌面端被动接收，延迟从平均半轮询周期降到毫秒级。
- 保留轮询作为弱网/不稳定连接的降级（socket 断开重试期间用轮询补数据）。
- 在 socket 上引入背压与缓冲：Agent 端环形缓冲，溢出时丢弃最旧事件并在 `NetworkCoverage.droppedEvents` 中累计（该字段已存在，建议明确语义）。

### 3. URL 脱敏算法的明确化【影响:高 / 可行性:高】

**当前实现问题**：`redactedUrl` 字段表明 URL 已脱敏，但未说明脱敏算法。URL 中常含 token、用户 ID、查询参数（如 `?token=xxx&user=123`），脱敏不当会泄露敏感信息；脱敏过度又失去诊断价值（无法区分是哪个 API 慢）。

**更好的实现方式**：
- 文档明确脱敏策略：**保留 scheme + host + path**，**查询参数按 key 白名单保留**（如 `page`、`size` 可保留；`token`、`auth`、`session`、`key`、`password` 一律替换为 `<redacted>`），其余值用 `***` 替代。
- 路径中的动态段（如 `/users/12345/orders`）可选规约：默认保留原始值（便于看到是哪个用户慢），提供"路径参数化"开关（→ `/users/{id}/orders`）用于聚合统计。
- 提供 **可配置脱敏规则**（项目级配置文件），不同业务自定义敏感 key 列表。
- 对响应体/请求体，默认不采集内容（只采体积），避免泄露 PII；如需采内容，需用户显式开启并支持字段级 redaction。

### 4. 阶段映射的损失与语义对齐【影响:中 / 可行性:高】

**当前实现问题**：`NetworkPhase.kind` 枚举 11 种，但 OkHttp EventListener 实际暴露的回调有限（`connectStart/connectEnd` 不分 TCP/TLS 中间态、`requestHeadersStart/End` 不分发送/接收等）。部分 phase 是推断的，存在时序歧义（如 `SERVER_WAIT` = `responseHeadersStart - requestBodyEnd`，但若没有 body 则起点是 `requestHeadersEnd`）。

**更好的实现方式**：
- 文档补充 **每个 phase 的计算公式**（start/end 各取哪个回调的 ns），消除歧义。
- 对"推断阶段"（无法直接从 EventListener 取到的，如 `DISPATCHER_QUEUE`、`PROXY_SELECT`）标注 `confidence`（已有字段，建议明确分级：`MEASURED` / `INFERRED` / `APPROXIMATED`）。
- 评估用 **OkHttp Interceptor**（`Interceptor.Chain`）补充 EventListener 不暴露的细节（如重试、重定向的多次 exchange），EventListener 对重定向/重试的可见性较差。

### 5. 连接复用与 keep-alive 分析【影响:中 / 可行性:中】

**当前实现问题**：`HttpExchange` 有 `CONNECTION_HELD` phase，说明记录了连接持有期，但未说明是否做"连接复用率""连接池健康度"分析。连接复用是性能优化的关键点（DNS/TLS 每次重连代价大）。

**更好的实现方式**：
- 增加会话级指标：**连接复用率**（reuse = 无 DNS/Connect/TLS 阶段的请求占比）、**平均连接寿命**、**连接池命中率**。
- 对每次新建连接（cold connection）单独标注，并对比其总耗时 vs 复用连接的耗时，量化"未复用连接的代价"。
- 对长连接被异常关闭（`CONNECTION_HELD` 异常短）的情况，作为异常事件提示。

### 6. TLS 版本与协议指纹【影响:低 / 可行性:高】

**当前实现问题**：`secureConnectStart/End` 记录 TLS 握手耗时，但未记录 TLS 版本、密码套件、证书信息。这些对调试"TLS 慢"或"安全降级"很关键。

**更好的实现方式**：
- Agent 端从 `SSLSocket`/`OkHttpsURLConnection.handshake` 读取 **TLS 版本、cipher suite、证书链**（CN、有效期），作为 `HttpExchange` 的扩展字段 `tlsHandshake`。
- 对 TLS 1.0/1.1 等过时协议、自签名证书、即将过期证书给出告警，帮助发现安全配置问题。
- 注意证书信息脱敏：CN 通常可保留，私钥/完整证书链按需采集。

### 7. 并发分析的算法明确【影响:低 / 可行性:中】

**当前实现问题**：`NetworkAnalyzer` 有"并发分析：同时进行的请求数"，但未说明算法。简单"同时间窗口计数"在大量请求时会失真（重叠区间计数）。

**更好的实现方式**：
- 用 **扫描线算法**（sweep line）：对每个请求 [startNs, endNs] 排序端点事件（+1/-1），计算每个时刻的精确并发数，输出并发数时序与峰值。
- 提供"并发数时序图"叠加在请求时间线上，可视化潮汐式流量，比单一"峰值并发"更有诊断价值。

### 8. HAR 导入的字段映射完整性【影响:低 / 可行性:高】

**当前实现问题**：`HarParser` 解析 HAR entries 转换为内部模型，但 HAR 的 `timings` 字段语义与 OkHttp phase 不完全对应（HAR 有 `block`、`dns`、`connect`、`ssl`、`send`、`wait`、`receive`），映射规则文档未说明。

**更好的实现方式**：
- 文档提供 **HAR timings → NetworkPhase.kind 映射表**，明确每个 HAR timing 映射到哪个 phase，对无法映射的字段标注。
- 对 HAR 缺失的字段（如 `requestBytes`/`responseBytes` 需从 `content.size` 推断、HAR 不区分 dispatcher queue），明确标注 `confidence=INFERRED`。
- 导入时校验 HAR 版本（1.1/1.2）与 `creator` 字段，对 Chrome/Fiddler/Charles 等不同来源的 HAR 做差异化适配（不同工具的 timings 粒度与起始点不同）。
