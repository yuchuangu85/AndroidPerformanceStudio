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
