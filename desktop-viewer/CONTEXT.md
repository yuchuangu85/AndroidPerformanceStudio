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

**Capture Artifact**:
由 Capture 或 Import 得到的单个、不可变的性能证据单元，带有来源、时间域、能力和完整性信息。
_Avoid_: Session（单个产物不是跨产物会话）

**Provenance**:
Capture Artifact 从实际生产者到 Capture 或 Import 的来源链；Import 只记录引入行为，不会成为该产物的生产者。
_Avoid_: Producer（生产者只是来源链的一部分）

**Capability**:
某个证据来源能够提供的信息集合，不表示某次 Capture 或 Import 已实际获得这些信息。
_Avoid_: Completeness（完整性描述单个产物的实际结果）

**Artifact Completeness**:
单个 Capture Artifact 相对本次请求的 Capability 实际保留的证据程度，并附带缺失原因；无法知道请求范围时为未知。
_Avoid_: Capability（能力不代表本次产物完整）

**Device Target**:
一次 Capture 所选的 Android 设备身份，用于定位设备并关联产物来源，不承载捕获生命周期。
_Avoid_: Device Session（设备选择不是会话）

**Process Identity**:
用于判断不同证据是否来自同一进程实例的身份，包括 Device Target、PID、进程或包名以及可获得的启动标识；缺少启动标识时只能视为弱身份。
_Avoid_: PID（PID 会被复用，不是完整的进程身份）

**Live Frame Observation**:
设备连接期间持续显示帧时序和卡顿趋势的低延迟观察，不与有界 Frame Capture 共享同一证据能力假设。
_Avoid_: Frame Capture（有界捕获会产生可供事后分析的 Capture Artifact）

**Clock Domain**:
时间戳所属的时钟原点和计时体系；不同 Clock Domain 的原始时间戳不可直接比较。

**Clock Mapping**:
两个 Clock Domain 在有效时段内的对应关系，带有误差界限；跨产物关联只能使用已知映射。
_Avoid_: Timestamp normalization（该说法会隐去时钟与误差语义）

**Heap Dump**:
进程对象图的一次点状快照，是 Analyze Memory Usage 与 Find Memory Leaks 的输入。
_Avoid_: Heap snapshot、HPROF（HPROF 是文件格式，不是概念）

**Allocation Track**:
对时间窗口内分配行为的逐次记账，按语言分 Java/Kotlin 与 Native 两种。

**Leak Suspect**:
被支配树 + 引用链分析标记为"疑似泄漏"的类或实例，带置信度，需人工复核。

**Profiler Session**:
关联一个或多个 Capture Artifact 的跨功能容器，共享设备、进程和时间上下文，但不统一各功能的业务数据模型。
_Avoid_: Session（与功能内会话歧义）、Capture（捕获是动作，不是容器）

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
