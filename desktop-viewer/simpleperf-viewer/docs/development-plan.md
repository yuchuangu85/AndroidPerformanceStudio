# Android Performance Studio 开发计划

- 文档版本：v0.1
- 创建日期：2026-07-13（实施状态更新：2026-07-14）
- 目标版本：V0.1 MVP
- 建议计划周期：18 周
- 建议启动日期：2026-07-20
- 关联跟踪表：`docs/simpleperf_client_development_tracker.xlsx`
- 技术决策：`docs/technology-stack-research.md`

## 1. 技术路线

V0.1 采用以下路线：

```text
Compose Multiplatform Desktop
+ Kotlin / JVM + JBR/JDK 21 LTS
+ kotlinx.coroutines / Flow
+ ADB 子进程封装
+ Simpleperf device/host executable
+ simpleperf report-sample --show-callchain --protobuf
+ protobuf-java + Simpleperf framing 流式解析
+ xerial sqlite-jdbc + JDBC/PreparedStatement + WAL
+ Compose Canvas / Skia 自定义 Timeline / CallTree / FlameGraph
+ Gradle nativeDistributions/jpackage 分平台打包
```

暂不在第一版直接依赖 `libsimpleperf_report` 和 Perfetto Trace Processor；待数据模型和产品流程稳定后再接入。

## 2. 架构分层

```text
app-desktop
├── presentation              # Compose Desktop UI
├── application               # 用例、状态机、任务编排
├── platform-toolchain        # OS/arch、路径、进程、工具资源、错误归一化
├── device-adb                # ADB 子进程、设备发现、权限探测
├── capture-simpleperf        # simpleperf record/stat/list/report-sample 封装
├── parser-simpleperf-proto   # protobuf 流式解析
├── profile-model             # 统一分析模型
├── storage-sqlite            # SQLite schema、DAO、索引、迁移
├── analysis-rules            # 数据质量、热点、线程规则
├── visualization             # Timeline、FlameGraph、CallTree 数据适配
├── export-adapters           # JSON、CSV、session package、外部格式
└── test-fixtures             # 样本 perf.data/protobuf、Golden 结果
```

## 3. 关键技术决策

| 决策 | 采用方案 | 理由 | 延后方案 |
|---|---|---|---|
| UI 框架 | Compose Multiplatform Desktop/JVM | Desktop 稳定；Android/JVM 生态契合；Canvas/Skia 适合高密度自绘 | Flutter Desktop |
| 运行时 | JBR/JDK 21 LTS + 裁剪 runtime | 稳定 LTS；发行包自包含，无需用户安装 JDK | Kotlin/Native |
| 并发 | kotlinx.coroutines + Flow | 统一取消、进度、后台 I/O/CPU 任务 | 手工线程池 |
| ADB 集成 | 子进程封装 | 成本低、兼容 SDK 工具链 | 自实现 ADB 协议 |
| 解析入口 | `report-sample --protobuf` | 避免从零解析 `perf.data` | `libsimpleperf_report` |
| Protobuf 读取 | 流式 Record 读取 | 支持大文件，避免 OOM | 单体 parse（禁止） |
| 本地存储 | xerial sqlite-jdbc + JDBC + WAL | 三平台 native SQLite；可精确控制批量事务和查询计划 | ORM / 自研列式存储 |
| 火焰图 | 自定义绘制 | 满足联动和桌面交互 | 嵌入 HTML report |
| 平台差异 | platform-toolchain 集中封装 | 禁止 OS 判断散落 UI/领域层 | 各模块自行判断 OS |
| 打包 | Compose nativeDistributions/jpackage | 自包含 runtime，提供 DMG/PKG、MSI/EXE、DEB/RPM | 手工组装安装包 |
| 外部验证 | report_html / simpleperf report / Android Studio trace | 建立正确性基线 | 完全自证 |

## 4. 里程碑计划

| 阶段 | 周期 | 目标 | 退出标准 |
|---|---:|---|---|
| P0 项目启动与基线 | 第 1 周 | 工程脚手架、工具链、样本数据 | 可运行空壳 App，CI 可执行基础测试 |
| P1 ADB 与设备能力 | 第 2-3 周 | 设备发现、能力检测、目标选择 | 可列出设备、App/进程/PID，展示权限状态 |
| P2 Simpleperf 采集闭环 | 第 4-5 周 | 参数模型、采集状态机、文件拉取 | 能对 profileable App 生成 `perf.data` |
| P3 转换、解析、SQLite | 第 6-8 周 | protobuf 转换、流式解析、入库 | 可导入百万级样本并查询基础统计 |
| P4 基础报告视图 | 第 9-11 周 | Overview、Timeline、Top、CallTree、FlameGraph | 视图可按时间/线程过滤联动 |
| P5 诊断、导入导出 | 第 12-13 周 | 规则卡片、会话包、JSON/CSV、外部验证 | 可生成完整报告和验证材料 |
| P6 稳定性、性能与三平台交付 | 第 14-16 周 | 大文件、错误恢复、三平台打包与 clean VM 验证 | 关键性能指标和三平台兼容测试达标 |
| P7 发布准备 | 第 17-18 周 | 文档、示例、签名/公证、安装包、验收 | V0.1 release candidate 可交付 |

### 4.1 当前实施状态（2026-07-14）

V0.1 release candidate 的代码、测试、文档和本机 macOS 发行物已完成；真机与 GitHub Actions 三平台安装验收按 `docs/release-checklist.md` 跟踪：

| WBS | 状态 | 已完成证据 |
|---|---|---|
| WBS-001 | 已完成 | Gradle Wrapper、Kotlin/JVM 21、Compose Desktop 空壳可启动 |
| WBS-002 | 已完成 | 文档定义的 13 个模块已纳入构建，依赖方向已建立 |
| WBS-003 | 已完成 | `./gradlew check` 覆盖 JUnit、ktlint、detekt 并通过 |
| WBS-004 | 已完成 | AOSP `perf.data` 已按 upstream revision、SHA-256、大小和许可证固定到 `test-fixtures` |
| WBS-005 | 已完成 | `StudioError`、`ErrorCategory`、`StudioResult` 已测试 |
| WBS-006 | 进行中 | Windows/Linux/macOS GitHub Actions 矩阵已配置，clean runner 结果待首次 CI |
| WBS-007 | 已完成 | Host platform 检测、资源目录规则、toolchain manifest 已测试 |
| WBS-008 | 已完成 | macOS arm64 百万记录 PoC：SQLite 导入 7541.260 ms、峰值堆增量 160926928 bytes；Timeline/10 万节点 FlameGraph 帧准备 P95 为 0.174/0.389 ms |
| WBS-010 | 已完成 | ADB 按用户配置、`ANDROID_HOME`、兼容 `ANDROID_SDK_ROOT`、`PATH`、系统默认 SDK 目录依次发现；本机 ADB 37.0.0 自检通过 |
| WBS-011 | 已完成 | 参数数组启动、stdout/stderr 并发有界消费、超时/显式取消、进程树清理与结构化错误均有单元测试 |
| WBS-012 | 实现完成 | `adb devices -l` 支持多设备、空列表、offline、unauthorized、no permissions 与未知状态解析；刷新继承结构化取消/错误，本机空设备实测通过，真机热插拔 smoke 待有设备环境补测 |
| WBS-013 | 实现完成 | 单次 `adb -s <serial> shell getprop` 解析 model、ABI 列表、SDK 与 Android version，支持旧版 ABI 回退、完整性校验和结构化取消/错误；真机属性 smoke 待有设备环境补测 |
| WBS-014 | 实现完成 | `id -u`、`ro.build.type`、`simpleperf --version` 和 `simpleperf list` 探测 root、profileable、工具版本及设备事件，聚合 Ready/Limited/Blocked；不自动执行 `adb root`，真机能力 smoke 待补测 |
| WBS-015 | 实现完成 | `cmd package list packages`、`ps -A` 和 `ps -T -p` 查询 App、进程/PID 与线程，支持大小写无关的包名/进程名/用户/PID 搜索、显式刷新、结构化取消和错误传播；真机目标列表 smoke 待有设备环境补测 |
| WBS-016 | 实现完成 | Application `StateFlow` 控制设备刷新、能力/目标加载、搜索、App/进程/线程选择和页面切换；Compose Desktop 已实现 Device & Target 布局并接入真实 ADB gateway，Blocked 能力禁止进入 Capture；真机 UI 流程待有设备环境补测 |
| WBS-020 | 实现完成 | 采样目标、event、frequency/period、duration/manual stop、DWARF/frame pointer/no callgraph、user/kernel/both scope 与输出路径均为强类型模型；高级参数 UI 与自动采集共用参数对象 |
| WBS-021 | 实现完成 | App CPU Basic、UI Thread Focus、Native Hotspot、Low Overhead、System Process 五个模板已接入 Capture 页面，切换模板保留所选目标 |
| WBS-022 | 实现完成 | Capture 页面在上部提供一键获取数据，不展示或要求输入命令；客户端自动从参数模型生成并执行参数数组，已测试 App/PID/TID、frequency/period 和 callgraph 序列化 |
| WBS-023 | 策略实现 | 优先使用设备端 simpleperf；缺失时按 ABI 选择 bundled 资产，校验远端 SHA-256，按需 mkdir/push/chmod 并执行 `--version` 验证；实际 bundled 二进制目录留待 WBS-083 打包阶段接入 |
| WBS-024 | 实现完成 | `Idle/Preparing/Recording/Stopping/Pulling/Completed/Failed/Cancelled` 已接入桌面 Start/Stop/Cancel；Stop 独立发送 SIGINT 后继续 pull，Cancel 终止任务，二者均保留日志 |
| WBS-025 | 实现完成 | 每次采集创建隔离 session 目录，校验 session id 与目录边界，拉取并确认非空前置流程生成的 `perf.data`，完成/失败/取消均保留会话路径 |
| WBS-026 | 实现完成 | 持久化最终命令、record/pull stdout、stderr、退出码、截断标记、命令与时间戳，并以稳定 `session.properties` 记录完成、失败或取消状态 |
| WBS-027 | 待真机验收 | 底层采集、桌面触发与仿真端到端测试已通过；当前环境无 Android 设备，profileable App 真实 `perf.data` smoke 尚未执行 |
| WBS-030 | 实现完成 | host simpleperf 按用户配置、bundled、PATH 确定优先级发现；执行 `--version` 并计算 SHA-256，bundled manifest hash 不一致时拒绝使用 |
| WBS-031 | 实现完成 | 使用参数数组执行 `report-sample --protobuf --show-callchain -i ... -o ...`，支持 symdir/Proguard mapping、取消、超时、输入校验与非空输出验证 |
| WBS-032 | 实现完成 | AOSP `cmd_report_sample.proto` 固定到 `android-17.0.0_r1`，记录 SHA-256/Apache-2.0 来源，由 protobuf Gradle 插件生成 Java 模型 |
| WBS-033 | 实现完成 | 流式校验 10-byte `SIMPLEPERF` magic、LE16 version、LE32 长度与终止记录；逐条有界分配/解析，错误包含 record index 与 byte offset |
| WBS-034 | 实现完成 | protobuf 类型隔离在 parser；有状态 normalizer 解析 Meta/File/Thread 后输出业务层 Sample/Frame、进程线程、事件、执行类型、Lost/Unwind/unknown 占位与 ContextSwitch |
| WBS-035 | 实现完成 | SQLite schema v1 覆盖 process/thread/event/file/symbol/frame/callsite/sample/lost/context switch/metadata，启用 WAL、外键、查询索引、`user_version` 迁移和幂等重开测试 |
| WBS-036 | 实现完成 | 规范化 Record 通过单写者、PreparedStatement、实体缓存和每 10000 samples 有界事务流式写入；百万样本复测 7541.260 ms、峰值堆增量约 153.47 MiB，未 OOM |
| WBS-037 | 实现完成 | 查询 API 支持时间、线程、事件过滤下的样本数、线程权重和 Top Functions inclusive/exclusive/sample/thread 统计；共享 callsite 由递归 CTE 展开 |
| WBS-038 | 实现完成 | LostSituation、Unwind Error、unknown symbol、空栈和未知 Record 均持久化并聚合为 `DataQualitySummary`，保留 unwind code/raw code/address 证据 |
| WBS-039 | 实现完成 | Home 可识别会话目录/包、protobuf 和 `perf.data`；在线采集成功自动在原会话转换、索引并打开报告；可选 mapping/symbols 与工具 hash/version 均保留 |
| WBS-040 | 已完成 | ReportController 管理会话打开/关闭、Loading/Ready/Failed 和报告页路由 |
| WBS-041 | 已完成 | Overview 展示时间、样本/event 权重、进程线程、热点、质量和产物 |
| WBS-042 | 已完成 | SQLite 时间桶查询支持当前时间、线程、事件过滤 |
| WBS-043 | 已完成 | Timeline Canvas 支持框选、线程过滤、W/A/S/D 和 Ctrl+滚轮导航 |
| WBS-044 | 已完成 | Top Functions 支持搜索、inclusive/exclusive/库/sample/thread 五种排序、升降序，并可分别跳转 CallTree 路径或 FlameGraph |
| WBS-045 | 已完成 | 递归 CTE 聚合正向/反向调用树并验证 inclusive/exclusive |
| WBS-046 | 已完成 | CallTree 可展开、切换方向、搜索函数并自动展开匹配路径，叶子可跳转 FlameGraph |
| WBS-047 | 已完成 | 当前过滤条件下生成稳定 id、父子关系、path 和权重坐标的 FlameGraph 模型 |
| WBS-048 | 已完成 | FlameGraph Canvas 支持搜索/高亮、点击下钻、双击重置和节点详情 |
| WBS-049 | 已完成 | Timeline 时间范围与线程过滤触发 Top/CallTree/Flame/Diagnostics 重算 |
| WBS-050 | 已完成 | 报告底部和诊断规则统一说明 sample/event 权重不是精确耗时 |
| WBS-060~064 | 已完成 | 可注册诊断引擎、质量/CPU/线程/权重规则和证据卡片跳转均有测试 |
| WBS-065 | 已完成 | 确定性会话 zip、schema 清单、SHA-256、唯一导入目录和完整性验证 |
| WBS-066~068 | 已完成 | JSON/Top CSV/CallTree CSV/PNG/原始 protobuf 导出及测试 |
| WBS-069~071 | 已完成 | simpleperf report、report_html.py 适配以及 Android Studio/Perfetto 入口 |
| WBS-080 | 已完成 | 百万记录导入 7541.260 ms、峰值堆增量约 153.47 MiB，Top 20 为 669.926 ms |
| WBS-081 | 已完成 | viewport/LOD 后最多 20000 可见节点一批，用户可继续渐进绘制；10 万节点准备 P95 0.389 ms |
| WBS-082 | 已完成 | 成功/失败/取消都独立清理设备文件并保留 cleanup 日志；会话导入临时目录失败回收 |
| WBS-083 | 已完成 | 本机 macOS arm64 portable app image 和 `Android Performance Studio-1.0.0.dmg` 已生成 |
| WBS-084~085 | CI 待验收 | Compose 已配置 MSI/DEB/RPM/portable；CI 分平台执行原生打包并上传 artifacts |
| WBS-086 | CI/真机待验收 | 三平台 check 内含空格/中文路径 Golden 导入→分析→导出→打包→重开；真实设备采集仍需硬件 |
| WBS-087 | 已完成 | API 29/31/34/36 与 arm64-v8a/armeabi-v7a/x86_64 代表性 protobuf 流式兼容测试 |
| WBS-088 | 已完成 | simpleperf `--sort symbol` exclusive 权重百分比容差比较与 report_html 非空验证 |
| WBS-090 | 已完成 | `docs/user-guide.md` 覆盖安装、采集、导入、报告、快捷键和导出 |
| WBS-091 | 已完成 | `docs/troubleshooting.md` 覆盖 ADB、权限、simpleperf、取消、符号、丢样和平台问题 |
| WBS-092 | 已完成 | 4 KB 可复现 Golden 会话包可导入查询；生成任务和固定 SHA-256 均有测试 |
| WBS-093 | 已完成 | `docs/release-checklist.md` 分离自动化、真机、三平台安装和签名门禁 |
| WBS-094 | RC | 功能和本机自动化评审通过；正式 V0.1 仍以 clean CI、真机和签名门禁全勾选为准 |

交互约束补充：Timeline/FlameGraph 导航动作统一由 `visualization` 模块提供，默认采用 Perfetto 风格的 `W/A/S/D` 与 `Ctrl + 鼠标滚轮`。

WBS-008 的可复现任务、原始 JSON 和测量边界见 `docs/p0-performance-poc.md`。当前帧指标是交给 Compose Canvas 前的 CPU 侧 viewport/LOD 模型准备耗时；GPU 呈现与真实输入延迟留到 P4 UI 集成时补测。

ADB 自检可通过 `./gradlew :device-adb:runAdbSelfCheck` 复现，输出 ADB 版本、发现来源、设备数量、各设备原始状态、能力以及目标数量。`ANDROID_SDK_ROOT` 仅作为旧环境兼容回退，新配置以 `ANDROID_HOME` 为准。

Simpleperf protobuf schema 来自 AOSP `refs/tags/android-17.0.0_r1`，来源和摘要见 `parser-simpleperf-proto/src/main/proto/README.md`。转换命令与 AOSP `view_the_profile.md` 保持一致；解析器不会对整个 trace 调用单体 `parseFrom()`。

离线导入链路通过同步 Record callback 直接写入有界 SQLite writer，不在 application 层缓存完整 protobuf 或 sample 列表。解析失败或取消时删除可重建的 SQLite/WAL/SHM，保留原始文件与失败元数据用于复盘。

## 5. 详细 WBS

### 5.1 P0 项目启动与基线

| ID | 任务 | 优先级 | 估算 | 依赖 | 验收 |
|---|---|---:|---:|---|---|
| WBS-001 | 初始化 Gradle/Kotlin/Compose Desktop 工程 | P0 | 1d | 无 | App 可启动 |
| WBS-002 | 建立模块结构与基础依赖 | P0 | 1d | WBS-001 | 模块边界清晰，能编译 |
| WBS-003 | 配置 ktlint/detekt/test | P0 | 1d | WBS-001 | CI 本地命令可跑 |
| WBS-004 | 收集测试样本 `perf.data` / protobuf | P0 | 1d | 无 | 样本纳入 test-fixtures 或外部说明 |
| WBS-005 | 定义统一错误模型与 Result 类型 | P0 | 0.5d | WBS-002 | 外部命令错误可分类 |
| WBS-006 | 三平台 CI 与空壳安装/启动 PoC | P0 | 3d | WBS-001 | Windows/Linux/macOS clean runner 均可启动 |
| WBS-007 | platform-toolchain 接口与工具 manifest | P0 | 1.5d | WBS-002 | OS 差异不进入 UI/领域模块 |
| WBS-008 | 百万 Record/SQLite/Canvas 选型 PoC | P0 | 4d | WBS-001,WBS-002 | 记录导入耗时、峰值内存和交互帧表现 |

### 5.2 P1 ADB 与设备能力

| ID | 任务 | 优先级 | 估算 | 依赖 | 验收 |
|---|---|---:|---:|---|---|
| WBS-010 | ADB 路径发现与配置 | P0 | 1d | WBS-005 | 可检测 SDK adb 或用户配置路径 |
| WBS-011 | 子进程执行器：stdout/stderr/timeout/cancel | P0 | 1.5d | WBS-005 | 命令可取消并返回结构化结果 |
| WBS-012 | `adb devices` 解析与设备状态刷新 | P0 | 1d | WBS-011 | 插拔设备状态正确 |
| WBS-013 | 设备属性读取 | P0 | 1d | WBS-012 | 获取 model、ABI、SDK、Android version |
| WBS-014 | root/profileable/simpleperf 能力检测 | P0 | 1.5d | WBS-013 | UI 展示 Ready/Limited/Blocked |
| WBS-015 | App/进程/PID/线程列表查询 | P0 | 2d | WBS-013 | 可搜索目标并刷新 |
| WBS-016 | 设备与目标选择 UI | P0 | 2d | WBS-012~015 | 可选设备和目标进入采集页 |

### 5.3 P2 Simpleperf 采集闭环

| ID | 任务 | 优先级 | 估算 | 依赖 | 验收 |
|---|---|---:|---:|---|---|
| WBS-020 | 采样参数模型 | P0 | 1d | WBS-016 | 可序列化为命令参数 |
| WBS-021 | 常用采样模板 | P0 | 1d | WBS-020 | App CPU/UI Thread/Native 模板可选 |
| WBS-022 | 一键获取数据 UI | P0 | 1d | WBS-020 | 用户无需输入命令即可启动自动采集 |
| WBS-023 | 设备端 simpleperf 选择/推送策略 | P0 | 2d | WBS-014 | 根据设备能力选择 bundled/device simpleperf |
| WBS-024 | 采集状态机 | P0 | 2d | WBS-011,WBS-020 | 状态流转完整，支持取消 |
| WBS-025 | 文件拉取与会话目录 | P0 | 1.5d | WBS-024 | 生成标准 session 目录 |
| WBS-026 | 采集日志持久化 | P0 | 0.5d | WBS-024 | 保存命令、stdout、stderr、退出码 |
| WBS-027 | 端到端 App 采集 smoke test | P0 | 1d | WBS-020~026 | 可生成有效 `perf.data` |

### 5.4 P3 转换、解析、SQLite

| ID | 任务 | 优先级 | 估算 | 依赖 | 验收 |
|---|---|---:|---:|---|---|
| WBS-030 | host simpleperf 路径管理 | P0 | 1.5d | WBS-010,WBS-007 | 可找到 Windows/Linux/macOS host simpleperf 并校验版本/hash |
| WBS-031 | `report-sample --protobuf` 转换器 | P0 | 1.5d | WBS-030 | `perf.data` 可转为 protobuf/trace |
| WBS-032 | Simpleperf protobuf schema 接入 | P0 | 1d | WBS-031 | Kotlin protobuf 类生成成功 |
| WBS-033 | Record 流式读取器 | P0 | 3d | WBS-032 | 读取 SIMPLEPERF 头、版本、长度前缀、Record 流 |
| WBS-034 | Profile Normalizer | P0 | 2d | WBS-033 | 输出 Process/Thread/Sample/Frame/Callsite |
| WBS-035 | SQLite schema v1 | P0 | 2d | WBS-034 | 建表、索引、迁移可运行 |
| WBS-036 | 批量写入与事务优化 | P0 | 2d | WBS-035 | 百万样本导入不 OOM |
| WBS-037 | 基础查询 API | P0 | 2d | WBS-036 | 查询样本数、线程、Top functions |
| WBS-038 | 数据质量提取 | P0 | 1d | WBS-033 | LostSituation/Unwind/unknown 可统计 |
| WBS-039 | 导入已有 perf.data/protobuf | P0 | 1.5d | WBS-031~037 | 离线导入可用 |

### 5.5 P4 基础报告视图

| ID | 任务 | 优先级 | 估算 | 依赖 | 验收 |
|---|---|---:|---:|---|---|
| WBS-040 | Report 页面路由与状态 | P0 | 1d | WBS-037 | 打开会话进入报告页 |
| WBS-041 | Overview 卡片 | P0 | 2d | WBS-037,WBS-038 | 展示会话和质量概要 |
| WBS-042 | Timeline 数据聚合 | P0 | 2d | WBS-037 | 时间桶查询可用 |
| WBS-043 | Timeline UI | P0 | 3d | WBS-042 | 支持缩放、框选、线程过滤 |
| WBS-044 | Top Functions 表格 | P0 | 2d | WBS-037 | 支持排序、搜索、跳转 |
| WBS-045 | CallTree / Reverse CallTree 聚合 | P0 | 3d | WBS-037 | 正反调用树结果正确 |
| WBS-046 | CallTree UI | P0 | 2d | WBS-045 | 可展开、过滤、跳转 |
| WBS-047 | FlameGraph 聚合模型 | P0 | 3d | WBS-037 | 当前过滤条件下生成树 |
| WBS-048 | FlameGraph Canvas UI | P0 | 4d | WBS-047 | 支持下钻、搜索、高亮、面包屑 |
| WBS-049 | Timeline 与 FlameGraph 联动 | P0 | 1.5d | WBS-043,WBS-048 | 时间范围变化后重算 |
| WBS-050 | Sample/Event 权重语义提示 | P0 | 0.5d | WBS-041~048 | 所有相关页面可见提示 |

### 5.6 P5 诊断、导入导出、外部验证

| ID | 任务 | 优先级 | 估算 | 依赖 | 验收 |
|---|---|---:|---:|---|---|
| WBS-060 | 规则引擎框架 | P0 | 1.5d | WBS-038,WBS-037 | 规则可注册、运行、输出证据 |
| WBS-061 | 数据质量规则 | P0 | 1d | WBS-060 | 丢样/截断/unknown 提示 |
| WBS-062 | CPU 热点规则 | P0 | 1d | WBS-060 | Top hotspot 卡片可跳转 |
| WBS-063 | 线程热点规则 | P0 | 1d | WBS-060 | 主线程/线程池热点提示 |
| WBS-064 | Diagnostics UI | P0 | 2d | WBS-061~063 | 卡片展示等级、结论、证据、建议 |
| WBS-065 | Session package 导出/导入 | P0 | 2d | WBS-025,WBS-035 | zip 包可复现打开 |
| WBS-066 | JSON/CSV 导出 | P0 | 1.5d | WBS-044,WBS-045 | Top/CallTree 可导出 |
| WBS-067 | 截图导出 | P1 | 1d | WBS-048 | 可导出当前视图图像 |
| WBS-068 | 保留和导出原始 protobuf | P0 | 0.5d | WBS-031 | protobuf 可在会话包中找到 |
| WBS-069 | `simpleperf report` 校验适配器 | P0 | 1d | WBS-031,WBS-044 | Top 函数可对比 |
| WBS-070 | `report_html.py` 校验适配器 | P0 | 1d | WBS-031 | 可生成 HTML 报告用于人工对照 |
| WBS-071 | Android Studio/Perfetto trace 打开说明 | P1 | 0.5d | WBS-031 | UI 提供外部打开/导出入口 |

### 5.7 P6 稳定性与性能

| ID | 任务 | 优先级 | 估算 | 依赖 | 验收 |
|---|---|---:|---:|---|---|
| WBS-080 | 百万样本导入性能优化 | P0 | 3d | WBS-036 | 达到内存和耗时目标 |
| WBS-081 | 大型 FlameGraph 渲染优化 | P0 | 2d | WBS-048 | 操作不卡死，支持渐进加载 |
| WBS-082 | 错误恢复与临时文件清理 | P0 | 1.5d | WBS-024,WBS-025 | 失败不污染设备，不丢本地证据 |
| WBS-083 | macOS 打包 | P0 | 1d | WBS-001 | 生成可安装/运行包 |
| WBS-084 | Windows 打包 | P0 | 1.5d | WBS-001 | 生成可运行包 |
| WBS-085 | Linux 打包 | P0 | 2d | WBS-001 | 生成 deb/rpm/portable 发行物并在 X11/Wayland 启动 |
| WBS-086 | 三平台 clean VM 端到端矩阵 | P0 | 3d | WBS-027,WBS-065,WBS-083~085 | 三端采集、导入、分析、导出均通过 |
| WBS-087 | 兼容样本矩阵测试 | P0 | 2d | WBS-039~049 | 多 Android 版本/ABI 样本通过 |
| WBS-088 | 外部工具结果对比 | P0 | 1d | WBS-069,WBS-070 | 核心统计无明显偏差 |

### 5.8 P7 发布准备

| ID | 任务 | 优先级 | 估算 | 依赖 | 验收 |
|---|---|---:|---:|---|---|
| WBS-090 | 用户使用手册 | P0 | 1d | 全部功能 | 覆盖采集、导入、查看、导出 |
| WBS-091 | 故障排查文档 | P0 | 1d | 错误模型 | 覆盖权限、符号、丢样、设备连接 |
| WBS-092 | 示例会话包 | P0 | 0.5d | WBS-065 | 可用于演示 |
| WBS-093 | Release checklist | P0 | 0.5d | WBS-080~088 | 打包、测试、文档均勾选 |
| WBS-094 | V0.1 验收评审 | P0 | 1d | WBS-090~093 | 形成 release candidate |

## 6. 测试计划

### 6.1 单元测试

| 模块 | 测试重点 |
|---|---|
| Command Executor | timeout、cancel、stdout/stderr、exit code |
| ADB Parser | devices、props、ps、thread 列表解析 |
| 参数模型 | 模板生成命令、非法组合校验 |
| Protobuf Reader | magic header、version、length prefix、Record 边界 |
| Normalizer | sample、thread、frame、callsite 去重 |
| SQLite DAO | schema、迁移、索引、批量写入 |
| Aggregator | Top、CallTree、FlameGraph 结果 |
| Rules | 阈值、等级、证据链接 |

### 6.2 集成测试

| 场景 | 验收 |
|---|---|
| 导入 perf.data | 自动转换、解析、入库、打开报告 |
| 导入 protobuf | 跳过转换直接解析 |
| App 在线采集 | 生成有效会话和报告 |
| 采集中取消 | 设备临时文件清理，本地日志保留 |
| 权限不足 | 明确错误和建议 |
| 符号缺失 | unknown 比例提示和补救入口 |

### 6.3 性能测试

| 指标 | V0.1 目标 |
|---|---:|
| 100 万 samples 导入 | 不 OOM，耗时在可接受范围内 |
| Top Functions 查询 | 秒级响应 |
| Timeline 重聚合 | 交互级或后台可取消 |
| FlameGraph 绘制 | 大树渐进渲染，不冻结 UI |
| SQLite 文件大小 | 可解释、可清理、有会话包压缩 |

### 6.4 三平台测试矩阵

| 平台 | 最小矩阵 | 核心验证 |
|---|---|---|
| Windows | Windows 10/11 x64；arm64 安装/启动 | 路径空格/中文、ADB、子进程取消、SQLite native、安装与卸载 |
| Linux | Ubuntu 20.04/22.04/24.04 x64；X11/Wayland | 动态库、字体、权限、ADB、SQLite native、deb/rpm/portable |
| macOS | macOS 13+ Apple Silicon；Intel 若纳入范围则单列 | Gatekeeper、签名/公证、ADB、SQLite native、DMG/PKG |

三平台必须导入相同 Golden 数据，并比较 sample 数、Top Functions、CallTree 和 FlameGraph 聚合摘要。

### 6.5 正确性验证

必须建立 Golden 样本，并与以下工具对比：

1. `simpleperf report --sort symbol`
2. `simpleperf report --sort tid,comm`
3. `report_html.py`
4. Android Studio Profiler 打开的 `perf.trace`
5. Perfetto / Firefox Profiler（V0.2 Gecko Profile 后）

V0.1 验证重点：

- Sample 数量。
- 线程列表。
- Top Functions 排名。
- inclusive/exclusive 权重口径。
- FlameGraph 栈路径。
- 丢样、截断、unknown symbol 统计。

## 7. 交付物

| 交付物 | 路径/形式 |
|---|---|
| 需求文档 | `docs/requirements.md` |
| 产品设计文档 | `docs/product-design.md` |
| 开发计划 | `docs/development-plan.md` |
| 技术栈调研与 ADR | `docs/technology-stack-research.md` |
| 开发跟踪表 | `docs/simpleperf_client_development_tracker.xlsx` |
| 安装包 | release artifacts |
| 示例数据 | test fixtures / sample session package |
| 用户手册 | docs 后续新增 |
| 故障排查 | docs 后续新增 |

## 8. 风险管理

| 风险 | 等级 | 触发信号 | 应对 |
|---|---|---|---|
| Protobuf 格式理解错误 | 高 | 与 Android Studio / report_html 差异大 | 优先做流式解析测试和外部校验 |
| 大文件导入慢 | 高 | 百万样本导入耗时不可接受 | 批量事务、索引延后、后台任务、采样聚合缓存 |
| 权限边界影响体验 | 中 | 多数 release App 不能采 | 能力检测前置，文档说明 profileable/debug/root |
| 符号缺失导致结果不可用 | 中 | unknown 占比高 | 首版引导用户添加 mapping/native libs |
| UI 渲染卡顿 | 中 | 火焰图节点多、Timeline 点多 | 虚拟化、分层聚合、渐进绘制 |
| 跨平台 simpleperf 包管理 | 高 | Windows/Linux/macOS executable、权限、路径或版本不一致 | platform-toolchain、工具 manifest、三平台 Golden 验证 |
| Compose 平台支持缺口 | 高 | Intel Mac 或 Linux 无障碍要求不满足 | 第 1 周 PoC；触发门禁时切换 Flutter Desktop |
| 规则误报 | 中 | 诊断结论与证据不一致 | 规则只输出证据型建议，不做绝对根因判断 |

## 9. Definition of Done

功能完成必须满足：

1. 有需求编号或 WBS 编号对应。
2. 有单元测试或集成测试；如无法自动化，必须有手工验收记录。
3. 失败路径有错误提示和日志。
4. 文档或开发跟踪表已更新。
5. 与相关外部工具对比不出现明显数据口径错误。
6. UI 不阻塞；长任务可取消或后台执行。
7. Windows、Linux、macOS 的 clean VM 端到端矩阵通过，核心聚合结果一致。

## 10. V0.2 待办池

| 功能 | 价值 | 初步估算 |
|---|---|---:|
| CPU Sample Heatmap | 发现周期性和突发热点 | 5-7d |
| Differential FlameGraph | 优化前后比较 | 7-10d |
| Gecko Profile 导出 | Perfetto / Firefox 兼容 | 3-4d |
| Folded Stacks 导出 | FlameGraph 生态兼容 | 2-3d |
| PProf 导出 | 聚合、Pivot、Diff 生态 | 3-5d |
| Perfetto Trace Processor 接入 | sched/Binder/FrameTimeline 联合分析 | 10-15d |
| libsimpleperf_report 接入 | 更直接的数据访问与兼容能力 | 5-8d |
| 线程池归一化分组 | 合并 Binder/AsyncTask/Coroutine 同类线程 | 3-4d |

## 11. 参考资料

- ChatGPT 共享对话：https://chatgpt.com/share/6a54e924-0698-83ea-a051-ef5ff3b4db94
- Android NDK Simpleperf：https://developer.android.com/ndk/guides/simpleperf
- AOSP Simpleperf README：https://android.googlesource.com/platform/system/extras/+/master/simpleperf/doc/README.md
- AOSP Simpleperf View the profile：https://android.googlesource.com/platform/system/extras/+/master/simpleperf/doc/view_the_profile.md
- 跨平台技术栈调研：`docs/technology-stack-research.md`
