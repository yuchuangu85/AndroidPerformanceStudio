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

## ART startup attribution

Startup Perfetto evidence 将 GC、JIT/dex2oat、class loading、class verification/initialization 分别保留，并按 milestone phase 统计重叠时长；JSON round-trip 保留原始 slice，不把多个 ART 通道合并为单一耗时。

## Rust 桌面端：启动 + CPU 热点

在标题栏选择在线设备及应用进程，进入 **启动分析 → CPU 热点：开 → 检查实验配置**。确认后的每轮测量分别启动一份最长 12 秒的 Perfetto 录制：收到数据源就绪回执并核对本轮会话后，才拉起目标 Activity。冷启动按包名限定调用栈采样，不绑定会被强停的旧 PID。关闭开关时仍只采集原有启动指标。此录制要求 Android 11+ 及可用的系统 Perfetto；设备或应用的采样权限还会影响能否得到调用栈。

选择一轮结果查看**启动到首帧（TTID）的主线程 CPU 热点**。窗口来自本轮 Trace 中唯一匹配的启动和首帧，且进程须与启动后验证的目标 PID 一致；不以 Activity Manager 的 TotalTime 或主机时钟补齐。面板分别显示：

- 实际观测的运行、就绪等待 CPU 和其他线程状态时间，以及调度记录覆盖了多少窗口。未覆盖时间不等于阻塞，“其他”不证明在等锁或 I/O。
- 采到的前 12 个叶符号和样本数，占比分母包含未解析样本。若整份 Trace 有内核采样记录丢失，会提示统计可能不完整；不能确定哪些线程受影响。
- 未匹配首帧、不支持符号解析或没有样本时的限制。冷启动新进程的早期名称与包名可能不同，即使线程确实使用过 CPU，也可能没有目标样本。样本数既不是方法调用次数，也不是某次调用的耗时。

**AI 分析**使用所选轮次的启动指标、调度摘要及有界的热点计数/符号；不会发送原始 Trace、ADB 日志或本地路径。实时热点随当前采集会话保留，现有 CSV/Compose JSON 启动报告不包含可重新分析的 CPU 轨迹。优化前后请在相同条件下复测，并用关闭 CPU 采集的轮次核对启动指标。
