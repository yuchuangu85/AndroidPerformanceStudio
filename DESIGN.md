# Design

## Source of truth
- Status: Active
- Last refreshed: 2026-07-08
- Primary product surfaces:
  - Compose Desktop 独立应用：`desktop-viewer/desktop-app`
  - 三栏检查器：左侧层级结构、中央截图画布、右侧属性、底部问题列表
  - 当前设计增量：已在“层级结构 / 画布命中测试”中支持候选排序切换、同点轮选、临时隐藏指定层级，使鼠标焦点可以穿透到下方层级
- Evidence reviewed:
  - `README.md`：仓库当前只实现 Desktop 方案，目标是 Android 布局复杂度检测工具
  - `desktop-viewer/README.md`：现有三栏 hierarchy/canvas/properties UI、在线采集、离线归档、画布选中边框
  - `desktop-viewer/docs/2026-07-02-desktop-viewer-design.md`：三栏检查器交互原则，树、截图、Finding、属性共享 `selectedNodeId`
  - `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt`：`HierarchyPane`、`PreviewPane`、面板标题、Header、画布点击和 hover wiring
  - `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/CanvasHitTester.kt`：命中测试当前按 z/elevation 与 child index 选择最上层命中路径
  - `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/CanvasPointerSelection.kt`：同一点反复点击可在命中路径内轮选，但没有显式隐藏/忽略上层状态
  - `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewBoundsOverlay.kt`：当前 overlay 绘制所有有效可见节点，未考虑用户临时隐藏层级
  - `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/HierarchyTreeState.kt`：已有树折叠状态，但折叠只影响左侧树显示，不影响画布命中测试
  - `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewDisplayOptions.kt`：已有视图显示偏好，适合扩展“显示隐藏层级边框/清除隐藏”等非结构性选项
  - `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewerStrings.kt`：已有英中双语字符串入口

## Brand
- Personality:
  - 工具型、精确、低干扰；优先让开发者理解真实层级关系，而不是制造动画或装饰。
- Trust signals:
  - 所有隐藏都是“临时调试视图状态”，不修改采集快照、不改变分析结果原始数据。
  - UI 明确显示当前隐藏数量，并提供一键恢复。
- Avoid:
  - 不把“隐藏层级”伪装成真实 Android View 可见性。
  - 不在层级树中永久删除节点。
  - 不依赖只有鼠标才能完成的隐藏/恢复操作。

## Product goals
- Goals:
  - 用户点击某个上层节点后，可以一键临时隐藏该节点/子树，使画布 hover 与 click 命中测试穿透到下方节点。
  - 被隐藏节点仍在层级树中可见、可识别、可恢复；隐藏状态同步影响画布边框 overlay 和鼠标命中。
  - 刷新快照、切换窗口、导入归档时隐藏状态应按清晰规则重置，避免旧节点 ID 影响新快照。
- Non-goals:
  - 不改变 Android 设备上的真实 View 状态。
  - 不新增截图编辑、透明度调节或逐层 overdraw 可视化。
  - 不替代现有树折叠；折叠仍仅控制树的显示密度。
- Success signals:
  - 对同一坐标点，隐藏顶部层级后，画布 hover/click 会命中下方层级。
  - 用户能从层级树清楚看到哪些节点已被临时隐藏，并能单个恢复或全部恢复。
  - 隐藏状态不污染 Findings、Metrics、Properties 的原始分析含义。

## Personas and jobs
- Primary personas:
  - Android 性能/布局优化开发者
  - Launcher/SystemUI/复杂页面维护者
  - 使用离线归档复盘 UI 结构问题的工程师
- User jobs:
  - 在多个重叠 View 中定位实际接收触摸或被遮挡的下层 View。
  - 临时排除全屏容器、透明蒙层、Dialog/Popup 根节点等上层结构，检查底部层级。
  - 在不重新采集、不修改设备状态的情况下快速验证层级命中关系。
- Key contexts of use:
  - 在线自动/手动刷新后的当前前台应用快照。
  - 离线导入的归档快照。
  - 多窗口快照中切换 Window 后重新定位。

## Information architecture
- Primary navigation:
  - Header：连接/刷新/自动扫描/全局面板与设置。
  - 左侧 HIERARCHY：层级选择、折叠展开、隐藏/恢复某个节点。
  - 中央 CANVAS：截图、bounds overlay、hover/click 命中。
  - 右侧 PROPERTIES：选中节点详情。
  - 底部 FINDINGS：问题列表与节点跳转。
- Core routes/screens:
  - 单窗口/多窗口 Inspector 工作区，无新增页面。
- Content hierarchy:
  - 隐藏功能归属“调试视图状态”，入口放在层级树行级操作和 Canvas 标题状态区，而不是 Header 全局主操作。
  - Canvas 标题显示当前隐藏数量，例如 `Hidden 2 · Clear` / `已隐藏 2 · 清除`。

## Design principles
- Principle 1: 隐藏是可逆、显式、局部的调试状态。
  - 隐藏只影响画布命中与 overlay 展示；原始层级、属性、指标和 findings 不被删除。
- Principle 2: 选择与隐藏分离。
  - 点击树行仍是选择；隐藏入口必须是独立小按钮/图标，避免误把选择变成隐藏。
- Principle 3: 能穿透，也能找回。
  - 隐藏后下层可被鼠标命中；被隐藏节点在树里保留占位状态并提供恢复。
- Tradeoffs:
  - 首版采用“隐藏整个节点子树”而不是“只忽略节点自身但保留子节点命中”。这样符合用户“隐藏对应层级”的直觉，也避免父节点不可见时子节点还可命中的语义混乱。
  - 不把隐藏状态持久化到用户偏好；刷新/切换快照重置可减少 stale node id 风险。

## Visual language
- Color:
  - 隐藏节点行使用 muted/disabled 风格：文本降低透明度，增加 `hidden` 状态标签。
  - 隐藏按钮使用低强调色；hover 时提高边框/文字对比。
  - Canvas 中默认不绘制隐藏节点边框；如未来加入“显示隐藏层级边框”，使用虚线或低透明 warning 色。
- Typography:
  - 沿用层级树 monospace 小字号，避免破坏当前 16dp 高密度行。
  - 行内状态短文案：`Hide` / `Show`，中文 `隐藏` / `显示`。
- Spacing/layout rhythm:
  - 层级行保持 16dp 高度；隐藏按钮宽度控制在 28–36dp，避免挤压类名。
  - Canvas 标题区延续现有 `PanelTitle` actions slot 间距。
- Shape/radius/elevation:
  - 使用现有小圆角 text button 风格；不新增浮层弹窗。
- Motion:
  - 首版无动画；隐藏/恢复即时生效。
- Imagery/iconography:
  - 首版优先文本按钮，避免新增图标资产和主题适配成本。

## Components
- Existing components to reuse:
  - `HierarchyPane` / 树行 Row：增加行内隐藏/恢复操作。
  - `PreviewPane`：接收隐藏节点集合，命中测试与 overlay 绘制过滤隐藏子树。
  - `PanelTitle` action slot：展示隐藏数量与清除入口。
  - `ViewerStrings`：新增英中双语文案。
  - `CanvasHitTester`、`ViewBoundsOverlay`：增加忽略节点集合参数。
- New/changed components:
  - `HiddenLayerState`（建议新增）：保存 `hiddenNodeIds: Set<String>`，提供 `hide(nodeId)`、`show(nodeId)`、`toggle(nodeId)`、`clear()`、`sanitize(rows)`。
  - `HierarchyLayerVisibilityButton`（建议新增 composable）：行级 `Hide/Show` 操作，点击不触发行选择。
  - `HiddenLayerSummary`（建议新增 composable 或 PanelTitle 内容）：`Hidden N · Clear`。
- Variants and states:
  - Node normal：未隐藏，可选择，可 hover/click 命中。
  - Node hidden：自身与子树在画布命中和 overlay 中被忽略；树行仍显示为 muted，按钮变为 `Show`。
  - Ancestor hidden：子孙节点在树中可继续显示但标记为“由上层隐藏影响”；首版可只在祖先行显示状态，避免所有子孙行噪音。
  - Selected hidden：允许存在；右侧属性仍显示该节点，Canvas 不绘制选中边框或改为低透明提示，避免用户误以为它仍参与命中。
  - No hidden layers：Canvas 标题不显示清除入口。
- Token/component ownership:
  - 不新增设计 token；使用 `ViewerColors` 现有 `mutedText`、`hiddenRowText`、`accent`、`border`。

## Accessibility
- Target standard:
  - 工具型桌面应用达到键盘可达、语义可读、颜色非唯一状态表达。
- Keyboard/focus behavior:
  - 树行选中后提供快捷键：建议 `H` 切换当前选中层级隐藏，`Shift+H` 清除所有隐藏。
  - Enter 保留折叠/展开，不与隐藏冲突。
  - 隐藏按钮可聚焦并有 contentDescription：`Hide this layer from canvas hit testing` / `从画布命中测试中隐藏此层级`。
- Contrast/readability:
  - 隐藏状态不仅靠颜色，还加短标签或按钮文案 `Show`。
- Screen-reader semantics:
  - 行内按钮语义包含节点 label 和状态，例如 `Hidden layer, show Button`。
- Reduced motion and sensory considerations:
  - 无强制动画；后续如加入动画需可关闭或非常短。

## Responsive behavior
- Supported breakpoints/devices:
  - 桌面窗口可拖拽三栏宽度；左侧层级可能很窄。
- Layout adaptations:
  - 层级栏宽度足够时显示 `Hide/Show` 文本；窄栏可退化为短文案 `H/S` 或仅保留 Canvas 标题清除入口。
  - 横向滚动仍只服务类名/ID；行级隐藏按钮应固定在行尾或靠近 disclosure，避免被横向滚动隐藏。首选放在 disclosure 后、label 前，保持可见。
- Touch/hover differences:
  - 当前目标是桌面鼠标；不引入触屏专属行为。

## Interaction states
- Loading:
  - 采集/刷新中不清除隐藏状态，直到新快照成功加载；成功加载后按 snapshot/window 规则 sanitize 或重置。
- Empty:
  - 无快照时不显示隐藏入口。
- Error:
  - 连接失败不改变已有离线/上次快照隐藏状态；若没有快照则隐藏入口不可见。
- Success:
  - 隐藏/恢复即时更新 Canvas hover/click、bounds overlay、树行状态。
- Disabled:
  - 根节点可隐藏，但需要确认是否会导致画布完全不可命中。首版建议允许隐藏根节点，并显示清除入口作为恢复路径。
- Offline/slow network, if applicable:
  - 离线归档与实时快照行为一致；隐藏是本地 UI 状态，不依赖设备。

## Content voice
- Tone:
  - 简短、调试工具语气，避免解释性长文。
- Terminology:
  - 英文：`layer` 用于用户可见文案，`node` 用于代码/测试。
  - 中文：用户文案用“层级”，代码注释/测试可用“node”。
- Microcopy rules:
  - 行级按钮：`Hide` / `Show`，中文 `隐藏` / `显示`。
  - Canvas summary：`Hidden {count} · Clear`，中文 `已隐藏 {count} · 清除`。
  - Tooltip/语义：`Hide this layer from canvas hit testing`，中文 `从画布命中测试中隐藏此层级`。

## Implementation constraints
- Framework/styling system:
  - Kotlin/JVM + Compose Desktop；沿用现有 `ViewerTheme`、`ViewerStrings`、轻量 presenter/state object 模式。
- Design-token constraints:
  - 不新增图标依赖、不新增主题体系；优先复用现有颜色与按钮风格。
- Performance constraints:
  - 命中测试每次鼠标移动触发；隐藏过滤必须是 `Set<String>` O(1) 判断。
  - 不在 pointer move 中重建大树索引；需要时在 composable 外或 remember 中准备 hidden set。
  - 10,000 节点离线报告仍应保持可交互；overlay 绘制跳过隐藏子树，避免遍历无效子树。
- Compatibility constraints:
  - 不改变协议模型；隐藏状态是桌面 UI 派生状态。
  - 不改变 `InspectorState.selectedNodeId` 语义；隐藏节点仍可被选中和查看属性。
  - 新增文案必须支持英文/简体中文。
- Test/screenshot expectations:
  - Unit tests:
    - `CanvasHitTester`：给定重叠上下层，隐藏上层后命中下层。
    - `CanvasHitTester`：隐藏父节点时其子树不参与命中。
    - `ViewBoundsOverlay`：隐藏节点/子树不绘制默认 overlay。
    - `HiddenLayerState`：toggle、clear、sanitize 行为。
    - `HierarchyPane` 或源码级测试：树行包含隐藏/显示入口，点击操作独立于选择。
    - `ViewerStrings`：新增文案英中都有。
  - Integration/smoke:
    - 选择上层节点 → 点击隐藏 → 同坐标点击 Canvas 选中下层节点 → 清除隐藏后再次命中上层。
  - Regression:
    - 树折叠/展开仍不影响 Canvas 命中；隐藏才影响。
    - `Hide invisible views` 现有显示选项语义不变。

## Open questions
- [ ] 行级隐藏按钮最终放在 disclosure 后还是行尾？建议首版放在 disclosure 后，保证窄栏可见；实现后用截图/手测确认不影响阅读。
- [ ] 选中节点被隐藏时，Canvas 是否完全不显示选中边框，还是用低透明虚线提示？建议首版不显示，后续根据用户反馈增加“显示隐藏层级边框”选项。
- [ ] 隐藏状态在手动刷新成功后是完全清空，还是保留仍存在的 nodeId？建议首版刷新/切换窗口清空，避免误判新快照。
