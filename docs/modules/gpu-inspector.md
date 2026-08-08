# GPU Inspector

## 功能作用

GPU Inspector 集成 Android GPU Inspector（AGI）与应用内 Perfetto Workspace，用于管理和打开 GPU 性能证据：

- 定位、探测和启动本地 AGI。
- 导入 AGI System Profile、Frame Profile、Perfetto Trace、截图和外部报告。
- 用完整 SHA-256 标识内容并验证文件完整性。
- 使用 JSON v2 索引保存制品元数据、设备证据、图形 API、实现层和多个文件位置。
- 根据制品类型、文件状态和当前工具能力动态提供 AGI、Perfetto、桌面打开或仅启动 AGI。
- 检查外部文件是否缺失或大小变化，并支持按完整 SHA-256 重新定位。

AGI 用于详细帧和图形 API 调用分析；Perfetto 用于查看已有 Trace 中的系统 GPU、内存、Render Stages 与 FrameTimeline 证据。本模块不声称两者可以互相完整替代。

## 工具链集成

`AgiLocator` 按以下顺序查找可执行文件：

1. 用户本次显式选择的路径。
2. `PATH` 中的 `agi`／`gapic`。
3. 各桌面平台的已知安装位置。

定位后分别探测：

- `launchSupported`：可执行文件是否可以启动。
- `launchMode`：是否探测到设备、包名、Activity 或采集等自动化参数；未探测到时为 GUI-only。
- `artifactOpenSupported`：是否为已识别的 AGI／GAPIC launcher，可以用单个位置参数打开制品。

“可启动”不等于“能打开指定文件”。未知的自定义可执行文件只允许启动，不会收到制品路径。

## 制品导入与持久化

1. 文件选择器取得外部路径。
2. IO 调度器读取文件头、计算完整 SHA-256 并建立 `GpuArtifact`。
3. 相同 SHA-256 复用内容记录，但保留所有已导入位置；最近导入位置成为主位置。
4. `JsonAgiArtifactStore` 先写同目录临时文件，再原子替换 `~/.android-performance-studio/gpu-integration/artifacts.json`。
5. JSON 解析失败会向 UI 报错并保留原文件，不会伪装成空索引。

旧数组格式和旧 `openCapability`／`OPENGL_ON_ANGLE` 值可在读取时迁移；新文件使用 schema v2 对象格式。

## 数据语义

- **GpuArtifact**：由完整 SHA-256 标识的 GPU 制品内容，以及类型、大小、来源元数据和一个或多个外部位置。
- **GPU 制品位置**：外部路径；位置可动态变为 Available、Missing 或 Size Changed，不属于内容身份。
- **GraphicsApi**：应用提交接口，仅包含 Vulkan、OpenGL ES、WebGPU 和 Unknown。
- **GraphicsImplementationContext**：驱动或翻译实现的名称、版本、后端 API 与证据来源，例如以 Vulkan 为后端的 ANGLE。
- **GpuDeviceContext**：设备、GPU 和驱动字段，以及字段到证据来源的映射；缺失值保持未知。
- **ArtifactOpenRoute**：制品类型推荐的 AGI、Perfetto、Desktop 或 None 路由，不表示当前一定可用。

GPU 频率、GPU 内存等随时间变化的数据属于 Perfetto 时间序列证据，不写入静态设备上下文。

## 已实现的优化

### 1. AGI 与 Perfetto 按能力分工

- 不再把 AGI 标记为已停更或旧设备降级路径。
- AGI 制品走 AGI；Perfetto Trace 委托给现有 Perfetto Workspace。
- UI 明确展示两类工具的证据职责。
- 依据：[AGI 官方页面](https://developer.android.com/agi)、[AGI GitHub 仓库](https://github.com/google/agi)、[Perfetto GPU 数据源](https://perfetto.dev/docs/data-sources/gpu)。

### 2. 后台计算完整 SHA-256

- 导入、验证、重新定位和 JSON 保存均在 `Dispatchers.IO` 执行。
- UI 显示索引中状态并在 IO 完成前禁用冲突操作。
- 文件大小只用于快速状态检查；判重和重新定位仍要求完整 SHA-256。

### 3. JSON 无损、原子持久化

- schema v2 完整往返保存 device、graphics API、graphics implementation、warnings 和备用位置。
- 临时文件加原子替换防止写入中断破坏上一份索引；文件系统不支持原子移动时回退为同目录替换。
- 解析错误明确上报，不静默清空列表。
- 当前仍使用 JSON；未引入没有规模依据的 SQLite 迁移。

### 4. 分离图形提交 API 与实现层

- 移除混合语义的 `OPENGL_ON_ANGLE`，增加 `WEBGPU`。
- ANGLE 及其 Vulkan 后端记录在 `GraphicsImplementationContext`，不伪装成新的提交 API。
- 普通文件扩展名和文件头不用于猜测图形 API 或实现层。

### 5. GPU 设备证据保真

- `GpuDeviceContext.evidenceSources` 保存设备字段的来源，图形实现也保存独立来源。
- JSON round-trip 测试覆盖设备、驱动、API、ANGLE 后端与来源。
- 当前文件选择导入不会自动采集设备上下文；只有调用方提供的证据才会保存。

### 6. 动态打开能力

- 每次加载列表及打开前，根据位置状态和当前 AGI 能力重新计算动作。
- 已识别 AGI launcher 才能收到制品路径；其他可执行文件只显示“启动 AGI”。
- `.gfxtrace` 不回退到系统默认程序，并始终提供“在文件系统中定位”。

### 7. 文件生命周期与重新定位

- 列表加载与操作完成后检查每个已知位置的存在性和大小，不持久化派生的 `missingOnDisk`。
- 重新定位在 IO 调度器执行大小预检和完整 SHA-256 校验，内容一致才更新位置。
- 不扫描磁盘或猜测新位置。

### 8. 分离内容与位置

- SHA-256 表示内容身份，同一内容可以保留多个外部路径。
- 重复导入不再使用 `distinctBy(sha256)` 静默丢弃备用路径。
- 本模块只索引外部文件，不复制内容，因此不声称去重可以节省磁盘空间。
- GPU 制品与 Analysis Session 的多重引用语义已确定，但当前没有 GPU 会话关联入口；接入时再增加引用记录。

## 数据流

```text
[AGI / Perfetto / external file]
    -> file chooser
    -> Dispatchers.IO: detect kind + full SHA-256
    -> GpuArtifact content + locations
    -> JSON v2 atomic store
    -> runtime location/tool availability
    -> AGI | Perfetto Workspace | Desktop | reveal/relocate
```

## 当前边界

- 不在本模块内采集 Perfetto GPU Trace，只导入并打开已有 Trace。
- 不解析 AGI 私有 payload，不从普通文件名或文件头推断设备、图形 API 或根因。
- 不自动采集设备扩展、纹理格式、静态 VRAM 或 Shader 编译器版本。
- 尚未实现 GPU 制品到 Analysis Session／Source Workspace 的引用入口。

## 主要代码位置

- 模型：`desktop-viewer/gpu-inspector-integration/gpu-integration-model`
- AGI 工具链：`desktop-viewer/gpu-inspector-integration/agi-toolchain`
- 索引与 JSON：`desktop-viewer/gpu-inspector-integration/agi-artifact-index`
- UI：`desktop-viewer/gpu-inspector-integration/presentation`
- 页面编排：`desktop-viewer/gpu-inspector-integration/gpu-integration-app`
