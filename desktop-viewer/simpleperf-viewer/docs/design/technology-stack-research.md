# Simpleperf Viewer 当前技术栈与运行时约束

- 核对日期：2026-10-30。
- 本文保留已采用技术和有效约束，不再维护早期候选方案评分或替换框架建议。
- 依赖版本以 [版本目录](../../../gradle/libs.versions.toml) 为准；模块以 [settings.gradle.kts](../../settings.gradle.kts) 为准。
- 平台支持目标不等于已验收；发布状态以 [发布门禁](../records/release-checklist.md) 为准。

## 1. 已采用技术

- Kotlin/JVM 21、Compose Multiplatform Desktop；发行物包含裁剪运行时，用户无需另装 JDK。
- `kotlinx.coroutines` / Flow：后台任务、进度、取消和不可变状态发布。
- JVM `ProcessBuilder`：通过参数数组调用 ADB、device/host Simpleperf 和符号工具。
- `protobuf-java`：固定 AOSP schema 生成类，配合有界 Simpleperf Record 流读取。
- xerial `sqlite-jdbc`、JDBC/PreparedStatement、WAL：会话索引、批量写入、迁移和聚合查询。
- Compose Canvas / Skia：原生 Timeline、Flame Graph、Stack Chart 等视图；表格和树使用虚拟化。
- Gradle、Compose native distributions / jpackage：按宿主平台生成 DMG、MSI、DEB、RPM 和 portable app image。

原生分析工作区不嵌入 React 或 WebView。另有外部浏览器引擎入口：本地 Firefox Profiler 模式通过 `127.0.0.1` 同源提供固定站点和 `perf_data.json.gz`，官方网站模式仍打开外站；两者不能混同为原生 Compose 渲染或无条件离线可用。

## 2. 工具进程与平台边界

- 平台进程与工具发现实现位于共享 `platform-core/host-toolchain`、`platform-core/adb-core`；Simpleperf 模块保留领域适配，不复制平台逻辑。
- 使用 executable 和参数列表执行进程，不拼接 shell 命令；stdout/stderr 并发持续消费，保留有界输出、截断标记、退出码和失败证据。
- 超时、显式取消和协程取消必须传播到进程树。正常 Stop、Cancel 与独立远端清理的语义不可合并；清理失败不能抹去已拉回的证据。
- ADB 和 host Simpleperf 的配置、bundled、SDK、PATH 等来源按各自发现器的确定优先级处理。GUI 启动不能假定继承交互 shell 的 PATH。
- 工具来源、版本、ABI 和 SHA-256 可追溯；bundled 资产提取后继续校验，不把存在文件等同于可执行或兼容。

## 3. 解析、存储与迁移

- `perf.data` 默认通过官方 `report-sample --protobuf --show-callchain` 转换，再按 `SIMPLEPERF` magic、版本和长度前缀逐条解析。
- 每条 Record 有大小上限，错误保留 record index 与 byte offset；禁止把整个文件一次性读入或对整份文件调用单体 `parseFrom()`。
- 原始 `perf.data`、protobuf、符号、mapping 和命令日志是可复核证据；SQLite 是可重建分析索引。失败时不能连同原始证据一起清理。
- 解析到写入采用有界批次和背压；SQL 使用参数绑定，UI 只接收聚合、分页或视图投影，不持有百万 sample 明细。
- 数据库迁移先在副本上完成并校验，再替换主库；保留旧库及摘要。失败时保持原始库，并通过 legacy read-only 路径打开，不能静默覆盖。
- schema 版本、迁移和查询语义由 Golden/回归测试约束，不把早期 schema v1 PoC 当成当前数据库版本声明。

## 4. 查询与绘制

- 文件、进程和数据库 I/O 离开 UI 线程；解析、过滤、变换和聚合在后台执行。
- `ProfileQueryCoordinator` 负责取消旧查询和代次隔离；旧投影不得覆盖新会话、新范围或新筛选。
- 界面消费不可变快照，绘制使用 viewport、可见行和有界缓存；不为每条 sample 创建 Composable。
- hover、选择和滚动不应重建完整调用树；主题变化只影响绘制样式，不改变统计或符号身份。
- 上述是持续约束，不是所有面板均已满足的验收结论。尤其 Stack Chart 仍有逐栈块交互覆盖层和全表扫描问题，按 [未实现的性能方案](2026-10-29-stack-chart-performance-optimization-plan.md) 跟踪。

## 5. 平台与性能门禁

- Windows、Linux、macOS 仍是交付目标，每个平台使用原生 runner 打包并完成安装、启动、在线采集、离线导入、分析和导出验证。
- macOS 以 Apple Silicon 为首发基线；Intel 若纳入发布范围须单列验证。Windows arm64、Linux 发行版和 X11/Wayland 覆盖仍按产品需求与开发计划逐项验收，不能从单机结果外推。
- 屏幕阅读器、键盘和平台无障碍能力单独验证，尤其 Linux；自动测试或启动成功不能替代合规验收。
- Golden 数据验证三平台 sample 数、Top Functions、Call Tree 和 Flame Graph 聚合一致。签名、公证、安装器与下载制品完整性分别属于发布门禁。
- 百万样本导入、深栈、高节点数、取消和内存峰值保留可复现实验；CPU 模型准备耗时不代表 GPU 呈现或原生窗口输入延迟。

## 6. 相关文档

- [产品范围与验收](../requirements/requirements.md)
- [产品与交互契约](product-design.md)
- [开发计划及尚未闭环的路线](../requirements/development-plan.md)
- [Firefox Flame Graph 兼容与验证边界](../requirements/firefox-flame-graph-compatibility.md)
- [P0 性能方法、原始结果和适用边界](../records/p0-performance-poc.md)
- [发布门禁](../records/release-checklist.md)
