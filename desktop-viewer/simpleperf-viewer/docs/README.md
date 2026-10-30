# Simpleperf Viewer 文档索引

## Requirements

- [`requirements/requirements.md`](requirements/requirements.md) — V0.1 产品需求和验收标准。
- [`requirements/development-plan.md`](requirements/development-plan.md) — 版本范围、WBS 和阶段状态。
- [`requirements/firefox-flame-graph-compatibility.md`](requirements/firefox-flame-graph-compatibility.md) — Flame Graph 兼容矩阵、交互契约与待验收条件。
- [原始需求跟踪表](requirements/simpleperf_client_development_tracker.xlsx) — 保留需求追踪，不作为当前验收证明。

## Design

- [`design/product-design.md`](design/product-design.md) — 产品与交互契约，含当前报告工作区。
- [`design/technology-stack-research.md`](design/technology-stack-research.md) — 当前技术栈与运行时约束，保留原路径以兼容引用。
- [`design/2026-07-14-firefox-profiler-native-parity-design.md`](design/2026-07-14-firefox-profiler-native-parity-design.md) — 原生能力对齐的范围和分阶段路线；Approved design 不代表全部实现或验收。
- [`design/2026-10-29-stack-chart-performance-optimization-plan.md`](design/2026-10-29-stack-chart-performance-optimization-plan.md) — Stack Chart 大数据性能优化方案，**未实现**。

## Records

- [`records/p0-performance-poc.md`](records/p0-performance-poc.md) — 性能验证方法、结果和适用边界。
- [`records/release-checklist.md`](records/release-checklist.md) — 发布门禁。
- [`records/release-notes-v0.1-rc.md`](records/release-notes-v0.1-rc.md) — V0.1 RC 能力与验证记录。
- [P0 原始 JSON](poc-results/p0-performance-macos-arm64.json)、[Firefox Flame Graph 原始 JSON](poc-results/firefox-flame-graph-macos-arm64.json) — 环境相关的性能证据，不代表所有平台或原生交互均已验收。

## User documentation

- [`user/user-guide.md`](user/user-guide.md) — 安装、采集、分析和导出说明。
- [`user/troubleshooting.md`](user/troubleshooting.md) — ADB、权限、工具链和平台问题排查。

实现过程以 Git 历史为准；已被实现和当前契约覆盖的 UI 设计稿不再保留。长期行为约束由 requirements 和 design 文档维护，未完成路线、性能方案和发布门禁继续保留。代码或测试存在，不等于已完成原生交互、硬件或跨平台验收。
