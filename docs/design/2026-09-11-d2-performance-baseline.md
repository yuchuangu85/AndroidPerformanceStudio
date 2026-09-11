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
- 目前只有 HPROF 有 JVM 对照；SIMPLEPERF 与 ART trace 的基准尚未建立。

## 下一步

- 让 `findLeakSuspects` 接受外部传入的 dominator 结果，消除会话创建时的重复计算
  （chain 形状 `createMemorySession` 637 ms 中约 500 ms 是 dominator）。
- 为 SIMPLEPERF 与 ART trace 补上同样的 JVM 对照与导出器。
- 把带闸门的基准从「每次 push」改成定时任务或仅在相关路径变化时运行，避免拖慢日常 PR
  （当前 golden 作业约 3 分钟，其中 Gradle 构建占大头）。
