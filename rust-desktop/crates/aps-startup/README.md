# aps-startup

Rust 启动分析支持有界、明确的平台实验以及实际存在的输入格式：

- `am start -W` 文本（`parse_am_start` / `open_am_start`），由调用方提供明确的
  目标包和请求模式。
- 限定于**单次启动**的日志（`with_event_log`），其中包含
  `wm_activity_launch_time`、`wm_fully_drawn_time`、`Displayed` 或 `Fully drawn`。
  目标组件包匹配是精确匹配，而不是子字符串匹配。多个匹配观测会被拒绝，而不
  是默默采用最新的一项。
- 现有的 `StartupCsvExporter` 报告（`parse_csv` / `open_csv`），按命名列解析，
  支持成对引号以及带引号的逗号/换行。其他编译/环境/导出字段保留在
  `imported_fields` 中。
- 桌面 Startup JSON v1 报告（`report::parse_json` / `report::open_json`）。GUI 与 headless 共享校验；[CLI](../../../docs/ai/cli/startup.md) / [MCP](../../../docs/ai/mcp/startup.md) 只投影数值和固定枚举。

会话保留运行级别的缺失指标、请求模式与观测模式，以及来源。`TotalTime`、
`ThisTime` 和 `WaitTime` 仍是 Activity Manager 字段：它们都不能替代 TTID。
TTID 需要 Displayed 证据；TTFD 需要 Fully drawn 证据，绝不回退到 TTID 或
Agent-first-frame 事件。即使请求模式为 Cold/Warm/Hot，缺失的 `LaunchState` 仍
保持为观测未知。

摘要报告独立的指标可用性、均值和最近秩百分位数；页面可按请求/观测模式或 TTID
可用性过滤，最多返回 512 次运行。输入预算为 64 MiB 和 100,000 次运行。

所有受支持的值都是相对于启动的毫秒时长，而不是绝对时间戳；它们无法重建跨时钟
的阶段瀑布图。CSV 导出缺少完整的目标/会话身份，不能证明队列可比。
编译字段会被保留，但不会独立验证。Agent 传输、Perfetto 阶段关联、Baseline
Profile 准备和统计回归资格判定仍是独立工作。

## 明确的平台实验

`capture::capture_startup` 是供后台执行器运行的阻塞操作，只能在针对目标的 Review /
Confirm 流程之后调用。它会冻结已批准的设备、包和配置，验证最初选定的应用进程，
并在整个实验期间重新验证启动 ID、当前 Android 用户、包 UID、版本、安装时间戳
和代码路径。

- Cold 只会为其 Android 用户强制停止已批准的包，然后启动其解析出的 MAIN /
  LAUNCHER Activity。
- Warm 保留进程，发送 HOME，并启动一个新的已清除 Activity 任务。
- Hot 发送 HOME 并恢复 Activity。如果包没有进程，Warm/Hot 准备阶段会先启动它；
  该启动不计入测量运行。
- 运行次数为 1–20 次，启动/延迟/事件窗口有界，总截止期限为 15 分钟。取消会
  停止后续命令和所拥有的主机进程，但不会撤销已经完成的应用生命周期变更。
- 不会执行数据/日志重置、root、权限修改或编译/Profile 准备。当前编译状态不会
  被验证。
- 准备命令和测量命令必须返回 `Status: ok`、`Complete`，以及已批准包中的明确
  Activity。启动失败/不完整时会中止发布，而不是用部分证据替换已有报告。

观测模式只能来自已识别的平台 `LaunchState`，绝不会来自请求或 PID 启发式判断。
TTID/TTFD 需要精确 Android 用户和已启动 Activity 的新事件，并通过 Activity
token 关联。缺失、不匹配、格式错误或有歧义的事件保持不可用；`TotalTime` 不能
替代它们。这意味着 Hot 恢复通常没有启动指标，这是有意设计的。不会重建关联的
阶段瀑布图。

工件包含每次运行的 Activity Manager 输出、仅限目标包的事件行，以及私有且独占
拥有的临时目录中的有界实验清单。使用其中的路径时必须保持
`StartupCaptureArtifact` 存活；Drop 只会删除该目录。失败/取消的运行不会替换先前
发布的报告。CSV 元数据不能声明类型化的实时来源。

桌面当前页面投影保留七个业务列，并增加第八个类型化的 `Evidence source` 列。
因此，CSV/JSON 导出和有界 AI 投影可以区分实时平台、am-start 文本和导入的 CSV，
而不会信任导入来源声明。将页面过滤为空不会改变已发布会话的来源。这些投影
不是完整的工件导出，也不会增加阶段、编译或队列资格判定。
桌面导出会选择整个自有 `startup-captures` 树之外的持久目标；生成/取消检查会拒绝
过期选择器，并在后台写入期间保持证据所有者存活。

## 可选的启动 + CPU 热点

`StartupCaptureConfig::cpu_hotspots` 默认关闭。开启后，Android 11+ 上每轮使用独立的
Perfetto Trace，采样范围限定为目标包进程名，不使用冷启动前的 PID。数据源就绪后才拉起
Activity。私有工件的 `cpu_traces` 仅按实际运行 ID 提供受管路径；导入报告中的路径不是
采集凭据。最长 12 秒的 Trace 自然结束，取消不再启动下一轮，也不终止其他会话。

桌面启动页从唯一目标首帧窗口计算主线程调度、Top 12 叶符号的观测样本数和未解析样本。
Linux 调度时间与调用栈样本是两种不同证据，样本数不能换算为方法耗时。功能、使用入口与
证据限制见[启动分析说明](../../../docs/features/startup-profiler.md#rust-桌面端启动--cpu-热点)。
