# CPU Profiler

## 功能介绍

CPU Profiler 使用 Android `simpleperf` 采集 CPU sample，提供时间范围、线程、库、符号、调用树和火焰图分析。它适合定位 CPU 热点、主线程过载、线程池分布、Native/JIT/ART 方法占比和调用路径。

## 详细设计方案

```text
设备/进程选择
  -> simpleperf capture
  -> perf.data / protobuf / method trace
  -> stream parser + SQLite storage
  -> profile analysis
  -> timeline / top / call tree / flame graph
```

### 模块职责

- `simpleperf-viewer/capture-simpleperf`：构建采集命令、启动/停止、pull 原始文件。
- `simpleperf-viewer/parser-simpleperf-proto`：解析 sample、callchain、lost/unwind 信息。
- `simpleperf-viewer/parser-art-trace`：处理 ART/method trace 输入。
- `simpleperf-viewer/profile-model`：统一 sample、thread、callsite、event 模型。
- `simpleperf-viewer/profile-analysis`：热点、调用树、反向调用树、聚合和过滤。
- `simpleperf-viewer/visualization`：火焰图和调用路径可视化。
- `simpleperf-viewer/storage-sqlite`：大样本增量存储和查询。
- `simpleperf-viewer/application`：设备、采集、报告状态和会话恢复。

### 证据边界

采样权重是统计估计，不是逐指令精确耗时。报告必须区分 sample count、inclusive、exclusive、lost sample、unwind error 和 symbol missing。

## 实现方案

桌面端将 ADB、Host Toolchain、simpleperf 命令和平台错误隔离在 capture 层；parser 不负责 UI，analysis 不直接启动设备命令。报告可通过 SQLite 查询重新聚合，避免导入时只保存 folded stack 而丢失时间线和过滤能力。

## 使用方案

1. 选择设备、进程和采样模板。
2. 配置时长、频率、event、callgraph 和符号策略。
3. 开始采集，必要时取消。
4. 等待 pull、report-sample 转换和导入。
5. 在 Overview、Timeline、Top Functions、CallTree、FlameGraph 间切换。
6. 导出报告、截图或原始 profile 数据。

## 输出与限制

- 支持原始采样、结构化 profile、火焰图和调用树。
- 栈展开、符号文件、权限和目标设备 ABI 会影响结果质量。
- Simpleperf 结果不能直接替代 Perfetto 调度证据；线程未运行、Binder 等待和 CPU contention 需要 Trace Analyzer 关联。
