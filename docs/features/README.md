# 功能介绍目录

本目录记录 AndroidPerformanceStudio 当前已经实现或正在维护的用户可见功能。每份文档都按同一结构说明：

1. 功能介绍与适用场景；
2. 详细设计方案；
3. 当前实现方案与关键代码入口；
4. 使用流程、输入输出与证据边界；
5. 已知限制和后续方向。

## 功能目录

| 功能 | 文档 | 主要证据 |
| --- | --- | --- |
| Layout Inspector | [layout-inspector.md](layout-inspector.md) | View hierarchy、截图、Agent/fallback capture |
| CPU Profiler | [cpu-profiler.md](cpu-profiler.md) | Simpleperf sample、call tree、flame graph |
| Trace Analyzer | [trace-analyzer.md](trace-analyzer.md) | Perfetto trace、Trace Processor、sched/Binder/graphics |
| Memory Profiler | [memory-profiler.md](memory-profiler.md) | HPROF、Shark、Dominator、Native heap |
| Frame Profiler | [frame-profiler.md](frame-profiler.md) | gfxinfo FrameStats、帧时长、Jank cluster |
| Startup Profiler | [startup-profiler.md](startup-profiler.md) | 冷/温启动、平台启动事件、Baseline Profile |
| Battery Profiler | [battery-profiler.md](battery-profiler.md) | batterystats、wakelock、alarm、network |
| Network Profiler | [network-profiler.md](network-profiler.md) | Agent HTTP/HTTPS events、HAR |
| GPU Inspector | [gpu-inspector.md](gpu-inspector.md) | AGI discovery、artifact validation、trace handoff |
| Benchmark Regression | [benchmark-regression.md](benchmark-regression.md) | AndroidX Benchmark JSON、baseline/current diff |

## 统一证据原则

- 采集原始文件、解析模型、分析结果和 UI 展示不是同一种证据；文档会分别说明。
- Agent 模式、ADB fallback、导入文件和外部工具 viewer 的能力边界必须明确标注。
- “可展示”不等于“可证明因果”；跨工具跳转默认是关联排查，不自动证明因果关系。
- 结果需要记录设备、App、进程、时间基准、采集配置、原始 artifact 和丢样/降级状态。
