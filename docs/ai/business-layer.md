# AI Business Layer

## 1. 目标

AI 不再只是各个 Profiler 内部调用的一个 gateway，而是 Android Performance Studio 的独立业务层。所有接入 AI 的业务都通过统一的 AI Entry Point 把业务上下文交给主 Agent；主 Agent 再根据任务选择子 Agent、Skill 或外部扩展，最后返回结构化结论或可下载的分析 Artifact。

```text
Business UI / Capture / Report / Finding
  → AI Entry Point
  → Context Builder + Policy Check
  → Main Agent
  → Sub-Agent / Built-in Skill / External Skill / External Agent
  → Evidence Validator + Artifact Publisher
  → Conversation Window + Markdown/HTML Artifact
```

这一层仍然必须遵守已有的性能证据、源码 Candidate、用户授权和 Analysis Session 边界。独立层不代表无限制访问。

## 2. 分层职责

### 2.1 AI Entry Point

每个业务注册一个自动接入口，描述：

```kotlin
data class AiEntryPointDescriptor(
    val businessId: String,
    val displayName: String,
    val profiler: ProfilerKind?,
    val contextKinds: Set<String>,
    val allowedActions: Set<String>,
    val defaultOutput: ArtifactFormat = ArtifactFormat.HTML,
)
```

Entry Point 负责：

- 从当前选择、报告、Capture Artifact 或业务错误中提取业务数据；
- 生成有界的 `BusinessContext`；
- 绑定设备、进程、Profiler Session、Evidence、Source Snapshot 和权限状态；
- 将上下文传给主 Agent，而不是由业务 UI 自己拼接 Prompt；
- 在业务页面、Finding、工具结果和报告页面提供“打开 AI 对话”入口。

任何业务自动接入 AI 都必须经过同一个 Context Builder 和 Policy Check，禁止业务绕过授权直接发送原始数据。

### 2.2 Main Agent

主 Agent 是 AI Business Layer 的唯一编排入口，负责理解问题、规划任务、选择子 Agent/Skill、校验权限和预算、合并结果、强制 Evidence/Candidate 引用，并发布 Markdown/HTML Artifact。

主 Agent 不直接执行任意脚本、不直接遍历文件系统、不直接修改工程。所有外部动作都必须经过注册的 Skill 或子 Agent capability。

### 2.3 Sub-Agent

子 Agent 是有明确输入输出契约的分析角色，例如：

- `FrameAnalysisAgent`：帧耗时、deadline miss、FrameTimeline 和 JankStats；
- `TraceAnalysisAgent`：调度、Binder、锁竞争、渲染和时间窗口关联；
- `SimpleperfAgent`：热点函数、调用树、火焰图和符号证据；
- `MemoryAgent`：堆对象、引用链、支配树和泄漏候选；
- `StartupAgent`：TTID、TTFD、启动阶段和 Baseline Profile；
- `BatteryAgent`：UID 资源、wakelock、alarm、job 和能量证据；
- `NetworkAgent`：请求阶段、失败、域名和 TLS 证据；
- `SourceEvidenceAgent`：源码候选、Build Evidence 和 stale 状态校验；
- `ReportAgent`：跨子 Agent 结果合并、冲突说明和报告生成。

子 Agent 必须声明输入 schema、输出 schema、可用 Evidence 类型、可调用 Skill、最大预算、是否允许网络和是否允许脚本。

### 2.4 Skill

Skill 是可复用的单一能力，不等同于自由 Prompt。每个 Skill 需要 manifest：

```kotlin
data class SkillManifest(
    val id: String,
    val version: String,
    val description: String,
    val inputSchema: String,
    val outputSchema: String,
    val permissions: Set<SkillPermission>,
    val supportedLanguages: Set<String> = emptySet(),
)
```

内置 Skill 示例：Evidence summary、Frame jank classification、Trace window query、Source candidate resolution、Build identity verification、Compare Analysis Sessions、Generate Markdown report 和 Generate HTML report。

### 2.5 External Agent / External Skill

外部 Agent 和 Skill 通过 manifest 注册，不允许通过任意 Prompt 隐式加载。注册信息至少包括 stable ID、版本、来源、schema、能力、权限、网络/源码/设备访问、超时、输出大小、签名/哈希或用户信任确认，以及失败和撤销状态。

外部扩展只能接收 Context Builder 授权的最小数据。外部 Agent 不能自行加载未声明的扩展，也不能提升权限。

### 2.6 Script Runtime

脚本是 Skill 的受控实现方式，不是主 Agent 的 shell 逃生口。首期支持 Python 3 和 JavaScript/TypeScript（默认 Node.js）。

脚本执行必须具备：

- 临时工作目录和只读输入挂载；
- CPU、内存、时长、输出字节和并发限制；
- 默认无网络；网络访问必须是 manifest 权限和用户授权；
- 禁止访问凭证、Keychain、任意宿主路径和未授权源码；
- stdout/stderr、退出码、运行时版本、输入哈希和产物哈希审计；
- 取消、超时、崩溃和部分输出状态；
- macOS、Linux、Windows 的运行时发现和能力差异显式展示。

默认不允许任意 `shell`、Gradle、ADB 或设备写操作。需要这些能力时必须注册专用 Skill，并附带独立的副作用确认。

### 2.7 Artifact Publisher

主 Agent 的最终输出统一为 Artifact：

- `HTML`：默认格式，适合应用内预览和浏览器打开；
- `Markdown`：适合版本控制、评审、Issue 或 PR 描述；
- 后续可扩展 JSON、CSV 等结构化格式。

Artifact 必须包含生成时间、模型、主 Agent/子 Agent/Skill 版本、原始 Analysis Session、Evidence/Candidate 引用、调用摘要、脚本产物索引、数据缺失和未验证假设。HTML 默认输出不允许嵌入凭证、原始 Prompt 或未授权完整源码。

## 3. 业务自动接入契约

每个业务实现一个 Context Provider：

```kotlin
interface AiBusinessContextProvider {
    val descriptor: AiEntryPointDescriptor

    suspend fun buildContext(request: AiEntryPointRequest): BusinessContext
}
```

`BusinessContext` 应包含业务类型、当前操作、选中对象、Capture Artifact/Profiler Session、结构化 Performance Evidence、Source Snapshot、Build Evidence、授权状态、能力限制和可用 Agent/Skill allowlist。

业务接入的最小验收条件：

1. 从业务入口可直接打开绑定上下文的 AI 对话窗口。
2. 自动调用主 Agent 使用同一份 `BusinessContext`，不重复拼接另一份 Prompt。
3. 上下文超限、未授权和证据不完整时可解释地阻止或降级。
4. 最终回答可追溯到业务数据和 Evidence ID。
5. 输出 HTML 默认可打开，Markdown 可导出。

## 4. 对话窗口与主 Agent

对话窗口是主 Agent 的用户前端：自动入口首次打开时创建或恢复绑定业务的 `AnalysisConversation`；主 Agent 可以调用子 Agent/Skill，但 UI 展示调用摘要、权限和状态；扩大范围、上传源码、联网或执行脚本都需要窗口内确认；结果可以继续追问、生成 HTML、导出 Markdown 或回到业务页面。

原有的 [AI 对话窗口设计](conversation-window.md)继续有效，但其中的 `AnalysisConversationGateway` 需要由 Main Agent Orchestrator 实现，而不是直接连接单次 OpenAI Gateway。
