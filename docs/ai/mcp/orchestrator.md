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

客户端配置示例（替换成本机路径；Windows 可执行文件带 `.exe`）：

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
- **`aps_analyze_file`**：仅在启动时成功绑定了可解析的本地文件或宿主下载副本时可发现。参数 `{}` 或 `{"question":"…"}`。返回与 CLI 共享的证据，包括 `input.sourceDigest` 和可引用的 `evidence.items[].id`、`label`、`value`。问题不会改变输入、筛选条件或测量结果。更细的热点、对象或帧分页查询仍用专用 Server。

```jsonl
{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"aps_capabilities","arguments":{}}}
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"aps_analyze_file","arguments":{"question":"仅基于这些观测说明有哪些网络问题"}}}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"aps_run_plan","arguments":{"intent":"macrobenchmark","question":"如何采集下次启动？"}}}
```

工具成功响应同时提供 `result.structuredContent` 和内容等价的 `result.content[0].text`；未知指标的 `null` 不能当作实测零。会话在启动时冻结摘要，取证时复核源文件 SHA-256；变更、消失或不可读后返回 `-32001`，须重新绑定。选择不同文件同样须重启。工具不接收路径、URL、Shell、凭据、设备操作、写报告目的地等参数；未知参数或类型错误返回 `-32602`。可选问题最多 2,048 字符；单次请求不超过 64 KiB，工具结果正文不超过 512 KiB（JSON-RPC 文本回退会增加线缆字节数）。`stdout` 仅用于协议，不混入日志。

验证入口：`cd rust-desktop && cargo test --locked -p aps-cli --test orchestrator_stdio`。这是本地协议与合成工件覆盖，不是所有客户端、真机采集或云端生成报告的验收。
