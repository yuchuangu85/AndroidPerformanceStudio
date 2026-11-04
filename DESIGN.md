# Design

## Source of truth
- Status: Active
- Last refreshed: 2026-11-02
- Scope: 共享桌面交互、主题和各功能设计增量。下文前半部分详细描述 Layout Inspector 层级隐藏；它不代表产品仅包含布局检查器。
- Primary product surfaces:
  - Compose Desktop 主入口与打包：`desktop-viewer/desktop-app`
  - Layout Inspector 功能 UI：`desktop-viewer/layout-inspector/presentation`
  - Memory Profiler 堆分析内容区：`desktop-viewer/memory-profiler/presentation`
  - 三栏检查器：左侧层级结构、中央截图画布、右侧属性、底部问题列表
  - 当前设计增量：已在“层级结构 / 画布命中测试”中支持候选排序切换、同点轮选、临时隐藏指定层级，使鼠标焦点可以穿透到下方层级
- Evidence reviewed:
  - `README.md` 与 `docs/features/README.md`：统一桌面性能工作站及当前功能范围
  - `desktop-viewer/README.md`：现有三栏 hierarchy/canvas/properties UI、在线采集、离线归档、画布选中边框
  - `docs/features/layout-inspector.md`：三栏检查器交互原则、采集、归档和时间线差异的能力边界
  - `desktop-viewer/layout-inspector/presentation/src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt`：层级、画布、详情、面板和共享选择接线
  - `desktop-viewer/layout-inspector/presentation/src/main/kotlin/com/androidperformancestudio/desktop/CanvasHitTester.kt`：命中候选与排序策略
  - `desktop-viewer/layout-inspector/presentation/src/main/kotlin/com/androidperformancestudio/desktop/CanvasPointerSelection.kt`：同点候选轮选和隐藏状态过滤
  - `desktop-viewer/layout-inspector/presentation/src/main/kotlin/com/androidperformancestudio/desktop/ViewBoundsOverlay.kt`：可见节点和本地隐藏状态的边框绘制
  - `desktop-viewer/layout-inspector/presentation/src/main/kotlin/com/androidperformancestudio/desktop/HierarchyTreeState.kt`：树折叠仅影响左侧显示，不改变画布命中含义
  - `desktop-viewer/layout-inspector/presentation/src/main/kotlin/com/androidperformancestudio/desktop/ViewDisplayOptions.kt`：视图显示偏好
  - `desktop-viewer/layout-inspector/presentation/src/main/composeResources/values*/strings.xml`：已有英中双语资源，并通过 `Res.string` 直接访问

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
  - 所有自有桌面界面的字号只允许三档，统一定义在 `desktop-viewer/ui-components/src/main/kotlin/com/androidperformancestudio/ui/ViewerTheme.kt` 的 `ViewerTypography`：标题、卡片标题和关键数值 16sp；正文、普通控件和小节标题 13sp；辅助说明、状态、密集表格和图表标签 11sp。
  - 沿用现有语义角色和适合各位置的行高，不通过增加字号建立新层级；同档标题使用字重、间距区分。层级树保留 monospace 和 16dp 高密度行。
  - Material3 的全部默认文字样式（含 display）以及自有 Canvas、浏览器错误页均复用公共三档；第三方 Perfetto/Firefox viewer 的内部 UI 和设备采集截图不在自有主题控制范围内。
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
- Shared device selection across workspaces uses `DeviceDropdownSelector`: empty/automatic controls display `设备` / `Device`, selected controls retain the caller's actual device label, and accessibility uses `设备选择器` / `Device selector`. Layout Inspector keeps an explicit automatic-selection menu action. Serial-number input fields are not device dropdowns and retain their distinct purpose.
- Existing components to reuse:
  - `HierarchyPane` / 树行 Row：增加行内隐藏/恢复操作。
  - `PreviewPane`：接收隐藏节点集合，命中测试与 overlay 绘制过滤隐藏子树。
  - `PanelTitle` action slot：展示隐藏数量与清除入口。
  - Compose Resources `Res.string`：新增英中双语文案。
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
  - Kotlin/JVM + Compose Desktop；沿用现有 `ViewerTheme`、Compose Resources `Res.string`、轻量 presenter/state object 模式。
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
    - Compose Resources `Res.string`：新增文案英中都有。
  - Integration/smoke:
    - 选择上层节点 → 点击隐藏 → 同坐标点击 Canvas 选中下层节点 → 清除隐藏后再次命中上层。
  - Regression:
    - 树折叠/展开仍不影响 Canvas 命中；隐藏才影响。
    - `Hide invisible views` 现有显示选项语义不变。

## Open questions
- [ ] 行级隐藏按钮最终放在 disclosure 后还是行尾？建议首版放在 disclosure 后，保证窄栏可见；实现后用截图/手测确认不影响阅读。
- [ ] 选中节点被隐藏时，Canvas 是否完全不显示选中边框，还是用低透明虚线提示？建议首版不显示，后续根据用户反馈增加“显示隐藏层级边框”选项。
- [ ] 隐藏状态在手动刷新成功后是完全清空，还是保留仍存在的 nodeId？建议首版刷新/切换窗口清空，避免误判新快照。

## Desktop Form Inputs (2026-11-02)

- Ordinary single-line app-owned form fields use shared `ViewerTextField` and `ViewerDimensions.textFieldHeight` (32dp), replacing the 56dp Material default. Use 8dp horizontal and 4dp vertical input padding, shared 13sp body text and the existing theme accent/border colors.
- Labels remain above the outlined input in the shared 11sp supporting tier; supporting/error text remains below. Their space is not included in the 32dp input area, so long labels and validation messages may wrap without crowding text or the cursor.
- 32dp is a minimum, not a clipping constraint: larger font scales and explicitly multiline fields may grow. Preserve focus, selection, IME composition, keyboard actions, password masking, validation, disabled/read-only states and existing callbacks.
- Already compact 20-24dp toolbar/search inputs and dedicated multiline chat/code editors keep their established dimensions and behavior. Embedded third-party web inputs are outside this change.
- Settings, source-workspace dialogs, benchmark dialogs, Battery session search and AI management/rename inputs share this component. New ordinary form fields must not reintroduce page-local Material height defaults.
- Verify equal input-area heights before/after focus in light/dark themes, English/Chinese, narrow/wide layouts, long paths and scaled fonts; retain screenshots and keep packaged/native-window acceptance separate from Compose tests.

## Startup Style Adoption (2026-10-15)

The implemented Startup Profiler is the visual baseline for all other desktop workspaces. This changes visual language and shared component usage, not feature behavior or the Inspector interaction contract above.

Evidence reviewed:
- `desktop-viewer/startup-profiler/presentation/.../StartupWorkspaceControls.kt`, `StartupWorkspaceMetrics.kt` and `StartupProfilerScreen.kt`.
- `.omx/artifacts/shared-card-migration/startup-1280.png`.
- `desktop-viewer/ui-components/.../ViewerTheme.kt`, `studio/StudioTokens.kt`, `StudioCard.kt` and `StudioPanel.kt`.
- `desktop-viewer/docs/aps-commercial-ui-implementation/00-shared-foundation-and-delivery.md` and `07-startup-profiler.md`.

Adoption contract:
- Active `AppDestination` content areas do not render a decorative top `StudioWorkspaceHeader`; existing toolbars, tabs, panels and navigation retain page context. Nested Battery/Network content remains banner-free.
- `StudioPanel`, `StudioDataTable`, `StudioMetricCard` and `ProfilerMetricCard` use the compact Startup density by default. Explicit chart gutters, splitters, table rows and virtualization contracts remain feature-owned.
- `ViewerTheme` keeps the global navigation rail, window strip and every page workspace on the same sidebar `toolbar` color in light and dark themes; panels, borders and Material surface roles remain centrally owned. Feature screens do not install replacement themes or local palette transforms.
- The optional `StudioWorkspaceHeader` remains a shared control for scoped future surfaces, but is not rendered by active page roots.
- Repeated cards use `StudioCard`; structural panes, tables, toolbars, canvas surfaces and Inspector hierarchy rows do not become decorative nested cards.
- Existing capture, import, device, trace, source-resolution, selection, error, loading, unavailable and recovery behavior remains unchanged. No missing metric or evidence is fabricated.
- Minimum validation includes 1280x720 and narrower stacked windows, English/Chinese, light/dark themes, compact content overflow checks, shared color/inset tests, affected module tests, integration compilation and automated screenshots. Native packaged-app and real-device acceptance remain separate gates.

Implementation plan and evidence are kept under `.omx/artifacts/startup-style-adoption/plan.md` and `.omx/artifacts/startup-style-adoption/evidence.md`.

## Memory Analysis Content Cards (2026-10-22)

This is a user-requested, Memory-only refinement of the Startup adoption contract above. Separate analysis regions use card boundaries; table rows and object fields do not become nested decorative cards.

Evidence reviewed:
- `desktop-viewer/docs/aps-commercial-ui-implementation/05-memory-profiler.md` for the heap-evidence and three-pane interaction boundaries.
- `MemoryInvestigationWorkspace.kt`, `MemoryProfilerClassListView.kt`, `MemoryObjectInspector.kt`, and `MemoryProfilerScreen.kt` in the Memory presentation module.
- Shared `StudioCard`, `StudioTokens`, and the existing English/Chinese, light/dark workspace screenshots.
- Current user requirements: remove content-area import/export buttons, keep mapping/comparison at the end of the top toolbar, keep the summary compact, and present analysis regions as cards.

Adoption contract:
- Four compact metric cards, one heap-distribution card, and separate filters, classes, instances, and object-inspector cards define the class-list workspace. Dominators and snapshot comparison use the same card boundary for their central analysis pane.
- Reuse `StudioCard` and its theme-owned panel/border colors and 8dp radius. Use `StudioTokens.cardGap` (4dp) between regions and `workspacePadding` (4dp) at the workspace boundary; do not add a local palette or shadows.
- Metric label/value stacks remain content-sized, with 4dp card padding and no inter-line spacer; the summary must not regain its former 76dp fixed height.
- Structural cards with their own internal layout use zero extra card padding/spacing to avoid doubling table and inspector insets. Existing row density, lazy lists, class/instance selection, filters, object navigation, and class-level-only diff semantics remain unchanged.
- Keep the existing horizontally scrollable minimum-width three-pane workspace for narrower windows. Preserve independently scrollable filters/tables and the footer; do not stack constrained lazy lists into an unbounded outer vertical scroller.
- The instance table fills its card's available width, with a 650dp minimum for horizontal scrolling in narrow panes. Header and row content retain aligned 8dp internal insets; a fixed-width centered table must not introduce extra side gutters.
- Toolbar, snapshot tabs, provenance/evidence strip, and status bar remain chrome rather than analysis cards. Existing dedicated Bitmap, Native Heap, LeakCanary, and Dashboard surfaces are not redesigned by this refinement.
- Validate compact metrics and card separation at 1280x720, horizontal reachability at 900x720, English/Chinese, and light/dark themes. Review generated screenshots, run affected tests/static checks and desktop integration compilation; native packaged-app acceptance remains a separate gate.

## Window Title-Bar Device and Process Targets (2026-10-25)

This supersedes page-owned title-bar targets: the shell now owns one application-wide device and process target that stays fixed while pages change. Capture sessions and per-feature controllers remain page-owned.

- On macOS, the main window retains native close/minimize/full-screen buttons and always shows the same two controls on the right side of its app-painted title strip: a device selector and a Process selector. Switching pages must not remove or relabel the slots; CPU, Startup and Battery use the Process slot for their application/component/App-UID capture targets as described below. The two controls are the only device/process controls in the app shell. The title and native-button area stay unobstructed.
- The shell discovers both lists through the shared ADB target monitor and refreshes devices and the selected device's application processes automatically on an interval (single connected device selected automatically). There is no manual refresh action in the title bar; pages keep their own refresh behavior for page-level data only.
- By default the Process picker lists the selected device's application processes (main and sub-processes of installed applications); platform/native services without an owning package are excluded. Application processes outside the page's selected application remain selectable. Pages follow the shared selection in their controllers (device, then process).
- CPU refinement (2026-10-29): while CPU is active, the same Process slot shows CPU's searchable installed-application list, including apps without a running PID, and selects an application capture target through the existing app-selection action. Its separate page-level App selector is hidden in the hosted window. Retain the slot's label, width and keyboard semantics; disable it while CPU loads, captures, has no apps, or is switching devices. Selecting an app clears stale shared PID selection so periodic process discovery cannot overwrite it. Leaving CPU restores the default process list, including when the CPU page remains retained; standalone/native-frame windows keep the original inline App fallback. Capture actions, sampling settings, and imports are unchanged.
- Startup refinement (2026-10-29): while Startup is active, Process shows the searchable Application / Component entries with the same package/activity labels and full component details; hide the separate page-level Application / Component entry in the hosted window. Preserve exact component identity for apps with multiple launch activities, including stopped apps. Disable selection while refreshing/running, with no targets, or during device handoff. Explicit component choices survive process refresh and page retention; clear shared PID selection when adopting a component, and restore the normal Process list on exit. Standalone/native-frame windows retain the original inline fallback; experiment configuration, start/stop, imports, and exports are unchanged.
- Battery refinement (2026-10-29): while Battery is active, Process shows the searchable App / UID entries with the same localized package, UID and shared-UID labels; hide the separate page-level App / UID entry in the hosted window. Keep package identity independent from UID so shared-UID packages remain separately selectable, including apps without a running process. Disable selection while refreshing, loading a session, running any capture mode, with no targets, or during device handoff. Clear stale shared PID selection when adopting an app so process refresh cannot overwrite it. Restore the normal Process list on exit, including retained pages, and keep the inline fallback outside hosted windows. Capture configuration, attribution semantics, historian and file actions remain unchanged.
- Windows uses a JBR custom title bar when the runtime supports it: retain the existing native menu model and shortcuts, embed its Swing menu bar into one app-themed title row, leave a draggable title region, and reserve the runtime-managed caption-button area. Do not replace caption buttons with Compose buttons or make menu targets draggable. Unsupported runtimes retain the standard frame and menu bar. Windows device/process selectors stay inline; this change does not adopt the macOS selector host. Linux and standalone feature windows retain their existing controls.
- Pin and verify the Windows JBR runtime used for packaging. Keep macOS/Linux runtimes unchanged. Test menu identity, live page/language updates, keyboard actions, cleanup and fallback locally; Windows-native DPI, maximize/restore, resizing, dragging and Snap Layouts require Windows acceptance and are not proven by macOS rendering tests.
- Title-bar selectors use bounded widths and a fixed window-point scale so long device/process names cannot cover the native controls or push the window title out. Keyboard/focus semantics and dropdown opening must be checked in a real macOS window in addition to focused Compose tests.
- An unavailable list keeps its selector disabled and shows the localized placeholder instead of a clickable dead control.


## Compact Card Exterior Spacing (2026-10-22)

User-requested refinement across every card-containing Desktop workspace, including Home, profiler pages, source-workspace cards, and settings. This supersedes earlier 8dp exterior spacing without changing card content density.

- Shared ownership: `StudioTokens.workspacePadding = 4.dp` and `StudioTokens.cardGap = 4.dp` define the fixed 4dp exterior rhythm. Preserve `compactContentPadding` and `panelGap` at 8dp for card internals and non-card controls.
- Latest user override: normalize all card exterior margins and gaps to exactly 4dp, including previously custom values. Do not stack wrapper padding and list padding into larger effective card gaps; structural non-card chrome and splitter hit regions remain unchanged.
- Apply to horizontal/vertical card grids, nested card lists and card groups at the content area's four boundaries. Toolbars, navigation, footer chrome, table-row density, chart axes, splitter hit targets, and card internal text/control padding remain unchanged.
- Reuse `StudioCard`, `StudioPanel`, existing theme/color/radius ownership and current responsive/scroll behavior. No new dependency or parallel styling layer.
- Validate all four exterior edges and both card-gap directions with rendered geometry at 1280x720 and 900x720 in light/dark themes, preserve 8dp default content insets, inspect generated screenshots, run affected tests and integration compilation. Automated render checks do not imply native packaged-app acceptance.

## Offline Leak Investigation (2026-10-24)

User-requested Android Studio-style leak analysis supersedes the earlier exclusion of the dedicated LeakCanary page from redesign. The reference is the official heap-dump investigation workflow, not a supplied pixel-exact screenshot.

- Bind analysis to the selected immutable HPROF snapshot, including when an older open snapshot is selected. Offline analysis requires no device or injected agent. Keep candidate detection distinct from a proven application bug.
- Use a compact analysis toolbar and independently scrollable findings, reference-path, and object-inspector panes. Prefer dense rows, column headers, restrained selection, monospace object identities, and existing theme tokens over summary-card stacks.
- Show GC-root-to-retained-object paths, suspect references, retained bytes/counts, and available lifecycle reasons. Navigate only using original object IDs from the same HPROF; never resolve an instance by its class name.
- Keep live Agent start/stop and live events explicitly separate from offline snapshot evidence. Opening the leak view must not start device monitoring implicitly.
- Preserve findings per open snapshot. Loading, cancellation, recoverable failure/retry, no candidates, and no retained paths have distinct states. Missing metrics and identities remain unavailable.
- Keep all panes reachable at narrow widths through horizontal scrolling or explicit pane selection. Validate 1280x720 and 900x720, English/Chinese and light/dark, object navigation, and analysis state controls with Compose tests and inspected screenshots. Real Android dump coverage, runtime monitoring, and native packaged-app acceptance are separate gates.

## Heap Classifier Hierarchy (2026-10-26)

- The main class table below the Leaks summary retains `App heap`, `Image heap`, and `Zygote heap` parent rows. A single-heap filter keeps that heap parent; All displays each available heap separately.
- All initially shows collapsed heap roots so every heap is visible; a single-heap selection initially expands its class tree. Expanding a heap header does not materialize every instance in that heap.
- Build each heap subtree from its own histogram. A class present in multiple heaps has independent counts, sizes, row identities, and instance navigation; never repeat all-heap totals beneath each heap.
- Match the locally installed Android Studio heap-dump classifier: first-open sorting is Retained Size descending; the current column comparator applies to heap roots and to siblings within each heap. Name sorting is case-sensitive by simple class name, with package names displayed separately in parentheses. Equal keys retain their existing order. Unknown retained metrics remain unavailable, rather than summing overlapping class dominator sizes.
- Selecting a heap, group, class, or original object preserves the exact heap scope for instance queries, including All mode. Keep existing search, issue filters, warnings, snapshot guards, and horizontal scrolling.
- Verify App/All switching, sibling sorting, same-name classes across heaps, grouped navigation, and leak markers with focused tests and the real HPROF fixture. This is the main heap class table, not the separate Shark findings list.

## HPROF Engine Selection (2026-10-30)

- Settings > Configuration owns the persisted HPROF engine selector: Kotlin (Legacy) or Rust. Rust is the default as requested on 2026-10-30; an explicitly saved Legacy choice remains respected. A change affects subsequent loads, never silently replaces an open snapshot or discards its investigation state.
- Retain the Kotlin parser/analyzer and their explicit selectable path. Rust owns its indexed graph in an isolated child process; snapshot evidence records the engine actually used. An unavailable selected engine is an explicit error, not an unreported fallback.
- Rust instance lists use bounded pages with global counts, previous/next icon controls and stable object navigation. Retain the complete Legacy list behavior. Group queries preserve heap scope and class-local row ordinals across pages.
- Rust dominator trees page root objects and immediate children independently. An unfetched branch is not a leaf; fetched empty children establish the leaf state. Class metadata remains in analysis while visible row membership matches Legacy.
- Preserve typed fields, arrays, incoming-reference grouping, nearest-root semantics, findings, mapping, source-bound Shark and exports. Native/reachable HPROF class metrics are distinct from Native Heap capture and remain nullable when verification is unavailable.
- Closing or evicting a snapshot closes only its engine and releases its immutable source leases. Keep original user artifacts and existing captures unchanged.

## Memory Overview Scope (2026-10-26)

- Rename the previous Leaks & Bitmaps dashboard to Overview / 概览. Retain only heap overview metrics and the class histogram; remove its leak suspects, Activity leaks, Bitmap, Native Heap, and Diff summaries entirely.
- Keep Classes, Dominators, Diff, Memory Leaks, Bitmaps, Native Heap, and ART Samples as independent investigation views. The overview removal does not delete analysis results, class-list warnings, capture commands, exports, or offline/live leak workflows.
- Remove the top-level Compare shortcut because Diff already owns navigation. Preserve the Diff page's before/after snapshot selection and its actual Compare command; Mapping remains a toolbar action.
- Lock the reduced Overview with populated analysis data, both languages/themes, and narrow/desktop layouts. Verify that dedicated investigation pages remain reachable and functional.

## Memory Capture And Analysis Groups (2026-10-26)

- Keep Capture and Analysis commands on one compact toolbar row, separated by 8dp of whitespace, without rendering group titles. Reuse shared header height, button styles, typography, and theme colors without changing command behavior.
- Capture contains target selectors/refresh when needed, Dump Heap, Capture Native Heap, Record ART Samples, Dump Bitmaps, and the Agent setup action. Agent injection configures capture prerequisites; it does not start monitoring or analyze a snapshot.
- Analysis contains the existing primary view selector, Bitmaps, Native Heap, ART Samples, Memory Leaks, and Mapping. Preserve each view's selected/disabled state and the explicit analysis/monitoring actions inside the leak page. Do not restore the removed Compare shortcut.
- Keep the combined command row horizontally scrollable at narrow widths. Verify the 8dp group gap, absence of labels, localized command text, geometry, disabled states, and callbacks in light/dark screenshots and tests.

## Classes Without Filters Pane (2026-10-26)

- Remove the Classes view's left Filters pane, including its column toggles and saved-filter controls. Expand the class and instance tables into that space without an empty placeholder.
- Preserve the primary heap/scope/issue/arrangement/search bar, sorting, object inspector, and narrow-width scrolling. Dominators and Diff retain their search Filters panes.
- Verify empty and populated Classes layouts in both languages, with primary-filter callbacks and object navigation unchanged.

## Instance List And Details (2026-10-26)

- Keep the Instance List flat: fixed-height selectable rows, without disclosure arrows or inline GC-root paths. Selection updates the right-hand Instance Details pane using the original snapshot-local object ID.
- Instance Details conditionally exposes Fields and References tabs when their data is available. Fields shows the selected object's field values with inline object expansion and cumulative array batches. References owns the unchecked-by-default Show nearest GC root only checkbox and incoming-reference tree, with no separate GC-root path section.
- Match the installed Android Studio nearest-root predicate: include incoming references whose source has a known root distance strictly less than the inspected object's distance. Do not treat unknown depth as zero or simply replace the reference list with a single path.
- Keep the chosen Fields/References tab and nearest-root checkbox state within a snapshot when selecting another instance; reset on snapshot changes. If the chosen tab is unavailable for an object, show its first available tab without overwriting the preference. Tree expansion and row selection remain object-local. Both class-list entry points use the same details component; native details parity supersedes the former custom history, pinning, identity, and information strips.
- Cover row selection without expansion, Fields/References switching, nearest-reference filtering, unavailable roots, array pagination, and snapshot-local state in Compose and query/controller regressions. Keep native-window acceptance separate from rendered screenshot checks.

## Memory Heap Table Density (2026-10-27)

- Use the installed Android Studio 2026.2.3 profiler as the behavioral reference: classifier and instance tables enable vertical column lines with zero intercell spacing, and row height is font height plus 4dp. In Compose, use the shared compact 16sp line height plus 4dp, respecting font scale; keep headers at 24dp.
- Overview histogram items use the same row-height helper and compact text style as the Classes table: 20dp at default font scale, growing with text size. Do not retain an independent 32dp item height; keep row contents single-line and vertically centered.
- Draw continuous theme-owned column dividers through headers, rows, selections, and unused table space. Indent only the class-name cell so heap/package depth and warning icons cannot shift numeric columns. Keep rows square and numbers right aligned.
- Reserve bottom space in the Classes table only when its columns actually overflow horizontally, using the scrollbar's actual thickness. Otherwise extend the table to the Classes/Instance List height divider without a blank scrollbar gutter. Preserve the existing vertical scrollbar lane and draggable divider hit area.
- Instance names carry the actual runtime class from the query, including grouped selections and arrays, plus the original object ID in decimal and hexadecimal. Class objects additionally carry their represented type for the Studio-style `(class)` label, without changing their runtime type. Never substitute a package or heap label for an object's class. Retain independently scrollable instance tables in both entry points.
- Keep Instance Details compact with Fields/References immediately below its native-style title. Metrics are aligned table columns instead of the former facts strip and information popover. Preserve snapshot-local identity, unknown-value semantics, reference filtering, and cumulative array expansion.
- Validate separator pixels and geometry, compact row height, grouped runtime names, narrow-width scrolling, field/reference navigation, and English/Chinese light/dark rendering. Local Compose rendering is not native Studio pixel-comparison acceptance.

## Instance List Split Layout (2026-10-27)

- In Classes, place the full-width class table above one full-width Instance List region. Under its shared title, arrange the Instance table on the left and Instance Details on the right; do not keep a full-height inspector beside the class table or nest decorative cards.
- Share the same instance/details split between the active workspace and the legacy class-list entry point. Start the left pane at 62 percent and use a 7dp vertical drag target with a horizontal-resize cursor. Support keyboard arrows and accessibility adjustment, and retain the ratio across object selection and recomposition.
- Reserve 280dp for the instance viewport and 260dp for details when space permits; fit the bounds to the available width on smaller surfaces. The Classes workspace now adapts down to 650dp instead of reserving the former 1120dp three-column width; tables retain their own horizontal scrolling. Window resizing must not produce negative widths or hide either pane.
- Give the stacked class and instance regions equal remaining height so the lower details table has usable space. Dominators and Diff keep their existing full-height inspector layout. Verify drag directions, width bounds, locale/theme changes, narrow reachability, selection, and detail navigation.

## Instance Details Inline Expansion (2026-10-27)

- Clicking object-valued fields or incoming references only expands or collapses that branch in Instance Details. Never route these content clicks through instance selection, class selection, table replacement, or navigation history. The Instance List remains the owner of selected root identity; the native context-menu Go to Instance command is an explicit navigation action.
- Load nested object details on demand through a separate snapshot-bound query. Loading, missing objects, and empty fields or references are distinct states. Clear loaded inspector objects when the selected root or snapshot changes, and discard late results that no longer belong to the active inspection.
- Keep branch identity path-local, including repeated objects and array pages. Fields expands fields; References expands inbound references and applies the nearest-root predicate against each expanded object's depth. Render visible entries in the existing lazy list, stop ancestor cycles with a clear marker, and bound expansion depth to 32.
- Primitive and null values have no expansion action. Nested arrays page locally without selecting the array or changing the outer Instance List. Preserve dense rows, column alignment, tooltips, the resizable split, and the selected root's own array pagination.
- Verify multi-level expansion/collapse, shared targets on different branches, cycles, unavailable data, nested array paging, root/snapshot reset, and stale asynchronous completions. Assert that content clicks leave class, instance rows, selected object, history, and split width unchanged.

## Compact Snapshot Strip (2026-10-27)

- Reduce the Snapshots row from 42dp to a 32dp minimum and each snapshot tab from 40dp to a 28dp minimum. Use shared compact typography and allow taller text at increased font scales instead of clipping it.
- Preserve the existing 28dp close target, snapshot selection and independent close callbacks, evidence tooltip/accessibility text, one-line ellipsis, and narrow-window horizontal scrolling.

## Unified Heap Analysis Card (2026-10-27)

- Combine the Classes table and Instance List into one shared StudioCard. Keep both sections unframed, with one full-width horizontal divider between them; preserve the equal-height split and the instance/details drag handle.
- Keep the common card 4dp below the preceding region and 4dp above the status bar. Do not reserve an empty outer scrollbar strip when the workspace fits its viewport.
- When a very narrow workspace needs horizontal scrolling, reserve the scrollbar inside the shared card so its outside bottom spacing remains unchanged. Dominators and Diff keep their existing card and scrollbar layouts.
- Verify a single shared frame, divider geometry, equal top/bottom spacing, empty and loaded data, narrow scrolling, both locales/themes, instance selection, and inline details expansion.

## Resizable Class And Instance Heights (2026-10-27)

- Make the horizontal Classes/Instance List divider draggable. Keep a centered 1dp visual line inside a 7dp hit target with a vertical-resize cursor, keyboard up/down adjustment, and accessibility progress semantics.
- Start with equal upper/lower heights and retain the user's ratio across instance selection, recomposition, and window resizing. Keep the height ratio independent of the left/right instance/details ratio and inline expansion state.
- Reserve at least 100dp for Classes and 180dp for Instance List when space permits; fit both bounds to smaller windows without negative dimensions. Keep the shared card and its existing 4dp exterior spacing unchanged.
- Verify pointer drags in both directions, minimum bounds, keyboard adjustment, resize restoration, instance selection, inline expansion, and independent horizontal resizing in both languages and themes.

## Compact Heap Filter Padding (2026-10-27)

- Halve the All heaps filter row's vertical padding from 6dp to 3dp per side. Preserve control heights, 8dp horizontal padding/gaps, all filter callbacks, and the workspace's existing 4dp exterior spacing. At the default scale the row becomes 38dp high instead of 44dp.
- Follow-up (2026-10-28): remove the remaining 3dp top/bottom padding from the All heaps / All classes row. The row is now 32dp high at the default scale; retain control sizes, horizontal spacing, scrolling, callbacks, and surrounding workspace spacing.

## Native Instance Details (2026-10-28)

- Use the locally installed Android Studio 2026.2.3 profiler as the reference. Show `Instance Details - SimpleType@decimal (0xhex)` and a close control; remove the custom facts, provenance, pin, copy, and history strips from this pane. Closing clears instance selection only, preserves the class and instance rows, and restores their full width. Hide the details pane and splitter when no inspectable fields or references exist.
- Match the native tree-table columns: Instance, Depth, optional Native Size, Shallow Size, optional Retained Native Size, Retained Size. Use raw grouped byte counts, `-` for unavailable evidence, continuous column dividers, resizable widths, and font line height plus 4dp rows. Fields defaults to descending retained size; References defaults to ascending depth. Apply sorting to expanded siblings too.
- Fields hides its root and formats rows as `field = {SimpleType}`, `count = 7`, `next = null`, and `{String} "preview"`; array elements use bare indices. Group incoming references by source object and label them `fieldA, fieldB in Owner@decimal (0xhex)` or `Index 0, 3 in Foo[]@decimal (0xhex)`. References shows the selected root, initially expanded.
- The unchecked-by-default nearest-root control recursively retains sources with known depth strictly less than their parent's depth and expands the first decreasing-depth path when enabled. Do not fabricate paths or allocation/deallocation tabs for HPROF.
- Expand fields cumulatively in 100-row batches using an inline Click to see next N action; no Previous/Next pager or selection change. Read primitive-array values lazily in bounded batches, keep NODATA unavailable, and show bounded String previews from recorded primitive payloads without eagerly copying the whole heap.
- Field metrics belong to the target object; inbound metrics belong to the source. Primitive rows use their encoded byte width and parent depth, null rows have zero sizes, and missing retained-native values remain unknown. Keep the existing ancestor-cycle and depth-32 guards as an explicit safety difference from native repeated wrappers.
- Verify data decoding, labels, metric provenance, sorting, resize bounds, close behavior, cumulative expansion, async snapshot guards, and ordinary-click versus explicit navigation semantics. Render English/Chinese light/dark and narrow layouts; these checks do not claim native-window pixel equality or packaged-app acceptance.

## Memory Language Changes (2026-10-28)

- Changing the UI language must not recreate the Memory controller or backend, reload imported artifacts, clear snapshots or analysis, change the selected class/instance or filters, or close active device/agent subscriptions. Preserve the details tab, expanded branches, cached evidence, and split sizes while updating UI labels.
- Update the controller/backend language in place for subsequent localized messages. Preserve existing evidence and in-flight imports, captures, and analyses; normal page disposal remains responsible for cancelling work and closing subscriptions.

## Trailing Leak Warnings (2026-10-28)

- Place warning icons after leak labels and retained-instance titles, keeping short labels and icons adjacent. Give long labels bounded text space with ellipsis so the fixed-size warning remains visible.
- In class and instance tables, place warnings at the trailing edge of the Class Name / Instance cell, before numeric columns, not at the end of the whole table. Reserve icon space inside the name cell so long text ellipsizes without hiding the warning. If a wide name column is only partially visible, keep its icon within the visible part of that same column. Do not add a table-wide warning column or alter metric widths. Dedicated leak findings retain their row-end markers.
- Preserve 20dp table rows, sorting, selection, grouping/expansion, column alignment, tooltips/accessibility, and existing warning-evidence rules. Cover long names, narrow panes, horizontal scrolling, both entry points, and English/Chinese light/dark rendering.

## Filled Memory Analysis Workspaces (2026-10-28)

- Overview's class histogram fills the available height beneath its metrics, with a 4dp gap above the status bar. Keep its title and sort headers outside the independently scrolling rows; do not cap the table at 300dp or scroll the whole overview to reveal the table.
- In Dominators, keep Filters in a separate full-height pane and combine the dominator tree and Instance Details into one zero-padding StudioCard with an unframed split interior. All panes share a bottom edge, 4dp above the status bar when the workspace fits; do not reserve a hidden horizontal-scrollbar strip.
- Start the tree/details ratio at 62 percent. Use a centered 1dp vertical divider in a 7dp drag target, horizontal-resize cursor, keyboard left/right steps, and accessibility progress. Reserve at least 260dp per pane when space permits, and retain the user's ratio across selection, search, language, and window-size changes. Keep very narrow layouts horizontally reachable without negative widths.
- Let the dominator list and details table use their full pane height. Reserve scrollbar height only for real horizontal overflow, not as permanent bottom padding. Preserve tree expansion, object identity, field/reference behavior, filtering, and overview sorting/highlighting.

## Leak Analysis Naming And Spacing (2026-10-28)

- Name the Memory toolbar entry and report page Leak Analysis / 泄漏分析. Keep the existing internal view/resource identifiers and analysis behavior; this is not a change to candidate detection or evidence semantics.
- Match other Memory workspaces' 4dp bottom exterior spacing above the status bar. Findings, reference paths, and Instance Details fill the available height; reserve the actual horizontal scrollbar height only when the workspace is narrower than its content.

## Status Feedback Visibility (2026-10-28)

- Remove the app-shell global status bar and its permanent 28dp reservation. Navigation and retained workspace content extend to the window's bottom edge; do not replace the removed bar with a spacer. Destination and artifact identity remain in existing navigation/workspace surfaces.
- Memory shows its own compact footer only during an operation, instance query, error, or nonblank warning/cleanup warning. Blank messages do not reserve height. Running captures/imports still expose Cancel even before a progress message arrives; errors retain Retry. Keep horizontal scrolling and selectable/copyable status text.
- When Memory's footer is hidden, analysis content ends 4dp above the page bottom. When feedback appears, the same 4dp gap applies above that footer. Showing/hiding feedback must not clear snapshots, selection, details tabs, expansion, or splitter ratios. Other profilers' feature-owned feedback is not removed with the global shell bar.

## CPU Stack Chart Bounds (2026-10-28)

- The standalone Stack Chart has no track-label column, so its plot starts at the content's left edge instead of reserving the timeline's 150dp label gutter. Keep the timeline's real labels unchanged.
- Drawing, block semantics, hit testing, and range dragging share the same plot bounds. Preserve the time viewport and genuine intervals without samples, along with row gaps, labels, selection, zoom, and scrolling.

## Layout Inspector Compose Agent Consent and Diagnostics (2026-10-31)

- `Compose Agent` is an explicit, session-only opt-in, off by default. The off path must not prepare or inject the Compose JVMTI agent. Compatible View capture and offline Compose archive inspection remain available; disabling cannot undo prior target-process side effects.
- Show a localized confirmation after preflight, before injection or inspector download, with device, package, PID, API, ABI, Compose version, inspector source, and download/restart requirements. Only confirmation for the still-current request and target grants authorization. Cancellation, device changes, System UI capture, and archive import revoke live consent; a changed app/process needs new confirmation.
- Disabling during preparation or attachment cancels the current flow and releases owned resources. A cancelled or superseded request must not publish a frame, reset a newer request, or overwrite imported evidence. A failed full capture may fall back to compatible capture without silently reauthorizing.
- Errors, warning reasons and operation diagnostics use shared `CopyableText`, preserving text, semantic roles, layout and neighboring actions. Support normal text selection plus localized full-message context-menu and accessibility actions; visual ellipsis must not truncate copied content. Navigation and button labels remain ordinary controls.
- Compose attach warnings keep a compact summary and explicit, keyboard-accessible details toggle. Expanded details wrap and scroll within a bounded height, preserving the diagnostic tail and inspector workspace. A changed reason collapses old details. Tokens are redacted before a reason reaches the UI, copy action or logs.
- An Android `ActivityThread` classloader attempt is diagnostic context, not proof of terminal failure. Socket readiness determines success within a bounded wait; diagnostic log timestamps alone do not prove which attach attempt failed. Automated host/Compose tests, native interaction, and Windows/device attachment are separate acceptance evidence.

## CPU Chart Firefox Parity (2026-10-30)

- The user's chart-specific override makes Firefox Profiler the behavior and rendering reference for native CPU Flame Graph and Stack Chart. Review baseline: upstream `a73f07c297386c77b880c3f4b9d07571953c535e` (2026-10-29). Preserve the Compose application shell, Android evidence and localization. The latest UI-parity request supersedes the earlier standalone left-edge exception: Stack Chart now shares the 150dp timeline gutter and 15dp right margin.
- Flame Graph has no horizontal zoom or pan. W/S move vertically; arrows select call nodes, with left/right traversing the entire depth row. Hover, selection and context states use category fill/text, without an additional node outline. Graph labels use the upstream 10 CSS-pixel font as a chart-local exception to the shared supporting-text tier.
- Both charts share call-node identity, stack filtering, transforms and source/detail actions. Implementation filtering precedes comma-separated AND stack search. Stack Chart dims functions that match none of the search terms; selected or hovered functions retain category color. Excluded samples remain genuine time gaps.
- Stack Chart offers time-proportional and equal-stack-width presentation. Equal widths change only display coordinates; selection, tooltips, range actions and stored sample facts retain original timestamps and weights. Coalesce only equal call-node paths, not equal frame names beneath different callers.
- Pointer interaction uses one canvas surface. Drawing and semantic nodes are restricted to visible depth/time and pixel eligibility; hidden source blocks remain available through exact indexed hit testing and zoom. Selection and hover do not rebuild the snapshot or query SQLite.
- Full parity requires separately recorded source conformance, deterministic visuals, native input/performance, platform accessibility and cross-host evidence. Passing Compose tests alone is not a claim that all Firefox features or platforms are identical.
- Call Tree and Flame Graph keep independent inversion preferences. Stack Chart always uses forward call stacks and committed-range node metadata, even when its preview contains no sample timestamps. Preview results must not disable interactions on a visible interval.
- Viewport navigation uses continuous frame-driven keyboard motion at 2000 CSS pixels per second, capped at 500 CSS pixels per frame. Pixel scrolling and dragging preserve fractional movement; key release, focus loss and session replacement stop motion. Q/Y and E/U zoom only Stack Chart; Ctrl/Shift wheel zooms around the pointer. Chart panning publishes preview updates, and only an explicit zoom command commits a range.
- Stack Chart may prepend genuine `UserTiming` marker rows, grouped by captured name with interval overlap lanes. Ordinary Android markers are not relabeled as user timing. Hover reflects actual marker duration; no call-node selection or unsupported context action is fabricated for markers.
- Stack settings use a compact 25dp inline radio/checkbox/search bar. Slash focuses stack search without stealing character input while the field is focused. Transform history has a persistent complete-profile root and clickable prior steps; clicking a step removes later transforms.
- SQLite-backed Firefox comparison uses unit sample weights for chart widths while retaining canonical event weights. Initial thread selection must filter the first published projection; an aggregate query must not highlight a fake default thread.
- Tooltip samples, chart weights and traced milliseconds are distinct quantities. Gecko-compatible traced timing preserves per-thread cumulative time reconstruction and stable merged-thread tie order, derives durations after committed/preview time cropping, and does not refill search/transform gaps. The SQLite export interval is 1ms; this is not a generic original-Gecko/diff-profile compatibility claim.
- SQLite export and native tooltip categories share resource-based classification. Function-name substrings such as `.kotlin` must not create Kernel/Native categories. Existing source artifacts remain unmodified; regenerated browser evidence belongs in ignored local output.

## Perfetto Frame Timeline Colors (2026-10-28)

- Color frame bars by the authoritative FrameTimeline `jank_tag`, not duration-based `JankSeverity`: No Jank uses green `#4CAF50`; Buffer Stuffing and SurfaceFlinger Stuffing use light green `#C0D588`; Self Jank uses red `#FF5722`; Other Jank uses yellow `#FFEB3B`; Dropped Frame uses blue `#03A9F4`. These are Perfetto's base colors in `third_party/perfetto/ui/src/plugins/dev.perfetto.Frames/actual_frames_track.ts` and remain the same in light and dark themes.
- Preserve `jank_tag` and `present_type` in the existing persisted frame state tags. Do not override a known tag with dropped presentation, elapsed duration, or the jank of a correlated SurfaceFlinger frame. Missing actual evidence stays unknown; pure stuffing is high latency, not deadline-jank attribution.
- Keep unsupported or insufficiently attributed frames neutral. Legacy Perfetto records can retain exact known normal/stuffing labels, but deadline misses and capture sample loss alone must not fabricate self-jank, SurfaceFlinger responsibility, or dropped-frame evidence.
- Place the localized color legend on an independently wrapping row below the timeline header. Keep navigation, frame selection, hover, zoom, axes, duration-based summaries, and the separately explained deadline-miss cluster caps intact.

## Unified Resizable Frame Analysis (2026-10-28)

- Keep the summary metrics outside one shared analysis card. Inside it, place the frame timeline above the deadline-miss intervals, problem frames, and single-frame details columns, with attribution and warnings below. Internal sections are unframed; retain their compact 8dp content padding without nested cards or exterior card gaps.
- Use horizontal splitters to adjust timeline/body and body/attribution heights, and vertical splitters for the three analysis columns. Draw a centered 1dp divider inside a 7dp pointer target, with directional resize cursors, keyboard arrow steps, and accessibility progress adjustment.
- Remember split proportions across frame selection, filtering, language, data updates, and temporary window constraints. Apply minimum sizes only to rendered geometry; hiding attribution removes its divider and reserved space without discarding its saved proportion.
- The timeline plot and axes fill the resized timeline region. Keep the time/duration mapping, Perfetto colors, frame selection, filtering, hover, evidence actions, and previous/next controls unchanged. Lists, detail properties, layout preview, and attribution keep independent scrolling; compact previews retain usable content height instead of painting into their titles.
- The single card fills the remaining page with the standard 4dp bottom margin. When minimum sizes do not fit, make its interior vertically reachable and the three-column body horizontally reachable; do not create negative widths or allow section contents to paint across dividers.


## AI 平台全局窗口增量（2026-10-31）

- 产品依据：`docs/ai/conversation-window.md`、`docs/ai/dsh-chat-parity.md`、`agent-platform-todo.md`、ADR-0037；本轮以用户“AI 界面只保留对话，管理移入 AI 设置，参照 DSH”覆盖旧版管理标签页及常驻右侧证据栏布局。
- 全局独立 Compose 窗口从 app menu、Navigation Rail 和 Cmd/Ctrl+Shift+A 打开；新建时没有报告也可普通聊天，使用明确的 `CHAT/CONVERSATION` 空上下文及无 Skill/子 Agent 的 `aps.chat`，不伪造性能证据、不开放源码/脚本/Artifact 权限，仍保留预检和显式确认。历史报告分析缺失绑定时显示 unavailable，不降级为普通聊天或把另一页面的数据当成当前上下文。
- 布局检查器“问题”（FINDINGS）区的“AI 分析”（Run AI）在桌面宿主中直接打开统一会话预检，仅在该窗口确认后调用已保存的 Provider、模型和 Agent，并沿用报告格式及权限设置；不再叠加布局页的旧确认框。发起时暂停自动扫描，默认仅提交当前选中节点的有界脱敏布局证据，不发送截图、运行时文字或源码片段。结果按节点引用回写 Findings；快照、活动窗口或时间线变化后取消旧任务，禁止旧结果覆盖新布局。独立运行布局检查器时仍保留原有本地预检。
- 大布局可在“仅性能数据”模式分析有界摘要：投影最多 200 个节点、子树深度 8、每节点 24 个子节点，JSON UTF-8 不超过 48 KiB；按字节预算减少规则和节点，保留选中节点及可用的父节点上下文。`coverage.complete=false` 明确标注覆盖范围、裁剪与限制，原布局指标和实际发送树的指标分别记录，不以缺失节点推断整棵树无问题。未验证覆盖标记的截断输入仍拒绝，部分摘要不允许自动解析或上传源码。
- 布局 AI 默认采用最小性能白名单：显示尺寸/密度、节点引用、控件类型短名、边界、可见性、透明度、父子关系、文字长度、规则命中及覆盖范围。包名、采集时间、截图存在标记、资源名称、无障碍描述长度和完整类名不写入性能 payload；规则仅携带 ID、级别、节点 ID 及白名单数值参数，不带自由文本说明。完整类名、资源名和原始文字留在本地，源码解析仍需单独显式授权。
- AI 对话窗口复用 Layout 主窗口的系统标题栏主题接线：macOS 原生标题栏透明并绘制共享标题条，使用应用 `toolbar` 背景、`primaryText` 标题及 `border` 分隔线，保持原生窗口按钮及固定标题条高度；Windows 沿用共享 JBR 能力检测和标准标题栏回退。颜色随应用主题更新，聊天内容的局部 DSH 配色不被覆盖。
- AI 对话框不显示独立设置按钮，包括侧栏展开、收起及运行时不可用状态；设置仍从应用统一设置入口进入。输入框模型入口在对话内弹出当前已保存 Provider 的模型列表，不再导航到 AI 设置。
- AI 设置先选择 Codex、Claude 或自定义（OpenAI 兼容）Provider，仅显示所选表单。Codex/Claude 在组内提供“读取配置”按钮，仅在点击后读取本地静态连接并填入掩码草稿；自定义连接手动填写 endpoint、凭据、模型和可选模型列表地址，不伪造本地配置来源。不导入 OAuth 会话、不执行 Key helper，读取失败保留原字段，缺失 Key 的新连接不得沿用其他连接密钥。
- AI 设置的统一页标题固定在内容区最上方，使用 `ViewerTypography.pageTitle`（16sp）；下方四个分类切换选项使用全局中档 `ViewerTypography.body`（13sp）。分类切换或连接内容滚动时标题仍可见，分类内容内不重复显示“AI 设置”标题。
- “Provider”分类（原“连接”）顶部使用共享 macOS 风格 `DropdownSelector`，下面显示当前 Provider 的单张共享 `StudioCard`，表单随页面垂直滚动。选择只切换草稿；“保存并应用”成功后才发布当前 Provider 与模型到统一设置，并显示已应用的 Provider。原独立 Provider 选择与默认模型管理页不恢复。
- 连接卡片内的读取配置、刷新模型、重试及保存均使用共享 `MacOSTextButton`，沿用统一紧凑高度、圆角和主题边框，不再混用 Material 胶囊按钮与纯文本按钮。
- 每种 Provider 单独持有密钥掩码草稿、模型、接口、模型列表、读取/刷新/保存状态及错误，切换后返回保留未保存草稿；请求中禁用切换，防止丢失异步结果。读取来源不匹配时保留原字段。自定义连接首次保存从旧 OpenAI 连接迁移，之后采用独立配置与凭据，不覆盖 Codex；旧配置仍可读取。保存合并最新 Agent/权限选择，不覆盖不相关设置。
- 主体为“会话侧栏 + 消息流 + 底部输入框”，默认 1200x800、最小 900x720；宽度不足时侧栏可折叠，中央内容不保留管理标签页、配置表单或常驻证据面板。证据/源码引用留在消息内，点击时显示精确详情或导航，不从模型 Markdown 猜测文件路径。
- 聊天局部采用本机 DeepSeek Harness 0.2.1-rc.5 的已核对 tokens，不修改全局 ViewerTheme/ViewerTypography：正文 14sp/24sp、用户消息 14sp/22sp、空白标题 26sp/32sp/500；发送/停止按钮按当前紧凑要求使用实际 28dp 圆形，不再被 Material 最小交互尺寸撑大，圆角 8/16/20/28dp。浅色底/正文/发送/气泡为 `#FFFFFF/#0F1115/#4176E6/#EDF3FE`；深色底/正文/输入和气泡/发送为 `#151517/#F9FAFB/#2C2C2E/#7AAAFF`。Compose 与 Web 的实际像素和字体栅格不能仅凭 token 等价宣称一致。
- Provider/Agent/Skill/Execution Policy 管理全部位于“设置 → AI 设置”；Provider 负责连接配置，其他分类负责扩展及执行策略。对话内模型菜单复用同一配置存储，仅更新当前 Provider 的模型，不改变 Provider、Agent 或权限。导入只写应用目录、显式信任及启用；不可用 provider/sandbox 显示稳定状态和可复制说明，不静默降级。
- AI 设置分类在统一页标题下方展开成一组标签，顺序固定为“Provider / Agent / Skill / 执行策略”；复用 Layout Inspector“刷新 / 自动扫描”的 `SegmentedSelector` 和 `COMPACT_OUTLINED` 样式，不再用下拉分类器。下方只显示当前选中的功能，不把四组设置同时堆叠。
- 顶部分类组在设置内容区横向居中，四个选项使用共享中档字号 `ViewerTypography.body`（13sp），保留标题行高与原有 30dp 高度、统一主题边框及选中态；窄窗口仍可横向滚动，其他页面紧凑选择器的默认 24dp 不变。
- 首次进入 AI 设置默认选中“Provider”；之后沿用 `ApplicationUiSettings` 持久化上次选中的标签，返回设置页和重启后均恢复。旧 `CONNECTION`、旧 `PROVIDER` 以及未知或损坏值均打开新的 Provider 连接页，读取时不重写偏好或连接配置。标签导航只改变可见分类，不保存连接草稿、不发起 Provider/脚本执行，也不扩大权限。
- AI 设置全页使用共享 `ViewerTypography` 的 16sp/13sp/11sp 三档，不另设局部字号；这条设置页约束不修改 DSH 聊天局部的字体、颜色或布局 tokens。
- Provider、Agent、Skill 和执行策略沿用共享 `StudioCard` 的圆角、边框、背景与间距：Provider 显示所选连接；Agent/Skill 的导入区域和每个包独立成卡；执行策略分为执行权限、报告输出与会话历史。卡片不嵌套，连接配置、信任、版本、启用和历史清理操作保持原语义。
- “执行策略 → 报告输出”以固定 320dp 的共享下拉框选择 `HTML (.html)` 或 `Markdown (.md)`，默认 HTML，旧配置缺失或未知值也回退 HTML。选择立即保存但不授予文件写入权限；新报告预检冻结格式并纳入确认 hash，已预检或运行中的报告不因设置变化改写。已生成报告按各自产物元数据的真实格式打开。
- 同一卡片以固定 320dp 下拉框选择报告语言：自动、英语或简体中文，默认自动。新预检把语言冻结为可信请求字段并纳入确认 hash；OpenAI/Codex Responses 与 Claude 的系统提示要求摘要、Finding 和报告正文使用所选语言，同时保留符号、路径和证据 ID 原文。语言变化不改变报告证据的 canonical session/hash，也不改写待确认、运行中或历史结果。
- AI 对话在发送、重试及队列问题开始预检时读取最新已保存的 Provider、模型、Agent 和权限设置；已预检和运行中的请求不因设置更改而替换。普通无报告聊天仍使用受限 `aps.chat`，不因设置 Agent 而获得源码、脚本或 Artifact 权限。Codex 使用配置中的完整模型 ID，不要求名字包含 codex，不切换为 CLI/tools。
- 连接保存使用带回滚的“保存并应用”操作，统一设置发布失败时恢复旧连接；无法恢复时保持不可用，不以新接口搭配旧模型继续运行。请求预检记录非秘密连接版本，连接变化后旧授权失效，需要重新预检，不能把已确认 payload 静默发往新 endpoint。
- AI 设置中 OpenAI/Codex 和 Claude 的模型列表统一复用紧凑 macOS 风格 `DropdownSelector`，保留主题边框、展开箭头和选中态。控件宽度不随模型名变化，长名称省略，菜单限高滚动；加载中或列表为空时禁用，成功查询但无可选模型时显示明确的空结果提示，不伪装成请求失败，也不阻止手动填写模型或显式保存。
- OpenAI/Codex 目录按模型 ID 最后一段识别文本模型家族和非文本排除项；网关命名空间只参与路由，不造成整批模型被误过滤。选择、请求和保存均保留服务端返回的完整 ID，不去掉前缀或合并不同命名空间的模型。
- 执行先预检实际 immutable payload，明确 source snippets、字节、evidence/candidate 数及 provider/model；用户确认后发起，支持取消/重试/历史恢复，恢复不重放。
- 会话提供新建、搜索、置顶、重命名、分支、归档/取消归档、复制 ID；持久化有界脱敏问题和会话元数据，回复沿用已校验结构化结果。新建、打开历史和元数据操作不发起模型请求；历史恢复不重放。连续追问只携带同 Analysis Session/context 的最近 4 个成功 turn，作为不可信对话背景，不作为性能事实或权限。
- 单个会话菜单增加紧凑 32dp 的删除项，始终先显示不可恢复的二次确认；取消、关闭确认框不发起删除或停止操作。确认后删除会话记录、消息及其内部 Agent 子任务；若删除当前会话，停止其请求并切换到新会话，删除其他会话不影响当前会话、草稿与运行。用户显式创建的分支不随父会话隐式删除，存在分支依赖时返回明确错误并保留记录；共享 Artifact 内容不因会话删除而任意移除。
- 输入支持 Enter 预检、Shift+Enter 换行、IME 提交保护、空输入上箭头取回问题、两次独立 Escape 停止；忙碌时问题进入本地会话队列，下一次仍需独立预检与确认。与 DSH 的 Steer/持久化 Inbox/互补发送快捷键差距按参照文档列出，不显示假能力。
- 会话中的失败、不可用与中断消息显示带刷新图标的“重试”；点击某条消息只重试该条问题，不误取最后一条问题，不覆盖未发送草稿。预检失败可保留失败输入并在修正设置后重试；报告入口没有手写问题时也可重试原报告。每次读取最新已保存 Provider、模型、Agent、报告格式和语言，生成新 run 并重新确认；忙碌、证据未绑定或源码未授权时禁用，不重放旧授权。
- 输入框底部右侧依次为模型选择入口、4dp 间距、发送/停止按钮；发送/停止始终是最右侧控件，距输入框右边保留 12dp 内边距，并在状态切换和模型名变化时保持相同位置及 28dp 尺寸。模型入口位于按钮左侧，长名称在剩余空间中省略，不挤压发送按钮；弹出模型列表带选中态、加载/空/错误状态和显式刷新，宽高有界且可滚动，上下文入口仍位于左侧。
- 设置页成功加载并应用的模型目录按 Provider 和连接版本缓存，供对话菜单复用；缺失时读取当前已保存连接，显式刷新绕过缓存。未保存草稿或过期请求不得替换对话目录。模型切换不提交问题，运行或待确认时禁用；下一次发送、重试或排队预检使用最新已保存模型。
- 每次从导航栏、菜单或协调器打开 AI 都产生新的窗口激活请求：已有窗口恢复最小化、移至前台并请求焦点，不重建 Runtime、不清空草稿或取消执行。仅将可见开关重复设为 true 不视为完成激活。
- 长对话的滚动导航固定于整个消息区域最右下角，位于输入区上方，与消息文字保留独立侧边空间；32dp 的回到顶部/回到最新消息图标按钮上下竖排、间隔 6dp，距视口右边和底边各 12dp。短内容隐藏；上箭头仅离开顶部时显示，下箭头仅未到底部时显示。回到顶部暂停自动跟随，回到底部恢复跟随；滚动不触发模型或其他业务操作。
- 对话显示用户问题、结构化 Findings、Evidence/Source 跳转、子任务/Skill timeline 和已验证 HTML/Markdown Artifact；HTML 由固定惰性模板转义生成。摘要与 Findings 共用原生 Markdown 显示子集：段落、标题、嵌套列表/任务项、引用、可横向滚动的表格、分隔线、强调和代码。正文 14sp/24sp、段落间距 16dp，h1/h2/h3 为 21/30、19/28、18/26；代码块带语言、复制、换行切换和轻量着色，8dp 圆角沿用本地约束。HTTP(S) 链接仅显示主题样式，不执行导航；HTML、图片及不安全链接惰性显示，不从模型文本读取文件或远程资源。附件、任意源码浏览、完整 CommonMark/数学公式、另存到任意目录、Token/速度精确统计等必须有真实后端和独立验收后才能呈现为可用功能；不能用占位按钮宣称 DSH 完全对齐。
- 中英、明暗、900/1280 布局、键盘/焦点/语义测试与截图独立于实际 provider、沙箱和跨平台 Release 证据。

### CPU AI Key Facts (2026-11-04)

- CPU/Simpleperf 分析默认仅发送性能数据；页面入口和全局 AI 对话复用同一个有界摘要，不上传完整报告、原始样本、完整调用树、火焰图、时间线、Marker、绝对路径或诊断自由文本。
- 性能摘要 JSON 的 UTF-8 大小最多 8 KiB；一次一个 `simpleperf-hotspots` evidence，包含已加载候选中按权重排序的最多 8 个函数、最多 3 个线程的角色/计数、当前过滤范围、图表权重类型及会话级质量计数。长标签和超量过滤条件截断时标明覆盖缺口；预算不足进一步缩减热点，不静默扩大请求。
- 当前选中函数、调用节点或 Stack Chart block 优先，仅提交该对象及有界公共摘要；失效/有歧义的选择拒绝分析，不回退整个报告。Stack block 只描述采样块权重和区间，不伪造单样本数、exclusive 函数成本或精确方法耗时；质量、基础范围、当前图表与线程摘要分别声明作用域。
- 报告和截断标签使用不透明内容引用区分身份，不外发原始会话路径。线程仅传角色，不传用户控制的线程名。源码需用户显式关闭“仅发送性能数据”并取得工作区授权，不能由模型自动补读；最多 2 个候选，每段 UTF-8 最多 4 KiB，候选序列化增量合计最多 8 KiB，超过则要求缩小范围。

### Profiler AI Entries (2026-11-04)

- 保留 Layout Inspector 和 CPU 原有入口；内存、帧、启动、电量、网络、GPU、基准回归、Trace、方法录制和源码元数据页在现有工具栏增加紧凑“AI 分析”图标文字按钮。首页、设置页没有独立证据，不添加伪分析按钮。
- 新入口共用 `ProfilerAiAnalysisButton` 和 `AiContextHost.analyze`，点击提交与页面发布完全相同的 canonical context，桌面经 `DesktopAiCoordinator` 打开统一预检，读取已保存 Provider、模型、Agent、报告格式与语言；未确认前不调用 Provider。独立运行且无 AI 宿主时隐藏，页面非活动、无证据或超预算时禁用。
- 新入口的性能 payload UTF-8 合计最多 8 KiB、短摘要合计最多 2 KiB；适配器先做本地有界聚合，保留 omitted/缺失值/单位，不上传原始 profile、SQL、对象字段运行值、网络 URL/headers/body、Trace 正文或源码。帧/启动/内存/GPU/回归摘要最多 8 行；电量最多 4 轮且每轮 8 条能量记录，网络最多 6 个慢请求且状态码和阶段最多 4 项。
- 方法录制使用独立 `METHOD_RECORDING` / `method-recording-summary`，最多 8 个方法及 self/total 微秒、调用/线程数、时钟和质量计数，不伪造统计采样或纯 CPU 时间。共享内置包以 1.4.0 版本扩展白名单，不修改已安装旧版本文件内容。
- Trace 只有当前 artifact 对应的已执行诊断数值结果可分析，GPU 仅制品身份元数据，源码页仅可验证快照元数据；没有对应测量时不能展示推断的 GPU 时序、泄漏证明、阻塞或 ANR 根因。真实 Provider、设备和各平台 GUI 验收独立于本地接线测试。
