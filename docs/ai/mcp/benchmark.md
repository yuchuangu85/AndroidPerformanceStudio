# Benchmark Regression MCP

[总览](README.md) · [统一编排 MCP](orchestrator.md) · [基准回归 CLI](../cli/benchmark.md)

在宿主的 stdio 启动配置里显式绑定两份已有的 AndroidX Benchmark JSON。无需新增 Server：

```sh
aps mcp --input /absolute/path/to/current.json \
  --baseline /absolute/path/to/baseline.json
```

同样可用 `aps-orchestrator-mcp`，参数不变。可在**启动配置**中加 `--threshold-percent 8`（默认 5）；工具不能修改比较策略。配对模式只接受本地普通文件，不与 `--artifact-url` 一起使用。两份文件内容完全相同时直接拒绝，不把同一工件当作独立重复实验。

两份报告均成功解析并完成有界比较后，`tools/list` 才注册 **`aps_benchmark_compare`**。只绑定一个报告时，`aps_analyze_file` 仍只返回单文件摘要。配对后，该摘要工具仍只读当前报告。可向外部客户端提问：「按已绑定的基线判断当前版本是否回归；解释全量门槛，再引用相关指标证据。」

```json
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"aps_benchmark_compare","arguments":{"offset":0,"limit":8}}}
```

参数仅有 `offset`（默认 0）和 `limit`（默认 8，范围 1–16）；不能传路径、URL、设备、Gradle 参数或模型指令。响应的 `structuredContent` 与同文件、同阈值、同分页的 CLI JSON 一致：

- `baselineDigest`、`currentDigest` 是两份实际输入的 SHA-256；引用时同时保留两个摘要和 `items[].id`。
- `policy` 显示算法版本、相对阈值、样本数下限和区间配置；`gate` 与 `counts` 覆盖**整组比较**，不是当前页。门槛为 `PASS | FAIL | INCONCLUSIVE`；不兼容或证据不足不是通过。
- 指标行含中位数或报告代表值、样本数、单位、可比情况下的差值及分类；不返回原始样本、报告路径、设备标识或原始日志。只有满足领域规则的行才有可用 BCa 区间。不兼容行的差值为 `null`。
- 使用 `nextOffset` 继续读，`null` 表示结束。每次取证会同时复核两个文件；检测到更改、消失或替换后本进程不再返回对比结果（`-32001`，恢复旧字节也不重开）。非法参数或越界页码返回 `-32602`；正文上限 32 KiB。

比较使用已有 Rust Benchmark 分析器，不运行设备实验，不写报告，也不调用模型。详见 [CLI 预算与门槛](../cli/benchmark.md)及[领域比较规则](../../../rust-desktop/crates/aps-benchmark/src/compare.rs)。

验证：`cd rust-desktop && cargo test --locked -p aps-cli --test benchmark_compare`。覆盖合成报告的实际 CLI/stdio 进程，不代表真实设备或发布 CI 已执行。
