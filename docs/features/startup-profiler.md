# Startup Profiler

## 功能介绍

Startup Profiler 用于分析冷启动、温启动和可重复启动实验，拆分进程启动、Application、Activity、首帧、首次绘制和 fully drawn 等关键阶段，并支持 Baseline Profile 相关结果导入/导出。

## 详细设计方案

```text
设备/包名/启动模式
  -> am start / force-stop / warm reset
  -> platform startup events + log/Perfetto evidence
  -> parser-startup
  -> StartupAnalyzer
  -> phase breakdown / stability / comparison
  -> JSON/CSV/report
```

### 模块职责

- `startup-profiler/capture-startup`：实验编排、重复运行、socket Agent connection。
- `startup-profiler/startup-agent-protocol`：设备端启动事件、cursor、result 和 session descriptor。
- `startup-profiler/parser-startup`：`am start`、事件日志和启动输出解析。
- `startup-profiler/startup-model`：milestone、phase、confidence、compilation mode。
- `startup-profiler/analysis-startup`：阶段分解、稳定性和基线对比。
- `startup-profiler/startup-app` / `presentation`：实验配置、结果和导出。

## 使用方案

1. 选择设备、包名、启动模式和重复次数。
2. 配置 cold/warm/hot、编译模式和 Baseline Profile 输入。
3. 运行实验并等待所有 iteration 完成。
4. 查看 p50/p90、阶段分布、波动和异常 iteration。
5. 导出结果，与另一组基线进行比较。

## 证据边界

- 首帧、fully drawn 和用户可交互是不同 milestone，不应混为一个数字。
- 单次启动不能作为稳定性结论；重复实验需保留每轮原始事件和丢失状态。
- Baseline Profile 只说明编译配置差异与观测指标关联，不单独证明某项代码修改的因果。

## 启动阶段跨域归因

Startup Perfetto 证据包含调度、`sched_waking`、run queue、Binder、主线程阻塞和帧区间。存在可接受的 Perfetto 到 `elapsed_realtime` 时钟映射时，这些区间会按相邻启动 milestone 窗口汇总。结果保留有界重叠、时钟误差和缺失证据限制，不把时间相关直接表述为代码级因果。
