# Benchmark Regression

## 功能介绍

Benchmark Regression 用于导入 AndroidX Benchmark JSON，对比 baseline 与 current 结果，按指标和统计规则标记回归，并导出适合 CI 或人工审查的报告。

## 详细设计方案

```text
baseline JSON + current JSON
  -> parser-benchmark-json
  -> benchmark model
  -> RegressionAnalyzer
  -> threshold / direction / sample comparison
  -> report / export / CI result
```

### 模块职责

- `benchmark-regression/parser-benchmark-json`：AndroidX Benchmark JSON 解析。
- `benchmark-model`：benchmark、metric、iteration、baseline 和 regression 状态。
- `analysis-regression`：方向、阈值、样本和回归分类。
- `benchmark-storage-sqlite`：结果和历史基线存储。
- `benchmark-export-adapters`：JSON/CSV/Markdown/CI 输出。
- `benchmark-app` / `presentation`：导入、选择、比较和报告展示。
- `benchmark-cli`：适合自动化和脚本的入口。

## Macrobenchmark 实验台

`benchmark-cli` 还提供可复现的 Macrobenchmark 命令计划和执行入口：

```bash
./gradlew -p benchmark-regression :benchmark-cli:run \
  --args='macro-run --project /path/to/android-project \
  --task :macrobenchmark:connectedCheck \
  --compilation-mode SPEED_PROFILE \
  --startup-mode WARM \
  --warmups 2 --iterations 5 \
  --trace-dir /tmp/macro-traces \
  --result /tmp/benchmark.json'
```

命令通过 `APS_MACROBENCHMARK_*` 环境变量和 Gradle project properties 把编译模式、启动模式、warmup、iteration、trace 输出目录传给目标 Android Benchmark 工程。执行完成后可以直接复用现有 JSON parser 和 RegressionAnalyzer 做 baseline/current 比较。

当前边界：目标工程仍需提供 AndroidX Macrobenchmark 测试和可运行设备；APS 负责实验编排、参数传递、原始输出和结果比较，不替代目标工程中的 benchmark test。

## 使用方案

1. 导入 baseline Benchmark JSON。
2. 导入 current Benchmark JSON。
3. 选择比较范围、指标和回归阈值。
4. 查看相对变化、样本分布和不确定性提示。
5. 导出报告或在 CI 中读取退出状态/结构化结果。

## 证据边界

- 单次 benchmark 结果不能代表稳定回归；应保留样本数和统计上下文。
- baseline 与 current 必须记录设备、编译模式、版本和环境，否则比较可能无效。
- 回归标记是统计规则输出，不自动解释代码因果；需要结合 Startup、Frame、CPU 或 Trace Analyzer。
