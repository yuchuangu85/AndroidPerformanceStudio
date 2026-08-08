# Android Performance Studio (desktop-viewer)

桌面端应用，复刻 Android Studio Profiler 的能力集：从 Android 设备捕获性能数据，在桌面端分析并可视化。范围锁定为 9 项功能（见「功能」），分阶段交付。

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

**Battery Profiler**:
以测量前后的 Battery Snapshot 差值分析目标 UID 的资源使用与能耗，并保留设备级电池状态及原始证据。

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

**Battery Snapshot**:
Battery Profiler 在某一时点取得的电池状态与资源累计值。实验结果由起止 Battery Snapshot 的差值产生。

**Online Battery Observation**:
实验期间对设备电量、温度、电压和供电状态进行的轻量趋势采样。UID 资源统计仍由起止 Battery Snapshot 计算，不把轮询样本解释为瞬时功耗。

**Read-only Battery Experiment**:
不会自动重置统计或修改设备电源、亮度、网络、Doze 等全局状态的电池实验。设备状态变更只能由用户通过独立且明确提示副作用的操作触发。

**Energy Evidence**:
带有来源、置信度和归因范围的能量数据。整机电流或 power rail 实测值属于 Device 范围；只有明确按 UID 提供的计数器才能作为 UID 实测值，系统功耗模型始终属于模型估算。

**Legacy Battery Historian Export**:
为兼容既有 Battery Historian 工作流而保留的完整 bugreport 导出。它不是轻量 history 导出，也不由 Perfetto 能量采集替代；产物按隐私敏感数据处理。

**Battery Experiment Conditions**:
用于解释和复现实验的只读外部状态，包括屏幕亮度与模式、屏幕开关、Doze deep/light 状态和默认网络传输类型；在起止 Battery Snapshot 记录，不包含瞬时 CPU/GPU 频率或完整 telephony dump。

**Repeated Battery Experiment**:
由多轮测量组成、轮次之间带有限冷却时间的电池实验。每轮均有独立的起止 Battery Snapshot；起始温度相对首轮漂移达到 3°C 时，结果带数据质量告警，但实验不会无限等待温度恢复。

**Interrupted Battery Experiment**:
已经开始但没有正常完成的电池实验。已完成轮次仍可查看，未完成轮次不能续跑；后续测量必须创建新实验，避免混用失效的 baseline 上下文。

**UID-attributed Resource**:
batterystats 记到某个 UID 名下的 Wakelock、Alarm、Job 或 Sensor 使用记录。它不证明 UID 内具体组件的所有权；Shared UID 或框架代理执行会使包级归因存在歧义，只有原始记录明确提供 source package 时才采用包归因。
