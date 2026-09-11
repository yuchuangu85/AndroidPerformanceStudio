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

## 未完成：由 Kotlin 生成的 golden 语料

要把「人工转录」升级为「自动对照」，需要让 Kotlin 侧把**程序构造的输入字节**连同它的
解析结论一起导出，TS 侧只负责消费：

1. 在 `desktop-viewer/memory-profiler/parser-hprof/src/test/` 增加一个 golden 导出测试：
   用现有的 `HprofFixtureBuilder` 构造若干用例，把字节写入
   `build/golden/<case>.hprof`，把解析结论（类名、实例数、浅层大小、GC 根、字段引用、
   告警）写成 `<case>.json`。SIMPLEPERF 与 ART trace 各加一份同样结构的导出器。
2. 把导出的文件复制到 `electron-viewer/golden/`，纳入版本控制。
3. TS 侧增加语料运行器（`electron-viewer/packages/*/src/golden.test.ts` 的扩展）：
   遍历 `golden/*.json`，按 `parser` 字段选择实现，逐字段比对。
4. 在 CI 中固定这条链路：CI 里网络可用，因此 Gradle 侧可以跑导出器；
   本地受限时语料保持只读。

**当前阻塞**：本机无法下载 Gradle 发行版（`services.gradle.org` 的 zip 连接超时），
因此第一步的导出器还没跑起来；`~/.gradle/wrapper/dists` 只有 8.13/8.14.4，而
`desktop-viewer/memory-profiler` 的 wrapper 固定 9.5.1。CI（GitHub runner）不受此限制。

## 结论

- 磁盘夹具对照：**已建立**，并且已经产生实际收益（三个缺陷）。
- 生成语料对照：**未完成**，需要 Kotlin 侧导出器 + CI 运行。
- 在生成语料落地之前，D1 不应标记为完成。
