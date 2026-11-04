# Android Performance Studio V0.1 用户手册

## 1. 安装与启动

V0.1 提供自包含 JRE 的桌面发行物：macOS DMG、Windows MSI、Linux DEB/RPM，以及各平台 portable app image。开发环境需要 JDK 21，可执行：

```bash
# 在 desktop-viewer 下
./gradlew :simpleperf-viewer:app-desktop:run
```

安装包生成不等于所有平台均已通过安装、真机或签名验收，当前状态见 [发布门禁](../records/release-checklist.md)。

统一桌面安装包自带 ADB，无需配置 `PATH` 或安装 SDK。共享定位器继续支持显式配置、`ANDROID_HOME`、兼容的 `ANDROID_SDK_ROOT`、`PATH` 和常见 SDK 目录；未找到外部工具时使用安装包内的 ADB。单独构建本手册的 Simpleperf 应用不等于生成统一安装包，其开发环境仍需准备 ADB。

## 2. 在线采集

1. 通过 USB 连接 Android 设备并允许调试授权。
2. 在 **Device & Target** 刷新设备，确认能力为 Ready 或阅读 Limited/Blocked 原因。
3. 搜索并选择 App/进程；需要时选择线程。
4. 打开工作区的采集设置，选择 App CPU、UI Thread、Native Hotspot、Low Overhead 或 System Process 模板；无需再进入独立 Capture 页面。
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
- `*.json.gz`：支持 AOSP `gecko_profile_generator.py -i perf.data | gzip` 生成的 Firefox Profiler Gecko Profile，直接解压、解析调用栈并导入 SQLite。

演示包位于 `test-fixtures/src/main/resources/sessions/golden.apsession.zip`，可直接从 Home 打开。

### 3.1 Profile database migration

- Migration works on a copy; `profile.sqlite` is replaced only after the migrated candidate and retained evidence pass verification.
- The first successful migration retains `profile.v1.sqlite` and its SHA-256 in `migration.properties`.
- Backup and metadata publication uses fail-closed hard links; if publication, verification, or migration fails, the application opens the original database in legacy read-only mode.
- Availability is reported as exactly one of: Available, Empty, Not collected, Unavailable, Unauthorized, Failed, or Not applicable.
- Users must copy the complete session directory before attempting manual SQLite repair.

## 4. 阅读报告

报告顶部 **Timeline** 常驻，支持时间桶、拖拽框选、线程过滤和高度调整；下方固定七个分析标签。共享范围、栈筛选、反转和详情开关在切换标签时保留。

- **Overview**：会话时长、样本数、event 权重、线程、数据质量和 Diagnostics 证据/建议。
- **Top Functions**：inclusive/exclusive、库、sample、线程排序和搜索；Path/Flame 分别跳转调用路径或火焰图。
- **Call Tree**：正向/反向调用树共用标签，通过 Invert Call Stack 切换；搜索会自动展开匹配路径。
- **FlameGraph**：搜索会重算火焰图和联动 Call Tree；可切换 Forward/Inverted、实现类型过滤，并通过点击/键盘选择帧、打开源码/反汇编/符号 fallback 详情，或应用 Focus/Merge/Drop/Collapse 变换。
- **Stack Chart**：按真实采样时间与栈深显示栈块，也可切换每个堆栈等宽。可启用已采集的 User Timing 行；普通 Android marker 不会冒充该类型。悬停显示真实区间时长，右键应用共享变换，双击或 Enter 打开帧详情；图内拖动平移视口，不提交时间范围。
- **Marker Chart / Marker Table**：同一批 marker 的时间泳道与表格，区分点/区间事件并共享选择；没有采集 marker 时说明来源状态，不隐藏标签或伪造事件。

All Frames、Script、Native、Filter Stacks 是共享栈控件；Call Tree 和 Flame Graph 分别保留 Invert Call Stack，Stack Chart 始终正向。marker 标签使用独立 marker 筛选，不影响 Stack Chart 的 User Timing 行。Show details 控制当前标签的详情，关闭不清除选择，切换标签不会把其他面板的选择当作当前详情。

Timeline 与 FlameGraph 的键盘/鼠标入口：

| 区域 | 动作 | 快捷键 / 手势 |
|---|---|---|
| Timeline | 缩放 | `W`/`S` 或 `Ctrl + 鼠标滚轮` |
| Timeline | 向左/右平移 | `A` / `D` |
| FlameGraph | 移动选择 | 方向键 |
| FlameGraph | 纵向滚动 | `W`/`K` 向上、`S`/`J` 向下；不支持水平缩放/平移 |
| Stack Chart | 纵向 / 横向平移 | `W`/`K`、`S`/`J` / `A`/`H`、`D`/`L`，或图内拖动 |
| Stack Chart | 缩放视口 | `Q`/`Y` 放大、`E`/`U` 缩小，或 `Ctrl`/`Shift + 鼠标滚轮` |
| FlameGraph | 打开当前帧详情 | `Enter` 或双击节点 |
| FlameGraph | 关闭详情或菜单 | `Escape` |
| FlameGraph | 打开上下文菜单 | `Shift + F10` 或右键 |
| FlameGraph | 复制帧信息 | 菜单中的 Copy frame |
| 两种图 | 聚焦栈搜索 | `/`，搜索框聚焦时不拦截字符输入 |
| 两种图 | 返回先前变换步骤 | 点击 Complete 或历史面包屑；后续变换会被移除 |

FlameGraph 只按当前 viewport materialize 可见节点；纵向滚动、hover 和选择不重新执行 SQLite 投影。Stack Chart 的横向平移/缩放发布预览范围；点击 Zoom to selection 才提交，Cancel preview 恢复完整已提交范围。持续按住视口键会连续移动，松键、失焦或切换会话后停止。输入框聚焦时字符键由输入框处理。

Flame Graph 的兼容约束和待验收条件见 [兼容矩阵](../requirements/firefox-flame-graph-compatibility.md)。Stack Chart 的索引和视口绘制已部分实现，原生性能仍 [待验收](../design/2026-10-29-stack-chart-performance-optimization-plan.md)，不能把 Flame Graph 的基准结果作为该面板的原生交互验收。

## 5. 导出与外部验证

报告页提供：

- `.apsession.zip` 可复现会话包；
- Firefox Profiler Gecko Profile `perf_data.json.gz`（AOSP schema v24，可直接上传到 `profiler.firefox.com`）；
- `report.json`、Top Functions CSV、CallTree CSV；
- 当前窗口 PNG；
- 原始 Simpleperf protobuf；
- `simpleperf report --sort symbol` 结果；
- `report_html.py` HTML；
- Android Studio Profiler / Perfetto 打开说明。

会话包拒绝路径穿越、重复条目、符号链接和超过数量/解压大小上限的内容，并在失败后删除导入临时目录。

从 Gecko Profile 导入的会话在导出时直接复制原始 `json.gz`，保持字节不变；其他会话从 `profile.sqlite` 重建与
`gecko_profile_generator.py -i perf.data | gzip` 相同的线程、样本、Frame/Stack/String Table 和分类结构。

## 6. 权重说明

Sample/Event 权重不是精确 wall-clock 时间。`cpu-cycles` 等 event 的 `event_count` 用于聚合 inclusive/exclusive 权重；不同 event、frequency、period 或设备之间不可直接当作耗时比较。诊断结论始终附带证据，而不是宣称唯一根因。
