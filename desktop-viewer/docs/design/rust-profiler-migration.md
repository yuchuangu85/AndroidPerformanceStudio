# Profiler 引擎现状

CPU、HAR、HPROF 三套实验性 Rust 引擎及其客户端、设置和 Cargo/发布接线已移除。

- CPU：Kotlin Simpleperf protobuf 导入、SQLite 索引和分析继续工作；LOCAL/Firefox 查看器选择不变。
- HAR：Kotlin HarParser 保留取消检查、脱敏和导入错误净化。
- HPROF：Kotlin parser/probe/indexer 加载原始对象 ID，现有 JVM 图分析提供对象查询和支配树，Shark 提供 LeakCanary 分析。
- 托管 HPROF 内容租约、来源摘要和可选 Trace Processor 类指标验证保留；TP ID 不用于替换原始对象 ID。
- 构建、测试和打包无需 Rust。第三方子模块与其 Rust 代码不受影响。
