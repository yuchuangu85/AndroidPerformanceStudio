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

## 三个解析器的生成语料（CI 自动运行）

| 解析器 | Kotlin 导出器 | TS 消费端 | 用例数 |
| --- | --- | --- | ---: |
| HPROF | `HprofGoldenExportTest` | `packages/memory-profiler/src/golden-corpus.test.ts` | 4 |
| SIMPLEPERF | `SimpleperfGoldenExportTest` | `packages/simpleperf-profiler/src/golden-corpus.test.ts` | 3 |
| ART trace | `ArtTraceGoldenExportTest` | `packages/art-trace/src/golden-corpus.test.ts` | 3 |

各解析器覆盖的内容：

- **HPROF**：基础记录、8 字节标识、Android 扩展（`HEAP_DUMP_INFO` 堆名切换）、
  objectId 为 0 的根。
- **SIMPLEPERF**：用生成的 protobuf 类构造记录并按其真实帧格式写出；摘要覆盖记录/样本数、
  丢失样本、事件总数、事件类型、包名、off-CPU 标志、线程名、每条样本的帧符号；
  `dual-clock` 用例带 `trace_offcpu`，`lost-and-unknown` 覆盖缺失文件记录与
  `symbol_id = -1`。
- **ART trace**：流式 v4/v5（双时钟与单时钟）与经典 v2；摘要覆盖时钟源、时间线边界、
  各动作事件数、方法显示名、线程名。

每个导出器写自己的子目录（`hprof` / `simpleperf` / `art-trace`），TS 消费端只读自己的子目录。

### 一个只有检查产物才会发现的坑

第一次三语料 CI 是**绿的**，但下载 artifact 后发现：HPROF 与 SIMPLEPERF 各有一个叫
`basic` 的用例，写在同一个目录里互相覆盖，HPROF 的 `basic.json` 被替换掉了——
消费端因此只检查了 3 个用例而不是 4 个，而作业照样通过。

两处修复：

1. 每个导出器使用自己的子目录（也因此不会再和别的解析器撞名）；
2. 消费端在 `APS_GOLDEN_DIR` 已设置但目录为空时**失败**而不是跳过——「少检查了东西」
   不再可能伪装成绿色。

两条路径都在本地验证过：空目录失败、有语料通过。

## 结论

- 磁盘夹具对照：**已完成**。
- 生成语料对照：**三个已迁移的解析器全部覆盖，在 CI 中自动运行**（HPROF 4 + SIMPLEPERF 3 +
  ART trace 3 = 10 个用例），累计抓到 5 个真实缺陷。
- 人工转录的测试映射（见下节）保留作为行为对照，但不再是唯一证据。

D1 可以视为达成：每个已迁移的解析器都有由 Kotlin 侧产出的期望值驱动、在 CI 中自动运行的
对照证据，并且「语料缺失」会以失败暴露而不是静默跳过。剩余未迁移的解析器（如 Gecko/Firefox
profile、Perfetto protobuf）在迁移时应按同样模式补上导出器。

