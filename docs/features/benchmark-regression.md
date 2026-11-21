# Benchmark Regression

## 功能定位

Benchmark Regression 对 AndroidX Benchmark JSON 的 baseline/current 运行进行身份对齐、样本比较和统计门禁。它把“测量变化”和“是否足够确定地判为回归”分开，避免用两个 summary median 直接下结论。

当前状态：

- **已接入**：Benchmark JSON 导入、case/metric/sample 模型、baseline/current 对齐、兼容性检查、统计分类、趋势/报告/导出和 CLI/MCP 只读分析。
- **条件能力**：真实 Macrobenchmark 编排需要目标 Android 工程提供测试、Gradle task、设备和可比较环境。
- **未完成验收**：真实设备编排、完整报告交互、失败恢复和发布级 CI 门禁仍需单独验证。

## 功能点

- 解析 benchmarks/results/tests、`className#testName` identity、metric、raw samples、median/min/max、device/API/ABI/build 信息。
- 保留场景、编译模式、启动模式、Baseline Profile 状态、trace artifact 和实验身份。
- baseline/current 按稳定 case identity 和 metric name 对齐。
- 比较前检查设备、API、ABI、指纹、构建变体、场景、编译/启动模式、单位和指标方向。
- raw sample 默认要求 baseline/current 各至少 5 个；默认相对实际阈值约 5%、95% confidence、约 2,000 次 bootstrap（具体策略以报告字段为准）。
- 支持 BCa median difference 区间、`REGRESSED`、`IMPROVED`、`STABLE`、`INCONCLUSIVE`、`INCOMPATIBLE`、`INSUFFICIENT`。
- ReleaseGatePolicy 按不兼容证据、阻断回归、不确定证据、通过顺序给出 PASS/FAIL/INCONCLUSIVE。
- 输出 JSON、CSV、Markdown、JUnit、SARIF、PDF 等适配器；CLI 退出码区分通过、回归、输入错误和数据不足。

## 原理与数据流

```text
AndroidX Benchmark JSON / Macrobenchmark result
  -> bounded parser + benchmark identity
  -> comparable cohort validation
  -> raw sample statistics / bootstrap interval
  -> regression classification + release gate
  -> GUI trend/table/report, CLI/MCP and exports
```

当前 Rust 入口：

- `rust-desktop/crates/aps-app/src/benchmark`：页面、表格、趋势、对话框和报告接线。
- `rust-desktop/crates/aps-benchmark`：解析、模型、比较、策略和导出。
- `rust-desktop/crates/aps-cli`：文件分析、报告和 capture 编排入口。

Macrobenchmark 编排只构建并执行目标 Gradle task，传递 compilation mode、startup mode、scenario、warmup、iteration、trace dir 等参数；APS 不替代目标工程的 AndroidX benchmark test。

## 使用方法

### 导入比较

1. 导入 baseline JSON 和 current JSON。
2. 检查两组设备、构建、场景、编译模式、启动模式、单位和样本数。
3. 选择 case/metric 和比较策略。
4. 查看 raw samples、median、变化百分比、区间和分类原因。
5. 只有明确 PASS/FAIL 时才作为门禁；INCONCLUSIVE/INCOMPATIBLE 必须处理证据问题。
6. 导出报告或读取 CLI/JUnit/SARIF 结果。

### Macrobenchmark

目标工程需提供可运行的 Macrobenchmark 测试和设备。编排完成后把生成 JSON/trace 导入同一比较流程；Profile 验证状态和性能回归结论分别报告。

## 证据边界与限制

- summary median 不能替代 raw sample；样本少、单位不匹配、metric 方向未知或 cohort 不兼容时不猜测。
- benchmark 回归分类是统计规则结果，不自动证明代码因果。
- baseline/current 必须记录设备、版本、编译模式、场景、环境和 artifact 身份。
- trace 路径存在不等于 trace 已成功采集或内容足够；需检查报告状态和源文件。
- 单次运行不能代表稳定回归；应保留完整样本和实验上下文。

## 故障排查

- Case 对不上：检查 class/test identity、参数化 benchmark、metric name 和单位。
- INCOMPATIBLE：核对设备、API、ABI、build fingerprint、场景和编译模式。
- INCONCLUSIVE：增加 raw samples、检查异常值/丢样/阈值和 confidence interval。
- CLI 返回 3：通常是数据不足或不可比较，不应当改写成 PASS。
- Macrobenchmark 失败：区分目标 Gradle/设备测试失败、APS 编排错误和后续 JSON 解析错误。
