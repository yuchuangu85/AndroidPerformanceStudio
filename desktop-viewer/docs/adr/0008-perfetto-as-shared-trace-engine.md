---
status: accepted
---

# ADR-0008: Perfetto 作为共享 trace 数据引擎

将 Perfetto capture 和 Trace Processor 从 `perfetto-viewer` 的功能内实现提取为独立的 `platform-perfetto` composite build，供 Frame、Memory、Startup 和 Capture System Activities 共享。该 build 初始只包含一个 JVM library module；平台负责工具定位与校验、capture config 合并/校验/序列化、Trace Processor 生命周期、并发与取消、SQL 执行和类型安全取列，但不认识任何功能业务 DTO。

## 边界

- FrameTimeline、Native Heap 和 Startup 的 SQL、Capability ID 及 DTO 映射归各功能 adapter；各功能也声明自己的 Perfetto 数据源，平台不提供不断膨胀的“万能默认配置”。Trace Processor 原始文本不泄漏到 UI。
- 每个打开的 Capture Artifact 拥有一个可关闭的 `TraceAnalysisContext`，端口动态分配并对调用者隐藏；同一 Artifact 的 adapter 共享 context。当前不建全局 daemon 或推测性缓存。
- 默认只使用发行包内固定并校验 SHA-256 的 Trace Processor。用户显式配置的 binary 必须记录版本与哈希并通过兼容检查；不静默回退到 `PATH` 中的任意版本。

## 数据源策略

- Native Heap 默认走 Trace Processor SQL。只在 Trace Processor 不可用或版本不兼容时才自动降级到现有 wire parser，并将 Artifact 标记为 `PARTIAL`、记录 fallback 原因与缺失能力；trace 损坏或 SQL 失败不静默降级。
- Android 12+ 的有界 Frame Capture/Import 以 FrameTimeline 为权威证据；Live Frame Observation 继续优先 FrameMetrics Agent，失败再用 `gfxinfo`。两类意图不伪装为同一证据能力。
- Startup 保留 platform reported timing、Agent 自定义里程碑和 Perfetto 根因证据，仅通过带误差界限的 Clock Mapping 关联。传统 HPROF 与 Perfetto `java_hprof` 通过不同输入 adapter 映射到同一 Memory 功能模型，不可证明的字段保持不支持或未知。
- Simpleperf 继续使用现有 parser 和强类型模型，本阶段不转换进 Perfetto。Perfetto Web UI 只作为原始 trace 高级探索入口；不搬入 Android Studio `profilers-ui`，不引入 `perfd/perfa`。
