# 待办事项

最后扫描：2026-09-21

本清单汇总 Git 已跟踪的一方源码和文档中仍然有效的规划、延期能力、开放决策与发布门禁。第三方/生成产物、已被当前实现推翻的旧研究结论、历史执行计划中的未回填复选框，以及仅声明“不在当前范围”的设想不重复收录。

## 已形成设计、待实施

- [ ] [支持多设备选择](../desktop-viewer/README.md#current-scope) — 当前实时路径只面向一台已授权设备。
- [ ] [支持报告持久化](../desktop-viewer/README.md#current-scope)。
- [ ] [支持时间线差异比较](../desktop-viewer/README.md#current-scope)。

## 性能工具扩展路线

来源：2026-09-20 性能工具规划讨论。以下能力应优先复用现有 `platform-perfetto`、`frame-profiler`、`startup-profiler`、`benchmark-regression`、`memory-profiler`、Android Agent 与统一证据模型，不新增彼此重复的独立 Profiler。

### LeakCanary 实时链路（2026-09-20）

以下实现已完成源码、协议、AAR 构建和桌面端接入；真机端到端验收仍单独保留为待办，不把本地构建证据当作设备运行证据。

- [x] Shark/HPROF 离线泄漏分析页面和引用链展示。
- [x] LeakCanary bridge AAR：ContentProvider 自动启动、Activity/Fragment 监听、弱引用观察和 Agent session。
- [x] ADB forward、token、轮询协议和桌面端实时事件页面。
- [x] Android 工程自动注入：复制 AAR/JAR、添加 debug 依赖、备份 Gradle 文件并避免重复注入。
- [x] AAR bundle 与 protocol JAR 构建任务。
- [ ] 使用真实可调试 App 完成注入、重装、Activity/Fragment 销毁、GC 保留和桌面实时事件端到端验收。

### P0 本轮实现进度（2026-09-21）

以下为已落地的核心实现；真实 Android 工程/真机验收仍按原 P0 项目保留。

- [x] Perfetto Diagnostics 增加 sched switch/waking、run queue、主线程阻塞、CPU contention、Binder 长尾/system_server/wait chain、Binder/FrameTimeline 对齐查询。
- [x] Frame Profiler 增加 Activity/Fragment/window/page/interaction/UI-state、JankStats、RenderThread、SurfaceFlinger 和 FrameTimeline 归因，并纳入 JSON 导出。
- [x] Benchmark CLI 与模型增加 Macrobenchmark 场景矩阵（cold/warm/hot、scroll/animation/page switch）、ART 编译模式、Baseline Profile 状态、trace/JSON/统计结果关联和 regression 对比。
- [x] Startup Profiler 将 sched_waking/run queue/Binder/main-thread/frame 证据按启动阶段做有界时间关联，并保留 Baseline Profile artifact 身份。
- [ ] 用真实 Macrobenchmark Android 工程和真机完成冷/温/热、滚动/动画/页面切换的端到端验收。
- [ ] 用真实 Perfetto trace 验证调度、Binder、FrameTimeline 查询在目标 API/设备矩阵上的 schema 兼容性。
- [ ] 用真实 JankStats/FrameMetrics Agent 事件验证 Activity、Fragment、页面状态与 FrameTimeline 的时间关联。

### P0：实验与跨域归因

- [x] **Macrobenchmark + Baseline Profile 实验台** — 在 `benchmark-regression` / `startup-profiler` 增加实验矩阵、编译模式、Profile 状态、trace/JSON/统计关联和前后对比。
- [x] **CPU 调度与线程竞争分析** — 在 `perfetto-viewer` 增加 `sched_switch`、`sched_waking`、run queue、线程唤醒延迟、主线程阻塞和 CPU contention 分析。
- [x] **Binder / IPC 性能分析** — 识别调用方、服务端、Binder 等待链、system_server 交互和 IPC 长尾，并提供 Binder–FrameTimeline 对齐查询。
- [x] **FrameTimeline + JankStats 联合分析** — 在现有 `frame-profiler` 增加 Activity、Fragment、页面、交互状态、RenderThread、SurfaceFlinger 与 Jank frame 的统一关联。

### P1：运行时和 UI 深度诊断

- [ ] **Compose Stability / Recomposition Profiler** — 解析 Compose Compiler stability/composable reports，展示不稳定参数、不可跳过 Composable、重组次数和跳过次数，并关联 Jank。
- [ ] **I/O 与 SQLite Profiler** — 增加文件读写、fsync、block I/O、page fault、SQLite query/transaction、WAL checkpoint 和数据库锁等待分析。
- [ ] **ART / GC / JIT / Class Loading Profiler** — 在启动和内存分析中增加 GC pause、allocation churn、JIT、dex2oat、类加载、验证和初始化阶段归因。
- [ ] **Thermal / DVFS / Power Rails 分析** — 在 `battery-profiler` 与 Perfetto 分析中加入温度、CPU/GPU 频率、thermal throttling、power rail 和持续性能窗口，并关联帧率/Jank。

### P2：后台与专项场景

- [ ] **Background Work Profiler** — 分析 WorkManager、JobScheduler、AlarmManager、BroadcastReceiver、后台恢复、Doze 和 App Standby 对启动及功耗的影响。
- [ ] **系统专项扩展** — 根据真实用户场景增加 Camera、Audio、Media Pipeline 等专项采集器；每个专项必须有独立协议、权限说明和证据完整性状态。

### 统一交付门禁

- [ ] 每个新工具都具备 `capture → protocol/agent → model → analysis → presentation → export` 的可复核链路。
- [ ] 每份结果记录设备、App、进程、版本、时间基准、采集配置、原始 artifact 和丢样/截断/降级状态。
- [ ] 实时 Agent 默认只服务 debug/profileable 构建；已安装 APK 不能假设可以无损注入，注入必须经过重建、签名和重新安装。
- [ ] 优先提供离线 fixture、真机采集、取消/清理、断连恢复、跨平台主机和大文件性能测试。
- [ ] 新功能不得把 Native、Java、GPU、FrameTimeline 等不同证据通道伪装成同一种指标；跨域关联必须保留来源和置信边界。

## Layout Inspector / Compose

- [ ] [完成 Full Compose Inspection 发布门禁并转为标准入口](../desktop-viewer/docs/design/layout-inspector/2026-08-08-compose-inspection-capability-parity-design.md#delivery-and-release-gate) — 补齐动态注入、ABI/Compose 版本矩阵、隐私/兼容/断连/清理、三主机和 Android Studio 同 fixture 对照；完成前继续使用内部开关。
- [ ] [实验性支持 Compose State Reads 与调用栈](../desktop-viewer/docs/design/layout-inspector/2026-08-08-compose-inspection-capability-parity-design.md#recomposition-observation)。
- [ ] [确认层级行隐藏按钮的最终位置](../DESIGN.md#open-questions) — disclosure 后或行尾。
- [ ] [确认隐藏的已选节点是否显示低透明虚线边框](../DESIGN.md#open-questions)。
- [ ] [确认手动刷新后隐藏状态是清空还是保留仍存在的 nodeId](../DESIGN.md#open-questions)。
- [ ] [为候选节点切换补充更明确的键盘/鼠标滚轮提示](design/layoutinspectorv2-pro-comparison.md#后续决策建议)。
- [ ] [在树中区分直接隐藏与受祖先隐藏影响](design/layoutinspectorv2-pro-comparison.md#后续决策建议)。

## AI 源码分析加固

来源：[AI analysis roadmap](../desktop-viewer/docs/requirements/ai-analysis-roadmap.md#follow-up-hardening)。

- [ ] 接入 Native Build ID、`llvm-symbolizer`、R8 `mapping.txt` 与 Gradle build-evidence。
- [ ] 实现 Windows Credential Manager 与 Linux Secret Service 凭据存储。
- [ ] 增加 100k 文件性能基准和支持取消的远端传输。
- [ ] 补齐所有 provider/error 状态的 Compose 本地化 UI 与 visual-golden 覆盖。

## Simpleperf 后续能力

来源：[V0.2 待办池](../desktop-viewer/simpleperf-viewer/docs/requirements/development-plan.md#10-v02-待办池)与[产品路线图](../desktop-viewer/simpleperf-viewer/docs/design/product-design.md#9-产品路线图)。

- [ ] CPU Sample Heatmap。
- [ ] Differential FlameGraph。
- [ ] Gecko Profile 导出。
- [ ] Folded Stacks 导出。
- [ ] PProf 导出。
- [ ] 接入 Perfetto Trace Processor，联合分析 sched、Binder 与 FrameTimeline。
- [ ] 接入 `libsimpleperf_report`。
- [ ] 线程池归一化分组，合并 Binder、AsyncTask、Coroutine 同类线程。
- [ ] 补齐 V0.3 系统诊断中的频率与 GPU 联合分析。
- [ ] 实施 V0.4 团队与自动化能力：批量采集、CI 对比、报告模板、符号服务器和规则市场。

## Simpleperf 性能验证

来源：[P0 性能 PoC 后续门禁](../desktop-viewer/simpleperf-viewer/docs/records/p0-performance-poc.md#4-后续门禁)。

- [ ] 在 Windows、Linux clean runner 运行同一性能任务并保留独立 JSON。
- [ ] 使用真实 Simpleperf protobuf Record 替换合成记录复测。
- [ ] 记录实际 Canvas 绘制、键盘、鼠标滚轮和上下文菜单的端到端延迟与掉帧。
- [ ] 使用真实百万 sample 会话复测数据库大小、导入事务恢复和查询计划。

## Simpleperf 发布门禁

来源：[V0.1 Release Checklist](../desktop-viewer/simpleperf-viewer/docs/records/release-checklist.md)。完成以下项目后，才可从 release candidate 晋级正式 V0.1。

- [ ] GitHub Actions 上传 DMG、MSI、DEB、RPM 与 portable artifacts。
- [ ] 在 profileable 真机完成 Start → Stop → pull → report。
- [ ] 真机取消后验证设备临时文件删除且本地日志保留。
- [ ] Windows 10/11 完成安装、启动、卸载、空格/中文路径、ADB 与截图验收。
- [ ] Ubuntu X11/Wayland 完成 DEB、RPM、portable 安装启动，以及 ADB/截图限制验收。
- [ ] macOS 13+ Apple Silicon 完成安装启动；若发布 Intel 版本则单列验证。
- [ ] 确认正式 DMG、MSI、Linux 包的签名、公证或仓库签名策略。
- [ ] 使用同一真实 Golden `perf.data` 人工比较 sample 数、线程、Top、CallTree 与 Flame 路径。
- [ ] 随 GitHub Release artifacts 发布各平台安装包 SHA-256。

## 扫描说明

- 一方代码中没有发现明确的 `TODO`、`FIXME`、`XXX`、`TBD`、`TODO()` 或 `NotImplemented` 债务标记。
- 旧研究文档中关于 `platform-adb`、共享 Perfetto 引擎和 FrameTimeline 路径未完成的描述已被当前实现覆盖，因此未收录。
