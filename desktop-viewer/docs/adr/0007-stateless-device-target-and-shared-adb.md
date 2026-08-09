---
status: accepted
---

# ADR-0007: 使用无状态 Device Target 和共享 ADB 原语

不建立跨功能的有状态 `DeviceSession`：公共层使用不可变 `DeviceTarget` 标识设备，由 ADB client 提供发现、命令、push/pull/forward、超时、取消和类型化错误，捕获生命周期仍归功能层所有。这以与已有无状态 `adb-core` 一致的边界取代 ADR-0004。

## 依赖方向

将现有 `platform-adb` composite 提升并重命名为中立的 `platform-core`，初始只包含 `profiler-contracts`、`host-toolchain` 和 `adb-core`。`host-toolchain` 是唯一的 Host Process 执行、文本/二进制输出、超时与取消实现；`adb-core` 只构造、执行并解释 ADB 命令，不保留第二套 process runner。`StudioResult` 原样迁入 `profiler-contracts`，本次不重做错误体系。

## 结果

其他 Profiler 不再为 ADB、Host Toolchain 或通用结果类型反向 `includeBuild("../simpleperf-viewer")`；业务 adapter 可组合公共原语，但不得直接启动 `adb` 或重复实现设备发现、安全校验、超时和错误分类。
