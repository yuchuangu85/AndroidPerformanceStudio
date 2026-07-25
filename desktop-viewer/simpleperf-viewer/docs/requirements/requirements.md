# Android Performance Studio 需求文档

> 2026-07-14 实施状态：V0.1 P0 功能已进入 release candidate；自动化和本机 macOS 打包证据见 `docs/requirements/development-plan.md`，未完成的真机、三平台安装与签名门禁见 `docs/records/release-checklist.md`。

- 文档版本：v0.1
- 创建日期：2026-07-13
- 依据来源：ChatGPT 共享对话《开源 Simpleperf 工具》及其中形成的方案规划、开发跟踪表、知乎文章分析结论
- 目标版本：V0.1 MVP
- 技术决策：`docs/design/technology-stack-research.md`

## 1. 背景与问题

Android 性能分析在日常研发中常见痛点包括：

1. `simpleperf` 命令行能力强，但学习成本高，参数、权限、符号、报告转换分散。
2. Android Studio Profiler 易用，但对高级采样参数、离线报告、系统进程、跨版本对比和外部生态导出支持有限。
3. Perfetto、Firefox Profiler、FlameGraph、PProf 等工具各有优势，但数据转换、符号化和验证链路复杂。
4. 团队需要一套面向 Android App / Native / 系统进程的桌面客户端，覆盖采集、解析、可视化、自动诊断、导入导出与开发跟踪。

本项目定位为：

> 基于 Simpleperf 的桌面端 Android CPU Profile 独立客户端，将命令行工具链工程化为可视化、可追踪、可验证的性能分析工作台。

## 2. 产品目标

### 2.1 V0.1 目标

V0.1 聚焦从 0 到 1 跑通闭环：

```text
连接设备 → 选择目标 → 配置采样 → 执行 simpleperf → 拉取 perf.data
→ report-sample --protobuf → 流式解析 → SQLite 入库
→ Timeline / Top Functions / CallTree / FlameGraph 展示
→ 基础诊断 → 导出报告
```

### 2.2 成功指标

| 指标 | V0.1 验收目标 |
|---|---:|
| 首次可用路径 | 用户 10 分钟内完成一次 App CPU 采样并看到火焰图 |
| 数据正确性 | 与 `simpleperf report` / `report_html.py` 的核心 Top Function 排名基本一致 |
| 大文件能力 | 支持至少 100 万 samples 的本地导入、索引与聚合 |
| 跨平台 | Windows、Linux、macOS 均可安装、启动并完成端到端采集与离线分析 |
| 离线分析 | 可导入已有 `perf.data` / protobuf 并打开历史会话 |
| 数据保真 | 保留原始 `perf.data`、Simpleperf protobuf、符号材料和会话元数据 |

## 3. 用户与场景

| 用户角色 | 主要诉求 |
|---|---|
| Android App 开发者 | 快速定位 UI 卡顿、启动慢、CPU 热点、线程热点 |
| Native / NDK 开发者 | 查看 C/C++ 调用栈、符号、库和函数热点 |
| 性能工程师 | 批量对比多次采样、导出证据、沉淀诊断结论 |
| 系统 / ROM 工程师 | 在 userdebug/root 环境分析系统进程和全局 CPU 负载 |
| QA / 发布工程师 | 复现性能问题并生成可分享报告 |

## 4. 范围定义

### 4.1 V0.1 必须实现

| 模块 | 功能 | 优先级 | 说明 |
|---|---|---:|---|
| 设备管理 | USB ADB 设备发现、连接状态、基础信息 | P0 | 设备序列号、ABI、Android 版本、root 状态 |
| 能力检测 | simpleperf 可用性、事件列表、权限探测 | P0 | 判断 app/system/root 能力边界 |
| 目标选择 | 应用包、进程名、PID、线程 | P0 | 支持 App 优先，系统进程有条件支持 |
| 采集配置 | event、frequency、duration、callgraph、user/kernel | P0 | 基于参数模型生成命令 |
| 采集控制 | 开始、停止、取消、超时、异常恢复 | P0 | 有明确状态机和错误提示 |
| 文件管理 | 保存会话目录、拉取 `perf.data`、保留原始材料 | P0 | 会话可复现、可导出 |
| 转换链路 | `simpleperf report-sample --show-callchain --protobuf` | P0 | 输出 Simpleperf protobuf / `perf.trace` |
| 解析器 | 按 `SIMPLEPERF` 头、版本、长度前缀流式读取 Record | P0 | 禁止一次性 `parseFrom()` 读取大文件 |
| 数据存储 | SQLite 会话库、样本索引、栈帧/调用点/符号表 | P0 | 支持 WAL、批量写入、索引优化 |
| Timeline | 样本时间线、线程过滤、时间范围选择 | P0 | 与 FlameGraph 联动 |
| FlameGraph | 自定义聚合火焰图 | P0 | 支持搜索、下钻、路径高亮 |
| Top Functions | 函数/库/线程热点排行 | P0 | 明确 sample/event 权重语义 |
| CallTree | 正向/反向调用树 | P0 | 支持 inclusive/exclusive 权重 |
| 基础诊断 | 数据质量、热点、线程热点、符号缺失、丢样提示 | P0 | 规则引擎输出证据卡片 |
| 导入 | 导入 `perf.data`、Simpleperf protobuf、会话包 | P0 | 支持离线分析 |
| 导出 | 会话包、JSON、CSV、截图、原始 protobuf | P0 | 外部验证与协作 |
| 外部验证 | 与 Perfetto / Android Studio / report_html 对比 | P0 | 作为数据正确性验收 |

### 4.2 V0.1 明确不做

| 功能 | 推迟原因 | 目标版本 |
|---|---|---:|
| Perfetto 联合采集 | 跨 trace 时间对齐和数据模型复杂 | V0.2+ |
| FrameTimeline / Binder / sched 自动归因 | 依赖 Perfetto Trace Processor | V0.2+ |
| 源码行级和汇编级标注 | 需要完整符号、DWARF、objdump 链路 | V0.2+ |
| 远程符号服务器 | 需要团队服务端和权限模型 | V0.3+ |
| ETM 指令流 | 设备和数据量要求高 | V0.3+ |
| 实时增量火焰图 | 活动采样文件增量读取复杂，先用分段采集 | V0.2+ |
| 团队云端报告平台 | 超出单机 MVP 范围 | V0.3+ |
| LLM 自动报告 | 规则证据未稳定前容易误导 | V0.3+ |

## 5. 核心用户流程

### 5.1 在线采集流程

1. 启动客户端，自动扫描 ADB 设备。
2. 用户选择设备，系统展示设备信息与能力检测结果。
3. 用户选择 App / 进程 / PID。
4. 用户选择采样模板或手动配置参数。
5. 点击获取数据，客户端自动生成并执行 ADB 与 simpleperf 命令，无需用户输入命令。
6. 采集结束后拉取 `perf.data`，生成 protobuf，流式解析入库。
7. 展示 Overview、Timeline、Top Functions、CallTree、FlameGraph 和诊断卡片。
8. 用户选择时间范围、线程、函数搜索并导出报告或会话包。

### 5.2 离线导入流程

1. 用户导入 `perf.data` 或 Simpleperf protobuf。
2. 若导入 `perf.data`，客户端调用 host simpleperf 生成 protobuf。
3. 用户可补充 ProGuard mapping、unstripped so、binary_cache。
4. 解析入库并进入报告视图。

### 5.3 验证与对比流程

1. 用户在报告页点击“外部验证”。
2. 客户端可导出 `report_html.py` 报告、Perfetto/Android Studio 可打开的 protobuf、CSV 排名。
3. 用户或测试用例对比 Top Function、线程热点、样本数量和丢样信息。

## 6. 功能需求详情

### 6.1 设备与目标管理

| 编号 | 需求 | 优先级 | 验收标准 |
|---|---|---:|---|
| REQ-DEV-001 | 自动发现 USB ADB 设备 | P0 | 插拔设备后列表能刷新状态 |
| REQ-DEV-002 | 展示设备基础信息 | P0 | 展示 serial、model、ABI、Android version、SDK |
| REQ-DEV-003 | 检测 root / userdebug / profileable 能力 | P0 | UI 明确说明可采集范围和限制 |
| REQ-DEV-004 | 获取包名、进程、PID、线程列表 | P0 | 支持搜索和刷新 |
| REQ-DEV-005 | 支持无线 ADB | P2 | V0.1 可不做 |

### 6.2 采集配置与控制

| 编号 | 需求 | 优先级 | 验收标准 |
|---|---|---:|---|
| REQ-CAP-001 | 提供常用采样模板 | P0 | App CPU、Native、UI Thread、System Process 模板可选 |
| REQ-CAP-002 | 支持事件、频率、时长、callgraph 模式配置 | P0 | 所选参数直接用于自动采集 |
| REQ-CAP-003 | 支持一键获取数据、停止、取消 | P0 | 无需输入命令，状态流转可见，失败后能清理临时文件 |
| REQ-CAP-004 | 捕获并解释 simpleperf 错误 | P0 | 权限、目标不存在、丢样、截断栈均有提示 |
| REQ-CAP-005 | 保存采集命令、输出日志与原始文件 | P0 | 会话目录可复现 |

### 6.3 解析与存储

| 编号 | 需求 | 优先级 | 验收标准 |
|---|---|---:|---|
| REQ-PARSE-001 | 使用 `report-sample --protobuf --show-callchain` 转换 | P0 | 成功生成 protobuf / trace 文件 |
| REQ-PARSE-002 | 流式解析 Simpleperf Record | P0 | 能处理大文件，不因单体 parse 导致 OOM |
| REQ-PARSE-003 | 保留 sample 明细 | P0 | 支持按时间、线程、事件过滤重新聚合 |
| REQ-PARSE-004 | 生成 Frame / Callsite 共享树 | P0 | 避免重复存储调用栈字符串 |
| REQ-PARSE-005 | SQLite 批量写入和索引 | P0 | 百万样本导入性能达标 |
| REQ-PARSE-006 | 记录 LostSituation / Unwind Error / 符号缺失 | P0 | 数据质量卡片可展示 |

### 6.4 可视化与分析

| 编号 | 需求 | 优先级 | 验收标准 |
|---|---|---:|---|
| REQ-VIS-001 | Overview 总览 | P0 | 展示样本数、事件、时长、线程数、丢样率、Top 热点 |
| REQ-VIS-002 | Timeline | P0 | 支持缩放、框选时间范围、线程过滤 |
| REQ-VIS-003 | FlameGraph | P0 | 支持缩放、搜索、高亮、路径面包屑 |
| REQ-VIS-004 | Top Functions | P0 | 可按 inclusive/exclusive、库、线程排序 |
| REQ-VIS-005 | CallTree / Reverse CallTree | P0 | 可从函数跳转到调用路径和火焰图位置 |
| REQ-VIS-006 | CPU Sample Heatmap | P1 | 用于发现周期性和突发热点 |
| REQ-VIS-007 | Differential FlameGraph | P2 | 支持优化前后对比 |

### 6.5 诊断规则

| 编号 | 需求 | 优先级 | 验收标准 |
|---|---|---:|---|
| REQ-DIAG-001 | 数据质量诊断 | P0 | 丢样、截断栈、unknown symbol、空栈可提示 |
| REQ-DIAG-002 | CPU 热点诊断 | P0 | 输出 Top N 热点和证据链接 |
| REQ-DIAG-003 | 线程热点诊断 | P0 | 识别主线程/工作线程/线程池集中负载 |
| REQ-DIAG-004 | 解释 sample 权重语义 | P0 | 避免将采样权重误报为精确耗时 |
| REQ-DIAG-005 | Android 专项归因 | P1 | Binder、GC、JIT、RenderThread 等规则后续扩展 |

## 7. 数据需求

必须保留以下数据层级：

```text
ProfileSession
├── Raw Artifacts
│   ├── perf.data
│   ├── simpleperf.protobuf / perf.trace
│   ├── command.log
│   ├── device.json
│   ├── symbols / binary_cache
│   └── mapping.txt
├── Indexed Data
│   ├── process
│   ├── thread
│   ├── sample
│   ├── event
│   ├── frame
│   ├── callsite
│   ├── symbol
│   └── lost_situation
└── Derived Views
    ├── timeline buckets
    ├── top functions
    ├── call tree
    ├── reverse call tree
    ├── flamegraph tree
    └── diagnostics
```

关键原则：不能在导入时只保存 folded stacks 或聚合火焰图，否则会丢失 Timeline、时间范围过滤、多线程过滤和后续 Perfetto 联合分析能力。

### 7.1 Profile database migration

- Migration works on a copy; `profile.sqlite` is replaced only after the migrated candidate and retained evidence pass verification.
- The first successful migration retains `profile.v1.sqlite` and its SHA-256 in `migration.properties`.
- Backup and metadata publication uses fail-closed hard links; if publication, verification, or migration fails, the application opens the original database in legacy read-only mode.
- Availability is reported as exactly one of: Available, Empty, Not collected, Unavailable, Unauthorized, Failed, or Not applicable.
- Users must copy the complete session directory before attempting manual SQLite repair.

## 8. 非功能需求

| 类别 | 要求 |
|---|---|
| 性能 | 百万样本级导入不 OOM；聚合操作可取消；UI 不阻塞 |
| 可靠性 | 所有外部命令有超时、日志、退出码、错误分类 |
| 可维护性 | 采集、解析、存储、分析、UI 分层；模块边界清晰 |
| 可扩展性 | 预留 libsimpleperf_report、Perfetto Trace Processor、外部导出适配器 |
| 跨平台 | Windows / Linux / macOS 同属 P0；路径、进程、权限、工具资源和打包差异集中封装 |
| 可验证性 | 每个会话可导出原始材料与验证报告 |
| 安全性 | 不上传用户 profile；默认本地存储；避免误删设备文件 |

## 9. 风险与约束

| 风险 | 影响 | 缓解策略 |
|---|---|---|
| Android 权限限制 | 无法采集系统进程或 release App | 能力检测和 UI 明确提示，优先支持 profileable / debug App |
| Simpleperf 版本差异 | protobuf 字段或命令行为变化 | 封装版本探测，建立兼容样本库 |
| Protobuf 大文件 | OOM 或导入慢 | 流式读取、批量入库、后台任务、可取消 |
| 符号缺失 | 火焰图大量 unknown | 支持 mapping、unstripped so、binary_cache，引导修复 |
| 采样误读 | 用户把 sample 当精确耗时 | 所有指标标注 sample/event 权重语义 |
| UI 性能 | 大型火焰图卡顿 | 分层聚合、虚拟化绘制、渐进加载 |
| 桌面框架平台缺口 | 特定 CPU 架构或 Linux 无障碍能力不满足 | 第 1 周完成三平台/架构 PoC；不达标时切换 Flutter Desktop |

## 10. 验收标准

V0.1 完成条件：

1. Windows、Linux、macOS 均完成安装、启动、在线采集、离线导入和核心报告闭环。
2. 支持导入已有 `perf.data` 并完成 protobuf 转换、解析、入库。
3. Overview、Timeline、Top Functions、CallTree、FlameGraph 均可用。
4. 诊断页至少包含数据质量、CPU 热点、线程热点三类卡片。
5. 与 `simpleperf report`、`report_html.py` 对比核心统计结果，无明显数据模型错误。
6. 文档、测试矩阵、风险清单、开发跟踪表同步更新。

## 11. 参考资料

- ChatGPT 共享对话：https://chatgpt.com/share/6a54e924-0698-83ea-a051-ef5ff3b4db94
- Android NDK Simpleperf：https://developer.android.com/ndk/guides/simpleperf
- AOSP Simpleperf README：https://android.googlesource.com/platform/system/extras/+/master/simpleperf/doc/README.md
- AOSP Simpleperf View the profile：https://android.googlesource.com/platform/system/extras/+/master/simpleperf/doc/view_the_profile.md
- 跨平台技术栈调研：`docs/design/technology-stack-research.md`
