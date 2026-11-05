## Why

打开大体积 trace 时，现有"Open in Perfetto UI"路径（浏览器拉全量字节 → WASM 引擎解析）会撞上浏览器内存墙（典型 ~2GB 上限，trace 运行时表示通常还要膨胀 2-4 倍）。`trace_processor_shell server http` 原生进程能用满本机内存和原生性能，ui.perfetto.dev 会自动探测 `127.0.0.1:9001` 的 RPC 端点并提示用户切换到原生加速器。同时，现有"Open in Perfetto UI"按钮的 handler（`onOpenViewer`）接线错误：只重新激活了当前 trace（no-op），从未启动 trace-serving 服务或打开浏览器，按钮点击无反应，违反 `perfetto-ui-launch` spec 的现有 Requirement。

## What Changes

- 新增"Open with native engine"按钮（Trace Analyzer 工作区，与现有"Open in Perfetto UI"并列），点击后：
  - 停止正在运行的 `PerfettoUiServer`（字节服务），释放 9001 端口——两个模式互斥占用 9001，显式接管。
  - 通过 `TraceProcessorToolResolver` 解析 `trace_processor_shell`，以 `server http --ip-address 127.0.0.1 --port 9001 <trace>` 启动原生 RPC 服务进程（沿用代码库现有 `server http` 现代语法，不用旧版 `--httpd` shorthand；9001 是 ui.perfetto.dev CSP 唯一允许的本地源，`PrivatePortAllocator` 的随机端口在此不可用）。
  - 就绪探测通过后打开系统浏览器到 `https://ui.perfetto.dev`（不带 `?url=`），UI 自动探测 RPC 端点并提示用户选择 WebSocket 原生引擎。
  - 进程带 `--idle-timeout 30m` 兜底，页面停用/失活时随 `onDispose`/`isActive` 清理关闭。
- 修复"Open in Perfetto UI"按钮接线：`onOpenViewer` 在 `openTrace(...)` 之后补调 `openInPerfettoUi(traceFile, null)`，与 `onOpenRecent`/`onOpenInTimeline` 对齐，满足 `perfetto-ui-launch` spec 既有 Requirement。
- 明确两种打开模式的语义分工：官方 UI 路径适合常规体积（字节流式拉取、IndexedDB 缓存），原生引擎路径适合大文件（trace 留在原生进程内存，浏览器只传查询结果）。
- 不改动 APS 智能洞察卡片的"Open in timeline"置灰逻辑（已确认现状，暂不处理）。
- 不改动诊断查询链路（`TraceAnalysisContexts` 私有端口 warm context 保持独立；"诊断复用原生 9001 实例"留作未来优化）。

## Capabilities

### New Capabilities
_(无)_

### Modified Capabilities
- `perfetto-ui-launch`: 新增"Open with native engine"动作的需求（启动原生 trace_processor RPC 服务、与字节服务互斥接管 9001、浏览器打开官方 UI 靠 RPC 自动发现）；同步收紧既有"Open in Perfetto UI"动作的验收（工作区按钮必须真正启动服务并打开浏览器，修复接线缺失）。

## Impact

- `desktop-viewer/perfetto-viewer/perfetto-app/.../PerfettoMainPage.kt`：修复 `onOpenViewer`；新增 native engine 启动/停止逻辑与按钮接线；`onDispose`/`isActive` 清理扩展。
- `desktop-viewer/perfetto-viewer/perfetto-ui-server/.../PerfettoUiServer.kt`：可能新增原生引擎服务类（复用 resolver + `HostProcessRunner` + 就绪探测模式），或在该模块新增 `NativeTraceProcessorServer`。
- `desktop-viewer/perfetto-viewer/perfetto-presentation/.../TraceAnalyzerWorkspace.kt`：新增"Open with native engine"按钮（`TraceAnalyzerWorkspaceActions` 新增回调）。
- strings.xml（中英）新增按钮文案。
- 复用：`platform-perfetto` 的 `TraceProcessorToolResolver`、`HostProcessRunner`、就绪探测模式（`TraceAnalysisContext.start()` 的轮询逻辑）。
- 测试：presentation 按钮接线测试、app 层模式互斥/生命周期测试。
