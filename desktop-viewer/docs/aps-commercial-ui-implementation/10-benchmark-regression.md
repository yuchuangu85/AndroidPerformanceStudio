# 页面 10：Benchmark Regression

![Benchmark Regression 设计参考](images/10-benchmark-reports.png)

## 15.1 目标

从当前 comparison card 列表升级为：

> Release-oriented performance regression investigation workspace.

## 15.2 布局

```text
Toolbar
Baseline | Current | Policy | Compare | Import | Export

Summary
Compared Metrics | Regressions | Improvements | Gate Status

Regression Table
Case | Metric | Baseline | Current | Delta | % | Confidence | Classification

Trend / Historical Runs

Right Inspector
Compatibility
Reasons
Samples
Trace artifacts
Policy
```

## 15.3 当前已有真实能力

- device compatibility；
- API/ABI/fingerprint；
- build variant；
- metric direction；
- absolute/relative delta；
- minimum sample count；
- MAD noise band；
- regression/improved/stable/inconclusive/incompatible。

## 15.4 新增 Release Gate Policy

不要第一阶段设计任意 Performance Score。

推荐：

```kotlin
enum class GateDecision {
    PASS,
    FAIL,
    INCONCLUSIVE,
}

data class ReleaseGatePolicy(
    val blockingMetricPatterns: List<String>,
    val failOnRegression: Boolean,
    val maxAllowedRegressions: Int,
    val allowInconclusive: Boolean,
    val minimumConfidence: EvidenceConfidence,
)

data class ReleaseGateResult(
    val decision: GateDecision,
    val blockingComparisons: List<MetricComparison>,
    val reasons: List<String>,
    val policyVersion: String,
)
```

Policy 必须版本化，可导出。

## 15.5 Trend

当前 SQLite 已有 run/metric，需扩展查询：

```text
metric history by case + metric + comparable environment
```

Trend 只能比较兼容环境或明确标识环境变化。

## 15.6 当前落地与布局说明（2026-10-20）

- 基线版本与当前版本选择器、开始对比、导入运行、编排运行、导出 PDF/CSV、分享报告、对比设置和打开 Perfetto Trace 全部移入页面顶部 Header。
- 顶部 Header 支持横向滚动；基线版本选择器是该行的第一个控件。
- 报告工作区不再渲染独立的工具条面板，直接从汇总卡片开始。

## 15.7 验收

- Gate 不使用魔法分数；
- incompatibility 优先于 regression verdict；
- low sample confidence 不被显示为高置信回归；
- trend 能追踪 build/commit；
- report export 含 policy version 和 compatibility。

---
