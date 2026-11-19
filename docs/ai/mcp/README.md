# APS MCP 总览

本目录记录 Android Performance Studio 当前可调用的 MCP 接口。接口名称、参数和结果
以工作区的 Server 注册与处理代码为准；规划、GUI、Rust API 和 AI 证据适配器不等于
已经实现的 MCP 接口。

本文说明当前源码行为，不表示某个安装包已经包含这些能力；构建和发布物还应核对其
实际 `tools/list` 与响应，不能仅凭文档推断发布状态。

## 当前实现

当前只有一个 MCP Server：`aps-mcp`。它通过 stdio 提供离线 Memory HPROF 查询，
启动时必须指定目标 HPROF B 和一个 `aps-memory-worker`，可选指定基线 HPROF A。
目前没有通用模块选择、动态 provider 注册、HTTP/SSE 服务地址或远程监听端口。

以下 5 个工具始终注册在 `tools/list` 中，完整输入输出见 [Memory MCP](memory.md)：

- `memory_snapshot_summary`：HPROF 元数据与快照摘要。
- `memory_leak_findings`：启发式泄漏和生命周期候选。
- `memory_class_histogram`：有界类直方图查询。
- `memory_object_detail`：一个对象的字段、引用和保留信息。
- [memory_snapshot_diff](memory.md#memory_snapshot_diff)：固定 B-A 方向的有界类级差异，
  调用时要求已配置基线 A；模块文档包含外部 Client 配置、请求与完整成功/错误示例。

原有四个工具始终读取 B，即使配置了 A 也不切换来源。未配置 A 时仍可发现第五个
工具，但调用 diff 返回 `-32001`，不影响原有单快照启动和调用方式。

## 业务模块索引

范围对应桌面业务导航；Home、AI 对话窗口和 Settings 不是额外的业务 MCP provider。

- [Memory](memory.md)：已实现 5 个工具，内置 AI 仍只预取原有 3 个。
- [CPU](cpu.md)：尚未注册 MCP 接口。
- [Frame](frame.md)：尚未注册 MCP 接口。
- [Startup](startup.md)：尚未注册 MCP 接口。
- [Battery](battery.md)：尚未注册 MCP 接口。
- [Network](network.md)：尚未注册 MCP 接口。
- [GPU](gpu.md)：尚未注册 MCP 接口。
- [Layout Inspector](layout-inspector.md)：尚未注册 MCP 接口。
- [Capture](capture.md)：尚未注册 MCP 接口。
- [Trace](trace.md)：尚未注册 MCP 接口。
- [Benchmark](benchmark.md)：尚未注册 MCP 接口。
- [Method Recording](method-recording.md)：尚未注册 MCP 接口。
- [Source Workspaces](source-workspaces.md)：尚未注册 MCP 接口。

“尚未注册”表示当前没有该模块的 MCP 使用命令、输入参数或输出契约，不表示该模块
没有本地分析、GUI、采集或 AI 能力。模块文档分别说明这些区别，不提供虚构调用。

## 入口与数据流

- [Server 入口与工具注册](../../../rust-desktop/crates/aps-memory/src/bin/aps_mcp.rs)。
- [HPROF worker 及分页边界](../../../rust-desktop/crates/aps-memory/src/worker.rs)。
- [共享类级比较](../../../rust-desktop/crates/aps-memory/src/comparison.rs)：`aps_memory::compare_snapshots`。
- [AI Host 固定预取与校验](../../../rust-desktop/crates/aps-ai/src/mcp.rs)。
- [Memory 页面创建 MCP context](../../../rust-desktop/crates/aps-app/src/import/memory/ai.rs)。

```text
外部 MCP Client -> aps-mcp -> aps-memory-worker -> 目标 HPROF B / 可选基线 A

APS Memory 页面 -> MCP context -> AI prepare 固定预取
                -> 来源与预算校验 -> 冻结 evidence -> 用户批准 -> AI Provider
```

MCP 查询和 AI Provider 请求是两件事。外部 Client 可以只读取本地工具结果，不请求
模型；内置 AI 在 prepare 阶段读取 MCP，冻结结果后才进入既有 Provider 审批流程。
模型不获得这些工具的自由调用权限，也不获得 MCP 启动命令和宿主文件路径。

## 构建与连接

从仓库根目录进入 Rust 工作区：

```sh
cd rust-desktop
cargo build --locked -p aps-memory --bin aps-memory-worker --bin aps-mcp
```

Debug 产物位于 `rust-desktop/target/debug/`，Windows 文件名带 `.exe`。下面的命令在
`rust-desktop/` 内执行，示例路径需要替换为用户选定的文件：

```sh
./target/debug/aps-mcp \
  --hprof /absolute/path/to/selected.hprof \
  --worker /absolute/path/to/aps-memory-worker
```

Server 直接读取 stdin，每行一个 JSON 消息；stdout 每行一个 JSON 响应。关闭 stdin
结束该 Server 会话。原有单快照命令向后兼容，已知 flag 的排列顺序可变；比较两份
HPROF 时增加 `--baseline-hprof`：

```sh
./target/debug/aps-mcp \
  --hprof /absolute/path/to/after-B.hprof \
  --worker /absolute/path/to/aps-memory-worker \
  --baseline-hprof /absolute/path/to/before-A.hprof
```

一个进程固定绑定 B 和可选的 A，切换任一输入或交换方向需重新启动。工具参数不能
指定任意路径，也没有 mapping CLI 参数；分页调用见
[memory_snapshot_diff](memory.md#memory_snapshot_diff)。

当前处理 `initialize`、`tools/list` 和 `tools/call`；没有 `id` 的通知不产生响应。
客户端先发送 `initialize`、`notifications/initialized`，再发现和调用工具。初始化
返回的 `protocolVersion` 是 `2024-11-05`，不代表已验证任意版本 Client 的兼容性。

外部 Client 的 stdio 配置示意如下，具体配置入口由 Client 决定：

```json
{
  "mcpServers": {
    "aps": {
      "command": "/absolute/path/to/aps-mcp",
      "args": [
        "--hprof", "/absolute/path/to/selected.hprof",
        "--worker", "/absolute/path/to/aps-memory-worker"
      ]
    }
  }
}
```

内置 AI 从当前 APS 可执行文件的同目录寻找 Server 和 worker，不要求用户配置外部
Client。缺少伴随程序时不创建 MCP context；已经创建 context 后查询失败则拒绝本次
prepare，不会静默退回旧 evidence。

## 结果与证据边界

工具成功响应包含 `result.content`、`result.structuredContent` 和 `result.isError=false`。
`content[0].text` 是相同工具 payload 的 JSON 文本，`structuredContent` 用于结构化读取。
空数组、缺失对象的 `null`、未知指标和省略记录必须按各接口语义处理，不能补成实测值。

三个预取工具的 payload 包含完整 `sourceDigest`；对象详情没有该字段，需要绑定同一
Server 进程的 B 快照身份使用。Diff 使用 `beforeSnapshotId` 和 `afterSnapshotId`
分别携带 A/B 的完整 SHA-256，不使用单一 `sourceDigest` 字段。

- Server 只读取明确选定的 HPROF，不提供 Shell、ADB、网络、采集或写文件工具。
- 启动路径属于宿主配置，不是模型工具参数；不向模型附带 HPROF 路径或启动命令。
- 类名、字段值、字符串预览和引用关系可能含敏感数据；结构化、有界不等于脱敏。
- 泄漏候选不等于确认泄漏，retained size 不是泄漏概率，也不能任意跨类相加。
- 对象 ID 仅在各自 HPROF 内有效；没有已实现的跨转储对象身份接口。
- 当前不接受 mapping，已映射快照与 Diff 不进入内置单快照 MCP 预取。
- 外部 diff 复用共享类级比较，按已知 retained 增量绝对值降序、再按类名全局排序后
  分页；每页返回前均校验 A/B 两端来源，缓存页也不跳过。缺失 retained 保持 `null`，
  不证明不同构建可比，不提供跨转储对象身份或确认泄漏结论。

新增外部 diff 不改变内置 AI：普通 HPROF prepare 仍只预取摘要、泄漏候选和类直方图；
Diff AI 仍只使用当前已发布并冻结的比较报告，`mcp: None`，不改为调用此工具。

Server 单个工具 payload 序列化上限为 512 KiB。内置 Host 的 stdout 上限为 768 KiB、
stderr 为 64 KiB、单条 evidence 为 32 KiB、子进程超时为 45 秒。Server 本身没有这个
45 秒总超时，外部 Client 需要自行设置超时和所属进程的取消策略。

Provider 执行取消与 MCP prepare 的生命周期不同；同步 MCP prepare 当前仍依赖独立
超时。详细错误、输入约束和结果省略方式见 [Memory MCP](memory.md)。

## 维护规则

1. 新工具同步更新对应模块的实际名称、使用方式、输入、输出、边界和验证入口。
2. 总览只登记真正出现在 `tools/list` 且已有处理逻辑的接口，提议接口不计入实现数量。
3. 未接入模块明确说明无接口，Rust/GUI/查询 API 不能代替 MCP 注册证据。
4. 后续模块沿统一 `aps-mcp` 的 provider/namespace 方向扩展；通用注册框架尚未实现。
5. 示例只使用合成数据或占位路径，不记录用户 HPROF、凭据和私有业务输出。
6. 保留可复现验证命令，不把某次测试数量、提交或构建当作持续有效的发布状态。

相关入口：[AI 文档](../README.md)、[项目文档](../../README.md)、
[Memory 历史专题](memory-mcp-leak-analysis.md)。
