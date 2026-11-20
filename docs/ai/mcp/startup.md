# Startup Profiler MCP

[总览](README.md) | [Memory](memory.md)

> **统一入口：** 对已绑定的APS Startup CSV，可以用 [`aps_analyze_file`](orchestrator.md) 读取有界证据。下文的“未暴露”仅指模块专用工具；统一入口不做设备采集。

## 当前状态
- 当前已实现 MCP 接口：**0**，Startup 模块没有注册 MCP 工具。
- 当前 `aps-mcp` 服务端只注册 `memory_*` 工具，不提供启动实验接口。
- 统一 `aps-mcp` provider/namespace 是架构方向，不代表 Startup 已完成模块注册。
- 启动分析的本地数据与 AI 证据投影不能证明 MCP 支持。

## 使用方式与 MCP 输入输出
- 目前没有可调用的 Startup MCP 接口，也没有本模块的 MCP 启动模式。
- MCP 输入：**未暴露**；目标包、启动模式、实验配置和导入报告不是 MCP 参数。
- MCP 输出：**未暴露**；启动运行记录、统计与工件不是 MCP 工具结果。
- 不存在通过本模块 MCP 发起启动、确认实验或读取指标的用法。

## 现有领域能力（非 MCP）
- `StartupSession` 保留每次运行的请求模式、观测模式、来源和缺失指标原因。
- 本地解析接受 Activity Manager 启动输出、单次启动事件证据与已有 CSV 报告。
- 运行记录分别存储 `ThisTime`、`TotalTime`、`WaitTime`、TTID 和 TTFD。
- `capture_startup` 是显式 Review / Confirm 后执行的阻塞式平台实验入口。
- 实验绑定批准的设备、包与配置，并在执行中重新检查目标身份。
- 可选 CPU 热点采集按轮次保留独立 Perfetto Trace，默认关闭。

## 约束与证据边界
- TTID 需要平台 Displayed 证据；`TotalTime` 不能替代 TTID。
- TTFD 需要 Fully drawn 证据，缺失时不能回退为 TTID 或 Agent 首帧。
- 请求 Cold/Warm/Hot 不证明实际模式；缺失可靠 LaunchState 时观测模式保持未知。
- 导入报告中的编译、环境字段不能直接当作已验证的可比实验条件。
- CPU 调度时间与热点观测样本数是不同证据，样本数不能换算为方法耗时。
- 启动实验涉及应用生命周期副作用；本页不提供绕过确认的 MCP 通道。
- 本页仅核对源码，不声明设备实验、CPU 关联或 MCP 端到端验证通过。

## 代码依据
- [启动会话与指标模型](../../../rust-desktop/crates/aps-startup/src/lib.rs)
- [平台实验与确认边界](../../../rust-desktop/crates/aps-startup/src/capture.rs)
- [启动模块说明](../../../rust-desktop/crates/aps-startup/README.md)
- [当前 MCP 工具注册](../../../rust-desktop/crates/aps-memory/src/mcp/protocol.rs)
