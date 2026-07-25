# AndroidPerfermanceStudio 与 LayoutInspectorV2-Pro 方案对比

日期：2026-07-08

## 背景

本文件记录 AndroidPerfermanceStudio 与 [CoXier/LayoutInspectorV2-Pro](https://github.com/CoXier/LayoutInspectorV2-Pro) 的方案差异，尤其关注：采集链路、交互模型、点击选择策略，以及“上层层级影响下层鼠标焦点”问题可借鉴的方向。

当前结论：

- LayoutInspectorV2-Pro 是 **Android Studio / IntelliJ 插件 + Legacy Layout Inspector V2 协议增强** 路线。
- AndroidPerfermanceStudio 是 **独立桌面应用 + 自有采集协议 / fallback ADB 采集 + 分析能力** 路线。
- 对“上层层级遮挡下层焦点”的问题，LayoutInspectorV2-Pro 已有两个可参考点：
  - 点击候选按区域大小排序，倾向选择更小、更具体的 View；
  - 右键菜单中存在预览显隐/forced state 类能力，用于影响预览绘制。
- AndroidPerfermanceStudio 已按组合方案落地：Canvas 候选支持“小面积优先 / z-order”切换，保留同点轮选，并在层级树中提供“隐藏/显示”作为强制穿透手段。

## 来源与证据

### LayoutInspectorV2-Pro

- README：说明它是 Android Studio/IntelliJ 插件，dump 后生成 `.liv2` 文件；核心改进是更快 dump 和更容易选中目标 View。
  - <https://github.com/CoXier/LayoutInspectorV2-Pro>
  - <https://raw.githubusercontent.com/CoXier/LayoutInspectorV2-Pro/master/README.md>
- `plugin/build.gradle.kts`：使用 `org.jetbrains.intellij` 插件，目标 IntelliJ type 为 Android Studio (`AI`)。
  - <https://raw.githubusercontent.com/CoXier/LayoutInspectorV2-Pro/master/plugin/build.gradle.kts>
- `LayoutInspectorBridge.kt`：采集 hierarchy 与 preview，并写入 `.liv2` 内容；源码中定义 `V2_MIN_API = 23`。
  - <https://github.com/CoXier/LayoutInspectorV2-Pro/blob/master/plugin/src/main/java/com/android/layoutinspectorv2/LayoutInspectorBridge.kt>
- `ClientWindow.kt`：通过 ddmlib `dumpViewHierarchy(..., useV2, ...)` 采集 View hierarchy，通过 `captureView` 获取预览图。
  - <https://github.com/CoXier/LayoutInspectorV2-Pro/blob/master/plugin/src/main/java/com/android/layoutinspectorv2/model/ClientWindow.kt>
- `ViewNodeActiveDisplay.java`：预览面板支持 hover/click、候选 View 查找、按面积排序、鼠标滚轮切换候选。
  - <https://github.com/CoXier/LayoutInspectorV2-Pro/blob/master/plugin/src/main/java/com/android/tools/idea/editors/layoutInspectorv2/ui/ViewNodeActiveDisplay.java>
- `LayoutInspectorContext.java`：右键菜单包含节点显示/隐藏预览相关状态，并可触发 repaint。
  - <https://github.com/CoXier/LayoutInspectorV2-Pro/blob/master/plugin/src/main/java/com/android/tools/idea/editors/layoutInspectorv2/LayoutInspectorContext.java>

### AndroidPerfermanceStudio

- `README.md`：仓库当前只实现 Desktop 方案。
- `desktop-viewer/README.md`：说明当前是 Compose Desktop inspector，包含三栏 hierarchy/canvas/properties UI，支持 Agent socket 高保真采集和 ADB fallback。
- `desktop-viewer/docs/design/2026-07-02-desktop-viewer-design.md`：设计目标是三栏检查器，树、截图、Finding、属性共享同一个 `selectedNodeId`。
- `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/CanvasHitTester.kt`：当前 Canvas 命中测试按 z/elevation 和 child index 优先选择上层/后绘制节点。
- `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/CanvasPointerSelection.kt`：同一点反复点击可以在命中路径中轮选，但没有显式隐藏/忽略上层状态。
- `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewBoundsOverlay.kt`：当前 overlay 绘制有效可见节点，未考虑用户临时隐藏层级。
- `desktop-viewer/adb-gateway/src/main/kotlin/dev/agentperf/adb/LiveDeviceClient.kt`：优先连接 Agent；Agent 不可用时 fallback。
- `desktop-viewer/adb-gateway/src/main/kotlin/dev/agentperf/adb/AdbFallbackCapture.kt`：fallback 使用截图 + visible window views / uiautomator hierarchy。

## 核心差异与优缺点

| 对比维度 | AndroidPerfermanceStudio | AndroidPerfermanceStudio 优点 | AndroidPerfermanceStudio 缺点 / 风险 | LayoutInspectorV2-Pro | LayoutInspectorV2-Pro 优点 | LayoutInspectorV2-Pro 缺点 / 风险 | 对 AndroidPerfermanceStudio 的决策影响 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 产品形态 | 独立 Compose Desktop 应用 | 不依赖 Android Studio；可服务非 IDE 使用场景；便于打包给测试、性能、平台同学使用 | 需要单独安装和维护桌面应用；不能直接复用 IDE 的设备、项目、源码上下文 | Android Studio / IntelliJ 插件 | 入口贴近 Android 开发者日常工作流；可复用 IDE 设备连接、项目上下文和插件 UI 能力 | 绑定 IDE 版本与插件兼容性；非 Android Studio 用户不可用；IDE 升级可能带来维护成本 | AndroidPerfermanceStudio 继续保持独立桌面路线；未来可另建 IDE 插件作为入口，而不是把核心能力绑定到 IDE |
| 分发方式 | 原生桌面安装包：DMG/PKG/EXE/MSI/DEB/RPM | 面向多角色分发简单；无需用户配置 IDE 插件市场；离线安装包可归档 | 多平台打包、签名、发布链路复杂；需处理运行时和系统差异 | JetBrains Marketplace / IDE 插件 | 安装路径标准；升级由 IDE 插件系统承接 | 受 Marketplace 和 JetBrains 插件 API 约束；离线/内部分发需额外流程 | 当前 release workflow 的多平台打包价值仍然成立；插件形态只适合作为后续补充 |
| 主要目标 | 布局复杂度检测、实时/离线分析、跨 IDE 使用 | 能承载 metrics、findings、报告、时间线等分析产品能力 | 比单纯 inspector 范围更大，容易导致功能边界膨胀 | 恢复并增强 Legacy Layout Inspector，重点是快速 dump View 层级 | 目标聚焦，用户心智清晰；快速解决旧 LLI 缺失问题 | 分析能力弱，更多是查看器而不是诊断平台 | AndroidPerfermanceStudio 文档与 UI 应持续强调“分析/诊断”，避免退化成普通 hierarchy viewer |
| 采集链路 | 优先 Agent socket；无 Agent 时 fallback 到 ADB 采集 | Agent 模式可控、可扩展、可加入自定义协议与能力；fallback 让非接入应用也能看 | 高保真需要 app 集成 debug Agent；fallback 能力不一致，UI 需表达降级 | ddmlib / View Debug dump，默认使用 V2 协议 | 无需 app 集成；直接使用 Android 既有 View Debug 能力；V2 dump 性能好 | 依赖 Android/IDE 内部调试能力；V2 最低 API 与兼容边界有限；不利于自定义扩展 | AndroidPerfermanceStudio 可借鉴 V2 dump/visible-window 解析优化 fallback，但核心高保真链路仍应保留 Agent 协议 |
| App 接入 | 高保真模式需要 debug app 接入 Agent；fallback 可无接入但能力弱 | 接入后能力上限高，可采集自定义字段、事件、未来 Compose 适配 | 需要开发者改 Gradle/debugImplementation；对第三方 app 只能 fallback | 不需要 app 集成 Agent | 上手成本低；适合临时查看任意 debuggable app | 可采集字段受系统 debug dump 限制；难以加 AndroidPerfermanceStudio 特有分析数据 | 需要继续降低 Agent 接入成本，同时明确 fallback 与 Agent 模式的能力差异 |
| 数据格式 | 自定义 versioned JSON snapshot + PNG frame/archive | 可版本化演进；可跨平台/跨工具消费；适合报告、回归和长期归档 | 需要维护协议兼容；与现有 `.li/.liv2` 生态不互通 | `.liv2` 文件，类似 Legacy `.li` | 贴近 LLI 用户习惯；格式和场景聚焦 | 格式偏插件内部与 legacy 场景；扩展为分析报告不自然 | AndroidPerfermanceStudio 保持自有协议；若需要迁移用户，可考虑导入 `.liv2` 而不是改用 `.liv2` |
| 属性值完整性 | 已补齐结构化 `layoutBounds`、`layoutParamsClass`，并新增 `rawProperties` 原始属性表 | 常用字段可直接用于 UI/规则；ADB fallback 可保留所有可解码 ViewHierarchyEncoder 属性；缺少结构化字段时仍能在原始属性区查看 | Agent 高保真链路仍受公开 API 与自定义采集范围影响，不能像 View Debug dump 一样天然枚举所有内部属性 | `ViewNode.properties/namedProperties/groupedProperties` 保存 dump 中几乎所有属性，并把 `layoutParams` 单独分组 | 属性面板最全面，适合作为普通 inspector 使用；新增系统属性通常无需改模型 | 属性值主要用于展示，缺少 AndroidPerfermanceStudio 规则/归档语义；依赖 Android View Debug dump 的字段稳定性 | AndroidPerfermanceStudio 采用“结构化字段 + 原始属性兜底”组合：规则消费强类型字段，人工排查查看 raw properties |
| 实时性 | 支持前台应用跟随、自动扫描、手动刷新 | 适合连续调试和观察应用切换；可做 live diagnostics | 自动刷新可能带来设备/ADB 压力；需要清晰的刷新状态和错误恢复 | 更偏单次 dump / 打开 dump 文件 | 单次动作确定，状态简单；性能和失败边界容易理解 | 不适合持续监控和时间序列分析 | AndroidPerfermanceStudio 应继续优化 live/refresh 体验；单次 dump 可作为手动刷新/归档模式 |
| 分析能力 | metrics、findings、bounds overlay，后续可扩展报告/时间线 | 能直接给出复杂度与风险信号，不只是让用户肉眼查找 | 规则准确性、误报解释和性能都需要维护；需避免把结构风险说成真实 GPU overdraw | hierarchy、preview、properties、选择体验优化为主 | 简洁、低认知负担；查看属性和结构直接 | 缺少自动诊断、趋势和回归能力 | AndroidPerfermanceStudio 的差异化在 findings/metrics；应继续强化证据、置信度和解释文案 |
| 点击策略 | 当前按 z/elevation 与 child index 命中最上层；同点重复点击轮选路径 | 更接近真实绘制/遮挡顺序；实现确定；保留路径轮选 | 上层大容器/蒙层会挡住下层，用户难以快速选到底部目标 | 命中候选按显示区域大小排序，倾向选择更小 View；鼠标滚轮切换候选 | 更容易点中具体小 View；对 LLI “总选上层”问题有直接改善 | 面积小不等于真实触摸或绘制优先级；可能选中装饰性小 View | 可引入“面积优先候选排序”作为调试模式或候选切换，不应完全替代真实绘制顺序 |
| 层级显隐 / 穿透 | 已实现临时隐藏层级：树行提供 Hide/Show，Canvas hit testing 与 overlay 跳过隐藏子树 | 用户可明确控制哪些上层不参与命中；比算法猜测更稳定；隐藏数量可在 Canvas 标题清除 | 新增了本地 UI 状态，需要用户理解它不等同于 Android View 真实可见性 | 右键菜单中存在预览显隐/forced state 类能力，影响预览绘制 | 证明 inspector 中临时改变预览状态是可接受交互；可从树菜单恢复 | 更偏预览绘制显隐，不一定等价于“Canvas 命中穿透”；入口较隐蔽 | AndroidPerfermanceStudio 已采用显式 HiddenLayerState，并让 hit testing / overlay 同步跳过隐藏子树 |
| UI 技术 | Compose Desktop | Kotlin 统一技术栈；与现有应用状态/主题/多语言集成方便 | Compose Desktop 组件生态和测试方式与 IDE 插件不同；复杂表格/树控件需自研 | Swing / IntelliJ Platform UI | 与 Android Studio 插件生态天然一致；树、表格、右键菜单成熟 | UI 风格和能力受 IDE 平台约束；跨独立桌面复用价值低 | 不直接移植 UI；只借鉴交互思想和算法 |
| 多语言 / 主题 | 已支持主题与语言偏好 | 面向中文/英文用户体验更完整；可独立控制视觉一致性 | 新功能必须同步维护双语文案和主题状态 | 主要依赖 IDE 插件 UI 与英文文案 | 维护成本较低，跟随 IDE 外观 | 对中文用户和独立品牌体验支持较弱 | AndroidPerfermanceStudio 新增任何层级隐藏/选择策略都要同步补 ViewerStrings 与主题状态测试 |

## 方案选择小结

### AndroidPerfermanceStudio 更适合的场景

- 团队需要一个不依赖 Android Studio 的独立布局诊断工具。
- 需要 metrics、findings、归档、后续时间线/回归分析。
- 需要面向性能、平台、测试等非单一 Android Studio 开发者角色分发。
- 可以接受高保真模式通过 debug Agent 接入，换取更强扩展性。

主要代价：

- 自有协议、打包发布、跨平台 UI、Agent 接入和 fallback 能力差异都需要长期维护。
- 若只想“快速 dump 一次 View 层级”，产品路径比 IDE 插件重。

### LayoutInspectorV2-Pro 更适合的场景

- 用户主要在 Android Studio 内工作，希望恢复 Legacy Layout Inspector 体验。
- 需求集中在快速 dump、打开 `.liv2`、查看 hierarchy/preview/properties。
- 不希望修改目标 app 或接入 Agent。
- 更看重单次 dump 速度和目标 View 选择便利性。

主要代价：

- 绑定 IDE 插件生态与 Android Studio 版本兼容。
- 扩展为独立诊断平台、报告系统或持续监控工具的空间较小。
- 采集能力受 ddmlib/View Debug dump 能力边界限制。

## 对“上层影响下层鼠标焦点”的启发

### 1. 面积优先选择可降低误选

LayoutInspectorV2-Pro 的 README 明确指出 Legacy Layout Inspector 默认选择算法容易优先选中上层 View；它改为比较候选区域大小，通常更容易选择用户真正想要的小目标 View。

可借鉴方向：

- 在 AndroidPerfermanceStudio 的 Canvas 命中候选中增加排序策略：
  - 默认真实绘制顺序：z/elevation + child index；
  - 可选调试友好顺序：面积小优先；
  - 或命中列表中优先展示“小面积候选”。
- 保留当前同点轮选能力，降低算法猜错时的操作成本。

风险：

- 面积小优先不等于真实触摸/绘制优先级；它是调试便利策略，不应伪装为真实事件分发。
- 对全屏小透明控件、不可点击装饰 View 等情况，面积策略仍可能误选。

### 2. 显式隐藏层级比算法猜测更可控

LayoutInspectorV2-Pro 的预览显隐能力说明“临时改变预览状态”是合理的 inspector 交互。但 AndroidPerfermanceStudio 的需求更明确：隐藏某个上层层级后，Canvas 鼠标 hover/click 应能穿透到下层层级。

建议 AndroidPerfermanceStudio 后续实现时保持以下设计：

- 隐藏是本地调试状态，不修改 snapshot、不影响原始 metrics/findings。
- 隐藏某节点时，默认隐藏该节点及其子树。
- 隐藏状态影响：
  - Canvas hit testing；
  - Canvas visible bounds overlay；
  - hover/selection 边框绘制。
- 隐藏状态不影响：
  - 原始层级树存在性；
  - Properties；
  - Findings 原始结果；
  - Metrics 原始统计。
- 层级树中必须保留恢复入口，并在 Canvas 标题显示隐藏数量与清除入口。

### 3. 推荐组合方案

当前已按两阶段组合思路实现核心能力，后续可继续细化：

#### 已落地：轻量选择策略优化

- `CanvasHitTester` 增加候选列表 API，返回同一点下所有命中节点。
- 支持候选排序：
  - z-order；
  - small-area first。
- Canvas 标题提供“小面积优先 / Z 序优先”切换。
- 保留同点轮选机制，点击同一位置可以在候选列表中循环。

#### 已落地：显式隐藏层级

- 新增 `HiddenLayerState`，保存 `hiddenNodeIds: Set<String>`。
- `CanvasHitTester` 与 `ViewBoundsOverlay` 接收隐藏集合，跳过隐藏子树。
- `HierarchyPane` 行内增加 `Hide/Show` 操作。
- `PreviewPane` 标题显示 `Hidden N · Clear` / `已隐藏 N · 清除`。
- 刷新成功、切换窗口、导入归档时清空隐藏状态；渲染时 sanitize 已不存在的 nodeId。

剩余可优化点：

- 为候选切换增加更明确的键盘/鼠标滚轮提示。
- 让隐藏状态在树中区分“直接隐藏”和“祖先隐藏影响”。
- 根据用户反馈决定是否绘制隐藏层级的低透明/虚线边框。

## 后续决策建议

当前已实现组合方案。后续迭代建议：

1. 保持“小面积优先”作为调试便利策略，不把它描述为真实触摸分发。
2. 保持“隐藏层级”作为强制穿透手段，并持续标注为本地调试状态。
3. 若新增候选列表 UI，可把面积优先和 z-order 候选同时展示给用户。
4. 回归测试必须持续覆盖：
   - 重叠节点隐藏上层后命中下层；
   - 隐藏父节点时子树不参与命中；
   - overlay 不绘制隐藏子树；
   - 树折叠仍不影响 Canvas 命中，隐藏才影响。
