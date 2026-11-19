# Source Workspaces MCP

[总览](README.md) | [Memory](memory.md)

## 当前状态
- 当前已实现 MCP 接口：**0**，Source Workspaces 没有注册 MCP 工具。
- 当前 `aps-mcp` 服务端只注册 `memory_*` 工具，不提供源码工作区访问。
- 统一 `aps-mcp` provider/namespace 是架构方向，不代表本模块已完成注册。
- 源码定位或 AI 源码同意标记不能证明存在 MCP 源码读取能力。

## 使用方式与 MCP 输入输出
- 目前没有可调用的 Source Workspaces MCP 接口，也没有本模块的 MCP 启动模式。
- MCP 输入：**未暴露**；根目录、快照身份和性能证据不是 MCP 参数。
- MCP 输出：**未暴露**；文件内容、索引符号与定位候选不是 MCP 工具结果。
- 注册工作区不是把源码暴露给 MCP，也不是授权上传整个工程。

## 现有领域能力（非 MCP）
- `aps-source` 提供工作区、不可变快照、源码位置和定位候选等本地领域模型。
- 当前 `SourceProviderRegistry::local()` 仅提供本地实现。
- GitHub / AOSP 配置有模型与校验，不代表已实现远程提供方。
- `LocalSourceProvider` 只读遍历受支持的源码类型，跳过构建目录且不跟随符号链接。
- 内容寻址缓存校验内容哈希；结构索引器提取 Kotlin、Java、XML 与 C/C++ 声明。
- `IndexedSourceResolver` 通过仓库索引生成候选，不接受 AI 凭空创建的路径和行号。
- 桌面 service 负责本地注册、刷新、SQLite 元数据和缓存，不是 MCP 适配层。

## 约束与证据边界
- 文件读取要求有界的普通相对路径，限制在根目录内，单文件上限为 5 MiB。
- 结构索引是启发式而非编译器解析，复杂语法与符号预算会影响完整性。
- 未验证构建身份时，原本 Exact 的定位候选会降级为 Probable。
- 本地缓存不是隐式上传来源，注册源码也不意味自动网络访问或凭据发现。
- 快照身份与定位置信度不同于 AI 分析置信度，不能相互替代。
- 本页仅核对源码，不声明 MCP、远程提供方或大型工作区运行验收通过。

## 代码依据
- [源码领域与边界说明](../../../rust-desktop/crates/aps-source/README.md)
- [本地提供方](../../../rust-desktop/crates/aps-source/src/provider.rs)
- [基于索引的定位](../../../rust-desktop/crates/aps-source/src/resolver.rs)
- [桌面工作区 service](../../../rust-desktop/crates/aps-app/src/source/service.rs)
- [当前 MCP 工具注册](../../../rust-desktop/crates/aps-memory/src/bin/aps_mcp.rs)
