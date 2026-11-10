# HPROF 加载实现

实验性 Rust HPROF 侧车已移除，当前加载路径见 [Memory Profiler](../../../docs/features/memory-profiler.md)。

`HprofInputProbe` 检查边界，`HprofParser` 流式解析对象并按需读取 primitive array，`HprofRecordIndexer` 为大文件建立记录索引。
`DesktopMemoryProfilerBackend` 通过 `ManagedHprofSource` 内容租约固定源文件，构建真实 HeapDump，经 `MemoryDeepAnalyzer` 和 `HeapDumpSnapshotSource` 提供直方图、支配树、引用链和导出。
LeakCanary 分析继续使用 Shark。可选 Trace Processor 指标只在原始类名、heap、对象数和 shallow size 全部匹配后增强，绝不将 TP ID 当作 HPROF ID。

取消、失败和未交付的加载均释放租约；成功快照持有租约直到关闭。
