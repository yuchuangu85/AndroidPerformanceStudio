# Network Profiler MCP

[总览](README.md) | [Memory](memory.md)

> **统一入口：** 对已绑定的HAR，可以用 [`aps_analyze_file`](orchestrator.md) 读取摘要，再用 `aps_follow_up` 的 `har_slowest` / `har_phases` 读取有限排名和阶段统计。下文的“未暴露”仅指模块专用工具；统一入口不做设备采集。

## 当前状态
- 当前已实现 MCP 接口：**0**，Network 模块没有注册 MCP 工具。
- 当前 `aps-mcp` 服务端只注册 `memory_*` 工具，不提供网络会话访问。
- 统一 `aps-mcp` provider/namespace 是架构方向，不代表 Network 已完成模块注册。
- Network Agent 传输、桌面分析和 AI 入口均不等于 MCP 接口。

## 使用方式与 MCP 输入输出
- 目前没有可调用的 Network MCP 接口，也没有本模块的 MCP 启动模式。
- MCP 输入：**未暴露**；HAR、观测快照、分页和筛选条件不是 MCP 参数。
- MCP 输出：**未暴露**；脱敏条目、阶段详情和报告不是 MCP 工具结果。
- 不能通过本模块 MCP 启动 Agent、读取请求详情或导出流量。

## 现有领域能力（非 MCP）
- `HarSession` 通过 HAR 引擎读取有界输入，形成脱敏条目及会话摘要。
- 会话绑定来源 SHA-256，缓存页面返回同一来源摘要并检查文件变化。
- `entry_detail` 按需提供脱敏 headers、阶段可用性和固定类型的错误证据。
- `analysis`、`domains`、`activity` 提供本地汇总、域名分组与活动统计。
- `from_observed` 接受独立 Agent 观测快照，不把观测数据伪装成 HAR 文件。
- 桌面可导出脱敏 JSON、CSV、部分 HAR 和带清单的证据包。

## 约束与证据边界
- URL 路径、查询值、凭据、Cookie、正文及大部分 header 值不进入公共投影。
- HAR 阶段是 producer 提供的推断证据，不能当作设备端直接观测。
- 调用 outcome 独立于 HTTP status，4xx/5xx 不能直接推出调用 Failed。
- Agent 采集要求明确同意、运行中的 debuggable 应用和已插桩的 OkHttp clients。
- WebView、Cronet、URLConnection、native socket 和未插桩客户端不在该覆盖范围内。
- 原始事件字节不保留；脱敏证据包不等于完整 wire traffic 或无敏感数据。
- 本页仅核对源码，不声明 MCP 或真实应用网络采集端到端验证通过。

## 代码依据
- [网络会话与查询 API](../../../rust-desktop/crates/aps-network/src/lib.rs)
- [Agent 观测模型](../../../rust-desktop/crates/aps-network/src/observed.rs)
- [脱敏与覆盖说明](../../../rust-desktop/crates/aps-network/README.md)
- [当前 MCP 工具注册](../../../rust-desktop/crates/aps-memory/src/mcp/protocol.rs)
