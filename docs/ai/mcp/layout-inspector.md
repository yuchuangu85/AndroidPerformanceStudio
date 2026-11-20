# Layout Inspector MCP

[总览](README.md) | [Memory](memory.md)

> **统一入口：** 对已绑定的Inspector capture ZIP，可以用 [`aps_analyze_file`](orchestrator.md) 读取有界证据。下文的“未暴露”仅指模块专用工具；统一入口不做设备采集。

## 当前状态
- 当前已实现 MCP 接口：**0**，Layout Inspector 没有注册 MCP 工具。
- 当前 `aps-mcp` 服务端只注册 `memory_*` 工具，不提供布局快照访问。
- 统一 `aps-mcp` provider/namespace 是架构方向，不代表本模块已完成注册。
- Inspector Agent、主机协议和 AI 分析不是 MCP 工具注册的替代证据。

## 使用方式与 MCP 输入输出
- 目前没有可调用的 Layout Inspector MCP 接口，也没有本模块的 MCP 启动模式。
- MCP 输入：**未暴露**；归档、目标包、窗口和节点选择不是 MCP 参数。
- MCP 输出：**未暴露**；层级、截图、属性与布局分析不是 MCP 工具结果。
- 不能通过本模块 MCP 发起设备检查、注入 Agent 或读取运行时节点值。

## 现有领域能力（非 MCP）
- 归档导入读取版本 1/2 的 `agentperf-inspector-capture` ZIP，不使用替代 JSON 容器。
- 发布归档帧前校验清单、条目完整性与同一帧身份，保留来源摘要。
- 本地层级分页、画布与属性 API 使用同一窗口和节点模型。
- `capture_compatible` 提供 Agent 优先与 ADB 回退捕获，捕获路径有独立错误边界。
- `CompilerReport` 导入 Compose 编译器报告，提供静态标志及参数稳定性关联。
- `InspectorProtocolSession` 是已有主机导入协议，不是 MCP 服务端。

## 约束与证据边界
- 归档导入本身不执行 ADB 或设备注入，不能替代实时检查授权。
- Agent 不可用与认证、转发或帧校验失败不同，硬错误不能静默改为成功回退。
- 节点身份限定于来源和窗口，节点 ID 不能证明跨帧对应。
- 静态编译器报告不能生成测量得到的重组、耗时或卡顿证据。
- 生产者声明的完整性、隐私状态不能自动升级为主机已验证的结论。
- 层级有结构与分页上限；布局归档和运行时属性仍可能包含敏感信息。
- 本页仅核对源码，不声明 MCP、设备检查或完整 Compose 能力验收通过。

## 代码依据
- [布局归档与领域 API](../../../rust-desktop/crates/aps-layout-inspector/src/lib.rs)
- [Agent 优先与 ADB 回退](../../../rust-desktop/crates/aps-layout-inspector/src/compatible.rs)
- [编译器报告解析](../../../rust-desktop/crates/aps-layout-inspector/src/compiler_report.rs)
- [主机导入协议](../../../rust-desktop/crates/aps-layout-inspector/src/protocol.rs)
- [当前 MCP 工具注册](../../../rust-desktop/crates/aps-memory/src/mcp/protocol.rs)
