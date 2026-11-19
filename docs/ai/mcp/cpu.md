# CPU Profiler MCP

[总览](README.md) | [Memory](memory.md)

## 当前状态
- 当前已实现 MCP 接口：**0**，CPU 模块没有注册 MCP 工具。
- 当前 `aps-mcp` 服务端只注册 `memory_*` 工具，不提供 CPU 会话访问。
- 统一 `aps-mcp` provider/namespace 是架构方向，不代表 CPU 已完成模块注册。
- 本页记录当前边界，不定义待实现工具或协议。

## 使用方式与 MCP 输入输出
- 目前没有可调用的 CPU MCP 接口，也没有本模块的 MCP 启动模式。
- MCP 输入：**未暴露**；本地 profile、过滤器和会话对象不是 MCP 参数。
- MCP 输出：**未暴露**；调用树、热点和导出内容不是 MCP 工具结果。
- 桌面 AI 分析与本地解析不等于 MCP 支持，不能据此构造调用。

## 现有领域能力（非 MCP）
- `CpuSession` 支持 SIMPLEPERF protobuf 与 AOSP Gecko / Firefox Profiler v24 JSON。
- 原始 `perf.data` 不是该会话解析器的直接输入，需要独立的主机转换步骤。
- 查询可按线程、事件、半开纳秒时间范围、树方向和权重方式筛选。
- `top_functions` 提供热点分页；火焰图、Stack Chart 和线程时间线提供本地绘图投影。
- CSV、折叠栈、JSON 报告和 Firefox profile 导出是领域 API，不是 MCP 接口。
- 已解码样本及完整查询投影留在本地会话内存中。

## 约束与证据边界
- 输入字节预算不是进程内存上限，不能据此承诺大型 profile 的峰值 RAM。
- 采样计数或采样区间不能解释为方法调用次数或精确方法执行时长。
- 火焰图水平缩放使用归一化权重范围，不是时间范围。
- 事件计数权重要求单一事件类型，不能直接混合不同事件单位。
- 本地会话适配器不执行 ADB；设备采集与导入是不同责任边界。
- 本页仅核对源码，不声明 MCP 运行、真实设备或原生界面验收通过。

## 代码依据
- [CPU 会话与查询 API](../../../rust-desktop/crates/aps-cpu/src/lib.rs)
- [CPU 模块说明](../../../rust-desktop/crates/aps-cpu/README.md)
- [图表投影](../../../rust-desktop/crates/aps-cpu/src/charts.rs)
- [导出实现](../../../rust-desktop/crates/aps-cpu/src/export.rs)
- [当前 MCP 工具注册](../../../rust-desktop/crates/aps-memory/src/bin/aps_mcp.rs)
