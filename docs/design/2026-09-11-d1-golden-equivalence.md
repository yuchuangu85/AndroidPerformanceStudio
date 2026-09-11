# D1：Kotlin 等价性验证（Electron 重写）

## 目标

用**同一份输入**驱动 Kotlin 参考实现与 TypeScript 重写，并断言两者给出相同的结论。
D1 的完成标准是：每个已迁移的解析器/分析器都有一条可自动运行的对照证据，且该证据
由 Kotlin 侧产出的期望值（测试断言或生成的 golden 文件）驱动，而不是由 TS 自己定义。

## 已完成：Kotlin 自带的磁盘夹具

Kotlin 仓库里可直接用作跨语言夹具的文件不多，但 HPROF 有一份，且带来源说明与摘要：

| 夹具 | 位置 | Kotlin 期望值来源 | TS 测试 |
| --- | --- | --- | --- |
| `android-converted-sample.hprof` | `desktop-viewer/memory-profiler/parser-hprof/src/test/resources/hprof/` | `HprofParserTest.parses converted Android sample hprof resource without warnings` + 夹具 README 的 SHA-256 | `packages/memory-profiler/src/golden.test.ts` |

TS 测试会：

1. 校验夹具的 SHA-256 与 Kotlin README 记录的一致（防止夹具被替换后对照失效）；
2. 逐条复现 Kotlin 断言：格式 1.0.2、唯一类 `com.example.ConvertedSample`、唯一实例、
   类声明实例大小 24 字节、无告警；
3. 再核对直方图与概要计数。

### D1 已经抓到的真实缺陷

对照这份夹具立刻暴露了三个问题，全部已修：

1. **`STRING` 记录被当成 NUL 结尾**。HPROF 的字符串是「记录长度限定」，Kotlin 读取记录体
   剩余字节；TS 之前一直扫描 `\0`，于是把下一条记录的 tag 吞进类名，之后所有记录全部错位
   （类与实例都解析不出来）。修复后字符串按 `length - idSize` 解码。
2. **`STRING` 记录多写了一个 NUL 的自造夹具**。TS 侧三处测试夹具（`hprof.test.ts`、
   `graph.test.ts`、桌面 `memory-capture-service.test.ts`）都按 NUL 结尾写字符串，
   与 Kotlin 的 `HprofFixtureBuilder` 不一致；夹具与实现「一致地错」，所以此前的单元测试
   全绿。三处夹具已改为长度限定。
3. **浅层大小语义偏差**。Kotlin 的 `HeapInstance.shallowSize` 直接取类声明的
   `instanceSize`，数组头固定 16 字节；TS 之前在实例上叠加了自造的「两个标识宽度」对象头
   估算，数组头也按 `2 × idSize` 算。现在实例与数组的大小都与 Kotlin 一致，
   `histogramIsEstimated()` 也随之改为 `false`（保留大小仍是估算，并在 UI 上标注）。

## Kotlin ↔ TypeScript 测试映射（人工转录的测试向量）

除磁盘夹具外，Kotlin 的单元测试用代码构造输入。这些向量的断言已逐条移植到 TS 测试中：

| Kotlin 测试 | TS 测试 | 覆盖内容 |
| --- | --- | --- |
| `HprofParserTest` | `packages/memory-profiler/src/hprof.test.ts` | 头部、标签、ID 宽度、类/实例/数组、截断、乱序记录、字段与根 |
| `HprofParserTest`（golden） | `packages/memory-profiler/src/golden.test.ts` | 磁盘夹具的字节级对照 |
| `SimpleperfRecordReaderTest` / `SimpleperfReportConverterTest` | `packages/simpleperf-profiler/src/reader.test.ts` | 容器分帧、版本、大小上限、错误码 |
| `SimpleperfProfileNormalizerTest` | `packages/simpleperf-profiler/src/normalizer.test.ts` | 文件/符号/线程/事件解析、执行类型、展开错误 |
| `CallStackContractsTest` / `CallStackTransformerTest` | `packages/profile-analysis/src/pipeline.test.ts` | 变换、过滤、调用树、行布局 |
| `CallTreeProjectorTest` / `FlameGraphRowProjectorTest` | `packages/simpleperf-profiler/src/analysis/analysis.test.ts` | 权重累积、兄弟排序、行几何 |
| `ArtTraceParserTest` / `ArtTraceAnalysisProjectionTest` | `packages/art-trace/src/projector.test.ts` | 两种 trace 格式、区间重采样、热点方法 |
| `MethodTraceCaptureSessionTest` | `packages/art-trace/src/capture.test.ts` | 采集阶段、错误码、清理 |
| `SourceWorkspaceIntegrationTest` | `packages/source-workspace/src/source-workspace.test.ts` | 索引、证据解析、置信度降级、内容寻址缓存 |

人工转录的**弱点**很明确：如果 Kotlin 侧改了行为而没人同步 TS 测试，对照不会失败。
这正是下一节要补的。

## 已完成：由 Kotlin 生成的 golden 语料（CI 自动对照）

`desktop-viewer/memory-profiler/parser-hprof` 的测试现在同时是导出器：
`HprofGoldenExportTest` 用现有的 `HprofFixtureBuilder` 构造 5 个用例，把字节写成
`<case>.hprof`，并把**它自己解析出来的结论**写成 `<case>.json`：

| 用例 | 覆盖 |
| --- | --- |
| `basic` | 字符串、类、实例引用、基本类型数组 |
| `eight-byte-ids` | 8 字节标识、long 字段、对象数组 |
| `android-extensions` | `HEAP_DUMP_INFO` 堆名切换、Android 扩展根、no-data 数组 |
| `null-root` | objectId 为 0 的根（参考实现会丢弃） |

TypeScript 侧 `packages/memory-profiler/src/golden-corpus.test.ts` 解析同一份字节，
逐字段比对：format、id 宽度、类名集合、实例数、实例与数组的浅层大小、堆名集合、
根数量、告警数量。没有语料时该套件跳过，本地开发不受影响。

CI 作业 `.github/workflows/golden.yml` 在同一个 job 里先跑 Kotlin 导出器，再跑
TypeScript 对照，并把语料与两侧基线作为 artifact 上传。**当前状态：绿**，
5/5 用例通过。

### D1 抓到的真实缺陷（累计 5 个）

前 3 个由磁盘夹具发现（见上一节）。语料对照又发现 2 个：

4. **objectId 为 0 的 GC 根**：Kotlin 用 `if (objectId != 0L)` 丢弃，TypeScript 之前把它
   计入了根集合。现在两侧一致，`null-root` 用例持续盯住这个行为。
5. **`HEAP_DUMP_INFO (0xFE)` 完全没有处理**：TypeScript 把它当成未知子标签，
   于是**停止解析整个 heap segment**，该用例的类与实例全部丢失。现在会切换当前堆并按
   Kotlin 的 `normalizeHeapName` 归一化（App/Image/Zygote/Default），结果新增
   `heapByObjectId`，语料也比对堆名集合。

这两个都属于同一类问题：自造夹具里永远不会出现的记录。

## 结论

- 磁盘夹具对照：**已完成**，抓到 3 个缺陷。
- 生成语料对照：**已完成并在 CI 中自动运行**，又抓到 2 个缺陷，5/5 用例通过。
- 人工转录的测试映射（上一节）仍然保留，作为生成语料之外的行为对照。

D1 现在可以视为达成：每个已迁移的 HPROF 行为都有由 Kotlin 侧产出的期望值驱动、
在 CI 中自动运行的对照证据。SIMPLEPERF 与 ART trace 的同类导出器尚未建立，
它们的对照目前仍依赖人工转录的测试向量。
