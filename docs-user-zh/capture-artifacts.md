# Capture Artifact 与证据状态

Android Performance Studio 仍在各专用 Profiler 的强类型模型中保存采集或导入的证据，同时为每个不可变证据写入小型、版本化的 **Capture Artifact** 信封。支持的 Profiler 会显示：

- 原始生产者（无法证明时为 **Unknown**）；
- 格式和 SHA-256 内容标识；
- 可用时的隐私安全设备/进程身份；
- 可用 Capability，以及产物的 Complete、Partial 或 Unknown 状态；
- 限制、警告以及分析期间使用的显式 fallback。

Import 只把 Android Performance Studio 记为执行导入的应用，不会冒充原始生产者。Artifact 元数据默认不持久化原始 ADB serial。

Perfetto Native Heap、Java Heap、FrameTimeline 和 Startup 根因证据使用应用固定支持的 Trace Processor 版本。工具缺失或不兼容时，UI 会显示可操作的错误。Native Heap 仅在工具缺失/不兼容时允许文档化的 wire fallback；损坏的 trace 或 SQL/schema 错误不会被静默重解释。

FrameTimeline 是 Android 12+ 有界帧 trace 导入的权威证据；Live Frame Observation 仍是独立的低延迟工作流。Startup Perfetto 证据只有在 Clock Mapping 误差上界不超过 5 ms 时才标记为已关联。

### 从慢帧到可审阅的源码补丁

1. 选中有实测耗时的超时帧或平台卡顿帧，点 **AI 建议在哪里加 Trace**。这一步只分析帧证据，不上传工程。渲染阶段耗时不能当作方法耗时。
2. 在 **源码工作区** 打开与 APK 匹配的本地 Kotlin 或 Java 文件；回到帧详情，选择同步方法，生成并审阅 Trace 补丁。复制或另存为新的 `.patch`，在目标工程先运行 `git apply --check`。APS 不替你修改、构建或安装应用。
3. 应用补丁并重新构建、安装后，刷新源码快照、重选进程。点 **采集 FrameTimeline**（Android 12+），复现相同操作。在帧详情查看探针与实际帧窗口的重叠耗时、该线程在窗口内已观测的上 CPU 时间。未观测不等于零，嵌套区段不能相加。
4. 对定向复采的帧，**定位探针源码** 按本机保存的导出记录和文件哈希定位，不需要 AI 权限。若同一探针在至少两个不同的慢帧中与主线程或渲染线程重叠，可点 **根据此探针建议源码优化**。先开启该工作区的 AI 源码共享，再勾选本次包含源码，审批所示的方法片段。请求不带历史对话；源码变化、定位歧义、引用不符或修改越界时不生成补丁。
5. 在 **采集前后对比** 把同一场景的多次采集加入基线组和改动后组；工作区打开期间每组可加入八次。对比各次 P95 的中位数、可判定帧的未命中率及其百分点变化，并看探针覆盖。缺少设备、进程、刷新率等信息或重复加入同一 Trace 时不比较。导入 Trace 可解读探针，不会自动绑定到某台设备或本地工程。移除探针后应再次测量；一次改善不能证明普遍收益。
