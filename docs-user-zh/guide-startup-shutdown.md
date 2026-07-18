# 分析 Firefox 启动与关闭

## 启动

在开始之前，请确保分析器的弹出窗口已经存在。否则，请先前往 https://profiler.firefox.com 将其添加到你的 Firefox 中。

1. 设置环境变量 `MOZ_PROFILER_STARTUP=1` 来启动 Firefox。这样，分析器将在启动过程中尽早开始运行。

   在运行 [Mach](https://firefox-source-docs.mozilla.org/mach/) 时，也可以内联执行此操作：

   ```bash
   $ MOZ_PROFILER_STARTUP=1 ./mach run
   ```

2. 然后像往常一样使用弹出窗口捕获配置文件。

启动分析不使用你在 `about:profiling` 中配置的设置。它使用可通过环境变量 `MOZ_PROFILER_STARTUP_ENTRIES`、`MOZ_PROFILER_STARTUP_INTERVAL` 等配置的设置：

- 如果缓冲区看起来不够大，你可以通过环境变量 `MOZ_PROFILER_STARTUP_ENTRIES` 调整缓冲区大小。默认值为 1000000，即 9MB。如果需要 90MB，请使用 10000000；如果需要 180MB，请使用 20000000，这些是调试长时间启动的良好数值。

- 如果你想要更粗的分辨率，也可以使用 `MOZ_PROFILER_STARTUP_INTERVAL` 选择不同的间隔，默认值为 1（单位为毫秒）。你不能低于 1 ms，但可以使用例如 10 ms。

- 还有更多环境变量可用于控制分析器设置。可以通过设置 `MOZ_PROFILER_HELP=1` 并在终端内的命令行中运行 Firefox 来列出它们；Firefox 将立即退出，并在终端中显示所有接受的变量。

## 关闭

1. 设置环境变量 `MOZ_PROFILER_SHUTDOWN=<filename>` 来启动 Firefox，其中 `<filename>` 是应保存记录配置文件的文件名。

2. 使用弹出窗口启动分析器，然后关闭 Firefox。

3. 你指定的文件将包含记录的配置文件。通过 [profiler.firefox.com](https://profiler.firefox.com) 界面，使用拖放或文件上传界面加载它。

对于启动分析，类似于 [桌面端的启动分析](https://developer.mozilla.org/en-US/docs/Mozilla/Performance/Profiling_with_the_Built-in_Profiler#Profiling_Firefox_Startup)，你需要手动设置一些 `MOZ_PROFILER_STARTUP*` 环境变量。具体操作方式取决于你要分析的 app（详见下文）。一旦使用这些环境变量启动了 app，分析器就会运行。然后你可以像往常一样使用 `about:debugging` 连接到 app，并使用常规 UI 捕获配置文件。

## Firefox for Android

首先，你需要参考 [关于在 Android 上进行分析的一般信息](./guide-remote-profiling.md)。
然后你可以遵循以下额外说明。

### 启动分析 GeckoView-example（以及 Fennec）

如果你已在本地编译了 GeckoView-example，可以使用 `./mach run` 启动它，并按如下方式指定环境变量：

```bash
./mach run --setenv MOZ_PROFILER_STARTUP=1 \
           --setenv MOZ_PROFILER_STARTUP_INTERVAL=5 \
           --setenv MOZ_PROFILER_STARTUP_FEATURES=js,stackwalk,screenshots,ipcmessages,java,processcpu,cpu \
           --setenv MOZ_PROFILER_STARTUP_FILTERS="GeckoMain,Compositor,Renderer,IPDL Background"
```

或者，如果你从其他来源安装了 GeckoView-example，你可以使用 `adb` 从命令行启动它，并像这样指定环境变量：

```bash
adb shell am start -n org.mozilla.geckoview_example/.App \
    --es env0 MOZ_PROFILER_STARTUP=1 \
    --es env1 MOZ_PROFILER_STARTUP_INTERVAL=5 \
    --es env2 MOZ_PROFILER_STARTUP_FEATURES=js,stackwalk,screenshots,ipcmessages,java,processcpu,cpu \
    --es env3 MOZ_PROFILER_STARTUP_FILTERS="GeckoMain,Compositor,Renderer,IPDL Background"
```

### 启动分析 Fenix

Fenix 有 [一种不同的方式](https://firefox-source-docs.mozilla.org/mobile/android/geckoview/consumer/automation.html#reading-configuration-from-a-file) 来指定环境变量：它使用 yaml 文件。

设置启动分析的最简单方法是运行 `<mozilla-central-repo>/mobile/android/fenix/tools/setup-startup-profiling.py` 脚本。例如：

```bash
./mobile/android/fenix/tools/setup-startup-profiling.py activate nightly  # To activate startup profiling on nightly.
./mobile/android/fenix/tools/setup-startup-profiling.py deactivate beta  # To deactivate startup profiling on beta.
```

如果 app 已卸载或设备已重启，可能需要重新运行 `activate` 命令。该脚本硬编码为使用带有默认分析参数的默认配置文件。如果你想更改这些参数或使用非标准 app ID，请在本地修改脚本或阅读下文。

如果你不想检出 [mozilla-central](https://hg.mozilla.org/mozilla-central/)，你应该能够下载 [独立脚本](https://hg.mozilla.org/mozilla-central/raw-file/tip/mobile/android/fenix/tools/setup-startup-profiling.py) 并执行它。

#### 手动配置

上述 YAML 文件的文件名取决于你的 Fenix app 的 bundle ID。以下说明假设你要分析的是 Fenix Nightly app，其 bundle ID 为 `org.mozilla.fenix`。

1. 在你的桌面机器上创建一个名为 `org.mozilla.fenix-geckoview-config.yaml` 的文件，内容如下所示：

    ```
    env:
      MOZ_PROFILER_STARTUP: 1
      MOZ_PROFILER_STARTUP_INTERVAL: 5
      MOZ_PROFILER_STARTUP_FEATURES: js,stackwalk,screenshots,ipcmessages,java,processcpu,cpu
      MOZ_PROFILER_STARTUP_FILTERS: GeckoMain,Compositor,Renderer,IPDL Background
    ```

2.  Push this file to the device with `adb push org.mozilla.fenix-geckoview-config.yaml /data/local/tmp/`.
3.  Run `adb shell am set-debug-app --persistent org.mozilla.fenix` to make sure the file is respected.

From now on, whenever you open the Fenix app, Gecko will be profiling itself automatically from the start, even if remote debugging is turned off. Then you can enable remote debugging, connect to the browser with `about:debugging`, and capture the profiling run.

You can delete the file again when you want to stop this behavior, e.g. using `adb shell rm /data/local/tmp/org.mozilla.fenix-geckoview-config.yaml`.

[Here's an example profile captured using this method](https://perfht.ml/3bKTFCG).

Refer to the [Reading configuration from a file](https://firefox-source-docs.mozilla.org/mobile/android/geckoview/consumer/automation.html#reading-configuration-from-a-file) section of the GeckoView docs for more details.

### Profiling App Link startup

Fenix can be launched with a URL as follows (assuming a debug Fenix build):

```
adb shell am start-activity -d "https://www.mozilla.org/" \
 -a android.intent.action.VIEW org.mozilla.fenix.debug/org.mozilla.fenix.IntentReceiverActivity
```

When combined with the startup profiling `.yaml` file as described in the previous section, this allows profiling GeckoView during the App Link startup path. This is the scenario of a user opening a link from a different Android app in the default browser.

Startup from App Link is the most important GeckoView startup scenario. In this scenario, GeckoView startup is directly in the critical path between the user action (tapping the link) and the first useful result (the web page being shown on the screen). This is different from the scenario of launching Fenix from the home screen - in that case, Fenix can show meaningful content even before Gecko is initialized, so Gecko's startup time is not as crucial to the experience.
