---
status: accepted
---

# ADR-0006: 先建立 Capture Artifact 契约，后置 Profiler Session

继续保持模块化 Profiler 和各功能的强类型模型，不建立通用业务 record 树或统一时间线；但现在就为 Capture 和 Import 产生的单个、不可变原始证据建立中立的 `CaptureArtifact` 契约。这取代 ADR-0003 中“当前不为它建抽象”的绝对边界：后置的是跨产物 `Profiler Session` 和聚合 UI，不是产物身份与证据质量。

## 契约边界

- `profiler-contracts` 承载 `CaptureArtifact`、`StudioResult` 和版本化 JSON envelope，不承载 Frame、Heap、HTTP、Battery 或 CPU 业务 record。
- Envelope 记录本地 `id`、可变存放位置、内容 SHA-256、`contractVersion`、可选的产物 `format/formatVersion`、Provenance、Device Target、Process Identity、`clockDomains/clockMappings`、`requestedCapabilities/availableCapabilities`、Artifact Completeness、限制和警告。
- `path` 不属于身份；字节改变时必须登记新 Artifact。暂不基于哈希做全局去重。
- Capability 使用受校验的命名空间 ID，其常量归功能模块所有；Completeness 只有 `COMPLETE`、`PARTIAL`、`UNKNOWN`，并以本次请求的能力为判定范围。Capture 失败是操作结果，不是 Artifact Completeness。
- Import 不成为产物 producer；真实 producer 不可知时显式记为 unknown。不同 Clock Domain 只能通过带误差界限的 Clock Mapping 关联，是否可用由功能 adapter 判定。
- 持久化 envelope 默认不包含原始 ADB serial，使用应用本地加盐的设备标识；只有显式保留敏感身份时才可导出 serial。PID 不单独构成 Process Identity，缺少启动标识时必须标记为弱身份。
- Live Observation 只在停止、快照或导出为不可变证据后创建 Capture Artifact。Trace Processor 查询结果、符号化缓存和导出报表是可重建的功能内产物，不登记为 Capture Artifact。

## 后置项

`.aps-session` 及 `Profiler Session` 只在需要聚合多个 Artifact 时建立。当前不为统一时间线、AOSP Transport、`perfd/perfa` 或 Simpleperf-to-Perfetto 转换预留接口。
