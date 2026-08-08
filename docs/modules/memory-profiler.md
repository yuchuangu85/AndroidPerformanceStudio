# Memory Profiler

## 功能作用

Memory Profiler 是一个 Android 堆内存分析工具，核心功能包括：

- **Heap Dump 采集**：通过 ADB 在设备上执行 `am dumpheap` 并 pull HPROF 文件，或直接导入已有 HPROF 文件
- **对象图解析**：`HprofParser` 解析 HPROF 二进制格式，提取 Class（类定义）、Instance（对象实例）、ObjectArray（对象数组）、PrimitiveArray（原始类型数组）、GC Root（垃圾回收根）等信息
- **类直方图**：按类聚合统计实例数量、Shallow Size（对象自身大小）、Retained Size（支配树分析后的持有大小），支持多列排序
- **泄漏证据检测**：基于强引用图、生命周期字段和支配树生成需人工复核的保留证据与引用链
- **Heap Diff 对比**：`HeapDiffAnalyzer` 对比两次 Heap Dump 之间的类实例数量变化，突出新增、移除和数量变化的类
- **Activity 计数**：统计当前堆中所有 `*Activity` 子类的存活实例数
- **Bitmap Dump**：`BitmapDumpParser` 提取堆中 Bitmap 实例的尺寸和 Retained Size，并通过 `BitmapDumpAnalyzer` 对比两次导出之间的 Bitmap 变化
- **会话持久化**：通过 SQLite（`SqliteMemorySessionStore`）持久化 Heap Dump 和 Bitmap Dump 的元数据，支持会话加载和导出

## 实现原理

### 采集流程

1. **设备选择 → 进程枚举**：通过 ADB `ps` 或 `cmd package` 列出可调试进程
2. **Heap Dump**：通过 ADB 执行 `am dumpheap <pid> <path>`，将 HPROF 写入 `/data/local/tmp/`，再通过 `adb pull` 拉取
3. **HPROF 转换**：Android HPROF 通常需要 `hprof-conv` 将其从 Android 格式转换为标准 Java HPROF 格式
4. **解析**：`HprofParser` 按 HPROF 规范逐 Record 解析：
   - STRING、LOAD CLASS 记录 → 构建字符串和类名映射
   - HEAP DUMP + HEAP DUMP SEGMENT 记录 → 解析实例、数组、类定义、GC Root
   - 构建完整对象图（Object Graph）

### 数据分析

| 分析组件 | 输入 | 输出 | 算法 |
|---------|------|------|-----|
| `MemoryHistogramAnalyzer` | 对象图 | `HeapHistogram`（类统计） | 按 className 聚合 instanceCount + shallowSize |
| `DominatorTreeAnalyzer` | 对象图 | immediate dominator + retained size | 对 GC Root 强可达子图执行 Lengauer–Tarjan |
| `HeapDiffAnalyzer` | 前后两次直方图 | `HeapDiff`（变化条目） | 按 className 匹配前后条目，计算 countDelta 和 shallowSizeDelta |
| `BitmapDumpAnalyzer` | 前后两次 Bitmap Dump | `BitmapDumpComparison` | 对比 Bitmap 列表，识别新增/移除/变化的 Bitmap |

### 数据结构

- **HeapDump**：包含 classes、instances、objectArrays、primitiveArrays、gcRoots、leakSuspects、bitmapInstances 等完整对象图
- **HeapHistogram**：按类聚合的统计表，包含 summary（总计）和 classes（ClassStats 列表）
- **HeapDiffEntry**：单类的对比结果，包含前后实例数、Shallow Size 及差值
- **LeakSuspect**：待复核的保留证据，包含 className、reason、retainedSize、referenceChain；兼容字段 confidence 不代表已校准概率

### 数据流

```
[Android Device] --ADB dumpheap--> [HPROF file] --pull--> [HprofParser]
    --> [HeapDump] --> [MemoryHistogramAnalyzer] --> [HeapHistogram]
    --> [DominatorTreeAnalyzer] --> [LeakSuspect[]]
    --> [HeapDiffAnalyzer] --> [HeapDiff]
    --> [Compose UI: MemoryProfilerScreen]
```

### Export

- **Raw / Converted HPROF**：分别导出设备原始文件和可用时的转换副本
- **CSV**：类直方图导出
- **Bitmap Dump**：Bitmap 信息打包导出
- **Bitmap Comparison**：Bitamp 对比结果 Markdown 导出

## 优化建议审计结果

本节以 Android/Perfetto 官方语义、现有代码和可运行测试为准。状态含义：**保留**表示建议成立，**部分保留**表示只保留可验证部分，**排除**表示原建议前提不成立且不实施。

| # | 审计状态 | 结论 |
|---|---|---|
| 1 | 部分保留 | 保留可达对象上的精确支配树和进度；排除采样 retained size、流式/ETA 及无基准的性能承诺。 |
| 2 | 排除原方案 | API Level 不是 HPROF 格式边界；改为始终保留原始 HPROF，并在工具可用时生成转换副本，转换失败仍由内置解析器读取原文件。 |
| 3 | 部分保留 | 保留 Android 10+ heapprofd 原始 Perfetto trace 采集；应用内分配表仅为 best-effort 摘要，完整分析交给 Perfetto。 |
| 4 | 保留 | 保留 mapping.txt 类名还原；数组类名/描述符同样还原，导入 mapping 不产生 Heap Diff。 |
| 5 | 部分保留 | 保留强/弱引用语义和可复核引用链；排除静态白名单豁免及未经校准的置信度。 |
| 6 | 部分保留 | 对齐 Android Studio 的 Activity/Fragment 生命周期候选规则；保留强可达证据并排除“多实例即泄漏”。 |
| 7 | 排除原方案 | hierarchy depth 不能代表 ClassLoader；两次独立 HPROF 的对象 ID 也不是稳定身份。仅保留按类名的统计差异并声明限制。 |
| 8 | 部分保留 | 保留 Bitmap 排序、现有 Bitmap Dump 图库及像素内存估算；排除把固定 `W×H×4` 当实测 native size。 |

### #1 精确支配树与弱引用边【部分保留】

- 先计算 GC Root 强可达集合，再对该集合执行精确 Lengauer–Tarjan；不可达对象不参与 retained size 聚合。
- `java.lang.ref.Reference` 及其子类的 `referent` 不作为强边。
- 修正迭代 DFS 在交叉边图上的父节点错误，并复用同一 `HeapGraph`，避免一次分析重复建图。
- **排除**：抽样 retained size、预计完成时间、流式计算和“提升一个数量级”等未实现或无基准结论。

### #2 HPROF 转换策略【原方案排除，替代方案已实施】

[Android 官方文档](https://developer.android.com/studio/profile/capture-heap-dump)仍区分 Android HPROF 与供其他 Java HPROF 工具使用的转换格式，因此不能用 API 26 作为跳过转换的依据。

- 原始 `.raw.hprof` 始终保留，并继续兼容既有 Android HPROF 导入。
- 找到 `hprof-conv` 时生成 Java SE 兼容副本；找不到或转换失败时保留原始文件并明确告警。
- `deviceSdkApiLevel`、`conversionSkipped` 等既有字段继续保留，避免破坏调用方；新流程不再按 API 跳过转换。

### #3 Native Heap / heapprofd【部分保留】

- heapprofd 从 Android 10（API 29）起可用；采集数据源修正为官方的 `android.heapprofd`。限制与权限以 [Perfetto Native Heap Profiler](https://perfetto.dev/docs/data-sources/native-heap-profiler) 为准。
- 原始 Perfetto trace 是权威证据，可导出并由 Perfetto/Android Studio 完整分析。
- 内置 protobuf reader 只提供 best-effort 分配摘要；损坏输入降级为空摘要，不能宣称完整处理 packet sequence、增量状态、guardrail、采样缩放或完整调用树。
- 删除自研 Itanium C++ demangler；符号化应复用已安装 NDK/Perfetto 工具并在不可用时保留原符号。
- **排除**：尚未实现的 pprof 导出，以及“深度解析全部完成”的结论。

### #4 mapping.txt 反混淆【保留】

- 支持类映射和双名展示，并覆盖 `Foo[]`、`[LFoo;` 等数组表示。
- mapping 只改变名称解释；对同一堆重新分析时不会生成一次伪 Heap Diff。
- 成员级反混淆不在当前需求内。

### #5 泄漏证据【部分保留】

- `LeakSuspect` 表示需复核的保留证据，不等同于已证实泄漏；引用链只沿强边构建。
- 静态字段或框架单例持有 Activity/Context 不再被白名单静默排除，因为长生命周期持有者同样可能造成真实泄漏。
- 未建立校准数据集前不输出伪精确置信度；兼容字段保留为 `0`，并统一标记 `requiresManualVerification=true`。
- 大 Bitmap 本身不是泄漏证据，不再仅因尺寸进入泄漏嫌疑列表。

### #6 Activity/Fragment 保留证据【部分保留】

- 严格按 HPROF 类继承链识别 `android.app.Activity`、平台 Fragment、Support Fragment 和 AndroidX Fragment 子类。
- 只有 `mDestroyed` 或 `mFinished` 已置位且对象仍从 GC Root 强可达时，才进入生命周期专项的 Activity 保留报告；静态/Handler 持有仍只作为通用待复核证据。
- Fragment 按 Android Studio 公开实现，将 `mFragmentManager == null` 且仍强可达的实例列为候选，并明确该规则可能误报尚未 attach 的新实例。
- Perfetto `android.java_hprof` 不携带通用 primitive 字段，因此该格式可做 Fragment 引用规则，但 Activity 生命周期字段只能在二进制 HPROF 中判断；界面不得把缺失字段解释为“无泄漏”。
- **排除**：仅凭同类 Activity 多实例直接判定泄漏；配置切换、多窗口和正常导航都可能产生多个实例。

### #7 Heap Diff 身份【原方案排除】

- 默认继续按 `className` 比较类计数和 shallow size，并明确同名多 ClassLoader 场景可能合并。
- `CLASS_NAME_AND_HIERARCHY` 仅为旧调用方保留，不再作为 Controller 默认模式。
- **排除**：用 hierarchy depth 代替 ClassLoader 身份，以及用 dump-local object ID 跨两次 HPROF 追踪“同一对象”。没有稳定构建与加载器身份时不伪造精确匹配。

### #8 Bitmap 内存与预览【部分保留】

- 有 `rowBytes` 时用 `rowBytes × height`；否则明确采用 ARGB_8888 的 `width × height × 4` 估算。ARGB_8888 通常为 4 B/像素，参见 [Android 内存管理文档](https://developer.android.com/topic/performance/memory)。
- HPROF 未提供 backing allocation 归属或 stride 时，`nativeSizeBytes` 保持 `null`，不把估算值冒充实测 native memory。
- Android 不同版本的 Bitmap 像素数据所在堆不同，参见 [Managing Bitmap Memory](https://developer.android.com/topic/performance/graphics/manage-memory)。
- 图像内容预览由已有 Bitmap Dump PNG 图库提供；普通 HPROF 不额外推测或重建像素。

完整改动与排除项见 [`memory-profiler-optimization-summary.md`](./memory-profiler-optimization-summary.md)。
