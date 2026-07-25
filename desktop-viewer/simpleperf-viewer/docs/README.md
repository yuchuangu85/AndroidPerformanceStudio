# Simpleperf Viewer 文档索引

## Requirements

- [`requirements/requirements.md`](requirements/requirements.md) — V0.1 产品需求和验收标准。
- [`requirements/development-plan.md`](requirements/development-plan.md) — 版本范围、WBS 和阶段状态。
- [`requirements/firefox-flame-graph-compatibility.md`](requirements/firefox-flame-graph-compatibility.md) — Flame Graph 兼容矩阵。
- `requirements/simpleperf_client_development_tracker.xlsx` — 原始需求跟踪表。

## Design

- [`design/product-design.md`](design/product-design.md) — 产品与交互设计。
- [`design/technology-stack-research.md`](design/technology-stack-research.md) — 技术栈调研和 ADR。
- `design/*-design.md` — Native Profiler、采集配置和 Firefox 交互对齐设计。

## Records

- [`records/p0-performance-poc.md`](records/p0-performance-poc.md) — 性能验证方法、结果和适用边界。
- [`records/release-checklist.md`](records/release-checklist.md) — 发布门禁。
- [`records/release-notes-v0.1-rc.md`](records/release-notes-v0.1-rc.md) — V0.1 RC 能力与验证记录。
- `poc-results/` — 性能验证原始 JSON 结果。

## User documentation

- [`user/user-guide.md`](user/user-guide.md) — 安装、采集、分析和导出说明。
- [`user/troubleshooting.md`](user/troubleshooting.md) — ADB、权限、工具链和平台问题排查。

逐步实施脚本已经删除。实现过程以 Git 历史为准，长期行为约束由 requirements 和 design 文档维护。
