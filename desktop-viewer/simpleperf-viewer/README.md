# Android Performance Studio

基于 Simpleperf 的跨平台 Android CPU Profile 桌面分析客户端。

## 当前阶段

V0.1 release candidate 功能已实现，等待 GitHub Actions 三平台安装包和真机人工门禁：

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
- 独立运行时，主界面和完整报告工作区支持系统/简体中文/英文以及系统/浅色/深色主题切换并持久化选择；嵌入根应用时由根应用顶部设置栏统一管理这些通用属性；
- Simpleperf 参数模型、五类模板、event/frequency/period/duration/callgraph/scope 高级编辑与同步命令预览；
- 可取消的采集状态机、标准 session 目录、`perf.data` 拉取和命令/stdout/stderr/退出码持久化；
- 桌面 Start/Stop-and-analyze/Cancel 控制与 Preparing/Recording/Stopping/Pulling/结果状态；
- host simpleperf 配置/bundled/PATH 发现、版本与 SHA-256 校验；
- `report-sample --protobuf --show-callchain` 转换器；
- 固定 AOSP schema 的 protobuf 代码生成，以及带长度上限和 byte offset 错误定位的流式 Record reader；
- protobuf 到业务层 Process/Thread/Sample/Frame 的有状态 normalizer，保留 Lost、Unwind 与 unknown 数据质量证据；
- SQLite schema v1、共享 Frame/Callsite、WAL、有界事务批量写入、迁移和时间/线程/事件过滤查询；
- 样本数、线程、Top Functions inclusive/exclusive 与 Lost/Unwind/unknown/空栈质量统计；
- 在线采集自动转换/索引/打开报告；Home 支持会话、`perf.data`、protobuf 导入及可选 mapping/symbols；
- Overview、Timeline、Top Functions、正反 CallTree、FlameGraph 联动报告；
- Timeline/FlameGraph 使用 Perfetto 风格 `W/A/S/D` 与 `Ctrl + 鼠标滚轮` 缩放平移；
- 证据型数据质量、CPU 热点和线程热点诊断；
- `.apsession.zip`、JSON、CSV、PNG、原始 protobuf 导入导出和外部验证入口；
- 大火焰图 20,000 可见节点分页投影，百万样本性能基线；
- 会话包 hash 校验、路径穿越/符号链接/解压炸弹防护；
- Android 10–16、arm64-v8a/armeabi-v7a/x86_64 兼容矩阵；
- macOS/Windows/Linux clean-runner Golden 工作流和 DMG/MSI/DEB/RPM/portable CI 打包。

## 本地验证

```bash
./gradlew check
./gradlew :device-adb:runAdbSelfCheck
./gradlew :test-fixtures:runP0PerformancePoc
./gradlew :test-fixtures:generateSampleSession
./gradlew :app-desktop:run
./gradlew :app-desktop:createDistributable
```

P0 性能 PoC 的方法、结果和适用边界见 `docs/p0-performance-poc.md`。

示例会话：`test-fixtures/src/main/resources/sessions/golden.apsession.zip`。

使用和发布资料：

- [用户手册](docs/user-guide.md)
- [故障排查](docs/troubleshooting.md)
- [Release checklist](docs/release-checklist.md)
- [V0.1 RC release notes](docs/release-notes-v0.1-rc.md)

产品范围、技术决策和 WBS 以 `docs/` 下文档为准。
