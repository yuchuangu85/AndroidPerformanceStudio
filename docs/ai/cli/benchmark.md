# 基准回归 CLI

[CLI 总览](README.md) · [基准回归 MCP](../mcp/benchmark.md)

`aps benchmark compare` 离线比较两份已有的 AndroidX Benchmark JSON。方向固定为**当前减基线**；不会执行 Gradle、连接设备、打开报告中声明的 Trace 或调用模型。

```sh
aps benchmark compare --baseline ./baseline.json --current ./current.json

# 修改实际变化阈值、读取指定页；可另存一份全新的 JSON 文件
aps benchmark compare --baseline ./baseline.json --current ./current.json \
  --threshold-percent 8 --offset 0 --limit 8 --evidence-out ./comparison-page.json
```

- 两份输入都是显式选定的普通本地文件；拒绝 URL、符号链接和完全相同的文件字节。每份最多 64 MiB、512 个指标；整组比较还设有 1000 万次 bootstrap 样本抽取预算，超限明确报错，不截断后判通过。
- 默认采用与 Rust 桌面 Benchmark 相同的 BCa 中位数差分析：95% 区间、2000 次重采样、每组至少 5 个原始样本、相对变化阈值 5%。`--threshold-percent` 接受 0–100。**阈值不是单独的回归判据**：样本、方向、单位或可比性不满足时不能按单个百分比判回归。域分析器限制单个指标的重采样预算，超限指标不会被判通过。
- stdout 输出一页 `aps-benchmark-comparison-v1` JSON，包含 `baselineDigest`、`currentDigest`、`policy`、`gate`、全量计数和具有引用 ID 的 `items`。`--offset` 从 0 开始，`--limit` 默认 8、最多 16；按 `nextOffset` 分页，`null` 即结束。分页不改变整组门槛结果。显式输出路径必须是尚不存在的文件；不会覆盖输入或现有文件。
- 当前缺少基线指标、解析时跳过了用例等覆盖不足不会产生 `PASS`。明确可阻断的回归仍可为 `FAIL`；硬性证据不兼容则遵循领域门槛，返回 `INCONCLUSIVE`。不输出原始样本、任意警告文本或设备标识；不兼容行不输出跨单位或跨队列差值。

退出码：`0` = `PASS`；`1` = `FAIL`；`3` = `INCONCLUSIVE`（CI 不应当作通过）；`2` = 参数、输入、预算、页码或发布错误，无有效门槛结果。门槛判断不代表发布已执行。

单文件仍用 `aps analyze current.json` 读取摘要，不做基线回归。原有的 `aps-benchmark compare` 和它的 JSON/JUnit/SARIF 导出不变；运行新实验是单独的 [Macrobenchmark 场景命令](README.md#自动执行已有设备场景)。
