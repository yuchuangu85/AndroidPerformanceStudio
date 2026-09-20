# Battery Profiler

## 功能介绍

Battery Profiler 基于 `dumpsys batterystats` 和系统状态实验，分析 wakelock、alarm、network、screen 和电量变化，并生成适合进一步使用 Battery Historian 的原始输入。

## 详细设计方案

```text
experiment config
  -> reset / start / workload / stop
  -> batterystats + device state capture
  -> parser/normalizer
  -> BatteryAnalyzer
  -> baseline comparison / attribution
  -> JSON/CSV/raw evidence
```

### 模块职责

主要实现集中在：

- `battery-profiler/battery-app/src/main/kotlin/.../BatteryExperimentRunner.kt`：实验状态、设备命令和进度。
- `battery-profiler/battery-app/src/main/kotlin/.../BatteryAnalyzer.kt`：电量、wakelock、alarm、network 和基线分析。
- `battery-profiler/battery-app/src/main/kotlin/.../BatteryProfilerMainPage.kt`：实验配置与结果 UI。

## 使用方案

1. 连接并选择 Android 设备。
2. 选择 interactive、timed、repeated 或 online 模式。
3. 配置 workload、时长和基线。
4. 运行实验并等待 batterystats 采集完成。
5. 查看指标、基线差异和原始证据。
6. 导出 JSON/CSV 或 Battery Historian 输入。

## 输出与限制

- batterystats 反映系统统计与归因，不是每条耗电的直接硬件测量。
- 设备厂商、电源 HAL、Android 版本和后台策略会影响可见字段。
- thermal、DVFS、power rail 等更细粒度证据需要额外 Perfetto/设备支持。
