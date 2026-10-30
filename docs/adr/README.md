# Architecture Decision Records

本目录记录 AI 分析、源码工作区、Compose 检查及各功能证据边界中难以逆转、存在真实权衡且仅从代码难以理解的决策。

- `0001`–`0003`：确定性定位、不可变源码快照和远程缓存。
- `0004`–`0007`：AI Provider、隐私、双置信度和 Source Viewer。
- `0008`–`0011`：构建证据、AOSP、结构化索引和模块边界。
- `0012`–`0015`：统一分析模型、显式触发、归档和 GitHub 范围。
- `0016`–`0019`：全局工作区、增量索引、存储和 Native 符号化。
- `0020`–`0021`：分析范围与结构化、可版本化的 AI 结果。
- `0022`–`0026`：Compose Inspector 复用、sidecar 兼容、精确制品、运行时授权和安全导出。
- `0027`–`0030`：基准回归不确定性、Jank/帧证据边界、网络最小化和会话时钟映射。
- `0031`–`0034`：Winscope 的原生 Compose 工作区与可选浏览器查看器路径（已被 `0035` 取代）。
- `0035`：移除 Winscope 工作区与上游查看器 submodule：删除 `third_party/aosp-winscope`、`desktop-viewer/winscope` 复合构建、浏览器查看器打包与页面入口。
- `0036`：独立 AI Business Layer、主 Agent、子 Agent、Skill、外部扩展和沙箱脚本运行时（提案）。

总体方案见
[`../../desktop-viewer/docs/design/2026-08-01-ai-source-workspace-design.md`](../../desktop-viewer/docs/design/2026-08-01-ai-source-workspace-design.md)。

Winscope 不再是产品功能；移除边界与原因见
[`0035-remove-the-winscope-workspace-and-upstream-viewer.md`](0035-remove-the-winscope-workspace-and-upstream-viewer.md)。
已被替代的 ADR 仅保留决策依据，不表示可以恢复旧构建或页面入口。
