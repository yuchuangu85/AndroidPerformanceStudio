# CPU Profiler MCP

[总览](README.md) | [Memory](memory.md)

## 当前状态

CPU 已实现 **8 个只读工具**（P0 三个，P1 两个，P2 三个），由独立的 `aps-cpu-mcp` stdio server 提供：

- `cpu_session_summary`：样本、丢失样本、线程、事件、时间范围和证据质量摘要。
- `cpu_top_functions`：按 inclusive weight 全局排序后分页的热点函数。
- `cpu_call_tree`：按前序顺序分页的调用树，支持正向或反向投影。
- `cpu_timeline_summary`：按时间桶、线程汇总实际观测到的样本，不返回原始调用栈。
- `cpu_compare_top_functions`：按当前 B 减基线 A，比较完整热点集合的可对齐部分。
- `cpu_flame_blocks`：按权重范围投影火焰图块，窄块可聚合。
- `cpu_stack_blocks`：指定时间范围，读取单个线程的采样栈块。
- `cpu_export_report`：内联返回一份有界的 JSON 或 CSV 报告，不写文件。

既有 `aps-mcp` 的 Memory 启动契约不变。这不是统一动态 provider 注册，也不改变
内置 CPU AI 的证据准备流程。采集、写文件、源码读取和模型执行不在本批范围。

## 构建与连接

从仓库根目录进入 Rust 工作区：

```sh
cd rust-desktop
cargo build --locked -p aps-cpu --bin aps-cpu-mcp
./target/debug/aps-cpu-mcp --cpu /absolute/path/to/profile.simpleperf.pb
```

原单文件启动命令不变。需要对比时，在同一命令中显式绑定两份输入：

```sh
./target/debug/aps-cpu-mcp --cpu /absolute/path/to/after.pb --baseline-cpu /absolute/path/to/before.pb
```

两个参数的顺序不影响方向。单文件和双文件模式均发现同样的 8 个工具；未配置基线时，
只有对比调用返回 `-32001`。其余 7 个工具始终读取当前 B。两个输入均支持
SIMPLEPERF protobuf 或 Gecko / Firefox Profiler v24 JSON。
原始 `perf.data`、gzip 和会话包不是此入口的直接输入，须先通过桌面导入流程转换或
解压成支持的 profile；server 不启动 converter。Windows 产物后缀为 `.exe`。

外部 Client 的配置示意（路径替换为实际构建产物与所选 profile）：

```json
{
  "mcpServers": {
    "aps-cpu": {
      "command": "/absolute/path/to/aps-cpu-mcp",
      "args": ["--cpu", "/absolute/path/to/profile.json"]
    }
  }
}
```

stdin/stdout 每行一条 JSON-RPC 2.0 消息，关闭 stdin 正常结束。客户端先执行
`initialize`、`notifications/initialized`，再 `tools/list` / `tools/call`。
当前返回协议版本 `2024-11-05`；没有 `id` 的通知不产生响应，支持 `ping`。
stdout 只承载协议，启动失败写 stderr 并以非零状态退出。

## 输入

`cpu_session_summary` 的 `arguments` 为 `{}`，不接受过滤参数。

`cpu_top_functions` 和 `cpu_call_tree` 共用以下可选参数；未提供才使用默认值，显式 `null`、错误类型、
未知字段和未知事件均拒绝，不会静默改成默认查询：

- `event`：摘要 `eventTypes` 中的一个非空事件名，最多 2,048 UTF-8 字节。
- `startNs` / `endNs`：非负 `u64` 整数，半开区间 `[startNs, endNs)`；同时提供时须 `startNs < endNs`。
- `direction`：`forward`（默认）或 `inverted`。
- `weightType`：`samples`（默认）或 `events`；`events` 必须显式选择一个事件，不能混合不同事件单位。
- `offset`：从 0 开始的非负整数，默认 0，受宿主平台大小和领域查询边界约束。
- `limit`：正整数；热点默认 50、最大 100，调用树默认 200、最大 2,000。

分页示例：

```json
{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"cpu_session_summary","arguments":{}}}
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"cpu_top_functions","arguments":{"event":"cpu-cycles","weightType":"events","startNs":1000,"endNs":5000000,"offset":0,"limit":50}}}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"cpu_call_tree","arguments":{"direction":"inverted","offset":200,"limit":200}}}
```

工具参数不能传入本地路径、转换器、命令或 SQL；仅 `cpu_stack_blocks` 接受当前来源内的线程 ID。进程的当前文件和可选基线在启动时固定，切换文件须重新启动。来源摘要是关联身份，不是额外访问授权。

## 输出

成功结果为 `result.isError=false`，包含相同 payload 的 `structuredContent` 和
`content[0].text`（JSON 文本）。所有 payload 均包含 `policyVersion`。单文件工具带完整 SHA-256 `sourceDigest`；对比结果分别带
`beforeSourceDigest`（A）和 `afterSourceDigest`（B）。除会话摘要外均返回实际 `query`。

摘要包含：

- `inputFormat`：`simpleperf_protobuf` 或 `gecko_v24`；`formatVersion` 是归一化领域流的版本，不是 Gecko JSON 版本。
- `sampleCount`、`recordCount`、`threadCount`、`fileCount`、`eventTypes`、首尾时间戳和 `traceOffcpu`。
- `reportedSampleCount`、`lostSampleCount` 与 `lostRate`。分母不可用时 `lostRate=null`、`lossRateAvailable=false`；计数 0 不能证明没有丢失。
- `totalEventCount` 是领域流原始 event-count 合计；多事件时不代表可比较的同单位指标，不能解释成 CPU 时间。
- `evidenceQuality` 明确样本是统计证据、没有精确方法时长，且未验证设备采集完整度。

热点行包含来源内的函数 `id`、`name`、`resource`、`inclusiveWeight`、`selfWeight`、`sampleCount`、
`selfSampleCount` 和 `threadCount`。调用树行增加会话投影内的 `id`、`parentId` 和
`depth`，不含 `threadCount`。ID 不能作为跨 profile 身份，父行可能位于前一页。

热点和调用树的查询结果包含实际 `query`、`total`、`offset`、`limit`、`hasMore` 和 `rows`。
下一页 offset 为当前 offset 加实际返回行数；超出末尾返回空数组且 `hasMore=false`。
调用树会组合领域层的有界分页，支持声明的 2,000 行上限；输出字节预算仍独立生效。

`resource` 只保留 Unix/Windows 文件名标签，`resourcePathsRedacted=true`。
同名文件可能产生相同标签，应以来源内的 `id` 区分，不能据标签合并身份。符号名、事件名仍可能包含敏感内容，
有界结构化输出不等于脱敏。

## P1：时间线摘要

```json
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"cpu_timeline_summary","arguments":{"event":"cpu-cycles","startNs":1000,"endNs":5000,"bucketCount":8,"threadLimit":5}}}
```

可选参数仅为 `event`、`startNs`、`endNs`、`bucketCount`（1–128，默认 20）和
`threadLimit`（1–100，默认 10）。每条符合事件及半开区间的样本计一次，包括同一
时间戳和空栈样本；没有用绘图抽稀后的点，也没有按 event count 加权。

- `buckets` 按整数纳秒切分，列出起点 `startNs`、**包含**的终点 `lastNsInclusive`
  及 `sampleCount`。桶数不超过区间内的纳秒数，不会产生零宽桶。
- 未提供的边界取匹配样本的首尾时间。无匹配时首尾时间为 null；若给齐两端，
  仍返回样本数为 0 的桶，否则返回空数组。
- `threads` 只列本次有样本的线程，按样本数降序，再按来源内线程 ID 升序。
  `peakBucketIndex` 是共享时间桶的零基下标，`peakBucketSampleCount` 是该线程在此桶的计数；
  并列峰值取最早桶。未展示线程计入 `omittedThreadCount`，不从总样本或桶计数中扣除。
- `sampleCount` 与 `emptyStackSampleCount` 是观测数。空桶不能解释为线程空闲；
  丢失样本不能分配给具体桶或线程，不能从样本密度直接推出 CPU 利用率或方法耗时。

## P1：热点对比

```json
{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"cpu_compare_top_functions","arguments":{"event":"cpu-cycles","weightType":"events","limit":20,"offset":0}}}
```

可选参数仅为 `event`、`weightType`、`offset` 和 `limit`（默认 50，最大 100）。
不跨采集复用时间戳、线程 ID 或函数 ID 做对齐。`events` 权重要求两端存在同一个显式命名事件；
来源内回退名 `event#N` 不能用于对比。未指定事件的 `samples` 比较两端各自的全部观测样本，
事件构成未必相同。已知 trace-offcpu 模式不一致时拒绝比较。

- 对两份完整热点集合取并集，以**原始资源标签 + 符号名**精确匹配，再按包含权重差值
  绝对值全局降序、原始资源标签和符号名升序排序，最后分页。不是分别取前 N 行后相减。
  每端最多处理 200,000 个函数；超限返回 `-32002`，不返回部分比较。
- 每行带 `matchStatus`（`matched`、`before_only`、`after_only`）和两端各自的函数 ID；
  `before*`、`after*`、`delta*` 分别提供包含权重、自身权重与样本数，差值始终 **B − A**。
  仅单侧观测到时，另一侧 ID 为 null、观测权重为 0；这不表示代码新增或被删除。
- 同一匹配键在任一端对应多个函数时，两端该键均不进入差值；不可识别标签也不参与匹配。
  `coverage.before/after` 报告总数、纳入数、歧义数和不可识别数。`total` 是可分页的行数，
  不是两端所有函数数之和。
- 资源名在输出时去除目录，路径形式的符号名显示为 `<path-like symbol>`。标签匹配并非构建身份；
  原始差值未按采集时长、频率或丢样归一化，不能据此认定优化或回归。

## P2：图表块

```json
{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"cpu_flame_blocks","arguments":{"event":"cpu-cycles","weightType":"events","startFraction":0,"endFraction":0.25,"maxBlocks":50}}}
{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"cpu_stack_blocks","arguments":{"startNs":1000,"endNs":5000000,"depthCount":16,"maxBlocks":50}}}
```

两个工具都支持 `event`、`weightType`、`direction`、半开时间筛选 `startNs/endNs`、
`depthCount`（1–64，默认 32）及 `maxBlocks`（1–512，默认 128）。权重为
`events` 时须选择单一事件。结果的 `truncated` 和 `omittedBlocks` 描述当前
深度及横向窗口内因预算未输出的块，不代表整个 profile 的覆盖率。

- **火焰图**：时间筛选可选。横向窗口由 `0 ≤ startFraction < endFraction ≤ 1` 指定，
  默认查看全部权重。`resolutionBins`（1–4096，默认 1024）控制小块聚合。
  `layoutStartFraction/layoutEndFraction` 是裁剪后的近似布局位置，不是时间。
  权重字段是来源节点的采样统计，不按可见宽度折算。聚合块返回计数，但不赋予
  确定的函数名或调用节点 ID。
- **栈图**：必须提供 `startNs/endNs`，可从时间线摘要选择同一来源的 `threadId`；
  不指定时按过滤范围内样本最多、并列时 ID 最小的线程选择。只投影该线程非空栈
  的正宽布局块；相邻采样可能合并。`layoutStartNs/layoutEndNsExclusive` 表示
  采样推导的显示区间，末端可能按采样间隔估算。它们不能相减当作方法执行时长，
  `selfWeight` 不可确认时为 null。空结果不证明线程空闲。

## P2：内联报告

```json
{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"cpu_export_report","arguments":{"format":"json","event":"cpu-cycles","limit":20}}}
```

报告始终读取当前 profile B，不导出基线、原始样本或源码。`format` 为 `json`
（默认）或 `csv`，其余可选参数为 `event`、`weightType`、`direction`、
`startNs/endNs` 和 `limit`（1–100，默认 50）。摘要描述整个 profile；过滤器作用于
热点和调用树两节，各节从第一行取至多 `limit` 行。`coverage` 分别给出
`total/returned/omitted`，不能将一份节选报告当作完整调用树。

工具 payload 中的 `content` 是报告正文（不是外层 MCP `result.content`）。
正文同时包含来源摘要和实际筛选条件；`sourceDigest` 标识 profile，
`artifactSha256` 标识正文的**精确 UTF-8 字节**。客户端可按 `encoding`、
`mimeType` 和 `byteLength` 核验，勿在哈希前重新排版 JSON、修改 CSV 换行或添加 BOM。
正文上限为 128 KiB；超限返回 `-32002`，不会生成不完整文件。服务端没有
`outputPath` 或报告目录参数；是否另存由客户端决定。

## 来源、预算与错误

server 从同一份实际解析字节计算 digest，打开后复核所选文件；每次有效工具调用在
查询前后复核来源，包括缓存页。比较前后对 A、B 分别复核。检测到文件变化、删除或不可读后，所有依赖该输入的工具在本进程内永久拒绝读取，
即使后来恢复原字节也须重新启动；只有基线失效时，不影响 B 的单文件工具。

- 每份输入的编码字节上限为 128 MiB，Gecko 另受领域 JSON 结构预算限制；这不是峰值 RAM 上限。
- 单条请求最多 64 KiB（包括换行），并限制 JSON 深度、节点、字符串和集合大小，拒绝重复键。
- 单个工具 payload 最多 512 KiB；文本和结构化双重承载后的整条响应可更大。超预算返回错误，调用方应缩小 `limit`；单个过长符号仍可能使一行超预算。
- 坏 JSON、超长请求会返回错误并继续处理后续行；不会无界累计输入。

错误码：`-32700` 为畸形、歧义或超结构预算 JSON，`-32600` 为无效请求/超长行，
`-32601` 为未知方法，`-32602` 为参数或工具名错误，`-32001` 为来源失效/分析失败，
`-32002` 为输出或比较规模超预算。错误消息不附带所选输入路径。

这里没有任意 Shell、ADB、网络监听、设备写操作或写文件工具。server 本身不提供
总执行超时或中途取消；外部 Client 应设置超时并终止自己启动的进程。采样权重不是
方法调用次数或精确方法时长，缺失指标不能补为零。

## 验证

```sh
cargo test --locked --offline -p aps-cpu
cargo clippy --locked --offline -p aps-cpu --bin aps-cpu-mcp
```

`tests/mcp_stdio.rs` 启动真实子进程，用合成 SIMPLEPERF/Gecko 数据验证八个工具、
分页过滤、来源变更、错误恢复和预算。它不是第三方 Client 兼容、真机、原生 GUI、
安装包或发布验收。

## 代码依据

- [CPU MCP server](../../../rust-desktop/crates/aps-cpu/src/cpu_mcp.rs)
- [图表块投影](../../../rust-desktop/crates/aps-cpu/src/cpu_mcp/blocks.rs) · [内联报告](../../../rust-desktop/crates/aps-cpu/src/cpu_mcp/report.rs)
- [真实 stdio 回归](../../../rust-desktop/crates/aps-cpu/tests/mcp_stdio.rs)
- [CPU 会话与查询 API](../../../rust-desktop/crates/aps-cpu/src/lib.rs)
- [CPU 模块说明](../../../rust-desktop/crates/aps-cpu/README.md)
