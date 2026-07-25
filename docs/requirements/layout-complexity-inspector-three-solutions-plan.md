# Android 布局复杂度检测工具：三种形态实施规划

## 一、总体方案

基于共享对话中提出的三种展示形态：[Android Studio 插件、独立桌面端、Web UI + App 内 HTTP Server](https://chatgpt.com/share/6a45a259-fc18-83ea-924d-cbbae9436962)。

三种方案统一复用：

- **Android Debug Agent**：采集 View 树、截图、布局事件。
- **分析内核**：复杂度评分、背景重叠、不可见 View、频繁 `requestLayout/invalidate` 等规则。
- **Layout Report Protocol v1**：版本化 JSON 协议。
- **Launcher 规则包**：Workspace、Hotseat、Folder、AllApps、Taskbar 专项规则。
- **ADB 通信层**：设备发现、端口转发、报告拉取。
- 不接入 Release 构建，不依赖 Layout Inspector 私有协议，不宣称能够测量真实 GPU 像素 overdraw。

### 共享接口

```text
LayoutSnapshot
- schemaVersion
- sessionId / packageName / processName
- deviceInfo / screenInfo / captureReason
- root: ViewNode
- metrics: ComplexityMetrics
- findings: Finding[]
- events: LayoutEventSummary[]
- screenshotRef

ViewNode
- stableNodeId / parentId / children
- className / resourceId / contentDescription
- bounds / visibility / alpha / z
- background / foreground / drawingFlags
- childCount / depth
- optional sourceHint

Finding
- ruleId / severity / confidence
- nodeIds / evidence
- recommendation
```

公共命令：

```bash
layout-agent start --package <package>
layout-agent snapshot --reason manual
layout-agent watch --duration 10s
layout-agent pull --output <directory>
layout-agent stop
```

### 共享质量门槛

- 500 个 View 的无截图快照采集 P95 ≤ 500ms。
- 截图与 Bounds Overlay P95 ≤ 1.5s。
- Debug Agent 空闲 CPU < 1%，额外内存 ≤ 20MB。
- JSON 协议向后兼容至少一个主版本。
- 规则结果可定位到具体 View，并包含证据和置信度。
- Release APK/AAB 中不存在 Agent、Receiver、Server 或调试权限。
- 同一快照重复分析结果完全一致。

---

## 二、共享内核建设

### 阶段 1：PoC，2 周

- 建立 Android `debugImplementation` Agent。
- 递归采集 DecorView，输出 ViewNode JSON。
- 支持手动快照、设备信息、基础复杂度统计。
- 在 Launcher Workspace、AllApps、Folder 三个场景验证。
- 明确自定义 View、SurfaceView、ComposeView 等能力边界。

**验收**：三类页面均可稳定生成完整 View 树，节点数量与人工检查一致率 ≥ 95%。

### 阶段 2：MVP，4 周

- 实现复杂度、背景覆盖、不可见挂载 View、高 childCount 等规则。
- 增加截图、Bounds Overlay、报告差异比较。
- 实现 ADB CLI、设备选择、进程重启恢复。
- 提供 Workspace、Hotseat、Folder、AllApps 首批规则。
- 固化 Protocol v1 和规则 ID。

### 阶段 3：Beta，4 周

- 加入 `requestLayout`、`invalidate`、关键 View Trace 采集。
- 支持连续快照、页面切换前后 Diff。
- 建立性能基线和误报样本库。
- 接入 CI：场景启动、采集、阈值比较、报告归档。
- 增加脱敏、报告大小限制和故障诊断日志。

### 阶段 4：稳定版，2 周

- API 冻结、接入文档、规则开发指南。
- 完成兼容矩阵和回归测试。
- 建立版本发布、Schema 迁移及规则变更审查机制。

**共享内核投入**：2 名 Android 工程师、1 名测试/性能工程师，约 12 周。

---

## 三、方案 A：Android Studio 插件

### 产品目标

在 IDE 内完成设备连接、快照采集、风险查看和源码跳转，适合高频开发与问题整改。

Android Studio 插件必须匹配目标 IDE 的 IntelliJ Platform 版本，并通过 Plugin Verifier 验证兼容性。[JetBrains 官方说明](https://plugins.jetbrains.com/docs/intellij/android-studio.html)、[兼容性验证](https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html)。

### 技术架构

- Kotlin + IntelliJ Platform Gradle Plugin 2.x。
- Tool Window 包含：
  - 设备与进程选择。
  - View Tree。
  - Screenshot/Bounds Overlay。
  - Finding 列表和过滤。
  - 属性面板、快照 Diff、事件排行。
- 插件只负责展示、ADB 编排和源码索引，不复制分析规则。
- 源码跳转策略：
  1. Resource ID → XML。
  2. Class Name → Kotlin/Java 类。
  3. `sourceHint` → 精确文件与行号。
  4. 无法精确定位时标记“候选位置”，不得伪装成精确结果。
- 通过公开 IntelliJ API 实现，禁止依赖 `@Internal` API。
- 首期支持团队正在使用的两个 Android Studio 主版本。

### 交付阶段

1. **PoC，2 周**
   - Tool Window、设备列表、导入本地 JSON。
   - 展示树、属性和截图 Bounds。
   - 验证 UI 在 2,000 节点下不卡顿。

2. **MVP，4 周**
   - 集成 ADB、启动 Agent、采集报告。
   - Finding 与节点双向联动。
   - Resource ID 和类名源码跳转。
   - 支持 JSON/HTML 导出。

3. **Beta，4 周**
   - 快照 Diff、连续采集、事件排行。
   - 多设备、断线恢复、Gradle Variant 检查。
   - 内部插件仓库和自动更新。
   - 两个 Android Studio 版本兼容测试。

4. **稳定版，3 周**
   - Plugin Verifier 纳入 CI。
   - 大型 View 树虚拟化、内存优化。
   - 插件异常隔离，确保不阻塞 IDE UI 线程。
   - 用户手册、故障诊断和版本兼容公告。

### 人力与周期

- 1 名 IntelliJ 插件工程师。
- 1 名 Android/ADB 工程师，投入 50%。
- 1 名测试工程师，投入 50%。
- 前端专属工作约 13 周；含共享内核约 16～18 周。

### 关键测试

- IDE 启动、卸载、升级和项目切换。
- USB/Wi-Fi ADB、多设备、离线设备。
- 500、2,000、10,000 节点树性能。
- XML、Java、Kotlin、自定义 View 的源码跳转。
- Android Studio 两个目标版本的 Plugin Verifier 和沙箱 E2E。
- Agent 或 ADB 异常时插件仍保持 IDE 可用。

### 主要风险

- **IDE API 变化**：限定支持版本，建立兼容分支。
- **源码定位不准确**：输出置信度与候选结果。
- **IDE 卡顿**：所有 ADB、解析和 Diff 在后台线程执行。
- **维护成本最高**：只在 Web/桌面方案验证稳定后扩大投入。

---

## 四、方案 B：独立桌面端

### 产品目标

提供不依赖 Android Studio 的完整分析工作台，适合内部性能专项、批量报告分析和跨项目使用。

### 技术架构

- Kotlin/JVM + Compose Multiplatform Desktop。
- 支持 macOS、Windows、Linux 原生安装包。
- 内置 ADB Bridge：
  - Platform Tools 路径发现。
  - 设备、用户、进程选择。
  - Agent 启停和端口管理。
  - 报告缓存及历史记录。
- 工作区：
  - View Tree、属性、截图 Overlay。
  - Finding 聚合与规则筛选。
  - 双快照/双版本 Diff。
  - Timeline 与 `requestLayout/invalidate` 排行。
  - 多设备和多构建对比。
- 报告保存在本地工作区，默认不上传服务器。

### 交付阶段

1. **PoC，2 周**
   - 打开本地报告。
   - Tree、Overlay、Finding 三面板联动。
   - 验证跨平台打包。

2. **MVP，4 周**
   - 内置 ADB 设备管理和实时采集。
   - 报告历史、搜索、筛选、导出。
   - 两个快照的节点和指标 Diff。
   - 支持异常恢复及日志导出。

3. **Beta，4 周**
   - 连续采集与布局事件 Timeline。
   - 多场景批量任务和基线比较。
   - 报告标签、备注、团队共享压缩包。
   - 自动检测 Platform Tools 版本。
   - 三平台安装包签名及内部更新。

4. **稳定版，3 周**
   - 大数据集性能优化。
   - 崩溃恢复、缓存清理和存储配额。
   - 安装、升级、降级验证。
   - CI 报告导入与趋势展示。

### 人力与周期

- 1 名 Kotlin/Compose Desktop 工程师。
- 1 名 Android/ADB 工程师，投入 50%。
- 1 名测试工程师，投入 50%。
- 前端专属工作约 13 周；含共享内核约 15～17 周。

### 关键测试

- macOS、Windows、Linux 安装与升级。
- 不同 Platform Tools 版本和多设备并发。
- 10,000 节点报告加载 ≤ 3s，常规交互保持流畅。
- 离线报告、损坏报告、未知 Schema 版本。
- 快照 Diff 对节点增删、移动、属性变化的识别。
- 应用退出或设备断开后端口和子进程被正确清理。

### 主要风险

- **跨平台差异**：ADB 调用与文件路径封装为平台适配层。
- **安装包体积较大**：内部工具接受自带 JVM，稳定优先。
- **无法直接跳转源码**：支持配置源码根目录和外部编辑器命令。
- **设备管理复杂**：所有 ADB 操作集中到单一状态机。

---

## 五、方案 C：Web UI + App 内 HTTP Server

### 产品目标

用最低部署成本快速验证能力；开发者通过浏览器访问当前设备的布局报告，适合 MVP、现场排查和报告分享。

ADB 官方支持通过 `adb forward` 将主机端口转发到设备端端口，可作为浏览器访问 Agent 的标准通道。[Android ADB 文档](https://developer.android.com/tools/adb)。

### 技术架构

- Web：React + TypeScript + Vite。
- App 内服务：Kotlin、Debug-only 嵌入式 HTTP Server，绑定 `127.0.0.1`。
- 浏览器通过以下链路访问：

```text
Browser
→ localhost:<dynamic-port>
→ adb forward
→ App Debug HTTP Server
→ Shared Agent
```

- API：
  - `POST /api/v1/snapshots`
  - `GET /api/v1/snapshots/{id}`
  - `GET /api/v1/snapshots/{id}/image`
  - `GET /api/v1/events`
  - `GET /api/v1/health`
  - `GET /api/v1/schema`
- 使用 SSE 推送采集状态和事件，不在首版引入 WebSocket。
- 静态 Web 资源随 Debug Agent 打包。
- Server 使用随机端口和 128 位会话 Token。
- Token 通过 `adb exec-out run-as <package>` 读取，不写日志、不进入报告。
- 只接受本机转发请求，限制请求体、并发数和快照频率。

### 交付阶段

1. **PoC，1 周**
   - HTTP Server、ADB Forward、浏览器打开静态页面。
   - 展示 View Tree 和原始属性。
   - 验证 Launcher 进程生命周期下服务可恢复。

2. **MVP，3 周**
   - Tree、Overlay、Finding、基础指标。
   - 手动快照、报告下载、错误提示。
   - Token 校验、Debug 构建隔离、请求限流。
   - 提供一键启动脚本。

3. **Beta，3 周**
   - 快照 Diff、连续采集、SSE 事件。
   - URL 状态持久化、报告压缩和离线导入。
   - Chrome/Edge 最新两个主版本兼容。
   - CI 生成静态 HTML 报告。

4. **稳定版，2 周**
   - CSP、安全头、依赖漏洞扫描。
   - 进程重启、端口冲突和多设备处理。
   - 前端资源缓存、超大树渲染优化。
   - 接入和故障诊断文档。

### 人力与周期

- 1 名 Web 工程师。
- 1 名 Android 工程师。
- 1 名测试工程师，投入 30%。
- 前端专属工作约 9 周；含共享内核约 12～14 周。

### 关键测试

- 未提供 Token、Token 错误、过期 Token均返回 401。
- Server 不能被局域网直接访问。
- Release 包中不存在 Server 和 Web 资源。
- 多设备端口自动分配且互不串线。
- 页面刷新、App 重启、ADB 重连后恢复。
- 10,000 节点树使用虚拟化渲染。
- 恶意大请求、路径遍历、脚本注入和敏感字段泄露测试。

### 主要风险

- **Debug Server 安全风险**：localhost、Token、限流、Release 剔除四重约束。
- **App 进程被杀导致页面失效**：前端健康检查并引导重新连接。
- **浏览器能力有限**：源码跳转通过自定义 `layoutinspector://` 协议或复制定位信息实现。
- **依赖影响 Debug APK**：Server 与静态资源放在独立 debug-only 模块。

---

## 六、测试与验收体系

### 单元测试

- View 树递归、节点 ID 稳定性、坐标换算。
- 复杂度评分和每条分析规则。
- Schema 序列化、兼容和迁移。
- 快照 Diff、严重级别与置信度计算。

### 集成测试

- Launcher Workspace、Hotseat、Folder、AllApps、Taskbar。
- 横竖屏、折叠屏、多窗口、动画中间态。
- 多进程、Activity 重建、Launcher 进程重启。
- ADB Forward、Pull、断线重连。

### E2E 测试

- 启动目标构建→进入场景→采集→查看风险→导出报告。
- 修改布局→再次采集→Diff 正确显示变化。
- CI 执行场景并在阈值回退时阻断构建。
- 三种前端对同一报告显示相同指标和 Finding。

### 非目标能力

- 不替代 Debug GPU Overdraw。
- 不保证获取每个像素的真实 GPU 绘制次数。
- 不修改或复用 Layout Inspector 私有协议。
- 首版不覆盖 Jetpack Compose 语义树；仅将 `ComposeView` 视为普通 View 节点，后续单独规划 Compose 采集器。

---

## 七、排期与决策建议

| 方案 | 首个 MVP | 稳定版 | 维护成本 | 最适用场景 |
|---|---:|---:|---:|---|
| C：Web UI | 共享内核完成后 3 周 | 12～14 周总周期 | 低 | 快速验证、现场分析 |
| B：桌面端 | 共享内核完成后 4 周 | 15～17 周总周期 | 中 | 专项性能分析、批量报告 |
| A：AS 插件 | 共享内核完成后 4 周 | 16～18 周总周期 | 高 | 日常开发、源码整改 |

推荐实施顺序：

1. 先完成共享内核和 Protocol v1。
2. 用方案 C 验证采集、规则和交互闭环。
3. 建设方案 B，形成稳定的独立分析工作台。
4. 最后建设方案 A，复用成熟交互并增加 IDE 源码能力。

## 八、假设与默认决策

- 服务对象为内部 Launcher/系统应用团队。
- 三种前端共享 Agent、协议、规则和测试样本，不重复实现分析逻辑。
- Android 侧全部能力仅进入 debuggable/system debug 构建。
- 默认同时支持 View 系统和嵌入其中的 `ComposeView`，暂不解析 Compose 内部节点。
- 第一稳定版以结构性 overdraw 风险为目标，真实 GPU overdraw 继续由系统工具验证。
- 团队若并行开发，建议共享内核完成 MVP 后，同时启动 Web UI 和桌面端；Android Studio 插件在 Protocol v1 冻结后启动。
