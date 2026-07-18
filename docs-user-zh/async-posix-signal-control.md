# 使用 POSIX 信号控制分析器

Firefox 分析器支持使用异步 POSIX 信号执行有限的操作，具体包括启动和停止分析器。在无法使用常规 UI 或键盘快捷键控制分析器的情况下，这可能非常有用。

简而言之，用户可以向 Firefox 主进程发送 POSIX 信号 `SIGUSR1` 以启动分析器，并发送信号 `SIGUSR2` 以停止分析器。收到“停止”信号后，分析器将收集当前的配置文件数据，并将其写入用户下载目录中的 `.json` 文件。例如，在 MacOS 上：

    // Send signal USR1 to the first (main) process of Firefox.app to start the profiler
    kill -s USR1 `pgrep -f Firefox.app | head -n 1`
    // wait for firefox to gather some data
    sleep 10
    // Send signal USR2 to the same process to stop the profiler
    kill -s USR2 `pgrep -f Firefox.app | head -n 1`
    // Find the result in the download folder
    ls ~/Downloads/ | grep "profile.*json"

From there, the user can open the written profile using the standard profiler
UI, by navigating to [profiler.firefox.com](https://profiler.firefox.com), and selecting
"Load a profile from file".

Typically, sending a signal to a program is done using the `kill`
[command](https://man7.org/linux/man-pages/man1/kill.1.html), as seen in the
above example. It is important to specify the correct signal, as the default is
`TERM`, which will terminate the program. For the profiler, only signals
`SIGUSR1` (for starting) and `SIGUSR2` (for stopping) should be used. Note that they
are written without the `SIG` prefix when used as an argument to `kill` i.e.:

    kill -s USR1 <firefox pid>      // start the profiler
    kill -s USR2 <firefox pid>      // stop the profiler

    // Alternative syntax
    kill -USR1 <firefox pid>
    kill --signal USR1 <firefox pid>

!> Only the process ID of the "main" process should be used. Child processes are
controlled by the parent process via IPC, and are unable to write their
processes to disk (due to sandboxing restrictions), so are not useful to profile
using signals.

The process ID of a running instance of Firefox can be found in a number of
ways, for instance:

    pgrep -f Firefox.app      // On MacOS
    pidof Firefox             // on Linux

In case of confusion, the main process is typically the one with lowest ID, when
sorted numerically.

Signal support is intended as a last-ditch debugging tool for situations where
we want to be able to diagnose issues with Firefox, but may not be able to use
the existing UI controls or Keyboard shortcuts to start/stop the profiler. For
example, if we encounter a prolonged freeze while browsing, it may be useful to
capture a profile by using POSIX signals to start and stop the profiler.

In a "normal" profiling flow, the resulting profile is communicated to the
profiler front-end directly, and then displayed to the user at the end of a
profiling session. The signal-controlled profiler bypasses this flow, and
instead writes a profile to disk. This makes it possible for us to debug in
situations where we may not be able to access the profiler UI in the same
session of firefox as the one we are debugging, with the small added friction of
needing to directly load the profile.

## Limitations

The current implementation of POSIX signal support has a number of important
limitations that potential users need to be aware of:

- The profiler currently uses a set of "default"
  [values](https://searchfox.org/mozilla-central/rev/7a8904165618818f73ab7fc692ace4a57ecd38c9/tools/profiler/core/platform.cpp#633)
  when started using signals. There are currently plans to support configuration
  (see [Bug 1866007](https://bugzilla.mozilla.org/show_bug.cgi?id=1866007) for
  further information).
- Async signal handling is currently only supported and tested on POSIX native
  platforms, i.e. Linux and MacOS. Support for Windows is planned, but not yet
  implemented (see [Bug
  1867328](https://bugzilla.mozilla.org/show_bug.cgi?id=1867328) for further
  information).
- The "stop" signal must be sent to Firefox's "main" process. This is due to
  Firefox's sandboxing rules, which disallow non-main processes (in general)
  from opening file handles. Because of this, individual processes cannot dump
  their own data to disk, so cannot individually handle the stop signal.
- Signal support in the Firefox profiler is incompatible with Firefox's [code
  coverage](https://firefox-source-docs.mozilla.org/tools/code-coverage/index.html)
  tooling, as both rely on the same POSIX signals (`SIGUSR1` and `SIGUSR2`). In
  an ideal world we could use Linux's
  [real-time](https://man7.org/linux/man-pages/man7/signal.7.html) signals
  instead, as they offer a much larger set of user-defined signals that would
  not clash with the signals used by the code coverage tool. Unfortunately,
  MacOS does not support these signals, so we are limited to the smaller set of
  signals in order to support a wider set of platforms.
- Although signals are used to start/stop the profiler, the aggregation of
  content-process profiles is still done using "traditional" Firefox IPC calls.
  If, therefore, you are using signals to diagnose issues with a "stuck" main
  thread in the main process, Firefox may not be able to aggregate child content
  process profiles.
