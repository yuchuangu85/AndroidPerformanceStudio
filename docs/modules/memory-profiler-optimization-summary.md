# Memory Profiler 优化项审计与实施总结

## 验收结论

原文 8 项并非全部准确：**#4 保留，#1/#3/#5/#6/#8 部分保留，#2/#7 原方案排除**。本轮只修复能够由官方语义或测试验证的行为；Java Heap Trace、实例浏览及阶段 B/C/D UI 不属于这 8 项，保持现状并另行验收。

| # | 处理 | 本轮结果 |
|---|---|---|
| 1 | 部分保留 | 修复 LT 迭代 DFS；弱引用子类 `referent` 从强图排除；分析复用单个 HeapGraph。 |
| 2 | 排除 API 分支 | 不再按 API 26 跳过；保留 raw，转换副本 best-effort，转换失败不丢数据。 |
| 3 | 部分保留 | 修正 `android.heapprofd`；原始 trace 为权威；损坏摘要输入安全降级；删除自研 C++ demangler。 |
| 4 | 保留 | 数组/描述符反混淆；导入 mapping 不制造 Heap Diff。 |
| 5 | 部分保留 | 删除不安全白名单和伪置信度；只输出需人工复核的强引用证据。 |
| 6 | 部分保留 | Activity/Fragment 按继承链识别；对齐 destroyed/finished 与空 FragmentManager 且强可达的候选规则。 |
| 7 | 排除身份推断 | Controller 恢复 name-only diff；层级模式只为兼容保留并标注非 ClassLoader 身份。 |
| 8 | 部分保留 | 优先 rowBytes 估算；未知 backing heap 时不填写 nativeSize。 |

## 兼容性

- 现有 HPROF、Bitmap Dump、SQLite 会话和导出格式未改版本，原始/转换后 HPROF 均继续可读。
- `MemoryCaptureResult.conversionSkipped`、`LeakSuspect.confidence`、`BitmapInstanceStats.nativeSizeBytes`、`CLASS_NAME_AND_HIERARCHY` 等既有字段/枚举继续保留；只修正生产语义，避免删除或重命名协议字段。
- mapping 导入只重新解释名称，不改变采集身份，也不生成一次前后堆差异。

## 明确排除

- retained size 抽样、置信区间、流式计算、ETA 和无基准性能倍数。
- API Level 推断 HPROF 是否需要转换。
- 手写 Perfetto 完整协议栈、完整符号化、pprof 导出。
- 生命周期白名单自动豁免、未校准的泄漏置信度。
- Activity 多实例即泄漏。
- hierarchy depth 充当 ClassLoader 身份、跨 dump object ID 追踪。
- 将 `width × height × 4` 直接记为实测 native memory。

## 验证

- 通过：analysis、capture、app、parser、storage、export、model 的全部单元测试；presentation 24 项中 23 项通过。
- 已知既有失败：`MemoryProfilerToolbarSelectorsTest` 在 headless Compose 鼠标注入处失败，与本轮逻辑无关。
- 通过：capture/app/model ktlint；analysis 的本轮修改无新增 ktlint 告警，但模块仍被既有 `JavaHeapTraceParser` / `HeapGraphToHeapDump` 格式告警阻断。
- 针对性覆盖：支配树交叉边、弱引用子类、Activity 继承关系、mapping 数组名称、mapping 不生成 diff、API 级别不绕过转换、转换失败保留 raw、heapprofd 数据源、Bitmap rowBytes、Native trace 序列隔离和损坏输入。
