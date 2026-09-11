# D2：性能基线（HPROF 流水线，TS 侧）

## 怎么跑

\`\`\`bash
cd electron-viewer
APS_PERF=1 APS_PERF_OUT=perf/hprof-baseline-$(uname -m).json \\
  pnpm --filter @aps/memory-profiler test src/perf.test.ts
\`\`\`

基准默认**不参与 CI**（\`describe.runIf(APS_PERF === '1')\`），不带 \`APS_PERF\` 时显示为 skipped。
同一份测试也是大输入正确性检查：断言实例数、类数、数组数、根数与生成器记录一致。

## 负载

合成堆按真实 Android 应用堆的形状生成（\`src/perf.test.ts\` 的 \`syntheticHeap\`），
两种形状共用同一套节点规模：

| 维度 | 取值 |
| --- | --- |
| 转储大小 | 8,027,504 字节（约 7.7 MiB） |
| 类 | 100 |
| 存活实例 | 200,000（每类 2000） |
| 不可达实例（垃圾） | 2,000 |
| 对象数组 | 1,000 × 16 元素 |
| 基本类型数组 | 2,000 × 256 int |
| GC 根 | 100（每类一个，经引用链覆盖全类） |
| 图节点 | 205,000 |

每个实例 12 字节字段（2 个对象引用 + 1 个 int）。两种形状的区别只在引用链的末端：

- \`chain\`：链在末尾结束（\`i → i+1\`、\`i → i+7\`，最后几个指向 null）——**代表真实堆**；
- \`cyclic\`：链回绕到本类第一个实例，形成 2000 长的环——dominator 定点迭代的最坏情况。

## 结果（2026-09-11）

运行时：Node v24.19.0，darwin arm64。未固定 CPU 频率、未隔离负载，绝对值仅供同机对比。

| 阶段 | chain (ms) | cyclic (ms) | 说明 |
| --- | ---: | ---: | --- |
| \`parseHprof\` | 68.3 | 59.9 | 解析与图形状无关 |
| \`classHistogram\` | 4.2 | 4.6 | |
| \`summarizeMemory\` | 1.0 | 0.9 | |
| \`buildObjectGraph\` | 50.2 | 39.0 | |
| \`reachableFromRoots\` | 68.6 | 61.2 | |
| \`computeDominators\` | 503.8 | **3,923.3** | 7.8× 差异来自环 |
| \`findLeakSuspects\` | 533.4 | 3,922.1 | 内部重算 dominator |
| \`createMemorySession\` | 637.5 | **4,511.9** | 图 + dominator + 泄漏 + 直方图 |

解析吞吐：**2,928,258 实例/秒**。

原始数据：\`electron-viewer/perf/hprof-baseline-macos-arm64.json\`。

## 结论

1. **解析不是瓶颈**。20 万实例 68 ms；\`classHistogram\` + \`summarizeMemory\` 合计 5 ms。
   如果 D2 的闸门只覆盖「解析 + 直方图」，TS 侧余量很大（约 70 ms / 20 万对象）。
2. **瓶颈在 dominator 分析**，而且**对引用环的长度高度敏感**：同样的 205k 节点，
   长环让 \`computeDominators\` 从 504 ms 涨到 3.9 s（7.8×），会话创建从 0.64 s 涨到 4.5 s。
   真实堆里对象图确实有环（父子互指、监听器回指），但环长通常很短；这个数据点的价值在于
   给出了「环长 → 耗时」的敏感度量级，而不是断言真实堆会到 4 秒。
3. **重复计算**：\`findLeakSuspects\` 与 \`createMemorySession\` 各自会重算一遍 dominator
   （\`createMemorySession ≈ buildObjectGraph + computeDominators + findLeakSuspects\`），
   一次会话里同样的工作做了两遍。
4. 因此 D2 的闸门必须**分阶段定义**：解析/直方图一条线，dominator/泄漏另一条线。

## 与 JVM 版本的对照（CI，已完成）

`.github/workflows/golden.yml` 在同一个 job 里先后跑 Kotlin 基准
（`HprofJvmBenchmarkTest`，与 TS 完全相同的合成堆）和 TypeScript 基准，
因此两侧在同一台机器、同一轮 CI 内测量。闸门：`parseHprof` 与
`classSumAggregation` 均需 ≤ 1.5× JVM 时间。

2026-09-11 的绿跑结果：

| 形状 | 阶段 | TypeScript | JVM | 比值 | 闸门 |
| --- | --- | ---: | ---: | ---: | --- |
| chain | `parseHprof` | 244.6 ms | 270.1 ms | **0.91×** | 通过 |
| chain | `classSumAggregation` | 121.6 ms | 88.5 ms | **1.37×** | 通过 |
| cyclic | `parseHprof` | 314.9 ms | 250.9 ms | **1.26×** | 通过 |
| cyclic | `classSumAggregation` | 124.9 ms | 94.2 ms | **1.33×** | 通过 |

### 闸门第一次跑就发挥了作用

首轮带闸门的运行报出聚合阶段 **1.96×**（单次 4–10 ms，被 JIT/GC 噪声主导）。把两侧的
聚合循环放大 20 倍后，差距稳定在 **1.79–1.80×**，说明不是噪声：TypeScript 用
`Map<bigint, ...>` 分组，而 bigint 作为 Map 键的开销远高于 JVM 的
`HashMap<Long, Long>`。真实堆转储里的对象 id 都能放进 double，于是热循环改为按 number
分组，只有超过 2^53 的 id 落到第二个 bigint 键的 map（保持正确性）。本地该阶段从
171 ms 降到 46 ms，CI 上从 1.80× 降到 **1.33–1.37×**。

基准阶段现在调用与直方图相同的 `groupInstancesByClass`，测的是产品代码路径而不是副本。

### 注意

- CI runner 的绝对值在轮次间有波动（同一份代码的 `parseHprof` 见过 218–315 ms），
  所以看的是同一次运行内的比值，不是跨运行的绝对值。
- HPROF 的闸门见上；SIMPLEPERF 与 ART trace 的对照见本文末节。

## 会话分析复用（2026-09-11 追加）

原本的计划是「消除会话创建时重复的 dominator 计算」。实现后测量发现**这个判断是错的**：
`createMemorySession` 从来不自己算 dominator，它只调用 `findLeakSuspects`，而后者算了一次
dominator 和一次可达性，再加上 `computeDominators` 内部自己的一次可达性——重复的是
**可达性遍历**，不是 dominator。

改动：`analyzeGraph(graph)` 一次算出可达性与 dominator，`findLeakSuspects` 接受可选的
`analysis` 参数（不传时自己算，调用方无感），会话把同一份分析传给泄漏排序。

同一轮运行内的 A/B（`sessionBeforeReachabilityReuse` 阶段复现旧路径）：

| 形状 | 复用后 | 旧路径 | 变化 |
| --- | ---: | ---: | ---: |
| chain | 898.5 ms | 1022.6 ms | **−12%** |
| cyclic | 5243.7 ms | 5710.1 ms | **−8%** |

与「省掉一次全图可达性遍历」（实测 71.8 ms chain / 135.3 ms cyclic）的量级一致。
注意：上一轮 A/B 里 chain 方向是反的（657.9 vs 632.7），共享 runner 上单轮差异不可靠，
所以这个对照阶段保留在基准里，用同轮数据而不是跨轮数据说话。

**dominant 成本没有变化**：`computeDominators` 仍是 523 ms（chain）/ 4.9 s（cyclic）,
占会话时间的绝大部分。真正的优化空间在 dominator 定点本身（长环会放大迭代次数），
而不是在调用方重复计算。

## Dominator 算法替换：CHK → Semi-NCA（2026-09-11 追加）

会话时间几乎全在 `computeDominators`，所以直接对它下手。

### 先测量，再动手（测量纠正了两个假设）

给真实堆加上计数器后：

| 形状 | 轮数 | intersect 次数 | **intersect 步数** | 耗时 |
| --- | ---: | ---: | ---: | ---: |
| chain | 2 | 398,600 | 801,000 | ~520 ms |
| cyclic | 4 | 686,000 | **66,636,500** | ~3,900 ms |

- 假设一「环让定点多跑很多轮」——**错**，只多 2 轮（4 vs 2）。
- 假设二「用一个工作列表（worklist）能解决」——**也错**，成本不在轮数或交点次数
  （只差 1.7×），而在**每次 intersect 沿 dominator 树走的步数**（平均 2 步 vs 97 步，差 83×）。
  工作列表版本对此毫无帮助。

这是迭代式 Cooper-Harvey-Kennedy 的固有弱点：`intersect` 的代价与 dominator 树的形状
相关，长环会让它退化。

### 替换为 Semi-NCA

`computeDominators` 改为 Semi-NCA（Georgiadis & Tarjan；LLVM 用的同一套）：
深度优先编号 → 并查集（带路径压缩）求半支配者 → 一趟 NCA 求直接支配者，
复杂度 O(E log V)，与图形状无关。旧实现保留为**仅供测试**的
`computeDominatorsReference`，属性测试在 40 个随机图 + 链/环/菱形上与它逐项比对
（直接支配者与保留大小完全一致）。

### 同一轮内的 A/B

| 形状 | Semi-NCA | 旧 CHK | 加速 |
| --- | ---: | ---: | ---: |
| chain | 608.7 ms | 1606.2 ms | **2.6×** |
| cyclic | 458.0 ms | 8088.7 ms | **17.7×** |

关键不只是快：**环状不再是慢的那种形状**（此前慢 8×，现在两者同量级），
支配者分析变成形状无关。

### 闸门的最新结果

同一次 CI 运行（两个工作流都绿）：

| 形状 | 阶段 | TypeScript | JVM | 比值 |
| --- | --- | ---: | ---: | ---: |
| chain | `parseHprof` | 214.4 ms | 213.9 ms | **1.00×** |
| chain | `classSumAggregation` | 96.5 ms | 84.8 ms | **1.14×** |
| cyclic | `parseHprof` | 192.3 ms | 192.1 ms | **1.00×** |
| cyclic | `classSumAggregation` | 95.4 ms | 90.2 ms | **1.06×** |

解析与 JVM 持平，聚合在 6–14% 以内，全部远低于 1.5× 闸门。

## SIMPLEPERF 与 ART trace 闸门（2026-09-11 追加）

首轮 SIMPLEPERF 闸门报出 **read 2.23×**（206.5 vs 92.6 ms）与 **readAndNormalize 2.84×**
（346.4 vs 121.9 ms）。查下去是三个独立问题，而不是「TypeScript 就是慢」一个原因。

### 1. 闸门在比较两种不同的工作量

`SimpleperfJvmBenchmarkTest` 的 `readAndNormalize` 只是把每条记录喂给
`SimpleperfProfileNormalizer` 并数样本数，**从不物化 profile**；TypeScript 侧调的是
`normalizeSimpleperfReport`，会构造 100000 个 `NormalizedSample` 和 120 万个
`ProfileFrame`。同一个阶段名，两边差一个数量级的工作量，比值没有意义。

现在受闸门的阶段改用与 JVM 逐行对应的流式循环（同一个 `SimpleperfProfileNormalizer`，
丢弃返回值只数样本）。完整 pipeline 作为**不受闸门**的信息项单列：
`normalizeProfile`、`buildCallStackTable`。

### 2. 生成器是二次复杂度，而且测试从没跑到过测量阶段

合成报告生成器为每个字段单独建 `Uint8Array` 再拷进父消息，并且**从不重置 record 消息**：
每次 emit 都把此前所有记录又拷一遍。生成这份 15 MB 报告本身要 150 s（测试总共 152 s），
CI 上 649 s——那次作业看起来「卡死」，实际是生成器在拷贝几百 GB。

改成复用一块缓冲区、每条记录 emit 后重置之后，同一份字节生成 < 1 s，本地测试
**152 s → 1.1 s**（字节数不变，仍是 15 052 649，测试里加了断言钉住）。

### 3. 解码器确实比 protobuf-java 慢，做了三处分配优化

| 问题 | 改法 |
| --- | --- |
| 每个子消息分配一个 subarray + 一个游标（每个样本 13 组） | `ProtoCursor` 改为带 limit 的原地进入/退出（`enterDelimited`/`leaveDelimited`） |
| 每个 varint 字节分配一个 BigInt | ≤ 5 字节的 varint 用 number 累加，只物化一个 BigInt；更长的走原来的 BigInt 路径 |
| 样本对象按 `unwindingResult` 是否存在切换形状 | 固定形状，字段为 `undefined` |
| 每条记录分配一次 payload subarray | `decodeRecord(bytes, offset, end)` 直接在流上解码 |

同一份输入、本机：`read` **87.7 → 32.8 ms**，`normalizeProfile`（完整 pipeline）
**133.3 → 98.4 ms**。

「行为等价」的本地证据：新增 `proto.test.ts`，用保留的通用字段读取器
（`readFields`）作为参考实现，对 300 条随机记录（覆盖全部记录类型、varint 边界值、
未知字段、空 callchain、unwind error）+ 100 条带前后缀的原地解码逐字段比对。
golden corpus 需要 Kotlin 侧产物，本地跑不了，这个差分测试是本地能做的最强证据。

### 4. ART trace 基准从未真正运行过

CI 步骤是两条命令顺序执行，第一条失败就中断，所以 ART 那档从来没有输出——
而它一旦真的运行就**立刻失败**：

- entry block 头部声明 1600 条记录，循环却按 `methodIds.length`（4）只写了 4 条；
- 版本 5 的每条记录还要多一个 CPU time 字段，生成器没写。

两个缺陷都在**测试自己的生成器**里（Kotlin 那份是对的），所以 ART 侧的产品代码
一直没有问题——有问题的是「从未跑过却看起来存在」的基准。修好后本地：63.6 万字节、
102 400 个事件，`parse` 11.7 ms、`parseAndProject` 53.3 ms。

CI 步骤改为**两个包都跑、都报结果**，一次失败不再掩盖另一次；同时三个基准测试在
`APS_GOLDEN_DIR` 已设置但基线读不到时**直接报错**，而不是静默跳过闸门
（此前 `catch { return; }` 会让一条改名的路径悄悄关掉闸门）。

### 5. 修好之后的 CI 结果（2026-09-11，同一 job 内）

| 阶段 | TypeScript | JVM | 比值 | 闸门 |
| --- | ---: | ---: | ---: | --- |
| HPROF chain `parseHprof` | 228.2 ms | 265.3 ms | **0.86×** | 通过 |
| HPROF chain `classSumAggregation` | 115.8 ms | 101.7 ms | **1.14×** | 通过 |
| HPROF cyclic `parseHprof` | 250.5 ms | 246.3 ms | **1.02×** | 通过 |
| HPROF cyclic `classSumAggregation` | 136.9 ms | 108.1 ms | **1.27×** | 通过 |
| simpleperf `read` | 79.9 ms | 76.9 ms | **1.04×** | 通过 |
| simpleperf `readAndNormalize` | 134.5 ms | 109.2 ms | **1.23×** | 通过 |
| ART `parse` | 38.3 ms | 7.6 ms | **5.04×** | 不设阈值（见下） |
| ART `parseAndProject` | 125.7 ms | 86.0 ms | **1.46×** | 通过 |

同一轮里两侧的 golden corpus 比对也全部通过，说明解码器重写没有改变解析结果。

### 6. ART 闸门第二次报错，指向的是产品代码里的真问题

生成器修好后 ART `parseAndProject` 仍报 **1.67×**。查下去不是 BigInt，而是
`toCallStackTable` 对**每个线程**都扫一遍全部事件
（`analysis.events.filter(e => e.threadId === threadId)`）：64 线程 × 102400 事件
= 650 万次谓词调用，全部发生在真正投影之前。改成一次分组后，本地 `parseAndProject`
**44.3 → 18.7 ms**（−58%），20 个投影测试全过——分组保持每个线程内的事件顺序，
所以输出逐字节不变。这不是基准的账，是方法剖析界面每次打开都要付的钱。

### 7. ART `parse` 仍然慢，但它是被报告而不是被断言的阶段

这一阶段几乎全是 64 位整数运算：每条记录三个 bigint 累加（102400 条记录），
而 JVM 用原生 long。把逐字节 BigInt 的 LEB128 读取改成数值快路径后，本地 `parse`
**11.7 → 7.2 ms**（−38%），CI 上仍约为 JVM 的 2–3×。

试过并放弃的改法：累加器用 number、只在构造事件时转 BigInt——反而更慢
（本地 12.7 vs 8.0 ms），因为 `Number(bigint)`/`BigInt(number)` 的转换比直接做 bigint
加法更贵。也就是说剩下的差距不是「多分配了几个对象」，而是 JS 没有 64 位原生整数。

因此 ART 的闸门放在**应用真正跑的 pipeline**（`parseAndProject`，含 parse），
`parse` 仍然每轮打印比值但不断言：PRD 没有给 ART 设阈值，我不为它发明一个数字。
若以后方法剖析的解析成为瓶颈，按 D2 的规则它是 Rust/WASM 的候选模块。

## 下一步

- 为 SIMPLEPERF 与 ART trace 补上同样的 JVM 对照与 golden 导出器（D1/D2 目前只覆盖 HPROF）。
- 把带闸门的基准从「每次 push」改成定时任务或仅在相关路径变化时运行，避免拖慢日常 PR
  （当前 golden 作业约 3 分钟，Gradle 构建占大头）。
- 泄漏排名里的 `referenceChainTo` 对每个可疑对象各做一次 BFS；当前形状下只占几十毫秒，
  但堆更大时可以用一次多源 BFS 换掉。
