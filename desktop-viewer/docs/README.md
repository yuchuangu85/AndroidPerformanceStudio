# Desktop Viewer 文档索引

## Current Product

- [`../../DESIGN.md`](../../DESIGN.md) — 当前桌面交互、共享设计规则和验收边界。
- [`../../docs/features/README.md`](../../docs/features/README.md) — 各功能当前的设计、实现入口、使用方式与限制。
- [`aps-commercial-ui-implementation/README.md`](aps-commercial-ui-implementation/README.md) — 商业化 UI 的共享规范及逐页规格。

## Requirements

- [`../../docs/ai/README.md`](../../docs/ai/README.md) — AI 当前能力、业务层、源码授权边界与路线图的统一入口。
- [`design/pending-acceptance.md`](design/pending-acceptance.md) — 从旧设计保留的性能、兼容、隐私和发布待验收项；文档清理不代表这些门禁已通过。

## Architecture

- [`architecture/DEVELOPMENT.md`](architecture/DEVELOPMENT.md) — 工具链与版本来源、构建入口、模块边界、安全约束和兼容策略。
- [`architecture/PROTOCOL.md`](architecture/PROTOCOL.md) — Layout Report Protocol 版本与兼容契约。
- [`architecture/ui-components-and-theme.md`](architecture/ui-components-and-theme.md) — 公共控件、主题令牌与胶囊单选控件用法。
- [`../layout-inspector/compose-inspection-host/AGENT_BUNDLE.md`](../layout-inspector/compose-inspection-host/AGENT_BUNDLE.md) — 当前仓库内 Agent Bundle 构建、校验与启用方式，无需完整 AOSP/Bazel 工作区。
- [`adr/`](adr/) — Profiler 公共产物、无状态设备目标及共享 Perfetto 平台决策。
- [`../../docs/adr/README.md`](../../docs/adr/README.md) — 源码工作区、Compose 检查与各功能证据边界决策。

## Design

- [`design/rust-profiler-migration.md`](design/rust-profiler-migration.md) - CPU、HPROF、HAR 的 Rust 分阶段迁移、旧实现保留、shadow 验证及待验收门禁。
- [`design/2026-08-01-ai-source-workspace-design.md`](design/2026-08-01-ai-source-workspace-design.md) — AI 分析、Local/GitHub/AOSP 源码工作区和可信源码定位总体方案。
- [`design/layout-inspector/2026-08-08-compose-inspection-capability-parity-design.md`](design/layout-inspector/2026-08-08-compose-inspection-capability-parity-design.md) — Compose 完整能力、资源预算、隐私及认证门禁。
- [`design/layout-inspector/2026-08-09-compose-inspection-artifact-acquisition-research.md`](design/layout-inspector/2026-08-09-compose-inspection-artifact-acquisition-research.md) — 固定上游 Agent 构建、精确版本 Inspector 获取和制品分发安全边界。

已被当前设计、功能文档、ADR 或代码取代的单功能方案与迁移记录不再保留副本；历史实现和提交时间查询 Git 历史。待验收项在取得对应证据前不得视为完成。
