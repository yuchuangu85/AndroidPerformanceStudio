# 实时捕获开发 — 2026-11-11

## 目标与工作约束

修复 `build/device-functional-audit-20261110/REPORT.md` 中的三个发现，实现 Startup/Battery/Network 实时捕获，并将 Native Heap、ART 采样和 Bitmap 捕获接入各自的实际分析工作区。

用户明确要求仅进行开发：**不要执行测试、设备探测、GUI 验收或基准测试**。格式化、编译和静态源码审查属于开发检查，不是运行时验收。
保留脏工作树、现有修订版本和无关的并行工作。不提交/推送。

## 工作拆分与所有权

- [x] CPU：远程 shell/PID 封装、用户空间应用范围采样、已验证的录制器完成、取消和有边界的产物。
- [x] Inspector：修正负 double 格式化，避免重复符号。
- [x] Settings：修正原生单选控件中过时的 choice-chip 边框断言；不要重新设计页面。
- [x] Startup：显式目标批准、Cold/Warm/Hot 启动采集、从平台派生的 TTID/TTFD 和观测状态边界、结果发布。
- [x] Battery：绑定目标/UID/启动 ID 的只读快照、轮询、停止/取消、保守的累计差异和报告发布。不执行全局重置。
- [x] Network：现有 APS Agent v2 传输、显式同意、范围限定的转发、有边界的增量轮询、隐私最小化和清理。
- [x] Native Heap：真实 heapprofd 跟踪采集、有边界的 Trace Processor 投影、导入/导出和专用页面集成。
- [x] ART：真实 `com.android.art` 堆采样、有边界的投影和页面集成；不会根据采样证据声称存在精确分配或完整保留信息。
- [x] Bitmap：Android bitmap heap dump 捕获、隔离解码/投影、图像/摘要页面集成和导出；区分普通 HPROF 发现。
- [x] 共享集成：标题栏设备/目标变更、操作取消、过期结果保护、配套本地化、菜单、文档和锁定离线构建。

Startup、Battery 和 Network 是相互独立的委派实现工作流。主工作流负责共享的 `import.rs`/`shell.rs`、资源、CPU 修复、高级 Memory、集成以及最终源码/编译审计。
委派工作流不得运行测试或修改共享文件。

## 完成证据

每个请求的捕获操作都必须调用真实的有边界传输，保留设备/进程身份，只取消和清理自有资源，将真实结果发布到现有功能页面，并在没有伪造数据的情况下报告不支持/权限错误。
仅仅启用按钮不算完成。

在上述所有项目完成实现并经过检查之前，保持此目标处于活动状态。
已勾选项目记录的是源码实现，而不是测试执行或运行时验收。开发完成不构成真实设备或发布验收；根据用户“不测试”的要求，该证据被有意延期。

## 集成审查

- Native/ART 和 FrameTimeline 现在共享自有的 Perfetto 录制器。它会保留私有远程目录，使用唯一的分离会话键，限制时长和跟踪字节数，并在拉取/删除前检查停止是否完成。不使用 `sh -c` 参数拼接、宽范围进程终止或全局跟踪停止。
  未知的停止/清理状态会保留精确路径和恢复诊断信息。
- Native/ART 还会在录制前/期间/之后重新检查冻结的启动 ID 和进程启动时钟 tick。数据集会在整个查询和导出期间拥有本地录制文件；过期/失败的发布只释放自有文件。
- 高级内存视图绝不会将较旧的 Java heap 导出为其 AI 证据。这些视图的 AI 导出会明确不可用，而不是错误归因到另一个数据集。Bitmap 进度也会区分主机取消和可能独立完成的设备 heap dump。
- 共享 Memory 视图菜单回归源码使用真实的 `Dashboard` 变体，而不是不存在的 `Overview` 变体。原生单选控件保留现有主题；移除过时的设置 choice-chip 断言，但不改变 UI 设计。

## 明确的剩余边界

- 本次开发跟进没有执行新增测试、设备探测、GUI 验收、基准测试、安装、提交或推送。回归测试源码仅完成编译。原始设备报告是修复前的历史观测，不是修复后的验收。
- Network 的 Kotlin/Android Agent 当前无法通过其常规 Gradle 构建：AGP `com.android.library` 9.4.1 在插件配置阶段因 `KotlinAndroidTarget` / 缺少 `com.android.build.gradle.api.BaseVariant` 而失败，发生在 Kotlin 源码编译之前。普通日志和 stacktrace 日志均位于 `desktop-viewer/build/network-agent-compile/`。未修改依赖版本或构建清单来掩盖该阻塞。
- 没有采样分配的 Native/ART 跟踪仍明确为空；非空传输输出不是获得 profiling 权限的证据。样本不是精确分配、完整覆盖、GC 或保留信息的证明。
- Bitmap 图像需要受支持的 Android bitmap dump 扩展；普通 HPROF bitmap 实例不会被提升为图像证据。PNG 提取过程隔离且有边界，并会显示被省略的图像。
- Battery 的导出是最小化、绑定 UID 的证据包，不是整台设备的原始历史记录。Historian、合格基线、已保存会话/整包导入以及其他分别不可用的能力不会被静默启用。

## 最终开发验证

最新的主工作流日志位于仓库根目录的
`build/live-capture-development-20261111/`：

- `workspace-check.log`：`cargo check --locked --offline --workspace
  --all-targets` 通过。该命令会编译回归源码，但不会运行它们。
- `build.log`：`aps-app` 和 `aps-memory-worker` 的锁定离线构建通过；两个二进制文件都未启动。
- `scoped-clippy.log`：`aps-app`、`aps-capture`、`aps-layout-inspector`、`aps-memory`、`aps-network`、`aps-battery`、`aps-startup`、`aps-perfetto` 和 `aps-ui-foundation` 的 all-targets/no-deps Clippy 通过。
  现有警告仍然存在；不宣称 lint 已完全干净。
- 范围内格式化、配套资源键/占位符和 `git diff --check` 通过。`exit-codes.txt` 和 `summary.json` 保留精确的证据边界。
- 完整工作区 Clippy 也已运行，但不算通过：未修改的 `aps-benchmark/src/util.rs:135` 测试字面量触发 `clippy::approx_constant`。失败日志是 `workspace-clippy-preexisting-failure.log`。
- 完整工作区格式化不算通过：未修改的 `aps-adb/src/model.rs` 和 `aps-app/src/studio.rs` 包含已有格式差异。它们被有意保持不变；`workspace-format.log` 标识了这两个文件。

以上实现清单中的所有项目均已在源码开发范围内完成。设备/GUI/测试/发布验收以及 Android Agent 构建阻塞仍明确开放；它们不会被转换为通过结果。
