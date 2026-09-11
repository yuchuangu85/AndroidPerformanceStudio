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

## 与 JVM 版本的对照（未完成）

原定闸门是「HPROF 解析 + 直方图 ≤ 旧 JVM 实现的 1.5 倍」。要闭合它需要：

1. Kotlin 侧一个可重复的基准入口：对**同一份**字节（把 \`syntheticHeap\` 生成器移植到
   Kotlin 测试，或把生成的转储提交为夹具）跑 \`HprofParser\` + 直方图，输出同结构 JSON；
2. 在同一台机器、同一次 CI 运行里先后跑两侧，避免机器差异；
3. 比较 \`parseHprof\` 与 \`classHistogram\`（闸门阶段），dominator 单独设阈值。

**当前阻塞**：本机无法下载 Gradle 发行版（\`services.gradle.org\` 的 zip 连接超时，
缓存里只有 8.13/8.14.4 而 wrapper 固定 9.5.1），Kotlin 侧跑不起来；CI runner 可以。
与 D1 的 golden 导出器是同一条链路，建议合并成一个 CI 作业。

## 下一步

- 让 \`findLeakSuspects\` 接受外部传入的 dominator 结果，消除会话创建时的重复计算
  （预计 \`createMemorySession\` 从 637 ms 降到约 570 ms，环状情形节省更多）。
- 优化 dominator 定点：先记录迭代次数（长环会放大迭代），再考虑按需分析——只对用户
  展开的子树或前 N 个可疑对象计算保留大小。
- 在 CI 增加 \`APS_PERF=1\` 的定时任务（非每次 push），把 JSON 作为 artifact 存档以追踪回归。
