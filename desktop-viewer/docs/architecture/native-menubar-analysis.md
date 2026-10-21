# 原生菜单栏（MenuBar）现状分析与替代方案

## 结论

当前方案：窗口内只有一个原生 `MenuBar`，各页面通过 `ActiveWindowMenuBar { }` 把自己的菜单内容发布到这个宿主中。为了解决 macOS 菜单栏闪烁，这套方案已经过多轮修补。它能工作，但有不少复杂度来自“每个页面一套不同的顶层菜单”这一前提。更好的替代方案是：顶层菜单骨架固定在应用外壳中，页面只贡献菜单项和启用状态，与 IntelliJ / Android Studio 的模型一致。

## 一、代码位置

路径相对于 `desktop-viewer/`。

基础设施：

- `desktop-app/src/main/kotlin/com/androidperformancestudio/desktop/Main.kt:21`：`Window(...)` 入口
- `desktop-app/.../DesktopAppMainPage.kt:136`：`WindowMenuBarHost(...)`，参数包括 `preloadedMenuKey`、`preloadedMenuTitles`、`onMenuBarReady`、`fallbackMenu`
- `desktop-app/.../DesktopAppMainPage.kt:208`：`LocalWindowMenuBarActive provides active`，标记当前激活的页面
- `desktop-app/.../AppDestination.kt:180`：`hasNativeFeatureMenu()`，记录哪些页面有菜单的白名单，需要手工维护
- `ui-components/src/main/kotlin/com/androidperformancestudio/ui/ActiveWindowMenuBar.kt`
  - `:102` `WindowMenuBarHost`：全项目唯一调用官方 `MenuBar {}` 的地方
  - `:157` `ActiveWindowMenuBar`：页面发布菜单的入口
  - `:43` `WindowMenuBarScope`：只收集菜单 spec，不直接 emit
  - `:62` `StableTopLevelMenu`：按位置复用 `JMenu`

各页面：

| 页面 | 菜单定义 | 调用处 | 顶层菜单 |
|---|---|---|---|
| Layout Inspector | `layout-inspector/presentation/.../NativeViewerMenuBar.kt:213` | `LayoutInspectorMainPage.kt:1018` | File / Actions / View |
| Simpleperf | `simpleperf-viewer/app-desktop/.../SimpleperfFileMenu.kt:148` | `SimpleperfMainPage.kt:402` | File / Configuration |
| Perfetto | `perfetto-viewer/perfetto-app/.../PerfettoFileMenu.kt:23` | `PerfettoMainPage.kt:582` | File |
| Memory | `memory-profiler/memory-app/.../MemoryProfilerFileMenu.kt:138` | `MemoryProfilerMainPage.kt:159` | File |
| Frame | `frame-profiler/frame-app/.../FrameProfilerFileMenu.kt:50` | `FrameProfilerMainPage.kt:118` | File |
| Startup | `startup-profiler/startup-app/.../StartupProfilerFileMenu.kt:50` | `StartupProfilerMainPage.kt:142` | File |
| Battery | `battery-profiler/battery-app/.../BatteryProfilerMenu.kt:86` | `BatteryProfilerMainPage.kt:147` | File / Advanced |
| Method Recording | 内联于 `simpleperf-viewer/method-recording-app/.../MethodRecordingMainPage.kt:88` | 同左 | Method Recording |
| Home / Settings / Network / GPU / Benchmark 等 | 无 | 使用 `DesktopAppMainPage.kt:142` 的 `fallbackMenu` | File（打开 Trace / 分析 HPROF / 设置） |

## 二、当前的设置方式

1. `WindowMenuBarHost` 在所有页面组合之前，把一个 `WindowMenuBarContent` 状态对象挂到 `window.rootPane` 的 client property 上。
2. 宿主中只有一个 `MenuBar {}`。它读取 `holder.content`：有页面内容时使用页面内容，没有时使用 `fallbackMenu`。
3. 所有页面始终保持组合，非激活页面用 alpha 0 隐藏。`ActiveWindowMenuBar` 检查 `LocalWindowMenuBarActive`，只有激活页面会在 `SideEffect` 中把自己的 lambda 写入 holder，并在 dispose 时清空。
4. 页面在 `WindowMenuBarScope.Menu(...)` 中只收集 `WindowMenuSpec`。宿主用 `StableTopLevelMenu` 按位置渲染：第 N 个顶层菜单始终是同一个 `JMenu`，切换页面时只更新标题和内容。
5. 窗口标题要等 `onMenuBarReady` 回调后才更新，以免 macOS 先闪出旧标题。这一步依赖 `hasNativeFeatureMenu()` 和预载的菜单标题配合。

这样设计的原因（见源码注释）：早期每个页面各自拥有一个 `MenuBar`，页面切换时组合被 dispose，触发 `SwingApplier.onClear()` → `JMenuBar.removeAll()`，导致 macOS 菜单栏闪烁或变空。

## 三、官方用法

Compose Multiplatform 的标准写法：每个窗口一个声明式 `MenuBar`，菜单变化由状态驱动。

```kotlin
Window(onCloseRequest = ::exitApplication) {
    MenuBar {
        Menu("File", mnemonic = 'F') {
            Item("Open…", shortcut = KeyShortcut(Key.O, meta = isMac, ctrl = !isMac), onClick = ::open)
            Item("Export", enabled = state.canExport, onClick = ::export)
        }
        Menu("View") { CheckboxItem("Dark", checked = dark, onCheckedChange = { dark = it }) }
    }
}
```

要点：

- 一个窗口一个 `MenuBar`，放在窗口的顶层组合中
- 变化通过状态表达（`enabled`、勾选状态、动态列表）
- 不随页面创建或销毁 `MenuBar`

macOS HIG 以及 IntelliJ / Android Studio 的惯例是：顶层菜单固定（File / Edit / View / … / Window / Help），不适用的菜单项置灰，而不是移除整个菜单。

以上官方用法基于 Compose 文档、HIG 以及源码注释中对 `Window.desktop.kt` 的引用，撰写时未联网核对。

## 四、当前方案的问题

- 按位置复用只能覆盖顶层菜单数量相同的情况。Layout Inspector 有 3 个顶层菜单，Perfetto 只有 1 个，在两者之间切换时，第 2、3 个 `JMenu` 仍会被删除再重建。
- 需要同步的环节多：`hasNativeFeatureMenu()`、`preloadedMenuTitles`（Method Recording 的标题还要特殊处理）、`onMenuBarReady`、延迟更新窗口标题。这条链都是在掩盖“菜单随页面变化”带来的问题。新增页面时如果漏改白名单，标题就会卡在上一个页面。
- 通过 rootPane client property 传递 holder，相当于无类型的全局状态，而 `CompositionLocal` 可以做到类型安全。
- 重复代码多：每个页面各自实现 File > Open / Recent / Export，各自判断 `isMacOs` 拼快捷键。名义上都是 File 菜单，各页面的条目并不一致。
- Network / GPU / Benchmark 没有菜单，只能回退到 fallback，用户能感知到菜单栏不一致。

## 五、替代方案：固定骨架 + 页面贡献

顶层菜单由应用外壳固定，页面通过类型化的贡献接口只提供各槽位的内容。

```kotlin
// ui-components
class PageMenuContributions(
    val fileOpen: MenuAction? = null,              // null → 置灰
    val recent: RecentMenuModel? = null,
    val export: List<MenuAction> = emptyList(),
    val actions: (@Composable MenuScope.() -> Unit)? = null,  // Actions/Tools 槽位
    val view: (@Composable MenuScope.() -> Unit)? = null,
)

val LocalPageMenu = staticCompositionLocalOf<MutableState<PageMenuContributions>> { error("no host") }

@Composable fun ProvidePageMenu(contrib: PageMenuContributions) {
    if (!LocalWindowMenuBarActive.current) return
    val slot = LocalPageMenu.current
    SideEffect { slot.value = contrib }
}

// DesktopAppMainPage：唯一的 MenuBar，顶层菜单固定
MenuBar {
    val c = pageMenu.value
    Menu(file) {
        Item(open, enabled = c.fileOpen != null, shortcut = OpenShortcut) { c.fileOpen?.invoke() }
        RecentSubmenu(c.recent)                    // 为空时显示 "No recent files"
        Menu(export, enabled = c.export.isNotEmpty()) { c.export.forEach { Item(it.label, onClick = it.run) } }
        Separator(); Item(openTrace) { openTraceFile() }; Item(analyzeHprof) { analyzeHprof() }
        Separator(); Item(settings) { openSettings(SettingsPage.GENERAL) }
    }
    Menu(actionsTitle, enabled = c.actions != null) { c.actions?.invoke(this) }
    Menu(viewTitle,    enabled = c.view != null)    { c.view?.invoke(this) }
    Menu(windowTitle) { /* 切换页面：Layout / CPU / Perfetto … */ }
    Menu(helpTitle)   { /* 文档、关于 */ }
}
```

收益：

- 顶层 `JMenu` 的数量和标题永不变化，从结构上消除闪烁。可以删除 `StableTopLevelMenu` 的位置复用、`WindowMenuSpec` 和 fallback 菜单。
- 可以删除 `hasNativeFeatureMenu()`、`preloadedMenuTitles` 和 `onMenuBarReady` 这条标题同步链，窗口标题直接跟随 `navigator.destination`。
- 打开、最近文件、设置和快捷键只需统一实现一次，各页面行为一致，Network / GPU 等页面也自然拥有完整菜单。
- 用类型化的 `CompositionLocal` 替代 client property，可测试性更好。现有大量基于 `source.contains(...)` 的源码断言，可以改为针对 `PageMenuContributions` 的单元测试。

代价：Simpleperf 的 Configuration、Battery 的 Advanced、Method Recording 等页面独有的顶层菜单，需要并入 Actions（或 Tools / Analysis）槽位，或改为子菜单。这属于产品层面的菜单结构调整，需要确认。

## 六、迁移建议

1. 在 `ui-components` 中新增骨架和 `PageMenuContributions` 接口。
2. 先用 Perfetto（结构最简单）和 Layout Inspector（菜单最多）做试点。
3. 验证通过后迁移其余页面，最后删除 `ActiveWindowMenuBar`、`hasNativeFeatureMenu()` 以及预载标题相关代码。

## 七、各页面菜单树（现状）

`⌘X` 表示 macOS 用 Cmd、其他平台用 Ctrl。`[条件]` 是启用条件，`(mac 隐藏)` 表示 macOS 上不渲染（由应用菜单的 Preferences 接管，见 `ApplicationSettingsMenuInstaller.kt`）。

```text
Layout Inspector                         NativeViewerMenuBar.kt:223
├── File
│   ├── Import Archive…        ⌘I   [!busy]
│   ├── Import Screenshot…          [!busy && canImportScreenshot]
│   ├── Open Recent ▸               [!busy]
│   │   ├── <archive…> | No recent
│   │   ├── ───
│   │   └── Clear
│   ├── ───
│   ├── Export Archive…        ⌘E   [!busy && canExport]
│   ├── ───                         (mac 隐藏)
│   └── Settings               ⌘,   (mac 隐藏)
├── Actions
│   ├── ☑ Auto Scan            ⌘R
│   ├── ───
│   ├── Previous Node / Next Node / Toggle Selected Node   (菜单无快捷键，页面内为 ↑ ↓ Enter)
│   ├── ───
│   ├── ☑ Hierarchy ⌘1 / ☑ Findings ⌘2 / ☑ Details ⌘3
│   ├── ───
│   ├── ☑ Hierarchy IDs
│   ├── ───
│   └── Settings               ⌘,   (与 File > Settings 重复)
└── View
    ├── ☑ Hide invisible hierarchy views
    ├── ☑ Hide invisible findings
    ├── ☑ Hide hierarchy indices
    ├── ───
    ├── ☑ Show hierarchy layer visibility buttons
    ├── ───
    └── ☑ Show visible view bounds

Simpleperf (CPU)                         SimpleperfFileMenu.kt:157
├── File
│   ├── Open…                  ⌘O
│   ├── Export ▸                    [canExport，全部共用]
│   │   ├── Session Package / Report ⌘E / Raw Protobuf / Gecko Profile
│   │   ├── Folded Stacks / pprof / Screenshot
│   │   └── Simpleperf Report / HTML Report / Open Externally
│   ├── Open Recent ▸ (<file…> | No recent, ───, Clear)
│   ├── ───                         (mac 隐藏)
│   └── Settings               ⌘,   (mac 隐藏)
└── Configuration                   [configurationMenu.enabled]
    ├── Sampling Template
    ├── Capture Configuration
    └── Advanced Parameters

Perfetto                                 PerfettoFileMenu.kt:36
└── File
    ├── Open…                  ⌘O
    ├── Export ▸ (Session Package (.zip) / Raw Trace (.pftrace))  [canExport]
    └── Open Recent ▸ (<file…> | No recent, ───, Clear)

Memory Profiler                          MemoryProfilerFileMenu.kt:157
└── File
    ├── Import HPROF… / Import Mapping… / Import Native Heap… / Import Java Heap…   (各自 enabled)
    ├── Recent Sessions ▸ (<session…> | No recent)               (无 Clear)
    └── Export ▸ (11 项)
        ├── Raw HPROF / Standard HPROF / CSV / Class Instances / Object Investigation
        ├── Diff / Snapshot JSON / Investigation Report
        └── Bitmap Dump / Bitmap Comparison / Native Heap

Battery Profiler                         BatteryProfilerMenu.kt:96
├── File
│   ├── Import…
│   ├── Export ▸ (JSON / CSV / Raw Evidence)
│   └── Open Recent ▸ (<file…> | No recent, ───, Clear)
└── Advanced
    └── Reset Statistics

Frame Profiler                           FrameProfilerFileMenu.kt:56
└── File
    ├── Import Frame Stats…
    └── Export ▸ (CSV / JSON)

Startup Profiler                         StartupProfilerFileMenu.kt:56
└── File
    ├── Import…
    └── Export ▸ (CSV / JSON)

Method Recording                         MethodRecordingMainPage.kt:88
└── Method Recording                     (顶层菜单不叫 File)
    └── Import Trace…                    [!loading]

Home / Settings / Network / GPU / Benchmark / Source Workspaces   DesktopAppMainPage.kt:142 (fallback)
└── File
    ├── Open Trace File…       (打开后跳到 Perfetto)
    ├── Analyze HPROF…         (打开后跳到 Memory)
    ├── ───
    └── Settings
```

## 八、横向对比发现的问题

| 维度 | 现状 | 问题 |
|---|---|---|
| 顶层菜单 | 1 个（Perfetto/Memory/Frame/Startup/Method/fallback）、2 个（Simpleperf/Battery）、3 个（Layout） | 顶层数量随页面跳变，位置复用兜不住，这是闪烁问题的根源 |
| 顶层命名 | `File` / `Actions` / `View` / `Configuration` / `Advanced` / `Method Recording` | Method Recording 不叫 File，因此需要专门预载标题；Configuration 和 Advanced 都是页面私有的命名 |
| 主入口 | Open… / Import… / Import Archive… / Import HPROF… / Import Frame Stats… / Import Trace… | 同一个语义有 6 种说法 |
| 快捷键 | 只有 Layout（⌘I ⌘E ⌘R ⌘1-3 ⌘,）、Simpleperf/Perfetto（⌘O，Simpleperf 另有 ⌘E）有快捷键 | Layout 用 ⌘I 导入，别的页面用 ⌘O；Memory/Frame/Startup/Battery/Method 完全没有快捷键 |
| 最近文件 | Layout/Simpleperf/Perfetto/Battery 有 Clear，Memory 无 Clear 且叫 Recent Sessions，Frame/Startup/Method 没有 | 能力不一致 |
| Settings | Layout/Simpleperf 在 File 里放（mac 隐藏），Layout 的 Actions 里又放一次，fallback 菜单无条件显示，其余页面没有 | Windows/Linux 上一部分页面根本找不到 Settings；macOS 上 fallback 菜单与应用菜单的 Preferences 重复 |
| 菜单位置 | Layout 的 Hierarchy/Findings/Details 面板开关放在 Actions，而显示选项在 View | 面板显隐按惯例属于 View |
| 全局入口 | Open Trace / Analyze HPROF 只在 fallback 中出现 | 一进入功能页就消失；也没有页面间导航（Window/Go）和 Help |
| 实现 | 每个页面各写一套 `XxxMenuModel` + `XxxMenuShortcut` + `primaryShortcut(isMacOs)` + Recent 子菜单 | 7 份重复代码；`nativeMenuItemText` 用 em space 补宽，只有 Layout 用了 |

## 九、优化方案：统一菜单树

固定 5 个顶层菜单，由外壳渲染。页面只填充槽位，没有内容的项置灰，而不是移除。

```text
File
├── Open…                     ⌘O    ← 槽位 primaryOpen（各页的 Import…/Open… 统一放到这里）
├── Import ▸                         ← 槽位 secondaryImports（Screenshot、Mapping、Native/Java Heap）
├── Open Recent ▸                    ← 槽位 recent（统一包含 No recent / Clear）
├── ───
├── Export ▸                  ⌘E 为第一项  ← 槽位 exports（各页格式列表）
├── ───
├── Open Trace File…                 ← 全局，常驻
├── Analyze HPROF…                   ← 全局，常驻
├── ───
└── Settings…                 ⌘,    (仅 Windows/Linux；macOS 统一走应用菜单)
View
├── 面板开关（Layout：Hierarchy ⌘1 / Findings ⌘2 / Details ⌘3）   ← 槽位 view
└── 显示选项（Layout 的 5 个 CheckboxItem）
Tools                                ← 槽位 tools，没有内容的页面整个菜单置灰
├── Layout：Auto Scan ⌘R、Prev/Next/Toggle Node、Hierarchy IDs
├── Simpleperf：Capture Configuration ▸（Sampling Template / Capture Configuration / Advanced Parameters）
└── Battery：Reset Statistics
Go                                   ← 全局，页面间导航，当前页面打勾
├── Home  ⌘0
├── Layout Inspector ⌘⇧1 / CPU ⌘⇧2 / Perfetto ⌘⇧3 / Memory … / Frame … / Startup … / Battery … / Network … / GPU … / Benchmark …
└── Method Recording
Help
└── User Documentation / About   (macOS 的 About 走应用菜单)
```

逐项迁移：

- Method Recording：去掉顶层菜单 `Method Recording`，`Import Trace…` 放进 File > Open…。`preloadedMenuTitles` 里针对它的特殊分支随之删除。
- Layout Inspector：⌘I 改为 ⌘O，与其他页面一致；也可以保留 ⌘I 作为别名，在页面内的按键处理里继续响应。Actions 拆成 View（面板开关）和 Tools（扫描、节点导航）两部分。删掉 Actions > Settings 这个重复项。
- Simpleperf：Configuration 改为 Tools > Capture Configuration ▸。
- Battery：Advanced 改为 Tools > Reset Statistics。
- Memory：Recent Sessions 并入统一的 Open Recent 槽位并补上 Clear。4 个导入项中，HPROF 作为 Open…，其余放进 Import ▸。11 项导出建议按“原始数据 / 分析结果 / Bitmap”用分隔线分组。
- Frame / Startup / Network / GPU / Benchmark：自动获得完整的 File / Go / Help，只需要提供 Open 和 Export 槽位。没有内容的槽位置灰。
- Settings：只由外壳渲染一次，并按平台判断一次，删除各页的 `settingsShortcut.takeUnless { isMacOs }`。fallback 菜单在 macOS 上的重复 Settings 也一并消失。

槽位接口（替代七套 `XxxMenuModel`）：

```kotlin
data class MenuAction(val label: String, val enabled: Boolean = true, val shortcut: KeyShortcut? = null, val run: () -> Unit)
data class RecentMenu(val items: List<Pair<String, () -> Unit>>, val onClear: (() -> Unit)?)

data class PageMenuContributions(
    val primaryOpen: MenuAction? = null,
    val secondaryImports: List<MenuAction> = emptyList(),
    val recent: RecentMenu? = null,
    val exports: List<List<MenuAction>> = emptyList(),      // 外层列表 = 分隔线分组
    val view: (@Composable MenuScope.() -> Unit)? = null,   // 需要 CheckboxItem 的页面用 composable 槽位
    val tools: (@Composable MenuScope.() -> Unit)? = null,
)
```

快捷键统一由外壳定义：Open ⌘O、Export 第一项 ⌘E、Settings ⌘,、Go ⌘⇧N。页面不再自行调用 `primaryShortcut(isMacOs)`，Layout 页面内 `ViewerActionMenu.commandAction` 的按键映射要与菜单保持同一份定义，避免两处漂移。

收益汇总：

- 顶层菜单恒为 File / View / Tools / Go / Help，切页不增删 `JMenu`。`StableTopLevelMenu`、`WindowMenuSpec`、`fallbackMenu`、`hasNativeFeatureMenu()`、`preloadedMenuTitles`、`onMenuBarReady` 可以全部删除。
- 7 份菜单模型和渲染代码合并为 1 份渲染 + 各页一个 `PageMenuContributions` 构造函数，后者可以做纯单元测试。
- 用户在任何页面都能找到 Open / Recent / Export / Settings / 页面切换，Windows/Linux 上的 Settings 缺失问题随之解决。
