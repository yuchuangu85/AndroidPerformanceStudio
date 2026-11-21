# Network Profiler

## 功能定位

Network Profiler 分析目标 App 内已配置 APS Network Agent v2/OkHttp Agent 的 HTTP/HTTPS 请求，也支持独立导入 HAR。它不是系统级抓包器，不会自动看到目标 App 未接入 Agent 的请求、其他进程流量、WebView/Cronet/URLConnection/native socket 或全部设备流量。

当前状态：

- **已接入**：Agent v2 受控采集、脱敏请求模型、请求列表/详情、阶段、连接/TLS、失败、并发、域聚合、p90/阶段统计、JSON/CSV/部分 HAR/raw bundle 导出。
- **条件能力**：实时 capture 依赖目标 App debug 接入 Agent、ADB forward、目标进程、授权和 Agent 事件排空。
- **未完成验收**：真实插桩客户端、握手/停止/排空、会话自动持久化和所有真机矩阵仍开放。

## 功能点

- 请求开始/结束、失败/取消、DNS、连接、TLS、请求/响应阶段和字节证据。
- URL、Header、Query、Cookie、Body 和自由文本在传输/展示边界脱敏；AI 证据排除 URL 列。
- 按 method、status、失败、域名、时间、连接复用、方向和并发过滤/排序。
- 请求详情展示阶段耗时、连接/TLS、响应字节、失败类型和 inferred/observed 标记。
- 阶段中位数/P95、p90、域聚合、完成字节活动图和活动并发。
- 有界损失字段：dropped events、sequence gaps、discarded events、omitted calls。
- 最多约 20,000 请求、每请求约 64 个阶段；超限保持截断状态。

## 原理与数据流

```text
configured OkHttp Agent v2 or HAR import
  -> protocol/session + controlled ADB forward
  -> cursor event drain / bounded HAR parser
  -> redacted NetworkSession
  -> request timeline, detail, aggregates and exports
```

当前 Rust 入口：

- `rust-desktop/crates/aps-app/src/import/network`：live capture、controls、details、exports。
- `rust-desktop/crates/aps-network`：Agent v2 协议、脱敏模型、统计和限制。
- `rust-desktop/crates/aps-har-engine`：HAR 解析和导出边界。

HAR 是独立输入；其 timing 通常是 inferred，不能冒充 Agent 的完整事件覆盖。源文件按元数据/SHA-256 检查变化，变化后不会继续使用旧缓存。

## 使用方法

### Agent

1. 在目标 Android App 的 debug 工程中接入 APS Network Agent/OkHttp EventListener。
2. 安装并启动目标 App，选择设备和进程。
3. 确认 Network capture 授权，建立受控 ADB forward。
4. 执行业务操作，等待事件轮询和停止时 drain 完成。
5. 查看请求列表、阶段、连接/TLS、失败和域聚合。

### HAR

1. 导入 HAR 文件并等待源校验/解析。
2. 按 URL 脱敏标识、method、status、阶段和时间筛选。
3. 查看请求详情和统计；注意 inferred timing 与原始 producer 语义。
4. 导出 JSON/CSV/部分 HAR/raw bundle。

## 证据边界与限制

- Agent 只看到它所在进程和已接入客户端的事件。
- 脱敏后不能恢复原始 URL、凭据、body 或任意 headers；导出不是无损网络抓包。
- HAR 的字段和 timing 不能证明实时连接复用、TLS 或完整事件覆盖。
- 设备单调时钟、Agent 时间和主机时间必须分开；不能直接混用 wall-clock。
- 网络延迟不自动证明服务端、DNS、TLS、连接池或客户端代码是根因。

## 故障排查

- 没有实时请求：检查 App 是否真的接入 OkHttp Agent、进程是否重启、端口/forward 和授权是否有效。
- 只有 HAR：说明当前路径没有 Agent 证据；不要声称已完成设备抓取。
- 事件缺失：查看 sequence gaps/dropped/omitted 字段，不要用空列表表示“没有请求”。
- HAR 导入失败：检查 JSON schema、大小/entry 上限和源文件是否在导入期间变化。
