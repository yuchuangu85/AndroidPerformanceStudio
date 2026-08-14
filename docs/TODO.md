# 待办事项

最后扫描：2026-08-14

本清单汇总 Git 已跟踪的一方源码和文档中仍然有效的规划、延期能力、开放决策与发布门禁。第三方/生成产物、已被当前实现推翻的旧研究结论、历史执行计划中的未回填复选框，以及仅声明“不在当前范围”的设想不重复收录。

## 已形成设计、待实施

- [ ] [将 AOSP Winscope 改为源码驱动的增量构建](../desktop-viewer/docs/design/2026-08-14-aosp-winscope-source-build-design.md) — 固定上游源码版本，以源码为事实来源，由 Gradle 增量构建并校验发布资源。
- [ ] [开发 Android Studio 插件](records/feature-status.md#规划未完成能力) — 当前目录仍是规划占位，没有可构建插件。
- [ ] [开发 Web UI 与 App 内 HTTP Server](records/feature-status.md#规划未完成能力) — 当前目录仍是规划占位，没有可运行 Web 前端或服务端。
- [ ] [支持多设备选择](../desktop-viewer/README.md#current-scope) — 当前实时路径只面向一台已授权设备。
- [ ] [支持报告持久化](../desktop-viewer/README.md#current-scope)。
- [ ] [支持时间线差异比较](../desktop-viewer/README.md#current-scope)。

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
- `docs/superpowers/plans/` 中的未勾选步骤属于历史执行记录；对应功能已由当前源码、测试或状态记录证明完成，因此未复制为新待办。
- 旧研究文档中关于 `platform-adb`、共享 Perfetto 引擎和 FrameTimeline 路径未完成的描述已被当前实现覆盖，因此未收录。
