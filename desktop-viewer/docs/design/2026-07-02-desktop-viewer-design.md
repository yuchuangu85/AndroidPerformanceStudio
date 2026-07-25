# Desktop Viewer 设计规格

## 1. 目标与成功标准

Desktop Viewer 是面向内部多个 Android 项目的独立布局复杂度分析工具。它不依赖 Android Studio、root、系统签名、隐藏 API 或 Launcher 私有实现。

目标：

- 普通 Android 应用通过 `debugImplementation` 零代码接入。
- 支持 Android 5.0（API 21）到最新稳定 Android 版本。
- 完整支持传统 View 层级，并通过可选适配器支持 Jetpack Compose。
- 在 macOS、Windows、Linux 上提供一致的在线采集与离线报告分析体验。
- 检测结构性过度绘制风险、布局复杂度、异常 View 和频繁布局事件；不冒充真实 GPU overdraw 计数器。

稳定版成功标准：

- 500 节点无截图在线快照 P95 不超过 500ms。
- 10,000 节点离线报告首次加载不超过 3 秒。
- Android Agent 空闲 CPU 低于 1%，附加内存不超过 20MB。
- Release 变体中不存在 Agent 代码、组件、资源或新增权限。
- 在线连接失败、设备断开、应用重启和协议不兼容都有可恢复状态。

## 2. 技术决策

### 2.1 桌面技术栈

采用 Kotlin/JVM 与 Compose Multiplatform Desktop。

原因：

- 与 Android Agent、协议模型和分析内核共享 Kotlin 类型及序列化实现。
- 避免维护 Kotlin、Rust、TypeScript 三套领域模型。
- Compose Desktop 支持 macOS、Windows、Linux 原生分发与 UI 自动化测试。
- 安装包内置裁剪后的运行时，不要求用户安装 JDK。

构建与打包使用 JDK 17 或更高版本；Compose Desktop 原生打包依赖 `jpackage`，官方文档要求打包阶段使用 JDK 17+。

参考：

- [Compose Multiplatform 原生分发](https://github.com/JetBrains/kotlin-multiplatform-dev-docs/blob/master/topics/compose/compose-native-distribution.md)
- [Compose Desktop UI 测试](https://github.com/JetBrains/kotlin-multiplatform-dev-docs/blob/master/topics/compose/compose-desktop-ui-testing.md)

### 2.2 Android 接入边界

- 最低版本：API 21。
- 仅支持 `android:debuggable="true"` 的构建。
- SDK 通过 `debugImplementation` 引入。
- 默认使用 AndroidX Startup 自动初始化，并注册 `ActivityLifecycleCallbacks`。
- 提供显式 `LayoutAgent.install()` 和 Manifest 元数据，用于关闭自动初始化或覆盖默认配置。
- 不申请网络权限，不开放 TCP Server，不使用系统级 AccessibilityService。
- 通过 ADB 转发本地 abstract socket 与桌面端通信。

### 2.3 UI 技术覆盖

- `agent-view`：稳定核心，采集原生 View 层级、属性、坐标、绘制标记和布局事件。
- `agent-compose`：可选模块，提供 Compose 语义树深度采集。
- Compose 正式支持窗口为当前稳定系列及前两个稳定系列。
- 不在正式窗口内的 Compose 版本自动降级到公共 Android 可访问语义，最差降级为单个 `ComposeView` 宿主节点。
- Compose 适配失败不得影响 View 采集、应用运行或桌面连接。

## 3. 项目架构

`desktop-viewer/` 是独立 Gradle 根工程，内部采用共享候选内核加桌面应用的模块结构：

```text
desktop-viewer/
  settings.gradle.kts
  desktop-app/
  layout-inspector/
    shared-kernel/
      protocol-model/
      analysis-engine/
      android-agent-core/
      android-agent-view/
      android-agent-compose/
      android-agent-startup/
      test-fixtures/
    presentation/
    application/
    adb-gateway/
    report-storage/
    platform-integration/
    samples/
  simpleperf-viewer/
  docs/
```

这些 `layout-inspector/shared-kernel` 模块目前只服务 Layout Inspector；待 Android Studio 插件或 Web 方案产生真实依赖后，再评估提升为仓库级共享构建。

### 3.1 Shared Kernel

#### Protocol Model

职责：

- 定义在线传输和离线报告共用的数据模型。
- 负责 JSON/二进制帧序列化、版本检查和 capability 协商。
- 不依赖 Android、Compose Desktop、数据库或具体传输实现。

核心类型：

```kotlin
data class ProtocolVersion(
    val major: Int,
    val minor: Int,
)

data class AgentCapabilities(
    val viewHierarchy: Boolean,
    val composeSemantics: ComposeCapability,
    val pixelCopy: Boolean,
    val softwareScreenshot: Boolean,
    val layoutEvents: Boolean,
)

data class LayoutSnapshot(
    val schemaVersion: ProtocolVersion,
    val session: SessionMetadata,
    val screen: ScreenMetadata,
    val rootNodes: List<UiNode>,
    val metrics: ComplexityMetrics,
    val findings: List<Finding>,
    val screenshot: ArtifactReference?,
)

sealed interface UiNode {
    val nodeId: String
    val parentId: String?
    val bounds: IntRect
    val children: List<String>
}

data class ViewNode(/* View-specific fields */) : UiNode
data class ComposeNode(/* semantics-specific fields */) : UiNode
```

兼容规则：

- 主版本不一致时停止实时解析，但允许保存原始数据用于升级后重新打开。
- 未知次版本字段必须忽略。
- Desktop Viewer 至少读取当前主版本和前一个主版本。
- Agent 与 Viewer 连接后先交换版本与 capability，再允许采集。

#### Analysis Engine

职责：

- 复杂度指标计算。
- 背景及前景矩形覆盖风险分析。
- 深层级、高 childCount、不可见挂载节点、透明覆盖节点检测。
- 快照 Diff 和回归判定。
- Finding 证据、置信度及修复建议生成。

规则必须：

- 使用稳定 `ruleId`。
- 明确适用节点类型与所需 capability。
- 输出参与节点、面积比例、阈值和降级原因。
- 不将结构性风险描述为真实 GPU overdraw。

#### Android Agent

`android-agent-core` 管理生命周期、会话、主线程采集调度和能力探测。

`android-agent-view` 负责：

- 从当前 resumed Activity 的 DecorView 递归采集。
- 收集 ID、类名、visibility、alpha、bounds、padding、背景、前景、Z、裁剪标记和 childCount。
- 对访问失败的单个属性记录缺失原因，而不是终止整个快照。

`android-agent-compose` 负责：

- 运行时识别 Compose UI 版本。
- 选择匹配适配器。
- 将 Compose 语义节点映射到统一 `ComposeNode`。
- 不支持或失败时返回明确降级 capability。

`android-agent-startup` 负责：

- AndroidX Startup 初始化。
- 自动注册 Activity 生命周期。
- 仅在 debuggable 构建中启动。
- 生成每次进程唯一的 socket 名称和认证 Token。

### 3.2 Desktop Viewer

#### Presentation

采用三栏检查器布局：

```text
顶部：设备 / 进程 / Activity / 采集 / 导入 / 导出

左栏：View + Compose 统一层级树
中栏：Screenshot + Bounds Overlay
右栏：Properties / Findings / Metrics

底部：Timeline / Diff / Diagnostics
```

交互原则：

- 树、截图、Finding 和属性面板共享同一个 `selectedNodeId`。
- 任一面板选择节点时，其他面板同步定位。
- 大树使用虚拟列表，截图 Overlay 只绘制可见节点。
- 在线与离线模式使用同一工作区，不创建两套界面。
- 能力不可用时显示原因和降级模式，不隐藏缺失功能。

#### Application

用例：

- 发现设备及 debuggable 进程。
- 建立、恢复和关闭 Agent 会话。
- 请求快照或指定时间窗口事件。
- 打开、保存、标记、比较及导出报告。
- 管理存储配额与受保护报告。

Application 层只依赖接口，不直接调用 ADB、SQLite 或 Compose UI。

#### ADB Gateway

状态机：

```text
Disconnected
→ DeviceSelected
→ ProcessDiscovered
→ ForwardEstablished
→ Handshaking
→ Ready
→ Capturing
→ Ready
```

失败状态保留：

- 当前设备序列号。
- 包名和进程名。
- 最近一次成功 capability。
- 可执行的恢复动作。

连接流程：

1. 使用最新 Android SDK Platform Tools 执行 `adb devices -l`。
2. 检查设备状态及目标包是否 debuggable。
3. 使用 `run-as` 读取 Agent 会话描述。
4. 创建 `tcp:0` 到 `localabstract:<session>` 的端口转发。
5. 使用随机 Token 完成握手。
6. 协商协议版本和 capability。
7. 会话结束时删除端口转发和临时进程。

Android 官方说明最新 Platform Tools 原则上向后兼容旧 Android 版本：
[SDK Platform Tools release notes](https://developer.android.com/tools/releases/platform-tools)。

#### Report Storage

采用 SQLite 与内容寻址文件存储：

- SQLite：报告索引、设备、会话、指标、Finding、标签和保留状态。
- 文件存储：原始快照、截图、事件流和导出包。
- 文件以 SHA-256 命名，重复内容只存一份。
- 默认配额 10GB。
- LRU 清理仅删除未保护报告；手动标记保留的报告不自动删除。
- 数据库迁移必须可回滚；迁移失败时以只读模式打开旧数据。

## 4. 数据流

### 4.1 在线快照

```text
用户点击 Capture
→ Application 创建采集请求
→ ADB Gateway 发送协议消息
→ Agent 在主线程读取 UI 状态
→ 工作线程序列化、分析并压缩
→ Viewer 接收 Snapshot 与可选截图
→ Storage 原子写入
→ UI 更新树、Overlay、Finding 与指标
```

主线程只允许执行 View/Compose 状态读取；序列化、压缩、规则计算和文件写入必须离开主线程。

### 4.2 截图

- API 26+：优先使用 PixelCopy。
- API 21–25：使用 Bitmap Canvas 调用根 View `draw()`。
- 软件截图标记 `SOFTWARE_FALLBACK`。
- SurfaceView、TextureView、硬件 Bitmap、实时阴影或系统窗口可能缺失时，在报告中记录 limitation。

PixelCopy 从 API 26 提供，官方建议用于窗口截图：
[PixelCopy API](https://developer.android.com/reference/android/view/PixelCopy)。

### 4.3 离线报告

- 导出包包含 manifest、协议版本、快照、截图、事件和校验和。
- 打开时先验证大小限制、路径和校验和，再写入本地存储。
- 未知主版本报告以只读原始模式展示元数据，不尝试错误解析。

## 5. 安全与隐私

- Agent 只存在于 debug 依赖图。
- SDK 在运行时再次检查 `ApplicationInfo.FLAG_DEBUGGABLE`。
- 连接使用进程级随机 Token。
- socket 不监听局域网，不增加 `INTERNET` 权限。
- Desktop Viewer 不执行报告内脚本或外部命令。
- 文本属性默认限制长度，并提供 contentDescription/text 脱敏开关。
- 报告导出默认不包含完整文本内容，可由用户显式启用。
- 日志禁止写入 Token、完整用户文本和截图二进制。

## 6. 兼容矩阵

### Android

必测 API：

- API 21：最低版本和 Canvas 截图。
- API 23：早期运行时权限环境。
- API 26：PixelCopy 起点。
- API 28：旧式 View/渲染兼容边界。
- API 30：现代窗口及存储行为。
- API 33：组件导出和通知相关行为变化环境。
- API 35：近期稳定版本。
- 最新稳定 API：前向兼容。

每次稳定版至少在一台真实设备和相应 AVD 上完成端到端采集；不能只依赖 Robolectric。

### Compose

- 当前稳定系列。
- 前一个稳定系列。
- 前两个稳定系列。
- 一个更老版本用于验证降级。
- 一个最新预览版用于验证未知版本不会崩溃。

依赖版本使用 Compose BOM 管理，避免单独组合未经验证的 Compose 组件版本：
[Compose BOM](https://developer.android.com/develop/ui/compose/bom)。

### Desktop

- macOS 13+：Intel、Apple Silicon。
- Windows 10 22H2、Windows 11：x64。
- Ubuntu 22.04、24.04 LTS：x64。

原生安装包分别在目标操作系统构建：

- macOS：DMG。
- Windows：MSI。
- Linux：DEB。

## 7. 测试策略

### 单元测试

- 协议序列化、未知字段和版本协商。
- View/Compose 节点映射。
- 每条分析规则的阳性、阴性及边界样本。
- Diff 节点匹配和回归判定。
- ADB 输出解析和状态机转移。
- SQLite 迁移、配额和 LRU。

### 集成测试

- 示例 View 应用、纯 Compose 应用和混合应用。
- Activity 切换、重建、多窗口和进程重启。
- USB、Wi-Fi ADB、多设备及设备离线。
- API 21–25 Canvas 与 API 26+ PixelCopy。
- Compose 支持、降级及适配器加载失败。
- 损坏、超大、未知版本的离线报告。

### Desktop UI 测试

- 树、截图、Finding 和属性面板选择同步。
- 10,000 节点虚拟列表。
- 缩放、平移、Bounds 命中测试。
- 连接失败、权限不足和协议不兼容提示。
- 快照 Diff 和 Timeline 过滤。

### 发布验证

- 三个平台干净机器安装、升级、降级和卸载。
- 应用 release 依赖图与 APK 内容扫描，确保无 Agent。
- 安装包签名和内部制品校验。
- 内部 Maven 元数据及依赖解析验证。

## 8. 交付阶段

### 阶段 0：工程基础，1 周

- Gradle 多模块工程。
- Protocol v1 骨架。
- CI、静态检查和示例应用。
- 三平台构建任务雏形。

### 阶段 1：Android Agent，3 周

- View 采集、自动初始化和会话生命周期。
- localabstract socket 与 ADB 连接。
- API 21+ 截图双实现。
- 首批性能基准。

### 阶段 2：Desktop MVP，4 周

- 设备、进程和会话管理。
- 三栏工作区。
- 树、属性、截图和 Bounds 联动。
- 在线快照、离线导入和基础存储。

### 阶段 3：分析能力，3 周

- 复杂度、覆盖、不可见节点和 childCount 规则。
- Finding、指标和快照 Diff。
- 报告导出及内容校验。

### 阶段 4：Compose 与事件，2 周

- Compose 适配器及版本窗口。
- 通用语义降级。
- `requestLayout`、`invalidate` 汇总和 Timeline。

### 阶段 5：稳定化，2～3 周

- 跨平台安装包。
- 兼容矩阵回归。
- 大型报告性能优化。
- 内部 Maven 与桌面制品发布。
- 接入、诊断和版本支持文档。

总周期：15～16 周。

推荐人员：

- 1 名 Android/Agent 工程师。
- 1 名 Kotlin/Compose Desktop 工程师。
- 1 名测试/性能工程师。

三人可并行时，阶段 1 后可将总周期压缩至约 12 周。

## 9. 明确不做

首个稳定版不包含：

- 真实 GPU 每像素 overdraw 计数。
- 非 debuggable 或生产应用连接。
- root、系统签名或隐藏 API 模式。
- Android Studio 源码跳转。
- 云端报告同步、账号体系和远程遥测。
- iOS 或 Web 应用分析。

## 10. 风险与缓解

- **Compose 内部实现变化**：隔离适配器、限定支持窗口、提供通用降级。
- **老 Android 截图不完整**：明确 capability 和 limitation，不伪造完整截图。
- **ADB 多设备状态复杂**：集中状态机管理，所有资源按设备序列号和会话隔离。
- **大型树导致桌面卡顿**：虚拟列表、增量索引、后台解析和按需 Overlay。
- **Agent 影响目标应用性能**：仅手动采集，限制连续采样频率，提供可量化基准。
- **报告泄露用户内容**：默认脱敏、显式启用文本、导出前提示。

## 11. 默认假设

- 工具用于内部多个 Android 项目，不对外提供商业支持。
- Agent 通过内部 Maven 仓库发布。
- Desktop Viewer 通过内部签名制品分发。
- 开发和 CI 使用最新稳定 Android Platform Tools。
- 桌面原生分发由对应操作系统的 CI Runner 构建。
