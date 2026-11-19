# Frame Profiler MCP

[总览](README.md) | [Memory](memory.md)

## 当前状态
- 当前已实现 MCP 接口：**0**，Frame 模块没有注册 MCP 工具。
- 当前 `aps-mcp` 服务端只注册 `memory_*` 工具，不提供帧数据访问。
- 统一 `aps-mcp` provider/namespace 是架构方向，不代表 Frame 已完成模块注册。
- 帧分析模型、设备探针与 AI 入口均不能替代 MCP 注册。

## 使用方式与 MCP 输入输出
- 目前没有可调用的 Frame MCP 接口，也没有本模块的 MCP 启动模式。
- MCP 输入：**未暴露**；gfxinfo 文本、FrameTimeline 行和帧查询不是 MCP 参数。
- MCP 输出：**未暴露**；帧列表、统计摘要与问题帧投影不是 MCP 工具结果。
- 现有数据只能按各自本地 API 或桌面流程使用，不能从 MCP 读取。

## 现有领域能力（非 MCP）
- `FrameSession` 可离线解析 gfxinfo FrameStats，保留窗口、时间戳、列值和解析警告。
- 旧导入 API 支持按窗口、半开设备单调时间范围及截止期限判定分页。
- 帧模型区分预算来源、截止期限未命中和平台 Jank 信号。
- `model`、`gfxinfo`、`analysis` 与 `frame_timeline` 提供独立的领域模型和分析。
- `map_timeline_rows` 把 Android 12+ Expected/Actual FrameTimeline 行映射为 `FrameSample`。
- FrameTimeline 结果同时携带能力状态，不是对所有 Trace 都具有相同覆盖的承诺。

## 约束与证据边界
- gfxinfo 导入上限为 256 MiB、100 万帧；旧分页 API 最多返回 512 行。
- 未知或无效时间戳不能补成零时长，也不能用默认 60 Hz 填充未知预算。
- gfxinfo 的截止期限未命中不等于平台 Jank；没有平台信号时应保持未知。
- FrameTimeline 的 surface token 是平台证据，不能保证每个样本的选择身份唯一。
- FrameTimeline 行映射不接受任意 SQL，也不自动建立 SurfaceFlinger 因果关系。
- 本页仅核对源码，不声明 MCP、设备探针或多来源时钟关联已完成运行验证。

## 代码依据
- [帧会话与导入 API](../../../rust-desktop/crates/aps-frame/src/lib.rs)
- [帧领域模型](../../../rust-desktop/crates/aps-frame/src/model.rs)
- [FrameTimeline 行映射](../../../rust-desktop/crates/aps-frame/src/frame_timeline.rs)
- [帧分析规则](../../../rust-desktop/crates/aps-frame/src/analysis.rs)
- [当前 MCP 工具注册](../../../rust-desktop/crates/aps-memory/src/bin/aps_mcp.rs)
