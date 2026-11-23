# 重构之后：为什么把桌面端换成 Rust + GPUI Kit，以及一次可复核的优化专项

> 2026-11-22 · 依据 2026-11-18 的 HPROF 三方实测、迁移执行记录与对等审计整理。
> 文中所有倍数与毫秒都绑定同一份输入、同一台机器与明确的计时边界；不构成全平台、全功能或发布验收结论。

## 一、先把结论说清楚

这次重构的目的不是"换一门语言"，而是把 Android 性能分析从"能采、能看"推进到**可测量、可复核、可复用**：同一份证据要能被桌面端、无界面 CLI 与 MCP 以同一种语义解释，每一次变快都要说得清快在哪里、代价是什么、边界在哪。

四个可以直接引用的事实：

- **三方导入耗时**（同一份 `launcher.hprof`，184,442,700 字节，完整数据就绪）：Compose v0.7.5 **49.585 秒**｜Android Studio 默认 TraceProcessor **18.527 秒**（`loadTrace` + `heap overview` 两次 RPC；含 native daemon 启动 18.576 秒）｜当前 Rust **3.318 秒**。对 Compose 是 **14.94 倍**、耗时减少 93.3%，每次约省 46.27 秒；对 Studio 同轮的 Rust 3.259 秒，任务路径比约 **5.69 倍**。
- **核心分析入口峰值 RSS**：Compose 4.344 GiB → Rust 0.931 GiB，**低 78.6%**；Studio 默认 TP 的 native daemon 为 6.173 GiB（不含 IDE 与 Java 客户端）。
- **安装包体积**：macOS arm64 DMG **198.1 MB → 51.6 MB（−73.9%）**，Windows x64 MSI **165.0 MB → 47.4 MB（−71.3%）**，Linux x64 DEB **161.9 MB → 45.1 MB（−72.1%）**。
- **优化专项自身**（优化前 / 优化后同一套 Rust 链路、7 次配对样本）：完整数据与 Presenter 就绪 **6,436.442 ms → 3,155.108 ms，2.040 倍**。

同时必须说清边界：HPROF 数字是**后端数据就绪**，不是首帧；只覆盖 HPROF、一个文件、一台 Apple M5 Pro / 64 GiB / macOS 27.2.1。Studio 的两次 RPC 不是等工作量比较——TP 不执行 APS findings，也不能把 5.69 倍读成"所有操作都快 5.69 倍"。体积对比取 GitHub Releases 上同架构、同格式的安装包，降幅来自运行时形态与打包内容的变化，不是同等内容的压缩率提升。

## 二、重构之前，真正的问题是什么

### 1. 运行成本先于体验成为瓶颈

Compose 桌面端在同一份文件上的完整导入接近 50 秒，核心进程峰值 RSS 4.3 GiB（`-Xmx4g`），解析之后的构图、dominator、逐 heap 聚合才是时间大头：`MemoryDeepAnalyzer.analyze` 单项中位数 37.273 秒。用户的直觉是"导入慢"，工程上的事实是**分析阶段慢，而且慢在无法单独引用的一整段里**。

### 2. 能力边界容易被"看起来能用"吞掉

性能工具的诚信取决于缺失语义：没有 Fully Drawn 事件就不该有 TTFD，没有 Native 值就不该显示 0，没采到就不该用参考实现的数字补齐。旧结构里，UI、分析器与导出各自解释同一份数据，边界只能靠约定维持。

### 3. 引擎与界面耦合，优化无从下手

解析、建图、聚合、编码、呈现串在一条链上，"变快"只能靠整体重跑对比。无法回答：是读取慢、构图慢，还是重复物化慢？没有可分离的阶段，就没有可复核的优化。

### 4. 交付形态受限

没有桌面窗口就不能分析，意味着 CI、脚本与 Agent 工作流只能另写一套逻辑——第二套实现，就是第二套口径。

## 三、重构做了什么

**技术栈与规模。** 桌面端改为 Rust 2024 + GPUI Kit 0.9.0（固定版本），工作区 29 个 crate，`crates/` 下 492 个 `.rs` 文件。迁移合并本身涉及 2,627 个文件、+306,844 / −304,786 行，并在 2026-11-17 移除旧的 `desktop-viewer` 树。

**边界写进 CI，而不是写进注释。** `scripts/check_rust_architecture.py` 读取 Cargo 元数据，为每个 workspace 成员与每条**直接生产依赖边**指定具名所有者；`#[path]`、`include!`、`include_str!` 跨 crate 借用源码会被拒绝；共享的 hash / 编码原语归 `aps-primitives`，不允许分析器各自实现。规则说明见 [rust-desktop/ARCHITECTURE.md](../../rust-desktop/ARCHITECTURE.md)。

**页面边界与第二组合根。** `aps-app` 中每个导入目的地持有独立 workspace 状态，外壳只做组合与路由；`aps-cli` 是第二个组合根，桌面与 CLI/MCP 共用同一批 analyzer 与证据契约，而不是复制分析逻辑。

**迁移纪律。** 迁移不是"一次性切换"：[MIGRATION.md](../../rust-desktop/MIGRATION.md) 把领域回归、同输入对齐、原生交互、设备执行、性能、打包与平台验收分开记录；编译通过、测试通过、截图通过都不等于对应门禁关闭。[FUNCTIONAL_PARITY.md](../../rust-desktop/FUNCTIONAL_PARITY.md) 保留了仍开放的 Compose 对等项，并明确"被禁用的按钮、一条导航路由、一次成功导入都不能证明工作流已实现"。

## 四、重构带来的收益

| 指标（同一文件、同一机器） | Compose v0.7.5 | 当前 Rust | 结论与边界 |
| --- | --- | --- | --- |
| 完整数据就绪 | 49.585 s | 3.318 s | 14.94 倍，−93.3%；含 findings 与 Presenter，不含首帧 |
| 纯解析入口 | 3.503 s | 0.912 s | 3.84 倍 |
| 核心投影就绪 | 44.592 s | 2.917 s | 两者工作量并不完全相同，报告不为此推导比值 |
| 核心入口峰值 RSS | 4.344 GiB | 0.931 GiB | 低 78.6%；单进程入口，不是整个产品进程树 |
| Android Studio 默认 TP（参考） | 18.527 s（两次 RPC 合计） | 3.259 s（同轮） | 路径比约 5.69 倍；TP 不做 APS findings，不是等工作量跑分 |

比数字更耐用的是三类结构性收益：

1. **一套分析、多个入口。** 桌面端与 CLI/MCP 共享 analyzer 与证据契约，"命令行结论与界面结论不一致"从设计上被消除。
2. **证据边界被强制而不是被呼吁。** 缺失就保持缺失；完整快照与 findings 不被 pending / 0 替代；来源身份检查贯穿读取，优化不以删除校验为代价。
3. **发布形态收敛。** 安装包捆绑校验过的 Trace Processor 与应用托管的 ADB，Release 只从自身的 `resources/` 加载工具，不搜索 `PATH`、不假设开发 checkout 存在（[rust-desktop/README.md](../../rust-desktop/README.md)）。

### 安装包体积

| 平台 / 格式 | 0.7.5（Compose） | 0.8.2（Rust） | 变化 |
| --- | --- | --- | --- |
| macOS arm64 · DMG | 198.1 MB | 51.6 MB | −73.9% |
| macOS arm64 · PKG | 187.3 MB | 46.5 MB | −75.2% |
| macOS x64 · DMG | 200.4 MB | 54.2 MB | −72.9% |
| macOS x64 · PKG | 189.3 MB | 48.6 MB | −74.3% |
| Windows x64 · MSI | 165.0 MB | 47.4 MB | −71.3% |
| Linux x64 · DEB | 161.9 MB | 45.1 MB | −72.1% |
| Linux arm64 · DEB | 160.3 MB | 44.3 MB | −72.3% |
| Linux x64 · RPM | 183.1 MB | 55.3 MB | −69.8% |
| Linux arm64 · RPM | 181.0 MB | 54.5 MB | −69.9% |

同一平台、同一格式的安装包普遍小到原来的约 1/3 至 1/4（3.3–4.0 倍），Windows 与 Linux 的降幅集中在 70% 上下。这个结果**不是靠删功能换来的**：新包仍随包分发固定版本的 Trace Processor，并新增了 Firefox Profiler 资源，同时不再携带 JVM/Compose 运行时——发布工作流写入应用包的只有 `aps-app`、`aps-memory-worker` 与 `resources/`（[aps-desktop-release.yml](../../.github/workflows/aps-desktop-release.yml)）。

因此体积收益的性质和性能收益一样：**换的是形态，不是删减**。旧形态为运行一整套 JVM 桌面栈付固定成本，新形态把这份成本换成了按平台编译好的原生二进制；包内资源集合也完全变了，所以不能用压缩率解释这 70%。

## 五、优化专项：把"能跑"做成"可解释的快"

重构给了可分离的阶段，优化专项才有下手的地方。这次专项以 HPROF 导入为切口，原则是**只优化重复工作与不必要的物化，不动语义与校验**。

### 1. 先定口径，再谈收益

三组实验回答三个不同问题，不能互相借样本：

- **A 组**：从选定文件到完整数据就绪（host hash、worker open、完整 typed snapshot、传输/解码、Presenter）。
- **B 组**：核心阶段（`Heap::open`、`Engine::new`、histogram、findings），与 Compose 的 `parse`、`analyze`、逐 heap 聚合对照。
- **C 组**：Android Studio 默认 TraceProcessor 的真实 `loadTrace` + `heap overview` RPC。

每组 1 次丢弃预跑 + 3 次正式样本、轮换顺序；已有 OS 页缓存，不清缓存，因此不声明冷盘结果；n=3，不推导置信区间。优化前后的配对实验则是另一套：2 次预热 + 7 次 AB/BA 轮换，只报中位数。

### 2. 七项机制

| # | 机制 | 保留的约束 | 收益证据 |
| --- | --- | --- | --- |
| 1 | 紧凑记录 + 惰性字段：建图只取引用 ID，字段与数组值在详情查询时再读 | 字段详情、数组值、独立来源检查全部保留 | 反映在 0.912 s 解析与 2.382 s 引擎就绪中，未单独拆分 |
| 2 | 缓存继承字段布局，批量扫描建图所需引用 | 批次按对象数与字节数检查来源；静态/数组/弱引用语义不变 | 同上，属于链路整体收益 |
| 3 | Native 归属按文件偏移批量读取，复用已算结果 | 继承/shadowed 字段、first-non-null、missing 与 zero、last-Cleaner-wins 全部保留 | 组件级配对实测 1,713.625 ms → 82.116 ms，约 20.87 倍 |
| 4 | 使用系统加速 SHA-256（macOS CommonCrypto），其他平台保留 portable 实现 | host 与 worker 共三次完整 hash、文件句柄与路径身份检查不动 | host hash 431.218 ms → 61.984 ms；本轮 A 组实测 65.194 ms |
| 5 | 多 heap histogram 共享一次节点扫描与 dominator 遍历；retained summary 直接累加可见根 | class object 计入、映射类分组、heap-local 祖先抑制、未知值保持 | 本轮共享 histogram 中位数 263.283 ms；不把单次诊断当统计结论 |
| 6 | 有界投影缓存（32 MiB / 64 项）+ 最终响应只编码一次 | cache hit 仍校验来源与图；不落盘；预算、超时、取消保留 | 完整 snapshot 1,124.816 ms → 723.655 ms；不单独归因给某项缓存 |
| 7 | UI 共享不可变分类树，缓存可见路径与警告标记 | 刷新、重置、展开覆盖、默认 heap 可见性都会正确失效 | 有缓存/失效回归，但没有测出一帧渲染提升 |

此外还有两项**正确性**修复，被有意排除在百分比之外：mapping 重载绑定 workspace / load generation 与 snapshot ID，成功后才替换会话；dominator 请求之间不再互相作废，快照恢复只补缺失页。它们的时间收益未独立测量，因此不写预期数字。

### 3. 专项的整体收益

| 指标（优化前后配对，7 次） | 优化前 | 优化后 | 结论 |
| --- | --- | --- | --- |
| 完整数据 / Presenter 就绪 | 6,436.442 ms | 3,155.108 ms | 2.040 倍，−50.981%，省 3,281.334 ms |
| Host 完整 SHA-256 | 431.218 ms | 61.984 ms | 保留全部读文件与身份检查 |
| Worker open | 4,876.753 ms | 2,367.103 ms | 含解析、Native、图、retained、索引与 worker hash |
| 完整 Snapshot | 1,124.816 ms | 723.655 ms | 含编码、传输、typed 解码与 fallback |

关键之处在于：这些收益**没有以删除校验、延迟 findings 或跳过来源验证为代价**。快到这里，恰好证明了重构把"速度"和"可信"拆成了两个可以同时满足的维度。

## 六、正确性如何不被优化牺牲

"前后结果一致"只证明行为被保留，"独立参照一致"才提供额外数值证据。两类证据都记录了：

- **13 组全量分析数组指纹**：2,501,406 节点、6,615,064 引用边、390,650 raw roots，retained / immediate dominators / path / native / shallow 全部一致——不是节点抽样。
- **2,098 项旧/新 worker API 精确比较**：五类代表对象全部分页共 493,686 行、495,672 个可见 dominator 根、490 条 findings 行，含筛选、排序与详情查询。
- **独立 perflib 对照**：3 个 heap、21 个重叠 scope、210 项标量与 63 组 ID-keyed 向量指纹，无差异。
- **回归**：136 项核心回归通过；此前修复后的 workspace 1,847 通过、0 失败，另有 124 项 Memory 回归。
- **信号与结论分离**：76 个 leak suspects、372 个 Bitmap 是分析信号，不是已证明的泄漏。

还有一个值得单独写的细节：App shallow 在三套实现里分别是 104,666,905（Rust / legacy perflib）、109,063,260（Compose v0.7.5）、109,553,013（Studio TP class-overview）字节。它们由不同的 sizing 规则产生，报告给出了 4,886,108 与 −489,753 的可复算分解，但**没有把任何一方"拉齐"成看起来一致的黄金值**。这就是证据纪律：解释差异，而不是抹平差异。

## 七、这套方法论对后续优化的约束

专项沉淀下来的规则，比任何一次数字都更有复用价值：

- **就绪 ≠ 首帧。** 报告不估算首帧；Presenter 时间不被当作渲染速度。
- **阶段中位数不可相加。** 总时间来自完整样本，不由各阶段推导。
- **单次样本不作为加速结论。** 375.583 ms vs 244.394 ms 这类单次值只作为诊断记录。
- **不同组的样本不可挑最小值混算。** A/B/C 三组各自独立。
- **不把参考实现的值填进本实现的缺失字段。** 未知保持未知，已知 0 与缺失严格区分。
- **红灯不被掩盖。** 既有 `collapsible_if` 警告、历史 `launcher_parity` 断言与失败测试都如实保留，没有为了让 CI 变绿而改写期望值。

下一轮已经排好的优先项同样克制：原生导入首帧的同钟计时；不同大小、4/8 字节 ID、多 classloader、异常文件与冷盘/已运行 JVM 的更广输入；完整进程树（host、worker、daemon、JVM）的并发 RSS；以及继续分离 graph / retained / snapshot 编码的 CPU、分配与拷贝——每一项都要求单变量修改并复用同一套等价校验。

## 八、结语

重构的价值不在于"Rust 比 Compose 快"。它在于：

1. 把一条无法拆解的性能链路，拆成了可以分别测量、分别优化、分别验证的阶段；
2. 把"证据边界"从文档约定变成了 CI 与数据契约；
3. 让同一份分析被桌面、CLI 与 MCP 共享，而不是各自维护一套口径；
4. 让优化第一次可以用"前后等价 + 独立参照"来证明自己，而不是靠一次观感良好的跑分。

14.94 倍是一次具体实验的结论；可复算、可复核、可被下一个专项继承的证据链，才是这次重构真正的长期收益。

---

**证据与复现**

- [HPROF 三方性能对比与重构报告（2026-11-18）](../records/memory/hprof-reconstruction-comparison-2026-11-18.html) — 本机三方实测、阶段拆解、RSS、正确性与边界，内嵌 JSON 可离线复算。
- [rust-desktop/HPROF_IMPORT_PERFORMANCE.md](../../rust-desktop/HPROF_IMPORT_PERFORMANCE.md) — 优化前后 7 次配对实验、组件计时、保留行为与实现位置。
- [rust-desktop/MIGRATION.md](../../rust-desktop/MIGRATION.md)、[FUNCTIONAL_PARITY.md](../../rust-desktop/FUNCTIONAL_PARITY.md) — 迁移门禁、Compose 对等审计与仍开放的工作流。
- [rust-desktop/ARCHITECTURE.md](../../rust-desktop/ARCHITECTURE.md) — crate 边界、CI 强制规则与双宿主结构。
- [GitHub Releases](https://github.com/yuchuangu85/AndroidPerformanceStudio/releases) — 0.7.5 与 0.8.2 的安装包体积取自实际发布资产，同架构、同格式逐项对比。
