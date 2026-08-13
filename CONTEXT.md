# Android Performance Studio

Android Performance Studio 将性能采集结果、分析结论与产生问题的 Android 源码关联起来，帮助用户从性能现象定位到可检查和修改的代码位置。

## Language

**源码工作区（Source Workspace）**:
一个只读注册的源码集合，可以来自本机 Android 工程目录、GitHub 仓库或 AOSP 在线源码；注册不意味着把整个源码复制到应用数据中或发送给 AI。
_Avoid_: 导入代码、上传工程、源码副本

**源码提供方（Source Provider）**:
提供源码内容和版本身份的来源，当前包括 Local、GitHub 和 AOSP。
_Avoid_: 导入类型、代码平台

**源码定位（Source Resolution）**:
将性能证据解析为源码工作区中的一个确定位置或一组有置信度排序的候选位置。
_Avoid_: AI 跳转、模糊搜索

**定位候选（Resolution Candidate）**:
由可验证的源码身份信息支持、可能对应某项性能证据的源码位置；AI 可以解释和排序候选，但不能凭空创建候选。
_Avoid_: AI 猜测、推荐文件

**源码位置（Source Location）**:
由源码工作区、不可变版本、相对路径以及可用时的行列范围共同标识的可导航位置。
_Avoid_: 文件路径、链接

**源码查看器（Source Viewer）**:
应用内统一呈现源码位置及其版本和匹配证据的只读界面，是源码定位的默认导航目标。
_Avoid_: 代码编辑器、AI 结果页

**源码快照（Source Snapshot）**:
源码工作区在某个确定版本上的身份，用于让分析结果和源码定位在源码更新后仍可复现。
_Avoid_: 最新代码、当前分支

**构建证据包（Build Evidence Bundle）**:
描述被分析应用或系统构建身份、符号和混淆映射的一组只读证据，用于把运行时名称和地址还原为源码身份。
_Avoid_: 构建产物、符号目录、APK 导入

**在线源码发现（Online Source Discovery）**:
在尚未缓存完整源码快照时，通过源码提供方搜索可能相关的源码；发现结果只有固定并校验到确定版本后才能成为定位候选。
_Avoid_: 在线源码定位、远程跳转

**虚拟源码工作区（Virtual Source Workspace）**:
逻辑上提供完整源码命名空间、但只按需获取和缓存相关项目或文件的源码工作区，主要用于大规模 AOSP 源码。
_Avoid_: AOSP 完整下载、在线文件列表

**源码索引（Source Index）**:
从确定源码快照中提取的文件、模块、资源和符号身份集合，为源码定位提供可验证候选。
_Avoid_: 向量库、AI 知识库、全文缓存

**分析会话（Analysis Session）**:
一次性能证据、AI 分析结果与一个或多个源码快照之间的稳定关联。
_Avoid_: AI 请求、聊天记录

**分析范围（Analysis Scope）**:
一次分析明确覆盖的当前选择或报告摘要，决定被收集的性能证据和允许使用的源码上下文。
_Avoid_: Prompt 长度、选中代码、上下文窗口

**性能证据（Performance Evidence）**:
由某个 Profiler 从采集数据中提取、可独立引用并复核的性能事实，例如布局节点、热点调用栈或采样区间。
_Avoid_: Prompt 数据、AI 上下文、性能结论

**分析结论（Analysis Finding）**:
基于一组性能证据形成的可操作问题说明，可以引用定位候选，但本身不拥有或生成源码位置。
_Avoid_: AI 回复、源码定位结果

**分析置信度（Analysis Confidence）**:
分析结论成立的可信程度，不用于判断源码跳转是否准确。
_Avoid_: AI 置信度、跳转置信度

**定位置信度（Resolution Confidence）**:
定位候选与性能证据匹配程度的确定性等级，由源码定位过程产生，不受分析置信度影响。
_Avoid_: AI 评分、相似度

**布局快照（Layout Snapshot）**:
某一采集时刻的应用窗口、界面节点层级与显示空间状态，是布局分析和时间线比较所引用的不可变性能证据。
_Avoid_: 当前界面、实时布局

**Winscope 工作区（Winscope Workspace）**:
Android Performance Studio 中用于联合检查按时间对齐的窗口管理、合成层、事务与系统交互证据的功能工作区；它不是独立应用，也不代表设备提供了所有 Winscope 数据源。
_Avoid_: 独立 Winscope、Winscope 克隆、Layout Inspector

**上游 Winscope 查看器（Upstream Winscope Viewer）**:
随 Android Performance Studio 分发、通过应用自动管理的本机服务在系统浏览器中一键载入当前检查会话证据的 AOSP Winscope Web 查看器；它不要求用户启动独立静态服务器或 `winscope_proxy.py`。入口只有在解析确认至少存在一种 Winscope 核心证据时才可用，屏幕录像本身不满足此条件。它只查看 Android Performance Studio 已采集或导入的证据，不负责设备连接或采集。分发版本必须移除遥测并将运行所需资源本地化，不依赖第三方网络服务。它是现有 Winscope 工作区之外的可选查看路径，不替代工作区，也不嵌入桌面应用窗口。
_Avoid_: 内置 Winscope、原生 Winscope 工作区、Winscope 替代版

**Winscope 部分采集（Partial Winscope Capture）**:
至少保留一种可检查的 Winscope 核心证据、但未取得全部请求能力的采集结果；缺失能力及原因是结果的一部分，而不是整次采集失败。窗口管理与合成层核心证据均不可用时不产生部分采集。
_Avoid_: 采集成功、降级成功、不完整错误

**Winscope 核心采集（Core Winscope Capture）**:
覆盖窗口管理、合成层、合成事务和窗口转场证据的最小可检查能力集；它用于判断部分采集是否仍有分析价值，不代表首版交付范围。
_Avoid_: 首版 Winscope、完整 Winscope、全部数据源

**Winscope 平衡采集（Balanced Winscope Capture）**:
以帧级窗口状态和常用合成层细节为默认范围、优先控制设备开销的 Winscope 核心采集预设。
_Avoid_: 低保真采集、最小采集、快速采集

**Winscope 完整细节采集（Full-detail Winscope Capture）**:
以事务级窗口状态和高详细度合成层信息为范围、接受更高设备内存与制品体积的 Winscope 核心采集预设；它不等同于包含所有数据源的 Winscope 完整采集。
_Avoid_: 完整采集、全部数据源、无损采集

**Winscope 完整采集（Complete Winscope Capture）**:
首版在设备能力与用户选择允许时，请求并呈现窗口核心、EventLog、输入、输入法、ViewCapture、ProtoLog 以及可选屏幕录像证据的目标能力集；某台设备的实际结果仍可能是 Winscope 部分采集。
_Avoid_: 必然完整、全量成功、无条件支持

**Winscope 完整查看（Complete Winscope Inspection）**:
在统一时间游标下联合提供数据源时间轴、层级树、二维边界、属性、三维场景、全局查询和屏幕录像联动的检查能力；缺少某类证据时保留会话并明确显示该视图不可用的原因。
_Avoid_: 基础查看、核心查看、上游界面复刻

**Winscope 检查会话（Winscope Inspection Session）**:
由设备采集或本地导入产生、按共同时间轴组织的一组 Winscope 证据；证据来源不同不改变其检查语义，但能力完整性必须独立保留。
_Avoid_: 实时会话、导入文件、Trace 文件

**Winscope 状态快照（Winscope State Snapshot）**:
在用户指定时刻取得的 WindowManager 与 SurfaceFlinger 单一状态证据以及可选屏幕截图；它没有持续 trace 的状态序列，但仍可作为只有一个时间点的 Winscope 检查会话。
_Avoid_: 单帧 trace、布局快照、当前状态

**Winscope ViewCapture 证据（Winscope ViewCapture Evidence）**:
由已接入平台 ViewCapture 的系统窗口提供的逐帧 View 属性证据，例如 System UI 或 Launcher；它不代表任意应用的 View 或 Compose 层级，也不属于 Layout Inspector 会话。
_Avoid_: 应用布局、Compose 树、Layout Inspector 快照

**Winscope 证据包（Winscope Evidence Package）**:
保存原始 Perfetto trace、可选同步屏幕录像及能力完整性说明的可移植检查制品；从中生成的层级、属性和三维投影视图是可重建表示，不是新的权威证据。
_Avoid_: 解析数据库、Winscope 模型文件、可视化快照

**敏感 Winscope 证据（Sensitive Winscope Evidence）**:
包含完整输入事件、屏幕截图、屏幕录像或 ProtoLog 调用栈的原始 Winscope 证据；其导出或首次交给系统浏览器中的上游 Winscope 查看器前必须由用户显式确认，且不能在不改变原始证据的情况下宣称已脱敏。
_Avoid_: 普通 trace、自动脱敏、可安全分享

**Winscope 三维堆叠视图（Winscope 3D Stack View）**:
把某一时刻的窗口或合成层矩形按显示空间和 Z 顺序分离呈现的检查投影，用于观察遮挡与层叠关系；它不是通用三维场景或设备画面的立体重建。
_Avoid_: 3D 模型、场景引擎、立体屏幕

**Winscope 时间对齐（Winscope Time Alignment）**:
依据 trace 中明确记录的单调时间、VSync 标识或录像时间元数据，把不同证据定位到同一检查时刻的可验证关系；缺少映射时保持证据独立，不以最近时间猜测同步。
_Avoid_: 时间相近、自动同步、同一帧

**Winscope 跨源对应（Winscope Cross-source Correspondence）**:
由 trace 中明确记录的 Layer ID、Window Token、SurfaceControl 或转场参与者关系证明，不同 Winscope 数据源中的节点代表同一系统对象；名称、边界和时间相似不构成对应关系。
_Avoid_: 名称匹配、位置匹配、自动关联

**未记录的 Winscope 属性（Unrecorded Winscope Property）**:
当前 trace 没有提供该属性值的证据状态；它既不等于类型默认值，也不等于空值，除非 Trace Processor 明确恢复了平台默认值。
_Avoid_: 默认值、空属性、false/0

**帧间节点对应（Cross-frame Node Correspondence）**:
两个布局快照中的节点代表同一逻辑界面元素的可验证关系；位置路径本身不是跨帧身份，存在歧义时不建立对应。
_Avoid_: 稳定节点 ID、按路径猜测

**时间线差异（Timeline Diff）**:
在建立帧间节点对应后，对相邻布局快照中的节点新增、移除与属性变化形成的可复核结果。
_Avoid_: 快照差值、路径对比

**Compose 检查能力对齐（Compose Inspection Capability Parity）**:
以 Android Studio 2026.1.2 的公开稳定能力为基线，在 Android Performance Studio 中提供等价的 Compose 检查证据和工作流，而不复制其界面或私有传输实现。
_Avoid_: 完整 Compose 支持、复刻 Android Studio、协议兼容

**Compose 检查能力清单（Compose Inspection Capability Set）**:
一次检查会话经 Agent 协商后可验证使用的能力集合；未满足完整模式基线的能力必须连同降级原因明确呈现。
_Avoid_: Compose 版本推断、已连接

**Compose 检查详情（Compose Inspection Detail）**:
布局快照中可选的完整 Compose 检查证据，包括 Composable 层级身份、参数、Modifier、Semantics、调用点和重组统计；缺失详情不改变基础布局快照的可读性。
_Avoid_: Compose 快照、AOSP protobuf

**Compose 检查帧（Compose Inspection Frame）**:
实时检查会话中一次原子采集形成的布局快照及其 Compose 检查详情边界；树、显示空间、能力状态和统计区间必须属于同一帧，按需返回的详情也必须声明所属帧。
_Avoid_: 实时树、当前详情、跨帧拼接

**Compose 详情覆盖率（Compose Detail Coverage）**:
一个 Compose 检查帧中实际采集到的节点、字段、递归深度和分页范围，以及未采集、截断或失败部分的明确记录；它与归档是否脱敏无关。
_Avoid_: 完整归档、空属性、已加载详情

**系统 Composable（System Composable）**:
由 Compose Runtime、UI 框架或组件库生成而非直接代表应用调用点的 Composable；它属于原始检查证据，但可以在展示投影中隐藏。
_Avoid_: 无用节点、过滤节点

**安全检查归档（Safe Inspection Archive）**:
默认可分享的检查归档，其中运行时文本和参数值按声明的策略脱敏，但结构、类型、来源、截断和遗漏状态仍可复核。
_Avoid_: 匿名归档、无敏感数据归档

**完整保真检查归档（Full-fidelity Inspection Archive）**:
经用户显式确认后保存已采集运行时原值的检查归档；它属于敏感数据载体，但仍不包含凭据、认证 Token、Inspector 程序或源码正文。
_Avoid_: 原始归档、未脱敏归档

**可比运行组（Comparable Benchmark Cohort）**:
设备环境、测试 Case、Metric 语义及单位满足同一比较约束的一组 Benchmark Run。
_Avoid_: 兼容 cohort、可比环境、同设备结果

**回归门槛（Regression Gate）**:
结合可比性、业务变化阈值和统计不确定性，判断性能变化是否构成可操作回归或改善的准则。
_Avoid_: p-value 门槛、显著性、百分比阈值

**初始显示时间（Time To Initial Display, TTID）**:
从启动请求到目标界面首帧由系统完成显示的启动指标；Agent 观察到的首帧事件可以辅助诊断，但不能替代平台 TTID。
_Avoid_: Fully Drawn、可交互时间、统一启动耗时

**完整显示时间（Time To Full Display, TTFD）**:
从启动请求到应用通过 `reportFullyDrawn` 明确声明主要内容可用的启动指标；缺少声明时该指标缺失，不能回退为 TTID。
_Avoid_: 首帧时间、Displayed Time、估算可交互时间

**请求启动模式（Requested Startup Mode）**:
实验准备阶段要求形成的 Cold、Warm 或 Hot 启动状态；它是测试意图，不证明实际运行满足该状态。
_Avoid_: 启动类型、实际启动模式

**观测启动模式（Observed Startup Mode）**:
依据平台 LaunchState、进程身份和生命周期证据判定的实际启动状态；无法可靠判定时必须保持 Unknown。
_Avoid_: 请求启动模式、配置启动类型、推定启动模式

**启动编译状态（Startup Compilation State）**:
一次启动实验开始时目标包实际使用的 ART 编译过滤器、Profile 可用性及其可验证来源；请求的编译模式只是建立该状态的操作意图。
_Avoid_: 编译模式、原始编译缓存、可恢复编译快照

**启动环境证据（Startup Environment Evidence）**:
与每次启动测量绑定的设备身份、电量、模拟器状态和平台热状态；它用于判断运行是否可比，而不是假定工具能够控制硬件环境。
_Avoid_: 固定设备环境、温度校准、已消除噪声

**启动模式预置（Startup Mode Priming）**:
在正式测量前建立 Warm 或 Hot 所需进程和 Activity 状态的非测量运行；它只服务于启动模式，不用于形成编译 Profile。
_Avoid_: Warm-up、JIT 预热、预跑次数

**编译预热（Compilation Warmup）**:
为形成明确 JIT/Profile 编译状态而执行的非测量运行；它属于启动编译状态准备，不能替代启动模式预置。
_Avoid_: 启动预热、Warm-up runs、缓存填充

**启动阶段证据（Startup Phase Evidence）**:
由同一时钟域内的平台 Trace 或 Agent 里程碑界定的启动子区间；无法可靠关联的边界保持缺失，不能拼成完整阶段图。
_Avoid_: 固定启动阶段、推测阶段、完整启动分解

**帧截止时间未命中（Frame Deadline Miss）**:
有可靠帧预算时，帧完成耗时超过该预算的可复核事实；它不等同于用户可见 Jank，也不表示帧被完全丢弃。
_Avoid_: Jank、掉帧、卡死

**平台 Jank 信号（Platform Jank Signal）**:
Android 平台或 JankStats 按其版本和启发式规则报告的 Jank 判断；其来源和规则身份属于信号语义的一部分。
_Avoid_: 截止时间未命中、统一 Jank 率、平台根因

**网络采集覆盖（Network Capture Coverage）**:
一次网络采集经验证能够观测的进程、客户端、协议栈和时间范围；它不表示已观测事件本身没有缺失。
_Avoid_: 完整采集、支持列表、证据完整度

**网络证据完整度（Network Evidence Completeness）**:
已进入采集范围的网络事件是否存在序列缺口、丢弃、未闭合或解析遗漏；它不证明应用的全部网络调用都在采集范围内。
_Avoid_: 采集覆盖、分析置信度、完整网络流量

**最小化网络证据（Minimized Network Evidence）**:
按已声明策略不可逆移除或替换凭据、用户标识和非必要网络内容后的性能证据；最小化不等同于匿名，也不保证产物不再敏感。
_Avoid_: 安全 HAR、匿名流量、原始网络证据

**网络调用结果（Network Call Outcome）**:
一次网络调用在观测边界内完成、失败、取消或未完成的生命周期结果；它与 HTTP 响应状态和业务成功无关。
_Avoid_: HTTP 成功率、状态码、业务结果

**堆转储证据（Heap Dump Evidence）**:
一次堆转储中记录的对象、类、GC Root、字段和采集格式事实；转储内对象身份只在该证据边界内有效，不能据此认定另一转储中的对象是同一实例。
_Avoid_: 稳定对象 ID、完整运行时内存、跨转储对象身份

**内存保留证据（Memory Retention Evidence）**:
由强引用可达性、引用链、支配关系或生命周期状态支持的可复核事实；它可以形成泄漏嫌疑，但不等同于已证实的内存泄漏。
_Avoid_: 泄漏结论、AI 置信度、白名单豁免

**原生堆轨迹（Native Heap Trace）**:
heapprofd 采样形成的 Perfetto 轨迹，是原生分配分析的权威采集证据；从中派生的摘要必须保留采样和解析覆盖限制。
_Avoid_: 完整 Native Heap、精确 malloc 总量、分配表真值

**GPU 制品内容（GPU Artifact Content）**:
由完整内容摘要标识的一份 GPU 性能证据字节内容；其身份独立于文件位置和引用它的分析会话。
_Avoid_: GPU 文件、制品路径、物理副本

**GPU 制品位置（GPU Artifact Location）**:
能够提供某份 GPU 制品内容的外部文件位置；同一内容可以有多个位置，位置是否可用是动态状态。
_Avoid_: 制品身份、唯一文件、存储副本

**GPU 证据引用（GPU Evidence Reference）**:
GPU 制品内容与分析会话之间的逻辑关联；同一内容可以被多个分析会话独立引用。
_Avoid_: 重复制品、实验记录、文件关联

**图形提交 API（Graphics Submission API）**:
工作负载向图形系统提交 GPU 操作所使用的接口，例如 Vulkan、OpenGL ES 或 WebGPU；它不表示底层驱动或翻译实现。
_Avoid_: 图形后端、ANGLE API、GPU 类型

**图形实现层（Graphics Implementation Layer）**:
实现图形提交 API 的驱动或翻译栈，例如供应商驱动或使用 Vulkan 后端的 ANGLE。
_Avoid_: 图形 API、Vulkan on ANGLE、GPU 型号
