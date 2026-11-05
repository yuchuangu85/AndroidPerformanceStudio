## 1. 修复"Open in Perfetto UI"接线

- [x] 1.1 `PerfettoMainPage.kt`：`onOpenViewer` 在 `openTrace(traceFile, activeArtifact)` 后补调 `openInPerfettoUi(traceFile, null)`
- [x] 1.2 presentation 测试：补/改断言——点击工作区"Open in Perfetto UI"按钮触发 `onOpenViewer`，app 层接线使服务启动 + 浏览器打开（对齐 `TraceAnalyzerWorkspaceTest` 现有 onOpenViewer 断言与 `perfetto-ui-launch` spec 的 workspace button scenario）

## 2. NativeTraceProcessorServer

- [x] 2.1 `perfetto-ui-server` 新增 `NativeTraceProcessorServer`：构造入参 `TraceProcessorTool`/`HostProcessRunner`/startup probe；`start(traceFile)` 以 `server http --ip-address 127.0.0.1 --port 9001 --idle-timeout 30m <trace>` 启动进程并轮询就绪（10s 超时 → `TRACE_PROCESSOR_NOT_READY`），复用/参照 `TraceAnalysisContext.start()` 模式；`stop()` 杀进程并清理
- [x] 2.2 错误路径：tool resolution 失败、绑定失败/就绪超时、`Desktop.browse` 失败分别映射既有错误码（`TRACE_PROCESSOR_*`、`BROWSER_OPEN_FAILED`），失败时确保进程已停止
- [x] 2.3 `NativeTraceProcessorServer` 单元测试：启动成功路径、就绪超时（fake runner/clock）、stop 幂等、`openTrace` 前置校验（trace 文件不存在）

## 3. 模式互斥与页面接线

- [x] 3.1 `PerfettoMainPage.kt`：新增 `openInNativeEngine` 动作——先 `uiServer.stop()`，resolve 工具，`nativeServer.start(traceFile)`，成功后 `Desktop.browse("https://ui.perfetto.dev")`；错误写 `diagnosticError`
- [x] 3.2 生命周期：`onDispose` 与 `isActive` 失活分支加 `nativeServer.stop()`（与 `uiServer.stop()` 同批；注释说明原生进程内存大、失活即回收的差异）
- [x] 3.3 确认反向路径不回归：native 占 9001 时 `launchInUi` 走 `SERVER_START_FAILED` 错误呈现（现有逻辑，加测试或手工验证记录）

## 4. UI 按钮

- [x] 4.1 `TraceAnalyzerWorkspace.kt`：`TraceAnalyzerWorkspaceActions` 新增 `onOpenNativeEngine`；artifact 按钮行新增"Open with native engine"按钮（testTag `open-native-engine`，`enabled = artifact.viewerAvailable`）
- [x] 4.2 strings.xml（中英）新增按钮文案与适用场景说明（tooltip/副文案"适合大文件"）
- [x] 4.3 presentation 测试：按钮渲染、enabled 条件、点击触发 `onOpenNativeEngine`

## 5. 验收

- [x] 5.1 小 trace：两条路径（官方 UI `?url=`、native engine RPC）均能打开并正确渲染
- [ ] 5.2 大 trace（≥1GB）：native engine 路径可打开；确认 ui.perfetto.dev 弹出加速器选择对话框（如未弹，记录实际行为并评估 fallback 提示文案）
- [x] 5.3 模式切换：字节 → native → 字节（含互斥修复后来回快速切换，浏览器每次均弹出、最终服务与最后点击一致）
- [ ] 5.4 `ps` 确认页面关闭/失活后无残留 `trace_processor_shell` 进程；空闲 30 分钟后进程自行退出（可临时调小 idle-timeout 验证）
