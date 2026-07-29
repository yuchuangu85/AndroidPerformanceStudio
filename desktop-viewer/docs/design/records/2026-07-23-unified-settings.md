<!-- status: implemented -->

# 统一设置窗口 (Unified Settings)

> **状态：已实施 (Implemented)**
> 统一窗口、页面路由、即时持久化、错误提示、完整模块设置迁移及独立运行回退已完成。

> **评审状态：已评审 (Reviewed)**
> 原始草案保留在下方；实施应以“修订设计”为准。

## 动机

当前三个设置入口各自独立，体验碎片化：

| 设置 | 入口 | 形式 |
|---|---|---|
| **通用设置**（语言/主题） | macOS `Cmd+,` Preference Handler | `AlertDialog` 420dp |
| **Layout Inspector 设置** | File → Settings 菜单项 | `AlertDialog` 520dp |
| **Simpleperf 设置** | File → Settings / Configuration 菜单 | 内联面板（非弹窗） |

用户需要到不同位置找到各项设置，不符合桌面 App 的常见习惯。

## 设计方案

### 统一设置窗口 + 侧边栏导航

将所有设置合并到一个独立 Window/Dialog 中，左侧侧边栏切换模块，右侧展示对应的设置内容。

```
┌──────────────────────────────────────────────────┐
│  设置 (Settings)                            ✕   │
├──────────────┬───────────────────────────────────┤
│  通用        │  语言:  [跟随系统 ▾]             │
│  Layout      │  主题:  [跟随系统 ▾]             │
│  Inspector   │                                   │
│  Simpleperf  │                                   │
│              │                                   │
└──────────────┴───────────────────────────────────┘
```

- **容器**：独立 `Dialog`（非 `AlertDialog`），支持更大尺寸以适应多模块
- **导航**：左侧垂直列表/`NavigationRail`，选中项高亮
- **入口**：macOS `Cmd+,` 统一触发，默认选中"通用"标签
- **各模块内部菜单入口**：保留但改为触发统一设置窗口并定位到对应 tab

### 为什么不选标签页 (Tabs)

- 标签页在模块名较长时横向空间受限
- 侧边栏更符合桌面 App 默认惯例（VS Code、IntelliJ IDEA、Figma）
- 未来添加新设置模块时扩展性好

## 涉及模块（现状）

### 1. 通用设置 (`desktop-app`)
- 文件：`ApplicationSettingsDialog.kt`
- 内容：语言 (`ApplicationLanguagePreference`)、主题 (`ApplicationThemePreference`)
- 数据：`ApplicationUiSettings` + `ApplicationUiSettingsStore`（Java Preferences）

### 2. Layout Inspector 设置 (`layout-inspector/presentation`)
- 文件：`SettingsDialog` in `ThemeSettingsDialog.kt`
- 内容：视图选项 toggle、存档快照限制 slider、Canvas 边框颜色
- 数据：`ViewDisplayOptions`、`CaptureArchiveLimits`、`CanvasBorderColors`

### 3. Simpleperf 设置 (`simpleperf-viewer`)
- 文件：`SimpleperfUiSettings.kt` + `CaptureConfigurationWorkspace.kt` + `ProfilerPreferences.kt`
- 内容：采集模板、采集配置、高级参数、Flame Tooltip 模式、引擎选择
- 数据：`SimpleperfUiSettings` + `CaptureSettingsSection` 枚举

## 实施步骤（草案）

### Task 1: 创建统一设置窗口容器
- 新建 `DesktopAppSettingsDialog.kt`（`Dialog`，非 `AlertDialog`）
- 左侧 `Column` 列表导航，右侧内容区域
- 支持通过参数控制默认选中的模块

### Task 2: 提取各设置模块为独立 Composable
- 将 `ApplicationSettingsDialog` 的 `text` 内容提取为 `GeneralSettingsContent`
- 将 `SettingsDialog` (Layout Inspector) 内容提取为 `LayoutInspectorSettingsContent`
- 将 Simpleperf 设置面板提取为 `SimpleperfSettingsContent`
- 各模块保持自身数据流，通过 `MutableState` 或回调与容器通信

### Task 3: 统一入口
- 修改 `Main.kt` 中 `Cmd+,` handler 改为打开 `UnifiedSettingsDialog(defaultTab = GENERAL)`
- Layout Inspector 菜单 `OPEN_SETTINGS` action 改为打开统一设置并定位到 `LAYOUT_INSPECTOR`
- Simpleperf File → Settings 菜单改为打开统一设置并定位到 `SIMPLEPERF`
- Simpleperf Configuration 菜单保留独立入口（采集配置与通用设置性质不同）

### Task 4: 验证
- 确保三个模块的设置读写行为不变
- 验证 macOS `Cmd+,` 和 Windows 菜单入口均正常工作
- 回归测试三个模块的设置持久化

## 风险 & 注意事项

- Layout Inspector 和 Simpleperf 运行在**不同 JVM 进程/模块**中，统一设置窗口需要跨进程状态共享或共享 Preferences key。当前方案假设它们运行在同一进程，如果进程隔离需额外设计 IPC。
- Simpleperf 的 Configuration 菜单（采集模板/配置/高级参数）本质是采集流程的一部分，是否合并到通用设置中需进一步确认。
- 窗口大小需适配 Layout Inspector 的颜色设置控件（当前 520dp 宽度）。

## 修订设计（评审结论）

### 1. 架构边界

当前统一桌面应用会在同一 JVM/Compose Window 内组合 Layout Inspector、Simpleperf 和其他工具；不应以 IPC 作为默认方案。真正需要解决的是 Gradle 模块的可见性、状态所有权以及独立运行模式兼容性。

统一设置由 Shell 持有协调器，各功能模块只提供公开的设置状态与设置内容：

```kotlin
data class SettingsRequest(
    val page: SettingsPage,
    val requestId: Long,
)

enum class SettingsPage {
    GENERAL,
    LAYOUT_INSPECTOR,
    SIMPLEPERF,
}
```

每个模块提供独立的 `SettingsController` 或等价的公开状态/事件接口。统一窗口不直接访问模块内部 `MutableState`，也不依赖模块内部 Dialog。

独立运行 Layout Inspector 或 Simpleperf 时，保留本地设置窗口适配器；统一 Shell 模式则由 Shell 统一承载窗口。

### 2. 设置分类

统一窗口只收纳稳定、跨会话的应用偏好：

#### 通用

- 语言
- 主题

#### Layout Inspector

- 视图显示选项
- 存档快照限制
- Canvas 边框颜色

#### Simpleperf

- Flame Tooltip 模式
- 默认分析引擎
- 采集模板
- Event、采样率、时长
- Call graph、事件范围和高级采集参数
- User Guide

采集参数通过 `SimpleperfCaptureSettingsContext` 连接当前 CPU Profiler 会话。未打开 CPU Profiler 或尚未选择目标时，完整设置分区仍可浏览，目标相关控件保持禁用并显示说明；Flame Tooltip 和分析引擎可随时修改。独立运行 Simpleperf 时保留原本地设置对话框作为回退。

### 3. 持久化策略

设置按模块命名空间分别持久化，不合并成一个巨型 Store：

```text
application.theme
application.language

layout.showHierarchyControls
layout.archiveMultiplier
layout.canvasBorder.*

simpleperf.tooltipMode
simpleperf.engine
```

统一设置采用桌面应用常见的“修改即生效、修改即保存”语义：

- 不提供 Apply/Cancel
- 关闭只关闭窗口，不回滚修改
- 重置默认值使用单独的确认操作
- Preferences 写入失败必须在界面中显示非阻塞错误

### 4. 窗口与导航

不使用默认 `NavigationRail`，改用紧凑的 macOS 风格侧边栏：

- 侧栏宽度约 `150–170dp`
- 行高约 `28–32dp`
- 右侧使用 `VerticalDivider`
- 选中项复用 Layout Inspector 的 selected-row 颜色
- 页面标题 `13–15sp`
- 正文 `11–12sp`
- 默认窗口尺寸 `1100 × 760dp`，允许用户调整大小
- 高度不足时仅右侧内容滚动

### 5. 入口路由

`Cmd+,`、Layout Inspector 菜单和 Simpleperf 菜单都发送带目标页面的 `SettingsRequest`，不再只使用无页面语义的递增计数器。

```text
Cmd+,                         -> GENERAL
Layout Inspector / Settings  -> LAYOUT_INSPECTOR
Simpleperf / Settings        -> SIMPLEPERF
Simpleperf / Configuration   -> SIMPLEPERF，并定位到对应采集设置分区
```

如果统一窗口已经打开，新的请求应切换到目标页面并将窗口置前；没有目标页面时默认打开 General。

### 6. 实施门槛

开始 UI 实现前必须先完成：

1. `SettingsPage`、`SettingsRequest` 和 Shell 协调器。
2. Layout Inspector 与 Simpleperf 的公开设置 Controller/状态接口。
3. Simpleperf Tooltip/Engine 的 Preferences 持久化。
4. 保存失败的可见错误状态。
5. 统一模式与独立运行模式的回退路径。

### 7. 验证清单

- 三个入口都能打开统一窗口并定位正确页面。
- 页面切换不会丢失未关闭窗口中的当前状态。
- 主题和语言修改在所有已打开工具中一致生效。
- Layout Inspector、Simpleperf 设置重启后仍能恢复。
- Preferences 写入失败时用户能看到明确提示。
- 独立运行模块仍能打开本地设置界面。
- Simpleperf 工具栏设置及 Configuration 子菜单在统一模式下定位到正确的内部分区。
- Windows 菜单、macOS `Cmd+,` 和键盘焦点行为均通过回归测试。

## 相关文件索引

| 文件 | 说明 |
|---|---|
| `desktop-app/.../ApplicationSettingsDialog.kt` | 通用设置 AlertDialog |
| `desktop-app/.../ApplicationSettingsMenuInstaller.kt` | macOS Preferences handler |
| `desktop-app/.../ApplicationUiSettings.kt` | 通用设置数据模型 + Store |
| `desktop-app/.../Main.kt` | `Cmd+,` 路由 |
| `desktop-app/.../UnifiedDesktopApp.kt` | `showApplicationSettings` 状态 |
| `layout-inspector/.../ThemeSettingsDialog.kt` | Layout Inspector 设置弹窗 |
| `layout-inspector/.../NativeViewerMenuBar.kt` | 菜单中 `OPEN_SETTINGS` action |
| `layout-inspector/.../DesktopViewerApp.kt` | `settingsVisible` 状态 |
| `simpleperf-viewer/.../SimpleperfUiSettings.kt` | Simpleperf 设置数据模型 |
| `simpleperf-viewer/.../SimpleperfFileMenu.kt` | File → Settings / Configuration 菜单 |
| `simpleperf-viewer/.../SimpleperfWorkspace.kt` | `SimpleperfMenu` + `captureSettingsSection` |
| `simpleperf-viewer/.../CaptureConfigurationWorkspace.kt` | 采集配置内联面板 |

## 变更历史

- **2026-07-23**: 初始草案，状态设为未实施
- **2026-07-25**: 完成设计评审；补充状态所有权、设置分类、持久化、路由和实施门槛。后续实现以“修订设计”为准。
- **2026-07-25**: 完成统一设置实现及回归验证。
- **2026-07-25**: 按完整迁移要求扩大窗口至 1100×760dp；迁入 Layout Inspector 全部视图选项与 Simpleperf 全部采集设置，并接通当前采集会话。
