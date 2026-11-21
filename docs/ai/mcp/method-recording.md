# Method Recording MCP

[总览](README.md) | [Memory](memory.md)

> **统一入口：** 对已绑定的ART 方法记录，可以用 [`aps_analyze_file`](orchestrator.md) 读取有界证据。下文的“未暴露”仅指模块专用工具；统一入口不做设备采集。

## 当前状态
- 当前已实现 MCP 接口：**0**，Method Recording 没有注册 MCP 工具。
- 当前 `aps-mcp` 服务端只注册 `memory_*` 工具，不提供方法事件访问。
- 统一 `aps-mcp` provider/namespace 是架构方向，不代表本模块已完成注册。
- 方法分析的本地 API 与 AI 入口均不能作为 MCP 支持的依据。

## 使用方式与 MCP 输入输出
- 目前没有可调用的 Method Recording MCP 接口，也没有本模块的 MCP 启动模式。
- MCP 输入：**未暴露**；ART trace、线程、方法与时间窗口不是 MCP 参数。
- MCP 输出：**未暴露**；事件、调用树、时间线与热点不是 MCP 工具结果。
- 本地方法录制文件导入不是通过 MCP 录制设备方法事件的能力。

## 现有领域能力（非 MCP）
- `MethodRecordingSession` 提供离线 ART 方法事件导入、目录查询与时长分析。
- 当前解析器接受二进制首部 `SLOW` v2/v3/v4/v5 的受支持布局。
- `parser/standard.rs` 另支持文本首部的标准 v2/v3，要求文本与二进制版本一致。
- 标准格式显式处理 wall、thread-cpu 与 dual 时钟，保留生产者起始值。
- 线程、方法、事件、热点、调用树与时间线通过本地分页 API 查询。
- ENTER、EXIT 与 UNROLL 分别保留调用、正常完成与异常展开语义。

## 约束与证据边界
- 方法事件不是 CPU 样本；采样计数不能重新解释为真实调用次数。
- 自身时长来自观测到的栈区间，包含时长按调用累加，递归时可能重复覆盖时间。
- 未关闭栈帧只到其所属线程最后事件为止，不能延伸到全局结束时间。
- 不明确的时钟、时钟回退和缺失 CPU 增量必须保留诊断，不合成替代时长。
- 生产者起始值不能证明与 Perfetto 或其他录制共享时间纪元。
- 默认输入预算为 64 MiB、100 万原始记录；本地分页最多返回 512 行。
- 本页仅核对源码，不声明全部 ART 变体、设备采集或 MCP 运行验证通过。

## 代码依据
- [方法会话与时间模型](../../../rust-desktop/crates/aps-method-recording/src/lib.rs)
- [二进制格式解析](../../../rust-desktop/crates/aps-method-recording/src/parser.rs)
- [标准文本首部格式解析](../../../rust-desktop/crates/aps-method-recording/src/parser/standard.rs)
- [调用栈重放与时长分析](../../../rust-desktop/crates/aps-method-recording/src/replay.rs)
- [当前 MCP 工具注册](../../../rust-desktop/crates/aps-memory/src/mcp/protocol.rs)
