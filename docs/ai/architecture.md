# AI 总体架构与接口

## 1. 核心边界

AI Business Layer 是独立于 Profiler 的业务编排层。Profiler 提供业务入口和 Context Provider，AI 层负责主 Agent 编排、子 Agent/Skill 选择、脚本策略和 Artifact 输出；`ai-core` 的 provider gateway 是底层适配器，不是业务编排器。

AI 分析的可信链路是：

```text
Profiler
  → Evidence Adapter
  → Source Resolver
  → User Preflight
  → AI Gateway
  → Schema/ID Validation
  → Analysis Session / Finding
  → Source Viewer
```

AI 只能解释请求中已有的 `PerformanceEvidence` 和 `AiSourceCandidate`。模型不得自行读取文件系统、生成未经 resolver 证明的路径/行号，或把源码注释当作指令。

## 2. 现有公共接口

### `ai-core`

`com.androidperformancestudio.ai.AnalysisContracts` 当前提供：

- `ProfilerKind`: `LAYOUT_INSPECTOR`、`SIMPLEPERF`。
- `AnalysisScopeKind`: `CURRENT_SELECTION`、`REPORT_SUMMARY`。
- `PerformanceEvidence`：Evidence ID、类型、摘要和结构化 payload。
- `AiSourceCandidate`：相对路径、符号、置信度、理由、源码片段和哈希。
- `AnalysisRequest`：session、profiler、scope、evidence、candidates、prompt/policy 版本。
- `AnalysisFinding`：severity、解释、建议、analysis confidence、Evidence/Candidate 引用。
- `AnalysisSession`：状态、模型、provider、snapshot/build evidence 引用和父 Session。
- `AiAnalysisGateway`：provider-neutral 的单次结构化分析入口。

实现位置：[AnalysisContracts.kt](../../desktop-viewer/ai-core/src/main/kotlin/com/androidperformancestudio/ai/AnalysisContracts.kt)。

### `source-workspace`

源码层提供：

- Local、GitHub、AOSP provider；
- 不可变 Source Snapshot 和 content hash；
- 结构化索引与 `SourceResolver`；
- `EXACT`、`PROBABLE`、`WEAK` resolution confidence；
- per-workspace `allowAiSourceUpload` 授权；
- Build Evidence Bundle 数据模型；
- 只读、可验证的 Source Viewer 内容读取。

实现位置：[SourceWorkspaceModels.kt](../../desktop-viewer/source-workspace/src/main/kotlin/com/androidperformancestudio/source/SourceWorkspaceModels.kt) 和 [source-workspace README](../../desktop-viewer/source-workspace/README.md)。

## 3. 目标 AI Business Layer

目标层由以下组件组成：

- `AiEntryPointRegistry`：登记每个业务的自动 AI 接入口和上下文能力；
- `BusinessContextBuilder`：将业务选择、报告、Capture Artifact、Evidence、Source Snapshot 和权限组装成有界 Context；
- `MainAgentOrchestrator`：理解问题、拆解任务、选择子 Agent/Skill、合并结果和发布 Artifact；
- `SubAgentRegistry`：管理内置和外部子 Agent 的 manifest、schema、capability 和限制；
- `SkillRegistry`：管理内置和外部 Skill，所有调用必须经过权限和预算检查；
- `ScriptRuntime`：在沙箱中运行 Python/JavaScript/TypeScript Skill；
- `ArtifactPublisher`：生成默认 HTML 及可选 Markdown；
- `ConversationStore`：保存绑定 Analysis Session 的 conversation/turn 关系和结构化引用；
- `PolicyAudit`：记录授权、工具调用、脚本运行、输入输出哈希、取消和失败。

业务调用链：

```text
Business UI / automatic hook
  → AiEntryPointRegistry
  → BusinessContextBuilder
  → MainAgentOrchestrator
  → SubAgent / Skill / External Extension / ScriptRuntime
  → Evidence Validator
  → ArtifactPublisher + ConversationStore
```

## 4. 当前桌面接线状态

- Layout Inspector 通过 `SourceAwareLayoutAiAnalysisClient` 接入 `ai-core`、Local resolver、Analysis Session 和 OpenAI gateway。
- Simpleperf 有 `SimpleperfAiAnalysisClient`、evidence 提取和结果抽屉，但统一桌面目前传入空 client，不能算完成的产品能力。
- Layout AI 请求当前将 `buildEvidenceBundleIds` 写为空列表，因此 Build Evidence 仍是待完成的可信链路。
- AI 入口常量当前为可见状态，但总体设计要求内部 Feature Flag 默认隐藏；发布策略需要先统一。
- Layout Inspector MCP server 提供设备、View hierarchy 和源码定位工具。它是独立的本地桥接能力，不等于 AI Gateway，也不提供任意代码读写。

## 5. 对话窗口的接口提案

对话窗口不应直接把 `AiAnalysisGateway.analyze()` 改成无边界聊天接口。建议增加一个建立在不可变 Analysis Session 之上的 conversation layer：

```kotlin
@JvmInline
value class AnalysisConversationId(val value: String)

@JvmInline
value class AnalysisTurnId(val value: String)

data class AnalysisConversation(
    val id: AnalysisConversationId,
    val baseSessionId: AnalysisSessionId,
    val profiler: ProfilerKind,
    val scope: AnalysisScope,
    val status: ConversationStatus,
    val createdAt: Instant,
)

data class AnalysisTurn(
    val id: AnalysisTurnId,
    val conversationId: AnalysisConversationId,
    val parentTurnId: AnalysisTurnId?,
    val userQuestion: String,
    val evidenceIds: List<String>,
    val candidateIds: List<String>,
    val status: ConversationTurnStatus,
    val answer: StructuredAnalysisAnswer?,
)

data class ConversationRequest(
    val conversationId: AnalysisConversationId,
    val parentTurnId: AnalysisTurnId?,
    val question: String,
    val context: ConversationContext,
)

interface AnalysisConversationGateway {
    suspend fun open(sessionId: AnalysisSessionId): AnalysisConversation
    suspend fun send(request: ConversationRequest): AnalysisTurn
    suspend fun cancel(turnId: AnalysisTurnId)
}
```

`ConversationContext` 必须由已有 Session 构造，包含允许使用的 Evidence/Candidate ID、当前 Finding、用户授权状态、payload budget 和 source snapshot。每个 turn 都要通过同一套 Schema、ID allowlist、大小和隐私校验。

## 6. 允许的对话动作

首版只支持只读、证据绑定动作：

- 解释当前 Finding；
- 比较两个已存在的 Finding 或 Evidence；
- 请求展开已授权的 Evidence/Candidate；
- 请求缩小或切换分析范围；
- 打开已验证的 Source Candidate；
- 创建新的 Analysis Session 重新分析。

不支持：

- 自主读取整个源码工作区；
- 未经预检上传源码；
- 自动执行脚本、修改文件、生成 Patch、Commit 或 PR；
- 脱离 Profiler Session 的全局聊天；
- 未经用户确认的多步工具调用。
