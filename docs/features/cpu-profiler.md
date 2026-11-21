# CPU Profiler

## 功能定位

CPU Profiler 通过 Android `simpleperf` 或导入的 `perf.data`/采样报告，回答“哪些线程在运行、哪些函数占用采样、调用路径如何展开、样本质量是否足够”。它用于热点定位和跨线程比较，不替代 Perfetto 对调度等待、Binder、run queue 或 CPU contention 的证据。

当前状态：

- **已接入**：采样导入、simpleperf 转换、线程/时间线、Top Functions、调用树、反向调用树、火焰图、Stack Chart、过滤和报告导出。
- **条件能力**：设备采集依赖 simpleperf、目标权限、ABI、符号和 callgraph 能力；Native/JIT/ART 符号质量各不相同。
- **未完成验收**：完整物理设备矩阵、所有符号器和长时大文件内存/取消门禁仍需单独验证。

## 功能点

- 采集配置：时长、采样频率、event、callgraph、目标线程/进程和符号策略。
- 导入 plain `perf.data`、simpleperf protobuf、gzip 包装的 simpleperf 数据以及兼容报告格式。
- 时间线查看线程活动和采样分布；支持时间、线程、事件、库和栈过滤。
- Top Functions 按采样权重展示热点；区分 inclusive weight、self/leaf weight、sample count 和线程数。
- Forward Call Tree 从调用者展开到被调用者；Reverse Call Tree 从热点反查调用路径。
- Flame Graph 和 Stack Chart 支持聚合、缩放和选中联动；缺失符号、unwind error、lost sample 保留为质量提示。
- 对比和 AI 入口只使用当前选择和有界证据；不会自动上传原始 profile。

## 原理与数据流

```text
ADB/simpleperf or imported profile
  -> bounded source validation / optional conversion
  -> sample parser + symbol/callchain normalization
  -> typed profile model
  -> timeline / query / call tree / flame graph projections
  -> report, export and bounded AI context
```

当前 Rust 入口：

- `rust-desktop/crates/aps-app/src/cpu.rs`：CPU workspace、查询状态、图表、调用树和交互。
- `rust-desktop/crates/aps-cpu`：profile 模型、转换、聚合、分页和图表数据。
- `rust-desktop/crates/aps-simpleperf-engine`：simpleperf/perf.data 解析和转换边界。
- `rust-desktop/crates/aps-app/src/simpleperf_tool.rs`：宿主工具发现和执行隔离。

解析器不启动 UI 命令，UI 不直接解析 shell 文本。导入会校验来源身份、大小和转换结果；取消、损坏、超预算和缺失工具应以明确错误结束。

## 使用方法

1. 选择设备、目标进程和采样模板，或直接导入 profile。
2. 配置采样时长、频率、event、callgraph 和符号来源；先确认目标权限。
3. 开始采集并等待 pull、转换和导入完成；中途取消时检查任务是否真正结束。
4. 从 Overview/Timeline 查看样本量和质量，再进入 Top Functions、Call Tree、Reverse Tree、Flame Graph 或 Stack Chart。
5. 先按线程和时间窗口缩小范围，再定位函数、库、调用者和热点路径。
6. 导出结构化报告或原始 profile；需要调度/阻塞解释时转到 Trace Analyzer。

## 输出与证据解释

输出包括原始 profile、解析后的采样模型、线程时间线、热点表、调用树、火焰图和报告。解释结果时至少保留：

- sample count、采样权重、inclusive/self 语义和分母；
- lost sample、unwind error、symbol missing、截断和转换警告；
- 设备、ABI、event、采样频率、callgraph 和符号版本；
- 采样时间窗口和目标进程/线程身份。

采样权重是统计估计，不是逐条指令耗时，也不是方法调用次数。某函数没有样本不等于它没有执行；可能是窗口、采样率、展开或符号问题。

## 限制与故障排查

- 没有样本：检查 simpleperf 是否可用、目标是否有负载、权限和采集窗口；空闲设备不能证明无 CPU 工作。
- 调用树断裂：检查 callgraph 模式、unwind、Native 符号和混合 Java/ART 栈；不要手工拼接断裂栈。
- 结果偏少：查看 lost sample、过滤条件、分页和输入预算；导入成功不等于完整 profile。
- 与 Trace 不一致：CPU sample 统计“采到的运行样本”，Perfetto 还可说明 runnable、睡眠、Binder、频率和 contention；两者应按时间/线程身份关联。
- Release/设备验收、符号文件完整性和大 profile 性能仍需按项目审计门禁验证。
