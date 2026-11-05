# Tasks: fix-capture-nonfinite-floats

## 1. 解码层 sanitize

- [x] 1.1 在 `VisibleWindowHierarchyParser.kt` 的 `EncodedMap.toViewNode` 中为所有浮点属性读取引入 finite 检查工具函数（`finiteOr(default)`），覆盖 alpha/elevation/translation*/rotation*/scale*/pivot*/z 等读取点
- [x] 1.2 `alpha` 非 finite → 1f；其余浮点属性非 finite → 0f；确认 padding/margin/bounds 等 int 路径不受影响
- [x] 1.3 在 `VisibleWindowHierarchyParserTest` 新增用例：构造含 NaN `drawing:alpha` 与 `misc:transformation.alpha` 的 encoded 条目，断言解析成功且 alpha=1f；新增部分窗口坏值、部分正常值的混合用例

## 2. 编码层防御

- [x] 2.1 排查 `ProtocolCodec.encodeSnapshot` 对非 finite 浮点的行为，实现 `encode` 阶段失败包装：捕获 `JsonEncodingException` 并重抛带 `encode` 阶段标识与原因的异常（不改变正常路径输出）
- [x] 2.2 为 agent 路径（`ConnectedDeviceSession.capture` 中 `runCatching { decodeSnapshot }` 降级）补充注释与测试，确认 NaN 快照走 `agentFrame` 降级而非崩溃
- [x] 2.3 新增 `ProtocolCodec` 单测：输入含 NaN 节点 → 失败信息含 `encode` 阶段标识；正常快照输出与现状逐字节一致

## 3. 失败原因分阶段收集

- [x] 3.1 在 `AdbFallbackCapture` 内引入阶段失败摘要收集（visible-window-dump / visible-window-parse / uiautomator / screenshot / encode），将三处 `runCatching{}.getOrNull()` 与命令非零退出改为记录摘要后保持原降级语义
- [x] 3.2 `AdbFallbackUnavailableException` message 拼接阶段摘要（单阶段 ≤120 字符、总计 ≤500 字符），保留 cause
- [x] 3.3 更新引用旧 message 的测试（`LiveDeviceClientTest`、`AdbGatewayTest` 等），并新增断言：多阶段失败时 message 包含各阶段摘要

## 4. UI 根因摘要

- [x] 4.1 在 presentation 层错误 reason 构造处（`describeFullComposeFailure` 及 `connectionFailed` 调用点）追加最深 cause message 摘要（≤200 字符）的公共工具函数
- [x] 4.2 新增/更新 presentation 测试：包装异常展示包含根因摘要

## 5. 端到端验证

- [x] 5.1 全量运行 adb-gateway / shared-kernel / presentation 相关模块测试
- [x] 5.2 有设备时实测：ZUI 平板展开通知中心 → SYSTEM_UI 模式抓取成功；停用 adb（或选离线设备）→ 错误条显示分阶段原因

## 6. 节点 ID 显示契约

- [x] 6.1 确认详情面板完整显示多窗口节点 ID（含 `window:<hash>/` 前缀），不在展示层剥离，便于与原始 visible-window dump ZIP 条目名对照
- [x] 6.2 新增 `NodeDetailsPresenterDisplayIdTest` 防回归测试：多窗口命名空间前缀保留 + agent 路径 ID 原样
