# Android 上的 Perf 分析

Firefox 分析器可以可视化从 [Android Studio CPU Profiler](https://developer.android.com/studio/profile/cpu-profiler) 导出的 `*.trace` 格式的 CPU 性能配置文件。将导出的文件加载到 [profiler.firefox.com](https://profiler.firefox.com)，可以通过拖放或“从文件加载配置文件”来实现。

或者，Android NDK 提供了 [`simpleperf`](https://android.googlesource.com/platform/system/extras/+/master/simpleperf/doc/README.md)，它可以分析任何 Android 进程。Simpleperf 在很大程度上是 Linux `perf` 工具的替代品。Android Studio CPU Profiler 内部也使用 `simpleperf`。

Firefox 分析器可以可视化这些 `simpleperf` 配置文件，从而增强 `simpleperf`（例如 [`report_html.py`](https://android.googlesource.com/platform/system/extras/+/master/simpleperf/doc/scripts_reference.md#report_html_py)）自带的查看器功能。

若要专门分析 Firefox for Android，请参阅 [远程分析 Firefox for Android](guide-remote-profiling.md)。

## 安装 simpleperf

安装最新版本的 `simpleperf`，它可以为 Firefox 分析器输出配置文件：

```bash
git clone https://android.googlesource.com/platform/system/extras
cd extras/simpleperf
```

## 使用说明

### 步骤 1：捕获配置文件

按照 [simpleperf 说明](https://android.googlesource.com/platform/system/extras/+/master/simpleperf/doc/scripts_reference.md#app_profiler_py) 记录配置文件，例如，要分析 `MyActivity` 在应用 `com.example.myapplication` 中的启动过程，请运行：

```bash
./app_profiler.py -p com.example.myapplication -a .MyActivity
```

这将把配置文件记录到 `perf.data` 文件中，并将其拉取到你的主机上。

### 步骤 2：转换配置文件

你可以将 `perf.data` 文件转换为 Firefox 分析器支持的格式之一。

#### 选项 1：Simpleperf 跟踪文件

将 `perf.data` 转换为 Simpleperf 跟踪文件格式。你也可以修改此命令以提供 proguard 映射文件或未剥离的 SO 文件来进行符号化。

```bash
# Convert perf.data to perf.trace
# If on Mac/Windows, use simpleperf host executable for those platforms instead.
./bin/linux/x86_64/simpleperf report-sample --show-callchain --protobuf -i perf.data -o perf.trace
```

#### 选项 2：使用 gecko_profile_generator.py

然后使用 [`gecko_profile_generator.py`](https://android.googlesource.com/platform/system/extras/+/master/simpleperf/doc/scripts_reference.md#gecko_profile_generator_py) 将其转换为 Gecko Profile（Firefox 分析器）格式：

```bash
./gecko_profile_generator.py | gzip > profile.json.gz
```

`gecko_profile_generator.py` 将之前写入的 `perf.data` 文件作为其隐式输入。

### 步骤 3：在 profiler.firefox.com 中查看配置文件

将上一步创建的 `perf.trace` 或 `profile.json.gz` 加载到 [profiler.firefox.com](https://profiler.firefox.com)，可以通过拖放或“从文件加载配置文件”来实现。

## 另见

[Perf Profiling for Linux](guide-perf-profiling.md) 中的提示。
