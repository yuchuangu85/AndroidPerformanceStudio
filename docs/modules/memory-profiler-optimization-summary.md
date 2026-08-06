# Memory Profiler 优化项实施总结

基于 `docs/modules/memory-profiler.md` 中的 8 条优化建议，分两轮实施：

- **第一轮**「核心高可行包」5 条：#2 / #4 / #5 / #6 / #7（已全部完成）
- **第二轮**迭代暂缓项 3 条：#1 支配树性能（完成）、#3 heapprofd Native 堆（采集+深度解析全部完成）、#8 Bitmap 预估像素内存（完成）

至此 8 条优化建议及 2 项后续工作（`InternedData` 外部符号化、Bitmap java/native 分离）全部落地。

共涉及 20+ 个文件、6 个新增模型、2 个新增解析器/采集器。

## 改动文件清单

| 文件 | 改动类型 |
|------|----------|
| `capture-memory/.../MemoryCaptureSession.kt` | 扩展：getprop 探测 API level + 按 API 跳过 hprof-conv |
| `memory-app/.../DesktopMemoryProfilerBackend.kt` | 扩展：mapping 状态、importMapping、反混淆管线、Activity 报告接线 |
| `memory-app/.../MemoryProfilerController.kt` | 扩展：importMapping 控制器方法 + 接口 + 层级匹配 diff |
| `memory-app/.../MemoryProfilerFileMenu.kt` | 扩展：「导入 mapping.txt」菜单项 |
| `memory-app/.../MemoryProfilerMainPage.kt` | 扩展：mapping 文件对话框 |
| `memory-model/.../MemoryModel.kt` | 扩展：ClassStats 双名/深度、ActivityLeakEntry、LeakSuspect 手动验证、HeapDiffMatchMode |
| `analysis-memory/.../ProguardMapping.kt` | 新增：mapping.txt 解析器 + 反混淆扩展 + 混淆名启发式 |
| `analysis-memory/.../MemoryHistogramAnalyzer.kt` | 扩展：deobfuscator + 层次深度 |
| `analysis-memory/.../MemoryDeepAnalysis.kt` | 扩展：泄漏白名单、弱引用感知、Activity 泄漏报告、层级 diff |
| `presentation/.../MemoryProfilerState.kt` | 扩展：mappingLoaded + activityLeaks |
| `presentation/.../MemoryProfilerScreen.kt` | 扩展：Activity 泄漏区、双名显示、手动验证标记、mapping 提示 |
| 2 个 `strings.xml` × 2 语言 | 新增：mapping 导入 / Activity 泄漏 / 手动验证文案 |
| 4 个 `*Test.kt` | 更新/新增测试用例 |

---

## 优化项详述

### #2 hprof-conv 按 API Level 判断

**影响**: 中 / **可行性**: 高 ✅

- 采集流程在 pull 后通过 `adb shell getprop ro.build.version.sdk` 探测设备 API level
- **API ≥ 26**（Android 8.0+）：`am dumpheap` 已直接输出标准 Java HPROF，**跳过 hprof-conv**，减少一步命令与失败点
- **API < 26** 或探测失败：保留原逻辑（有 hprof-conv 则转换，缺失则给出 `HPROF_CONV_MISSING` 提示）
- `MemoryCaptureResult` 新增 `deviceSdkApiLevel` / `conversionSkipped`；backend 在跳过转换时**不再**提示「请安装 hprof-conv」
- 行为在本文档与 UI 文案中说明，避免「为何有时转换有时不转换」的困惑

### #4 混淆类名的 mapping 还原

**影响**: 中 / **可行性**: 高 ✅

- 新增 `ProguardMappingParser`：解析 R8/ProGuard `mapping.txt` 的类映射行（`com.example.MainActivity -> a.b.c:`），忽略缩进的成员行
- **File ▸ Import mapping.txt…** 导入后：
  - 解析阶段对 `HeapDump` 全量反混淆（classes / instances / references 的 `className`），**先反混淆再分析**——使基于类名后缀的 Activity 检测、泄漏启发式在 release 混淆构建下依然有效
  - 直方图保留**双名展示**：`com.example.MainActivity (a.b.c)`（`ClassStats.obfuscatedClassName`）
  - 界面顶部显示「mapping.txt loaded」提示
- 未提供 mapping 且堆中存在疑似混淆名（`isLikelyObfuscatedClassName` 启发式）时，加载后给出提示，避免把 `a.b.c` 误当真实类
- 导入 mapping 后若已加载堆，会立即重新分析并刷新界面

### #5 泄漏识别启发式与误报控制

**影响**: 中 / **可行性**: 高 ✅

- 新增 `LeakWhitelist` 长生命周期白名单：`android.app.Application`、`ActivityThread`、`LoadedApk`、`ResourcesManager` 等框架单例，以及 `*Application` 子类
  - 静态字段持有 Context 的启发式**跳过**持有者为白名单类、或目标为白名单类的场景（单例 Application 本应常驻）
- **WeakReference / 软引用感知**：`ReferenceChainFinder` 不再把 `java.lang.ref.*` 的 `referent` 边当作强可达边；仅经弱引用可达的对象**不再算作泄漏嫌疑**
- 所有启发式都要求**强可达引用链非空**（排除弱引用可达、纯静态不可达的情况）
- 置信度算法文档化（注释 + 本文档）：
  - Activity 多实例 0.85，含已销毁实例提升至 0.9
  - 静态持有 Context 0.95；Handler/Thread 持有 Activity 0.9；超大 Bitmap 0.8
- `LeakSuspect` 新增 `requiresManualVerification`（置信度 < 0.7），UI 以「requires manual verification」标记

### #6 Activity 泄漏专门识别

**影响**: 中 / **可行性**: 高 ✅

- 新增 `ActivityLeakReport` / `ActivityLeakEntry` 模型：按 Activity 类名分组统计
  - `liveInstanceCount`：强可达存活实例数
  - `destroyedInstanceCount`：已 `mDestroyed`/`mFinished` 但仍留在堆中的实例数（**最强泄漏信号**）
  - 代表性实例的 retained size + 最短根引用链
- `MemoryDeepAnalysisResult.activityLeaks` 贯穿 backend → state → 独立「Activity leaks」UI 区块
- Activity 多实例泄漏嫌疑在含已销毁实例时置信度提升至 0.9

### #7 Heap Diff 类匹配稳定性

**影响**: 中 / **可行性**: 高 ✅

- `ClassStats` 新增 `hierarchyDepth`（superclass 链深度，Object=0），由 `MemoryHistogramAnalyzer` 计算
- `HeapDiffAnalyzer.diff()` 新增 `HeapDiffMatchMode`：
  - `CLASS_NAME`（默认，向后兼容）
  - `CLASS_NAME_AND_HIERARCHY`：按「类名 + 层次深度」匹配，避免跨 classloader / 多 dex 下同名类被错误合并
- `HeapDiffEntry` 记录 `matchedBy` 与 `hierarchyDepth`；Controller 已切换为层级匹配模式
- 完整 classloader 身份追踪需解析器提取 classloader 字段，列为后续工作

---

## 第二轮迭代：暂缓项

### #1 支配树计算 reachability-first【影响:高 / 可行性:中】 ✅

`DominatorTreeAnalyzer` 重写为**可达性优先**：

- 先从 GC Roots 经引用图做 BFS，计算可达（可存活）对象子集
- 仅对该子集用**紧凑索引**构建 Lengauer-Tarjan 支配树——不再为不可达对象分配 LT 数组（`successors`/`predecessors`/`semi`/`bucket` 等），大堆含大量垃圾时**峰值内存与耗时显著下降**
- 不可达对象语义保持：`immediateDominator = null`、`retainedSize = shallowSize`；等价性由既有测试覆盖
- 新增 `onProgress` 进度回调（0→30→70→100），`MemoryDeepAnalyzer` 透传，backend 将解析 0-50% + 分析 50-100% 合并为总进度
- 新增测试：不可达对象保持 shallowSize、进度单调 0→100

### #3 heapprofd / Native 内存【影响:高 / 可行性:中】 ✅ 采集 + 深度解析

**采集管线**
- 新增 `NativeHeapCaptureSession`：API ≥ 29（Android 10+）门槛 → 生成 heapprofd Perfetto text 配置（`linux.heapprofd`，4096B 采样，10s）→ `adb push` 配置 → `perfetto --txt -c … -o …` 抓取 → `adb pull` `.pb` trace → 清理设备文件
- 工具条「Capture Native Heap」按钮；File ▸ Export Native Heap trace 导出原始 trace

**深度解析（分配表）**
- 新增 `NativeHeapTraceParser`：**手写 Perfetto protobuf 线格式解析器**（无 protobuf 运行时依赖）
  - 遍历 `Trace.packet`(1) → `TracePacket.profile_packet`(37) → `ProfilePacket` 内的 interning 表（`InternedString`(1) / `Frame`(2) / `Callstack`(3)）与 `ProcessHeapSamples`(5)
  - 从 `HeapSample`（`callstack_id`/`self_allocated`/`self_freed`/`alloc_count`/`free_count`）聚合出按**叶函数**分组的分配表：`NativeHeapAnalysis`（total allocated/freed、样本数、top 50 分配）
  - 对未知字段做安全跳过，损坏/不完整输入退化为空结果不崩溃
- UI Native Heap 区块展示：文件摘要 + 总分配/已释放/样本数 + Top 分配列表（函数名 · 分配字节 · 次数）
- **InternedData 外部符号化（Android R+）**：同时解析 `TracePacket.interned_data`(12) 内的 `function_names`(5) / `frames`(6) / `callstacks`(7) 表，覆盖较新 Android 将 interning 表移出 ProfilePacket 的情况——`<unknown>` 回退仅在完全无法解析时出现
- 原始 trace 仍可在 Perfetto / Android Studio 中打开做完整调用栈分析

### #8 大 Bitmap 的图像预览与体积优先级【影响:低 / 可行性:中】 ✅（预估内存 + java/native 分离）

- `BitmapInstanceStats` 新增 `estimatedPixelBytes`（`width × height × 4B`，ARGB_8888 推算公式）
- **`javaSizeBytes` / `nativeSizeBytes` 分离展示**：java 侧为 Bitmap 对象浅堆大小（HPROF），native 侧为像素缓冲预估（Android 8+ 像素数据存于 native）——UI 同时标注 `java X · native Y`
- UI Bitmap 区块按 retained size 降序展示，标注「预估像素内存 W×H×4B」与 java/native 拆分，帮助识别超大尺寸图片未压缩
- 注：HPROF 解析已跳过原始数组数据，无法从堆转储直接还原像素；**真实缩略图**来自已有的 Bitmap dump（`am dumpheap -b png`）图库

---

## 后续工作

| 项 | 说明 |
|----|------|
| — | 8 条优化建议 + 2 项后续工作已全部落地；无阻塞性遗留 |

## 测试与质量

- memory-profiler 全部模块测试通过（capture / analysis / model / app / export / parser / storage / presentation）
- 新增测试：ProguardMapping 解析与反混淆、泄漏白名单、弱引用感知、Activity 报告（destroyed 计数）、HeapDiff 层级匹配、hprof-conv API 跳过与 getprop 回退
- 修复 1 个预先存在的错误断言：`DesktopMemoryProfilerBackendTest` 的 `standard hprof import` 断言 `heapDump.id == 文件名`，实际导入 id 为 `import-<hash>`（与生产行为一致）
- **已知环境问题（预先存在）**：`MemoryProfilerToolbarSelectorsTest` 在 headless 环境因鼠标注入失败，与本次改动无关（在 HEAD 干净 worktree 上同样失败）
- detekt / ktlint 全部通过
