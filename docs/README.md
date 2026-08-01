# 项目文档索引

本目录只保留对产品和工程决策有长期价值的项目文档。Git 历史是逐次代码提交的权威记录，文档不再保存可由设计规格和提交历史还原的逐步实施脚本。

## 保留范围

### 需求记录

- [`requirements/layout-complexity-inspector-three-solutions-plan.md`](requirements/layout-complexity-inspector-three-solutions-plan.md) — 三种产品形态、共享能力和阶段规划。
- [`../desktop-viewer/docs/requirements/`](../desktop-viewer/docs/requirements/) — Desktop Viewer 延后需求和路线记录。
- [`../desktop-viewer/simpleperf-viewer/docs/requirements/`](../desktop-viewer/simpleperf-viewer/docs/requirements/) — Simpleperf 产品需求、开发计划、兼容矩阵和跟踪表。

### 设计文档

- [`../DESIGN.md`](../DESIGN.md) — 仓库级设计入口。
- [`../CONTEXT.md`](../CONTEXT.md) — AI 分析与源码定位领域语言。
- [`design/layoutinspectorv2-pro-comparison.md`](design/layoutinspectorv2-pro-comparison.md) — Layout Inspector 方案比较和设计依据。
- [`../desktop-viewer/docs/design/2026-08-01-ai-source-workspace-design.md`](../desktop-viewer/docs/design/2026-08-01-ai-source-workspace-design.md) — AI 分析、源码工作区与可信源码定位总体设计。
- [`../desktop-viewer/docs/design/`](../desktop-viewer/docs/design/) — Desktop Viewer、Layout Inspector 和架构变更设计。
- [`../desktop-viewer/simpleperf-viewer/docs/design/`](../desktop-viewer/simpleperf-viewer/docs/design/) — Simpleperf 产品、技术栈和 UI 设计。

### 架构文档与架构图

- [`adr/`](adr/) — 难以逆转、存在真实权衡的架构决策记录。
- [`../desktop-viewer/docs/architecture/`](../desktop-viewer/docs/architecture/) — 模块边界、协议和 Mermaid UI 布局图。
- 各模块的 `README.md` — 模块职责、构建入口和依赖边界。

### 提交、验证与发布记录

- [`records/feature-status.md`](records/feature-status.md) — 功能与关键提交对应关系。
- [`../desktop-viewer/simpleperf-viewer/docs/records/`](../desktop-viewer/simpleperf-viewer/docs/records/) — 性能验证、发布门禁和 release 记录。

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
4. 发布、性能验证和关键提交映射放入 `docs/records/`。
5. 不提交逐命令、逐步骤的临时实施脚本；实施过程由 Git commits 和 PR 记录承载。
6. 移动或删除文档时同步更新 README、源码注释和 Markdown 链接。
