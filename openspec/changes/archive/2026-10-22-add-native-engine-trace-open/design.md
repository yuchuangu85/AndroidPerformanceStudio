## Context

Trace Analyzer 当前的"Open in Perfetto UI"路径：`PerfettoUiServer`（JDK `HttpServer`，绑 127.0.0.1:9001）只吐 trace 字节，浏览器端 ui.perfetto.dev 通过 `?url=` 拉全量字节后用 WASM 引擎解析。浏览器 `Uint8Array` ~2GB 上限 + WASM 内存墙 + 运行时膨胀 2-4 倍，大 trace 无法打开。

`trace_processor_shell`（pinned v57.2）的 `server http` 模式在原生进程加载 trace，通过 HTTP+WebSocket RPC 服务查询；ui.perfetto.dev 启动时主动探测 `http://127.0.0.1:9001`，发现后弹对话框让用户选择"外部加速器(WebSocket)"或"内置 WASM"。官方文档（[Visualising large traces](https://perfetto.dev/docs/visualization/large-traces)）明确这是大 trace 的官方方案。

现有基础设施：
- `TraceProcessorToolResolver`：二进制解析 + SHA-256 校验 + 可执行位自愈 + override 优先级（spec `trace-processor-tool-resolution`）。
- `HostProcessRunner`：进程启动/日志/取消。
- `TraceAnalysisContext.start()`：`server http` 启动 + 就绪探测轮询（10s 超时）的模式可参照。
- `PerfettoMainPage` 的 `DisposableEffect`/`isActive`：已有 `uiServer.stop()`、`analysisContext?.close()` 清理钩子。
- 9001 端口冲突：`PerfettoUiServer` 与原生引擎必须互斥；`PerfettoUiServer.start()` 遇占用报 `SERVER_START_FAILED`。

已确认的行为事实（v57.2 实测）：
- `trace_processor_shell server --help`：`http` 模式默认端口 9001，"This is what the Perfetto UI (ui.perfetto.dev) connects to"；支持 `--idle-timeout`（http 模式默认 never）。
- ui.perfetto.dev 无需 `?url=` 或 CSP flag 即可探测 9001；`?rpc_port=` 仅非 9001 多实例场景需要（且需手动开 CSP flag）。
- Chrome 对 HTTPS 页面连接 `ws://127.0.0.1:9001` 无混合内容阻断（loopback 是安全上下文）。
- 首次 RPC 连接后 UI 缓存 trace 到 IndexedDB 并改写为 `local_cache_key`，此后刷新不再依赖本地端点。

## Goals / Non-Goals

**Goals:**
- 用户可用一个显式按钮，以原生 `trace_processor_shell server http` 引擎在 ui.perfetto.dev 中打开当前 trace（大文件可用）。
- 修复"Open in Perfetto UI"按钮无反应的接线 bug，使其真正启动字节服务并打开浏览器。
- 两种模式对 9001 端口的互斥接管清晰、可预期、无半开状态。
- 原生引擎进程随页面生命周期清理，带 idle 超时兜底，不残留孤儿进程。

**Non-Goals:**
- 不改 APS 智能洞察"Open in timeline"的置灰逻辑（用户已确认暂不处理）。
- 不让诊断查询（`TraceAnalysisContexts`）复用原生 9001 实例（未来优化）。
- 不做多 trace 多实例并行（需 CSP flag，且多端口语义与 spec 冲突）。
- 不做自动模式选择/体积阈值魔法（保持"显式用户动作启动服务"的 spec 哲学）。
- 不支持 `server unix`/`stdio` 模式。

## Decisions

### D1: 两个模式互斥占用 9001，后启动者显式接管

原生引擎启动前先 `uiServer.stop()`；字节服务启动路径（`launchInUi`）不变——若原生进程占着 9001，`PerfettoUiServer.start()` 会自然报 `SERVER_START_FAILED`（沿用现有错误路径）。

- 替代方案 A（原生引擎用随机端口 + `?rpc_port=`）：被否。`?rpc_port=` 非默认端口需要用户在 ui.perfetto.dev 手动开启 `cspAllowAnyWebsocketPort` flag，每次安装只提示一次但仍是额外摩擦，且 spec 已锁定"9001 是唯一允许的本地源"。
- 替代方案 B（自动检测 9001 占用者并提示）：被否。引入跨进程端口归属探测的复杂度，收益低；显式接管 + 现有错误提示足够。

### D2: 新建 `NativeTraceProcessorServer`（perfetto-ui-server 模块），不扩展 `PerfettoUiServer` 也不复用 `TraceAnalysisContext`

新类职责单一：resolve 二进制 → `server http --ip-address 127.0.0.1 --port 9001 --idle-timeout 30m <trace>` 启动 → 就绪探测 → `Desktop.browse("https://ui.perfetto.dev")` → stop。

- 不扩展 `PerfettoUiServer`：字节服务与进程管理是完全不同的生命周期模型（无子进程 vs 有子进程），混在一起会让 `ServeMode` 枚举承载两种实现语义。
- 不复用 `TraceAnalysisContext`：它是 artifact-scoped（注册表、hash 校验、随机端口、SQL 查询门面），语义不同；且它不绑 9001。
- 复用：`TraceProcessorToolResolver`、`HostProcessRunner`、就绪探测轮询模式（参照 `TraceAnalysisContext.start()`）。
- 就绪探测：轮询 `http://127.0.0.1:9001` 直到响应或 10s 超时（`TRACE_PROCESSOR_NOT_READY`），沿用现有模式。可先复用 `isTraceProcessorReady` 的思路（探测 RPC 端点响应），实现时抽取或复制小函数。

### D3: 浏览器 URL 不带 `?url=`，靠 UI 的 RPC 自动发现

打开 `https://ui.perfetto.dev`（裸地址）。UI 启动时探测 9001，弹"使用外部加速器？"对话框，用户确认后走 WebSocket。

- 替代方案（带 `?url=http://127.0.0.1:9001/trace_file.perfetto-trace`）：被否。`?url=` 语义是"拉全量字节进浏览器"，会绕过原生加速的意义；且 v57.2 的 `server http` 的 `/trace` 路径是 RPC 端点，不是字节端点，拉出来的是 RPC 响应而非 trace 字节。
- 不带 `?rpc_port=`：9001 是探测默认值，无需该参数。

### D4: 进程生命周期挂在页面生命周期 + `--idle-timeout 30m` 双保险

- `onDispose`：关闭 native server（kill 进程 + 清理）。
- `isActive` 失活：同样关闭（与 `uiServer.stop()` 同批处理；注释说明差异——字节服务可留驻，原生进程内存占用大，失活即回收）。
- 启动参数带 `--idle-timeout 30m`：即使清理路径遗漏（如进程崩溃恢复边界），30 分钟无活动后 trace_processor 自行退出，不永久占用 9001。
- 替代方案（仅靠 `--idle-timeout` 不做主动清理）：被否。用户回到页面点"Open in Perfetto UI"会被 `SERVER_START_FAILED` 困住；主动清理保证模式切换即时生效。

### D5: 按钮常驻 + 文案标注适用场景，不做体积阈值

"Open with native engine"（中文"用原生引擎打开"）按钮常驻在"Open in Perfetto UI"旁，`enabled` 条件与现有按钮一致（`artifact.viewerAvailable`）。副文案或 tooltip 说明"适合大文件"。

- 替代方案（trace > 某阈值才显示/高亮）：被否。显式动作优先于自动魔法，且阈值魔法不可预期；用户教育交给文案。
- 不新增 `viewerAvailable` 类似的新可用性条件：resolver 解析失败会在点击时报错（`TRACE_PROCESSOR_*` 错误码），与诊断查询链路的错误呈现一致。

### D6: 修复 `onOpenViewer` 接线（顺带修复，非独立 change）

`PerfettoMainPage.kt` 的 `onOpenViewer` 在 `openTrace(traceFile, activeArtifact)` 后补调 `openInPerfettoUi(traceFile, null)`，与 `onOpenRecent`（L550）、`onOpenInTimeline`（L661）对齐。这满足 `perfetto-ui-launch` spec 既有 Requirement（"Open in Perfetto UI SHALL load the trace through the official Perfetto UI"），无需改 spec 既有条目，仅在 delta 中以 scenario 固化验收。

### D7: 不带时间戳定位参数

原生模式打开不带 `visStart/visEnd` 时间窗参数（官方 UI RPC 模式下定位点语义由 UI 内部处理，v57.2 的 RPC 模式不消费 `visStart/visEnd`）。若未来需要从 insight 卡片跳原生引擎定位，另行探索。

## Risks / Trade-offs

- **[9001 被外部进程占用]** → 原生引擎 `server http` 绑定失败：进程启动但就绪探测超时，报 `TRACE_PROCESSOR_NOT_READY`/启动失败，错误经 `diagnosticError` 呈现。与现有 `SERVER_START_FAILED` 语义一致。
- **[用户已装 ui.perfetto.dev 缓存了同一 trace]** → UI 可能直接用缓存的 WASM 引擎而不提示切换。缓解：这是官方 UI 自身行为；文档/tooltip 引导用户确认对话框出现时选择 WebSocket。可在验收时实测确认提示是否出现（待验证点，不阻塞实现）。
- **[原生进程内存占用]** → 大 trace 在原生进程可占数 GB。缓解：`--idle-timeout 30m` + 失活/销毁即关；状态栏或按钮 busy 态让用户可感知。
- **[trace_processor 启动耗时]** → 大 trace 解析可能超过就绪探测的 10s 超时。缓解：就绪探测只验证 HTTP 端点可响应（trace 加载异步进行），v57.2 的 http 模式在文件解析完成前已可接受 RPC 连接；若实测发现必须等解析完，把超时放宽到 30s 并在 UI 显示"启动中"。
- **[v57.2 未实测 RPC 提示行为]** → 验收阶段用真实 trace 实测；若 UI 不弹提示，fallback 是用户在 UI 内手动选择数据源。不阻塞实现（机制上 9001 探测是官方文档明确行为）。
- **[.diagnostic 链路不受影响]** → `TraceAnalysisContexts` 用 `PrivatePortAllocator` 随机端口，与 9001 无交集，无回归风险。

## Migration Plan

1. 实现按钮 + server 类 + 生命周期清理（一次 PR）。
2. 手工验收：小 trace 走两条路径均可打开；大 trace（>2GB 或 >1GB）走原生路径可打开；模式切换（字节→原生→字节）即时生效；页面失活/关闭后 `ps` 确认无残留 `trace_processor_shell` 进程。
3. 回滚：revert PR 即可。原生路径是纯新增按钮，不改变现有字节路径行为（除 `onOpenViewer` 修复，属 bug 修复可独立回滚评估）。

## Open Questions

- ~~ui.perfetto.dev 对 9001 的探测提示在 pinned v57.2 下的确切表现~~ → 机制已由官方文档确认，具体对话框文案/时机在验收时实测，不阻塞。
- ~~`server http` 在文件解析完成前是否接受 RPC 连接~~ → 官方流程是"先启动 server 再打开 UI"，且 UI 连接后按需等待；实现按"HTTP 端点可响应即就绪"，验收时确认大 trace 场景。
