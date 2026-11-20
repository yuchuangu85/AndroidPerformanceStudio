# Frame MCP 接口

[总览](README.md) | [Trace Analyzer](trace.md)

## 当前状态

`aps-frame-mcp` 是独立的只读 stdio Server。普通报告模式注册 5 个接口；同时绑定
可信本地采集凭据和原始 Trace 后再注册 2 个接口。两种模式均不启动采集，不改变桌面
内置 AI 或 Memory MCP 的调用方式。

- 普通报告：[会话摘要](#frame_session_summary)、[帧列表](#frame_list)、
  [单帧详情](#frame_detail)、[卡顿簇](#frame_problem_clusters)、[上下文分组](#frame_attributions)。
- 采集凭据：[单帧探针重叠](#frame_trace_overlaps)、[前后会话对比](#frame_session_compare)。
  对比工具可被发现，但只有额外绑定基线才可调用。

若要复现桌面「分析此文件 / 选中帧」的 AI 证据，而不是分页浏览，请使用[统一编排 MCP](orchestrator.md) 的 `aps_analyze_file`。在同一绑定内可先用 `aps_follow_up` 的 `frame_rows` 查找错过截止时间的帧，再按返回的帧 ID 下钻。它和 [CLI](../cli/README.md) 使用相同的文件范围、度量和引用 ID；不会因此取得专用 Server 的采集凭据权限。

## 数据输入与启动方式

在仓库根目录执行：

```sh
cd rust-desktop
cargo build --locked -p aps-frame-mcp
./target/debug/aps-frame-mcp --input /absolute/path/to/selected-frame-analysis.json
```

### 普通报告模式

`--input` 绑定用户选定的一份常规文件：UTF-8 gfxinfo FrameStats 文本，
或 Frame Analyzer 导出的 `schemaVersion: 3` 帧报告。输入最多 64 MiB、100,000 帧。
JSON 另有结构预算；版本不支持、重复 JSON 键、重复 `frameId` 或帧行缺少有效身份或来源
时拒绝。gfxinfo 表头与帧数据行各限 16 KiB；格式错误行可以跳过并计入警告。两种格式
均须至少有一帧可用。Server 从帧行重新计算指标，不采用缓存的摘要、判定、卡顿簇或分组。

### 采集凭据模式

在桌面 Frame Analyzer 中完成目标进程的 FrameTimeline 采集，然后从该次完整结果
导出 JSON。桌面端复核分析时的 Trace SHA-256 与采集产物，在 APS 设置目录下写入
`frame-profiler/capture-artifacts/<artifact-id>.frame-mcp.json`。凭据记录精确导出字节的
摘要、原始 Trace 摘要、目标身份、帧数和探针查询状态。同 ID 的普通产物元数据可用于
定位 Trace；用报告 SHA-256 匹配凭据中的 `reportDigest`，不要按文件时间猜测。
旧报告、重新导入的报告、gfxinfo 或仅由查看器关联的 Trace 不会生成可信凭据；实际
Trace 中的进程名缺失或与所选目标不符时仍可导出报告，但不会写凭据。

每个采集端点都由 **v3 报告 + 本机 APS 生成且受信的凭据 + 原始 Trace** 组成：

```sh
./target/debug/aps-frame-mcp \
  --input /absolute/path/to/after.json \
  --capture-receipt /absolute/path/to/after.frame-mcp.json \
  --trace /absolute/path/to/after.pftrace \
  --baseline-input /absolute/path/to/before.json \
  --baseline-receipt /absolute/path/to/before.frame-mcp.json \
  --baseline-trace /absolute/path/to/before.pftrace
```

只查询重叠时省略三个 `--baseline-*` 参数。比较方向固定为 after − baseline。
凭据最多 16 KiB，原始 Trace 最多 64 MiB。选项不全、摘要不符或帧数/进程归属不符时
拒绝启动。MCP 不从文件内容或历史目录推导路径，不运行 Trace Processor 或 ADB；
它只校验 Trace 字节摘要，并读取报告中已计算的探针观测。凭据是操作员信任的本地
记录，不是签名，也不能以未知来源随附的 JSON 替代。

### 外部 Client 配置

以下示例是普通报告模式。采集模式在 `args` 中加入上面命令的对应启动参数。
配置入口由 Client 决定：

```json
{
  "mcpServers": {
    "aps-frame": {
      "command": "/absolute/path/to/aps-frame-mcp",
      "args": ["--input", "/absolute/path/to/selected-frame-analysis.json"]
    }
  }
}
```

每行一条 JSON-RPC 消息；stdout 每行一个响应，无 ID 的通知不返回响应。关闭 stdin
结束进程。先初始化，再发现工具。以下各工具的示例均在初始化后发送：

```jsonl
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"example","version":"1.0"}}}
{"jsonrpc":"2.0","method":"notifications/initialized"}
{"jsonrpc":"2.0","id":2,"method":"tools/list"}
```

## 公共参数与输出格式

### 筛选与分页

五个普通报告工具支持以下可选筛选。省略字段表示不加对应限制，不能传 `null`：

- `source`：精确匹配 `GFXINFO`、`PERFETTO`、`FRAME_METRICS` 或
  `JANK_STATS`。标签 `PERFETTO` 不会打开 Trace。
- `windowId`：精确匹配可公开的窗口标识，非空、最多 512 字节。路径形态及控制字符
  不可用；保留符合约束的 Android 组件名（如 `com.example/.MainActivity`）。
- `startNs` / `endNs`：按 `intendedVsyncNs` 筛选，非负 64 位整数纳秒。起点包含、
  终点不包含，二者同时给出时起点必须小于终点。设置任一时间边界后，缺少该
  时间戳的帧不匹配；不按 wall clock 筛选。
- `view`：默认 `all`。`problems` 包含 Deadline Miss 或任一报告的 Jank 信号；
  `deadline_misses`、`platform_jank`、`jank_stats` 分别匹配 Deadline Miss、
  明确为真的平台 Jank 和明确为真的 JankStats Jank。

支持分页的工具接受 `offset`（非负整数，默认 0）和 `limit`（1–100，默认 50）。
分页结果的 `page` 包含 `offset`、`limit`、`total`、`returned`、`hasMore`、`nextOffset`。
继续时保持输入和筛选不变，传回 `nextOffset`；`null` 表示没有下一页。比较工具的
分页只作用于标记结果，不接受普通报告筛选。

### 来源与结果外层

成功结果中 `result.structuredContent` 是工具载荷，`result.content[0].text` 是同一载荷的
JSON 文本，`result.isError` 为 `false`。下文输出字段均指 `structuredContent`。

- 七个工具都附带主报告的 `sourceDigest`（所解析字节的完整 SHA-256）、
  `sessionId`（`frame-<sourceDigest>`）、`analysisPolicyVersion`（当前
  `aps-frame-v2`）和 `inputWarningCount`（导入警告条数）。
- 普通报告工具还返回 `inputFormat`（`gfxinfo_framestats` 或 `frame_analysis_json_v3`）、
  实际生效的 `filter`、`provenanceComplete` / `provenanceWarnings`（报告本身不提供完整
  设备来源证明），以及 `coverage`。其中 `inputFrames` 是未筛选的总帧数，
  `matchedFrames` 是筛选命中数；`framesWithoutIntendedVsync` 和
  `framesWithoutDuration` 按命中集计数，不按当前页计数。
- 两个采集凭据工具还附带主采集的 `captureId`、`traceDigest`、`receiptDigest`。
  比较时 `baseline`、`after` 也分别记录各自的摘要和采集身份。
- 帧身份是 `sessionId + frameId`。所有帧 ID 以无前导零的 `u64` 十进制字符串
  传输；重复的 `frameTimelineVsyncId` 只是证据，不是查找键。纳秒整数可能超出
  JavaScript 安全整数范围，Client 应保留整数精度。缺失指标为 `null`，不能当成实测零。

## frame_session_summary

获取筛选范围的汇总。输入只有公共筛选，不接受分页：

```json
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"frame_session_summary","arguments":{"view":"all"}}}
```

**输出结果：**`summary` 包含 `totalFrames`；Deadline、平台 Jank、JankStats 分别统计其
`*ClassifiedFrames`、问题帧、`*UnknownFrames` 与比率。比率以各自已分类帧为分母
（0–1，分母为零时为 `null`）。`p50DurationNs`、`p95DurationNs`、`p99DurationNs` 与
`worstDurationNs` 基于可用时长。`observedCapabilities` 表示范围内观测到了哪些指标，
不承诺每个样本都具备相应能力。

## frame_list

分页读取帧。输入为公共筛选及 `offset` / `limit`：

```json
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"frame_list","arguments":{"view":"all","offset":0,"limit":10}}}
```

**输出结果：**`items` 和 `page`。按 `intendedVsyncNs` 排序，缺失时使用帧 ID 回退。
每行包含 `frameId`、`source`、`activityName`、`windowId`、`intendedVsyncNs`、
`durationNs`、`expectedDurationNs` 及 `expectedDurationSource`、`deadlineVerdict`、
`severity`、`missedVsyncCount`、`platformJank`、`jankStatsJank` 和 `largestReportedStage`。

## frame_detail

输入必须包含当前会话的 `sessionId` 和 `frameId`，另接受公共筛选。使用列表返回的
实际值替换占位符；帧缺失、跨会话或不满足筛选条件时返回 `-32001`：

```json
{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"frame_detail","arguments":{"sessionId":"frame-<sourceDigest>","frameId":"0"}}}
```

**输出结果：**`frame` 包含列表行字段，并增加实际 VSync、完成与呈现时间、刷新率、
FrameTimeline VSYNC token、Jank 分类及规则，以及 `stagesNs` 中的 `input`、`animation`、
`layoutMeasure`、`draw`、`sync`、`commandIssue`、`swapBuffers`、`gpu`。各阶段缺失时为
`null`。若 `jankStatsReasonsRedacted` 为真，表示省略了不适合公开的原因文本，不表示
没有报告信号。

## frame_problem_clusters

分页读取筛选范围内的 Deadline Miss 簇。除了公共筛选和簇分页，可用
`memberOffset`（非负整数，默认 0）独立翻页簇成员：

```json
{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"frame_problem_clusters","arguments":{"offset":0,"limit":1,"memberOffset":0}}}
```

**输出结果：**`items` 与外层 `page`。每项包含 `clusterId`、首尾帧 ID、`memberCount`、
`deadlineMissFrameIds`、`membersTruncated`、`memberPage`、`startNs`、`endNs`、
`durationNs`、`worstSeverity`、窗口和 Activity 以及 `dominantReportedStage`。
成员页最多 20 个 ID；继续读取单个簇时固定外层 `offset` 与 `limit: 1`，
使用该簇的 `memberPage.nextOffset` 作为 `memberOffset`。

同一簇不会跨来源、进程 ID、包名、窗口或 Activity；筛选仅裁剪成员，不合并独立的簇。
起止时间来自首帧 intended VSync 与末帧呈现时间（缺失则尝试完成时间）。
多成员簇缺少有效端点时，时长为 `null`，不能用末帧自身时长替代。主要报告阶段不是根因。

## frame_attributions

分页查询上下文分组。输入为公共筛选及 `offset` / `limit`：

```json
{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"frame_attributions","arguments":{"offset":0,"limit":10}}}
```

**输出结果：**`items` 与 `page`。每项按 Activity、窗口、UI 状态、Fragment、页面和
交互状态的组合分组，提供 `totalFrames`、`deadlineMissFrames`、`platformJankFrames`、
`jankStatsJankFrames`，平均/P95/最差时长及报告的 Jank 类型。渲染线程名、SurfaceFlinger
Jank 类型各最多展示 20 项，并附截断标记。unknown 标签是缺失时的占位，不是实测标签；
分组是统计结果，不推断因果关系。

## frame_trace_overlaps

仅采集凭据模式可调用。输入只接受主报告的 `sessionId` 与 `frameId`：

```json
{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"frame_trace_overlaps","arguments":{"sessionId":"frame-<afterReportDigest>","frameId":"1"}}}
```

**输出结果：**`frameId`、`items`、`coverage`、`evidenceKind`。每帧至多 8 条匹配，
每条包含 `marker`、`tid`、`threadRole`（`Main`、`RenderThread` 或 `Other`）、
`overlapNs` 和可空的 `observedScheduledNs`。覆盖信息包含 `returned`、`omitted`、
`complete`。已成功查询且帧有实际窗口时，空数组表示没有匹配；探针查询失败或未执行、
帧没有实际窗口时返回 `-32001`。重叠不等于整个方法耗时，也不证明卡顿根因。

## frame_session_compare

比较一次基线与一次 after 的完整运行。输入只接受标记分页 `offset` / `limit`，
不支持重选来源或筛选掉不利帧：

```json
{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"frame_session_compare","arguments":{"offset":0,"limit":16}}}
```

**输出结果：**`direction: "after_minus_baseline"`、`baseline`、`after`、`delta`、
`items`、`page` 与 `evidenceKind`。

- 两端摘要输出各自的 `captureId`、`reportDigest`、`traceDigest`、`receiptDigest`、
  总帧数及实测帧数、`p95DurationNs`、Deadline Miss 计数和分类分母及比率，
  并记录可计算探针重叠的实际帧数、查询及覆盖状态。
- `delta.p95DurationNs` 单位是纳秒；`delta.deadlineMissPercentagePoints` 单位是
  百分点。两项均为 after − baseline；不是多轮中位数，也不证明某项代码改动引起差异。
- `items` 分页两端已观测探针标记的并集。每项的 `baseline` / `after` 包含
  `observedFrames`、`observedSpans`、`p95OverlapNs`；没观察到同一标记或覆盖不足时，
  `deltaP95OverlapNs` 为 `null`，不补成零改善。

**比较约束：**两端必须是分别绑定的不同采集、报告与 Trace，设备、API、包名和进程名相同，
刷新率和帧预算可比（独立运行的 PID 可以不同）。每端至少两帧有正的实测时长，且至少
一帧可判定 Deadline。缺基线、不匹配或指标覆盖不足时返回 `-32001`。

## 参数约束与错误结果

工具不接受任意路径、SQL、设备或源码操作。每条请求最多 64 KiB，成功工具载荷最多
512 KiB；分页工具可通过降低 `limit` 缩小结果。

- `-32602`：无效工具或参数，包括未知字段、无效帧 ID、越界分页和路径形态窗口标识。
- `-32001`：来源不可用/已改变、会话不匹配、未找到帧、缺少采集证据或比较条件不满足。
- `-32002`：结果超过载荷预算。

一次进程绑定固定来源。查询前及成功返回前复核使用的输入，缓存页也不例外；
比较复核两端全部六份文件。检测到变化或无法读取后，数据查询在该进程中失效，
即使恢复原字节也须重启。例如未配置基线时：

```json
{"jsonrpc":"2.0","id":9,"error":{"code":-32001,"message":"Comparison requires a pinned baseline capture"}}
```

## 证据边界

- 输出不带完整状态映射、原始列或宿主所选路径。路径形态、控制字符和双向格式字符的
  标签会省略；这不会合并原本独立的卡顿簇。正常业务标签仍可能包含敏感信息，
  输出白名单不是通用脱敏。
- Deadline Miss、平台 Jank、JankStats 是独立信号。未知时长、阶段、预算或调度时间
  不替换为零或默认 60 Hz。凭据不会证明安装的应用版本、外部时钟关联或源码根因。
- 桌面 FrameStats 对实际解析的有界字节计算摘要，并在发布前复核。历史读取检查版本、
  会话归属、帧数、重复 ID 和新记录的行摘要；旧记录只供本地查看。手动关联的 Trace 仅供
  查看，不覆盖原分析绑定，也不会按帧时间跳转。历史记录和查看器关联都不是 MCP 数据源。
- Server 不提供设备录制、gfxinfo reset、任意 Trace 查询、源码读写、落盘或 Provider
  执行能力；不表示安装包已经包含 Server。

## 验证入口

在 `rust-desktop/` 下执行：

```sh
cargo test --locked -p aps-frame-mcp
cargo test --locked -p aps-app --bin aps-app frame::mcp_receipt::
cargo test --locked -p aps-app --bin aps-app frame::sessions::
```

MCP 用例覆盖实际 stdio；桌面凭据用例在本地安装了仓库固定版本 Trace Processor 时
用合成 Trace 验证导出及读取。它们不覆盖设备采集、第三方 Client 配置或发布安装包。

## 代码依据

- [stdio 入口](../../../rust-desktop/crates/aps-frame-mcp/src/main.rs)
- [工具注册与报告投影](../../../rust-desktop/crates/aps-frame-mcp/src/query.rs)
- [来源绑定与响应](../../../rust-desktop/crates/aps-frame-mcp/src/lib.rs)
- [采集凭据校验](../../../rust-desktop/crates/aps-frame-mcp/src/capture.rs)、
  [重叠与比较投影](../../../rust-desktop/crates/aps-frame-mcp/src/capture_query.rs)
- [桌面凭据生产](../../../rust-desktop/crates/aps-app/src/frame/mcp_receipt.rs)、
  [桌面历史校验](../../../rust-desktop/crates/aps-app/src/frame/sessions.rs)
