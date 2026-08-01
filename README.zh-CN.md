# AndroidPerfermanceStudio

[English](README.md)

AndroidPerfermanceStudio 是一个基于 Compose Desktop 的 Android 性能分析工作台。它将布局、CPU、系统 Trace、内存、帧耗时、启动、电量、网络、GPU 和 Benchmark 工作流集中在同一个桌面应用中，同时让各分析器保持独立的功能模块边界。

> 为保持兼容性，产品名称和包名沿用既有的 `AndroidPerfermanceStudio` 拼写。

## 特性概览

- 一个统一的桌面应用外壳，支持英文、简体中文、浅色、深色和跟随系统设置。
- 本地优先的工作流：通过 ADB 和导入的分析产物完成采集与分析。
- 完整的原生发布流程：macOS（DMG/PKG）、Windows（MSI/EXE）和 Linux（DEB/RPM）。
- 支持跨工具关联，例如将 GPU 或 Benchmark 分析产出的 Trace 交给 Trace Analyzer 打开。

## 功能说明

| 工具 | 功能 |
| --- | --- |
| **Layout Inspector** | 采集 Android View 层级和截图，提供层级树、画布、属性面板、边界高亮与问题提示，并支持采集归档导入/导出。调试构建可通过 Android Agent 获得更高保真的采集；其他前台应用可回退到 UI Automator 与截图。 |
| **CPU Profiler** | 使用 `simpleperf` 采集 CPU 样本，并通过火焰图和调用树分析热点。 |
| **Trace Analyzer** | 采集或导入 Perfetto 系统 Trace，分析调度、Binder 和图形链路；支持最近会话、导出原始 trace 文件。内置 UI 使用固定版本的 Perfetto 与 trace processor。 |
| **Memory Profiler** | 采集或导入 HPROF 堆转储，分析对象统计和类直方图；支持导出原始 HPROF 与分析结果。 |
| **Frame Profiler** | 在线采集帧数据或导入 `gfxinfo` FrameStats，识别帧耗时和卡顿区间，并导出 CSV/JSON 报告。选中的帧可以跳转到 Layout Inspector 做关联查看。 |
| **Startup Profiler** | 分析冷启动和温启动耗时分解，并支持 Baseline Profile 以及结果导入/导出。 |
| **Battery Profiler** | 基于 `batterystats` 执行实验，统计 wakelock、alarm 和网络用量；支持导出 JSON、CSV、原始证据，并生成 Battery Historian 输入。 |
| **Network Profiler** | 通过 Android Agent 采集 HTTP/HTTPS 请求活动，或导入 HAR 文件，展示请求时间线和请求详情。 |
| **GPU Inspector** | 发现并启动 Android GPU Inspector（AGI），索引和校验 GPU 分析产物，并可将 Trace 产物交给 Trace Analyzer。 |
| **Benchmark Regression** | 导入 AndroidX Benchmark JSON，对比基线与当前结果、标记回归，并生成适合 CI 使用的报告。 |

## 快速开始

### 前置条件

- macOS 13+、Windows 10 22H2/11 或 Ubuntu 22.04/24.04
- JDK 21
- Git
- Android SDK Platform Tools / `adb`（设备采集功能需要）
- 构建内置 Firefox Profiler 静态资源时需要 Node.js 24 和 Yarn Classic 1.x
- 准备内置 Perfetto 资源和 trace processor 时需要 `curl`、`unzip` 与 Python 3

### 克隆并准备内置分析器资源

```bash
git clone --recurse-submodules https://github.com/yuchuangu85/AndroidPerformanceStudio.git
cd AndroidPerformanceStudio

npm install --global yarn@1
./scripts/firefox-profiler.sh all
./scripts/build-perfetto-ui.sh download
PERFETTO_TOOLS_DIR="$PWD/build/perfetto-tools" ./scripts/install-trace-processor.sh
```

如果克隆时没有拉取子模块，请先执行：

```bash
git submodule update --init --depth 1 --recursive
```

### 运行桌面应用

```bash
cd desktop-viewer
./gradlew :desktop-app:run
```

从操作系统应用菜单打开 **Settings**，即可设置 Android SDK 路径、语言、主题，或查看动态解析的应用版本号。

## 构建与测试

运行完整桌面测试：

```bash
./desktop-viewer/gradlew -p desktop-viewer test --no-daemon
```

为当前主机操作系统创建原生应用包：

```bash
./desktop-viewer/gradlew -p desktop-viewer :desktop-app:createDistributable --no-daemon
```

发布格式对应的平台任务如下。原生安装包必须在操作系统和 CPU 架构均匹配的主机上构建：

```bash
# macOS Apple 芯片（arm64）
./desktop-viewer/gradlew -p desktop-viewer :desktop-app:packageDmg :desktop-app:packagePkg -Ptarget.arch=arm64 --no-daemon

# macOS Intel（x64），需要在 Intel 主机上使用 x64 JDK
./desktop-viewer/gradlew -p desktop-viewer :desktop-app:packageDmg :desktop-app:packagePkg -Ptarget.arch=x64 --no-daemon

# Windows x64
./desktop-viewer/gradlew.bat -p desktop-viewer :desktop-app:packageMsi :desktop-app:packageExe --no-daemon

# Linux x64
./desktop-viewer/gradlew -p desktop-viewer :desktop-app:packageDeb :desktop-app:packageRpm --no-daemon
```

发布工作流会生成 Linux x64 的 DEB、RPM，Windows x64 的 MSI、EXE，以及 macOS arm64 与 macOS x64 两种架构的 DMG、PKG。需要显式指定打包 JDK 时，可增加 `-Ptarget.javaHome=/JDK/绝对路径`。

不会生成 Windows x86（32 位）安装包。Compose Desktop 使用的 Skiko 在 Windows 上仅支持 x86_64，不支持 32 位 x86；把 x64 安装包改名为 x86 会得到无法运行的错误产物。详见 [Compose 原生分发的主机限制](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html)和 [Skiko 支持的平台列表](https://github.com/JetBrains/skiko#supported-platforms)。

## 项目结构

```text
android-studio-plugin/   规划中的 Android Studio 插件集成
web-ui-http-server/      规划中的 Web UI 与应用内 HTTP Server 集成
desktop-viewer/          Compose Desktop 应用和功能模块
  desktop-app/           应用外壳、设置、资源与原生打包
  layout-inspector/      View 层级采集与分析
  simpleperf-viewer/     CPU 采样与性能分析
  perfetto-viewer/       Perfetto Trace 采集、分析、存储和 UI 桥接
  memory-profiler/       HPROF 采集与分析
  frame-profiler/        帧耗时与卡顿分析
  startup-profiler/      启动耗时分析
  battery-profiler/      电量实验与归因分析
  network-profiler/      网络采集与 HAR 分析
  gpu-inspector-integration/  AGI 发现与产物集成
  benchmark-regression/  AndroidX Benchmark 对比与报告
  ui-components/         共享的公共 Compose 控件
  ai-core/               共享的 AI Provider 与结构化响应基础设施
  import-core/           共享的导入契约与源文件校验
third_party/             固定版本的 Firefox Profiler 和 Perfetto 子模块
scripts/                 内置分析器和 trace processor 的准备脚本
docs/                    架构、需求与设计记录
```

## 开发说明

- `desktop-viewer/desktop-app/` 只负责统一应用外壳、设置和原生打包；每个分析器拥有各自的实现，功能实现之间不互相依赖。
- Layout Inspector 在调试构建中使用 Android Agent 获得高保真采集；不需要 root、隐藏 API、系统签名或网络权限。回退路径受 UI Automator 的可见性和性能限制。
- 跨分析工具的跳转仅用于关联排查，不能作为因果关系证明。
- Android Studio 插件和 Web UI 目录目前是规划占位；已经实现的产品形态是桌面应用。

## 更多文档

- [桌面端开发指南](desktop-viewer/docs/architecture/DEVELOPMENT.md)
- [Layout Inspector 协议](desktop-viewer/docs/architecture/PROTOCOL.md)
- [桌面端设计](desktop-viewer/docs/design/2026-07-02-desktop-viewer-design.md)
- [文档索引](docs/README.md)
- [第三方资源构建说明](third_party/README.md)
