# Trace Analyzer MCP

[总览](README.md) | [Memory](memory.md)

## 当前状态
- 当前已实现 MCP 接口：**0**，Trace 模块没有注册 MCP 工具。
- 当前 `aps-mcp` 服务端只注册 `memory_*` 工具，不提供 Trace 查询。
- 统一 `aps-mcp` provider/namespace 是架构方向，不代表 Trace 已完成模块注册。
- Trace Processor、Perfetto UI 与 AI 证据分析均不等于 MCP 支持。
- [Frame 的探针重叠查询](frame.md#frame_trace_overlaps)由 `aps-frame-mcp` 提供，
  仅返回凭据绑定的帧报告中已计算的观测；它不属于 Trace Analyzer 工具，也不执行 Trace 查询。

## 使用方式与 MCP 输入输出
- 目前没有可调用的 Trace MCP 接口，也没有本模块的 MCP 启动模式。
- MCP 输入：**未暴露**；Trace 文件、预设、检索词和书签不是 MCP 参数。
- MCP 输出：**未暴露**；查询表、诊断发现与搜索命中不是 MCP 工具结果。
- 本地 Trace Processor 的进程模式不是本模块的 MCP 启动方式。

## 现有领域能力（非 MCP）
- `TraceWorkspace` 导入显式选定的本地 Trace，管理诊断、搜索、采集会话与书签。
- `TraceProcessor` 校验固定版本 v59.1 和主机对应 SHA-256，不使用任意 PATH 工具。
- `TraceSession` 优先维护 warm native session，使同一 Trace 解析后可复用查询。
- warm 模式不可用时可降级为有界单次查询，并通过会话模式暴露降级状态。
- 诊断采用固定目录与预设，搜索使用受约束的 slice 检索。
- Trace 工作区另有本地 Perfetto 采集与外部查看器集成，不属于 CPU Capture 的 MCP。

## 约束与证据边界
- Trace 输入上限为 8 GiB；分页、诊断行和单元格预览有独立预算。
- 本地适配器不提供任意 SQL、自动下载或云端查询回退。
- 工具缺失、摘要或版本不符时应报告不可用，不能从文件扩展名推断可分析。
- 有界诊断结果可能截断，空结果不能直接证明整个 Trace 没有性能问题。
- 本地打开、诊断或搜索不代表授权上传 Trace，AI 流程仍有独立审批边界。
- Trace 采集和查询是不同路径，不能把读取已有文件描述成已验证设备采集。
- 本页仅核对源码，不声明 MCP、Trace Processor 或查看器运行验证通过。

## 代码依据
- [Trace 工作区](../../../rust-desktop/crates/aps-app/src/trace.rs)
- [Trace Processor 与会话 API](../../../rust-desktop/crates/aps-perfetto/src/lib.rs)
- [诊断目录](../../../rust-desktop/crates/aps-perfetto/src/diagnostics.rs)
- [Trace 采集工作区](../../../rust-desktop/crates/aps-app/src/trace/capture.rs)
- [当前 MCP 工具注册](../../../rust-desktop/crates/aps-memory/src/bin/aps_mcp.rs)
