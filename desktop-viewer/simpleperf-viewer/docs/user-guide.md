# Android Performance Studio V0.1 用户手册

## 1. 安装与启动

V0.1 提供自包含 JRE 的桌面发行物：macOS DMG、Windows MSI、Linux DEB/RPM，以及各平台 portable app image。开发环境需要 JDK 21，可执行：

```bash
./gradlew :app-desktop:run
```

首次启动会自动查找 `ANDROID_HOME`、兼容的 `ANDROID_SDK_ROOT`、`PATH` 和常见 SDK 目录中的 `adb`。如果未找到，可在启动前把 platform-tools 加入 `PATH`。

## 2. 在线采集

1. 通过 USB 连接 Android 设备并允许调试授权。
2. 在 **Device & Target** 刷新设备，确认能力为 Ready 或阅读 Limited/Blocked 原因。
3. 搜索并选择 App/进程；需要时选择线程。
4. 进入 **Capture**，选择 App CPU、UI Thread、Native Hotspot、Low Overhead 或 System Process 模板。
5. 可选：在 Advanced parameters 中编辑 event、frequency/period、duration（留空表示手动停止）、callgraph 和 user/kernel 范围。
6. 点击 **Get data / 获取数据**，客户端会自动生成并执行 Simpleperf 采集命令，不需要复制或手动输入命令。Recording 时可选择 **Stop and analyze**（SIGINT 正常收尾并继续拉取）或 **Cancel**（终止当前任务）。
7. 成功后会话目录保存 `perf.data`、命令、stop/record/pull/cleanup 日志和元数据，自动转换、索引并进入报告。

失败或取消也会保留本地证据；设备端 `/data/local/tmp/aps/perf.data` 会通过独立、不继承取消信号的清理命令删除。

## 3. 离线导入

Home 的 Open 支持：

- 会话目录：直接打开含 `profile.sqlite` 的目录；
- `.apsession.zip`：校验清单和 SHA-256 后解压到用户目录；
- Simpleperf protobuf：可选 ProGuard mapping 与 symbols/binary_cache，随后流式解析并导入 SQLite；
- `perf.data`：选择 host simpleperf，可选 mapping 与 symbols/binary_cache，执行 `report-sample --protobuf --show-callchain` 再导入。

演示包位于 `test-fixtures/src/main/resources/sessions/golden.apsession.zip`，可直接从 Home 打开。

### 3.1 Profile database migration

- Migration works on a copy; `profile.sqlite` is replaced only after the migrated candidate and retained evidence pass verification.
- The first successful migration retains `profile.v1.sqlite` and its SHA-256 in `migration.properties`.
- Backup and metadata publication uses fail-closed hard links; if publication, verification, or migration fails, the application opens the original database in legacy read-only mode.
- Availability is reported as exactly one of: Available, Empty, Not collected, Unavailable, Unauthorized, Failed, or Not applicable.
- Users must copy the complete session directory before attempting manual SQLite repair.

## 4. 阅读报告

- **Overview**：会话时长、样本数、event 权重、线程和数据质量摘要。
- **Timeline**：时间桶、拖拽框选、线程过滤；时间范围会重算其他视图。
- **Top Functions**：inclusive/exclusive、库、sample、线程排序和搜索；Path/Flame 分别跳转调用路径或火焰图。
- **Call Tree / Reverse Call Tree**：搜索会自动展开匹配路径，点击叶子跳转火焰图。
- **FlameGraph**：搜索会重算火焰图和联动 Call Tree；可切换 Forward/Inverted、实现类型过滤，并通过点击/键盘选择帧、打开源码/反汇编/符号 fallback 详情，或应用 Focus/Merge/Drop/Collapse 变换。
- **Diagnostics**：丢样、unwind、unknown、CPU/线程热点的等级、证据和建议。

Timeline 与 FlameGraph 的键盘/鼠标入口：

| 区域 | 动作 | 快捷键 / 手势 |
|---|---|---|
| Timeline | 缩放 | `W`/`S` 或 `Ctrl + 鼠标滚轮` |
| Timeline | 向左/右平移 | `A` / `D` |
| FlameGraph | 移动选择 | 方向键 |
| FlameGraph | 打开当前帧详情 | `Enter` 或双击节点 |
| FlameGraph | 关闭详情或菜单 | `Escape` |
| FlameGraph | 打开上下文菜单 | `Shift + F10` 或右键 |
| FlameGraph | 复制帧信息 | 菜单中的 Copy frame |

FlameGraph 只按当前 viewport materialize 可见节点；滚动、hover 和选择不重新执行 SQLite 投影。输入框聚焦时字符键由输入框处理。

## 5. 导出与外部验证

报告页提供：

- `.apsession.zip` 可复现会话包；
- `report.json`、Top Functions CSV、CallTree CSV；
- 当前窗口 PNG；
- 原始 Simpleperf protobuf；
- `simpleperf report --sort symbol` 结果；
- `report_html.py` HTML；
- Android Studio Profiler / Perfetto 打开说明。

会话包拒绝路径穿越、重复条目、符号链接和超过数量/解压大小上限的内容，并在失败后删除导入临时目录。

## 6. 权重说明

Sample/Event 权重不是精确 wall-clock 时间。`cpu-cycles` 等 event 的 `event_count` 用于聚合 inclusive/exclusive 权重；不同 event、frequency、period 或设备之间不可直接当作耗时比较。诊断结论始终附带证据，而不是宣称唯一根因。
