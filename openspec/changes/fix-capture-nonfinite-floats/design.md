# Design: fix-capture-nonfinite-floats

## Context

ADB 兜底抓取链路（`AdbFallbackCapture`）对所有非 debuggable 应用、SYSTEM_UI
目标模式与归档导出共用同一条解码管线：

```
cmd window dump-visible-window-views (ZIP)
  └─ VisibleWindowHierarchyParser.parseWindows
       └─ EncodedHierarchyDecoder.decodeDocument → EncodedMap.toViewNode
            └─ ViewNode(alpha, elevation, translation… 均为 Float)
                 └─ ProtocolCodec.encodeSnapshot (kotlinx JSON, 禁止 NaN/Inf)
```

实测（ZUI 平板 TB710FU / TB322FC，Android 17）：NotificationShade 窗口在展开
期间 `drawing:alpha` 与 `misc:transformation.alpha` 持久为 NaN（23/23 帧），
端到端探针复现 `JsonEncodingException: Unexpected special floating-point value
NaN`，最终被包装为 `AdbFallbackUnavailableException`，cause 被三层
`runCatching`/`catch` 吞掉，UI 只显示笼统错误。

现有异常路径：
- `AdbFallbackCapture.capture()`：`catch (error: Throwable)` → 包装，cause 不进 message。
- `LayoutInspectorMainPage`：`error.message ?: error.javaClass.simpleName`
  展示，cause 链被丢弃。

## Goals / Non-Goals

**Goals:**
- 单个非 finite 浮点值不得导致整次抓取失败；按属性语义替换为安全默认值。
- 抓取失败时，用户可见的错误信息包含分阶段根因（哪一步、设备返回了什么）。
- 覆盖三条使用路径（兜底抓取 / SYSTEM_UI 模式 / 归档导出）与 agent 路径的编码防御。

**Non-Goals:**
- 不修复 SystemUI/ROM 侧产生 NaN 的源头（设备侧行为，超出本工具范围）。
- 不改变快照 schema 或协议版本。
- 不做 root/JDWP agent 注入（独立 change）。
- 不重构 `AdbFallbackCapture` 的重试策略（仅在其上追加失败信息收集）。

## Decisions

### D1: 在解码层做 sanitize，而不是在编码层放行 NaN

**选择**：`EncodedMap.toViewNode` 内对每个 float 属性读取后立即做
`takeIf { it.isFinite() }`，NaN/Inf → 语义化默认值。

**理由**：
- 解码层是三条路径的公共汇聚点，一处修复全覆盖；
- `ViewNode.alpha` 语义上 NaN 等价于"未知透明度"，回退 1f（不改变渲染判断）；
  elevation/translation/rotation 等 NaN → 0f 与 Android View 默认值一致；
- 编码层即使放行（`allowSpecialFloatingPointValues = true`），下游 UI 与
  归档 JSON 仍会携带 NaN，比较/绘制逻辑同样脆弱。

**替代方案**（否决）：仅设置 `allowSpecialFloatingPointValues`——把坏值扩散给
消费者；在 UI 层逐点防御——遗漏点多，且归档文件仍含非法 JSON 值。

### D2: 编码层仍加一道兜底 sanitize（双保险）

**选择**：`ProtocolCodec` 序列化前不做全局转换；改为在 `ViewNode` 构造入口
（`toViewNode`）保证 finite。对 agent 路径（进程内生成的快照 JSON 由 agent
侧编码），桌面端 `decodeSnapshot` 保持现状——`ignoreUnknownKeys` 已放宽，
decode 对 NaN 字符串会失败，此时沿用现有 `runCatching { decodeSnapshot }
.getOrNull() ?: return agentFrame` 的降级，不新增处理。

**理由**：agent 侧产物是独立构建的 jar（compose-agent-build），本轮不动它；
桌面端 decode 已有降级路径，风险可控。

### D3: 失败原因收集采用"阶段化摘要字符串"，不引入新异常类型

**选择**：`AdbFallbackCapture` 内部维护 `stageFailures: MutableList<String>`
（如 `"visible-window-dump: exit 1"`、`"visible-window-parse: Encoded hierarchy
has no property index"`、`"uiautomator: exit -1 (timeout)"`），失败时拼入
`AdbFallbackUnavailableException` 的 message（保留 cause 不变）。

**理由**：
- message 直达 UI（现有 `error.message` 展示链无需改动即变好）；
- 不新增异常类型/协议字段，归档与测试影响最小；
- `runCatching{}.getOrNull()` 的三处静默吞掉点改为把异常摘要记录进列表后
  继续原降级语义。

**替代方案**（否决）：结构化 `CaptureStageReport` 随异常传递——可诊断性更好，
但需要改 presentation 消费端与测试，超出本次最小修复范围；message 内固定前缀
（`stage:`）已足够检索。

### D4: UI 侧保留 cause 摘要

**选择**：`LayoutInspectorMainPage.describeFullComposeFailure` 与
`store.connectionFailed` 的 reason 构造处，对带 cause 的异常追加
`" (caused by: <rootCause.message>)"`（取最深 cause 的 message，截断 ~200 字符）。

**理由**：排查本次问题时 `JsonEncodingException` 只出现在探针 stack trace 里，
UI 完全不可见；一条摘要即可定位。

## Risks / Trade-offs

- [alpha 回退为 1f 可能让原本不可见（NaN alpha 视为透明）的节点显示为可见]
  → 可接受：NaN 本身语义未定义，且可见性还受 `misc:visibility` 与父链约束。
- [message 变长，UI 错误条可能换行] → 摘要截断（每阶段 ≤120 字符，总长 ≤500）。
- [ZUI 设备 dump 属性名可能与 AOSP 不同（如 `misc:transformation.alpha`）]
  → sanitize 按"读取到的 float 值"统一处理，不按属性名枚举，天然覆盖变体。
- [协议层测试断言旧 message 文本] → 更新受影响断言
  （`LiveDeviceClientTest` 等引用该 message 的测试）。

## Migration Plan

纯端内修复，无数据迁移。回滚 = revert 提交。验证方式：
1. 单元测试：构造含 NaN alpha 的 encoded dump → 解析成功且 alpha=1f；
   编码含 NaN 的快照 → 失败原因摘要包含 `encode` 阶段。
2. 设备验证（有条件时）：ZUI 平板展开通知中心 → SYSTEM_UI 模式抓取成功，
   错误条在故意断开 adb 时显示分阶段原因。

## Open Questions

（无——方案在探索阶段已与需求方对齐。）
