## Why

设备投屏目前作为主窗口内一个导航目的地（`AppDestination.SCREENCAST`）渲染，与其它模块走“同一时刻只有一个目的地可见”的叠加层模型。这意味着用户**无法在操作其它 profiler 模块的同时看到投屏画面**——投屏虽因 `retainedDestinations` 在后台保活，但被 `alpha=0` 遮住、不可见。把投屏抽成一个独立 OS 窗口后，投屏可与任意模块界面并排共存、可拖到第二屏，且用 OS 原生关闭按钮即可一键结束投屏，交互更直观。

## What Changes

- 投屏从「主窗口内的导航目的地」改为「主窗口触发的独立 OS 窗口」。点导航栏 / 首页卡片的「设备投屏」入口**打开一个独立的 Compose `Window`**，而非 `navigator.open(SCREENCAST)`。
- 投屏窗口拥有独立的 `WindowState`、标题、图标与 `minimumSize`；其关闭（`onCloseRequest`）= 结束投屏并清理资源，**不退出整个 application**。
- 主窗口的 `AppDestination.SCREENCAST` 不再成为 active destination，`DesktopAppMainPage` 的内联 `when (destination)` 分支移除 SCREENCAST 一支。
- 导航栏在投屏窗口已打开时显示「已在运行」指示，再次点击聚焦该窗口（`toFront()`），保证单实例（一次只允许一个投屏窗口 / 一条会话）。
- 投屏窗口内 `ScreencastMainPage` 顶部的「返回首页」按钮语义改为「关闭」；窗口关闭即触发 `DisposableEffect` 的 `controller.dispose()`，完成 adb reverse 隧道移除与设备端 server 终止。
- 投屏窗口复用主窗口已解析好的 `language`、`androidSdkPath`、`darkTheme`、`accentColor` 等依赖，避免把 settings store 上提到 `Main.kt`。
- 命令面板（command palette）中 SCREENCAST 项的触发改为开窗，而非 `navigator.open`。

## Capabilities

### New Capabilities

- `screencast-window`: 投屏作为独立 OS 窗口的生命周期管理——开窗入口、单实例约束、与主窗口的聚焦/共存关系、关窗即终止会话的语义。

### Modified Capabilities

- `device-screencast`: 「应用内提供设备投屏模块入口」要求中“模块关闭（evict）时停止投屏”的措辞与触发方式，从“模块被 evict”调整为“投屏窗口被关闭”；入口语义从“导航到目的地”改为“打开独立窗口”。

## Impact

- `desktop-app/.../Main.kt`：单窗口结构不变，但 `application {}` 下需支持第二个 `Window`（嵌套渲染在 `DesktopAppMainPage` 内）。
- `desktop-app/.../DesktopAppMainPage.kt`：新增 `screencastWindowOpen` 状态与投屏 `Window` 渲染；`onOpenScreencast` 回调改为开窗；移除内联 SCREENCAST 分支。
- `desktop-app/.../shell/CommercialAppShell.kt`：导航栏与命令面板对 SCREENCAST 的派发特判为开窗/聚焦；新增「已开」指示。
- `device-screencast/.../ScreencastMainPage.kt`：顶部「返回首页」改为「关闭」回调语义。
- `desktop-app/.../AppDestination.kt`：`AppDestination.SCREENCAST` 枚举保留（rail 图标、卡片、`titleResource` 仍依赖），仅 `shouldMaximizeWindow` 行为对它不再生效（不再是目的地）。
- macOS 多窗口菜单条：投屏独立窗口需确认不出现空/重复菜单条闪烁（主窗口已有 `WindowMenuBarHost` preload 机制，投屏窗口给极简菜单或复用 fallback）。
- 不引入新依赖；需验证所用 Compose Desktop 版本对嵌套 `Window` 的支持无回归。
