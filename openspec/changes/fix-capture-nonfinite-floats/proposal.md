# Proposal: fix-capture-nonfinite-floats

## Why

布局检查器对 SystemUI（如展开的通知中心）的抓取必现失败，用户只看到笼统的
"Unable to capture foreground app com.android.systemui through ADB"。经端到端定位：
ZUI 平板的 NotificationShade 窗口在展开/旋转过渡期间，`drawing:alpha` 与
`misc:transformation.alpha` 持久为 NaN（实测 23/23 帧），`EncodedHierarchyDecoder`
未做 finite 校验，NaN 进入 `ViewNode.alpha` 后 `ProtocolCodec.encodeSnapshot`
（kotlinx JSON 默认禁止非 finite 浮点）抛出 `JsonEncodingException`，
被 `AdbFallbackCapture` 的 catch-all 包装并吞掉真实原因。

该解码器是所有非 debuggable 应用 ADB 兜底抓取、SYSTEM_UI 模式、归档导出三条
路径的公共层——任何厂商 ROM 的任何窗口出现非 finite 值都会导致整次抓取失败。
同时整个排查过程中真实异常链在 UI 上完全不可见，这是本次最大的可诊断性缺陷。

## What Changes

- **解码层防御**：`EncodedHierarchyDecoder.toViewNode` 对所有浮点属性
  （alpha/elevation/translation*/rotation*/scale*/pivot* 等）做 finite 检查，
  非 finite 值替换为安全默认值（alpha→1f，其余→0f），单个坏值不再导致整次抓取失败。
- **编码层防御**：`ProtocolCodec` 对非 finite 浮点做 sanitize（或启用
  `allowSpecialFloatingPointValues` 前先归一化），覆盖 agent 路径返回的快照。
- **诊断透传**：`AdbFallbackCapture` 按阶段（visible-window dump / 解析 /
  uiautomator / 截图 / 编码）收集失败原因并附在异常上；
  `AdbFallbackUnavailableException.message` 包含根因摘要；
  UI 展示连接失败原因时不再丢弃 cause 链
  （`LayoutInspectorMainPage` 的 `error.message ?: simpleName` 路径）。
- **节点 ID 显示契约**：多窗口 ADB 抓取的节点 ID 含 `window:<hash>/` 前缀，
  详情面板完整显示该前缀（与原始 visible-window dump ZIP 条目名对照），不在
  展示层剥离。补防回归测试锁定该行为。

## Capabilities

### New Capabilities

- `layout-inspector-capture-robustness`: ADB 布局抓取对非 finite 浮点数据的
  容错与失败原因可诊断性（解码 sanitize、编码 sanitize、分阶段失败原因透传）。

### Modified Capabilities

（无——现有 specs/ 下没有布局检查器抓取相关 capability。）

## Impact

- `desktop-viewer/layout-inspector/adb-gateway/.../VisibleWindowHierarchyParser.kt`
  （`EncodedHierarchyDecoder.toViewNode` 及 float 属性读取处）
- `desktop-viewer/layout-inspector/shared-kernel/protocol-model/.../ProtocolCodec.kt`
- `desktop-viewer/layout-inspector/adb-gateway/.../AdbFallbackCapture.kt`
  （异常与分阶段失败收集）
- `desktop-viewer/layout-inspector/presentation/.../LayoutInspectorMainPage.kt`
  （错误信息展示，保留 cause 摘要）
- 相关单元测试：`VisibleWindowHierarchyParserTest`、`LiveDeviceClientTest`、
  presentation 层错误展示测试。
- 无协议破坏性变更：JSON 输出中不再出现 NaN/Inf（本来也不允许出现），
  快照 schema 不变。
