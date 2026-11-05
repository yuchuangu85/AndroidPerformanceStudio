# Proposal: fix-desktop-packaging

## Why

本地 `packageDeb` 构建出的安装包存在多个打包缺陷，已造成两类用户可见故障：
(1) 打包内 `adb-tools/adb` 二进制丢失可执行位（0644），在无系统/SDK adb 的
干净用户机上应用完全无法使用 adb（实测 `AdbExecutableLocator.kt:99` 直接抛
`AdbNotExecutableException`）；(2) 安装的 .desktop 缺少 `StartupWMClass`，
GNOME Dock 无法匹配窗口，应用在任务栏不显示图标。此外应用名拼写错误
"AndroidPerfermanceStudio"（应为 Performance）随包名/窗口名/错误文案一起暴露
给用户；打包 runtime 缺少 `bin/` 目录（jlink 产物异常，暂不致命但偏离
jpackage 标准结构，排查成本高）。

已定位的根因：`desktop-app/build.gradle.kts:83-155` 的 repack 补救逻辑已知
Compose 打包插件用 `File.copyTo` 会丢 POSIX 可执行位，但只对
`trace_processor_shell` 做了 exec-bit 修复，未覆盖 `adb-tools/adb` 与其他二进制；
deb repack / .desktop 内容未做校验。

## What Changes

- **exec-bit 修复泛化**：`packageDeb`/`packageRpm` 的 repack 步骤从"仅修复
  `trace_processor_shell`"改为修复资源目录内全部应可执行的二进制
  （`adb-tools/adb`、`perfetto-tools/trace_processor_shell`、`compose-agent-bundle`
  内的原生 agent 等，以白名单/通配定义），repack 后校验。
- **.desktop 校验与修补**：repack 时校验安装的 .desktop 文件包含
  `StartupWMClass`，并从 payload 的 `lib/app/*.cfg` 读取 `app.mainclass` 派生
  其值（`com.androidperformancestudio.desktop.MainKt` →
  `com-androidperformancestudio-desktop-MainKt`），与 AWT X11 工具包为运行窗口
  生成的 WM_CLASS 对齐（普通 JDK 忽略 `awt.appclass`，故必须按主类派生而非
  packageName）；`Icon=` 指向打包内图标并校验存在。
- **拼写修正**：`AndroidPerfermanceStudio` → `AndroidPerformanceStudio`
  （窗口显示名 `Main.kt`、`jvmArgs apple.awt.application.name`、
  `nativeDistributions.packageName`、`LiveDeviceClient` 错误文案等全部出现点）。
  **BREAKING**（轻）：Linux 包名/安装路径由 `androidperfermancestudio` 变为
  `androidperformancestudio`，已装旧包需先卸载。
- **runtime `bin/` 缺失调查**：确认本地构建产物 `lib/runtime/` 缺 `bin/` 的原因
  （jpackage 版本/构建参数/构建环境污染），若为本地构建环境问题则记录到
  构建文档；若可代码修复则修复。

## Capabilities

### New Capabilities

- `desktop-packaging-integrity`: Linux 安装包的完整性约束（二进制可执行位、
  .desktop 桌面集成字段、应用名拼写），打包后自动校验。

### Modified Capabilities

（无——现有 specs/ 下无桌面打包相关 capability。）

## Impact

- `desktop-viewer/desktop-app/build.gradle.kts`（repack 逻辑、packageName）
- `desktop-viewer/desktop-app/src/main/kotlin/.../Main.kt`（APP_DISPLAY_NAME）
- `desktop-viewer/layout-inspector/adb-gateway/.../LiveDeviceClient.kt`
  （错误文案中的应用名）
- 其他含该拼写的用户可见文案（全仓搜索 `Perfermance`）
- CI：`.github/workflows/package-linux.yml`（若 repack 需要的工具在 CI 可用性
  变化）——预计无变化（dpkg-deb 已是现有依赖）。
- 用户侧：旧版本安装包升级路径（新包名不同，需卸载重装）。
