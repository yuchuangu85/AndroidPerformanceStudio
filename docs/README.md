# 项目文档索引

本目录只保留当前维护所需的功能说明、有效设计、协议与架构决策、未完成需求和验收门禁。实现过程与历史版本以 Git commits、tags 和发布产物为准，不再维护重复的实施日志或静态“完成状态”快照。

## 保留范围

### 功能介绍

- [`features/README.md`](features/README.md) — 当前各性能工具的功能介绍、设计、实现、使用和证据边界。

### 需求记录

- [`TODO.md`](TODO.md) — 当前未完成的规划、延期能力、开放决策与发布门禁。
- [`ai/README.md`](ai/README.md) — AI 分析、源码工作区和 AI 对话窗口的统一规划入口。
- [无界面 CLI 与 MCP](ai/cli/README.md) — 文件链接、设备采集、结构化证据与可选 AI 报告。
- [`ai/mcp/README.md`](ai/mcp/README.md) — MCP 总览、业务模块索引和已实现接口的使用与输入输出契约。
- [`ai/roadmap.md`](ai/roadmap.md) — AI 能力缺口和分阶段验收顺序。
- [`../rust-desktop/TODO.md`](../rust-desktop/TODO.md) — Rust 桌面重写的 Agent 化性能工作流待办。

### 设计文档

- [`../DESIGN.md`](../DESIGN.md) — 仓库级设计入口。
- [`../CONTEXT.md`](../CONTEXT.md) — AI 分析与源码定位领域语言。
- [`design/2026-09-17-memory-profiler-hprof-viewer-design.md`](design/2026-09-17-memory-profiler-hprof-viewer-design.md) — Android Studio 风格离线 HPROF Viewer 的能力边界、交互、实现阶段与验收设计。
- [`ai/architecture.md`](ai/architecture.md) — AI 跨模块接口、证据边界和对话窗口契约。
- [`ai/conversation-window.md`](ai/conversation-window.md) — 绑定 Analysis Session 的 AI 对话窗口设计。
- [`ai/roadmap.md`](ai/roadmap.md) — AI 当前能力、缺口和分阶段验收顺序。
- [`../rust-desktop/MIGRATION.md`](../rust-desktop/MIGRATION.md) — Rust + GPUI Kit 迁移执行记录和未完成验收门槛。
- [`../rust-desktop/FUNCTIONAL_PARITY.md`](../rust-desktop/FUNCTIONAL_PARITY.md) — Rust 实现与 Compose 行为对等的当前审计及剩余工作流。
- [`../rust-desktop/README.md`](../rust-desktop/README.md) — 当前 Rust 桌面实现、构建和验证入口。

### 架构文档与架构图

- [`adr/`](adr/) — 难以逆转、存在真实权衡的架构决策记录。
- [`../rust-desktop/README.md`](../rust-desktop/README.md) — 当前桌面实现和 crate 边界入口。
- [`../rust-desktop/FUNCTIONAL_PARITY.md`](../rust-desktop/FUNCTIONAL_PARITY.md) — 对等范围、证据原则与切换门禁。
- 各模块的 `README.md` — 模块职责、构建入口和依赖边界。

### 性能验证与发布门禁

- [`records/memory/hprof-reconstruction-comparison-2026-11-18.html`](records/memory/hprof-reconstruction-comparison-2026-11-18.html) — 2026-11-18 同文件 HPROF 三方实测、Rust 重构收益、正确性证据与验收边界；离线暗色 HTML 报告。
- [`../rust-desktop/FUNCTIONAL_PARITY.md`](../rust-desktop/FUNCTIONAL_PARITY.md) — 仍开放的 Compose 对等、真实数据、原生交互和发布验收门禁。
- [`../rust-desktop/TODO.md`](../rust-desktop/TODO.md) — Rust 性能 Agent 工作流的未完成需求。

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
