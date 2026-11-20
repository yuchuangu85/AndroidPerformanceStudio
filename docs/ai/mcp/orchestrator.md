# CLI 编排 MCP：让外部 AI 读取 APS 证据

[全部 MCP](README.md) · [CPU](cpu.md) · [Memory](memory.md) · [Frame](frame.md)

用法分两步：由宿主选择**允许读取的工件**（本地文件或明确绑定的 HTTPS 副本），再由 MCP 客户端解释自然语言。服务端不会根据对话切换文件、运行命令或调用模型。

从仓库根目录构建并启动：

```sh
cd rust-desktop
cargo build --locked -p aps-cli --bins
./target/debug/aps mcp --input /absolute/path/to/capture.har
# 等价的专用入口：./target/debug/aps-orchestrator-mcp --input /absolute/path/to/capture.har
```

按所用客户端任选一条注册命令（替换为本机绝对路径，只绑定愿意交给该客户端分析的文件）：

```sh
codex mcp add aps-analysis -- /absolute/path/to/aps mcp --input /absolute/path/to/capture.har
claude mcp add --scope local --transport stdio aps-analysis -- /absolute/path/to/aps mcp --input /absolute/path/to/capture.har
```

其他支持 stdio 的客户端可使用等价配置（Windows 可执行文件带 `.exe`）：

```json
{
  "mcpServers": {
    "aps-analysis": {
      "command": "/absolute/path/to/aps",
      "args": ["mcp", "--input", "/absolute/path/to/capture.har"]
    }
  }
}
```

支持的解析器名称可在未绑定文件时通过 `aps_capabilities` 的 `supportedAnalyzers` 查看。HPROF 需要同目录的 `aps-memory-worker`（从 Rust 工作区构建：`cargo build --locked -p aps-memory --bin aps-memory-worker`）；缺少解析器运行依赖不会伪造摘要。

Perfetto protobuf 通过 `.pftrace` / `.perfetto-trace` 扩展名，或有界前缀中的 Perfetto 同步标记识别。没有标记的无后缀文件可用 CLI `aps analyze FILE --kind trace`。需由宿主在启动服务前配置 `APS_TRACE_PROCESSOR` 的绝对路径，或将本平台的 `trace_processor_shell` 放在可执行文件旁边。只接受通过固定 SHA-256 和 v59.1 版本检查的工具；不从工具参数、PATH 或网络选择。解析时只向固定引擎传入此次读取字节的私有临时快照，退出正常流程后清理；不会根据原文件内的路径重开其他文件。统一入口返回固定的范围、CPU 调度与帧分组摘要，不提供任意 SQL。

Unix 宿主若要读取原始 `perf.data`，须在启动前显式配置 `APS_SIMPLEPERF=/absolute/path/to/trusted/simpleperf`；不从工具参数或 PATH 选择。这里的 Simpleperf 是宿主信任的程序，不是签名验证的沙箱。转换只处理私有输入副本，最长 180 秒，输出上限 64 MiB；证据区分原始与派生数据的 SHA-256 和样本观测，零样本不是成功采集的证明。没有配置时直接拒绝；不自动解压 gzip 或 CPU 会话包。

连接后可以问：“这份 HAR 包含多少个请求？哪些事实支持你的结论，哪些指标缺失？” 客户端可先查询能力，再调用 `aps_analyze_file`，根据有界证据写出带引用的解释。**是否将证据交给外部模型由客户端的授权策略决定**；结构化摘要不等于匿名数据。MCP 不把用户问题当作可执行指令。

也可以在**宿主启动配置**中将 `--input 本地文件` 替换成 `--artifact-url https://artifacts.example.org/run/capture.har`。宿主在处理第一条协议消息之前获取一次、冻结临时工件；工具只能读取这份副本，不跟踪远程更新，不能请求下载、重定向或切换 URL。含签名的 URL 不应进入共享客户端配置。

## 协议与工具

使用 stdin/stdout 每行一个 JSON-RPC 2.0 消息。先 `initialize` → `notifications/initialized` → `tools/list`；`initialize` 返回协议版本 `2024-11-05`。关闭 stdin 退出。未提供 `--input` 也能启动，此时只暴露前两个工具：

- **`aps_capabilities`**：参数 `{}`。返回本会话是否绑定输入、可用分析类型与只读权限；不联系设备或网络。
- **`aps_run_plan`**：必须给 `intent: "analyze" | "macrobenchmark" | "cpu-capture"`，可加 `question`。返回 `status: "preview"`、`executed: false` 和建议步骤；不是批准、采集或执行回执。
- **`aps_analyze_file`**：仅在启动时成功绑定了可解析的本地文件或宿主下载副本时可发现。参数 `{}` 或 `{"question":"…"}`。返回与 CLI 共享的证据，包括 `input.sourceDigest` 和可引用的 `evidence.items[].id`、`label`、`value`。问题文本不改变输入或测量结果。绑定 Frame 输入时，还可给 `{"scope":"report"}` 或 `{"frameId":"17"}`（可加 `question`，两种选择互斥）；分别等价于 CLI 的 `--scope report` 和 `--frame-id 17`。帧证据中的 `frameId` 为十进制字符串，可原样回传；安全整数范围内也接受数字输入。省略选择器仍返回原聚合摘要；非法或不存在的 ID 不会退回摘要。显式范围的证据含 `analysisPolicyVersion`；报告最多包含 8 行，缺失值不会冒充实测零。更细的热点、对象、帧归因和对比仍用专用 Server。

- **`aps_follow_up`**：只在绑定工件时注册。先从 `aps_analyze_file` 的响应取出 `input.sourceDigest`，每次原样传回。可用的 `query` 以当前 `tools/list` 中的枚举为准。摘要是调用前置条件，不是授权其他文件的句柄。

### 在同一工件内追加取证

外部客户端根据上一步结果决定下一次工具调用，APS 不运行调查循环。可以直接问：

> 找出最慢的三个请求，再看整份记录的等待阶段有没有足够样本。分别引用证据 ID，不要把整份 HAR 的统计归因到某个请求。

1. 调用 `aps_analyze_file({})` 取得摘要与 `input.sourceDigest`。
2. 对同一份 HAR 调用 `aps_follow_up`，参数为 `{"sourceDigest":"<上一步64位摘要>","query":"har_slowest","limit":3}`。只返回耗时已知的前十个慢请求中的一页；`entryIndex` 从 0 开始，`status` 和 `durationMs` 不包含 URL、headers 或 body。`total` 是保留的排名条数，不是全部请求数。
3. 如仍需证据，以同一摘要调用 `{"sourceDigest":"<同上>","query":"har_phases"}`，查看固定七阶段的样本数、缺失数、无效数和毫秒统计。SSL 属于 connect 的一部分，不应重复相加。

Frame 可问：「找出错过截止时间的帧，再看其中一帧的阶段耗时。」先用 `query:"frame_rows", view:"deadline_misses"`（默认视图为 `all`），再把某行 `value.frameId` 的十进制字符串原样传给已有的 `aps_analyze_file({"frameId":"…"})`。错过截止时间不等于平台已判定卡顿。

对所有支持的工件，`query:"evidence_item"` 配合 `evidenceId` 可精确取回**无范围摘要中的一项**，保留原引用 ID；它不会产生额外测量，不能按任意键或 JSON 路径遍历。报告中应同时引用 `sourceDigest` 与 `items[].id`。

**追问边界：**
- 每个服务进程绑定单一工件，最多 **8 次追问**。结果包含 `remainingQueries`；也可通过 `aps_capabilities.followUpRemaining` 查看。查询参数合法且摘要匹配后即扣除一次配额，即使随后页越界、证据 ID 不存在或结果超限。错误摘要、未知操作或非法参数不会消耗次数。配额不改变已有工具的行为。
- 分页默认 8 条，每页 1–16 条。`offset` 按匹配行计，从 0 开始；等于 `total` 返回空末页，超过则报错。`nextOffset:null` 表示该查询无下一页；证据充分或预算用尽时应停止，不应自动重启进程来绕过配额。
- 每次取证复核绑定文件；检测到移除或变更后，本进程不再发布该工件的证据。新工具单次正文不超过 **32 KiB**。非法查询或页码返回 `-32602`，有效格式但不匹配的来源或失效绑定返回 `-32001`，预算用尽或响应超限返回 `-32002`。
- 追问不接收自然语言指令、SQL、文件路径、URL、设备命令或报告路径；只查绑定时冻结的数据，不重新解析、下载或调用模型。没有任务存储或常驻服务，关闭 stdin 即结束。

**其他工具的协议示例：**

```jsonl
{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"aps_capabilities","arguments":{}}}
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"aps_analyze_file","arguments":{"question":"仅基于这些观测说明有哪些网络问题"}}}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"aps_run_plan","arguments":{"intent":"macrobenchmark","question":"如何采集下次启动？"}}}
```

工具成功响应同时提供 `result.structuredContent` 和内容等价的 `result.content[0].text`；未知指标的 `null` 不能当作实测零。会话在启动时冻结摘要，取证时复核源文件 SHA-256；变更、消失或不可读后返回 `-32001`，须重新绑定。选择不同文件同样须重启。工具不接收路径、URL、Shell、凭据、设备操作、写报告目的地等参数；未知参数或类型错误返回 `-32602`。可选问题最多 2,048 字符；单次请求不超过 64 KiB，工具结果正文不超过 512 KiB（JSON-RPC 文本回退会增加线缆字节数）。`stdout` 仅用于协议，不混入日志。

验证入口：`cd rust-desktop && cargo test --locked -p aps-cli --test orchestrator_stdio`。这是本地协议与合成工件覆盖，不是所有客户端、真机采集或云端生成报告的验收。
