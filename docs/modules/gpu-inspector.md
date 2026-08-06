# GPU Inspector

## 功能作用

GPU Inspector 是一个 GPU 性能分析工具的集成模块，核心功能包括：

- **Android GPU Inspector (AGI) 集成**：
  - 自动检测本地已安装的 AGI 工具链（`AgiLocator.locate()`）
  - 支持从 AGI 可执行文件路径启动（`launchSupported`）
  - 支持命令行参数传递（System Profile / Frame Profile）
- **GPU 制品管理**：
  - 导入 AGI System Profile、Frame Profile、Perfetto Trace 等 GPU 相关的分析结果文件
  - `AgiArtifactIndexer` 对导入的制品进行 SHA-256 哈希索引，防止重复
  - `JsonAgiArtifactStore` 持久化制品列表到 JSON 文件
- **制品验证**：支持对已导入制品进行哈希完整性验证
- **多方式打开**：根据制品类型选择打开方式：
  - **AGI 打开**：AGI System Profile / Frame Profile
  - **Perfetto 打开**：Perfetto Trace 在应用内 Perfetto Viewer 中打开
  - **桌面打开**：截图、报告等用系统默认程序打开
- **设备上下文记录**：记录 GPU 设备上下文（序列号、型号、API Level、GPU 厂商、渲染器、驱动版本）

## 实现原理

### 工具链集成

1. **AGI 定位**：`AgiLocator` 按以下优先级查找 AGI 可执行文件：
   - 用户手动配置的路径
   - 系统 PATH 环境变量中的 `agi` 或 `gapic`
   - 默认安装路径（Android SDK 目录）
2. **能力检测**：`AgiCapability` 描述 AGI 工具链的能力：
   - `version`：AGI 版本号
   - `launchSupported`：是否支持命令行启动
   - `launchMode`：VERIFIED_CLI / GUI_ONLY / UNSUPPORTED
   - `supportedArguments`：支持的命令行参数
3. **AGI 启动**：`AgiLocator.launch(capability, args)` 启动 AGI 进程并传入制品文件路径

### 制品管理

1. **导入**：用户在 UI 中选择文件，`AgiArtifactIndexer.import()` 分析文件并创建 `GpuArtifact`：
   - 识别制品类型（`GpuArtifactKind`）：AGI_SYSTEM_PROFILE、AGI_FRAME_PROFILE、PERFETTO_TRACE、SCREENSHOT、EXTERNAL_REPORT
   - 计算 SHA-256 哈希
   - 确定打开能力（`ArtifactOpenCapability`）：AGI / PERFETTO / DESKTOP / NONE
2. **去重**：按 SHA-256 去重，避免重复导入相同制品
3. **持久化**：`JsonAgiArtifactStore` 将制品列表以 JSON 格式保存到 `~/.android-performance-studio/gpu-integration/artifacts.json`

### 数据结构

- **GpuArtifact**：GPU 制品，包含 id、kind、path、sha256、sizeBytes、agiVersion、device（GpuDeviceContext）、packageName、graphicsApi、capturedAt、openCapability
- **GpuDeviceContext**：设备上下文，包含 serial、model、apiLevel、gpuVendor、gpuRenderer、driverVersion
- **GpuCaptureContext**：采集上下文，包含 device、packageName、graphicsApi、frameCapture
- **AgiCapability**：AGI 工具链能力，包含 executable、version、launchSupported、launchMode、supportedArguments

### 图形 API 支持

- **Vulkan**：低级图形 API，需要 AGI System Profile
- **OpenGL ES**：传统移动图形 API
- **OpenGL on ANGLE**：通过 ANGLE 转换层运行的 OpenGL

### 数据流

```
[AGI/设备] --导出--> [.gfxtrace / .perfetto-trace] --导入--> [AgiArtifactIndexer]
    --> [GpuArtifact] --> [JsonAgiArtifactStore] (持久化)
    --> [Compose UI: GpuIntegrationScreen]
    --> 打开:
        - AGI System/Frame Profile --> [AgiLocator.launch()] --> [AGI 桌面应用]
        - Perfetto Trace --> [onOpenTrace] --> [Perfetto Viewer]
        - 其他 --> [Desktop.open()] --> [系统默认程序]
```

### 与其他模块的关系

- **Perfetto Viewer**：GPU Inspector 导入的 Perfetto Trace 可委托给 Perfetto Viewer 模块打开（通过 `onOpenTrace` 回调）
- **Source Workspace**：制品导入记录可作为性能证据关联到 Source Workspace

## 优化建议与改进点

> 以下内容不替换已有设计，仅作为可考虑的更好实现方式或优化点补充。每条标注 **影响**（高/中/低）与 **可行性**（高/中/低）。

### 1. Android GPU Inspector (AGI) 已停更的风险【影响:高 / 可行性:中】

**当前实现问题**：AGI 自 2023 年起已停止官方维护（Google 官方仓库归档/转入维护模式，新版本与驱动兼容性下降），Vulkan Layer 拦截在新 Android 版本与 GPU 驱动上存在已知崩溃。把核心 GPU 采集链路绑定到 AGI 是长期风险。

**更好的实现方式**：
- 在文档显著位置标注 AGI 维护状态，并在 `AgiCapability` 增加 `deprecatedHint` 字段，让 UI 在用户启动 AGI 时提示"工具已停止维护，部分设备可能不可用"。
- 优先迁移到 **Perfetto GPU 数据源**：`gpu.counters`、`gpu.renderstages`、`gpu.memory` track 在 Android 12+ 可直接由 Perfetto 采集，不依赖 AGI 的 Vulkan layer 拦截，且与应用内 Perfetto Viewer 天然集成。
- 对 Frame Profile（per-frame GPU 阶段）需求，评估用 **Perfetto `gpu.renderstages` + `frame_timeline`** 替代 AGI Frame Profiler；这条路径长期可维护性远高于 AGI。
- 保留 AGI 集成作为"老设备/老 trace 兼容"的降级路径，但不再作为主推。

### 2. SHA-256 全文件哈希对大文件的代价【影响:中 / 可行性:高】

**当前实现问题**：`AgiArtifactIndexer` 对每个导入制品计算 SHA-256 全文件哈希。GPU trace（.gfxtrace、.perfetto-trace）常达数百 MB 至数 GB，全文件 SHA-256 在导入时阻塞 UI 且增加磁盘 IO。

**更好的实现方式**：
- 改用 **部分哈希（partial hash）**：文件头 + 中间 + 尾部各取若干 MB 拼接后哈希，速度提升一个数量级，去重场景已足够。
- 或用 **文件大小 + 前缀哈希** 组合作为快速去重键，冲突时再回退到全文件哈希（两级策略）。
- 把哈希计算改为后台异步，导入时先落库（status=INDEXING），完成后再更新 sha256，UI 即时响应。

### 3. JSON 文件存储 → SQLite 的可扩展性【影响:中 / 可行性:高】

**当前实现问题**：`JsonAgiArtifactStore` 把制品列表序列化为单个 JSON 文件。制品增多后（数百条），每次导入/查询都要全量读写整个 JSON，并发写入也无锁，存在数据损坏风险。

**更好的实现方式**：
- 迁移到 **SQLite（与其他 Profiler 模块统一）**，如 `SqliteGpuArtifactStore`，复用项目已有 SQLite 基建（battery/network/startup 都用 SqliteXxxStore）。
- 表结构：`artifacts(id, kind, path, sha256, size, agi_version, package_name, graphics_api, captured_at, ...)`，按 sha256 建唯一索引，按 captured_at 建查询索引。
- 保留一次性 JSON 导出/导入作为迁移与备份格式，但日常读写走 SQLite。

### 4. 图形 API 列表的完整性【影响:低 / 可行性:高】

**当前实现问题**：图形 API 只列了 Vulkan、OpenGL ES、OpenGL on ANGLE，未覆盖 Vulkan on ANGLE、Vulkan Ports/HLE、WebGPU（新设备已出现）等。

**更好的实现方式**：
- 扩展枚举到 `VULKAN`、`GLES`、`VULKAN_ON_ANGLE`、`GLES_ON_ANGLE`、`WEBGPU`，并在制品导入时通过 trace 文件头自动识别而非依赖用户选择。
- 在 `GpuDeviceContext` 增加 `angleVersion`（如适用），便于追溯 ANGLE 转换层的版本差异。

### 5. GPU 设备上下文的采集完整性【影响:中 / 可行性:高】

**当前实现问题**：`GpuDeviceContext` 含 serial/model/apiLevel/gpuVendor/gpuRenderer/driverVersion，但缺少对帧分析重要的扩展字段：GPU 时钟频率、显存、纹理压缩格式支持、shader 编译器版本。

**更好的实现方式**：
- 扩展 `GpuDeviceContext`：`maxClockMhz`、`totalVramBytes`、`supportedTextureFormats`（如 ASTC/BC）、`shaderCompilerVersion`。
- 这些字段可从 `dumpsys gfxinfo`（部分设备）或 GL/Vulkan extensions（`glGetString(GL_VERSION)` / `vkEnumeratePhysicalDevices`）读取，对解释"为何这台设备 GPU 慢"很有价值。

### 6. 制品打开方式的回退链【影响:中 / 可行性:高】

**当前实现问题**：`ArtifactOpenCapability` 分 AGI / PERFETTO / DESKTOP / NONE，但 AGI 不可用时如何打开 AGI System/Frame Profile 没有清晰回退（AGI 制品目前只有 AGI 自己能解析）。

**更好的实现方式**：
- 对 `AGI_SYSTEM_PROFILE` / `AGI_FRAME_PROFILE`，当 `AgiCapability.launchSupported=false` 时，UI 应明确提示"本机未安装 AGI 或版本不兼容，无法打开"，并引导安装/迁移路径，而不是落到 DESKTOP 用默认程序打开（默认程序打不开 .gfxtrace）。
- 对这类"无可打开方式"的制品，提供"在文件系统中定位"作为最小可用动作。

### 7. 制品的生命周期管理【影响:低 / 可行性:中】

**当前实现问题**：制品导入后持久化在 JSON/SQLite，但原始文件被移动或删除时，`path` 失效，UI 仍展示但打开失败。

**更好的实现方式**：
- 启动或打开前做一次 **文件存在性校验**，对失效路径标记 `missingOnDisk=true`，UI 灰显并提示"原文件已移走/删除"。
- 提供"重新定位"功能，让用户指定新路径（并校验 sha256 一致才接受），避免重新走完整导入。

### 8. 制品去重的语义【影响:低 / 可行性:高】

**当前实现问题**：按 SHA-256 去重，逻辑上正确，但"同一份 trace 被不同用户/不同会话重复导入"是合法的关联需求（同一份证据关联到多个实验），硬去重会让关联场景受限。

**更好的实现方式**：
- 物理制品（文件）按 sha256 去重存储，但 **关联记录（artifact → session/experiment）** 允许多条，即"物理唯一、逻辑多关联"。
- 在数据模型中区分 `GpuArtifact`（物理制品，唯一）与 `ArtifactReference`（逻辑关联，可多条），支持同一 trace 关联到多个实验而不重复占用磁盘。
