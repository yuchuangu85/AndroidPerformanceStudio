## Context

当前桌面应用是**单窗口**结构：`Main.kt` 的 `application {}` 下唯一一个 `Window` 承载 `DesktopAppMainPage`。模块通过 `AppDestination` 枚举 + `AppNavigator.retainedDestinations` 实现“叠加层”导航——所有保留的模块同时 composed，靠 `zIndex`/`alpha`/`blockPointerInput` 控制可见性，同一时刻只有 active destination 可见。

`SCREENCAST` 是其中一项目的地，渲染 `ScreencastMainPage`（内联在 `DesktopAppMainPage` 的 `when (destination)` 分支里，顶部带「返回首页」按钮）。投屏 controller 由 `remember` 持有、`DisposableEffect` 在 dispose 时清理。这意味着：切换到其它模块时投屏后台保活但不可见——这是“共存”痛点的根因：单窗口同一时刻只能看到一个模块。

Compose Desktop 允许在 `application {}` 下任意位置（含另一个 `Window` 的 content lambda 内）再声明 `Window(...)`，因此可以新增第二个 OS 窗口而无需重构 `Main.kt` 的窗口持有方式。

## Goals / Non-Goals

**Goals:**
- 投屏与任意 profiler 模块界面同时可见、可并存运行。
- 投屏窗口可独立移动、调整尺寸、拖到第二屏。
- 用 OS 原生关闭按钮即可一键结束投屏，交互直观。
- 复用主窗口已解析的依赖（`language`/`androidSdkPath`/`darkTheme`/`accentColor`），不把 settings store 上提到 `Main.kt`。
- 对现有架构改动可控，`AppDestination.SCREENCAST` 枚举保留以减少牵连。

**Non-Goals:**
- 多设备多投屏窗口（v1 只支持单实例/一条会话；多设备是后续扩展）。
- 投屏窗口内的功能新增（解码、控制、状态机等由现有 `device-screencast` capability 覆盖，不在本变更内改动）。
- 重构主窗口的叠加层导航模型本身。
- 投屏窗口独立持久化的窗口位置/尺寸记忆（v1 用默认 `WindowState`）。

## Decisions

### 决策 1：投屏窗口嵌套渲染在 `DesktopAppMainPage` 内，而非上提到 `Main.kt`

`ScreencastMainPage` 依赖 `language`、`androidSdkPath`、`darkTheme`、`accentColor`，这些在 `DesktopAppMainPage` 里已由 `applicationSettings`/`simpleperfPreferences`/`isSystemInDarkTheme()` 算好。把投屏 `Window(...)` 写在 `DesktopAppMainPage` 的 Composable 作用域内、复用这些 state，避免把 settings store 与主题解析上提到 `Main.kt`。Compose Desktop 支持这种嵌套 `Window`。

### 决策 2：`AppDestination.SCREENCAST` 枚举保留，仅改变其语义

枚举被 `navigationIconResource`、`titleResource`、`navigationLabelResource`、首页卡片、命令面板广泛依赖。删除会牵连过多且无收益。改为：枚举保留作为“导航项/卡片的身份与图标来源”，但**不再成为 active destination**——`DesktopAppMainPage` 的 `when (destination)` 分支移除 `SCREENCAST -> ScreencastMainPage(...)`，改由独立窗口渲染。`shouldMaximizeWindow()` 对它不再生效（它不会被设为 destination）。

### 决策 3：开窗状态用 `DesktopAppMainPage` 内的 `mutableStateOf<Boolean>`，并暴露聚焦能力

```kotlin
var screencastWindowOpen by remember { mutableStateOf(false) }
val screencastWindowState = rememberWindowState(...)   // 抬到可访问层级以便 toFront()
```
- 打开：`screencastWindowOpen = true`（若已开则 `screencastWindowState` + `window.toFront()` 聚焦）。
- 关闭：`onCloseRequest = { screencastWindowOpen = false }`（**不** `exitApplication`）。
- 「已在运行」指示 = `screencastWindowOpen` 传给 `CommercialAppShell` 渲染导航栏小圆点。

`toFront()` 需要 `Window` 的句柄；Compose Desktop `Window` content lambda 接收 `FrameWindowScope`，可从中拿到 `window`。聚焦可在投屏窗口的 `DisposableEffect` 或开窗回调里调用。

### 决策 4：导航栏 / 命令面板派发特判 SCREENCAST

`CommercialAppShell` 现有 `onDestinationSelected = navigator::open` 是通用路径。SCREENCAST 需特判：给 `StudioNavigationRail`/shell 增加一个 `onOpenScreencastWindow: () -> Unit` 回调（或统一 handler 内 `when` 特判）。命令面板 `commandItems` 同样特判。保持 rail 结构与图标不变，仅改派发动作与新增「已开」指示。

### 决策 5：`ScreencastMainPage` 顶部「返回首页」改为「关闭」

在独立窗口语境“返回首页”语义不通。`onBack` 回调语义改为“关闭窗口”。保留该按钮（不删除），与 OS 原生关闭按钮双保险，确保关窗即触发 `DisposableEffect` 的 `controller.dispose()` → adb reverse 隧道移除、设备端 server 退出，满足 `device-screencast` 资源清理要求。

### 决策 6：投屏窗口 chrome 与菜单条

- 窗口标题：本地化的「设备投屏」；图标复用主窗口 `painterResource("icons/app-icon.png")`。
- `minimumSize`：贴近手机竖屏的可读尺寸（如 360×720），具体值在实现时定。
- macOS 菜单条：投屏独立窗口会带自己的 app 菜单条。v1 给它极简菜单或复用主窗口 `WindowMenuBarHost` 的 fallback 机制，避免空/重复菜单条闪烁（主窗口已有 preload 处理，投屏窗口需同样处理或显式装一个 fallback 菜单）。是否装 `MacOsWindowChrome` 与主题色统一，留作实现期的小决策，默认不装以降低复杂度。

### 决策 7：主窗口关闭的连带清理

主窗口 `onCloseRequest = exitApplication` 会结束整个 `application`，投屏窗口随之销毁，其 `DisposableEffect` 触发清理。无需额外代码，但需验证该路径确实 dispose 投屏 controller。

### 决策 8：投屏窗口内部 UI 重排（紧凑堆叠工具栏）

投屏页面原为全宽主窗口设计，放进最小 360×640 的窄高独立窗口后出现三处问题：① `ScreencastMainPage` 的标题行（「关闭」+「设备投屏」）与 `ScreencastPage` 自带工具栏重复成两条栏，且「设备投屏」与 OS 窗口标题重复；② `ScreencastHeader` 单行内设备下拉框硬编码 `width(240.dp)`，加「刷新」「画质」「状态」「停止投屏」约 430dp，在 360dp 窗口溢出；③ `ControlBar` 单行内 3 个导航键 + 文本框 + 发送按钮挤在一起，文本框被压扁。

重排方案（用户选定「紧凑堆叠工具栏」，并明确不在应用内放关闭按钮）：

- 删除 `ScreencastMainPage` 的标题行；OS 窗口标题已显示「设备投屏」。
- 关闭投屏窗口**仅依靠 OS 提供的窗口关闭按钮**（投屏 `Window` 的 `onCloseRequest = { screencastWindowOpen = false }`），应用内不渲染任何「关闭」按钮。`ScreencastMainPage` 因此不再接收/透传 `onBack`，`ScreencastPage`/`ScreencastHeader` 也不含 `onClose`/`closeLabel`。
- `ScreencastHeader` 由单行 `Row` 改为 `Column` 包两行 `Row`：
  - 第一行：设备下拉（`Modifier.weight(1f)`，去掉固定 240dp）+ 刷新 + 画质。
  - 第二行：会话状态（`ProfilerToolbarStatus`，含连接中/运行中/失败信息）。**无「停止投屏」按钮、无关闭按钮**——停止投屏即关闭投屏窗口（关窗 → `dispose` controller → 移除 adb reverse 隧道、终止设备端 server），与「关窗即停止」的设计统一。`onStop`/`onClose` 参数因此从 `ScreencastHeader`/`ScreencastPage`/`ScreencastMainPage` 移除。会话的「开始/重连/重试」仍由画布上的 `StateCard` 承载（idle/disconnected/failed）。
- 与 `device-screencast` spec 的「会话状态清晰呈现且可重试」要求在 delta 中调和：原「运行状态下 SHALL 提供停止按钮」改为「停止即关窗，应用内不渲染单独停止按钮」。
- `ControlBar` 仅保留「输入文本 + 发送到设备」单行（导航键已上移，见下）。
- **导航键并入工具栏首行并图标化**：原 `ControlBar` 的「返回/主页/最近任务」文本按钮改为经典 Android 三按钮导航图标（◁ ○ ▢，用 Compose `Canvas` 直接绘制，无需新增资源文件/依赖），放在 `ScreencastHeader` 第一行的**最左侧**，与设备下拉、刷新、画质同一行，而非独占顶部一行。按钮放大为 32×32dp 的方形、图标 18dp（比紧凑文本按钮的 24dp/14dp 更醒目）。仅在会话运行中可用。`ControlBar` 因此不再含 `onNavigationKey` 参数。语义/可访问性通过 `contentDescription` 保留（返回/主页/最近任务）。
- **工具栏首行整体放大协调**：为与放大的导航图标协调，「刷新」改用本地 `ScreencastToolButton`（32dp 高、`bodyCompact` 12sp 字号、12dp 横向内边距）替代 24dp 的 `ProfilerCompactButton`；设备下拉与画质下拉的控件字号统一传 `controlFontSize = bodyCompact 12sp`（原默认 11sp），使整行（3 个 32dp 导航图标 + 设备下拉 + 刷新 + 画质）字号与高度一致、视觉协调。不改动共享 `ui-components`，放大仅限投屏 presentation 内部。
- **设备下拉显示友好名称**：原 `ScreencastDependencies` 设备下拉 `displayName = it.serial` 直接显示原始序列号（如 `emulator-5554`），与画质下拉的「流畅 · 30fps」风格不统一。改为 `displayName = it.model ?: it.serial`——`AdbDevicesParser` 已把 `model` 解析为「model → product → device → serial」的友好回退链，故下拉显示机型/产品名而非长序列号，与画质下拉同为短友好字符串，视觉统一。
- **下拉与按钮高度/边框统一**：工具栏首行控件高度原不齐——导航图标与刷新按钮为 32dp，而两个 `DropdownSelector` 控件高度由「字号 + 固定 vertical padding」撑开（约 21dp），边框因此比按钮矮、视觉不统一。给共享 `DropdownSelector` 新增可选 `controlHeight: Dp?` 参数（默认 null 保持原行为不变），在控件 `Row` 上 `Modifier.height(controlHeight)` 使其边框/背景填满指定高度；投屏两个下拉均传 `controlHeight = 32.dp`，与同行导航图标、刷新按钮等高、边框齐平。该参数为可选增量，不影响其它模块的现有 `DropdownSelector` 调用。
- **熄屏/亮屏与关闭/恢复亮度**：工具栏首行追加两个 `ScreencastIconButton`：
  - 熄屏/亮屏：经 scrcpy `SET_DISPLAY_POWER` 控制消息关/开设备物理屏幕显示，**投屏视频流不中断**（设备屏幕黑但电脑上画面继续）。仅会话运行中可用。状态由 `screenOff` 流驱动，熄屏时图标加「off」斜线。（已验证可用。）
  - 下发电源键：经 scrcpy 控制通道注入 `KEYCODE_POWER`（等价按设备物理电源键），在屏幕开/关间切换。仅会话运行中可用。状态由 `powerKeyActive` 流驱动，已下发时图标中心填点。注意设备可能熄屏后睡眠并暂停投屏流，唤醒需再次点击。原「关闭/恢复亮度」方案（`adb shell settings screen_brightness`）经验证改为下发电源键，更贴近物理操作、且全程走投屏控制通道（不再依赖 adb shell / 亮度读写注入）。
  - 控制能力均走 scrcpy 控制通道：`ScrcpyControlWriter.setDisplayPower` / `ScrcpySession.setDisplayPower` 与 `tapKeycode(KEYCODE_POWER)`。不再有 adb shell 亮度注入，`ScreencastPageController` 的 `brightnessGetter`/`brightnessSetter` 构造参数已移除，`ScreencastDependencies` 的 `readBrightness`/`writeBrightness` 已移除。两个新 `Canvas` 图标（显示器/电源符号）不引入新资源。
- **投屏期间禁止设备休眠**：`ScrcpyServerLauncher.serverCommand` 在 server 启动参数中加入 `stay_awake=true`。scrcpy server 据此在会话期间持有 wake lock，设备不进入休眠/doze；会话终止（关窗、server 退出、异常断开）时 server 退出即释放 wake lock，无需宿主侧额外清理，停止投屏后设备恢复正常休眠策略。已在 bundled scrcpy-server 3.1 中确认 `stay_awake` 选项存在，并实机验证长时间投屏不进休眠。这是 server 侧方案，比宿主侧轮询注入唤醒键更可靠、更省资源。
- **输入框回车发送**：`ControlBar` 抽出 `send` lambda（非空才发送并清空），输入框与「发送到设备」按钮共用。共享 `ProfilerCompactTextField` 新增可选 `onSubmit: (() -> Unit)?`（默认 null 不影响其它调用方）。回车提交双轨：桌面无软键盘 IME，IME "send" action 不可靠，故在 `BasicTextField` modifier 上以 `onPreviewKeyEvent` 直接拦截物理 `Key.Enter`（`KeyDown`）并 `return true` 消费触发 `onSubmit`；同时保留 `ImeAction.Send` + `KeyboardActions` 供有软键盘的平台。实机验证回车发送生效。
- 画布 `ScreencastStage` 保持 `contain` 比例缩放不变，仍占 `weight(1f)`。
- macOS 菜单条：投屏窗口仍装极简 `WindowMenuBarHost` fallback（仅为避免空菜单条闪烁），其菜单项为窗口标题 + 一个「关闭」项；此为原生菜单条惯例，非应用内按钮，与「不在应用内放关闭按钮」不冲突。

非目标：不做控件浮在画布上的 scrcpy 风格浮层（会拦截本该发给设备的点击，且与现有工具栏设计语言不一致）。

## Risks / Trade-offs

- **嵌套 `Window` 兼容性**：Compose Desktop 支持嵌套 `Window`，但需验证当前 `libs.versions.toml` 的 compose 版本无回归，且与 `WindowMenuBarHost`/`LocalWindowMenuBarActive` 不冲突。**缓解**：实现期先做最小验证（开窗 + 渲染 `ScreencastMainPage` + 关窗 dispose），再加指示与聚焦。
- **macOS 多窗口菜单条闪烁**：每个窗口有独立 app 菜单条，可能出现重复/空菜单条。**缓解**：投屏窗口显式装 fallback 菜单或复用 preload 思路；在 macOS 上手动验证。
- **聚焦/前置的跨平台差异**：`window.toFront()` 在某些 Linux WM 上行为不一致。**缓解**：v1 接受“尽量前置”，不阻塞核心功能；指示点已让用户知道窗口在运行。
- **枚举保留的隐性歧义**：`SCREENCAST` 仍是 `AppDestination` 但不再走 `navigator.open`，未来维护者可能误用。**缓解**：在 `AppDestination.kt` 与 `DesktopAppMainPage` 注释明确“SCREENCAST 仅作导航项身份，开窗走独立路径”。
- **单实例约束 vs 多设备预期**：用户可能期望同时投多台设备。**缓解**：v1 明确单实例并在 spec/README 注明，多设备留作后续。
- **窗口位置/尺寸不记忆**：每次开窗回到默认状态。v1 接受，后续可加持久化。
