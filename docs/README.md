# 项目文档索引

本目录只保留当前维护所需的功能说明、有效设计、协议与架构决策、未完成需求和验收门禁。实现过程与历史版本以 Git commits、tags 和发布产物为准，不再维护重复的实施日志或静态“完成状态”快照。

## 保留范围

### 功能介绍

- [`features/README.md`](features/README.md) — 当前各性能工具的功能介绍、设计、实现、使用和证据边界。

### 需求记录

- [`TODO.md`](TODO.md) — 当前未完成的规划、延期能力、开放决策与发布门禁。
- [`ai/README.md`](ai/README.md) — AI 分析、源码工作区和 AI 对话窗口的统一规划入口。
- [`ai/roadmap.md`](ai/roadmap.md) — AI 能力缺口和分阶段验收顺序。
- [`../desktop-viewer/simpleperf-viewer/docs/README.md`](../desktop-viewer/simpleperf-viewer/docs/README.md) — CPU Profiler 的有效需求、性能优化、用户指南和发布门禁。

### 设计文档

- [`../DESIGN.md`](../DESIGN.md) — 仓库级设计入口。
- [`../CONTEXT.md`](../CONTEXT.md) — AI 分析与源码定位领域语言。
- [`design/2026-09-17-memory-profiler-hprof-viewer-design.md`](design/2026-09-17-memory-profiler-hprof-viewer-design.md) — Android Studio 风格离线 HPROF Viewer 的能力边界、交互、实现阶段与验收设计。
- [`ai/architecture.md`](ai/architecture.md) — AI 跨模块接口、证据边界和对话窗口契约。
- [`ai/conversation-window.md`](ai/conversation-window.md) — 绑定 Analysis Session 的 AI 对话窗口设计。
- [`ai/roadmap.md`](ai/roadmap.md) — AI 当前能力、缺口和分阶段验收顺序。
- [`../desktop-viewer/docs/design/2026-08-01-ai-source-workspace-design.md`](../desktop-viewer/docs/design/2026-08-01-ai-source-workspace-design.md) — AI 分析、源码工作区与可信源码定位总体设计。
- [`../desktop-viewer/docs/design/layout-inspector/2026-08-08-compose-inspection-capability-parity-design.md`](../desktop-viewer/docs/design/layout-inspector/2026-08-08-compose-inspection-capability-parity-design.md) — 完整 Compose 检查的能力、隐私、性能与发布门禁。
- [`../desktop-viewer/layout-inspector/compose-inspection-host/AGENT_BUNDLE.md`](../desktop-viewer/layout-inspector/compose-inspection-host/AGENT_BUNDLE.md) — 当前仓内 Agent 构建、校验和使用方式。
- [`../desktop-viewer/docs/README.md`](../desktop-viewer/docs/README.md) — Desktop Viewer 的开发、协议和有效详细设计入口。
- [`../desktop-viewer/docs/aps-commercial-ui-implementation/README.md`](../desktop-viewer/docs/aps-commercial-ui-implementation/README.md) — 页面设计规格、视觉参考和未完成验收；不是功能已全部实现的声明。
- [`../desktop-viewer/docs/design/pending-acceptance.md`](../desktop-viewer/docs/design/pending-acceptance.md) — 从旧设计承接的有效验收目标与兼容要求。
- [`../desktop-viewer/simpleperf-viewer/docs/design/`](../desktop-viewer/simpleperf-viewer/docs/design/) — Simpleperf 产品、技术栈和 UI 设计。

### 架构文档与架构图

- [`adr/`](adr/) — 难以逆转、存在真实权衡的架构决策记录。
- [`../desktop-viewer/docs/architecture/DEVELOPMENT.md`](../desktop-viewer/docs/architecture/DEVELOPMENT.md) — 当前构建入口、模块边界和安全约束。
- [`../desktop-viewer/docs/architecture/PROTOCOL.md`](../desktop-viewer/docs/architecture/PROTOCOL.md) — Layout Report 协议。
- [`../desktop-viewer/docs/adr/`](../desktop-viewer/docs/adr/) — 共享平台层、采集产物和功能域边界的决策。
- 各模块的 `README.md` — 模块职责、构建入口和依赖边界。

### 性能验证与发布门禁

- [`../desktop-viewer/simpleperf-viewer/docs/records/p0-performance-poc.md`](../desktop-viewer/simpleperf-viewer/docs/records/p0-performance-poc.md) — 已有性能数据、方法和适用边界。
- [`../desktop-viewer/simpleperf-viewer/docs/records/release-checklist.md`](../desktop-viewer/simpleperf-viewer/docs/records/release-checklist.md) — 尚需完成的安装包、真机和跨平台门禁。

## 必要例外

以下文件不属于决策文档，但仍是运行或合规所需，不能作为冗余文档删除：

- `docs-user/`、`docs-user-zh/` — 桌面应用打包和运行时打开的双语用户文档。
- `third_party/**/README.md`、`SOURCE.md` — 第三方来源、版本和许可证说明。
- 测试资源目录中的 `README.md` — fixture 格式和来源说明。
- 用户手册与故障排查 — 面向发布物使用者的必要文档。

## 维护规则

1. 新需求放入对应模块的 `docs/requirements/`。
2. 设计决策、交互规格和架构变更放入 `docs/design/`。
3. 模块图、协议图和 UI/数据流图放入 `docs/architecture/`。
4. 有可复用数据和方法的性能验证、发布验收放入对应模块的 `docs/records/`；不能把某次测试通过写成持续有效的发布状态。
5. 不提交逐命令实施脚本、临时日志、重复完成清单或未采用技术栈的选型对比；实施过程由 Git commits 和 PR 记录承载。
6. 删除文档前把独有的未完成需求和验收项迁移到有效入口，再同步修复 Markdown、源码注释和索引引用。
7. ADR 记录决策原因；已被替代的 ADR 保留并明确标注后继决策，不作为当前实现指南。
