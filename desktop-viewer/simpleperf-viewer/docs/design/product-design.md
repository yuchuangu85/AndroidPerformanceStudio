# Android Performance Studio 产品设计文档

- 文档版本：v0.1
- 创建日期：2026-07-13
- 关联文档：`docs/requirements/requirements.md`、`docs/design/technology-stack-research.md`、`docs/requirements/development-plan.md`、`docs/requirements/simpleperf_client_development_tracker.xlsx`

本文保留产品契约与路线，当前采集和报告交互于 2026-10-30 按实现核对。历史版本规划不等于所有能力已经交付；代码和测试存在也不代表完成验收，发布状态仍以 [发布门禁](../records/release-checklist.md) 为准。

## 1. 产品定位

Android Performance Studio 是一个面向 Android CPU Profile 的桌面端分析客户端。第一版以 Simpleperf 为采集与转换内核，以本地 SQLite 为分析索引，以自定义 Timeline、CallTree 和 FlameGraph 为主要交互界面。

产品承诺：

> 让 Android 性能采样从“命令行专家工具”变成“可配置、可解释、可验证、可分享”的工程化工作台。

## 2. 设计原则

1. **先保真，后智能**：保留原始 `perf.data`、protobuf、符号、日志，再生成聚合视图和诊断结论。
2. **证据优先**：诊断卡片必须能跳转到样本、线程、函数、调用路径或外部验证报告。
3. **不误导**：所有 Sample/Event 指标必须提示“采样权重不是精确函数耗时”。
4. **渐进复杂度**：普通用户走模板采集；专家用户可展开完整参数。
5. **外部生态兼容**：支持导出到 Perfetto、Android Studio、Firefox Profiler、PProf、FlameGraph 等生态。
6. **离线优先**：默认本地保存和分析，不依赖服务端。
7. **三平台同权**：Windows、Linux、macOS 的核心流程、数据结果和错误语义保持一致，平台差异只存在于工具链与发行适配层。

## 3. 信息架构

```text
Android Performance Studio
├── Home / Session Library
│   ├── Recent Sessions
│   ├── Import perf.data / protobuf / session package
│   └── Open Existing Session
├── Device & Target
│   ├── Devices
│   ├── Capability Check
│   ├── Apps / Processes / PID / Threads
│   └── Permission Guidance
├── Capture Settings（目标与报告工作区的设置入口）
│   ├── Templates
│   ├── Advanced Parameters
│   └── 采集动作与状态保留在工作区
├── Report
│   ├── Persistent Timeline / Shared Range
│   ├── Overview
│   │   └── Diagnostics
│   ├── Top Functions
│   ├── CallTree
│   ├── FlameGraph
│   ├── Stack Chart
│   ├── Marker Chart
│   ├── Marker Table
│   └── Shared Filters / Details
├── Export & Validate
│   ├── Session Package
│   ├── JSON / CSV / Screenshot
│   ├── Simpleperf protobuf
│   ├── Gecko Profile
│   ├── Folded Stacks（V0.2）
│   └── External Viewer Launch
└── Settings
    ├── ADB Path
    ├── Simpleperf Toolchain
    ├── Workspace Path
    ├── Symbol Paths
    └── Diagnostics Rules
```

## 4. 页面设计

### 4.1 Home / Session Library

目标：让用户从历史会话、在线采集、离线导入三条路径进入。

关键元素：

| 区域 | 内容 |
|---|---|
| 顶部操作 | New Capture、Import、Open Session、Settings |
| 最近会话 | App/进程、设备、采样时间、样本数、风险标签 |
| 快速筛选 | 设备、包名、日期、是否有诊断问题 |
| 空状态 | 展示“连接设备采集”与“导入 perf.data”两个入口 |

交互要求：

- 点击最近会话直接进入 Report Overview。
- 导入文件后显示导入任务进度和解析日志。
- 会话卡片展示原始材料是否完整，例如 `perf.data`、protobuf、symbols、mapping。

### 4.2 Device & Target

目标：明确设备状态、权限边界和可采集对象。

布局：

```text
左侧：设备列表
右侧上：设备能力卡片
右侧中：应用 / 进程 / PID / 线程选择器
右侧下：权限与采样建议
```

能力卡片字段：

| 字段 | 示例 |
|---|---|
| Device | Pixel 8 / serial |
| Android | 15 / SDK 35 |
| ABI | arm64-v8a |
| Root | no / yes |
| Profileable | supported / unsupported |
| Simpleperf | device / bundled / missing |
| Event list | cpu-clock, cpu-cycles, instructions... |

状态提示：

| 状态 | UI 表达 | 用户动作 |
|---|---|---|
| Ready | 绿色 | 可进入采集 |
| Limited | 黄色 | 展示受限原因，如 release App 不可采 |
| Blocked | 红色 | 展示修复建议，如授权 ADB、选择 profileable App |

### 4.3 Capture

目标：用低成本完成一次高质量采样。

当前目标选择与报告共用工作区，采集配置从设置入口打开，不再经过独立 Capture 页面。采样模板、采集配置和高级参数保持可编辑，获取数据、停止分析和取消操作留在工作区。早期模板与参数双栏页面布局不再是当前界面契约。

#### 4.3.1 采样模板

| 模板 | 默认参数 | 适用场景 |
|---|---|---|
| App CPU Basic | `cpu-clock -g -f 1000 --duration 10` | 快速定位 App CPU 热点 |
| UI Thread Focus | `cpu-clock -g -f 1000` + UI thread filter | UI 卡顿、主线程热点 |
| Native Hotspot | `cpu-cycles -g` | C/C++ 计算热点 |
| Low Overhead | frame pointer callgraph / lower frequency | 降低采样开销 |
| System Process | process name / PID | userdebug/root 系统进程 |

#### 4.3.2 高级参数

- Event：`cpu-clock`、`cpu-cycles`、`instructions` 等。
- Frequency / Period：频率和周期二选一或高级模式。
- Duration：固定时长或手动停止。
- Callgraph：DWARF、frame pointer、no callgraph。
- Scope：user、kernel、both。
- Buffer：kernel buffer、user buffer。
- Symbol inputs：ProGuard mapping、unstripped native libs、binary cache。

#### 4.3.3 一键获取数据

- 工作区保留 **Get data / 获取数据** 主操作。
- 用户选择目标后可直接点击获取数据；采样模板已提供可执行默认参数。
- 客户端根据模板、用户可选的高级参数和设备能力自动生成并执行 ADB/Simpleperf 参数数组。
- UI 不展示需要复制或手动输入的采集命令；最终执行命令仍写入会话目录，用于故障定位和复现。

### 4.4 Capture Progress

状态机：

```text
Idle → Preparing → Recording → Pulling → Converting → Parsing → Indexing → Ready
                         └── Canceling / Failed
```

进度展示：

| 阶段 | 用户可见信息 |
|---|---|
| Preparing | 推送/选择 simpleperf、创建临时目录 |
| Recording | 剩余时间、实时日志、停止按钮 |
| Pulling | `perf.data` 大小、传输进度 |
| Converting | protobuf 输出路径、退出码 |
| Parsing | 已解析 Record 数、Sample 数、吞吐 |
| Indexing | 聚合和索引状态 |

错误策略：

- 每个失败显示“原因 + 技术日志 + 建议动作”。
- 失败会话仍保留日志和中间文件，方便复盘。
- 取消操作必须清理设备临时文件，但不得删除本地已拉取证据。

## 5. Report 设计

### 5.1 Overview

目标：给出“一眼判断 profile 是否可用、热点在哪里”。

卡片：

| 卡片 | 内容 |
|---|---|
| Session Summary | 设备、目标、命令、采样时长、事件、频率 |
| Data Quality | samples、lost、truncated、unknown symbols、unwind errors |
| Top Threads | 线程名、TID、样本权重、占比 |
| Top Functions | 函数、库、inclusive/exclusive 权重 |
| Initial Diagnosis | 规则命中、风险等级、证据入口 |
| Artifacts | perf.data、protobuf、mapping、symbols、日志 |

### 5.2 Timeline

Timeline 是 V0.1 P0 功能，不是附属视图。

交互能力：

- 按时间轴显示 samples 分布。
- 支持缩放、拖拽、框选时间范围。
- 支持线程单选、多选和搜索。
- 选中时间范围后，Overview、Top Functions、CallTree、FlameGraph 重新计算。
- 支持跳转到火焰图中当前范围的热点路径。

设计约束：

- 必须基于 sample 明细表，不可只依赖预聚合 folded stacks。
- 时间桶大小随缩放级别变化，避免一次性渲染所有点。

#### 5.2.1 导航快捷键

Timeline 使用 Perfetto 风格导航动作：

| 输入 | 动作 |
|---|---|
| `W` | 放大 |
| `S` | 缩小 |
| `A` | 向左平移 |
| `D` | 向右平移 |
| `Ctrl` + 鼠标滚轮 | 以指针位置为中心缩放 |

输入框获得焦点时不得拦截字符键。FlameGraph 的当前交互和相对 Firefox 的差异见 [兼容矩阵](../requirements/firefox-flame-graph-compatibility.md)，不能把两种视图的全部手势混为一谈。

### 5.3 FlameGraph

FlameGraph 是主要热点探索视图。

布局：

```text
报告级：共享过滤条件、搜索、反转调用栈与详情开关
面板级：变换导航与火焰图画布
详情：按当前选中 frame 显示，窄窗口可置于下方
```

交互：

| 操作 | 结果 |
|---|---|
| 点击 frame / 空白 | 选择 frame / 清除选择 |
| 双击 frame / Enter | 打开源码、反汇编或有原因的符号详情 |
| 搜索函数/库 | 在查询层过滤样本栈，与 Call Tree 联动，不仅改变绘图高亮 |
| 从 Top Functions 跳转 | 定位到对应 frame 或多条路径 |
| 选择线程或时间范围 | 重新聚合火焰图 |
| 右键 / 变换导航 | Focus、Merge、Drop、Collapse，以及撤销、移除和清空变换 |

提示：

- 火焰图宽度表示采样权重占比，不表示精确耗时。
- unknown / unresolved symbols 要用特殊颜色和提示引导补充符号。
- 完整交互、视觉基线、源信息校验和性能验收条件集中在 [兼容矩阵](../requirements/firefox-flame-graph-compatibility.md)。

### 5.4 Top Functions

表格字段：

| 字段 | 说明 |
|---|---|
| Function | 函数名，可折叠短名/全名 |
| Library | so / dex / kernel / unknown |
| Inclusive Weight | 包含子调用的样本权重 |
| Exclusive Weight | 函数自身样本权重 |
| Samples | 命中样本数 |
| Threads | 相关线程数 |
| Actions | 跳转 FlameGraph、CallTree、导出 CSV |

### 5.5 CallTree / Reverse CallTree

设计目标：回答两个问题：

1. 某个热点由哪些调用路径进入？
2. 某个入口下的主要子调用消耗在哪里？

能力：

- 正向调用树：root → callee。
- 反向调用树：symbol → callers。
- 支持按当前时间范围、线程集合和搜索条件重算。
- 支持与 FlameGraph 和 Top Functions 双向跳转。

### 5.6 Diagnostics

诊断保留在 Overview 中，不再占用独立标签页；证据和导航能力不因此删除。

诊断卡片结构：

```text
标题：主线程 CPU 热点集中
等级：High / Medium / Low / Info
结论：主线程 68% samples 位于 decodeBitmap → nativeDecode
证据：时间范围、线程、函数、调用路径、样本数
建议：降低主线程图片解码，迁移到后台线程，复测同一场景
跳转：打开 Timeline / FlameGraph / CallTree
```

V0.1 诊断类型：

| 类型 | 规则示例 |
|---|---|
| 数据质量 | 丢样率高、截断栈多、unknown symbol 比例高 |
| CPU 热点 | 单函数/单库/单调用路径占比过高 |
| 线程热点 | 主线程、RenderThread、Binder、线程池集中负载 |
| 参数建议 | 频率过高导致丢样；callgraph 模式不适合当前 ABI |
| 符号建议 | native unknown 高，建议补充 unstripped so / binary_cache |

### 5.7 报告工作区与共享状态

- 顶部 Timeline 常驻，高度有界可调整；下部固定七个标签：Overview、Top Functions、Call Tree、Flame Graph、Stack Chart、Marker Chart、Marker Table。缺失数据不能成为隐藏标签的理由。
- 时间范围、线程、event、筛选、反转状态和详情开关跨标签保留；选择按面板类型和会话隔离，不能把上一面板的选择冒充当前内容。
- 共享栈工具栏提供 All Frames、Script、Native、Invert Call Stack 和 Filter Stacks。Script 对应 managed/runtime，Native 包含 native 与 kernel；未分类帧只属于 All Frames。
- 共享栈筛选作用于可兼容的调用栈视图；Marker Chart/Table 使用各自的 marker 工具栏与范围查询，不能误用实现类型或反转筛选。
- 搜索去抖、查询可取消，更新期间保留稳定结果；过期代次不得覆盖新查询。面板不直接访问 SQLite，失败和重试应隔离在可恢复的面板边界。
- Show details 控制当前面板的详情区域；关闭不清空选择，重新打开恢复当前有效内容，无选择时给出提示。详情解析失败不得使整个报告失效。
- 窄窗口保留全部标签、工具和详情入口，通过滚动或上下布局适配；浅色、深色和键盘焦点不能仅依赖颜色区分。

### 5.8 Stack Chart 与 Marker 契约

- Stack Chart 横轴是真实采样时间，纵轴是栈深，不是 Flame Graph 别名。相邻区间合并不得改变时间边界、线程、栈块身份或统计口径。
- 栈实现类型、反转和搜索在投影前生效；选中块与真实帧/样本区间对应，范围交互与 Timeline 联动。
- Marker Chart/Table 来自 canonical marker 事实，区分点事件与区间重叠。表格保留名称、起始时间、时长、进程/线程、schema/category 和 payload 入口。
- Marker 图表、表格与 Timeline 的范围和 marker 身份保持联动；缺失来源、无记录、范围为空、过滤为空、不可用、未授权和解析失败分别呈现，不伪造浏览器事件。
- 表格虚拟化，Canvas 按视口和可见行组织绘制；大 payload 在需要时加载或格式化，不在每次绘图中重复解析。
- Stack Chart 的逐块点击覆盖层与全表扫描仍未优化。单独投影成功、百万样本合并成少量块，都不能代替高栈块数原生交互验收；完整待办和建议指标保留在 [未实现的性能方案](2026-10-29-stack-chart-performance-optimization-plan.md)。

### 5.9 验证与尚未闭环条件

`FirefoxReportWorkspaceTest`、`ReportWorkspaceBehaviorTest`、`StackChartComposeUiTest`、`MarkerPanelsComposeUiTest` 和 Flame Graph 测试是实现与回归入口，不是全部通过的当前声明。验收仍需覆盖：

- 七标签、共享范围/工具栏、详情切换、选择隔离、诊断导航和失败恢复；
- Stack Chart 身份与区间、marker 点/区间语义、排序、payload、筛选与代次取消；
- 浅色/深色、窄/宽窗口、空/加载/选中/失败状态，详情开关和 Timeline 高度边界；
- 键盘、屏幕阅读器、可见与选中 Canvas 节点的语义和焦点；
- 百万样本、高 marker 基数、深栈和高栈块数下的原生输入延迟、内存峰值与掉帧；
- 格式、静态分析、相关单元/集成/Compose/Golden 回归及跨平台原生验收。

原生能力对齐的其他阶段（Android 多来源、时钟对齐、符号、比较与保存分析状态）继续受 [原生能力路线](2026-07-14-firefox-profiler-native-parity-design.md) 约束，不因清理 UI 实施稿而缩小范围。

## 6. 导入、导出与外部集成

### 6.1 导入

支持：

- `perf.data`
- Simpleperf protobuf / `perf.trace`
- 会话包 `.apsession.zip`
- 可选 mapping 文件
- 可选 native 符号目录

### 6.2 导出

V0.1：

| 格式 | 用途 |
|---|---|
| 会话包 | 团队内部复现与归档 |
| JSON | 自动化分析与二次处理 |
| CSV | 表格分析和报告引用 |
| Screenshot | 快速分享可视化结果 |
| Simpleperf protobuf | Android Studio / Perfetto 验证 |

V0.2+：

- Gecko Profile：Perfetto / Firefox Profiler。
- Folded Stacks：FlameGraph / 差分火焰图。
- PProf protobuf：PProf 生态。

### 6.3 外部验证入口

报告页提供：

- 使用 `report_html.py` 生成 HTML 报告。
- 打开/导出 `perf.trace` 供 Android Studio Profiler 使用。
- 导出 Gecko Profile 供 Perfetto / Firefox Profiler 使用（V0.2）。

## 7. 视觉与交互规范

### 7.1 状态颜色

| 状态 | 颜色语义 |
|---|---|
| Ready / Passed | 绿色 |
| Warning / Limited | 黄色 |
| Error / Blocked | 红色 |
| Running / Processing | 蓝色 |
| Unknown / Not checked | 灰色 |

### 7.2 权重颜色

- 热点越高颜色越暖。
- unknown symbol 使用灰色或斜纹，不与真实热点混淆。
- selected frame 使用高对比描边。

### 7.3 空状态

每个空状态提供下一步动作：

| 页面 | 空状态动作 |
|---|---|
| Home | New Capture / Import perf.data |
| Device | Refresh ADB / Open Settings |
| Report | Import data / Re-run parser |
| Symbols | Add mapping / Add native libs / Build binary_cache |

## 8. 权限与错误提示设计

错误提示格式：

```text
发生了什么：无法采集目标进程
可能原因：目标不是 debuggable/profileable，设备无 root 权限
建议操作：选择 debug/profileable App，或在 userdebug/root 设备上重试
技术详情：simpleperf stderr / adb stderr / command exit code
```

常见错误：

| 错误 | 设计反馈 |
|---|---|
| ADB unauthorized | 提示设备上确认授权 |
| simpleperf missing | 提示使用内置 simpleperf 或配置 NDK 路径 |
| target process not found | 提示刷新进程列表或启动 App |
| permission denied | 说明 Android 权限边界 |
| lost samples high | 建议降低频率或增加 buffer |
| unknown symbols high | 引导补充 mapping / unstripped so |

## 9. 产品路线图

| 版本 | 主题 | 关键能力 |
|---|---|---|
| V0.1 | 单机采集与基础报告 | ADB、Simpleperf、protobuf、SQLite、Timeline、FlameGraph、基础诊断 |
| V0.2 | 高级可视化与外部生态 | Heatmap、差分 FlameGraph、Gecko、Folded Stacks、PProf、Perfetto 打开 |
| V0.3 | 系统诊断增强 | Perfetto Trace Processor、FrameTimeline、Binder、sched、频率、GPU 联合分析 |
| V0.4 | 团队与自动化 | 批量采集、CI 对比、报告模板、符号服务器、规则市场 |

### 9.1 V0.1 平台交付矩阵

| 平台 | 发行物 | 必须验收 |
|---|---|---|
| Windows | `.msi` / `.exe` | 安装、ADB 发现、在线采集、离线导入、报告、导出 |
| Linux | `.deb` / `.rpm` / portable app image | X11/Wayland 启动、ADB 发现、在线采集、离线导入、报告、导出 |
| macOS | `.dmg` / `.pkg` | 签名/公证、ADB 发现、在线采集、离线导入、报告、导出 |

三端使用同一份 Golden protobuf 验证 sample 数、Top Functions、CallTree 和 FlameGraph 聚合结果，禁止仅以“界面可启动”作为跨平台完成标准。

## 10. 产品验收清单

- [x] 用户可从 Home 发起采集或导入离线文件。
- [x] 用户可清楚知道当前设备能采哪些目标、不能采的原因。
- [x] 采集命令由参数模型自动生成，并在会话日志中可追踪，无需用户复制或输入。
- [x] 采集失败时仍能保存日志和中间产物。
- [x] Timeline 与 FlameGraph 支持时间范围联动。
- [x] Top Functions 与 CallTree / FlameGraph 可互相跳转。
- [x] 诊断卡片有证据链，不输出无依据结论。
- [x] 所有导出文件可在会话包中追溯。
- [ ] Windows、Linux、macOS 均通过安装、启动、采集、导入、分析和导出验收。

## 11. 参考资料

- ChatGPT 共享对话：https://chatgpt.com/share/6a54e924-0698-83ea-a051-ef5ff3b4db94
- AOSP Simpleperf View the profile：https://android.googlesource.com/platform/system/extras/+/master/simpleperf/doc/view_the_profile.md
- Android NDK Simpleperf：https://developer.android.com/ndk/guides/simpleperf
- 当前技术栈与运行时约束：`docs/design/technology-stack-research.md`

## V0.2-V0.4 delivered core (2026-09-21)

Advanced analysis is represented as evidence-preserving services rather than UI-only calculations: heatmap buckets retain CPU/thread/time identity, differential flame nodes retain baseline/current weights, Perfetto sources remain separate canonical slices/counters, and automation outputs fail closed when inputs or report-template fields are missing.
