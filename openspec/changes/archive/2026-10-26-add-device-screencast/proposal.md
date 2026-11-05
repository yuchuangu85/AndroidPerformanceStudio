# Proposal: add-device-screencast

## Why

调试 Android 性能问题时，开发者需要在桌面上实时看到设备画面才能观察界面行为、复现交互、核对帧问题。当前应用只能通过 Layout Inspector 拉取静态截图，无法实时观看设备屏幕，开发者被迫在应用和独立 scrcpy 窗口（或设备本身）之间切换。参考 aya 项目的投屏能力，本应用应当内建同等的设备投屏功能。

## What Changes

- 新增「设备投屏（Device Screencast）」模块：通过内嵌 scrcpy 协议客户端，在应用内实时显示 Android 设备画面。
- 支持反向控制：鼠标触摸、滚轮、键盘、Android 导航键（Back/Home/Recents）、剪贴板粘贴注入设备。
- 集成到主窗口导航：新增 `SCREENCAST` destination，入口与现有 profiler 模块一致。
- 协议实现完全内嵌于 JVM 进程（不 spawn 外部 scrcpy 二进制）：
  - 分发 `scrcpy-server` jar（~100KB，Apache-2.0）为应用资源，推送到设备后经 `adb reverse` 隧道连接。
  - H.264 流用 jcodec（纯 Java 软解）解码，帧绘制到 Compose 画布。
  - control 消息（触摸/按键/剪贴板）按 scrcpy 官方协议手写二进制编解码。
- 支持投屏会话生命周期管理：连接、断开、设备掉线、停止时清理设备端 server 进程与隧道；编码流按软解预算降载（分辨率/帧率/码率上限），保证长会话稳定。
- `AdbClient` 增加 `reverse` / `removeReverse` 能力（与现有 `forward` 对称）。

## Capabilities

### New Capabilities

- `device-screencast`: 应用内设备投屏——连接建立（server 分发、push、reverse 隧道、协议握手）、视频流解码渲染、反向控制注入、会话生命周期与清理。

### Modified Capabilities

（无——现有 spec 均不涉及设备投屏；`AdbClient.reverse` 属于实现层面，不构成 spec 级行为变化。）

## Impact

- **代码**：
  - 新模块 `desktop-viewer/device-screencast/`（协议、会话、presentation、app 集成），遵循现有模块分层（如 `frame-profiler` 的 capture/presentation 结构）。
  - `platform-core/adb-core`：`AdbClient` 接口增加 `reverse`/`removeReverse`，`DefaultAdbClient` 实现。
  - `desktop-app`：`AppDestination` 增加 `SCREENCAST`，导航/窗口标题/命令面板接线。
- **依赖**：
  - 新增 `org.jcodec:jcodec:0.2.5`（纯 Java，无原生库；用于 H.264 软解）。
  - 新增 `org.jetbrains.kotlinx:kotlinx-coroutines-swing`（Compose Desktop 的 `Dispatchers.Main`）。
  - 新增资源文件 `scrcpy-server`（scrcpy v3.1，Apache-2.0，约 100KB，随应用分发）。
- **系统**：设备端仅需 `/data/local/tmp` 写权限（与 adb 调试权限相同，无需 root）；宿主机随机本地端口监听（`ServerSocket(0)`）。
- **风险**：jcodec 软解为 CPU 解码，通过编码流降载（≤1280 宽、≤10fps、2Mbps）平衡产出与消费，画面清晰度低于原始分辨率（调试场景可接受）；硬解升级路径为 JavaCV/FFmpeg。
