# aps-benchmark

Benchmark Regression 工作区的离线 AndroidX Benchmark 导入、Compose 一致性回归报告
和失败即关闭的发布门禁。

## 导入

`BenchmarkRun::open(path, max_bytes)` 和 `BenchmarkRun::parse_json(bytes)` 移植了
Compose 的 `BenchmarkJsonParser`：根级 `benchmarks`/`results`/`tests`，按最后一个
`.` 拆分用例名称，`context`/`device` 元数据，根级或 context 的 `build`，
`metrics`/`measurements` 对象包含原始 `runs`/`values`/`samples`/`measurements`（数值或
`value`/`measurement`/`ns` 对象），Compose 单位和方向推断，trace/profile 场景
证据，跳过用例警告以及未知顶层警告。运行 ID 是 JSON 字节的 SHA-256
（`aps_contracts::SourceDigest`）；`imported_at` 是固定宽度的 RFC3339 UTC 字符串，
因此历史记录可以稳定排序。

失败即关闭的边界：常规文件以外的文件或缺失文件、超过 `MAX_IMPORT_BYTES`
（64 MiB）的输入、超过 10 000 个用例、重复的 `class#test` 身份，或不包含有效用例
的文档都会被拒绝；单个无效用例会像 Compose 解析器一样跳过并发出警告。

## 比较和发布门禁

`compare` 生成 Compose 形态的 `RegressionReport`：兼容性问题（设备型号/API/ABI、
指纹软匹配、构建变体、缺失队列身份、每个用例的场景/编译/启动）以及当前运行中
每个指标对应的一个 `MetricComparison`，其中包括基线/当前代表值、差异、原因和
置信度。分类证据仍遵循 Rust 分析器契约：针对中位数差异的确定性双样本 BCa 区间
以及明确的实际阈值；缺失/未知方向、原始样本过少、指标缺失、单位不匹配或队列
不兼容时，结果是无法确定或不兼容，而绝不会伪造分数。

`evaluate_gate` 和 `ReleaseGatePolicy` 移植了 Compose 的 `ReleaseGateEvaluator`
（决策顺序为不兼容证据 -> 阻断性回归 -> 无法确定 -> 通过，包含版本化策略、指标
模式阻断和最低置信度等级）。`compare_baseline_profile` 移植了 Baseline Profile
实验边界，并提供聚合的 profile 状态和 trace 对。

## 适配器

- `storage`：使用 Compose 模式的 rusqlite 存储，支持事务性保存、按内容寻址的重新
  导入替换、最近运行、趋势指标键和按时间顺序排列的指标历史。
- `export`：JSON（模式 1 报告视图 / 模式 2 发布导出）、CSV、Markdown、JUnit、
  SARIF 2.1.0，以及无依赖的 PDF 1.4 写入器（Helvetica、A4、每页 60 行，非 ASCII
  字符替换为 `?`）。Baseline Profile 对比另有包含状态和 trace 对的模式 2 JSON。
- `report_index`：导出工件的有界 JSON 目录；绝不删除用户文件。
- `orchestrate`：精确的 Macrobenchmark Gradle 命令/环境构建器，带合并输出、取消
  和超时的阻塞运行器，以及有界的 benchmark 结果定位器。

## 命令行

使用 `cargo run -p aps-benchmark --bin aps-benchmark -- --baseline BASE.json --current CURRENT.json`
比较结果，可选 `--threshold-percent`、`--json`、`--junit`、`--sarif`。
`macro-plan --project DIR` 预览命令；`macro-run --project DIR` 执行，支持
`--result`、`--baseline`、`--report-json` 及编排选项。比较退出码为 0（有结论且无回归）、
1（存在回归）、2（运行错误）、3（数据不足或不兼容）；执行超时为 124。
与 Compose CLI 不同，Rust CLI 不会将无法确定的结果当作成功。两端的统计判定
方法也不同，同一输入的分类可能不同；此命令不是 Compose 判定结果的逐位复现。

## 验证

`cargo test -p aps-benchmark` 会运行移植后的解析器、分析器、门禁、实验、存储、
导出器/PDF/索引和编排/定位器测试套件，以及分析器辅助函数和格式化的小型单元测试。
这些测试套件覆盖新增行为；真实设备上的 Macrobenchmark 执行和发布 CI 集成仍是
独立门禁。
