# AI 路线与缺口

本页按当前源码区分“已接线”“已有局部实现”和“尚待产品验收”。详细实施项见
[AI Agent Platform TODO](agent-platform-todo.md)，新测试计数与执行结果由主线更新到
[状态统计](agent-platform-todo-status.md)。本地验证与真实 Provider、原生平台、hosted 和签名证据分别统计，不能互相替代。

## 1. 已有实现，不再作为从零建设项

### 模块与桌面入口

- `ai-core` 保留中立分析契约、OpenAI Responses client/gateway、输出校验、Credential Store 和 Analysis Session。
- `ai-platform` 的 model/registry/storage/providers/runtime 分别负责契约、包信任、引用与结构化结果/审计、Provider/process 边界和编排；app 负责 profile 生命周期/上下文/设置，presentation 负责全局 UI，provider-claude/provider-codex 负责各自 API/CLI 边界，script-runtime 负责沙箱与 Artifact。具体依赖和源码入口见 [模块说明](../../desktop-viewer/ai-platform/README.md)。
- `AiPlatformRuntime.open()` 已由桌面调用，创建目录、取得独占 profile、安装内置包、注册可发现的 Provider 并把未完成记录恢复为 interrupted。恢复不重放请求，关闭先取消并等待所属工作。
- 全局 `AiConversationWindowHost` 已接入导航栏、菜单与快捷键。已有业务上下文绑定、证据追问、预检确认、取消/重试、Finding/Evidence/Source Candidate、时间线及只读历史；Provider/Agent/Skill/Policy 设置页已存在。应用 AI 设置继续管理 OpenAI 凭证/endpoint/model。

### 有界调用图与存储

- 七个内置包为四个 Agent：`aps.main`、`aps.simpleperf`、`aps.source-evidence`、`aps.report`；三个 Skill：`aps.evidence-summary`、`aps.source-candidates`、`aps.report-artifact`。
- `FrozenAgentGraph` 冻结可信 manifest 图，支持 imported Agent 和声明脚本；每个 child 独立调用 Provider、持久化独立会话并记录精确父子运行引用。子结果和脚本输出经过校验、脱敏及预算检查后作为不可信派生分析供父 Agent 使用，不能扩大证据/源码 allowlist 或覆盖系统指令。
- `MainAgentOrchestrator` 已实现冻结请求、宿主审批、计划/包哈希与权限复检、generation 防旧结果覆盖、取消和共享预算。预算覆盖根/child/Skill/Provider/Artifact；CPU 按 elapsed time 保守扣减，不是进程树 CPU 计量，JVM memory 也不是硬隔离。
- 会话/turn 保持 reference-only；`StoredAnalysisResult` 另存有界、校验并脱敏的结论、问题哈希、parent-turn/Artifact 引用。SQLite 原子完成 turn 与结果投影，并保存结构化执行审计；已有只读 restore、删除和显式 retention，不归档完整请求、源码、原始 profile 或 Provider response。
- `ArtifactPublisher` 已有默认 inert HTML、Markdown API、引用 allowlist、provenance 和读回哈希复检。报告 Skill 默认可生成内存报告，落盘需 `WRITE_ARTIFACT`；全局 UI 已能打开校验后的 HTML，尚不是完整的 Markdown 导出/共享产品。

### Profiler 适配与兼容入口

- `UiAiContext`/`UiAiEvidence` 与 `PublishAiContext` 是中立事实投影；桌面按当前页面建立不可变 `BusinessContext`，无有效上下文则不可发送。上下文发布不自动授予源码、网络、文件或设备权限。
- Layout、Simpleperf、Trace/Perfetto、Memory、Frame、Startup、Battery、Network、GPU、Benchmark Regression 均已有页面适配器及对应测试定义。Trace 是 artifact 绑定的数值诊断投影；GPU 当前仅 artifact 元数据。其余投影保留适用的单位、选择/摘要范围、缺失值、覆盖边界和省略计数，不能扩大为完整采集或全库分析。
- Simpleperf 统一桌面已传入 `ProductionSimpleperfAiAnalysisClient`，不是空 client。Layout 的 `SourceAwareLayoutAiAnalysisClient` 和 Simpleperf 均走 `DesktopAiCoordinator`，由同一全局窗口显式批准，旧入口等待精确 run 的结果；旧调用者取消不会取消新会话。
- Local/GitHub/AOSP 源码 provider、Snapshot、索引、确定性 resolver 与只读 Source Viewer 已存在。Source Workspace 的独立 AI 上下文已发布有界的可验证选择元信息，并经过本地回归；不发布路径/源码正文，也不冒充性能测量。
- Layout Inspector MCP 仍是设备、View hierarchy 与源码定位桥接，不等同于主 Agent 已开放通用 MCP 工具。

## 2. P0：收口当前可信链与验收

### P0.1 Source Workspace 与源码身份

- 保持 Source Workspace 上下文的快照/内容身份守卫；源码选择只提供元数据，不伪装成性能测量，不隐式授予片段上传。
- 分别验证 Local/GitHub/AOSP 从确定性候选、版本/hash、授权、预检到源码打开的实际路径，不能用 provider 浏览能力代替 AI 链路验收。
- 继续核对 Build Evidence Bundle（构建身份、mapping、ELF Build ID、symbols、revision）与 Analysis Session/Candidate 的绑定，以及 Local 文件变化后的 stale/source-hash 表现。不能仅凭候选名称或模型结论提升匹配置信度。

### P0.2 预检、全局窗口与旧入口

- 保持预览与发送使用同一冻结 payload，审批绑定计划、包、Provider/model、权限与 generation；取消、过期、撤权和超限必须阻止 Provider dispatch。
- 当前 UI 已显示 payload bytes、Evidence/Candidate 数、Provider/model 与 session；仍需补齐逐文件/行范围/片段字节预览和全部阻塞/权限状态的可用性验收。
- 扩展已有 Compose 测试到真实桌面导航、旧 Layout/Simpleperf 入口、上下文切换、来源失效、历史只读恢复与 Source Candidate 打开。缺少 canonical evidence 时显示 unavailable，不能推测匹配或重放。
- 区分明确声明的有界摘要及省略计数与静默截断；不把当前投影之外的内容冒充完整证据。保留“无业务上下文不可发送”的边界。

### P0.3 Provider 资格，不以适配器存在代替服务可用

- OpenAI 已接实际 client/gateway，但当前平台集成证据仅为 fake transport；真实凭证、网络、取消/错误行为和模型结果仍需独立验收。
- Claude CLI adapter 已有版本/flag 门槛、bare/no-tools、空 MCP、stdin evidence、单次结构化返回校验和受控进程；当前只接受 `ANTHROPIC_API_KEY` 环境引用，不回退订阅/OAuth。fake-executable 测试不是 live auth/network 证据。
- Codex 的显式 Responses API 路径已实现，CLI 仍保留 `UNSUPPORTED_CAPABILITY`，不能用只读 sandbox 冒充零工具执行或借用缓存认证。OpenAI/Codex 实际合成传输各一次均超时，成功推理、账号/模型资格尚未取得，不自动重试或更换 Provider。
- 本地配置发现仅提供白名单元数据/凭证引用，不能加载 auth 文件、helper/hook，不能把凭证存在等同真实登录成功。Provider/model 选择不自动提供执行授权。

### P0.4 脚本沙箱、产物与发布

- Linux arm64 Python/Node 已通过原生隔离敌对与公开 API 检查，生产使用 bubblewrap/prlimit/seccomp。继续取得原生 x86_64 和实际安装包证据，不能把容器内 arm64 结果扩大为所有 Linux 环境。
- macOS 继续返回 `E_MACOS_SANDBOX_UNQUALIFIED`，Windows 返回 `E_WINDOWS_SANDBOX_UNQUALIFIED`；普通进程、临时目录、清空环境和 timeout 都不是合格沙箱，不可作为 fallback。
- 主 Agent 已接入脚本，默认策略关闭。显式授权后仍须通过沙箱预检、包信任、路径/内容校验、结构化输入输出、共享预算与审计；设备写入和 Provider 自授工具仍不可用。
- 保持 Artifact 的显式写权限、内容转义、引用/provenance 校验和防篡改读回；补齐用户选择的 Markdown 导出、Artifact 清理/保留与分享流程，不能把会话删除等同产物已删除。
- 明确内部试用、公开构建、packaged desktop、跨平台、hosted release、签名/公证为独立门槛。目前不声明 hosted release 或签名验收。

## 3. P1：扩大产品闭环，而非重建已有适配器

1. 在现有 Profiler 适配器上补充真实桌面工作流和固定样例回归，复核单位/缺失/省略/覆盖语义；GPU timing 等未观测数据继续不可用。
2. 基于已有 history、retry、parent-turn/parent-conversation 契约完善用户可见的分支、Finding 比较和 evidence diff；restore 始终只读，重分析另行授权。
3. 增加反馈、误报/漏报标记、人工 usefulness 评分与结果质量评估；假 Provider 测试只证明协议/状态，不证明诊断质量。
4. 补齐跨平台凭证后端、受限 GUI 环境中的运行时发现、真实 transport 取消和大规模源码索引基准；逐平台保留独立证据。
5. 扩展已有 EN/ZH、明暗主题、宽窄窗口的 Compose 覆盖，继续验收全部 Provider/error、包信任和权限状态，不把局部测试称为完整 visual-golden 或原生 GUI 验收。
6. 完成显式导出与 Artifact/历史联合保留策略，检查脱敏失败边界；结构化结果可落盘不等于可无条件对外分享。

## 4. P2：仅在需求与安全边界成立后增加

- 本地模型或更多 Provider；Claude 已有 adapter，不再笼统写成“尚无第二 Provider”。
- 流式回答与更完整的阶段交互；当前已有执行时间线，不等于 token streaming。
- 跨 Profiler 的证据关联与统一诊断会话。
- 受限只读 MCP tools 与模型驱动的动态规划；当前通用 manifest 图编排不等于允许模型任意扩展执行图或主机工具。
- 显式脱敏的团队共享格式。

这些能力继续复用 Evidence/Candidate allowlist、用户授权、固定版本身份与 Analysis Session，不退化成全项目自由聊天。

## 5. 明确不纳入当前路线

- 自动修改源码、生成 Patch、Commit、PR。
- 没有有效业务证据的通用聊天页面。
- 未确认的源码扫描、RAG 全库检索或以向量结果直接替代可信定位。
- 自动执行 Gradle、未经单独授权的脚本或设备变更。
- 用模型生成路径/行号替代确定性 Source Resolver。
- 绕过 Codex capability gate、源码授权、沙箱失败或扩展信任，以普通进程/其他模型静默兜底。

## 6. 续作验收顺序

1. 在真实工程中复验已接线的 Source Workspace 上下文，核对版本/hash、Build Evidence 与 stale 链路。
2. 沿已有桌面 bootstrap、全局窗口和 Layout/Simpleperf coordinator 复验确认/取消/切换/恢复，不再作为未接线功能重做。
3. 在真实数据与桌面流程中逐一验收已存在的 Profiler adapters；保持缺失数据、只读历史和引用边界。
4. 修复真实 Provider 的可达性并按显式授权重新验收；Claude 另需 API 凭证。Codex API 与未开放的 CLI 路径保持明确区分。
5. 复验已接线的脚本/独立 child，取得 macOS/Windows 后端、原生 Linux x86_64 与实际安装包资格，并完成 Artifact 导出/保留。
6. 经授权发布待验收源码并配置受保护环境、证书/账号后，执行同 SHA hosted、签名与公证；不得用本地 fixture 或历史远端 run 代替。
