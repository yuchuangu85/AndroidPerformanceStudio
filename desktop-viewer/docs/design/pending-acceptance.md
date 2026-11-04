# 保留的待验收项

本清单承接旧 Desktop Viewer 总体设计、Layout Inspector 单功能方案、Profiler 架构研究和设计评审中仍有效的独有要求。清理旧方案只消除重复或冲突描述，不证明实现、性能或发布验收已经完成。以下均为待核验：本次只核对文档、代码和已有测试定义，没有运行测试、基准、真机、原生 GUI 或发布流程；完成时必须补充对应环境、版本和证据。

当前交互以[根 DESIGN](../../../DESIGN.md)、[功能文档](../../../docs/features/README.md)和[商业化 UI 规格](../aps-commercial-ui-implementation/README.md)为准，协议与安全约束以 [Protocol](../architecture/PROTOCOL.md)、[Development](../architecture/DEVELOPMENT.md)和 ADR 为准。本清单不恢复已移除的 Winscope，也不恢复旧品牌、旧菜单顺序、固定面板尺寸、两卡片首页或已被替代的模块布局。

## Layout Inspector 与 Android Agent

- [ ] 传统 View 路径的原始性能目标仍需实测：500 节点无截图在线快照 P95 不超过 500 ms；10,000 节点离线报告首次加载不超过 3 秒；Agent 空闲 CPU 低于 1%，附加内存不超过 20 MB。不能用桌面堆上限或 Compose 的独立预算代替这些指标。
- [ ] Android 兼容回归覆盖 API 21、23、26、28、30、33、35 和发布时最新稳定 API；同时保留真实设备与相应 AVD 的端到端证据。覆盖 API 21–25 Canvas、API 26+ PixelCopy、SurfaceView/TextureView/硬件 Bitmap/系统窗口截图缺失的明确 limitation，不能只以 Robolectric 或坐标单测代替。
- [ ] View、纯 Compose 和混合应用分别覆盖 Activity 切换、重建、旋转、多窗口、进程重启、USB/Wi-Fi ADB、设备离线与多设备歧义；验证设备/进程/会话隔离、鉴权、重连、forward 及临时资源清理。不能将旧的特定样例包或 OnePlus 设备当作唯一验收对象。
- [ ] 传统语义适配器的稳定版及前两个稳定系列、一个更老版本和一个预览版降级回归，仍与完整 Compose 检查认证分开记录；未知版本或适配失败不影响传统 View 采集，不冒充完整 Compose 能力。
- [ ] 协议未知次版本字段、主版本拒绝与原始证据保留需验证；原总体方案提出的当前及前一主版本报告读取目标，在出现对应版本时需提供兼容 fixture 和迁移证据，不能从当前版本 round-trip 推断。
- [ ] 大树虚拟列表、树/画布/属性/Findings 同步选择、缩放平移、裁剪与 bounds 映射，以及 Timeline/Diff 过滤需保留端到端覆盖。大快照连续刷新、Compose 详情展开及历史保留需单独进行内存与响应性验证，显式保留截断/部分覆盖状态。

## 归档、隐私与长期存储

- [ ] 在真实文件选择器完成导出、导入、取消、失败及再次导出的桌面冒烟；验证原始 ZIP/TXT 附件不丢失、单窗口旧归档和多窗口新归档可读、失败不替换当前状态、导入后停止自动扫描且明确离线来源。截图可选性、Compose sidecar 和版本规则以当前 codec 及 [ADR 0023](../../../docs/adr/0023-preserve-layout-snapshot-v1-with-compose-detail-sidecars.md)为准，不恢复旧的“必须有截图”限制。
- [ ] 覆盖 manifest、长度、SHA-256、PNG 签名、协议版本、重复/绝对/穿越路径、未知条目、损坏或超大归档、ZIP bomb、临时文件清理及原子替换；不能把解析失败当作部分成功，也不执行归档内脚本或命令。
- [ ] 传统 View 的 text/contentDescription 需要默认限长、脱敏开关及显式启用完整文本的导出边界；日志不得包含 token、完整用户文本或截图二进制。Compose 的递归脱敏、完整值导出确认和不自动发送 AI 的边界继续由 [ADR 0026](../../../docs/adr/0026-default-compose-detail-archives-to-safe-export.md)及下方保留设计约束。
- [ ] 长期报告索引仍须与内存时间线和单次 `.apinspect` 区分验收。原方案中的 SQLite 索引、SHA-256 内容寻址、10 GB 默认配额、仅对未保护报告做 LRU、保留标记、数据库迁移可回滚及失败时只读打开旧数据，尚不能因删除旧总体设计而视为已实现；若调整该后续方案，须先明确替代要求和数据保留策略。

## 原生桌面与发布

- [ ] 在 macOS 13+ Intel/Apple Silicon、Windows 10 22H2/11 x64、Ubuntu 22.04/24.04 x64 验证原生 GUI；当前发行矩阵增加的架构同样按对应构建配置验收。覆盖英中语言、明暗/系统主题、窄窗口、面板拖拽及重启恢复语义，不能以历史像素尺寸替代当前设计。
- [ ] 原生菜单、快捷键和焦点操作必须每次只派发一次；macOS Preferences、Windows 菜单、不支持原生菜单的回退、文件选择器及统一设置页面路由需实际验证。设置即时生效/保存、写入失败可见、模块命名空间独立和独立运行设置回退不得因删除旧设置窗口草案而丢失。
- [ ] 统一 Shell 的首页/功能往返、保留工作区与正在进行的任务、异常后可返回、退出时释放资源，以及跨功能路由载荷清理需回归；功能模块不反向依赖彼此，`desktop-app` 继续拥有主应用入口和原生打包。
- [ ] 原生安装包在目标 OS 构建并验证安装、升级、降级、卸载及内置运行时。DMG/PKG、MSI/EXE、DEB/RPM 的实际产物、当前架构/命名/版本、缺失或多余产物拒绝和所有打包任务成功后才发布，需以实际 CI/安装证据证明；不恢复历史“恰好六个 x64 产物”限制。
- [ ] 签名、Apple 公证和内部制品校验单独记录；本机打包或 unsigned 工作流通过不能证明已签名发布。内部 Maven 元数据和依赖解析，以及消费应用 Release 依赖图/APK 中无 Agent 代码、组件、资源或新增权限，仍须验收。

## Profiler 平台与后续评审

平台所有权和依赖方向已由 [ADR 0006](../adr/0006-capture-artifact-contract-before-profiler-session.md)、[ADR 0007](../adr/0007-stateless-device-target-and-shared-adb.md)、[ADR 0008](../adr/0008-perfetto-as-shared-trace-engine.md)承接。以下保留验收范围，不把旧研究中的实现缺口当成当前故障。

- [ ] 检查各功能不再为 ADB/Host Process/通用结果类型反向依赖 Simpleperf；无重复活跃 FQCN 或未纳入构建的旧实现、业务层直接启动 adb、重复设备解析/超时/取消/错误分类。Capture/Import 的 provenance/capability/completeness 一致，Live Observation 只在形成不可变证据时登记 Artifact。
- [ ] 同一原始 trace 由 Frame、Memory、Startup adapter 共享时，验证唯一固定版本/哈希的 Trace Processor、Artifact 级 context、动态端口、并发、取消与关闭。每个 SQL adapter 保留固定 trace fixture 和 schema 兼容检查，不向 UI 泄漏未经映射的原始文本。
- [ ] 对小、中、大 trace 做冷/热查询耗时和内存基准，确认诊断查询复用已加载的 context、不逐次重读 trace；浏览器 UI 是否使用本地 Trace Processor 单独证明，不能从诊断查询成功推断浏览器获得加速。
- [ ] Native Heap 的 symbolization、allocation/deallocation、sequence loss 与权威 Trace Processor 结果做差分；只在工具不可用/不兼容时显式降级并标记 PARTIAL，损坏 trace/SQL 失败不静默回退。FrameTimeline 与 Live FrameMetrics/gfxinfo、Startup 三类时间证据、HPROF 与 java_hprof 的未知字段，按 ADR 0008 分别验收，不互相冒充。
- [ ] Perfetto 工作区后续重构前锁定 capture 完成、trace 切换、诊断 context 清理、设备刷新和页面销毁行为；旧评审的具体 mutable-state 数量不再作为现状。跨功能载荷继续增加时再评估 route/payload 原子化及 typed source，保持 Shell 协调边界，不把风险推断写成已发生竞态，也不为该建议提前增加抽象。

跨产物 `.aps-session`/Profiler Session、统一时间线和 `perfd/perfa` 仍按 ADR 后置，不因旧研究被清理而提前实施。未来若确有 Live Telemetry、逐次 allocation/JVMTI 或严格同步缺口而启动 PoC，须另行确认 Android/API/ABI/build type、固定上游版本、构建/签名/分发校验、许可证、失败降级以及相对公开工具的收益；跨产物方案须保留内容哈希、producer、设备/进程身份、时间域/误差界限、能力/完整度和隐私策略。

## 保留的 Compose 专项门禁

- [ ] 按[当前 AGENT_BUNDLE 构建契约](../../layout-inspector/compose-inspection-host/AGENT_BUNDLE.md)验证无需完整 Android Studio tools/base 工作区即可构建 Host/Agent；同一 Android Agent Bundle 供 macOS、Windows、Linux 三宿主使用，不在各宿主重建设备端 Agent。
- [ ] 同 fixture 核对 View 层级、ID、类型、bounds、资源与基础属性，以及 Compose 层级、bounds、Semantics/TestTag、参数、Modifier、source 和 recomposition；缺失能力必须明确不可用，不能由传统 View 或 Semantics 降级结果推断完整证据。
- [ ] API 29+ 可调试应用在三 ABI 上无需业务代码改动即可注入；协议版本/能力协商、消息及资源上限、超时、未知字段兼容、仅经鉴权 ADB forward 的 socket、默认敏感值保护，以及 Agent/Inspector/发行包的来源、版本、SHA-256，均纳入单元、设备端、跨平台及 Android Studio 对照验收。
- [Compose 能力对齐设计](layout-inspector/2026-08-08-compose-inspection-capability-parity-design.md)完整保留：认证矩阵、三宿主验证、完整证据/工作流对照、性能预算、内部开关和不得提前宣称完整对齐的发布门禁均未取消。
- [制品获取研究](layout-inspector/2026-08-09-compose-inspection-artifact-acquisition-research.md)完整保留：固定源码/完整 manifest 或构建环境身份、精确版本 AAR 的 inspector.jar、同版本坐标回退而非邻近版本替代、签名/校验和、ABI、DEX 校验、许可证与 NOTICE/SBOM/provenance 分发要求均未取消。
