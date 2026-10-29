# AI 路线与缺口

## 1. 已有能力

- `ai-core`：provider-neutral contract、OpenAI Responses adapter、结构化输出、Credential Store、Analysis Session。
- `source-workspace`：Local/GitHub/AOSP provider、Snapshot、索引、确定性候选和只读 Source Viewer。
- Layout Inspector：当前选择/报告摘要、性能数据-only、源码授权、Finding 和源码跳转。
- Simpleperf：evidence/client 接口和 AI insight UI 骨架已存在，但统一桌面尚未传入实际 client。
- Layout Inspector MCP：设备、View hierarchy 和源码定位桥接；不是通用 AI Agent。

## 2. P0：建立独立 AI Business Layer

### P0.1 统一 Agent/Skill/Entry Point 契约

- 注册 `AiEntryPointDescriptor`、`BusinessContext`、`MainAgentOrchestrator`、`SubAgentRegistry` 和 `SkillRegistry`。
- 将现有 `AiAnalysisGateway` 保留为 provider adapter，不能直接承担主 Agent 编排。
- 每个业务自动入口必须声明上下文类型、Evidence 类型、允许动作、默认 Artifact 格式和权限。
- 内置 Agent/Skill 先使用固定 manifest；外部扩展先只支持显式注册和只读能力。

### P0.2 Python/JavaScript 脚本沙箱和 Artifact 输出

- 支持 Python 3 和 Node.js JavaScript/TypeScript 脚本 Skill。
- 限制 CPU、内存、时长、并发、输出大小和临时目录；默认禁止网络、凭证、任意路径和设备写入。
- 记录运行时版本、输入/输出哈希、stdout/stderr、退出码和取消状态。
- 实现 HTML 默认、Markdown 可选的 `ArtifactPublisher`，保留 Evidence/Candidate/Agent/Skill provenance。

### P0.3 统一文档和发布边界

- 让 `docs/ai/` 成为 AI 文档入口；旧设计和 roadmap 只作为历史详细设计链接。
- 明确“接口存在”和“统一桌面已启用”的差异。
- 恢复 AI 入口 Feature Flag 的发布策略，内部试用和公开构建分开。

### P0.4 完成 Layout/Local 可信链

- Build Evidence Bundle：APK/AAB、variant、mapping、ELF Build ID、native symbols、source revision。
- 将 Build Evidence 绑定到 Candidate Resolution 和 Analysis Session。
- 未验证构建身份时，候选最高只能是 `PROBABLE`。
- 当前 Local 文件变化后，历史 Finding 必须显示 `stale`。

### P0.5 让预检成为真正的发送边界

- UI 展示实际 Evidence、文件、行范围、片段大小和总 payload。
- 预览 payload 与实际发送 payload 必须使用同一份不可变对象。
- 超限、未授权和取消时保证零网络请求。
- 删除任何静默截断；改为 Blocked + 缩小范围建议。

### P0.6 接入对话窗口 MVP

- 先支持 Layout Inspector + Local Source Workspace。
- 对话绑定不可变 Analysis Session。
- 支持 Finding 解释、证据追问、候选比较和源码打开。
- 主 Agent 可以调用内置子 Agent/Skill，并显示调用过程和权限状态。
- 支持 HTML 默认预览和 Markdown 显式导出。
- 不提供无业务上下文的自由聊天、全库检索或代码修改。

## 3. P1：扩大受控分析范围

1. 将 Simpleperf 统一接入 `ai-core`、Source Resolver、Build Evidence 和 Analysis Session。
2. 为 Trace/Perfetto、Frame、Startup、Memory、Battery、Network 和 Benchmark Regression 增加独立 Evidence Adapter。
3. 让 GitHub/AOSP Source Workspace 真正进入 AI Candidate/授权链路，而不只是源码浏览。
4. 增加 Analysis Session 历史、重分析、child turn、Finding 比较和 evidence diff。
5. 增加 AI 结果反馈、误报/漏报标记、人工 usefulness 评分和固定样例回归。
6. 补齐 Windows Credential Manager、Linux Secret Service、可取消远端 transport 和 100k 文件索引基准。

## 4. P2：仅在需求成立后增加

- 第二个 AI provider 或本地模型 adapter；
- 流式回答和阶段级进度；
- 跨 Profiler 的证据关联与统一诊断会话；
- 受限的只读 MCP tool calls；
- 主 Agent 的多步工具调用、子 Agent 协作和脚本编排；
- 显式导出的脱敏分析报告和团队共享格式。

这些能力必须继续复用 Evidence/Candidate allowlist、用户授权和 Analysis Session，不得退化成全项目自由聊天。

## 5. 明确不纳入当前路线

- 自动修改源码、生成 Patch、Commit、PR；
- 没有 Profiler Session 的通用 ChatGPT 页面；
- 未经确认的源码扫描、RAG 全库检索或向量结果直接作为可信定位；
- 自动执行 Gradle、脚本或设备变更；
- 用模型生成路径/行号替代确定性 Source Resolver。

## 6. 验收顺序

1. Layout/Local 单次分析闭环。
2. Layout 对话窗口 MVP。
3. Build Evidence 与 stale/source hash 闭环。
4. Simpleperf 统一接线。
5. Trace/Frame/Startup/Memory 等 Evidence Adapter。
6. GitHub/AOSP AI 上传和第二 provider。
7. 外部 Agent/Skill、只读工具调用、流式交互和跨 Profiler 会话。
