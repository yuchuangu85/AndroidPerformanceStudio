# 待办事项

文档整理：2026-10-30。本文只保留未完成能力和验收门禁；本次整理不代表重新完成了真机、原生窗口或发布验收。

本清单汇总 Git 已跟踪的一方源码和文档中仍然有效的规划、延期能力、开放决策与发布门禁。第三方/生成产物、已被当前实现推翻的旧研究结论、历史执行计划中的未回填复选框，以及仅声明“不在当前范围”的设想不重复收录。

## 未完成能力

- [ ] [支持多设备选择](../desktop-viewer/README.md#current-scope) — 当前实时路径只面向一台已授权设备。
- [ ] [CPU Stack Chart 大数据性能优化](../desktop-viewer/simpleperf-viewer/docs/design/2026-10-29-stack-chart-performance-optimization-plan.md) — 移除全量逐块交互覆盖层，补视口索引、布局缓存和原生窗口性能验收；当前未实现。

Layout Inspector 已有 capture archive 持久化和相邻快照 Timeline Diff，不再列为“尚未实现”；能力边界见 [功能说明](features/layout-inspector.md)。
长期报告索引、配额和保留策略不同于单次归档，仍按[保留的待验收项](../desktop-viewer/docs/design/pending-acceptance.md#归档隐私与长期存储)核对，不因归档实现存在而自动核销。

## 性能工具扩展路线

来源：2026-09-20 性能工具规划讨论。以下能力应优先复用现有 `platform-perfetto`、`frame-profiler`、`startup-profiler`、`benchmark-regression`、`memory-profiler`、Android Agent 与统一证据模型，不新增彼此重复的独立 Profiler。

### LeakCanary 实时链路验收

- [ ] 使用真实可调试 App 完成注入、重装、Activity/Fragment 销毁、GC 保留和桌面实时事件端到端验收。

### 实验与跨域归因验收

- [ ] 用真实 Macrobenchmark Android 工程和真机完成冷/温/热、滚动/动画/页面切换的端到端验收。
- [ ] 用真实 Perfetto trace 验证调度、Binder、FrameTimeline 查询在目标 API/设备矩阵上的 schema 兼容性。
- [ ] 用真实 JankStats/FrameMetrics Agent 事件验证 Activity、Fragment、页面状态与 FrameTimeline 的时间关联。

### 运行时和专项证据验收

- [ ] 使用真实 Compose Compiler 版本矩阵验证 reports 文本/CSV 方言，并与运行时 inspector/JankStats 同步采集。
- [ ] 使用真实 SQLite workload 和文件 I/O 压力验证目标设备 ftrace/atrace 覆盖率。
- [ ] 使用真实 ART trace 验证不同 Android 版本的 GC、JIT、dex2oat 与 class-loading slice 命名。
- [ ] 使用具备 power rail 的设备完成 Thermal/DVFS/Power Rail 与 FrameTimeline 真机对齐验收。

### P2：后台与专项场景

- [ ] **Background Work Profiler** — 分析 WorkManager、JobScheduler、AlarmManager、BroadcastReceiver、后台恢复、Doze 和 App Standby 对启动及功耗的影响。
- [ ] **系统专项扩展** — 根据真实用户场景增加 Camera、Audio、Media Pipeline 等专项采集器；每个专项必须有独立协议、权限说明和证据完整性状态。
- [ ] **Network Agent Push 模式评估** — 旧优化总结中暂缓的推送方案尚未实施；当前仍使用轮询，不将其误写成已支持能力。

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
- [ ] 为候选节点切换补充更明确的键盘/鼠标滚轮提示；小面积优先仅是调试策略，不代表真实触摸分发。
- [ ] 在树中区分直接隐藏与受祖先隐藏影响；隐藏仅影响本地 Canvas 命中和 overlay，不改变原始快照或分析证据。

## AI 源码分析加固

统一来源：[AI 路线与缺口](ai/roadmap.md)。该文档维护 Build Evidence、跨平台凭据、100k 文件基准、远端取消、本地化、Simpleperf 接线及对话窗口的分阶段要求，不再保留第二份路线图。

## Simpleperf 后续能力验收

来源：[V0.2 待办池](../desktop-viewer/simpleperf-viewer/docs/requirements/development-plan.md#10-v02-待办池)与[产品路线图](../desktop-viewer/simpleperf-viewer/docs/design/product-design.md#9-产品路线图)。

- [ ] 在三平台加载真实 `libsimpleperf_report` 与 bridge，和 protobuf backend 对照同一 `perf.data`。
- [ ] 使用真实 Perfetto + Simpleperf 同步采集验证 sched/Binder/FrameTimeline 时钟映射与频率/GPU 对齐。
- [ ] 将 Heatmap 与 Differential FlameGraph 纳入真实百万 sample UI 性能和交互验收。
- [ ] 使用真实 CI runner、HTTP 符号服务器和外部规则包完成团队工作流验收。

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

## 原生界面与交付验收

- [ ] 完成[商业化 UI 待验收项](../desktop-viewer/docs/aps-commercial-ui-implementation/README.md#待验收)中的打包原生窗口、实际 1280×720 窗口、面板/导航联动、语言/主题/显示缩放、视觉参考及各平台交互。
- [ ] 验证 10k 节点、至少 10,001 帧的真实交互延迟和重组开销；保留 Layout、Frame、Startup 和图形上下文的真机闭环。
- [ ] 在真实 AGI 安装环境中验证启动、打开、Reveal/Relocate 和制品校验恢复；不把源码接线或截图当作外部工具运行证据。
- [ ] 验证 Startup 当前/上一次实验对比；非 TTID 指标保留描述性边界，声明的 Profile 来源不等于已在设备安装或验证 Profile。
- [ ] 按[保留的待验收项](../desktop-viewer/docs/design/pending-acceptance.md)完成传统 View 性能预算、API/ABI、归档/隐私、原生发布和共享平台验收；自动渲染、源码断言、局部测试或构建应用镜像均不替代这些门禁。
- [ ] 大数据验收分别测量冷显示、重复进入、连续交互和峰值内存；不能以投影耗时或样本数替代原生窗口交互延迟。
