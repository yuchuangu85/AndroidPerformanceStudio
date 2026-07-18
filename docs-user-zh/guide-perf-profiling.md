# 使用 Linux perf 进行分析

Linux 有一个名为 `perf` 的原生分析器，可以对任何应用程序进行分析。该分析器内置了一个以 `perf report` 命令形式存在的配置文件查看器，但你可能不喜欢它的用户界面。

[Firefox 分析器](https://profiler.firefox.com/) 为这些配置文件提供了另一种用户界面；它知道如何显示来自 perf 的配置文件。

（正在研究导入其他配置文件源的方法，例如在 [issue #1138](https://github.com/firefox-devtools/profiler/issues/1138) 和 [issue #1553](https://github.com/firefox-devtools/profiler/issues/1553) 中。）

Gecko 分析器与 perf 之间存在三个主要区别：

1.  Gecko 分析器只能分析 Gecko。Perf 可以分析系统上的任何进程。
2.  Gecko 分析器基于挂钟时间以固定频率采样。Perf 基于每个线程经过的 CPU 时间进行采样。
3.  Gecko 分析器默认只采样一小部分线程。Perf 会采样给定进程或进程树中的所有线程。

由于第 2 点，当分析许多线程时，perf 的开销远低于 Gecko 分析器：perf 仅在线程运行时才获取样本，而 Gecko 分析器即使在这些线程处于空闲状态时，也会以固定频率继续采样所有被分析的线程。

## 使用说明

（此处假设你已经安装了 perf。）

获取 perf 配置文件并将其加载到 Firefox 分析器中需要三个步骤：

1.  使用 perf 捕获配置文件。
2.  将其转换为文本格式。
3.  在 [profiler.firefox.com](https://profiler.firefox.com) 中加载文本文件。

### 第 1 步：捕获配置文件

你可以将 perf 附加到现有的（正在运行的）进程，也可以从它启动一个新进程并从一开始就对其进行分析，包括从其启动的整个进程树。

要附加到 PID 为 `<pid>` 的现有进程，请使用：

```bash
perf record -g -F 999 -p <pid>
# Stop with Ctrl+C once you've collected enough
```

要在分析器下启动新进程，请使用

```bash
perf record -g -F 999 program options
# Stop with Ctrl+C once you've collected enough
```

对于 Firefox，这将是：

```bash
perf record -g -F 999 firefox -P profile -no-remote
```

`perf record` 命令将配置文件写入当前目录下的 `perf.data` 文件中。

### 第 2 步：转换配置文件

退出 perf 后，将 perf 数据转换为 Firefox 分析器可以读取的格式：

```bash
perf script -F +pid > /tmp/test.perf
```

`perf script` 命令以 `perf record` 命令写入的 `perf.data` 文件作为其隐式输入。

### 第 3 步：在 profiler.firefox.com 中查看配置文件

你现在可以将 .perf 文件加载到 [profiler.firefox.com](https://profiler.firefox.com) 中：告诉 Firefox 分析器打开该文件，它应该会被自动识别并加载。
请注意，将没有标记和类别。许多堆栈帧将显示为 `[unknown]`。除非你执行如下所述的额外工作，否则将没有 JavaScript 堆栈。符号可能会被混淆。

## 分析 perf 跟踪数据和提示

请记住，'perf' 仅在线程正在执行时记录样本；如果线程处于睡眠状态，则省略样本。这意味着在许多情况下，样本之间会有间隙，通常是大间隙。你可能希望从左上角的“Categories”（类别）切换到“Stack height”（堆栈高度）模式，以便查看在不同线程上何时获取了样本。

Perf 有时在遍历堆栈时会遇到问题，你将在顶层得到错误根节点的子树。

Perf 可以将内核调用堆栈包含在配置文件中。它们可能显示为来自 `[kernel.kallsyms]` 库的 `[unknown]` 帧。将 `/proc/sys/kernel/perf_event_paranoid` 设置为 1 或更低可能会对此有所帮助。

如果你需要 JavaScript 堆栈，你可以使用 `--enable-perf` 构建 Firefox，然后使用环境变量 `IONPERF=func` 运行它。这将允许将一些 `[unknown]` 帧解析为 JS 源文件/函数。这样做确实会对记录的配置文件产生一些影响，但通常很小。
