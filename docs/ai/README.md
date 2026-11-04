# AI 文档

这里是 Android Performance Studio AI 能力的统一文档入口。AI 能力由独立的 AI Business Layer 统一编排，必须建立在可复核的性能证据、确定性源码候选和明确授权之上；对话窗口是业务上下文中的主 Agent 前端，不是无边界的全项目聊天。

## 当前实现

以下按当前源码和测试定义说明能力，不把接线、测试存在或本地假 Provider 验证等同于真实服务、跨平台或发布验收。新测试总数与执行结果只维护在 [TODO 状态统计](agent-platform-todo-status.md)，不在入口文档复制易失效的精确数字。

- `ai-core` 提供 provider-neutral analysis contract、OpenAI Responses client/gateway、结构化输出校验、Credential Store 和 Analysis Session 持久化；不依赖平台层或 Compose。
- `ai-platform` 已包含 model、registry、storage、providers、runtime、app、presentation、script-runtime、provider-claude、provider-codex。中立契约、包信任、结果与审计存储、Provider/process 边界、编排、桌面生命周期和 UI 分开归属，详见 [模块说明](../../desktop-viewer/ai-platform/README.md)。
- 桌面已调用 `AiPlatformRuntime.open()`，初始化私有目录、独占 profile、安装内置包、注册 Provider、恢复未完成记录为 interrupted。启动、恢复和只读发现不会自动发起分析；关闭时先取消并等待所属工作，再释放存储与锁。
- `DesktopAppMainPage` 已接入全局 Chat，即 `AiConversationWindowHost`，有导航栏、菜单和快捷键入口。窗口提供上下文绑定、问题输入、预检与显式确认、取消/重试、Finding/Evidence/Source Candidate、Agent/Skill 时间线和只读历史。没有有效业务上下文时不可发送，不是自由聊天。
- 统一 AI 设置保留 Provider/Agent/Skill/Policy 四个分类：Provider 是原“连接”页，管理 Codex、Claude 的 endpoint/model 和 Credential Store，原独立 Provider 选择与默认模型页已删除；其他分类管理扩展导入/信任/启用/撤销/版本和 Artifact/历史策略。本地 CLI 发现只读取白名单配置元数据，不复制凭证或加载认证文件、helper、hook。

## 内置编排、存储与产物

内置包共七个：Agent 为 `aps.main`、`aps.simpleperf`、`aps.source-evidence`、`aps.report`；Skill 为 `aps.evidence-summary`、`aps.source-candidates`、`aps.report-artifact`。`FrozenAgentGraph` 按可信 manifest 冻结包含 imported Agent/脚本 Skill 的有界调用图。每个 child 都有独立的 Provider 请求、run/session、父子会话与精确审计引用，不再只是内置步骤标签；选择来自已审批的 manifest 图，不是模型任意选工具。

`MainAgentOrchestrator` 把上下文、图/包哈希、权限、Provider/model、派生数据策略和 generation 绑定到宿主审批。根、child、Skill、脚本和 Artifact 共用累计预算；每步复检包与源码授权。子结论/脚本输出经过引用校验与脱敏，只以明确不可信的派生分析传给父 Agent，不提升为性能事实、源码位置或系统指令，超限拒绝而不截断。脚本默认关闭，需要显式权限、冻结路径/哈希和合格沙箱预检；Provider 自发工具调用与设备写入仍不开放。

`ConversationRecord`/turn 仍只保存引用。独立的 `StoredAnalysisResult` 现在可保存有界、校验并脱敏的摘要/Finding、模型和会话身份、问题哈希、parent-turn 与 Artifact 哈希；执行审计保存阶段、引用、预算和稳定错误码，不存原始 prompt/response、源码或 stdout/stderr。历史可恢复结论，但不会恢复可执行请求或自动重发；缺少原始 canonical context 的 Evidence 引用明确显示不可用。

报告 Skill 可生成内存中的只读报告；实际写文件还需显式 `WRITE_ARTIFACT`。`ArtifactPublisher` 默认输出转义后的 inert HTML，也有 Markdown API，并在预览/读取时复核内容与 provenance 哈希。当前全局 UI 可打开校验后的 HTML，不代表完整的 Markdown 导出/团队共享流程。脱敏策略也不等于匿名性保证。

## Profiler 与旧入口

`ui-components` 的 `UiAiContext`/`UiAiEvidence`、`AiContextHost` 和 `PublishAiContext` 只传业务事实，不授予 Provider、文件或源码上传权限。Profiler 持有各自的最小化适配器，桌面将当前页上下文转换为不可变 `BusinessContext`。

- Layout Inspector 与 Simpleperf 已发布选择/报告上下文；统一桌面不再给 Simpleperf 传空 client。旧 Layout 入口使用 `SourceAwareLayoutAiAnalysisClient`，Simpleperf 使用 `ProductionSimpleperfAiAnalysisClient`，两者都经 `DesktopAiCoordinator` 打开同一全局审批窗口并等待对应 run 的结果，不自行绕过确认执行 Provider。
- Trace/Perfetto 提供当前 artifact 的数值诊断结果；Memory、Frame、Startup、Battery、Network、GPU、Benchmark Regression 已有页面适配器和对应测试。投影保留单位、缺失/覆盖边界、选择/摘要范围与适用的省略计数，不上传原始 profile。Network 排除 URL/header/body，Memory 排除字段运行值，GPU 当前只有 artifact 身份元数据，不提供推测的 GPU timing。
- 上述页面、方法录制和源码元数据页均提供工具栏“AI 分析”，通过共享 `ProfilerAiAnalysisButton` 提交当前发布的证据，宿主统一预检及确认；无证据、采集中或超预算禁用，独立模式无宿主则隐藏。新入口性能 JSON 总计最多 8 KiB、摘要标签最多 2 KiB，本地聚合只保留关键行及遗漏计数，不批量发送原始报告。
- 方法录制新增独立 `METHOD_RECORDING` / `method-recording-summary`，保留 ART 方法记录时钟、计量单位和 Top 8 方法，不与 CPU 统计采样混淆。相关共享内置 Agent/Skill manifest 升级到 1.4.0，保持现有源码/网络/脚本权限边界。
- `source-workspace` 已有 Local/GitHub/AOSP provider、Snapshot、索引、确定性 resolver 和只读 Source Viewer。独立 Source Workspace 已发布可验证选择的快照哈希、语言、大小与行数元信息；缺失/错配内容不可用，不发送路径或源码正文，也不把源码浏览当作性能测量或上传授权。

## 验证与产品边界

- OpenAI 已接入真实 `ai-core` client/gateway，但平台集成证据仅使用 fake HTTP transport，不证明真实凭证、网络请求或模型效果。
- Claude CLI adapter 已实现版本/必要 flag 检查、bare/no-tools、空 MCP 配置、stdin evidence 和结构化输出校验；当前凭证要求为 `ANTHROPIC_API_KEY` 环境引用，不回退订阅/OAuth 登录。测试使用 fake executable，没有 live auth/network 验收。
- Codex 已接入明确标注的 Responses API 路径，使用配置的 API 凭证和显式 Codex 模型；强制无工具、无服务端 Responses 存储与有界输出，区分 commentary/final/refusal/incomplete。CLI 路径仍为 `UNSUPPORTED_CAPABILITY`，不会读取缓存登录来绕过能力限制，也不自动切换 Provider。
- Linux arm64 Python/Node 已通过隔离环境中的原生敌对用例与公开运行时 API 检查；生产路径使用 bubblewrap/prlimit/seccomp helper，打包时校验固定资源的架构与摘要。macOS 为 `E_MACOS_SANDBOX_UNQUALIFIED`，Windows 为 `E_WINDOWS_SANDBOX_UNQUALIFIED`，不回退普通进程；原生 x86_64 和实际安装包仍待验收。
- 2026-10-31 本地平台/桌面测试、Compose 和应用镜像打包结果见 TODO 状态。OpenAI、Codex 各一次真实合成验收尝试均因未收到 HTTP 响应而超时，没有自动重试，不能算真实推理通过。签名/公证、同 SHA 发布门禁和假执行器回归已实现，但本机没有可用签名身份，远端缺少 Secrets/受保护环境及已发布的新工作流，未执行 hosted CI 或真实签名/公证。通用 MCP、RAG、长期记忆和自动改码未启用。

## 文档地图

- [模块与源码入口](../../desktop-viewer/ai-platform/README.md)：当前模块归属、桌面接线、执行与验证边界。
- [总体架构与接口](architecture.md)：数据契约、源码授权边界和对话窗口设计；含历史提案，不能单独作为当前完成证据。
- [AI Business Layer](business-layer.md)：主/子 Agent、Skill、外部扩展、脚本和 Artifact 的详细设计，不代表所有目标都已开放。
- [AI 对话窗口](conversation-window.md)：以不可变 Analysis Session 为上下文的产品、交互、状态和安全需求。
- [路线图与缺口](roadmap.md)：源码已有能力、未完成产品闭环及后续验收顺序。
- [AI Agent Platform TODO](agent-platform-todo.md)：跨模块实施清单。
- [AI Agent Platform TODO 状态统计](agent-platform-todo-status.md)：由主线维护的新执行结果、计数及证据边界。
- [真实 Provider 验收](live-provider-acceptance.md)：显式 opt-in、合成输入、调用上限和脱敏回执。
- [原生沙箱契约](native-sandbox-contracts.md)：Linux 边界、逐平台资格和已知不支持项。
- [发布资格](release-qualification.md)：同 SHA CI、凭证引用、签名、公证与发布门禁。

## 历史设计与决策

以下文档保留为详细设计和不可逆决策的来源，`docs/ai/` 负责提供统一入口和当前状态解释：

- [AI 分析与源码工作区总体设计](../../desktop-viewer/docs/design/2026-08-01-ai-source-workspace-design.md)
- [ADR-0001：确定性源码定位优先于 AI 排序](../adr/0001-deterministic-source-resolution-before-ai-ranking.md)
- [ADR-0004：provider-neutral AI boundary，OpenAI first](../adr/0004-provider-neutral-ai-boundary-with-openai-first.md)
- [ADR-0005：最小化并授权发送到云端 AI 的源码](../adr/0005-minimize-and-authorize-source-sent-to-cloud-ai.md)
- [ADR-0012：通用分析信封与 Profiler Evidence Adapter](../adr/0012-use-a-common-analysis-envelope-with-profiler-evidence-adapters.md)
- [ADR-0013：云端分析必须由用户显式触发](../adr/0013-require-explicit-user-trigger-for-cloud-analysis.md)
- [ADR-0014：归档保存引用，不保存源码和凭证](../adr/0014-archive-analysis-references-without-source-or-credentials.md)
- [ADR-0020：分析范围显式且默认当前选择](../adr/0020-make-analysis-scope-explicit-and-selection-first.md)
- [ADR-0021：版本化结构化分析，不做自由聊天](../adr/0021-use-versioned-structured-analysis-instead-of-free-form-chat.md)
- [ADR-0037：AI Platform 信任、目录、凭证和执行边界](../adr/0037-ai-platform-trust-and-execution-boundaries.md)
- [ADR-0036：独立 AI Business Layer](../adr/0036-separate-ai-business-layer-with-agents-skills-and-sandboxed-scripts.md)

## 维护规则

1. AI 产品范围、路线和对话窗口需求写入本目录。
2. 不可逆的边界和安全决策写入 `docs/adr/`，并从本目录链接。
3. Profiler 专属 evidence adapter 和 UI 细节仍放在对应模块；本目录只定义跨模块契约和用户可见边界。
4. 文档中的“已实现”必须能在当前接口、接线或测试中找到证据；仅有模型或接口骨架的能力标记为“部分实现”。
5. AI Business Layer 的外部 Agent、Skill 和脚本能力必须先有 manifest、权限、资源限制和审计设计，再进入业务接入路线。
