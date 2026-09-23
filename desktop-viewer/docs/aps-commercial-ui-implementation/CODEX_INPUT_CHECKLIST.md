# Android Performance Studio — Codex 输入资料检查清单

每个商业化 UI 任务开始前，至少提供/允许 Codex 读取：

- [ ] 完整仓库，不是单文件摘录
- [ ] `AGENTS.md`
- [ ] `CONTEXT.md`
- [ ] `DESIGN.md`
- [ ] `android-performance-studio-commercial-ui-implementation.md`
- [ ] 对应 feature 的 design / ADR
- [ ] 对应页面 `images/*.png` 视觉参考
- [ ] 当前运行版本的页面截图（建议）
- [ ] 对应 feature 的真实/脱敏 fixture
- [ ] 当前 build/test 命令或允许 Codex 从 Gradle 中自行确认
- [ ] 明确 Scope / Non-goals / Acceptance Criteria
- [ ] 明确禁止 fake metric、禁止破坏 Evidence/Capability 边界

执行前 Codex 必须报告：

```bash
git status --short
git branch --show-current
git rev-parse HEAD
```

设计图只作为视觉目标，源码/测试/ADR/证据模型是功能事实来源。
