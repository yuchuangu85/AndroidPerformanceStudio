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

## Compose 支持状态

当前已发布采集链路只通过反射读取未合并的 Compose Semantics，因此只能标记为 `SEMANTICS_ONLY`：它不包含全部 Composable、参数、Modifier、精确调用点或重组统计，不能宣称完整 Compose Layout Inspector 支持。

完整模式按 [Compose Inspection Capability Parity 设计](../../desktop-viewer/docs/design/layout-inspector/2026-08-08-compose-inspection-capability-parity-design.md) 分阶段实现。只有动态注入、正式认证矩阵和能力对齐门禁全部通过后，才能更新本节为完整支持。

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

## 优化项必要性审查

审查日期：2026-08-08。只有能够由当前代码、测试或已知问题证明的缺陷才进入实现；缺少测量数据的性能假设和新增产品能力不作为本轮优化范围。

| 优化项 | 结论 | 依据 |
| --- | --- | --- |
| JSON 改为 protobuf 并分块传输 | 排除 | 当前 `CaptureFrameCodec` 已使用有长度上限的二进制传输帧承载 JSON 与 PNG；没有本项目基准证明 JSON 编解码或帧体积是瓶颈，原先的体积和速度倍数也没有测量依据。 |
| auto-scan 改为自适应或事件推送 | 排除 | auto-scan 默认关闭，当前没有观察者效应或漏采的测量结果；事件推送会扩大 Agent 生命周期和协议边界，应在获得真实采集开销证据后单独设计。 |
| 静态规则阈值配置界面和预设 | 排除 | 阈值已经集中在 `AnalysisConfig` 并可注入，不是散落在规则中的常量；设置界面、项目配置和按包名策略属于尚无明确需求的产品扩展。 |
| AI Provider 抽象与脱敏 | 排除 | 已存在 `AiAnalysisClient` 边界，OpenAI 是其中一个适配器；`AiAnalysisInputBuilder` 发送文本和描述长度而非原文，并限制树深、每节点子节点数和证据节点数。 |
| 帧间节点对应 | 实施 | View 的位置路径会在兄弟节点插入、删除或重排后变化，直接按 `UiNode.id` 比较会误判节点新增、移除或属性变化。 |
| 无障碍规则集 | 排除 | 这是新增诊断能力而非现有缺陷；尤其对比度规则缺少文字颜色、有效背景和像素归属证据，当前实现无法给出可复核结论。 |
| 为 View/Compose 增加统一 `framework` 字段和 `callSite` | 排除 | sealed `UiNode` 已统一公共节点模型并区分 `ViewNode`、`ComposeNode`；新增 `framework` 重复表达类型，`callSite` 也没有可靠的当前采集来源。 |
| 归档完整性校验 | 排除 | `CaptureArchiveCodec` 已生成并校验 `manifest.json`、版本、条目列表、长度和 SHA-256，同时限制路径、条目数量和解压大小。 |

### 帧间节点对应策略

时间线比较不再把位置路径直接当作跨帧身份，而是在相同窗口、已经对应的父节点内部递归建立保守的节点对应：

1. 优先使用协议提供的非位置节点 ID、Compose Semantics ID、兄弟范围内唯一的 `resourceName` 或 Compose `TestTag`。
2. 当资源身份重复时，只允许文本或 `contentDescription` 辅助消歧；它们不能单独证明节点身份。
3. 没有强身份的节点只有在兄弟数量不变、未检测到重排且相同位置的节点类型和身份兼容时才按位置对应。
4. 多个候选同样合理时不强行匹配，而是保守地报告新增和移除。
5. 节点跨父节点移动仍报告为原位置移除和新位置新增；当前 Timeline 模型不伪装为属性变化。

该实现不修改 `UiNode.id`、实时采集协议、`TimelineNodeChange` 或 `timeline/history.json` 格式。旧 JSON 快照和 archive v1 的读取行为保持不变。
