# Memory Profiler

## 功能作用

Memory Profiler 是一个 Android 堆内存分析工具，核心功能包括：

- **Heap Dump 采集**：通过 ADB 在设备上执行 `am dumpheap` 并 pull HPROF 文件，或直接导入已有 HPROF 文件
- **对象图解析**：`HprofParser` 解析 HPROF 二进制格式，提取 Class（类定义）、Instance（对象实例）、ObjectArray（对象数组）、PrimitiveArray（原始类型数组）、GC Root（垃圾回收根）等信息
- **类直方图**：按类聚合统计实例数量、Shallow Size（对象自身大小）、Retained Size（支配树分析后的持有大小），支持多列排序
- **泄漏嫌疑检测**：`DominatorTreeAnalyzer` 基于支配树分析识别潜在内存泄漏对象，提供引用链路径和置信度评估
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
| `DominatorTreeAnalyzer` | 对象图 | `LeakSuspect` 列表 | 构建支配树、计算 Retained Size，识别异常大的持有者 |
| `HeapDiffAnalyzer` | 前后两次直方图 | `HeapDiff`（变化条目） | 按 className 匹配前后条目，计算 countDelta 和 shallowSizeDelta |
| `BitmapDumpAnalyzer` | 前后两次 Bitmap Dump | `BitmapDumpComparison` | 对比 Bitmap 列表，识别新增/移除/变化的 Bitmap |

### 数据结构

- **HeapDump**：包含 classes、instances、objectArrays、primitiveArrays、gcRoots、leakSuspects、bitmapInstances 等完整对象图
- **HeapHistogram**：按类聚合的统计表，包含 summary（总计）和 classes（ClassStats 列表）
- **HeapDiffEntry**：单类的对比结果，包含前后实例数、Shallow Size 及差值
- **LeakSuspect**：泄漏嫌疑对象，包含 className、reason、retainedSize、referenceChain（引用链路径）、confidence

### 数据流

```
[Android Device] --ADB dumpheap--> [HPROF file] --pull--> [HprofParser]
    --> [HeapDump] --> [MemoryHistogramAnalyzer] --> [HeapHistogram]
    --> [DominatorTreeAnalyzer] --> [LeakSuspect[]]
    --> [HeapDiffAnalyzer] --> [HeapDiff]
    --> [Compose UI: MemoryProfilerScreen]
```

### Export

- **Raw HPROF**：原始或转换后的 HPROF 文件
- **CSV**：类直方图导出
- **Bitmap Dump**：Bitmap 信息打包导出
- **Bitmap Comparison**：Bitamp 对比结果 Markdown 导出

## 优化建议与改进点

> 以下内容不替换已有设计，仅作为可考虑的更好实现方式或优化点补充。每条标注 **影响**（高/中/低）与 **可行性**（高/中/低）。
>
> 状态：标注 ✅ 的条目已实施，详见 [`memory-profiler-optimization-summary.md`](./memory-profiler-optimization-summary.md)。

### 1. 支配树计算对超大堆的性能与内存【影响:高 / 可行性:中】 ✅

**当前实现问题**：`DominatorTreeAnalyzer` 构建"完整对象图 → 支配树 → Retained Size"。完整支配树计算（如 Lengauer-Tarjan）对大堆（数百 MB、千万对象）是 O(n·α) 但常数大，且需在内存中保留整个对象图，常 OOM 或耗时数十秒。

**更好的实现方式**：
- 引入 **可达性优先（reachability-first）算法**（类似 LeakCanary 2.x / Android Studio Memory Profiler 的思路）：先用 BFS/DFS 做可达性分析识别"无法回收"的对象子集，再在小得多的子集上做支配树，性能提升一个数量级、内存峰值大幅下降。
- 对超大批量对象（如百万级 byte[]），提供 **采样模式**：只对 N% 采样计算，标注 `sampled=true` 与置信区间，而非全量。
- 计算改为流式/分批，并在 UI 显示进度与"预计完成时间"，避免长时间无响应。

### 2. hprof-conv 的现状与必要性【影响:中 / 可行性:高】 ✅

**当前实现问题**：文档说"Android HPROF 通常需要 `hprof-conv` 转换为标准 Java HPROF 格式"。实际上 Android 8.0+（API 26+）的 `am dumpheap` 已能直接输出标准 MAT 可读 HPROF，`hprof-conv` 在新设备上基本不必要；继续无条件转换徒增步骤与失败点。

**更好的实现方式**：
- 在采集流程中 **按 API Level 判断**：API < 26 走 `hprof-conv`，API ≥ 26 直接使用原始 HPROF，跳过转换。
- 对跳过转换的情况，验证 `HprofParser` 能直接解析 Android 原生格式；若解析失败再回退到转换后文件。
- 文档说明这一行为，避免用户困惑"为何有时转换有时不转换"。

### 3. Native 内存与 heapprofd 的缺失【影响:高 / 可行性:中】 ✅（采集 + 分配表深度解析）

**当前实现问题**：工具只覆盖 Java Heap（HPROF），不分析 Native 内存。Android 性能问题中 Native 堆（SoLoader、Bitmap 像素数据在新版本、JNI malloc、 Vulkan/图形内存）是常见根因，缺失会让"Java 堆正常但内存涨"的案例无从下手。

**更好的实现方式**：
- 集成 **`heapprofd`（Perfetto 的 native heap profiler）**：通过 `perfetto -c -` 配置 `linux.heapprofd` 数据源，抓取目标进程的 Native 堆分配，导出 `.heapprofd` 在应用内查看。
- 对 Bitmap，在新 Android（Android 11+）像素数据存于 Native（`NativeAllocationRegistry`），可在 HPROF 中关联 native allocation tag，建议在 Bitmap 分析中同时展示 `nativeSize` 与 `javaSize`。
- 长期看，提供 **pprof 格式输出**，便于复用 `pprof` 生态（Go 工具、FlameGraph）分析。

### 4. 混淆类名的 mapping 还原【影响:中 / 可行性:高】 ✅

**当前实现问题**：release 构建通常经过 R8/ProGuard 混淆，HPROF 中类名是 `a.b.c`，难以直接定位源码。文档未提及 mapping 还原。

**更好的实现方式**：
- 支持 **导入 `mapping.txt`**（R8 输出），在解析阶段或展示阶段把混淆类名还原为原始类名（`a.b.c` → `com.example.MainActivity`）。
- 保留双名展示（`com.example.MainActivity (a.b.c)`），便于在反混淆失效时回溯。
- 对无 mapping 的情况，提示"未提供 mapping，结果为混淆名"，避免用户误把 `a.b.c` 当真实类。

### 5. 泄漏识别的启发式与误报控制【影响:中 / 可行性:高】 ✅

**当前实现问题**：`LeakSuspect` 基于"支配树中异常大的持有者 + 引用链 + 置信度"，但置信度算法未说明。常见的误报来源：单例的 Application/系统对象、合理长生命周期的缓存。

**更好的实现方式**：
- 明确 **启发式规则**：忽略 GC Root 直接持有的"合理长生命周期"对象（`Application`、`Runtime`、`MainActivity` 的 ViewModel Store 等）作为白名单。
- 引入 **WeakReference/软引用感知**：被 WeakRef 指向的对象不算泄漏嫌疑。
- 把 `confidence` 算法文档化（基于 retained size + 引用链深度 + 是否经过生命周期白名单），并对低置信度嫌疑标注 `requiresManualVerification`。

### 6. Activity 泄漏的专门识别【影响:中 / 可行性:高】 ✅

**当前实现问题**：只做通用泄漏嫌疑 + Activity 计数。Activity 泄漏是最常见且影响最大的泄漏类型，应单独识别与报告。

**更好的实现方式**：
- 专门扫描 `*Activity` 实例，检查是否存在 **多个实例**（同 Activity 多于 1 个 → 强烈泄漏信号）。
- 对每个 Activity 实例，构建 **泄漏路径直达 ActivityThread/Application**，并标记"是否已 destroy 但仍在堆中"（通过 `mDestroyed` 字段 / `mFinished` 标志位判断）。
- 输出 `ActivityLeakReport`，按 Activity 类名分组展示存活实例数 + 引用链，比通用 LeakSuspect 更可操作。

### 7. Heap Diff 的类匹配稳定性【影响:中 / 可行性:高】 ✅

**当前实现问题**：`HeapDiffAnalyzer` 按 className 匹配前后条目。混淆场景下 className 不稳定（不同 build 混淆结果可能不同），且类名相同但 classloader 不同（多 dex、插件化）会被错误合并。

**更好的实现方式**：
- 匹配键增加 **classloader + hierarchy depth** 等维度，避免跨 classloader 合并。
- 对 diff 结果增加 **对象实例级追踪**（可选高级模式）：用对象 id 在两次 dump 中追踪"同一对象是否存活"，区分"新增对象"与"老对象保留"，比只看类计数更精确。

### 8. 大 Bitmap 的图像预览与体积优先级【影响:低 / 可行性:中】 ✅（预估像素内存标注）

**当前实现问题**：`BitmapDumpParser` 提取 Bitmap 尺寸和 Retained Size，但未展示 Bitmap 内容预览。大 Bitmap（几 MB 的图片）是内存大户，看不到内容难以判断是否合理。

**更好的实现方式**：
- 对超过阈值的 Bitmap（如 retained > 1MB），从 HPROF 中提取像素数据生成 **缩略图预览**，让用户直观看到是哪张图。
- 按 retained size 降序展示，并在预览旁标注 `width×height × bytesPerPixel`（推算内存公式），帮助用户识别"超大尺寸图片未压缩"等常见问题。
- 注意 Bitmap 像素数据可能压缩存储，提取时需解压，限制预览生成数量避免性能问题。
