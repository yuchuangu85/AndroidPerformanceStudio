# 功能介绍目录

本目录是 Android Performance Studio 当前功能的用户和工程说明。`rust-desktop` 中的 Rust/GPUI 实现是当前桌面实现；文档中的历史 Compose 名称只用于解释行为来源，不代表当前代码仍由 Compose 驱动。

功能页会明确区分以下状态：

- **已接入**：当前 Rust 桌面端或离线分析路径已有对应入口和实现。
- **条件能力**：依赖设备版本、权限、目标 App 插桩、外部工具、artifact 完整性或特定输入格式。
- **未完成验收**：代码或解析器可能存在，但尚未证明完整的真机、原生 GUI、打包或发布工作流。
- **未接入**：历史实现、设计目标或测试夹具不会被写成当前产品能力。

完整工作流还需要结合 [Rust 功能对等审计](../../rust-desktop/FUNCTIONAL_PARITY.md) 阅读；审计中的通过测试、静态检查、设备运行、原生 GUI、打包和 Release 证据属于不同门槛。

## 统一结构

每个功能页按以下顺序说明：

1. 功能点与适用场景；
2. 数据流、实现原理和关键代码入口；
3. 使用方法、输入输出、导入/导出和跨工具关联；
4. 证据边界、失败诊断、限制和当前验收状态。

## 功能目录

| 功能 | 文档 | 主要输入 | 主要输出/用途 |
| --- | --- | --- | --- |
| Layout Inspector | [layout-inspector.md](layout-inspector.md) | View/Compose 层级、截图、Inspector archive | 层级树、画布边界、属性、结构 Findings、时间线和归档 |
| CPU Profiler | [cpu-profiler.md](cpu-profiler.md) | simpleperf/perf.data、调用栈、符号和采样配置 | 时间线、热点、调用树、火焰图、线程和 AI 证据 |
| Trace Analyzer | [trace-analyzer.md](trace-analyzer.md) | Perfetto trace、固定版本 Trace Processor、typed SQL | 调度/Binder/图形/运行时诊断、原始 trace 和查询报告 |
| Memory Profiler | [memory-profiler.md](memory-profiler.md) | HPROF、Native/ART/Bitmap 证据、mapping | 类/实例/引用/Dominator、diff、泄漏候选和导出 |
| Frame Profiler | [frame-profiler.md](frame-profiler.md) | gfxinfo、FrameTimeline、JankStats 或导入报告 | 帧时长、deadline miss、jank cluster、页面/状态关联 |
| Startup Profiler | [startup-profiler.md](startup-profiler.md) | cold/warm/hot 实验、启动事件、Perfetto 和 profile 状态 | TTID/TTFD 可用性、阶段分布、CPU 热点和比较 |
| Battery Profiler | [battery-profiler.md](battery-profiler.md) | batterystats、thermalservice、系统状态和实验配置 | 电量/UID 资源、wakelock、alarm、网络、thermal 和会话导出 |
| Network Profiler | [network-profiler.md](network-profiler.md) | 配置的 OkHttp Agent 事件或 HAR | 脱敏请求时间线、连接/TLS/失败详情、汇总和导出 |
| GPU Inspector | [gpu-inspector.md](gpu-inspector.md) | AGI 工具链、GPU artifact、设备图形元数据 | artifact 索引/校验、AGI 打开入口和 Trace handoff |
| Benchmark Regression | [benchmark-regression.md](benchmark-regression.md) | AndroidX Benchmark JSON、baseline/current、Macrobenchmark 结果 | 样本比较、统计回归门禁、趋势、报告和 CI/CLI 输出 |

## 统一证据原则

- 原始 artifact、解析模型、派生分析、UI 展示、CLI/MCP 响应和外部 viewer 不是同一种证据；功能页会分别说明。
- Agent、ADB fallback、离线导入、外部 AGI/Perfetto UI 和宿主工具链必须标明自己的前置条件与降级路径。
- “可展示”不等于“可证明因果”；跨工具跳转默认是有界关联排查，不自动证明代码、GPU 或功耗因果。
- 结果应记录设备、App、进程、窗口/会话身份、时间基准、采集配置、原始 artifact、schema/tool 版本和丢样/截断/降级状态。
- 未知、缺失、未覆盖和未验收必须保持显式；文档不使用 0、空列表或旧版本测试替代缺失证据。

## 代码入口

- [Rust Desktop](../../rust-desktop/README.md)：当前桌面实现、资源、构建和启动入口。
- [功能对等审计](../../rust-desktop/FUNCTIONAL_PARITY.md)：当前 Rust 工作流、已接入功能和剩余验收门禁。
- [CLI/MCP 文档](../ai/cli/README.md)：无需打开桌面时的文件/报告分析边界。
- 各 `aps-*` crate 的 `README.md` 和源码测试：模块职责、协议限制、输入预算和验证边界。

各功能页保留用户可见能力、原理、使用方法、输入输出、证据边界和当前限制；具体实现应以当前 Rust 源码、crate 文档和对等审计为准。
