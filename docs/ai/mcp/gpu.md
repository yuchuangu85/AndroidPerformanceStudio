# GPU Inspector MCP

[总览](README.md) | [Memory](memory.md)

## 当前状态
- 当前已实现 MCP 接口：**0**，GPU 模块没有注册 MCP 工具。
- 当前 `aps-mcp` 服务端只注册 `memory_*` 工具，不提供 GPU 工件访问。
- 统一 `aps-mcp` provider/namespace 是架构方向，不代表 GPU 已完成模块注册。
- 本地 AGI 工具链适配与 GPU 分析 API 不构成 MCP 支持。

## 使用方式与 MCP 输入输出
- 目前没有可调用的 GPU MCP 接口，也没有本模块的 MCP 启动模式。
- MCP 输入：**未暴露**；工件路径、设备上下文和查询页不是 MCP 参数。
- MCP 输出：**未暴露**；索引、可用性状态与 GPU 分析摘要不是 MCP 工具结果。
- 不存在通过本模块 MCP 启动 AGI、采集 GPU 数据或读取图形报告的用法。

## 现有领域能力（非 MCP）
- `GpuArtifact::import` 对显式选定的常规文件分类、计算 SHA-256 并记录元数据。
- `GpuArtifactIndex` 按摘要去重，允许同一工件关联多个有界本地位置。
- `resolve_location` 区分可用、缺失、大小变化、内容变化与不可读状态。
- `relocate` 只接受大小和摘要匹配的用户选定副本。
- 工件可记录设备、包、图形 API 和采集上下文；文件修改时间不等于采集时间。
- GPU 分析接收已验证的 Perfetto 查询页，解码频率、内存与渲染阶段数据。

## 约束与证据边界
- 工件分类与打开路由不证明解析器有效、AGI 可用、GPU 兼容或已获执行权限。
- Perfetto 查询页分析不解析 AGI 二进制，也不接受任意 SQL。
- 单个工件上限为 8 GiB；索引最多 10,000 项，每项最多 64 个位置。
- 哈希扫描为阻塞操作，应离开 UI 线程；取消不保证在哈希过程中即时响应。
- 绝对路径仅用于本地定位，不能据此认定允许将路径或工件上传给 AI。
- GPU 频率、内存和渲染阶段统计不自动构成性能瓶颈的因果证明。
- 本页仅核对源码，不声明 MCP、AGI 启动或真实 GPU 采集验证通过。

## 代码依据
- [GPU 工件与索引 API](../../../rust-desktop/crates/aps-gpu/src/lib.rs)
- [Perfetto GPU 分析投影](../../../rust-desktop/crates/aps-gpu/src/analysis.rs)
- [AGI 工具链适配](../../../rust-desktop/crates/aps-gpu/src/toolchain.rs)
- [当前 MCP 工具注册](../../../rust-desktop/crates/aps-memory/src/bin/aps_mcp.rs)
