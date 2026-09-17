# Memory Profiler：Android Studio 风格 HPROF Viewer 设计

**状态**：拟实施
**日期**：2026-09-17
**范围**：Compose Desktop `memory-profiler` 复合构建
**决策类型**：产品交互、数据模型与分析管线演进设计

## 1. 目标

将现有 Memory Profiler 演进为一个以 **Android 兼容 HPROF 堆转储** 为核心的离线调查工作台。用户可以导入或抓取快照、验证输入、分析对象图、比较多个快照、定位保留链，并导出可复核的调查证据。

目标体验参考 Android Studio 的 Heap Dump Viewer 的信息架构，而不是复制 IDE 外观或承诺相同的全部实时采集能力。

### 1.1 成功定义

一次成功的离线调查必须能完成以下闭环：

```text
导入或抓取 HPROF
→ 显示解析/分析阶段及警告
→ 选择快照与过滤范围
→ 类 / 支配树 / Diff 中定位候选对象
→ 查看实例、字段、入向引用和 GC Root 链
→ 固定或比较调查对象
→ 导出原始证据与结构化结论
```

### 1.2 非目标

以下能力不属于单个 HPROF 文件可证明的范围，不能以“完整 HPROF Viewer”名义虚构：

- 实时 Java/Kotlin allocation/deallocation 时间线。
- allocation call stack、火焰图或分配方法归因。
- 实时 Heap 曲线、GC 事件序列和采集过程中的 dump marker。
- 跨快照稳定对象身份、逐对象生命周期或销毁结论。
- 通用 Java 对象与 Native allocation 的精确一一关联。
- 编辑、过滤后重新序列化为等价 HPROF 的能力。

这些能力需要独立的设备端实时采集协议和时间序列数据模型，后续以“Live Memory Recording”项目处理，不与离线 HPROF Viewer 混合实现。

## 2. 当前基础与边界

现有实现已经拥有离线 HPROF 调查所需的大部分底层能力：

- `HprofParser` 解析 HPROF header、heap segment、类、实例、对象数组、基本类型数组、GC root 与解析警告。
- `DominatorTreeAnalyzer` 计算可达性、立即支配者和 retained size。
- `InstanceReferenceQuery` 查询类实例、字段、入向引用、数组元素和 GC Root 引用链。
- `MemoryHistogramAnalyzer` 提供类直方图、heap filter 与尺寸聚合。
- `MemoryProfilerController` 已负责导入、抓取、mapping、会话恢复、导出和类/实例选择。
- `MemoryProfilerClassListView` 已提供类、实例、字段、引用链等基本 UI。
- `MemorySessionStore` 已保存最近会话元数据。

现有边界必须保留：

1. **堆转储证据边界**：对象 ID 只在当前 dump 内有效，不得作为跨快照对象身份。
2. **内存保留证据边界**：引用链、支配关系与 retained size 是可复核证据；它们可形成泄漏嫌疑，不等同于已证明泄漏。
3. **Native Heap 边界**：`heapprofd` / Perfetto 原生堆轨迹是独立证据通道，不能伪装为 Java HPROF 的对象引用图。
4. **输入兼容边界**：第一阶段支持 Android / Android Studio 兼容 HPROF；对任意 JVM 或 HotSpot HPROF 不作无条件兼容承诺。

## 3. 用户与核心场景

| 用户 | 场景 | 完成条件 |
| --- | --- | --- |
| Android 开发者 | 导入应用抓取的 HPROF，定位占用最大的对象类型 | 能过滤 Heap、按 retained size 排序、打开实例与引用链。 |
| 性能工程师 | 比较两次 Heap Dump，确认类级增长 | 能固定两个快照、查看可信的类级增量并导出 CSV/JSON。 |
| 泄漏排查者 | 调查 Activity/Fragment 或自定义对象的保留路径 | 能从候选类跳到实例、字段、入向引用和 GC Root 路径。 |
| 发布/测试人员 | 保存可复核诊断证据 | 能导出原始 HPROF、Mapping 关联、分析报告和导入警告。 |

## 4. 功能需求

### FR-1：输入、导入与会话

1. 支持从文件菜单导入 `.hprof`，并明确显示输入类型、对象 ID 宽度、文件大小、解析警告和可用能力。
2. 支持现有 Android 设备 Java Heap Dump 抓取，并保留 raw / converted 源文件身份。
3. 支持将 `mapping.txt` 关联到具体快照；映射必须记录其文件摘要和关联快照，避免误用到不匹配构建。
4. 支持最近会话恢复；会话恢复后必须重新验证外部 HPROF 与 mapping 的可用性，而不是仅依赖历史路径。
5. 导入流程必须可取消。取消后不发布半成品 session，也不得覆盖当前活动快照。

### FR-2：快照工作区

1. 支持多个已加载快照，以 tab 表示，最多保留可配置数量的活动快照。
2. 每个 tab 显示：快照名称、来源、加载时间、对象/类数量、解析警告数量与可用分析能力。
3. 一个快照只能有一个活动视图：`Classes`、`Dominators`、`Diff` 或 `Leaks & Bitmaps`。
4. 快照关闭只清理可重建的内存索引与 UI 选择状态；不得删除用户原始 HPROF、mapping 或已导出的证据。
5. Diff 明确标记为**类级比较**，不得将不同 dump 中的相同 object ID 呈现为同一对象。

### FR-3：对象图调查

1. Classes 视图支持 heap、scope、leak filter、搜索、大小写匹配、正则与 class/package grouping。
2. 类表必须至少显示实例数、shallow size、retained size、native size（有证据时）和增长/差异信息（在 Diff 视图中）。
3. 选择类后显示实例表；选择实例后显示对象 ID、depth、可达性、shallow/retained/native size、字段、入向引用和 GC Root 路径。
4. 字段和入向引用中的对象目标必须可点击跳转；跳转生成浏览历史，提供后退、前进、固定对象和复制 object ID。
5. 数组必须分页或按范围加载。现有展示上限不能被默认为完整数组证据。
6. Dominators 视图必须复用既有分析结果，提供可展开树、retained size、直接支配者与跳转到实例的入口。

### FR-4：可解释的辅助分析

1. Activity/Fragment 泄漏、重复 Bitmap 与其他启发式结果必须显示规则来源、置信/限制与支持链。
2. 任何“Leak”文案须区分“嫌疑”与“已证实”；不能将单一 retained path 表述为确定根因。
3. Native Heap、Java HPROF 和 Bitmap 原生大小估计必须使用不同的证据标签。
4. 对没有 allocation trace 的 HPROF，allocation/deallocation、allocation method、call stack 字段必须显示为“不适用”或“不在该证据中”，不能填造数值。

### FR-5：导出

1. 保留原始 HPROF 与已转换 HPROF 的字节复制导出。
2. 提供类直方图、Diff、泄漏嫌疑、选中类实例、选中对象详情与引用链的 CSV/JSON 导出。
3. 提供 HTML 或 Markdown 调查报告，包含来源、摘要、过滤器、警告、分析版本、导出时间与引用对象 ID。
4. 每个结构化导出必须标识：快照 ID、源文件摘要、mapping 摘要（若有）、分析算法版本和证据限制。
5. 第一阶段禁止导出“重写后的 HPROF”；只有实现严格 HPROF writer 并完成兼容验证后才能开放。

## 5. 非功能需求

### NFR-1：正确性与证据完整性

- 解析未知 record、截断、无效 ID size、缺失转换器或不支持输入时，必须给出结构化错误/警告。
- 不可达对象、GC Root 路径缺失、Native Size 缺失和映射缺失必须显式呈现。
- 分析结果必须携带算法/输入版本，避免旧 session 混用新规则的结果。

### NFR-2：性能与资源控制

- 导入、解析、Dominator 分析、Diff 与大型导出必须脱离 Compose UI 线程。
- 每个长任务都必须有阶段名、进度、耗时、取消行为和失败恢复策略。
- 第一阶段对全量内存图保持现有实现；第二阶段引入磁盘索引、延迟对象细节与分页，以支持大 Heap Dump。
- 对超出当前可安全处理阈值的输入，先预检并提示风险，不以 OOM 作为流控机制。

### NFR-3：交互与可访问性

- 键盘支持 Tab 选择、表格行选择、Enter 打开对象、Cmd/Ctrl+C 复制对象 ID、Cmd/Ctrl+[ / ] 历史导航。
- 加载、警告、失败和取消结果统一在底部状态栏呈现；耗时任务另有可展开的任务详情。
- 所有状态图标和颜色均有文本含义；错误、警告、普通状态不能仅依赖颜色区分。

### NFR-4：兼容性与安全

- 不新增网络上传、云端解析或自动共享行为。
- 原始 HPROF、mapping、导出报告属于本地敏感证据；默认路径和日志不得泄露完整字段值或应用私有数据。
- 所有外部工具调用（如 `hprof-conv`）必须经过已存在的受控工具链边界，并记录命令结果而非猜测成功。

## 6. 目标信息架构与视觉设计

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│ File / Import / Export / Mapping / Recent / Compare                          │
├─────────────────────────────────────────────────────────────────────────────┤
│ [ Snapshot A ● ] [ Snapshot B ! ] [+ Import]       Search / Global filters  │
├───────────────┬───────────────────────────────────┬─────────────────────────┤
│ Investigation │ Main analysis                      │ Object inspector        │
│               │                                   │                         │
│ Heaps         │ Classes | Dominators | Diff | Leak │ Object ID / class       │
│ Scope         │                                   │ Size / depth / reach.   │
│ Leak filters  │ Virtualized table                  │                         │
│ Saved filters │ Frozen class column                │ Fields                  │
│               │ Configurable metrics               │ Inbound references      │
│               │                                   │ GC Root paths           │
│               │                                   │ History / pin / copy    │
├───────────────┴───────────────────────────────────┴─────────────────────────┤
│ Status: phase · progress · source · warnings · elapsed · cancel              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 6.1 布局规则

- 页面根容器使用正常工作区背景；表格、检查器和独立分析区使用语义化 card/panel 背景。
- 类直方图和主表区采用全宽卡片内容区，避免在卡片内部重复增加四周边距。
- 左栏宽度可拖拽并记忆；右侧检查器可折叠、固定或弹出为独立检查窗口。
- 主表使用虚拟化列表；保持首列可见，避免大类名在横向滚动中失去上下文。
- 不通过模糊、透明叠层或 IDE 像素级仿制获得“Android Studio 风格”；以层级、密度、选择、表格和导航行为对齐。

### 6.2 状态模型

```kotlin
data class MemoryWorkspaceState(
    val snapshots: List<HeapSnapshotSummary>,
    val activeSnapshotId: HeapSnapshotId?,
    val comparison: HeapComparisonSelection?,
    val activeView: MemoryWorkspaceView,
    val investigation: InvestigationState,
    val task: HeapTaskState,
    val status: MemoryStatus,
)
```

建议新增的概念：

- `HeapSnapshotId`：会话内快照身份，不复用对象 ID。
- `HeapSnapshotSummary`：来源、文件摘要、分析能力、警告、加载状态。
- `HeapTaskState`：`Probe`、`Convert`、`Parse`、`Index`、`Analyze`、`Persist`、`Export`、`Cancelled`、`Failed`。
- `InvestigationState`：当前 class、instance、pinned objects、history、active filters、table configuration。
- `HeapComparisonSelection`：两个快照与 class-level comparison method。

## 7. 模块与实现边界

| 模块 | 责任 | 主要变更 |
| --- | --- | --- |
| `memory-model` | 证据模型和稳定状态类型 | 快照身份、能力矩阵、任务阶段、导出元数据、对象导航记录。 |
| `parser-hprof` | 格式预检、解析与兼容报告 | `HprofInputProbe`、结构化兼容/警告、输入大小策略；不在此层实现 UI。 |
| `analysis-memory` | 索引、Dominator、Diff、启发式 | 支配树查询 API、分页 array/object 查询、明确 class-level diff 限制。 |
| `storage-sqlite` | 可恢复 session 与索引元数据 | 快照元数据、mapping 摘要、导出历史、可重建索引状态。 |
| `export-adapters` | 可复核导出 | 结构化 JSON/CSV、调查报告；保持原始 HPROF 复制语义。 |
| `presentation` | Compose 状态、布局和交互 | 多快照 tab、三栏工作区、表格、对象检查器、Dominator/Diff 视图。 |
| `memory-app` | controller、任务调度、工具链和文件菜单 | 取消、阶段进度、session 发布、外部工具调用和导出编排。 |

禁止跨层：

- Presentation 不直接读取 HPROF 文件或执行 `hprof-conv`。
- Parser 不依赖 Compose、SQLite 或页面状态。
- Export 不得把推测性的“泄漏结论”写成事实。
- Native Heap 不得被强行并入 Java HPROF 的 object graph。

## 8. 分阶段实施步骤

### 阶段 0：基线与契约固定

**目的**：在扩展前锁定当前可用行为和证据边界。

1. 为 Android-compatible HPROF、转换后 HPROF、截断 HPROF、未知 record、4/8-byte ID、mapping 缺失创建或整理 fixture。
2. 为现有 `HprofParser`、`DominatorTreeAnalyzer`、`InstanceReferenceQuery` 和 `MemoryProfilerController` 建立跨模块回归矩阵。
3. 在 UI 测试中锁定：导入状态、警告状态、类→实例→详情路径、当前底部状态栏、类直方图卡片布局。
4. 写入能力矩阵：每种输入是否有 roots、native size、allocation trace、mapping、bitmap payload、native trace。

**完成条件**：新增功能不会改变既有导入/导出语义，所有不适用指标都有明确状态。

### 阶段 1：可靠导入与任务生命周期

1. 在 `parser-hprof` 增加 `HprofInputProbe`：只读取 header 与初始 records，输出格式、ID 宽度、文件大小、可疑来源和预检警告。
2. 在 `memory-model` 增加 `HeapLoadPhase`、`HeapCapability`、`HeapSnapshotSummary`。
3. 在 `memory-app` 中把导入流程拆成 `Probe → Convert (optional) → Parse → Index → Analyze → Persist`。
4. 每阶段发布进度，支持 cancel；取消使用临时 session/临时文件，成功后才原子发布到最近会话。
5. File menu 与状态栏展示来源、转换状态、警告数与重新打开失败原因。

**关键文件**：

- `memory-profiler/parser-hprof/.../HprofParser.kt`
- `memory-profiler/memory-model/.../MemoryModel.kt`
- `memory-profiler/memory-app/.../MemoryProfilerController.kt`
- `memory-profiler/memory-app/.../DesktopMemoryProfilerBackend.kt`
- `memory-profiler/storage-sqlite/.../MemorySessionStore.kt`

### 阶段 2：三栏离线调查工作区

1. 将当前 Dashboard / Class List 演进为 `MemoryWorkspace` 容器。
2. 左栏实现 heap/scope/leak/search filters 和 saved filters。
3. 中间区域实现 `Classes`、`Dominators`、`Diff`、`Leaks & Bitmaps` 四个视图。
4. 右栏实现固定对象检查器；保留字段、入向引用、GC Root 路径，同时加入跳转、复制、固定与历史。
5. 引用目标使用 `onSelectInstance(objectId)` 打通，不以文本展示替代导航。
6. 类表与实例表保存排序、grouping、列宽、可见列与冻结列状态。

**完成条件**：从类表选中对象后，用户可不离开工作区完成“对象 → 字段/引用 → 目标对象 → Root 路径”的连续调查。

### 阶段 3：Dominator、Diff 与可复核导出

1. 基于 `DominatorTreeAnalyzer` 的已有结果暴露树查询接口，避免 UI 再次计算全图。
2. 新增 `DominatorTreePane`，支持展开、排序、跳转到实例和 subtree retained size。
3. 将 `HeapDiff` 明确限制为 class-level；展示 matching method、输入快照与无法建立对象身份的说明。
4. 在 `export-adapters` 增加：Diff CSV、选中 class/instance CSV、object details JSON、reference chain JSON、investigation report Markdown/HTML。
5. 导出报告使用原子写入：同目录临时文件完成后 rename，避免其他进程把半文件当作完成产物。

### 阶段 4：大文件与多快照治理

1. 为大型实例表、数组和入向引用建立分页/range 查询 API。
2. 将可重建索引移入受控磁盘缓存；session 只保存摘要、来源、版本和索引状态。
3. 支持多快照 tab、关闭/恢复、side-by-side class diff。
4. 增加容量预算、缓存清理策略与受控手工清理入口；不得把用户原始 dump 当作可任意删除的缓存。

### 阶段 5：独立 Live Memory Recording 项目

仅在阶段 0–4 稳定后启动，不与 HPROF Viewer 同一里程碑验收：

1. 定义设备端 allocation event / stack trace / GC event 采集协议。
2. 增加时间序列存储和 timeline UI。
3. 区分 sampled 与 full recording，并向用户披露性能开销和覆盖限制。
4. 将实时 recording 与离线 snapshot 通过证据引用关联，而非合并为同一对象图。

## 9. 验收标准

### 离线 HPROF Viewer v1

- 可导入 Android-compatible HPROF，并显示结构化成功、警告、失败或取消状态。
- 支持 raw/converted 源文件导出、mapping 关联与 session 恢复。
- 支持 heap、scope、leak、search、class/package grouping 与 retained-size 排序。
- 支持 Class → Instance → Fields / References / GC Root Chain 调查。
- 字段和入向引用可点击跟随；浏览历史、固定对象和复制 object ID 可用。
- Dominator Tree 可浏览且不重复分析全图。
- Diff 标明为 class-level，且不能产生跨快照稳定对象身份暗示。
- 支持结构化导出，所有导出带来源摘要与证据限制。
- 大任务有阶段、进度、取消与失败恢复；UI 线程不执行全图分析。

### 视觉验收

- 多快照 tab、三栏布局、主表、对象检查器和底部状态栏同时可用。
- 重要操作在键盘和鼠标下均可完成。
- 载入、警告、错误与完成状态有文字、图标和可访问性语义。
- 表格在常见窗口尺寸下不遮挡筛选器或对象检查器；超宽内容使用可预期横向滚动。
- 卡片内外边距由布局规则统一管理，避免连续嵌套 card 造成重复留白。

## 10. 验证策略

| 层级 | 验证 |
| --- | --- |
| Parser | fixture 兼容、截断/未知 record、ID size、GC root、警告与预检。 |
| Analysis | retained size、immediate dominator、path 查询、分页、Diff 语义与不跨 dump 认同 object ID。 |
| Controller | 导入状态机、取消、失败恢复、session 原子发布、mapping 关联、导出元数据。 |
| Presentation | filters、引用跳转、history、tabs、Dominator tree、Diff 限制文案、键盘操作、状态栏。 |
| Export | CSV/JSON/report schema、摘要、原子写入、导出内容与当前 filters/snapshot 一致。 |
| 性能 | 小/中/大 fixture 的解析与分析耗时、峰值内存、取消延迟、虚拟化滚动。 |

最低命令集：

```bash
cd desktop-viewer/memory-profiler
./gradlew :parser-hprof:test :analysis-memory:test :memory-app:test :memory-presentation:test
./gradlew :memory-presentation:detekt :memory-presentation:ktlintTestSourceSetCheck
```

每次扩大模型、解析器或存储边界后，再运行：

```bash
./gradlew checkAll
```

## 11. 风险与缓解

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 泛 JVM HPROF 与 Android HPROF 差异 | 导入失败或错误分析 | 明确输入支持矩阵、预检、fixture、结构化失败。 |
| 大 dump 全量物化 | OOM、界面阻塞 | 任务取消、容量预检、延迟索引、分页、磁盘缓存。 |
| 将启发式泄漏当作事实 | 错误诊断 | 显示规则、支持路径、限制和“嫌疑”语义。 |
| 跨 dump 对象 ID 误认 | 错误 Diff 结论 | class-level Diff 标签、禁止稳定对象身份声明。 |
| HPROF writer 兼容性 | 导出损坏或语义丢失 | v1 只复制源文件；writer 作为独立 major 项目。 |
| 视觉重构破坏既有导入链 | 功能回归 | 阶段 0 回归矩阵；presentation 只经 state/actions 访问功能。 |
| Live recording 侵蚀离线范围 | 里程碑失控 | 独立项目、独立模型、独立验收与证据标签。 |

## 12. 决策摘要

- 先交付高完成度的**离线 Android-compatible HPROF Viewer**，不承诺“只导入文件就等价完整 Android Studio Profiler”。
- 复用现有 parser、Dominator、实例引用查询和 Compose 页面；优先补可点击导航、对象历史、Dominator Tree、结构化导出和多快照工作区。
- 将实时 allocation/timeline/native cross-correlation 划为独立的 Live Memory Recording 路线。
- 所有 UI、导出和结论保持 evidence-first：快照范围、输入格式、算法版本、警告和限制必须可见且可导出。
