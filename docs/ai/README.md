# AI 文档

这里是 Android Performance Studio AI 能力的统一文档入口。AI 能力由独立的 AI Business Layer 统一编排，必须建立在可复核的性能证据、确定性源码候选和明确授权之上；对话窗口是业务上下文中的主 Agent 前端，不是无边界的全项目聊天。

## 当前边界

- Layout Inspector 已有完整的证据绑定分析入口：当前选择或报告摘要、源码候选、预检、OpenAI Responses、结构化 Finding 和源码跳转。
- Simpleperf 已有独立的 AI evidence/client 接口和结果抽屉，但统一桌面当前仍传入 `aiAnalysisClient = null`，尚未完成产品接线。
- `ai-core` 已提供 provider-neutral contract、OpenAI adapter、structured-output 校验、Credential Store 和 Analysis Session 持久化。
- `source-workspace` 已提供 Local、GitHub 和 AOSP provider、Snapshot、索引、确定性 resolver 和只读 Source Viewer；当前 Layout AI 默认只绑定 Local workspace。
- 当前 AI provider 仍是 OpenAI Responses；第二 provider、Agent Runtime、MCP 工具调用、RAG、长期记忆和自动改码均不属于首个闭环。
- 目标架构新增 Main Agent、Sub-Agent、Skill、External Agent/Skill、Python/JavaScript 沙箱脚本运行时和 HTML/Markdown Artifact Publisher；这些是 AI Business Layer 的后续实现目标，不应误写成当前已完成能力。

## 文档地图

- [总体架构与接口](architecture.md) — 现有模块、数据契约、源码授权边界和对话窗口接口提案。
- [AI Business Layer](business-layer.md) — 主 Agent、子 Agent、Skill、外部扩展、脚本运行时、自动业务入口和 Artifact 输出。
- [AI 对话窗口](conversation-window.md) — 以不可变 Analysis Session 为上下文的对话产品、交互、状态和安全规则。
- [路线图与缺口](roadmap.md) — 已实现、部分实现、待补充功能及验收顺序。

## 历史设计与决策

以下文档保留为详细设计和不可逆决策的来源，`docs/ai/` 负责提供统一入口和当前状态解释：

- [AI 分析与源码工作区总体设计](../../desktop-viewer/docs/design/2026-08-01-ai-source-workspace-design.md)
- [AI analysis roadmap](../../desktop-viewer/docs/requirements/ai-analysis-roadmap.md)
- [ADR-0001：确定性源码定位优先于 AI 排序](../adr/0001-deterministic-source-resolution-before-ai-ranking.md)
- [ADR-0004：provider-neutral AI boundary，OpenAI first](../adr/0004-provider-neutral-ai-boundary-with-openai-first.md)
- [ADR-0005：最小化并授权发送到云端 AI 的源码](../adr/0005-minimize-and-authorize-source-sent-to-cloud-ai.md)
- [ADR-0012：通用分析信封与 Profiler Evidence Adapter](../adr/0012-use-a-common-analysis-envelope-with-profiler-evidence-adapters.md)
- [ADR-0013：云端分析必须由用户显式触发](../adr/0013-require-explicit-user-trigger-for-cloud-analysis.md)
- [ADR-0014：归档保存引用，不保存源码和凭证](../adr/0014-archive-analysis-references-without-source-or-credentials.md)
- [ADR-0020：分析范围显式且默认当前选择](../adr/0020-make-analysis-scope-explicit-and-selection-first.md)
- [ADR-0021：版本化结构化分析，不做自由聊天](../adr/0021-use-versioned-structured-analysis-instead-of-free-form-chat.md)
- [ADR-0036：独立 AI Business Layer](../adr/0036-separate-ai-business-layer-with-agents-skills-and-sandboxed-scripts.md)

## 维护规则

1. AI 产品范围、路线和对话窗口需求写入本目录。
2. 不可逆的边界和安全决策写入 `docs/adr/`，并从本目录链接。
3. Profiler 专属 evidence adapter 和 UI 细节仍放在对应模块；本目录只定义跨模块契约和用户可见边界。
4. 文档中的“已实现”必须能在当前接口、接线或测试中找到证据；仅有模型或接口骨架的能力标记为“部分实现”。
5. AI Business Layer 的外部 Agent、Skill 和脚本能力必须先有 manifest、权限、资源限制和审计设计，再进入业务接入路线.
