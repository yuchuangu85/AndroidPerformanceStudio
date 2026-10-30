# Android Performance Studio

基于 Simpleperf 的跨平台 Android CPU Profile 桌面分析客户端。

## 当前阶段

V0.1 处于 release candidate 阶段。以下为实现能力概览，不代表真机、跨平台安装、签名或原生交互已经验收；状态以 [发布门禁](docs/records/release-checklist.md) 为准：

- Kotlin/JVM 21 + Compose Multiplatform Desktop 工程；
- 按领域边界拆分的 Gradle 多模块结构；
- 统一错误模型与 host platform/toolchain 基础接口；
- 固定版本的 AOSP `perf.data` Golden 样本；
- 百万记录 SQLite 与高密度 Timeline/FlameGraph 投影 PoC；
- 跨平台、无 shell 的结构化子进程执行器，支持并发消费输出、超时与取消；
- 确定优先级的 ADB 自动发现与本机版本自检；
- `adb devices -l` 多设备解析与可取消刷新，保留离线、未授权和无权限状态；
- 单次 bulk `getprop` 读取设备 model、ABI、SDK 与 Android version；
- root/userdebug、profileable、device simpleperf 版本和事件列表探测，聚合 Ready/Limited/Blocked；
- App 包名、运行进程/PID 和线程列表查询，支持刷新与本地搜索；
- Device & Target 桌面界面，支持设备能力、App/进程/线程选择并进入采集配置；
- 嵌入根应用时，主界面和完整报告工作区跟随根应用的语言与主题；页面本身不显示语言和主题设置控件；
- Simpleperf 参数模型、五类模板和高级参数设置；目标与报告共用工作区，可一键获取数据，应用自动生成并执行采集命令；
- 设备未提供 simpleperf 时，按 ABI 部署随应用提供的 Android NDK r27b simpleperf，并校验 SHA-256；
- 可取消的采集状态机、标准 session 目录、`perf.data` 拉取和命令/stdout/stderr/退出码持久化；
- 桌面 Start/Stop-and-analyze/Cancel 控制与 Preparing/Recording/Stopping/Pulling/结果状态；
- host simpleperf 配置/bundled/PATH 发现、版本与 SHA-256 校验；
- `report-sample --protobuf --show-callchain` 转换器；
- 固定 AOSP schema 的 protobuf 代码生成，以及带长度上限和 byte offset 错误定位的流式 Record reader；
- protobuf 到业务层 Process/Thread/Sample/Frame 的有状态 normalizer，保留 Lost、Unwind 与 unknown 数据质量证据；
- SQLite schema v1、共享 Frame/Callsite、WAL、有界事务批量写入、迁移和时间/线程/事件过滤查询；
- 样本数、线程、Top Functions inclusive/exclusive 与 Lost/Unwind/unknown/空栈质量统计；
- 在线采集自动转换/索引/打开报告；Home 支持会话、`perf.data`、protobuf、Firefox Profiler Gecko `json.gz` 导入及可选 mapping/symbols；
- Firefox Profiler 本地引擎使用固定版本站点，通过 `127.0.0.1` 同源提供页面和 `perf_data.json.gz`；Firefox Profiler 引擎继续打开官方网站；
- 常驻 Timeline、共享范围/筛选/详情，以及 Overview、Top Functions、Call Tree、Flame Graph、Stack Chart、Marker Chart、Marker Table 七标签报告；Diagnostics 位于 Overview；
- Timeline 支持 Perfetto 风格缩放平移；Flame Graph 支持方向键选择、`W/A/S/D` 视口导航和源码/反汇编/符号详情，差异见 [兼容矩阵](docs/requirements/firefox-flame-graph-compatibility.md)；
- 证据型数据质量、CPU 热点和线程热点诊断；
- `.apsession.zip`、Firefox Profiler Gecko `json.gz`、JSON、CSV、PNG、原始 protobuf 导入导出和外部验证入口；
- Flame Graph 可见行虚拟化和百万样本性能基线；Stack Chart 大数据交互优化仍 [未实现](docs/design/2026-10-29-stack-chart-performance-optimization-plan.md)；
- 会话包 hash 校验、路径穿越/符号链接/解压炸弹防护；
- Android 10–16、arm64-v8a/armeabi-v7a/x86_64 兼容矩阵；
- macOS/Windows/Linux clean-runner Golden 工作流和 DMG/MSI/DEB/RPM/portable CI 打包。

## 本地验证

在 `desktop-viewer` 下执行，结果需按自动测试、性能 PoC 和原生/打包验收分别记录：

```bash
./gradlew :simpleperf-viewer:checkAll
./gradlew :simpleperf-viewer:simpleperf-test-fixtures:runP0PerformancePoc
./gradlew :simpleperf-viewer:simpleperf-test-fixtures:runFlameGraphPerformancePoc
./gradlew :simpleperf-viewer:simpleperf-test-fixtures:generateSampleSession
./gradlew :simpleperf-viewer:app-desktop:run
./gradlew :simpleperf-viewer:app-desktop:createDistributable
```

P0 性能 PoC 的方法、结果和适用边界见 [性能记录](docs/records/p0-performance-poc.md)。CPU 侧模型准备耗时不代表真实窗口输入或 GPU 呈现延迟。

示例会话：`test-fixtures/src/main/resources/sessions/golden.apsession.zip`。

使用和发布资料：

- [用户手册](docs/user/user-guide.md)
- [故障排查](docs/user/troubleshooting.md)
- [Release checklist](docs/records/release-checklist.md)
- [V0.1 RC release notes](docs/records/release-notes-v0.1-rc.md)
- [文档索引](docs/README.md)

产品范围、当前技术栈、交互契约和未完成路线见 [文档索引](docs/README.md)；历史实施过程由 Git 历史保留。
