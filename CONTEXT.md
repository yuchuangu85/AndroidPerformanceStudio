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
