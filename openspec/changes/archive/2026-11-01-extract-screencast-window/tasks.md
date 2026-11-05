## 1. 投屏独立窗口骨架

- [x] 1.1 在 `DesktopAppMainPage.kt` 新增 `screencastWindowOpen` 状态（`mutableStateOf(false)`）与独立的 `rememberWindowState`（标题「设备投屏」、图标、`minimumSize` 贴近竖屏）。
- [x] 1.2 在 `DesktopAppMainPage` Composable 作用域内条件渲染第二个 `Window`，`onCloseRequest = { screencastWindowOpen = false }`（不 `exitApplication`），content 内调用 `ScreencastMainPage` 并复用已算好的 `language`/`androidSdkPath`/`darkTheme`/`accentColor`。
- [x] 1.3 验证嵌套 `Window` 在当前 Compose 版本可正常渲染（最小验证：开窗显示投屏页 + 关窗即 dispose controller，确认 adb reverse 隧道移除）。
- [x] 1.4 验证关闭主窗口（`exitApplication`）时投屏窗口随应用结束被 dispose，资源清理路径生效，无孤儿隧道。

## 2. 入口与派发改造

- [x] 2.1 将 `AppHomePage` 的 `onOpenScreencast` 回调从 `navigator.open(SCREENCAST)` 改为 `{ screencastWindowOpen = true }`（若已开则聚焦）。
- [x] 2.2 在 `CommercialAppShell` 为导航栏 SCREENCAST 派发增加特判/`onOpenScreencastWindow` 回调，触发开窗而非 `navigator.open`。
- [x] 2.3 命令面板（`commandItems`）中 SCREENCAST 项的触发同样改为开窗。
- [x] 2.4 实现已开时的聚焦：投屏窗口已存在则通过 `window.toFront()` 前置，不创建第二个窗口（单实例约束）。

## 3. 「已开」指示

- [x] 3.1 将 `screencastWindowOpen` 传入 `CommercialAppShell`，导航栏「设备投屏」项在投屏窗口打开时渲染「已在运行」视觉指示（如指示点）。
- [x] 3.2 投屏窗口关闭后指示随之消失（状态驱动）。

## 4. 主窗口内联分支清理

- [x] 4.1 移除 `DesktopAppMainPage` 的 `when (destination)` 中 `AppDestination.SCREENCAST -> ScreencastMainPage(...)` 分支。
- [x] 4.2 确认 `AppDestination.SCREENCAST` 枚举保留（`navigationIconResource`/`titleResource`/`navigationLabelResource`/首页卡片仍依赖），并在 `AppDestination.kt` 与 `DesktopAppMainPage` 加注释说明其仅作导航项身份、开窗走独立路径。
- [x] 4.3 确认 `shouldMaximizeWindow()` 不再因 SCREENCAST 触发主窗口最大化（它不会被设为 destination）。

## 5. 投屏页面头部与窗口 chrome

- [x] 5.1 `ScreencastMainPage` 顶部「返回首页」按钮文案改为「关闭」，`onBack` 语义改为关闭投屏窗口。
- [x] 5.2 投屏窗口装极简菜单条或复用 `WindowMenuBarHost` fallback，避免 macOS 空/重复菜单条闪烁；在 macOS 上验证。
- [x] 5.3 确认投屏窗口标题栏与主题；记录是否装 `MacOsWindowChrome` 的小决策（默认不装）。

## 6. 验证与文档

- [x] 6.1 端到端验证：开投屏窗口 → 切换主窗口各模块 → 投屏持续可见运行 → 关投屏窗口 → 会话终止、隧道移除 → 重新打开可再次投屏。
- [x] 6.2 单实例验证：投屏窗口已开时再次触发入口 → 不开第二个窗口、已有窗口前置。
- [ ] 6.3 跨平台基本验证（至少 Linux；macOS 菜单条与窗口 chrome 如可得则一并验证）。
- [x] 6.4 更新 `openspec/specs/device-screencast/spec.md` 与新增 `screencast-window` spec 与实现一致；归档时同步 spec Purpose。

## 7. 投屏窗口内部 UI 重排（紧凑堆叠工具栏）

- [x] 7.1 删除 `ScreencastMainPage` 的标题行（「关闭」+「设备投屏」文本），OS 窗口标题已显示名称；`ScreencastMainPage` 改为直接渲染 `ScreencastPage`。应用内不放关闭按钮，关窗仅靠 OS 窗口关闭按钮（`onCloseRequest`）。
- [x] 7.2 `ScreencastMainPage` 不再接收 `onBack`；`ScreencastPage`/`ScreencastHeader` 不含 `onClose`/`closeLabel`（关闭动作不暴露为页面控件）。
- [x] 7.3 `ScreencastHeader` 由单行 `Row` 改为两行：第一行 设备下拉(`weight(1f)`,去掉固定 240dp)+刷新+画质；第二行 仅会话状态(`ProfilerToolbarStatus`)。**无「停止投屏」按钮、无关闭按钮**——停止即关窗。移除 `onStop` 参数。
- [x] 7.4 `ControlBar` 仅保留「输入文本 + 发送到设备」单行；导航键上移并图标化（见 7.7）。
- [x] 7.5 清理因重排产生的未使用 import（如 `width`），保持 ktlint 通过。
- [x] 7.6 在 `device-screencast` spec delta 中调和「会话状态清晰呈现且可重试」要求：停止即关窗，应用内不渲染单独停止按钮。
- [x] 7.7 将「返回/主页/最近任务」改为经典 Android 三按钮导航图标（◁ 返回 / ○ 主页 / ▢ 最近任务），用 Compose `Canvas` 直接绘制（无新资源/依赖）；放入 `ScreencastHeader` 第一行最左侧，与设备/刷新/画质同一行；按钮放大为 32×32dp 方形、图标 18dp 以更醒目；仅运行中可用，`contentDescription` 保留无障碍语义。`ControlBar` 移除 `onNavigationKey` 参数。
- [x] 7.8 工具栏首行整体放大协调：「刷新」改用本地 `ScreencastToolButton`（32dp 高、12sp）；设备/画质下拉控件字号传 `controlFontSize=12sp`，使整行字号与高度统一协调。改动仅限投屏 presentation 内部，不动共享 ui-components。
- [x] 7.9 设备下拉显示友好名称而非原始序列号：`ScreencastDependencies` 把 `displayName` 从 `it.serial` 改为 `it.model ?: it.serial`（复用 `AdbDevicesParser` 已有的 model→product→device→serial 回退链），使设备下拉与画质下拉同为短友好字符串、视觉统一。
- [x] 7.10 下拉与按钮高度/边框统一：共享 `DropdownSelector` 新增可选 `controlHeight: Dp?` 参数（默认 null 不影响现有调用），控件 `Row` 套 `Modifier.height(controlHeight)` 使边框/背景填满该高度；投屏两个下拉均传 `controlHeight = 32.dp`，与同行导航图标、刷新按钮等高边框齐平。

## 8. 熄屏/亮屏 与 关闭/恢复亮度 控件

- [x] 8.1 `ScrcpyControlWriter` 新增 `setDisplayPower(on)` 发送 scrcpy `SET_DISPLAY_POWER` 消息（1=亮屏/0=熄屏）。
- [x] 8.2 `ScrcpySession` 新增 `setDisplayPower(on)` 转发到 control writer。
- [x] 8.3 `ScreencastPageController` 新增 `screenOff`/`powerKeyActive` 状态流、`toggleScreenPower()`（仅运行中可用）与 `togglePowerKey()`（注入 `KEYCODE_POWER`，仅运行中可用）。移除原 `brightnessGetter`/`brightnessSetter` 构造参数与 `brightnessDimmed`/`toggleBrightness` 逻辑。
- [x] 8.4 `ScreencastDependencies` 移除 `readBrightness`/`writeBrightness` 与亮度注入（改下发电源键后不再需要 adb shell 亮度读写）。
- [x] 8.5 工具栏第一行新增熄屏/亮屏按钮与电源键按钮（均仅运行中可用），复用 `ScreencastIconButton` 样式（32dp 方形、Canvas 图标）；熄屏时显示器图标加斜线、电源键已下发时图标中心填点以反映状态。`PowerKeyGlyph` 替换原 `BrightnessGlyph`。
- [x] 8.6 在 `device-screencast` spec delta 新增「熄屏/亮屏且画面不中断」「投屏时下发设备电源键」两条 ADDED requirement（含未运行禁用场景）。
- [x] 8.7 实机验证：熄屏/亮屏（关显示、投屏不停）与下发电源键均按预期工作。

## 9. 投屏期间禁止设备休眠

- [x] 9.1 `ScrcpyServerLauncher.serverCommand` 在 server 启动参数中加入 `stay_awake=true`，使设备端 server 在会话期间持有 wake lock，设备不进入休眠/doze。
- [x] 9.2 确认 bundled scrcpy-server 3.1 支持 `stay_awake` 选项（在 server jar 中 grep 到该字符串）。
- [x] 9.3 在 `device-screencast` spec delta 新增「投屏运行期间禁止设备休眠」ADDED requirement（含长投屏不休眠、停止后恢复休眠场景）。
- [x] 9.4 实机验证：长时间投屏设备不进入休眠、画面持续。

## 10. 输入框支持回车发送

- [x] 10.1 共享 `ProfilerCompactTextField` 新增可选 `onSubmit: (() -> Unit)?` 参数（默认 null 不影响其它调用方）。回车提交双轨：桌面无软键盘 IME、IME "send" action 不可靠，故在 `BasicTextField` modifier 上以 `onPreviewKeyEvent` 拦截物理 `Key.Enter`（`KeyDown`）并消费触发 `onSubmit`；同时保留 `ImeAction.Send` + `KeyboardActions(onSend)` 供软键盘平台。
- [x] 10.2 投屏 `ControlBar` 把发送逻辑抽为 `send` lambda（空文本不发送并清空），输入框传 `onSubmit = send`,与「发送到设备」按钮共用同一发送路径；placeholder 改为「输入文本发送到设备（回车发送）」。
- [x] 10.3 在 `device-screencast` spec delta 新增「发送文本支持回车键提交」ADDED requirement（含回车发送、空文本不发送场景，并写明双轨实现）。
- [x] 10.4 实机验证回车发送生效。
