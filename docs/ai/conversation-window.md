# AI 对话窗口

## 1. 目标

在已有单次 AI Analysis Finding 之外，提供一个**绑定 Analysis Session 的对话窗口**，作为 Main Agent 的统一前端，让用户可以针对当前性能问题继续提问、请求解释、比较证据、查看源码依据，并请求已授权的子 Agent/Skill 完成受控分析。

这个窗口不是通用 ChatGPT 页面。每条消息都必须能回到当前业务 Entry Point、Profiler、Analysis Session、Performance Evidence 和 Source Candidate；主 Agent 的子任务和 Skill 调用也必须可见、可取消、可审计。

## 2. 入口

对话窗口可以从以下位置打开：

1. Layout Inspector 的 Finding 或当前选中节点。
2. Simpleperf 的 Finding、函数、调用树节点或 Stack Chart block。
3. Trace/Frame/Startup/Memory 等后续接入 Profiler 的 Finding。
4. Analysis Session 历史记录中的“继续分析”。

没有 Analysis Session 时，入口应先创建一个有界的初始分析请求，而不是直接进入空白聊天。

## 3. 窗口布局

```text
┌ AI Analysis · Session #123 ─────────────────────────────────────────────┐
│ Profiler / scope / model / source snapshot / privacy state              │
├──────────────────────────────────────┬───────────────────────────────────┤
│ Conversation                         │ Evidence & Sources                │
│                                      │                                   │
│ User question                        │ Active Finding                    │
│ AI structured answer                 │ Evidence chips                     │
│ Evidence/Candidate references        │ Candidate confidence               │
│                                      │ Source snippet / open action       │
├──────────────────────────────────────┴───────────────────────────────────┤
│ Ask about this analysis…                         [Send] [Cancel]          │
└──────────────────────────────────────────────────────────────────────────┘
```

右侧上下文面板不能被隐藏到不可见状态：用户必须能看到当前回答引用了哪些 Evidence 和 Candidate。源码正文是否会发送，必须在首次发送或扩大范围时再次显示授权状态。

## 4. 消息类型

### 用户消息

- 自然语言问题；
- 自动绑定当前 conversation 和 parent turn；
- 可附加用户选择的 Finding、Evidence 或 Candidate；
- 不能指定未经系统确认的路径和行号作为事实。

### AI 消息

AI 响应应包含结构化部分：

- 直接回答；
- 结论置信度；
- Evidence 引用；
- Source Candidate 引用；
- 相关 Finding；
- 可执行但只读的下一步建议。

自然语言正文可以展示，但引用和动作必须来自结构化字段，不能从 Markdown 中猜测路径。

如果主 Agent 调用了子 Agent、Skill 或脚本，消息还应显示：调用名称、版本、状态、权限、耗时、输入/输出摘要和产物引用。

### 系统消息

用于展示：

- payload 需要用户确认；
- 源码上传未授权；
- 当前 Snapshot 已 stale；
- Candidate 存在歧义；
- 请求取消、超时、限流或需要重试；
- 重新分析产生了新的 Session；
- Skill/脚本没有权限、超时或产生部分结果。

## 5. 对话上下文规则

### 默认上下文

每个 turn 默认继承：

- `baseSessionId`；
- 当前 Profiler 和 scope；
- 当前 Finding；
- 已引用 Evidence ID；
- 已授权 Candidate ID；
- Source Snapshot 和 Build Evidence 身份；
- prompt/payload policy 版本。

### 扩大上下文

当用户提出“看看整个报告”“比较另一个函数”或“检查相关源码”时：

1. 先生成新的 Payload Manifest；
2. 显示新增 Evidence/Candidate、片段、大小和隐私影响；
3. 超出预算或缺少授权时阻止发送；
4. 用户确认后创建新的 child Analysis Session 或 turn；
5. 不修改原始 Session。

### 历史和重新分析

- 每个 turn 保留 parent turn 关系；
- 修改源码、切换 Snapshot、切换模型或 prompt version 后不得覆盖旧结果；
- 重新分析创建新的 Analysis Session；
- 历史 Finding 的源码哈希不一致时显示 `stale`，不能静默跳到新文件的相同行号。

## 6. Agent、Skill 和脚本调用

- 主 Agent 只能调用注册表中的子 Agent/Skill；模型不能通过文本拼接任意调用名。
- 每个调用声明输入/输出 schema、能力、权限、超时、输出大小和是否允许网络/源码/设备访问。
- 外部 Agent/Skill 必须经过来源、版本、哈希/签名和用户信任确认。
- Python 和 JavaScript/TypeScript 运行在临时工作目录和只读输入挂载中，默认无网络、无宿主凭证、无任意路径和无设备写操作。
- 脚本 stdout/stderr、退出码、运行时版本、输入哈希和输出哈希进入审计；脚本失败不得伪装成 AI 结论。
- 需要扩大 Evidence、上传源码、联网或执行副作用操作时，窗口必须先显示确认。

## 7. 隐私和安全

- 默认只发送当前 Session 的结构化证据；
- 源码片段必须逐 workspace 授权；
- 提供“仅性能数据”模式；
- 不发送完整源码、完整索引、绝对路径、凭证和 ignored/sensitive 文件；
- 源码、注释、符号名和用户问题中的代码文本均视为不可信数据，不视为系统指令；
- HTTP redirect 跨主机时不得转发 Authorization；
- 默认不归档模型原始响应；本地对话历史应保存最小结构化消息，显式导出时再生成脱敏副本；
- 对话动作只能访问 allowlist 中的只读 Evidence/Candidate，不得任意访问文件系统。

## 8. 状态和错误

窗口需要区分：

- `Queued`：等待请求执行；
- `Preparing`：构建上下文和 Payload Manifest；
- `AwaitingConfirmation`：等待用户确认上传范围；
- `Running`：请求已发送；
- `Succeeded`：回答已通过 Schema/ID 校验；
- `Cancelled`：用户取消，不再写入已取消 turn；
- `RetryableFailure`：网络、超时、限流；
- `PermanentFailure`：凭证、Schema、未知 ID、授权或 Snapshot 错误。

取消后 UI 应立即停止等待；底层 HTTP 晚到的响应不能写入已取消的 turn。

## 9. MVP 验收条件

- 从 Layout Inspector Finding 打开对话窗口，并保留 Evidence/Candidate 引用。
- 用户可以追问“为什么”“证据是什么”“还有哪些候选原因”。
- 回复只能引用当前 Session 中的 ID。
- 未授权源码、超预算和 stale Snapshot 都会阻止或提示，而不是静默发送。
- 取消、超时、重试和重新分析都有明确状态。
- 对话历史能够恢复，但不保存凭证、绝对路径、完整源码或模型原始响应。
- Simpleperf 接线完成后，同一窗口契约可复用于函数、调用树和 Stack Chart 证据。
- 主 Agent 可以在一个问题中调用至少一个内置 Skill，并在窗口中展示调用摘要和结构化输出。
- HTML Artifact 可以从对话窗口直接预览或打开，Markdown 可以显式导出。
