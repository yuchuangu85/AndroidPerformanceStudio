# Capture MCP

[总览](README.md) | [Memory](memory.md)

> [CLI 采集](../headless-cli.md) 支持受审的定时 CPU 录制和已有 Macrobenchmark 场景。统一 MCP 的 `aps_run_plan` 只提供步骤预览，不执行这些操作。

## 当前状态
- 当前已实现 MCP 接口：**0**，Capture 模块没有注册 MCP 工具。
- 当前 `aps-mcp` 服务端只注册 `memory_*` 工具，不提供采集控制。
- 统一 `aps-mcp` provider/namespace 是架构方向，不代表 Capture 已完成模块注册。
- `Destination::Capture` 的状态仍为 Planned；已有 CPU 采集 API 不等于导航完整交付。

## 使用方式与 MCP 输入输出
- 目前没有可调用的 Capture MCP 接口，也没有本模块的 MCP 启动模式。
- MCP 输入：**未暴露**；设备、PID、采样配置和确认计划不是 MCP 参数。
- MCP 输出：**未暴露**；采集状态、产物与来源摘要不是 MCP 工具结果。
- 不存在经本模块 MCP 开始、停止、取消或确认采集的用法。

## 现有领域能力（非 MCP）
- `aps-capture` 提供显式、有界的 CPU / Simpleperf 采集，不是通用 MCP 采集服务。
- `CaptureController::plan_cpu` 只读检查目标，产生一次性 `PlannedCapture`。
- 计划绑定设备、PID、进程名、启动 ID 与进程启动 tick，确认后运行前再检查身份。
- 采样配置区分事件、频率或周期、事件范围和调用图模式。
- 成功产物分别记录原始数据、可选转换 protobuf、大小、SHA-256 与来源关系。
- `CaptureWorkspace` 的本地状态与完成事件供桌面外壳集成，不是 MCP 消息。

## 约束与证据边界
- 导航、构建计划或 Inspect 不会启动录制；运行必须消费明确确认的计划。
- 计划有效期为 60 秒，身份复查降低 PID 复用风险但不是内核原子租约。
- 采集会创建本次运行拥有的本地与远程资源，并使用有界传输。
- 取消只处理所拥有的操作；停止未确认时必须保留未验证状态，不能声称已清理。
- 原始 `perf.data` 与转换后的 protobuf 不可混称；传输成功不证明样本非空或导入成功。
- Perfetto 录制位于 Trace 路径，不能从 `aps-capture` 的 CPU API 推断统一采集覆盖。
- 本页仅核对源码，不声明 MCP、真实设备采集或打包转换器验证通过。

## 代码依据
- [CPU 采集计划与执行 API](../../../rust-desktop/crates/aps-capture/src/lib.rs)
- [采集确认与资源边界说明](../../../rust-desktop/crates/aps-capture/README.md)
- [桌面 CPU 采集工作区](../../../rust-desktop/crates/aps-app/src/capture.rs)
- [Destination 状态定义](../../../rust-desktop/crates/aps-ui-foundation/src/lib.rs)
- [当前 MCP 工具注册](../../../rust-desktop/crates/aps-memory/src/mcp/protocol.rs)
