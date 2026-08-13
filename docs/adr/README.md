# Architecture Decision Records

本目录记录 AI 分析与源码工作区方案中难以逆转、存在真实权衡且仅从代码难以理解的决策。

- `0001`–`0003`：确定性定位、不可变源码快照和远程缓存。
- `0004`–`0007`：AI Provider、隐私、双置信度和 Source Viewer。
- `0008`–`0011`：构建证据、AOSP、结构化索引和模块边界。
- `0012`–`0015`：统一分析模型、显式触发、归档和 GitHub 范围。
- `0016`–`0019`：全局工作区、增量索引、存储和 Native 符号化。
- `0020`–`0021`：分析范围与结构化、可版本化的 AI 结果。
- `0031`–`0033`：通过固定 Trace Processor SQL 解析 Winscope，以原生 Compose 工作区交付，并将固定版本的上游查看器作为可选浏览器路径打包。

总体方案见
[`../../desktop-viewer/docs/design/2026-08-01-ai-source-workspace-design.md`](../../desktop-viewer/docs/design/2026-08-01-ai-source-workspace-design.md)。

Winscope 方案见
[`../../desktop-viewer/docs/design/2026-08-12-winscope-workspace-design.md`](../../desktop-viewer/docs/design/2026-08-12-winscope-workspace-design.md)。
