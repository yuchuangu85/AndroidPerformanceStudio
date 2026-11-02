# AI Agent Platform TODO

状态：分阶段实施，创建于 2026-10-30；2026-10-31 已接入统一桌面并完成本地集成验证。

本轮覆盖全局对话窗口、AI 设置、首次启动安装、Layout/Simpleperf 迁移、11 类业务上下文、
Provider 子进程、Codex Responses adapter、通用独立 child/脚本图编排、Linux arm64 Python/Node
原生隔离和本机打包。真实 Provider 各一次尝试均超时；macOS/Windows 沙箱仍不合格，
hosted/真实签名/公证还受未发布源码、证书和受保护凭据环境阻塞。
逐项完成/部分完成状态和证据见 [状态统计](agent-platform-todo-status.md)。

## 目标

将 AI 从各个 Profiler 内部的可选 gateway，演进为一个独立的 AI Platform：

- 可接入 OpenAI、Codex、Claude 等 provider/SDK/本地 agent runtime；
- Agent 可以在声明的权限和预算内调用 provider、内置 Skill、外部 Skill 和脚本；
- 读取本机 Codex、Claude 等工具的**配置元信息和凭证引用**，但不复制、展示或写入
  明文凭证；
- 在 `~/.android-performance-studio/agents/` 和
  `~/.android-performance-studio/skills/` 中管理内置与用户导入的 Agent/Skill；
- 通过 AI 设置导入、启用、禁用和信任 Agent/Skill；
- 提供独立的全局 AI 对话窗口，可从任何 Profiler、报告、Finding 或源码页面调出；
- 所有分析都绑定业务上下文、Evidence、Source Candidate、权限和 Analysis Session，
  不退化成无边界的全项目聊天或任意 shell 执行器。

本规划扩展现有 [AI Business Layer](business-layer.md) 和
[AI 对话窗口](conversation-window.md)，不替代已有 `ai-core` provider-neutral contract。

## 当前基础

- `desktop-viewer/ai-core` 已提供 `AiAnalysisGateway`、`AnalysisRequest`、结构化结果校验、
  Credential Store 抽象和 Analysis Session 持久化。
- `source-workspace` 已提供 Local/GitHub/AOSP workspace、Snapshot、索引和确定性
  Source Candidate resolver。
- Layout Inspector source-aware client 和 Simpleperf 的
  `ProductionSimpleperfAiAnalysisClient` 已通过 DesktopAiCoordinator 接入统一 Main Agent。
- `docs/ai/business-layer.md` 已描述 Main Agent、Sub-Agent、Skill、脚本沙箱和 Artifact
  Publisher；独立运行时、Provider 发现、Agent/Skill 安装和全局 Chat 已有本地实现，完整能力
  及发布边界仍以逐项状态和验证证据为准。

## 目标模块

保留 `ai-core` 作为无 UI 的协议和 provider 基础，在 desktop composite build 中新增独立
`ai-platform` composite build。首期不把 Agent Runtime 代码塞回某个 Profiler module。

建议模块边界：

| 模块 | 职责 |
|---|---|
| `ai-platform-model` | Agent、Skill、Provider、Conversation、Permission、Tool Call、Approval、Artifact 和运行状态模型 |
| `ai-platform-runtime` | Main Agent 编排、Sub-Agent 调度、Skill 调用、预算、取消、latest-generation-wins |
| `ai-platform-providers` | Provider registry、能力发现、配置引用解析、统一 streaming/error contract |
| `ai-platform-provider-codex` | Codex SDK/app-server/CLI 的版本化 adapter；具体 transport 由 provider capability 决定 |
| `ai-platform-provider-claude` | Claude Agent/CLI SDK 的版本化 adapter；不让业务直接依赖 provider SDK 类型 |
| `ai-platform-registry` | Agent/Skill manifest、目录扫描、校验、信任、版本、启停和原子导入 |
| `ai-platform-script-runtime` | Python/Node 脚本执行、沙箱、资源限制、审计和 Artifact 输出 |
| `ai-platform-storage` | Conversation、Agent Run、Skill Run、Approval、Audit 和 Artifact 元数据持久化 |
| `ai-platform-presentation` | Compose 独立 Chat 窗口、Agent/Skill 管理、Provider 状态和审批 UI |
| `ai-platform-app` | Desktop 组装、全局入口、业务 Context Provider 注册和设置接线 |

若首期模块数量过大，允许先合并为 `ai-platform-runtime`、`ai-platform-registry`、
`ai-platform-providers`、`ai-platform-presentation` 四个模块，但 API ownership 不得合并
回具体 Profiler。

## Provider 设计

### 统一接口

业务和 Agent Runtime 只依赖统一接口，不直接依赖 Codex/Claude SDK：

```kotlin
interface AiProviderDriver {
    val descriptor: AiProviderDescriptor

    suspend fun discover(): ProviderAvailability

    suspend fun run(request: ProviderRunRequest): Flow<ProviderEvent>
}
```

`ProviderRunRequest` 必须包含 provider、model/profile、Agent identity、Analysis Session、
bounded input、tool permissions、timeout、max output bytes 和 cancellation token。

`ProviderEvent` 至少区分：started、text delta、structured result、tool call、approval
required、usage、retryable failure、permanent failure、cancelled、completed。

### Codex / Claude 接入

- provider adapter 优先使用可验证的官方 SDK/app-server/agent runtime；SDK 不适合直接
  嵌入 JVM 时，使用受控本地 adapter process 或 CLI protocol，不把 provider-specific
  类型泄露到 `ai-platform-model`；
- adapter 启动时记录 provider、runtime version、capabilities、executable identity 和
  config source，但不记录 token、cookie、完整 environment 或 prompt 中的秘密；
- SDK、app-server、CLI 和直接 HTTP 的能力差异显式建模，例如 streaming、tool call、
  approval、session resume、model discovery 和 local-only mode；
- provider 缺失、版本不兼容、配置损坏、凭证不可用和权限不足必须成为可见的
  `ProviderAvailability` 状态，不静默切换到另一个 provider；
- Main Agent 只能通过 provider registry 选择 provider，不能由模型生成任意命令或任意
  provider name。

### 本地配置读取

新增 `LocalProviderConfigResolver`：

- 按平台和 provider 版本查找 Codex、Claude 等本地配置位置；路径必须是 resolver 的
  可验证结果，不能把未经验证的默认路径当作事实；
- 读取 provider、登录状态、默认 model/profile、runtime path、配置版本和 capability
  等**元信息**；
- 对 API key、OAuth refresh token、cookie、session secret 只保存
  `CredentialReference`（来源、key 名、是否可解析），不读入 UI 文本、不写入日志、不
  复制到 `.android-performance-studio`；
- 配置文件不存在、权限不足、JSON/TOML 损坏和版本未知都保留为明确状态；
- AI 设置允许用户选择“使用已发现的本机配置”或显式配置 provider，但不会自动上传
  本地配置文件内容。

## Agent / Skill 文件系统

### 目录

默认根目录：`~/.android-performance-studio/`

```text
~/.android-performance-studio/
├── agents/
│   ├── builtin/<agent-id>/
│   │   ├── manifest.json
│   │   ├── prompt.md
│   │   └── scripts/
│   └── imported/<agent-id>/<version>/
├── skills/
│   ├── builtin/<skill-id>/
│   │   ├── manifest.json
│   │   ├── SKILL.md
│   │   └── scripts/
│   └── imported/<skill-id>/<version>/
├── conversations/
├── artifacts/
├── provider-state/
└── audit/
```

内置 Agent/Skill 由应用资源安装到 `builtin` 目录，采用版本化、校验和、原子替换；应用
升级不得删除用户导入内容。用户通过 AI 设置导入到 `imported`，导入过程必须是：临时目录
写入 → manifest 校验 → 脚本清单校验 → hash/签名或用户信任确认 → 原子移动。

### Manifest

Agent manifest 至少声明：

- stable id、name、version、description、entrypoint、input/output schema；
- 支持的 Profiler/Context 类型和可引用 Evidence 类型；
- 可调用 Provider、Skill、Tool 的 allowlist；
- filesystem scope、network、source snippets、device、script、artifact 权限；
- CPU、内存、时长、并发、输出字节和 child-agent 深度预算；
- origin（builtin/imported）、content hash、签名状态、trust status、enabled status。

Skill manifest 至少声明：

- stable id、version、SKILL.md、script entrypoints 和 input/output schema；
- runtime（none/python/node）、依赖和版本要求；
- 所需 permissions、资源限制、可消费的 Evidence、可生成的 Artifact；
- network/device/source write 等副作用声明；
- origin、hash、trust、enabled 和撤销状态。

脚本文件是受 manifest 约束的实现，不是主 Agent 的 shell 逃生口。脚本默认：无网络、
无凭证、无任意宿主路径、只读输入挂载、临时工作目录、有限 CPU/内存/时间/输出；stdout、
stderr、退出码、运行时版本、输入 hash 和输出 hash 进入审计。

## Agent Runtime

### Main Agent

Main Agent 是唯一编排入口，负责：

1. 接收 `BusinessContext` 和用户问题；
2. 通过 Policy Check 生成 bounded payload；
3. 选择注册且 enabled 的 Sub-Agent/Skill/Provider；
4. 请求审批后执行 provider/tool/script；
5. 校验 Evidence/Candidate 引用、schema、预算和结果来源；
6. 保存 Analysis Session、Agent Run、Skill Run、Approval 和 Artifact provenance；
7. 将结构化结果交给全局对话窗口。

Main Agent 不直接遍历文件系统、不执行任意脚本、不调用未注册 provider、不自行生成
source path/line、不绕过 source workspace 授权。

### Agent 可调用 Codex / Claude

Agent 的 provider 调用必须是结构化 `ProviderRunRequest`：

- Agent identity、Skill identity、provider/model、session/generation；
- Evidence/Candidate allowlist；
- prompt/payload policy version；
- tool permissions、approval token、timeout、max output 和 cancellation；
- redacted request metadata 和 response provenance。

Agent 不能读取 API key，也不能自行读取 `~/.codex`、`~/.claude` 中的秘密文件；它只能
请求 `ProviderBroker` 使用已授权的 `CredentialReference`。

## 独立全局 AI 对话窗口

### 入口

在 `DesktopAppMainPage` 的全局 Window/Navigation Host 中注册独立
`AiConversationWindowHost`，支持：

- Navigation Rail / app menu / 全局快捷键调出；
- 从任意 Profiler、Finding、报告、Source Viewer 和错误面板打开；
- `Open AI with current context` 传递当前 `BusinessContext`，而不是页面自己拼 prompt；
- 没有业务上下文时显示 context unavailable，不自动开启全库自由聊天；
- 独立窗口生命周期不依赖当前 Profiler 页面，但上下文 snapshot/session 必须不可变绑定。

### UI

窗口包含：

- conversation/session selector；
- 当前 Context、Evidence、Source Candidate、权限和 provider/model 状态；
- streaming message、tool/skill/agent invocation timeline；
- approval dialog、取消、重试、重新分析和 stale 状态；
- Finding、evidence 引用、source candidate 打开和 HTML/Markdown Artifact 入口；
- provider、agent、skill 管理入口应在 AI 设置中，不和对话消息混成隐式操作。

### 状态和恢复

- Queued、Preparing、AwaitingApproval、Running、Succeeded、Cancelled、RetryableFailure、
  PermanentFailure、Interrupted；
- 应用退出时 non-terminal run 标记为 interrupted，不自动重放 provider/script/tool；
- 取消必须传播到 provider process、SDK stream、child agent 和 script runtime；
- late response 不得覆盖新 generation 或已取消 turn；
- 对话历史保存结构化消息、引用和 provenance，不保存凭证、完整源码或无界原始响应。

## AI 设置

AI 设置拆分为：

1. **Provider**：已发现 runtime/config、availability、model/profile、credential reference、
   endpoint、网络模式和测试连接；
2. **Agents**：内置/导入、版本、来源、hash/signature/trust、capabilities、权限、启用/禁用、
   删除 imported 版本；
3. **Skills**：同上，额外展示 runtime、脚本入口、资源预算和副作用；
4. **Execution Policy**：默认 provider、默认 Agent、网络/源码/脚本/设备权限、审批策略、
   并发和预算；
5. **Conversation**：历史、清理、session retention、artifact 目录和审计日志。

所有导入、启用、禁用、信任、撤销、provider 配置变更和执行审批进入本地 audit；敏感值
只能显示 masked state。

## 实施阶段

### P0：契约和边界

- [x] 冻结 `ai-platform-model`、Agent/Skill manifest、ProviderEvent、Permission、Approval、
  Conversation 和 Audit schema。
- [x] 将现有 `ai-core` 标记为底层 contract/provider adapter；禁止业务直接依赖 Codex/Claude
  SDK 类型。
- [x] 冻结 `~/.android-performance-studio` 目录、origin/trust/版本/atomic install 规则。
- [x] 写 ADR：provider credentials 不复制、脚本默认沙箱、全局 Chat 是唯一主入口。

### P1：独立模块和文件系统

- [x] 创建 `desktop-viewer/ai-platform` composite build 及 registry/storage/runtime 基础模块。
- [x] 首次启动安装 builtin Agent/Skill；校验、扫描、禁用、撤销和 imported 原子导入。
- [x] 为 malformed manifest、duplicate id、path traversal、symlink、hash mismatch、超限脚本
  和 unknown runtime 增加测试。

### P2：Provider Broker

- [ ] 实现 provider discovery、config metadata resolver、credential reference 和 capability
  matrix。
- [x] 接入现有 OpenAI gateway。
- [ ] 增加 Codex adapter 和 Claude adapter；按 SDK/app-server/CLI 实际能力选择 transport，
  不硬编码某个版本的外部类型。
- [x] 增加 fake provider、streaming、timeout、cancel、approval、retry、child process 和
  secret-redaction 测试。

### P3：Agent / Skill Runtime

- [x] 实现 Main Agent、Sub-Agent registry、Skill registry、provider broker、budget ledger、
  latest-generation-wins 和 Audit。
- [x] 内置 `SimpleperfAgent`、`SourceEvidenceAgent`、`ReportAgent` 和只读 Evidence summary
  / source candidate / report Artifact Skill。
- [ ] 实现 Python/Node sandbox，先支持只读、无网络、无设备写和 bounded Artifact；副作用
  Skill 单独审批。

### P4：全局对话窗口和业务接线

- [x] 在 `DesktopAppMainPage` 接入独立 `AiConversationWindowHost`，从所有页面提供统一入口。
- [x] Context Provider registry 接入 Layout、Simpleperf、Memory、Trace、Frame、Startup、
  Battery、Network、Benchmark 和 Source Workspace。
- [x] 将现有 Layout AI 和 Simpleperf AI 接到统一 Main Agent，不保留页面专用编排分支。
- [ ] 支持对话恢复、child session、Finding/evidence/source 跳转和 Artifact preview。

### P5：发布和验收

- [ ] macOS/Linux/Windows 创建目录、权限、provider discovery、script runtime 和 process
  termination 矩阵。
- [x] 英文/中文、浅色/深色、1280x720/900x720 的 AI 设置和全局 Chat Compose 测试/截图。
- [ ] secret egress、path traversal、untrusted prompt、script escape、provider timeout、
  stale context 和 cancellation 对抗测试。
- [ ] clean profile、已有 `.android-performance-studio` 升级、损坏配置、导入/回滚、无 provider
  和无 credential 的真实桌面冒烟。
- [ ] hosted build、签名/打包和跨平台 provider runtime 资格验证；静态测试不得替代这些门禁。

## 验收标准

- 全新安装会创建 `agents/` 和 `skills/`，并安装可校验的 builtin Agent/Skill。
- AI 设置可以导入合法 Agent/Skill，拒绝非法 manifest、越界路径、未声明脚本和 hash mismatch；
  原子导入失败不留下半成品。
- Agent 能通过 Provider Broker 调用已配置的 Codex/Claude/OpenAI provider；provider 不可用或
  配置缺失时显示明确 unavailable，不静默降级。
- 任意页面可以打开同一个独立 AI 对话窗口，并携带当前业务 context；没有 context 时不会假装
  具备当前证据。
- Agent/Skill/Provider/script 的调用、权限、审批、输入输出摘要和取消状态可审计。
- 脚本默认无网络、无凭证、无任意宿主路径和无设备写；超时/取消会终止子进程和子 Agent。
- AI finding 只能引用已发送 Evidence/Candidate ID，源码跳转只能进入已授权 Source Snapshot。
- 应用退出、provider 崩溃或对话取消不会自动重放未完成 run，不会让旧结果覆盖新 session。

## 明确不做

- 不把 Codex/Claude/OpenAI 的 API key、OAuth token、cookie 或原始配置复制到
  `.android-performance-studio`。
- 不允许 Agent 通过自然语言生成任意 shell、Gradle、ADB、Python 或 Node 命令。
- 不把导入 Agent/Skill 视为天然可信；导入、启用、脚本执行和网络访问必须有 manifest 和权限。
- 不把任意本地目录、源码全文、完整 profile 或 ignored 文件默认发送给 provider。
- 不让业务页面分别实现自己的 Chat、provider selector、prompt 构造和结果校验。

## 当前未完成状态

本文件包含规划和已验证的基础实现。已勾选项仅代表对应契约或本地 JVM 验证已完成；
未勾选项可能部分实现，不能将目录/接口存在等同于业务、UI 或发布可用。
在实现和对应测试、打包、跨平台、安全验收完成前，`docs/ai/README.md` 中必须继续把这些
能力标记为 planned/partial，而不是 available。
