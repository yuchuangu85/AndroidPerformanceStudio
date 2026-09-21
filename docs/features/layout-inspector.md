# Layout Inspector

## 功能介绍

Layout Inspector 用于采集 Android View 层级、截图和布局属性，帮助定位层级复杂度、不可见 View、异常尺寸、重复背景和选中节点位置等问题。它支持两条采集路径：

- Debug Agent：高保真、可扩展、可持续采集；
- ADB/UI Automator fallback：不修改目标 App 时的通用回退能力。

## 详细设计方案

### 采集链路

```text
设备发现
  -> 选择前台应用/进程
  -> Agent socket 或 ADB fallback
  -> LayoutSnapshot + screenshot
  -> LayoutAnalyzer
  -> hierarchy / canvas / properties / findings
```

### 模块职责

- `layout-inspector/shared-kernel/protocol-model`：快照、节点、截图和协议模型。
- `layout-inspector/shared-kernel/android-agent-core`：设备端 socket、token、请求处理。
- `layout-inspector/shared-kernel/android-agent-view`：Activity/View 树与截图采集。
- `layout-inspector/shared-kernel/android-agent-frame`：帧相关补充数据。
- `layout-inspector/shared-kernel/analysis-engine`：复杂度、背景覆盖、不可见节点等规则。
- `layout-inspector/adb-gateway`：设备发现、ADB fallback 和连接恢复。
- `layout-inspector/application`：会话、采集调度、归档和状态。
- `layout-inspector/presentation`：层级树、画布、属性、findings 和归档 UI。

### 关键边界

- Agent 快照和 ADB fallback 必须在 UI 中明确区分。
- 截图和 bounds 使用同一坐标空间，不能把设备完整屏幕误标为 App 内容区域。
- Findings 必须包含规则 ID、严重级别、节点 ID、证据和置信度。
- Release 构建不注入 Agent，不增加调试权限或网络权限。

## 实现方案

主要入口：

```text
layout-inspector/application
layout-inspector/presentation
layout-inspector/adb-gateway
layout-inspector/shared-kernel/android-agent-view
layout-inspector/shared-kernel/analysis-engine
```

桌面端通过共享 Android Agent session 连接设备；没有 Agent 时使用 UI Automator 和截图回退。快照归档保留 manifest、原始 JSON、截图和可选 sidecar，便于离线复核。

## 使用方案

1. 启动桌面应用并打开 Layout Inspector。
2. 选择已授权设备和前台 App。
3. 优先使用 Debug Agent 采集；普通 App 自动走 fallback。
4. 在层级树中选择节点。
5. 在 Canvas 查看 bounds overlay，在 Properties 查看字段。
6. 在 Findings 中按规则和严重级别筛选。
7. 需要离线复核时导出 capture archive。

## 输出与限制

- 输出包括层级快照、截图、属性、findings 和归档。
- fallback 无法保证获得 Agent 的全部字段、事件和自定义采集能力。
- 当前实时路径面向一台授权设备；多设备选择仍是待办。
- Compose 深层语义、State Reads 和调用栈属于独立能力，不应从传统 View snapshot 的结果推断。

## Compose Stability / Recomposition

Layout Inspector 可导入 Compose Compiler `*-composables.txt`、`*-composables.csv` 与 `*-classes.txt` 报告，在 Compose 节点详情中展示 restartable、skippable 和不稳定参数。`ComposeStabilityAnalyzer` 将静态稳定性与 Inspector 的 recompose/skip count 合并，并只在提供显式 `ComposeJankObservation` 时报告 Jank 关联，避免从重组次数直接推断卡顿因果。
