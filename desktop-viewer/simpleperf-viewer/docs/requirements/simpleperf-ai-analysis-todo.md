# Simpleperf AI Analysis TODO

状态：TODO，2026-10-30。

目标：让本地 Firefox Profiler 网页中的 Flame Graph / Call Tree / Stack Chart
选择结果能够使用 Android Performance Studio 已有的 Compose AI 能力分析。

统一原则：**Compose 宿主是唯一的 AI 执行者和结果所有者。** 网页端只负责
呈现本地 Firefox Profiler、报告节点交互和发送稳定的选择事件；不得在网页
JavaScript 中重复实现 AI client、prompt、source resolution、API key 管理或
结果校验。

原有 Kotlin/Compose 报告工作区继续保留，网页端接入不应改变现有 Native
Flame Graph、Firefox Profiler local launcher、报告数据模型或 source workspace
权限边界。

## 当前基线

- `simpleperf-viewer/app-desktop/.../SimpleperfAiAnalysisClient.kt` 已有
  `SimpleperfAiAnalysisClient`、Simpleperf 性能证据模型，以及对当前 Flame
  Graph call node、Call Tree node 和 Stack Chart block 的证据提取。
- `simpleperf-viewer/presentation/.../FirefoxReportWorkspace.kt` 已有
  `onRunAiAnalysis` 和 Compose AI 结果入口。
- `simpleperf-viewer/app-desktop/.../SimpleperfMainPage.kt` 已有 AI 对话框、
  结果 drawer、性能数据-only 选项和 source candidate 打开回调，但接收的
  `aiAnalysisClient` 仍可为空。
- `desktop-app/.../DesktopAppMainPage.kt` 当前将 `aiAnalysisClient = null`
  传给 Simpleperf 页面，因此生产路径尚未完成 AI provider 接线。
- `LocalFirefoxProfilerLauncher.kt` 当前启动 loopback server，提供静态
  Firefox Profiler 资源和 `perf_data.json.gz`，主要处理 `GET`/`HEAD`/`OPTIONS`。
  它还没有节点选择事件或 AI 请求协议。
- `ai-core` 已提供通用 `AiAnalysisGateway`、`AnalysisRequest`、性能证据、
  source candidate 约束和结构化结果校验。Simpleperf 应复用这套能力，不另造
  一套网页专用 AI 协议。

## 目标架构

```text
Firefox Profiler local web page
  -> selected stable call-node / stack-block event
  -> loopback bridge owned by Compose host
  -> ReportController / ReportState maps selection to canonical ReportData
  -> SimpleperfPerformanceEvidence
  -> AiAnalysisGateway / configured provider
  -> validated AnalysisResult
  -> Compose AI drawer / session repository
  -> optional result notification or read-only result relay to web page
```

### 所有权边界

- **Canonical profile facts**：`ReportData`、SQLite projection 和
  `ReportState` 由 Compose/ReportController 持有，网页传来的 symbol、权重和
  source path 只能作为选择提示，不能直接成为 AI 证据真值。
- **Selection identity**：网页必须发送稳定的 `FlameCallNodeId` 或
  `StackChartBlockId`、报告 session ID、generation 和 panel 类型。Compose
  根据当前 `ReportData` 重新解析节点；不存在、过期或跨 session 的 ID 必须
  返回 unavailable，不得按函数名猜测对象。
- **AI execution**：只在 Compose/Kotlin 侧调用 `SimpleperfAiAnalysisClient`
  和 `AiAnalysisGateway`。浏览器不得获得 OpenAI key、endpoint credential、
  source workspace token 或未授权源码片段。
- **Source resolution**：AI 只能引用已授权且已固定版本的 source candidate。
  网页不能发送任意文件路径、行号或源码内容来绕过 source workspace。
- **Result ownership**：`AnalysisResult`、finding evidence IDs、source candidate
  IDs 和 session status 由 Compose 保存和展示。网页端若需要结果，只能读取
  Compose 已验证后的投影。

## TODO

### P0：冻结桥接契约

- [ ] 定义 `SimpleperfWebBridgeEvent`：`sessionId`、`reportGeneration`、
  `panel`、`callNodeId`/`stackBlockId`、事件序号和取消标识。
- [ ] 定义 `SimpleperfWebBridgeResponse`：accepted、stale、unavailable、
  running、succeeded、failed、cancelled；错误必须带稳定 code 和可复制 detail。
- [ ] 明确网页与 Compose 的 session 生命周期：报告关闭、重新导入、页面重载、
  server 关闭和 generation 变化时，旧选择不能污染新报告。
- [ ] 固定协议版本和大小上限；禁止传输完整 profile、任意文件路径和未授权
  源码正文。

### P1：把现有 AI provider 接入 Compose

- [ ] 实现生产 `SimpleperfAiAnalysisClient`，把
  `extractSimpleperfEvidence(report, state)` 转换为 `ai-core` 的
  `PerformanceEvidence` 和 `AnalysisRequest`。
- [ ] 使用 `ProfilerKind.SIMPLEPERF`、`CURRENT_SELECTION` / `REPORT_SUMMARY`
  scope，保留 prompt version 和 payload policy version。
- [ ] 将 `DesktopAppMainPage.kt` 的 `aiAnalysisClient = null` 替换为真实的
  Compose-owned adapter，复用现有 AI 设置、模型选择、endpoint 校验和 source
  workspace runtime。
- [ ] 复用 `OpenAiAnalysisGateway` 的结构化输出校验：finding 只能引用已发送
  的 performance evidence ID 和 source candidate ID。
- [ ] 结果写入 `AnalysisSessionRepository`，支持 loading、success、failure、
  cancellation、重试和同一报告上下文内的追问。

### P2：网页选择事件接入 Compose

- [ ] 在 `LocalFirefoxProfilerServer` 增加 loopback-only bridge endpoint，
  只接收选择、取消和结果读取请求；静态 profile 文件服务保持不变。
- [ ] 为每次打开生成不可预测的 bridge session token；请求必须校验 token、
  report session ID、generation 和来源页面生命周期。
- [ ] 在 Firefox Profiler local web bundle 中增加最小 selection adapter：
  只发送稳定节点 ID 和 panel，不复制 AI 逻辑、不发送 API key。
- [ ] Compose 收到网页选择后，更新同一 `ReportController` 的 selection，复用
  Native Compose 报告页已有 selection/evidence 路径；网页选择不能直接构造
  `ReportState`。
- [ ] 网页页面关闭或 server close 时取消未完成请求，并清理 bridge session。

### P3：统一 Compose 结果体验

- [ ] 网页节点选择触发 Compose AI drawer/dialog，显示 evidence scope、sample
  count、当前 selection、summary、findings、confidence 和 source actions。
- [ ] 保留“仅性能数据”选项；未显式允许源码片段时不发送 source snippets。
- [ ] source candidate 点击继续通过 Compose navigator 打开固定 source snapshot，
  不让浏览器直接打开路径。
- [ ] 如果网页需要显示结果，只提供 Compose 已验证结果的只读 relay；网页不能
  自行重新请求 provider 或修改 finding。
- [ ] 让 Native Compose 报告页和 local web 页使用同一 AI drawer/result model，
  避免两个界面出现不同的 prompt、证据口径或 confidence 解释。

### P4：安全、取消与错误

- [ ] API key 只存在 Compose/Kotlin 运行时；检查浏览器 network payload 不含 key。
- [ ] loopback bridge 拒绝非本机来源、错误 token、错误 generation、未知 node
  ID、过期 session 和过大请求。
- [ ] AI 请求支持取消和 latest-generation-wins；旧结果不得覆盖新 selection。
- [ ] provider 不可用、模型不支持、source 未授权、节点失效和网页断连分别显示
  不同错误状态，不能静默退回报告摘要。
- [ ] 保留本地 evidence/session 记录，但不把原始 profile 或源码片段无意写入
  日志、URL、浏览器 history 或错误堆栈。

### P5：测试与验收

- [ ] `SimpleperfAiAnalysisClient` 单元测试：summary、当前 call node、stack
  block、过滤条件、source snippets 开关和空 selection。
- [ ] `ai-core` 差分测试：Simpleperf request 的 evidence IDs、scope、payload
  policy 和 source candidate 引用均可验证。
- [ ] loopback bridge 测试：token、session、generation、过期事件、重复序号、
  大请求、取消、断连和 server close。
- [ ] Compose UI 测试：AI action 可见性、confirmation dialog、loading、result
  drawer、failure/retry、source candidate navigation、性能数据-only。
- [ ] local web fixture 测试：选中 Flame Graph node 后，Compose 收到正确 stable
  node ID；Call Tree、Flame Graph、Stack Chart 三种面板分别覆盖。
- [ ] 端到端测试：网页选中节点 -> Compose ReportState -> evidence extraction ->
  fake AI gateway -> validated result -> Compose drawer。
- [ ] 安全回归：浏览器抓包无 API key，source path 不出现在 bridge payload，旧
  session 事件不会覆盖新报告。

## Non-goals

- [ ] 不在网页 JavaScript 中实现第二套 AI client、prompt 或结果解析器。
- [ ] 不让网页直接访问 OpenAI endpoint 或读取 Compose 设置中的 API key。
- [ ] 不把网页传来的函数名当作稳定对象身份。
- [ ] 不把 AI finding 当作性能事实；所有结论必须引用 canonical evidence。
- [ ] 不把 local Firefox Profiler 的静态网页能力等同于 native Compose UI、原生
  可访问性或跨平台验收。
- [ ] 不在本 TODO 中更换 Firefox Profiler 的 profile 格式或重写 Flame Graph
  投影、SQLite 查询和选择模型。

## Acceptance Gate

完成标准是：同一个 profile、同一个 Flame Graph 节点和同一组过滤条件，在 Native
Compose 和 local web 两条入口产生相同的 evidence IDs、scope、sample/weight 数值
和 source candidate 约束；AI provider 只被 Compose 调用一次；网页只接收经过校验的
结果；取消、重载和跨 session 场景不会出现旧结果覆盖新结果。

本 TODO 完成前，不得宣称“本地网页版已支持 AI”；最多只能描述为“已有 Compose
Simpleperf AI 接口和火焰图证据提取，网页桥接尚未完成”。
