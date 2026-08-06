# Benchmark Regression

## 功能作用

Benchmark Regression 是一个 AndroidX Benchmark JSON 结果对比与回归检测工具，核心功能包括：

- **基准数据导入**：导入 AndroidX Benchmark 库生成的 JSON 输出文件
  - Baseline（基线）：作为性能基准的 Benchmark 结果
  - Current（当前）：待对比的最新 Benchmark 结果
- **回归检测**：`RegressionAnalyzer` 对比 Baseline 和 Current 之间的各项指标变化：
  - **绝对差值**（absoluteDelta）
  - **相对变化百分比**（relativeDeltaPercent）
  - **回归分类**：REGRESSED（性能下降）、IMPROVED（性能提升）、STABLE（稳定）、INCONCLUSIVE（不确定）、INCOMPATIBLE（不可比）
- **兼容性检查**：检测 Baseline 和 Current 运行是否可比：
  - 设备信息（model、brand、apiLevel、fingerprint）
  - 构建信息（targetPackage、versionName、versionCode、variant、gitCommit、gitBranch）
  - 不兼容项标记为 `hard`（硬性不兼容）或非 hard（软性差异）
- **可配置的回归策略**：`RegressionPolicy` 支持：
  - `relativeThresholdPercent`：相对变化阈值（如 5%）
  - `absoluteThreshold`：绝对阈值
  - `minimumSampleCount`：最小样本数要求
  - `noiseBandMadMultiplier`：基于 MAD（中位数绝对偏差）的噪声带乘数，排除统计噪声
- **Perfetto Trace 集成**：支持打开 Benchmark 运行中采集的 Perfetto Trace（如果有）
- **报告导出**：支持将回归报告导出为 JSON 格式

## 实现原理

### 解析流程

1. **导入 JSON**：`BenchmarkJsonParser.parse()` 解析 AndroidX Benchmark 的 JSON 输出：
   - 提取设备信息（BenchmarkDevice）：model、brand、apiLevel、osVersion、abi、fingerprint、cpuCoreCount
   - 提取构建信息（BenchmarkBuild）：targetPackage、versionName、versionCode、variant、gitCommit、gitBranch
   - 提取测试 Case 和 Metric：
     - 每个 Case 包含 className、testName、packageName、compilationMode、startupMode
     - 每个 Metric 包含 name、unit、direction（LOWER_IS_BETTER/HIGHER_IS_BETTER）、samples、min/median/max、confidence
2. **持久化**：通过 `SqliteBenchmarkStore` 将解析结果保存到 SQLite

### 对比分析

1. **兼容性检查**：对比设备 fingerprint、构建信息等关键字段，生成 `CompatibilityIssue` 列表
2. **指标匹配**：按 caseIdentity（`className#testName`）+ metricName 匹配 Baseline 和 Current 的对应指标
3. **统计对比**：
   - 取 representative value（median 或中位数）
   - 计算 absoluteDelta = currentValue - baselineValue
   - 计算 relativeDeltaPercent = (delta / baselineValue) * 100
4. **回归判定**：
   - 若 `|relativeDeltaPercent| > thresholdPercent` 且与 Metric 的 direction（期望方向）同向 → REGRESSED
   - 若变化超出噪声带（`noiseBandMadMultiplier * MAD`）→ 有统计显著性
   - 若变化在噪声带内 → STABLE
   - 若样本量不足或置信度不足 → INCONCLUSIVE

### 数据结构

- **BenchmarkRun**：完整的一次 Benchmark 运行，包含 sourceFile、device、build、cases
- **BenchmarkCase**：单个测试 Case，包含 identity（`className#testName`）、metrics、traceArtifacts
- **BenchmarkMetric**：单个指标，包含 name、unit、direction、samples、min/median/max、confidence
- **MetricComparison**：指标对比结果，包含 baselineValue、currentValue、absoluteDelta、relativeDeltaPercent、classification、reasons
- **RegressionReport**：完整的回归报告，包含 comparisons、compatibilityIssues、regressionCount
- **RegressionPolicy**：回归判定策略配置

### 数据流

```
[AndroidX Benchmark JSON] --导入--> [BenchmarkJsonParser] --> [BenchmarkRun]
    --(baseline + current)--> [RegressionAnalyzer] --> [RegressionReport]
    --> [SqliteBenchmarkStore] (持久化)
    --> [Compose UI: BenchmarkRegressionScreen]
    --> [BenchmarkReportExporter] --> [JSON 导出]
```

### Metric Direction

指标的期望方向决定了变化是好是坏：
- **LOWER_IS_BETTER**：值越低越好（如启动时间、帧耗时、内存使用）
- **HIGHER_IS_BETTER**：值越高越好（如帧率、吞吐量）

### 噪声处理

`RegressionPolicy.noiseBandMadMultiplier` 基于 MAD 构建噪声带，防止将正常的测量波动误判为回归：
- 计算 Baseline 样本集的 MAD
- noiseBand = MAD * multiplier（默认 3.0）
- 只有超出 noiseBand 的变化才被视为具有统计显著性的回归

### Output 子模块

- **benchmark-cli**：命令行工具入口（`Main.kt`），可脱离桌面 UI 独立运行回归分析

## 优化建议与改进点

> 以下内容不替换已有设计，仅作为可考虑的更好实现方式或优化点补充。每条标注 **影响**（高/中/低）与 **可行性**（高/中/低）。

### 1. 噪声带仅基于 Baseline 的统计局限【影响:高 / 可行性:高】

**当前实现问题**：`noiseBandMadMultiplier` 仅基于 Baseline 样本集计算 MAD，噪声带 = `MAD_baseline * multiplier`。这忽略了 Current 自身的样本离散度。当 Current 由于设备发热、后台任务等产生更大方差时，本应稳定的指标可能被判为回归；反之 Baseline 方差大时，真实回归可能被噪声带掩盖。

**更好的实现方式**：
- 用 **双侧/双样本噪声带**：`noiseBand = max(MAD_baseline, MAD_current) * multiplier`，或采用 **Welch t-test / Mann-Whitney U** 做双样本显著性检验，输出 p-value 而非仅靠阈值。
- 同时报告 **效应量（Cohen's d 或相对变化）**，让用户区分"统计显著但实际可忽略"与"实际显著"。
- 对样本量 < 30 的指标，MAD 估计本身不稳定，建议降级为 INCONCLUSIVE 或采用 bootstrap 重采样估计置信区间。

### 2. 置信区间缺失【影响:高 / 可行性:中】

**当前实现问题**：`MetricComparison` 只有 `absoluteDelta`/`relativeDeltaPercent`/`classification`/`reasons`，缺少置信区间。用户无法判断"5% 回归"是基于稳定均值还是高度波动的均值。

**更好的实现方式**：
- 在 `MetricComparison` 增加 `baselineConfidenceInterval`、`currentConfidenceInterval`、`deltaConfidenceInterval`（如 95% CI）。
- 当 CI 区间重叠严重时，自动将分类从 REGRESSED/IMPROVED 降级为 INCONCLUSIVE，避免误报。
- 对 AndroidX Benchmark 已提供的 `confidence` 字段（benchmark 库内部基于中位数 CI），直接透传并在 UI 展示。

### 3. INCONCLUSIVE 与 INCOMPATIBLE 的处理路径【影响:中 / 可行性:高】

**当前实现问题**：文档定义了 INCONCLUSIVE / INCOMPATIBLE 分类，但未说明后续动作。INCONCLUSIVE 指标容易被当作"无关"忽略，INCOMPATIBLE 则无法对比。

**更好的实现方式**：
- 对 INCONCLUSIVE，建议自动触发 **重测建议**：提示用户增大 `measuredRuns` 或检查设备状态，并可一键重跑该 Case。
- 对 INCOMPATIBLE（设备/构建不兼容），在报告中保留一个 **"尽力对比"模式**：仅对比兼容的字段，对 hard 不兼容项明确标注"不可信"，而非整份报告作废。
- 记录 `incompatibleReasons` 的可读摘要，便于在 PR/CI 中直接展示。

### 4. 样本量与最小可检测效应（MDE）【影响:中 / 可行性:高】

**当前实现问题**：`minimumSampleCount` 是硬阈值，但未告诉用户"当前样本量能可靠检测多小的回归"。

**更好的实现方式**：
- 根据样本量与 Baseline 方差，预先计算 **最小可检测效应（MDE）** 并展示，例如"当前配置可可靠检测 ≥8% 的回归"。
- 若配置的 `relativeThresholdPercent` < MDE，在 UI/报告中给出警告"阈值低于可检测下限，可能产生漏报"。

### 5. 多设备 / 多变体的聚合对比【影响:中 / 可行性:中】

**当前实现问题**：对比是单 Baseline vs 单 Current 的 1:1 模型。实际工程常需"多设备矩阵"对比（低/中/高端机各跑一遍）或多变体（debug/release/minified）聚合。

**更好的实现方式**：
- 扩展 `RegressionReport` 支持多组对比：`ComparisonMatrix`（device × variant → MetricComparison），并提供"跨设备一致性"判定（如同一指标在多数设备回归才算真回归）。
- 对多变体，支持 **A/B 对照**（如 release vs release，排除 debug 噪声），而非只 baseline vs current。

### 6. 代表值的选择【影响:中 / 可行性:高】

**当前实现问题**：对比时"取 representative value（median 或中位数）"——median 与中位数是同一概念，此处描述冗余；且未说明何时用 median、何时用 mean。

**更好的实现方式**：
- 明确策略：默认用 **median**（对异常值稳健），但对样本量小（<10）时切换为 **trimmed mean（10% 截尾均值）**，并显式记录使用的统计量类型到 `representativeKind`。
- 对 trim 设置（trimRatio）暴露为配置项，便于高级用户调优。

### 7. Perfetto Trace 的关联与下钻【影响:低 / 可行性:中】

**当前实现问题**：支持"打开 Benchmark 运行中采集的 Perfetto Trace（如果有）"，但未说明 trace 与具体 Case/Metric 的关联方式。

**更好的实现方式**：
- 在 `BenchmarkCase` 中显式记录 `traceArtifacts` 与对应 metric 的映射（如 startup 时间对应的 trace slice 名称），打开 trace 时自动定位到相关 slice/track。
- 支持"回归指标 → 对应 trace 切片"一键跳转，便于定位根因。

### 8. 回归判定的方向逻辑澄清【影响:低 / 可行性:高】

**当前实现问题**：文档描述"若 `|relativeDeltaPercent| > thresholdPercent` 且与 Metric 的 direction 同向 → REGRESSED"，但"同向"对 IMPROVED 的处理不清晰（反向且超阈值应是 IMPROVED，而非"不回归即稳定"）。

**更好的实现方式**：
- 显式化判定表：
  - LOWER_IS_BETTER 且 delta > +threshold → REGRESSED；delta < -threshold → IMPROVED。
  - HIGHER_IS_BETTER 且 delta < -threshold → REGRESSED；delta > +threshold → IMPROVED。
  - |delta| ≤ noiseBand → STABLE。
- 把这张表写进文档与代码注释，避免实现时把 IMPROVED 误判为 STABLE。
