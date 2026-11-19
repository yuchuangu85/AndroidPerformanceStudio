# MCP 内存泄漏分析

> 历史专题说明。当前总览和模块索引见 [MCP 总览](README.md)，接口名称、使用方式、
> 输入、输出和已知限制以 [Memory MCP 接口](memory.md) 为准。下文保留早期设计、
> 使用记录与当时的验证快照，不作为当前参数编码、错误行为或发布状态的依据。
> 新增的第五个工具及双快照配置见 [memory_snapshot_diff](memory.md#memory_snapshot_diff)，
> 不沿用下文单快照流程来调用快照对比。

## 目标

为 Memory Profiler 的 HPROF 内存泄漏分析提供 MCP 能力，使 AI 可以通过受控的 MCP 工具获取：

- HPROF 快照摘要；
- HPROF 已解析的泄漏候选；
- Activity/Fragment 生命周期候选；
- GC Root 引用链；
- 类级内存直方图；
- 指定对象的字段、引用和 GC Root 证据。

APS 采用以下架构：

```text
Memory HPROF
    |
    v
aps-mcp  <---- stdio / JSON-RPC ----  APS AI Host
    |                                      |
    v                                      v
aps-memory-worker                    MCP evidence
    |                                      |
    v                                      v
aps-hprof-engine                    AI 审批 -> Provider 分析
```

APS 作为 MCP Server，AI Host 负责启动 MCP Server、调用工具、冻结 evidence，并将 evidence 交给现有 AI 审批和 Provider 流程。

## MCP Server

MCP Server 位于：

```text
rust-desktop/crates/aps-memory/src/bin/aps_mcp.rs
```

构建入口：

```sh
cd rust-desktop
cargo build --locked -p aps-memory --bin aps-memory-worker --bin aps-mcp
```

启动格式：

```sh
aps-mcp \
  --hprof /absolute/path/to/selected.hprof \
  --worker /absolute/path/to/aps-memory-worker
```

Server 使用 stdio 传输 JSON-RPC 消息，当前支持：

- `initialize`
- `tools/list`
- `tools/call`

Server 只接受一个明确选择的 HPROF 和一个明确的 `aps-memory-worker`。打开 HPROF 前会计算并校验 SHA-256，MemorySession 仍然负责进程隔离、worker 通信、取消、超时和响应边界。

## MCP 工具

### `memory_snapshot_summary`

返回当前 HPROF 的有界快照信息：

- HPROF source digest；
- format、ID width、heap 列表；
- class/object/root 数量；
- parser warnings；
- whole-snapshot histogram summary；
- evidence boundary。

### `memory_leak_findings`

返回当前 HPROF 已解析的泄漏证据：

- `leakSuspects`；
- `activityLeaks`；
- `lifecycleLeakObjects`；
- 代表性对象的 retained size；
- 有界 GC Root/reference chain；
- 每种 findings 的 total 和当前返回 rows。

该工具返回的是启发式证据，不代表已经确认存在泄漏。省略的 rows、缺失的引用链和未知指标仍然保持 unknown。

### `memory_class_histogram`

支持以下有界参数：

- `heapName`：可选 heap 过滤；
- `search`：可选类名搜索，最大 1024 bytes；
- `limit`：最大 100 rows。

返回类名、实例数、浅层大小、代表性 retained size 和 heap 归属。不会把 overlapping retained size 相加，也不会推导实时分配/释放数据。

### `memory_object_detail`

参数：

- `objectId`：十六进制对象 ID，例如 `0x1234`；
- `offset`：数组/分页偏移，最大 1000；
- `limit`：最大 100 rows。

返回有界字段、入引用、GC Root 相关信息和截断标记。对象 ID 只在当前 HPROF 内有效，不用于跨 dump 身份推断。

## 内置 AI Host 流程

内置 AI 的 MCP Host 位于：

```text
rust-desktop/crates/aps-ai/src/mcp.rs
```

Memory HPROF 请求会携带一个仅由 APS 生成的 MCP context，包含：

- 应用自有 `aps-mcp` 可执行文件；
- 应用自有 `aps-memory-worker` 可执行文件；
- 当前选中的 HPROF；
- 当前 HPROF source digest。

在 AI prepare 阶段，Host 会依次调用：

1. `initialize`；
2. `tools/list`；
3. `memory_snapshot_summary`；
4. `memory_leak_findings`；
5. `memory_class_histogram`。

返回的数据会被转换成：

```text
mcp-memory-memory_snapshot_summary-<digest-prefix>
mcp-memory-memory_leak_findings-<digest-prefix>
mcp-memory-memory_class_histogram-<digest-prefix>
```

这些 evidence 会加入当前 `AnalysisRequest`，并在现有 AI 审批卡片中展示。Provider 只能看到已经冻结的 evidence，不会获得 MCP Server 命令、H​​PROF 路径或任意文件访问能力。

当前实现采用 **Host 在 prepare 阶段确定性调用 MCP 工具并冻结 evidence** 的方式，而不是允许 Provider 自由发起任意动态工具调用。这样可以复用现有的：

- 一次性审批；
- payload hash；
- evidence 引用校验；
- source digest 绑定；
- generation/session 失效；
- cancel/timeout；
- fail-closed。

## 安全边界

MCP Server 和 AI Host 遵循以下边界：

- 不上传原始 HPROF；
- 不返回 HPROF 本地路径；
- 不允许任意文件读取；
- 不允许 shell、ADB、网络或写文件操作；
- 只执行 APS 自有 MCP Server 和 worker；
- source digest 变化后拒绝继续使用旧上下文；
- MCP 输出限制为 512 KiB；
- MCP Host stdout 限制为 768 KiB；
- MCP 子进程超时为 45 秒；
- HPROF 查询继续使用 `aps-memory` 的分页和 worker 限制；
- MCP 查询失败时不会回退到未经绑定的旧 evidence；
- 未加载 HPROF 时不生成 Memory MCP context；
- Native Heap、ART sampled 和 Bitmap 数据不会伪造成 Java HPROF 泄漏证据。

## AI 提示分类

AI 提示弹窗现在按分类显示：

1. **内存泄漏**
   - HPROF 泄漏原因定位；
   - 泄漏证据可信度；
   - Activity/Fragment 生命周期风险；
   - LeakCanary/Shark 结果分析。
2. **内存诊断**
   - 两个 HPROF 快照的类级增长；
   - Native Heap 采样；
   - 证据边界总结。
3. **源码辅助分析**
4. **通用分析**

点击提示只会填充 AI 输入框，不会自动发送；用户可以修改问题后再进入现有审批和会话流程。

## 使用方式

### 使用 APS 内置 AI

1. 构建 `aps-memory-worker` 和 `aps-mcp`。
2. 启动 APS Rust 桌面端。
3. 在 Memory 页面导入 HPROF。
4. 等待 HPROF snapshot 完成。
5. 打开 Memory 的 Leak Analysis 或点击 AI Analysis。
6. 在 AI 提示弹窗中选择“内存泄漏”提示，或直接输入问题。
7. 点击发送。
8. 检查 MCP evidence、数量、摘要和 payload hash。
9. 点击确认后，AI 才会执行泄漏分析。

示例问题：

```text
请结合 MCP 返回的 HPROF 泄漏候选、GC Root 引用链和类直方图，
按风险排序说明最可能的内存泄漏原因，并严格区分直接证据、推断和仍需验证的部分。
```

### 外部 MCP Client

外部 MCP Client 可以直接启动：

```json
{
  "mcpServers": {
    "aps-memory": {
      "command": "/absolute/path/to/aps-mcp",
      "args": [
        "--hprof",
        "/absolute/path/to/selected.hprof",
        "--worker",
        "/absolute/path/to/aps-memory-worker"
      ]
    }
  }
}
```

外部 Client 必须使用用户明确授权的 HPROF 和 APS 构建出的 worker/server，不应把任意路径、任意命令或任意 MCP Server 传给 APS 内置 AI。

## 证据和结论边界

MCP 只能让 AI 获取更多结构化内存证据，不能把启发式结果变成已证实泄漏：

- candidate 不是 confirmed leak；
- retained size 是代表性对象值，不是可直接累加的类总量；
- 相同对象 ID 不表示两个 HPROF 之间存在对象共享；
- HPROF 不提供实时 GC 时间线或完整 allocation/deallocation 轨迹；
- 代码片段不能证明运行时一定采用了该构建版本；
- 源码缺少 cleanup 代码不能由截断片段推出；
- Native Heap evidence 与 Java retained size 保持独立；
- LeakCanary/Shark 结论必须来自独立 artifact，不由 HPROF candidate 伪造。

## 验证状态

已通过：

- `aps-ai`：106 个单元测试；
- `aps-ai` contract：16 个测试；
- `aps-ai` runtime：4 个测试；
- `aps-mcp`：2 个协议和边界测试；
- `aps-memory-worker` / `aps-mcp` 构建；
- `aps-app` 编译检查；
- `git diff --check`。

尚需单独验收：

- 使用真实 HPROF 执行完整 MCP tools/call 链路；
- 外部 Claude/Codex MCP Client 真实连接；
- Provider 网络请求；
- 原生 GUI 交互、打包和发布版本；
- 多平台 MCP 可执行文件分发；
- 大型 HPROF 的 MCP 首次 prepare 延迟和峰值内存。

当前实现提交：

```text
17bd97a2 feat(memory): add MCP-backed leak analysis
```

## 如何使用 MCP

### 1. 构建 MCP Server 和 Memory Worker

在仓库根目录执行：

```sh
cd rust-desktop
cargo build --locked -p aps-memory --bin aps-memory-worker --bin aps-mcp
```

Debug 构建完成后，默认可执行文件位于：

```text
target/debug/aps-memory-worker
target/debug/aps-mcp
```

APS 内置 AI 会从当前 APS 可执行文件所在目录查找这两个文件。因此本地运行时应确保：

```text
target/debug/aps-app
target/debug/aps-memory-worker
target/debug/aps-mcp
```

位于同一个 `target/debug` 目录中。

### 2. 启动 APS

```sh
cd rust-desktop
cargo run --locked -p aps-app
```

如果只构建了 `aps-app`，没有构建 `aps-mcp`，Memory AI 仍可以使用原有 HPROF evidence，但不会附加 MCP evidence。要启用 MCP，必须先构建两个 Memory binary。

### 3. 在 APS 内置 AI 中使用

1. 打开 Memory 页面。
2. 点击工具栏的 **导入 HPROF**。
3. 选择一个 HPROF 文件并等待 snapshot 加载完成。
4. 打开 **Leak Analysis** 或点击 Memory 页面的 **AI Analysis**。
5. 在 AI 提示弹窗中选择 **内存泄漏** 分类下的提示。
6. 修改输入框中的问题。
7. 点击发送。
8. AI prepare 阶段会自动启动 `aps-mcp`，依次获取：
   - snapshot summary；
   - leak findings；
   - class histogram。
9. 在审批卡片中检查 MCP evidence 数量、摘要和 payload hash。
10. 点击确认后才会向 Provider 发送分析请求。

用户不需要在 APS 内置 AI 页面手动填写 MCP 地址或启动 MCP 进程。APS 会为当前 HPROF 创建绑定当前 source digest 的 MCP context。

推荐问题：

```text
请基于 MCP 获取的 HPROF 快照摘要、泄漏候选、GC Root 引用链和类直方图，
分析最可能的内存泄漏原因。请按风险排序，并严格区分直接证据、推断和仍需验证的部分。
```

### 4. 手动启动 MCP Server

MCP Server 的命令格式：

```sh
./target/debug/aps-mcp \
  --hprof /absolute/path/to/selected.hprof \
  --worker /absolute/path/to/aps-memory-worker
```

它使用 stdin/stdout 通信，不提供 HTTP 端口，也不应通过 Shell 拼接用户输入。

可以用下面的命令发送初始化和工具目录请求：

```sh
printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"manual-client","version":"0.1.0"}}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
  | ./target/debug/aps-mcp \
      --hprof /absolute/path/to/selected.hprof \
      --worker /absolute/path/to/aps-memory-worker
```

工具调用示例：

```sh
printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"memory_leak_findings","arguments":{}}}' \
  | ./target/debug/aps-mcp \
      --hprof /absolute/path/to/selected.hprof \
      --worker /absolute/path/to/aps-memory-worker
```

实际 MCP Client 应先执行 `initialize`，再发送 `notifications/initialized`，随后调用 `tools/list` 和 `tools/call`。

### 5. 外部 MCP Client 配置

外部 MCP Client 需要将 `aps-mcp` 配置为 stdio Server。例如：

```json
{
  "mcpServers": {
    "aps-memory": {
      "command": "/absolute/path/to/target/debug/aps-mcp",
      "args": [
        "--hprof",
        "/absolute/path/to/selected.hprof",
        "--worker",
        "/absolute/path/to/target/debug/aps-memory-worker"
      ]
    }
  }
}
```

每次切换 HPROF 时，应重新启动 MCP Server 并替换 `--hprof` 参数。不要让一个 MCP Server 跨多个 HPROF 复用 session。

### 6. 如何判断 MCP 是否生效

在 AI 审批卡片中应看到新增的 evidence：

```text
mcp-memory-memory_snapshot_summary-<digest-prefix>
mcp-memory-memory_leak_findings-<digest-prefix>
mcp-memory-memory_class_histogram-<digest-prefix>
```

如果审批卡片只有普通的：

```text
memory-hprof-leak-findings
memory-leak-suspect
memory-activity-candidate
memory-lifecycle-candidate
```

而没有 `mcp-memory-*` evidence，通常表示：

- `aps-mcp` 没有构建；
- `aps-memory-worker` 不在 APS 可执行文件同目录；
- HPROF 文件无法读取或 source digest 校验失败；
- MCP Server 启动超时；
- MCP 响应超过有界输出限制。

### 7. MCP 不可用时的行为

MCP Server 不可用时，基础 HPROF-only AI 分析仍然可以使用已解析的 Memory evidence；MCP 不会伪造结果，也不会使用其它 HPROF 或旧 session 的数据。

要获得 MCP evidence，应重新构建并确保以下三个文件可用：

```text
target/debug/aps-app
target/debug/aps-memory-worker
target/debug/aps-mcp
```

完成修复后重新导入 HPROF，并重新打开一次 Memory AI 分析请求。旧的审批请求不会自动重新绑定新的 MCP session。
