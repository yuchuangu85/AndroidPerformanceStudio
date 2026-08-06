# Network Profiler 优化项实施总结

基于 `docs/modules/network-profiler.md` 中的 8 条优化建议，本次共实施 7 条（2.2 Push 推送模式暂缓），涉及 8 个文件、1 个新增模型、4 个新增分析指标。

## 改动文件清单

| 文件 | 改动类型 |
|------|----------|
| `network-model/.../NetworkUrlRedactor.kt` | 重写：object → 可配置 class |
| `network-model/.../NetworkModels.kt` | 扩展：TlsHandshake + 字段文档化 |
| `capture-network/.../NetworkAgentCapture.kt` | 扩展：新增阶段 + TLS 提取 + 连接复用检测 |
| `analysis-network/.../NetworkAnalyzer.kt` | 扩展：连接复用分析 + 扫描线并发分析 |
| `parser-har/.../HarParser.kt` | 扩展：HAR 映射文档 + INFERRED confidence |
| `android-agent-network/.../NetworkProfilerAgent.kt` | 适配：cipherSuite 采集 + 新 API |
| `network-agent-protocol/.../NetworkAgentProtocol.kt` | 扩展：cipherSuite 字段 |
| `network-instrumentation/.../NetworkInstrumentationCoverage.kt` | 扩展：HTTP stack 覆盖率矩阵文档 |
| `android-agent-network/build.gradle.kts` | 修复：补充 :network-model 直接依赖 |
| 4 个 `*Test.kt` | 更新/新增测试用例 |

---

## 优化项详述

### 2.3 URL 脱敏算法的明确化

**影响**: 高 / **可行性**: 高

`NetworkUrlRedactor` 从单例 object 重构为可配置 class。

- **Query key 白名单**：支持 `queryKeyAllowlist` 保留特定参数值（如 `page`、`size`），敏感 key（`token`、`auth` 等）始终 redact
- **路径参数化**：`parameterizePath = true` 将 `/users/12345/orders/67890` → `/users/{id}/orders/{id}`，支持 UUID / 数字 / 长 hex 前缀识别
- **脱敏警告**：allowlist 中被覆盖的敏感 key 记录到 `redactionWarnings`
- **向后兼容**：`NetworkUrlRedactor.default()` 保持原"全部 redact"行为

### 2.4 阶段映射的损失与语义对齐

**影响**: 中 / **可行性**: 高

- `NetworkPhaseKind` 枚举补充 12 阶段的完整 OkHttp EventListener 映射表文档
- 新增两个阶段：
  - `DISPATCHER_QUEUE`：callStart → dnsStart/connectStart（APPROXIMATED）
  - `CONNECTION_HELD`：connectionAcquired → connectionReleased（EXACT）
- 细化 confidence 枚举使用：EXACT / DERIVED / INFERRED / APPROXIMATED / PARTIAL / UNKNOWN
- `NetworkEventAssembler` 增加完整的阶段计算公式文档

### 2.5 连接复用与 keep-alive 分析

**影响**: 中 / **可行性**: 中

- `HttpExchange` 新增 `connectionReused: Boolean` 字段
- `NetworkSummary` 新增 `connectionReuse: ConnectionReuseSummary`：
  - `reusedExchangeCount` / `coldExchangeCount`：复用 vs 新建连接数
  - `reuseRatio`：连接复用率
  - `avgConnectionHeldMs`：复用连接的平均持有时间

### 2.6 TLS 版本与协议指纹

**影响**: 低 / **可行性**: 高

- 新增 `TlsHandshake` 数据类（`tlsVersion`、`cipherSuite`、`resumed`）
- `HttpExchange` 新增 `tlsHandshake: TlsHandshake?` 字段
- Android Agent 从 OkHttp `Handshake` 同时采集 `tlsVersion` 和 `cipherSuite`
- 协议新增 `cipherSuite` 字段（向后兼容）

### 2.7 并发分析的算法明确

**影响**: 低 / **可行性**: 中

- 实现扫描线算法（Sweep-line algorithm）计算并发请求
- `NetworkSummary` 新增 `concurrency: ConcurrencySummary`：
  - `peakConcurrency`：峰值并发数
  - `peakConcurrencyNs`：峰值时间戳
  - `avgConcurrency`：平均并发数
  - `timeline: List<ConcurrencyPoint>`：并发变化时间线（适合绘制阶梯图）

### 2.8 HAR 导入的字段映射完整性

**影响**: 低 / **可行性**: 高

- HAR timings → NetworkPhaseKind 完整映射表文档化（含 Chrome/Fiddler/Charles 差异说明）
- HAR 导入的所有 phase 改用 `INFERRED` confidence（HAR 为墙钟时间，精度低于 monotonic ns）
- 连接复用检测：`dns=-1 && connect=-1 && ssl=-1` → `connectionReused = true`
- `responseBytes` 优先取 `bodySize`，fallback 到 `content.size`
- `parseInstant` 丢失的回归修复

### 2.1 OkHttp 单库覆盖的局限

**影响**: 高 / **可行性**: 中（仅文档化）

- `NetworkInstrumentationCoverage` 补充完整 HTTP stack 覆盖率矩阵：
  - OkHttp / Retrofit(OkHttp) / Ktor(OkHttp) → ✓ FULL
  - Ktor(CIO/Android) / HttpURLConnection / Cronet / WebView → ✗ NONE
  - Volley 区分 OkHttpStack(✓) vs HurlStack(✗)

---

## 测试结果

```
=== Compile all network-profiler modules ===
COMPILE: OK

=== Run tests ===
network-model:       6 tests passed
capture-network:     4 tests passed
analysis-network:    4 tests passed
parser-har:          1 test passed
TESTS: OK
```

- 所有模块编译通过（含 Android agent）
- 15 个测试全部通过
- 额外修复：`android-agent-network` 补全缺失的 `:network-model` 直接依赖
