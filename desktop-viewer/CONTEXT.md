# Android Performance Studio (desktop-viewer)

桌面端应用，复刻 Android Studio Profiler 的能力集：从 Android 设备捕获性能数据，在桌面端分析并可视化。范围锁定为 8 项功能（见「功能」），分阶段交付。

## Language

## 功能

**Capture System Activities**:
系统级活动的时序记录（CPU、线程、渲染、内核事件），以 trace 形式捕获后沿时间线查看。
_Avoid_: System trace、systrace（旧名）

**Live Telemetry**:
在设备保持连接期间连续采样的进程/设备实时指标（CPU、内存、网络等）。

**CPU Hotspots — Callback Sample**:
对调用栈做统计采样，找出 CPU 热点。
_Avoid_: Profiling、Sampling（过于宽泛）

**CPU Hotspots — Method Recording**:
在时间窗口内记录 Java/Kotlin 方法的进入/退出，还原方法级执行时间线。
_Avoid_: Method trace（trace 一词已用于 Perfetto 产物）

**Analyze Memory Usage**:
对进程堆做快照（Heap Dump）后，分析对象分布、直方图、保留大小。

**Find Memory Leaks**:
基于可达性分析，标记疑似泄漏的类或实例及其引用链。
_Avoid_: Leak（"泄漏"是已确认事实，本工具只产嫌疑对象）

**Track Memory Consumption — Java/Kotlin Allocations**:
时间窗口内对 Java/Kotlin 对象分配的逐次记账。

**Track Memory Consumption — Native Allocations**:
时间窗口内对 native (malloc) 分配的逐次记账。

## 核心概念

**Capture**:
从设备端获取一段有界的性能记录的过程（触发、采集、拉回）。与 Import 相对。
_Avoid_: Record（Android Studio 的界面用语，易与"录制型功能"混淆）

**Import**:
分析一个已经捕获好的产物文件，无需设备交互。
_Avoid_: Open、Load（与内存态加载歧义）

**Heap Dump**:
进程对象图的一次点状快照，是 Analyze Memory Usage 与 Find Memory Leaks 的输入。
_Avoid_: Heap snapshot、HPROF（HPROF 是文件格式，不是概念）

**Allocation Track**:
对时间窗口内分配行为的逐次记账，按语言分 Java/Kotlin 与 Native 两种。

**Leak Suspect**:
被支配树 + 引用链分析标记为"疑似泄漏"的类或实例，带置信度，需人工复核。

**Session**:
一次 Profiler 会话的统一容器，计划承载 CPU/内存/网络等全部数据源的时间线数据。**最终阶段目标**——当前各功能产出独立产物（HPROF / perfetto trace / simpleperf / ART trace），为此不建抽象。
_Avoid_: Capture（捕获是一次性动作，不是会话）
