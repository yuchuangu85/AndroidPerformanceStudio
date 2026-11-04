# 商业化 UI 共用基础与交付规范

本目录保留共享约束与 13 份页面规格，不是当前功能完成清单。页面目标、建议模型和设计图中的能力均需结合当前实现核验；未验收项见 [实施索引](README.md#待验收)。

## 设计权威与使用范围

- 仓库根 [DESIGN.md](../../../DESIGN.md) 是当前交互、主题、密度和窗口行为的权威；页面规格或图片与其冲突时，以当前设计为准，不恢复旧版 Shell、装饰横幅、布局或字号。
- [CONTEXT.md](../../../CONTEXT.md) 定义产品术语和证据语义；源码、测试及对应 feature 的 ADR/design 决定实际能力。图片只说明视觉层级和布局方向，不能证明指标存在或功能已完成。
- 继续使用 Kotlin/JVM、Compose Desktop 和现有 `ViewerTheme`、`ViewerTypography`、`StudioTokens`、共享组件与双语资源；不建立平行主题、局部替代色板或重复控件体系。
- 当前 Shell 保留一级导航和工作区；Source Workspaces 是一级入口，Settings 是路由页面，Battery 与 Network 分别独立导航。窗口目标选择器、原生菜单、平台回退和状态反馈遵循 `DESIGN.md`，不恢复旧顶栏或常驻全局状态栏及其占位。
- 活跃页面不渲染装饰性工作区横幅。卡片、结构面板、表格、画布和分隔条按当前设计使用；不因套用统一外观而破坏高密度行、独立滚动或选择行为。

## 模块职责

- 每个 Profiler 保留自己的强类型 Model、Analyzer、Controller、Storage 和采集/导入/导出链路。视觉迁移优先限制在 Presentation 与 Shell，不为截图重写分析器。
- Shell 负责路由、retained composition、窗口、应用设置和现有共享上下文协调；feature 负责目标语义、采集、解析、分析、存储、内部筛选和选择。共享设备/进程入口通过既有协调契约连接 feature，不把业务状态转移给 Shell。
- Home、Recent、Reports 通过只读 projection/summary 和现有 store/index 聚合，不复制完整业务记录，也不为首页初始化所有 Profiler Controller；单个来源读取失败不阻塞总览。
- 遵循 [ADR-0006](../adr/0006-capture-artifact-contract-before-profiler-session.md)：`CaptureArtifact` 是单个不可变原始证据的中立契约；共享契约不承载 Frame、Heap、HTTP、Battery 或 CPU 的业务 record。跨 artifact 的完整 Profiler Session 需要独立需求与 ADR。
- 不创建 `UniversalPerformanceRecord`、`UniversalProfilerModel`、`UniversalTimelineEvent` 或统一大 Controller；Reports 只索引结果身份、来源和文件，不强制转换为通用报告内容 schema。
- Battery 与 Network 不合并 Controller 或业务事件模型。Method Recording 保留既有高级工具入口，不因视觉统一重建业务模块。
- 保持现有 Perfetto UI、Trace Processor 和 CPU FlameGraph 的分析/布局能力；APS 提供周边工作区、诊断、证据和跨工具导航，不自研替代 Timeline 或重写 Flame 布局算法。

## 证据与能力边界

- 生产 UI 不注入 fake/sample 指标，不把设计图数字、fixture 或 AI 解释当作实测结果。缺失数据保留空态或 `UNAVAILABLE`，不能用零值或推测补齐。
- 明确区分 `COMPLETE / PARTIAL / UNKNOWN` 的完整度，以及 `EXACT / DERIVED / INFERRED / ESTIMATED / UNAVAILABLE` 的来源/可信性；沿用 feature 自身契约，不压成一个通用置信度。标签、限制与失败原因不能只靠颜色表达。
- CPU 没有相应证据时不得显示 CPU Usage、Energy 或温度；可展示真实 Samples、Threads、Duration、Effective Sample Rate 及有来源的丢失样本统计。
- Memory HPROF 是静态快照，不伪装实时 Heap、GC 或分配时间线；Diff 仅做可信类级比较，不将不同 dump 的 object ID 当作同一实例。Native size、retained size、泄漏嫌疑和确认结论按各自证据能力显示。
- Frame 的 deadline miss、平台 Jank 和 dropped frame 不混用；预算或归因未知时保持未知，颜色和布局遵循当前 `DESIGN.md`，跨工具跳转不改变证据含义。
- Startup 区分 requested/observed type、启动模式预置与编译预热。阶段必须由同一时钟域的真实里程碑支持，缺口保留缺口；平台 TotalTime 不拼成阶段加总。TTID/TTFD、置信度、样本缺失、低尾部分辨率及 trace correlation 误差必须可见。
- Startup 的已验证 compiler state 不等于已声明 Profile 来源；填写 artifact/hash 或选择 Speed Profile 不证明已在设备安装、校验 Baseline Profile。不能凭声明来源显示已就绪。
- Battery/Network 只有在 time domain、capture window、clock mapping error bound 与 provenance 可证明可比时才能关联；否则保持独立证据。Network 估算阶段、采集覆盖、证据完整度和脱敏限制不得隐去。
- GPU 当前定位为 AGI/Perfetto/制品集成中心。没有稳定、可合法使用的 parser/data source 前，GPU Busy、Shader Hotspots、Render Pass、Draw Call、Overdraw、由 AGI frame profile 派生的附件预览均不得冒充本地分析能力；改用元数据、关联证据和外部工具能力说明。
- GPU 制品的内容身份与位置分离；按既有 size/hash 契约验证，打开前重新校验。文件缺失、替换、不可读或迁移后保留 Verify/Reveal/Relocate 恢复路径，不信任缓存可用状态或调用方临时路径。
- Benchmark 采用可导出的版本化 Gate Policy 与 `PASS / FAIL / INCONCLUSIVE`，不创建任意 Performance Score。环境不兼容优先于回归判定，低样本不能冒充高置信回归；趋势保留 build/commit 和环境变化说明。
- Rule Finding、Measured/Derived Evidence、AI Interpretation 分开呈现；AI 保留模型、证据范围、置信度和限制，不能创造指标或确定的 Source Location。弱定位候选不能伪装确定跳转。
- Reports 删除索引默认不删除用户文件；真实删除需明确确认。凭据不写日志，敏感证据提示与脱敏边界可见，配置重置不能误删用户证据。

## 状态与交互

- 每页按真实能力覆盖 No device、No target、No artifact、Unsupported、Loading、Partial、Error/Retry；不以空白区域或可点击但无行为的控件暗示能力可用。
- Capture、Import、Parse、Analyze、Export 显示真实阶段、进度和取消能力；取消必须停止对应操作，失败不能作为成功。保留 feature 自有反馈，不强行迁入统一任务框架。
- 页面切换、主题/语言切换、缩放和暂时窗口约束不应清空 retained state、选择、展开状态或分隔比例。隐藏目的地不接收当前页面输入；既有快捷键和原生菜单保持有效。
- 保留表头、选中行 semantics、键盘焦点、图标说明、tooltip、禁用原因、错误恢复、复制证据、定位文件及 Recent 清理能力；缺失值和警告不只用颜色区分。

## 实施所需材料

仅保留资料原则，不重复 `AGENTS.md` 的执行流程：读取完整仓库、根设计/术语、对应页面规格、feature ADR/design、相关源码与测试，并确认明确的 scope、non-goals 和可验证验收条件。参考图与当前运行截图用于对比，不替代行为证据；交互录屏仅作辅助。

构建和测试入口以当前 build 配置与项目说明为准，不猜模块名或使用历史日志证明当前版本。已有未提交改动必须保留，缺图、缺 fixture 或能力缺失应明示，不补造素材或数据。

## Fixture 与视觉素材

以下是需提供或核验的真实/脱敏测试材料，不代表仓库已经具备。合成数据只用于明确标注的测试，不进入生产状态，不作为真机测量。

### Layout Inspector

- 100-500 节点普通快照、5k-10k 节点大快照、截图、Compose/View 混合 capture、findings、不可见/临时隐藏节点和归档文件。
- 覆盖层级虚拟化、Canvas overlay、属性、Findings、搜索、隐藏层级，以及导入导出闭环。

### CPU

- 正常与大采样 simpleperf session、缺符号、多线程、marker/session package 和既有 golden fixture。

### Trace

- Android UI jank、Binder/Input 延迟、CPU scheduling trace，以及支持/不支持部分 Perfetto module/schema 的兼容 fixture。

### Memory

- 小/大 HPROF、泄漏与重复 Bitmap 案例、两个可比较 dump、`mapping.txt`、截断/非法 HPROF。

### Frame

- gfxinfo framestats、FrameMetrics/Agent capture、Perfetto FrameTimeline、多刷新率、severe/frozen frame、缺 expected-duration 的场景。

### Startup

- cold/warm/hot、有/无 Agent、有/无 fully-drawn、speed-profile、Perfetto correlation 和环境不兼容案例。

### Battery

- batterystats 快照、wakelock、alarms/jobs、network usage、modeled energy、温度/充电漂移警告、counter reset。

### Network

- OkHttp EventListener capture、HAR import、连接复用、TLS、失败/取消、不完整事件序列、敏感 header 脱敏案例。

### GPU

- AGI 已安装/缺失环境、system/frame profile 元数据、缺失/迁移文件、Perfetto 与 screenshot artifact；不要求解析 opaque AGI 制品内容。

### Benchmark

- 兼容 baseline/current、回归、改善、低样本、device/API 不兼容、未知指标方向和历史运行。

### 尚缺材料

- Home、Reports Center、Settings、Source Workspaces 尚未定义专属 fixture，不能把覆盖缺口当成已验收。
- Source Workspaces 没有专属设计图；不借用无关图片填补。其余页面的参考图见对应规格。
- Reports 与 Settings 共用 `reports-settings-v2-overview.png`、`reports-settings-v2-spec-board.png`；`reference-original.png` 仅为旧版组合参考，保留但不作为当前设计权威。

## 交付与验收

### 行为与视觉

- 先验证 Behavior Parity，再更新 Visual Golden：覆盖打开/导入/采集、筛选/搜索、选择、跨页导航、导出、错误/重试、取消及状态保留。
- Shell 覆盖默认路由、导航折叠、retained destination、非活跃输入隔离、共享上下文和菜单；组件覆盖 normal/hover/selected/focus/disabled/loading/error，避免只测源码字符串就认定交互通过。
- 自有 UI 覆盖深浅色、accent、英中语言、display scale、键盘与可访问性；验证 1440x900、实际 1280x720 窗口及更窄时内容可达、独立滚动、文本不重叠。当前设计允许的自适应或横向滚动不能被旧固定布局取代。
- 用当前设计审查截图，说明与参考图差异；离屏渲染或像素差异结果不等于原生窗口和整页视觉验收。

### 大数据与性能

- 大列表使用虚拟化与稳定 key；Timeline/Flame/Canvas 保持高效绘制，不为每个 frame/sample 创建复杂组件树，不让鼠标移动重组整个 workspace。
- 解析/查询/分析在后台执行且 UI 可响应取消；大状态使用既有 projection/derived state，避免无界加载。
- 在实际窗口验证 10k 节点滚动/联动和至少 10,001 帧的选择/范围/缩放，记录机器、规模、交互延迟与重组证据；合成归档 round-trip 和有界布局测试不证明性能通过。
- 大 Flame Graph 不退化既有性能基线；大 HPROF 的解析/分析不能阻塞 UI。具体阈值结合测试机校准，不把“无明显卡顿”当作已有测量。

### 证据等级与完成条件

- 分开报告静态检查、模块测试、桌面集成、截图、打包、原生 GUI、真机/外部工具、签名和 Release 证据。局部测试、进程启动、app image 构建或 JAR 一致性不能替代后续验收。
- 对本次改动运行相关行为测试、lint/静态分析及必要集成验证，记录实际结果与未覆盖项；历史通过、历史编译错误和临时日志都不代表当前全仓状态。
- 整体完成需同时满足导航/Recent/Reports/Settings 闭环、feature 边界、指标可追溯、缺失/推断/限制可见、版本化 Gate、主题与可访问性、大 fixture 和 macOS/Windows/Linux 打包及原生交互验收；不能将未来规格标记为当前完成。
- 商业发布另行评审第三方许可及 AGI/Perfetto 集成边界、隐私与可选遥测、更新渠道、签名/公证、安装升级、数据迁移、备份/导出稳定性、诊断支持、版本兼容矩阵、Release notes 和安全。UI 验收不等于已具备商业发布资格。
