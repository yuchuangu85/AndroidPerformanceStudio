# Spec Delta: layout-inspector-capture-robustness

## ADDED Requirements

### Requirement: 解码层对非有限浮点值容错

ADB 可见窗口层级的解码器（visible-window dump → ViewNode）SHALL 对所有浮点
属性执行有限性检查：当属性值为 NaN 或 ±Infinity 时，MUST 以该属性的语义默认值
替代（参与计算的值取恒等默认：alpha → 1.0、translation → 0.0；可选展示属性
置为缺失），且 MUST NOT 使单条窗口条目的解析或整次抓取失败。

#### Scenario: SystemUI 窗口携带 NaN alpha

- **WHEN** 可见窗口 dump 中某窗口（如 NotificationShade）的 `drawing:alpha`
  或 `misc:transformation.alpha` 为 NaN
- **THEN** 该窗口解析成功，对应节点 alpha 等于 1.0，其余属性正常解码

#### Scenario: 混合好坏值的窗口集合

- **WHEN** dump 的多个窗口中仅部分包含非 finite 浮点值
- **THEN** 全部窗口（含坏值窗口）均出现在抓取结果的 windows 列表中

### Requirement: 快照编码不接受非有限浮点值

布局快照的 JSON 编码 SHALL 保证输出不含 NaN 或 ±Infinity 字面量；
若输入仍携带非 finite 浮点值，编码路径 MUST 产生包含阶段标识
（`encode`）的失败原因，MUST NOT 抛出不含原因的异常。

#### Scenario: 编码含 NaN 的快照

- **WHEN** 一份快照中某节点 alpha 为 NaN 且绕过了解码层（如 agent 路径）
- **THEN** 调用方收到带 `encode` 阶段标识原因的失败，而非裸 `JsonEncodingException`

### Requirement: 抓取失败原因分阶段透传

ADB 兜底抓取 SHALL 按阶段记录失败原因（visible-window-dump、
visible-window-parse、uiautomator、screenshot、encode），当全部阶段失败时，
抛出的异常 message MUST 包含各阶段根因摘要（单阶段 ≤120 字符，总计 ≤500 字符），
且 exception MUST 保留底层 cause。

#### Scenario: 设备命令失败时用户可见根因

- **WHEN** `cmd window dump-visible-window-views` 与 uiautomator dump 均失败
- **THEN** UI 显示的错误信息包含两个阶段的原因摘要，而非仅笼统的
  "Unable to capture foreground app … through ADB"

#### Scenario: 解析失败时用户可见根因

- **WHEN** 可见窗口 dump 命令成功但解析抛出异常
- **THEN** 错误信息包含 `visible-window-parse` 阶段与该异常的 message

### Requirement: UI 错误展示保留根因摘要

布局检查器展示连接/抓取失败时，SHALL 对携带 cause 的异常追加最深 cause 的
message 摘要（≤200 字符），MUST NOT 仅显示最外层包装异常的 message。

#### Scenario: 包装异常的根因在 UI 可见

- **WHEN** 抓取异常链为 `AdbFallbackUnavailableException → JsonEncodingException`
- **THEN** UI 错误信息中包含 `JsonEncodingException` 的 message 摘要

### Requirement: 节点 ID 完整显示以支持原始 dump 对照

详情面板 SHALL 完整显示 ADB 多窗口抓取的节点 ID（含
`window:<hash>/<window-relative-path>` 命名空间前缀），MUST NOT 在展示层剥离
前缀。其中 `<hash>` 与 `cmd window dump-visible-window-views` 输出 ZIP 条目名
（如 `9223a26 NotificationShade`）的前缀 token 一致，便于用户将选中节点与
原始 dump 数据对照。agent 路径的单窗口 ID（无前缀）SHALL 按原样显示。

#### Scenario: 多窗口节点 ID 完整展示

- **WHEN** 在 SYSTEM_UI 模式下选中 NotificationShade 窗口内的某节点
- **THEN** 详情面板 ID 显示为 `window:9223a26/root/7/1/3/0/0` 形式，
  其中 `9223a26` 可直接对应原始 visible-window dump ZIP 中的条目名

#### Scenario: agent 路径 ID 原样展示

- **WHEN** 选中 agent 路径（单窗口）快照中的节点
- **THEN** 详情面板 ID 显示为 `root/2/5` 形式，无 `window:` 前缀
