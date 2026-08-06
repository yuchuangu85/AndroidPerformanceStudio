# Layout Inspector

## 功能作用

Layout Inspector 是一个 Android 视图层级采集与分析工具，核心功能包括：

- **实时视图层级采集**：通过 ADB 连接设备上的 [Android Agent](shared-kernel/android-agent-view/)，实时获取前台应用或系统 UI 的完整 View 树（包含 View 层级和 Compose Semantics 节点）
- **截图叠加可视化**：采集设备截图并在截图上叠加每个 View 节点的边界框（Bounds），支持点击选中、拖拽平移、缩放等交互
- **层级树浏览**：左侧展示可折叠的 View 层级树，每个节点显示类名、资源 ID，选中后右侧详情面板展示节点属性（ID、类名、文本内容、边界、子节点数、详细属性分组）
- **布局分析规则引擎**：内置静态分析规则，自动检测布局问题：
  - `layout.invisible-node`：不可见但占用空间的节点
  - `layout.excessive-children`：子节点过多（超过阈值）
  - `layout.overlapping-siblings`：兄弟节点重叠比例过高
  - `layout.deep-hierarchy`：层级深度过深
- **AI 布局分析**：可选的 AI 分析集成（`AiAnalysisInputBuilder` + `OpenAiResponsesAnalysisClient`），将布局快照发送给 LLM 进行分析，返回 AI 发现的问题（`AiFinding`），并可关联到源码位置候选
- **时间线对比**：支持采集多帧快照，自动计算帧间 diff（新增/移除/边界变化的节点）
- **离线归档**：支持将布局快照导出/导入为 `CaptureArchive` 格式（含截图、协议数据、分析结果），便于离线分析和分享
- **截图替换**：支持手动导入新截图替换当前截图，用于适配不同分辨率的设备

## 实现原理

### 采集流程

1. **ADB 通道建立**：`LiveDeviceClient` 通过 ADB 建立设备连接，支持 USB 和 TCP/IP 模式
2. **目标选择**：支持两种 `CaptureTargetMode`：
   - `FOREGROUND_APP`：获取前台 Activity 的 View 树
   - `SYSTEM_UI`：获取系统 UI（状态栏、导航栏）的 View 树
3. **Agent 协作**：设备上运行的 Agent 负责：
   - `ViewTreeCollector`：递归遍历 Window 的 View 层级树
   - `ComposeSemanticsCollector`：收集 Compose 的 Semantics 树
   - `LiveSnapshotFactory`：生成包含 View 树 + 截图的快照
4. **协议解码**：`ProtocolCodec` 将 Agent 传回的 JSON 快照解码为 `UiNode` 树结构（区分 `ViewNode` 和 `ComposeNode`）
5. **采集模式**：
   - **自动扫描**（auto-scan）：以固定间隔（`CAPTURE_INTERVAL_MILLIS`）循环采集，自动检测前台切换
   - **手动刷新**：单次触发采集

### 数据结构

- **UiNode**：抽象节点，包含 id、className、bounds、alpha、visibility、children
- **ViewNode**：继承 UiNode，增加 resourceName、viewFlags 等 Android View 特有属性
- **InspectorState**：`InspectorStore` 管理的应用状态，包含 snapshot、selectedNodeId、analysis、timelineFrames

### 数据流

```
[Android Device] --ADB--> [LiveDeviceClient] --Snapshot JSON--> [ProtocolCodec]
    --> [InspectorStore] --State--> [InspectorPresenter] --ScreenModel--> [Compose UI]
                                              |
                                   [Analysis Engine] --findings--> [Findings Panel]
                                   [AI Analysis Client] --aiFindings--> [Findings Panel]
```

### 源码关联

通过 `InspectorCorrelationHint` 与 Source Workspace 建立关联，AI 分析结果中的 `sourceCandidateIds` 可导航到对应的源码位置（`onOpenSourceCandidate`）。

## 优化建议与改进点

> 以下内容不替换已有设计，仅作为可考虑的更好实现方式或优化点补充。每条标注 **影响**（高/中/低）与 **可行性**（高/中/低）。

### 1. JSON 协议编码的性能与体积【影响:高 / 可行性:中】

**当前实现问题**：`ProtocolCodec` 将 Agent 传回的快照以 JSON 解码为 `UiNode` 树。复杂 UI（Compose 节点上千、嵌套深）的 JSON 体积大、解析慢、内存占用高，且文本协议在 ADB 转发的 socket 上传输效率低。

**更好的实现方式**：
- 评估迁移到 **protobuf 编码**：定义 `UiSnapshotProto` schema，二进制传输 + 解析，体积通常压缩 3-5 倍、解析速度提升一个数量级。
- 过渡期可保留 JSON 作为可读 fallback，但默认走二进制；并在协议头协商编码格式（`Content-Type: application/protobuf` vs `json`）。
- 对大快照，Agent 端支持 **分块流式传输**（chunked streaming），避免单帧占用过大内存峰值。

### 2. auto-scan 间隔与性能影响【影响:中 / 可行性:高】

**当前实现问题**：auto-scan 以固定 `CAPTURE_INTERVAL_MILLIS` 循环采集。固定间隔过短会持续占用 ADB + 设备 Agent，干扰被测 App 的渲染与内存；过长则错过瞬时布局变化。

**更好的实现方式**：
- 把间隔改为 **自适应/事件驱动**：Agent 端监听 `OnGlobalLayoutListener` / `OnPreDrawListener` / Compose `Snapshot` 变化通知，仅在布局变化时推送快照，静止时不采集。
- 保留固定间隔作为降级（事件监听不可用时），但提供"仅变化时采样"模式，显著降低对被测 App 的干扰。
- 在 UI 显示每次采集的耗时与对设备的影响（如采集期间引起的额外帧耗时），让用户感知观察者效应。

### 3. 静态规则阈值的可配置性【影响:中 / 可行性:高】

**当前实现问题**：四条规则（invisible-node、excessive-children、overlapping-siblings、deep-hierarchy）的阈值写死在规则中，不同业务对"合理"的标准不同（如游戏 UI 深度 vs 表单页）。

**更好的实现方式**：
- 把每条规则的阈值抽到 **`LayoutRuleConfig`**（如 `maxDepth=10`、`maxChildren=20`、`overlapRatio=0.5`、`invisibleAreaRatio=0.3`），暴露为可配置项并支持预设（Strict / Balanced / Lenient）。
- 支持按节点类型/包名做差异化阈值（如对 `androidx.compose.*` 节点放宽 depth 阈值，因为 Compose 节点天然嵌套更深）。
- 提供"项目级规则配置文件"导入导出，便于团队统一规则。

### 4. AI 分析的提供商抽象与脱敏【影响:高 / 可行性:中】

**当前实现问题**：AI 分析硬绑定 `OpenAiResponsesAnalysisClient`，且把"布局快照"发送给 LLM。布局快照可能包含文本内容、资源 ID、用户输入等隐私/商业敏感信息，未说明脱敏策略；单一 provider 耦合也限制了本地模型或私有部署。

**更好的实现方式**：
- 抽象为 **`AiAnalysisClient` 接口**，支持 OpenAI、Anthropic、本地 Ollama 等多实现，按配置注入；文档中的 `OpenAiResponsesAnalysisClient` 仅作为默认实现之一。
- 在 `AiAnalysisInputBuilder` 增加 **可配置脱敏管道**：默认移除/打码用户文本（`text` 字段）、邮箱/电话正则替换、保留结构化字段（类名、bounds、resourceName），并在 UI 显示"已脱敏字段数"，让用户知情。
- 支持本地优先：对隐私敏感场景，允许只走本地模型（如 Ollama + qwen2.5-coder），不上传任何数据。
- 对发送的数据做 size 上限（如 > N KB 的快照先摘要/截断），避免超 token 与费用失控。

### 5. 帧间 diff 的稳定节点 ID【影响:中 / 可行性:高】

**当前实现问题**：时间线对比计算"新增/移除/边界变化的节点"，但 `UiNode.id` 如果是基于采集序号生成的，跨帧 id 不稳定，diff 算法会误判"新增/移除"。

**更好的实现方式**：
- 定义 **稳定节点标识**：优先用 `resourceName` + 父链路径哈希，或 Compose 的 `semanticsId`（如可读），作为跨帧匹配键。
- 对匹配键冲突（多节点同 key）时降级到 bounds + 类名组合，并在 diff 结果中标注 `matchConfidence`。
- 在 diff 算法上用 **最长公共子序列（LCS）** 或树编辑距离（GTM）做层级对齐，而非简单按 index 对比。

### 6. 无障碍（Accessibility）检查集成【影响:中 / 可行性:中】

**当前实现问题**：规则只关注性能/结构（重叠、深度、不可见），未覆盖无障碍问题（小触摸目标、无 contentDescription、对比度低），而这些是现代 UI 审查的重要维度。

**更好的实现方式**：
- 增加无障碍规则集：`a11y.small-touch-target`（< 48dp）、`a11y.missing-content-description`（图片/按钮无描述）、`a11y.text-contrast-low`（需配合截图分析）。
- 复用 Compose Semantics 树（`ComposeSemanticsCollector` 已采集），其中 `Role`、`ContentDescription`、`TouchTargetSize` 等字段是天然的 a11y 数据源。
- 与 Google Accessibility Scanner 的规则对齐，输出可操作的修复建议。

### 7. Compose 与 View 混合树的统一模型【影响:低 / 可行性:中】

**当前实现问题**：`ViewNode` 与 `ComposeNode` 分开建模，但实际 UI 常是 View + Compose 混合（`AbstractComposeView`、`ComposeView` 嵌在 View 树中），跨边界导航与归因需要清晰统一。

**更好的实现方式**：
- 在 `UiNode` 抽象上增加 `framework`（`VIEW` / `COMPOSE`）标签，并统一 `children` 顺序为真实视觉顺序（而非框架切换处乱序）。
- 对 Compose 节点记录 `callSite`（文件 + 行号，如 AGP Compose compiler 启用 `sourceInformation` 时可读），用于跳转源码——这是 Layout Inspector 的高价值能力，建议文档明确支持等级。

### 8. 离线归档的完整性校验【影响:低 / 可行性:高】

**当前实现问题**：`CaptureArchive` 含截图、协议数据、分析结果，但未说明归档的完整性校验机制；归档损坏或部分丢失时，导入静默失败或解析出错。

**更好的实现方式**：
- 归档时生成 **manifest.json**（文件列表 + sha256 + 版本号），导入时先校验 manifest 完整性，缺失文件给出明确错误清单。
- 对跨版本归档（旧版 schema）做向前兼容：解析时按 schemaVersion 分支处理，未知字段保留为 `extra` 而非丢弃。
