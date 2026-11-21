# Memory MCP 接口

[返回 MCP 总览](README.md)

本页记录当前已注册的 5 个 Memory 工具，依据为
[Server](../../../rust-desktop/crates/aps-memory/src/mcp/main.rs)、
[worker](../../../rust-desktop/crates/aps-memory/src/worker.rs) 和
[共享类级比较](../../../rust-desktop/crates/aps-memory/src/comparison.rs)；
[AI Host](../../../rust-desktop/crates/aps-ai/src/mcp/mod.rs) 仍只预取原有 3 个工具。

## 数据输入与启动方式

数据源是用户明确选定的离线 Java HPROF：目标 B 必填，基线 A 可选，当前 worker
每份输入上限为 8 GiB。原有四个工具只读取 B；`memory_snapshot_diff` 比较 A/B。
这些工具不接受 Native Heap、ART sampled、Bitmap dump 或 Perfetto trace，
也不支持 mapping CLI 或工具参数。

从仓库根目录构建，随后命令在 `rust-desktop/` 中执行：

```sh
cd rust-desktop
cargo build --locked -p aps-memory --bin aps-memory-worker --bin aps-mcp
```

原有单快照启动方式保持兼容，已知 flag 的排列顺序可变：

```sh
./target/debug/aps-mcp \
  --hprof /absolute/path/to/selected.hprof \
  --worker /absolute/path/to/aps-memory-worker
```

- `--hprof`：目标 B 的 HPROF 文件路径，必填。
- `--worker`：构建出的 `aps-memory-worker` 可执行文件路径。
- `--baseline-hprof`：可选的基线 A 的 HPROF 文件路径。
- 所有路径必须指向文件，示例使用绝对路径；Windows 使用对应 `.exe`。
- 启动时计算 SHA-256，worker 校验来源，禁用缓存的解析路径也复验实际文件句柄。
- 一个 Server 固定绑定 B 和可选的 A，换任一文件需重新启动，不复用对象 ID。
- `tools/list` 始终包含全部 5 个工具；缺少 A 只使 diff 调用返回 `-32001`。
- 工具参数不能指定其他文件路径，也不能改变 A/B 方向。

以下是在同一会话内初始化、发现并调用原有四个工具的完整示例，数据源均为 B。
先把 `HPROF` 改为选定文件；`4660` 只是对象 ID 示例，不承诺该对象在文件中存在。

```sh
export HPROF=/absolute/path/to/selected.hprof
printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"aps-doc-example","version":"0.1.0"}}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
  '{"jsonrpc":"2.0","id":"memory_snapshot_summary","method":"tools/call","params":{"name":"memory_snapshot_summary","arguments":{}}}' \
  '{"jsonrpc":"2.0","id":"memory_leak_findings","method":"tools/call","params":{"name":"memory_leak_findings","arguments":{}}}' \
  '{"jsonrpc":"2.0","id":"memory_class_histogram","method":"tools/call","params":{"name":"memory_class_histogram","arguments":{"limit":50}}}' \
  '{"jsonrpc":"2.0","id":"memory_object_detail","method":"tools/call","params":{"name":"memory_object_detail","arguments":{"objectId":"4660","offset":0,"limit":50}}}' \
  | ./target/debug/aps-mcp --hprof "$HPROF" --worker ./target/debug/aps-memory-worker
```

通知无响应，其余请求以相同 `id` 返回。外部 Client 的配置和 stdio 约定见
[总览](README.md#构建与连接)。

双快照启动及调用示例，`BEFORE_HPROF` 为 A，`AFTER_HPROF` 为 B；路径仅在启动时
由宿主指定，不进入工具参数。以下只是调用示例，不是设备数据或已验证输出：

```sh
export BEFORE_HPROF=/absolute/path/to/before-A.hprof
export AFTER_HPROF=/absolute/path/to/after-B.hprof
printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"aps-doc-example","version":"0.1.0"}}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
  '{"jsonrpc":"2.0","id":"diff-page-1","method":"tools/call","params":{"name":"memory_snapshot_diff","arguments":{"offset":0,"limit":50}}}' \
  | ./target/debug/aps-mcp \
      --baseline-hprof "$BEFORE_HPROF" \
      --worker ./target/debug/aps-memory-worker \
      --hprof "$AFTER_HPROF"
```

## 公共输出格式

成功响应外层包含 `content`、`structuredContent` 和 `isError=false`。下面是对象不存在
时的实际空结果格式，不是 JSON-RPC 错误：

```json
{
  "jsonrpc": "2.0",
  "id": "memory_object_detail",
  "result": {
    "content": [{"type": "text", "text": "null"}],
    "structuredContent": null,
    "isError": false
  }
}
```

有对象 payload 时，`content[0].text` 是该对象的 JSON 文本，与 `structuredContent`
表达同一结果。后面的输出示例仅展示 `structuredContent`。

除 diff 外，以下四个工具的“当前快照”和 `sourceDigest` 均指 B；配置基线不会改变
它们的来源或原有输出格式。

- 大小单位为 bytes，计数为整数；未知或不适用的可空字段保留 `null`。
- 对象 ID 来自 64 位标识，部分输出采用有符号 64 位投影。消费端须保留完整位模式，
  查询时转换为无符号十进制字符串，不能使用会丢失大整数精度的浮点数中转。
- worker 的嵌套列表和长字符串有展示上限；`presentationOmissions` 记录省略路径及
  原始/可见数量或字节数。空数组表示没有展示省略，不证明查询覆盖了整个应用。
- 字段或引用列表发生省略时，不能把可见项描述为完整对象图。

## memory_snapshot_summary

### 使用方式

获取当前 HPROF 的来源、格式、解析计数和全快照对象/浅层内存摘要。

```json
{
  "jsonrpc": "2.0",
  "id": "memory_snapshot_summary",
  "method": "tools/call",
  "params": {"name": "memory_snapshot_summary", "arguments": {}}
}
```

### 输入数据

`arguments` 为 `{}`，没有工具级参数。数据源由启动参数绑定，不传路径、heap 过滤、
mapping 或对象 ID。

### 输出结果

- `sourceDigest: string`：当前文件的完整 SHA-256，以 64 个十六进制字符表示。
- `metadata: object`：包含 `format`、`idSize`、`timestampMillis`、`classCount`、
  `instanceCount`、`objectArrayCount`、`primitiveArrayCount`、`rootCount`、`heaps`、
  `warnings`、`cacheStatus` 和 `presentationOmissions`。
- `warnings[]` 中的项包含 `message` 和可空的字节 `offset`。
- `summary: object`：包含 `objectCount`、`shallowSize`、`classCount`、`heapSegments`。
  每个 heap segment 包含 `name`、`sizeBytes`、`objectCount`。
- `evidenceBoundary: string`：离线 HPROF 证据边界说明。

`idSize` 为整数 4 或 8，`timestampMillis` 和各项计数为整数；`heaps` 为字符串数组。
`summary.shallowSize`、`heapSegments[].sizeBytes` 为整数字节数。当前只读 worker 不启用
索引缓存，因此这里的 `cacheStatus` 为 `"disabled"`。

`metadata.classCount` 是解析类数量，`summary.classCount` 是有对象的直方图类分组数，
两者不要求相等。该工具不提供实时 RSS、GC 时间线或完整分配/释放记录，也不在这个
摘要中承诺一个总 `retainedSize` 字段。

以下示例来自合成空 HPROF：4 字节 ID、时间戳 1、无记录，不是用户设备数据。

```json
{
  "sourceDigest": "16475bc162f76518ea73421767f84c8e0d5b421746ab836ee3b086d9a19fb00a",
  "metadata": {
    "format": "JAVA PROFILE 1.0.3",
    "idSize": 4,
    "timestampMillis": 1,
    "classCount": 0,
    "instanceCount": 0,
    "objectArrayCount": 0,
    "primitiveArrayCount": 0,
    "rootCount": 0,
    "heaps": [],
    "warnings": [],
    "cacheStatus": "disabled",
    "presentationOmissions": []
  },
  "summary": {"classCount": 0, "heapSegments": [], "objectCount": 0, "shallowSize": 0},
  "evidenceBoundary": "offline HPROF; unavailable metrics remain unknown"
}
```

## memory_leak_findings

### 使用方式

读取当前快照已生成的启发式泄漏和生命周期候选，不启动新的应用采集。

```json
{
  "jsonrpc": "2.0",
  "id": "memory_leak_findings",
  "method": "tools/call",
  "params": {"name": "memory_leak_findings", "arguments": {}}
}
```

### 输入数据

`arguments` 为 `{}`。每种候选固定查询 `offset=0`、`limit=32`，不暴露 `kind`、
分页、按类过滤或 Bitmap findings 参数。

### 输出结果

- `sourceDigest: string`：完整 HPROF SHA-256。
- `heuristicOnly: boolean`：当前为 `true`。
- `findings.leakSuspects`、`findings.activityLeaks`、`findings.lifecycleLeakObjects`：
  三个候选页，分别含 `kind`、`total`、`offset`、`rows` 和 `presentationOmissions`。
- `evidenceBoundary: string`：候选不等于确认泄漏，被省略记录保持未知。

三类 `rows[]` 的字段：

- `leakSuspects`：`className`、`reason`、`retainedSize`、`instanceCount`、
  `referenceChain`、`confidence`、`requiresManualVerification`、`activityOrFragmentLeak`。
  `confidence` 是内部整数值，不是泄漏概率；`retainedSize` 描述代表性对象。
- `activityLeaks`：`className`、`liveInstanceCount`、`destroyedInstanceCount`、
  `retainedSize`、`referenceChain`。Destroyed 属于 live 的子集，不能再次相加。
- `lifecycleLeakObjects`：`objectId`、`className`、`heapName`。

名称和原因是字符串，数量和 retained 是整数；引用链是数组，验证/生命周期标记是
布尔值。`lifecycleLeakObjects[].heapName` 可为 `null`，对象 ID 遵守公共 64 位规则。

原始引用链项使用 `fieldName`、`targetObjectId`、`targetClassName`，不要与桌面
`AnalysisRequest` 中已经转换过的另一种字段命名混用。

同一空 HPROF 的结果示例：

```json
{
  "sourceDigest": "16475bc162f76518ea73421767f84c8e0d5b421746ab836ee3b086d9a19fb00a",
  "heuristicOnly": true,
  "findings": {
    "leakSuspects": {"kind": "leakSuspects", "total": 0, "offset": 0, "rows": [], "presentationOmissions": []},
    "activityLeaks": {"kind": "activityLeaks", "total": 0, "offset": 0, "rows": [], "presentationOmissions": []},
    "lifecycleLeakObjects": {"kind": "lifecycleLeakObjects", "total": 0, "offset": 0, "rows": [], "presentationOmissions": []}
  },
  "evidenceBoundary": "candidates are not confirmed leaks; omitted rows remain unknown"
}
```

`total > rows.length` 表示候选页未全部返回，不等于确认遗漏了同样数量的泄漏。
空候选页也不能证明应用不存在泄漏。

## memory_class_histogram

### 使用方式

查询当前 HPROF 的类分组，固定按 retained size 降序、再按类名排序，搜索不区分大小写。

```json
{
  "jsonrpc": "2.0",
  "id": "memory_class_histogram",
  "method": "tools/call",
  "params": {
    "name": "memory_class_histogram",
    "arguments": {"heapName": "App", "search": "com.example", "limit": 50}
  }
}
```

`App` 是常见 heap 名称示意，实际值应来自 `metadata.heaps`，不需要过滤时省略
`heapName`，不要把示例名称当作每份转储都具有的 heap。

### 输入数据

- `heapName?: string`：可选 heap 名称，省略表示不指定 heap 过滤。
- `search?: string`：类名子串，默认 `""`，UTF-8 编码不超过 1024 bytes。
- `limit?: integer`：默认 50，声明的有效范围为 1 到 100。

不暴露 `offset`、排序、正则或大小写开关。查询始终从第 0 条开始，不能依靠该接口
遍历超过 100 条的完整类列表。

### 输出结果

- `sourceDigest: string`：完整 HPROF SHA-256。
- `total: integer`：过滤后匹配的类分组数，不是对象数量。
- `offset: integer`：当前为 0。
- `rows: array`：当前页类指标。
- `summary: object`：所选 heap 范围的摘要；搜索和分页不会把它改为搜索结果小计。
- `presentationOmissions: array`：展示省略信息。

`rows[]` 字段为 `className`、`instanceCount`、`shallowSize`、`reachableShallowSize`、
`retainedSize`、`obfuscatedClassName`、`hierarchyDepth`、`nativeSize`、
`retainedNativeSize`、`reachableNativeSize`、`totalCount`、`heapNames`。可空指标保留
`null`；当前 Server 不加载 mapping，不能把该接口当作反混淆服务。

`className` 为字符串，`heapNames` 为字符串数组；`instanceCount`、`totalCount`、
`shallowSize`、`retainedSize` 为整数，大小字段的单位均为 bytes。
`reachableShallowSize` 和 native 大小字段可为空整数，`hierarchyDepth` 可为空整数，
`obfuscatedClassName` 可为空字符串。

类 retained 由引擎按支配关系聚合，不同类之间仍可能重叠，不能相加作为总堆大小；
它不同于泄漏候选的代表性对象 retained。

同一空 HPROF、不传过滤条件时的结果示例：

```json
{
  "sourceDigest": "16475bc162f76518ea73421767f84c8e0d5b421746ab836ee3b086d9a19fb00a",
  "total": 0,
  "offset": 0,
  "rows": [],
  "summary": {"classCount": 0, "heapSegments": [], "objectCount": 0, "shallowSize": 0},
  "presentationOmissions": []
}
```

## memory_object_detail

### 使用方式

查询同一 HPROF 中一个对象的字段、入引用和 GC Root 引用链。该工具已暴露给外部
Client，但不在 APS 内置 AI 的三个固定预取工具之中。

```json
{
  "jsonrpc": "2.0",
  "id": "memory_object_detail",
  "method": "tools/call",
  "params": {
    "name": "memory_object_detail",
    "arguments": {"objectId": "4660", "offset": 0, "limit": 50}
  }
}
```

### 输入数据

- `objectId: string`：必填，当前应使用对象 ID 的十进制数字字符串。
- `offset?: integer`：字段或数组元素偏移，默认 0，有效范围 0 到 1000。
- `limit?: integer`：默认 100，有效范围 1 到 100。

**已知编码限制：** 工具描述为十六进制字符串，但当前处理器仅移除可选 `0x` 前缀，
随后调用十进制 `parse::<u64>()`，没有按 radix 16 解析。`"0x10"` 实际查询十进制 10，
`"0x1a"` 会失败。当前应先转成十进制字符串，例如十六进制 `0x1234` 应传 `"4660"`。
本页记录现状，不表示已修复该编码差异。

### 输出结果

不存在的对象返回 `structuredContent: null`，完整格式见[公共输出格式](#公共输出格式)。
对象存在时，payload 包含以下字段：

- 身份：`objectId`、`className`、`heapName`、`valueKind`、`representedClassName`。
- 大小：`shallowSize`、`shallowSizeKnown`、`retainedSize`、`nativeSize`、`retainedNativeSize`。
- 分页：`isArray`、`elementCount`、`arrayPageSize`、`fieldTotal`、`fieldOffset`、`fields`。
- 引用和预览：`depth`、`referenceChain`、`references`、`stringPreview`、`presentationOmissions`。

`fields[]`、`references[]` 的对象/字段行可含 `name`、`displayValue`、`targetObjectId`、
`targetClassName`、`depth`、`valueKind`、`shallowSize`、`retainedSize`、`nativeSize`、
`retainedNativeSize`、`representedClassName`、`stringPreview`、`childCount`。
`referenceChain[]` 使用前面列出的原始引用链字段。

`isArray`、`shallowSizeKnown` 为布尔值；`fieldTotal`、`fieldOffset`、`arrayPageSize`
为整数；`elementCount` 和 `depth` 可为空整数。可用大小为整数字节数，未知 native
指标保留 `null`。`shallowSizeKnown=false` 时不能把占位大小解释为实测零字节。
`representedClassName`、`stringPreview`、`heapName` 可为 `null`；`valueKind` 是类型标签，
如 `CLASS`、`OBJECT`、`ARRAY`、`STRING`，字段行还可能使用 `PRIMITIVE` 或 `NULL`。

该 payload **没有独立 `sourceDigest` 字段**，必须与同一个 Server 会话中的快照摘要
身份关联使用。字段值和字符串预览可能敏感，不应未经检查发送给模型；对象详情查询
不授予读取其他文件或跨 HPROF 查询的权限。

## memory_snapshot_diff

### 使用方式

比较启动时绑定的 A/B 类指标，固定计算 **B-A**。该工具复用
`aps_memory::compare_snapshots`，按类名合并已加载的类行，不按对象 ID 匹配。
必须通过 `--baseline-hprof` 选择 A；单快照进程也会注册此工具，但调用失败。

外部 Client 使用 `mcpServers` 风格配置时，在宿主启动参数中绑定两份文件。例如：

```json
{
  "mcpServers": {
    "aps-memory-comparison": {
      "command": "/absolute/path/to/aps-mcp",
      "args": [
        "--baseline-hprof", "/absolute/path/to/before-A.hprof",
        "--hprof", "/absolute/path/to/after-B.hprof",
        "--worker", "/absolute/path/to/aps-memory-worker"
      ]
    }
  }
}
```

配置位置由外部 Client 决定，示例路径需替换；两份文件路径只进入宿主配置，不放在
下方的工具 `arguments` 中。Server 启动后，在同一会话完成初始化再调用：

```json
{
  "jsonrpc": "2.0",
  "id": "diff-page-1",
  "method": "tools/call",
  "params": {
    "name": "memory_snapshot_diff",
    "arguments": {"offset": 0, "limit": 50}
  }
}
```

### 输入数据

- `offset?: uint`：默认 0，全局排序结果的零基行偏移；不沿用对象详情的 1000 上限。
- `limit?: uint`：默认 50，有效范围 1 到 100，不自动截到范围内。
- 省略 `arguments` 或传 `{}` 使用默认值；显式值必须是 JSON 无符号整数。
- 不接受其他参数：没有路径、snapshot ID、mapping、heap/search、排序或反转方向参数。

该工具严格校验参数。不支持的键、非对象 `arguments`、`null`、字符串、布尔值、
负数、非整数、不可表示的整数，以及越界 `limit` 均返回 JSON-RPC `-32602`。
这与原有工具的部分宽松处理不同，不能依赖默认值回退或静默忽略参数。

### 输出结果

成功时使用[公共输出格式](#公共输出格式)。以下是 `structuredContent` 的字段契约，
不是某次运行的测量结果：

- `policyVersion: string`：固定为 `"aps-memory-snapshot-diff-v1"`。
- `direction: string`：固定为 `"B-A"`。
- `beforeSnapshotId: string`：A 的完整 SHA-256（64 个十六进制字符）。
- `afterSnapshotId: string`：B 的完整 SHA-256（64 个十六进制字符）。
- `beforeMappingDigest: null`、`afterMappingDigest: null`：当前 MCP 不加载 mapping。
- `total: uint`：已加载类集合按类名合并后的总行数，分页前计算，不是对象数量。
- `offset: uint`、`limit: uint`：本次使用的偏移和页大小，含默认值。
- `hasMore: boolean`：当前页之后是否还有可分页的比较行。
- `coverage: object`：输入类覆盖和本页展示范围，见下节。
- `rows: array`：本页类级差异行，长度不超过 `limit`。
- `evidenceBoundary: string`：离线、类级、来源和覆盖限制的说明；不是泄漏确认或
  不同构建兼容性证明。

每个 `rows[]` 对象包含全部以下字段：

- `className: string`：合并键，使用 HPROF 中未映射的类名。
- `beforeCount: uint`、`afterCount: uint`：A/B 中的实例计数。
- `countDelta: integer`：有符号的 `afterCount - beforeCount`。
- `beforeShallowSizeBytes: uint`、`afterShallowSizeBytes: uint`：A/B 浅层大小，单位 bytes。
- `shallowSizeDeltaBytes: integer`：有符号的 B-A 浅层大小增量，单位 bytes。
- `beforeRetainedSizeBytes: uint | null`、`afterRetainedSizeBytes: uint | null`：A/B
  保留大小，单位 bytes；缺失证据保持 `null`。
- `retainedSizeDeltaBytes: integer | null`：两端 retained 均已知时才计算 B-A；否则
  为 `null`，不能把未知值当成零。

只在一端加载到的类，另一端 count 和 shallow 使用共享比较的零值投影；缺失 retained
仍为 `null`，不能把该行直接称为“已分配/已释放的同一个对象”。类 retained 可能重叠，
不得跨类相加为总堆增长。消费端应保留完整整数精度和差值符号。
Count 沿用直方图的类分组计数，可能包含该类的类对象，不等同于 Java 实例转储记录数
或某段时间的分配次数。

### 成功响应示例

以下是实际本地 Server/worker 对合成 HPROF 的输出，仅展示完整的
`result.structuredContent`；`content[0].text` 是同一 payload 的 JSON 文本。
这不是设备测量或用户应用数据，也不是内置 AI 自动发送的请求。

合成输入都只定义 `sample.Screen`，对象浅层大小为 16 bytes；A 含 1 个实例，B 含
3 个实例，并分别将实例设为 GC Root。类分组计数还包含各自的类对象，因此输出
`beforeCount=2`、`afterCount=4`，数量增量为 2；当前页包含全部 1 行比较结果。

```json
{
  "policyVersion": "aps-memory-snapshot-diff-v1",
  "direction": "B-A",
  "beforeSnapshotId": "4c66788e04ac0f330bcf235995f0a1a046b02e247ad5efcebc2a5dbc5f0cf6fb",
  "afterSnapshotId": "8fecec27998f0b99e9c915f17003d45bf9aaadf122710868dffacf841a80584e",
  "beforeMappingDigest": null,
  "afterMappingDigest": null,
  "total": 1,
  "offset": 0,
  "limit": 50,
  "hasMore": false,
  "coverage": {
    "beforeClassCount": 1,
    "afterClassCount": 1,
    "beforeLoadedClasses": 1,
    "afterLoadedClasses": 1,
    "included": 1,
    "omitted": 0,
    "truncated": false
  },
  "rows": [
    {
      "className": "sample.Screen",
      "beforeCount": 2,
      "afterCount": 4,
      "countDelta": 2,
      "beforeShallowSizeBytes": 16,
      "afterShallowSizeBytes": 48,
      "shallowSizeDeltaBytes": 32,
      "beforeRetainedSizeBytes": 16,
      "afterRetainedSizeBytes": 48,
      "retainedSizeDeltaBytes": 32
    }
  ],
  "evidenceBoundary": "Class-name matching only; build identity unverified; no cross-dump object identity; retained sizes may overlap and unavailable retained deltas remain unknown"
}
```

### 分页与覆盖

先对完整比较行集合排序，再按 `offset`/`limit` 取页：已知 retained 增量的行在前，
按其绝对值降序；绝对值相同按类名升序。未知 retained 增量的行在后，按类名升序。
这是增长和减少共同的全局排序，不是每页重新排序，也不是只返回增长类。

`coverage` 字段含义：

- `beforeClassCount: uint`、`afterClassCount: uint`：A/B 元数据中的类总数。
- `beforeLoadedClasses: uint`、`afterLoadedClasses: uint`：各快照实际加载用于比较的
  类行数；与元数据总数分开报告，不能假定两者相等。
- `included: uint`：本页返回的行数，即 `rows.length`。
- `omitted: uint`：比较集合中未包含在本页的行数，即 `total - included`；含当前
  偏移之前及本页之后的行，不是剩余页行数或已确认的泄漏数。
- `truncated: boolean`：本页是否省略比较行，即 `included < total`；不表示输入类
  覆盖是否完整，后者必须对照两端 class/loaded 计数。

下一页使用 `offset + rows.length`，仅在 `hasMore=true` 时继续；偏移到达或超过
`total` 时返回空 `rows` 且 `hasMore=false`。`hasMore=false` 不表示输入覆盖完整，
遍历完可用页面也不会恢复未加载类；应同时检查两端类覆盖、`omitted` 和 `truncated`。
没有输入覆盖之外的“完整堆 diff”保证。

### 错误与来源边界

- `-32602`：该工具的参数形状、类型、范围错误或存在不支持的参数。
- `-32001`：未配置基线、A/B 来源 SHA-256 相同、任一源文件发生变化、worker 失败，
  或比较无效（例如无效来源身份、类覆盖或增量超出有符号整数范围）。相同内容即使
  位于不同路径也被拒绝。
- `-32002`：工具 payload 序列化后超过既有 512 KiB 上限；不以静默截断伪装成功。

例如，使用单快照启动参数运行后调用 diff，会返回以下错误，而不是把 B 当成 A 或
静默返回单快照结果。修复方式是在宿主配置中补上 `--baseline-hprof`，并重新启动：

```json
{
  "jsonrpc": "2.0",
  "id": "diff-page-1",
  "error": {
    "code": -32001,
    "message": "Snapshot comparison requires --baseline-hprof at server startup"
  }
}
```

每页返回前均校验 A/B 两端来源，缓存比较页也不跳过校验；来源变化后不能继续
沿用旧页，需重新启动并绑定所选输入。响应只携带来源摘要，不携带 HPROF 路径。
被判失效的 worker 会话会关闭并清理其查询缓存，避免再读取该会话的旧查询结果。
固定 B-A 方向如需反转，应重新启动并交换 A/B 启动输入，不能在工具参数中换文件。

没有 mapping CLI，不验证不同构建、混淆版本或类名空间的兼容性；同名类只是合并键，
不是跨转储对象身份。外部类级 diff 不提供对象存活追踪、完整分配/释放时间线、
自动泄漏确认或模型自由调用权限，也不改变下述内置 AI 流程。

## 参数约束与错误结果

调用方应遵守 `tools/list` 声明的类型、范围和 `additionalProperties=false`。Server
对原有四个工具尚未统一执行完整 JSON Schema 校验：部分错误类型采用默认值，额外键
可能被忽略，这些宽松行为不是已承诺的兼容接口。新增 `memory_snapshot_diff` 严格
校验自己的参数，使用上节契约。

原有工具中的 `limit > 100` 会变成 100，对象详情 `offset > 1000` 会变成 1000；
`limit=0` 会在 worker 分页校验失败。非无符号整数的分页输入可能回到默认值，
不应依赖这种行为；这些规则不适用于 diff。

JSON-RPC 错误保留原请求 `id`，不返回成功工具 envelope，例如缺少工具名称：

```json
{
  "jsonrpc": "2.0",
  "id": "bad-request",
  "error": {"code": -32602, "message": "missing tool name"}
}
```

- `-32601`：未知方法，如尚未实现的 `resources/list`。
- `-32602`：`tools/call` 缺少有效工具名字符串，或 diff 参数不符合其严格契约。
- `-32001`：未知工具、无效对象 ID、搜索超限、worker 查询失败或其他工具处理错误。
  Diff 还包括缺少基线、同源、来源变化或无效比较。
- `-32002`：工具 payload 超过 512 KiB 序列化预算。
- CLI 参数错误、无法打开 HPROF/worker 或输入不是合法 JSON：Server 可向 stderr
  写诊断并非零退出，不保证总能收到 JSON-RPC 格式错误。

## 内置 AI 使用

将 `aps-app`、`aps-mcp` 和 `aps-memory-worker` 构建到同一输出目录，打开 APS，导入
HPROF 并等待快照就绪，再从普通 HPROF/泄漏分析进入 AI。自动预取范围仅为：

1. `memory_snapshot_summary`，参数 `{}`。
2. `memory_leak_findings`，参数 `{}`。
3. `memory_class_histogram`，参数 `{"limit":50}`。

Host 核对初始化/发现响应及三个工具结果：完整 SHA-256 与选定快照匹配，每个预期工具
仅一次且无工具错误。错误、缺失、重复、歧义 JSON 或子进程非零退出均拒绝整批。
每条 evidence 上限为 32 KiB，stdout 为 768 KiB，stderr 为 64 KiB，超时为 45 秒；
这与外部 Client 可读取的单个工具 payload 上限 512 KiB 是不同层级的限制。

通过后生成以下 evidence ID，后缀为规范化来源摘要的前 16 个十六进制字符；真正来源
校验使用完整 SHA-256，不只比较此前缀：

```text
mcp-memory-memory_snapshot_summary-<digest-prefix>
mcp-memory-memory_leak_findings-<digest-prefix>
mcp-memory-memory_class_histogram-<digest-prefix>
```

新增外部 `memory_snapshot_diff` 不增加预取项。结果冻结后，用户批准才会发出
Provider 请求。模型不会自由调用对象详情或 diff，也不会获得
Server 命令和本地 HPROF 路径。区分以下场景：

- 缺少伴随程序：不创建 MCP context，仍可使用已有 HPROF evidence。
- context 已创建，但查询或校验失败：prepare 失败，不静默降级到旧结果。
- 已应用 mapping：不启动未映射 MCP，普通 AI 保留已解析、已映射的 HPROF evidence。
- Diff AI：仍只使用当前已发布并冻结的 A/B 比较报告，`mcp: None`，不启动单快照
  MCP，也不改为调用外部 `memory_snapshot_diff`。
- Native Heap、ART sampled、Bitmap dump：不通过这组 HPROF MCP 工具分析。

Host 内部错误码包括 `E_MCP_CONTEXT_INVALID`、`E_MCP_SOURCE_INVALID`、
`E_MCP_SOURCE_MISMATCH`、`E_MCP_PROTOCOL`、`E_MCP_TOOL_FAILED`、`E_MCP_NO_EVIDENCE`、
`E_MCP_INCOMPLETE`、`E_MCP_LIMIT`、`E_MCP_EXECUTION`。当前 runtime prepare 将这些
错误归并为 `E_AI_CONTEXT`，界面不保证显示原始 Host 错误码。

## 验证入口与未覆盖能力

在 `rust-desktop/` 执行：

```sh
cargo test --locked --offline -p aps-memory --bin aps-mcp
cargo test --locked --offline -p aps-memory --test mcp_source_identity
cargo test --locked --offline -p aps-memory --test mcp_snapshot_diff
cargo test --locked --offline -p aps-ai mcp::tests
cargo test --locked --offline -p aps-app --bin aps-app import::memory::ai
```

[来源身份集成测试](../../../rust-desktop/crates/aps-memory/tests/mcp_source_identity.rs)
使用合成 HPROF 和真实本地 Server/worker 子进程，不是用户应用泄漏诊断、外部 Client
兼容性、真实 Provider 请求或签名发布验证。

`mcp_snapshot_diff` 是外部 A/B 工具的集成验证入口；本页列出命令不代表这些测试或
真实数据 smoke 已在某个构建上通过，执行结果应单独记录。

当前已暴露有界类级 MCP A/B diff，但未暴露完整类/实例/支配树遍历、跨转储对象匹配、
mapping、实时采集或源码读取接口。原有对象 ID 编码限制、原有工具参数 Schema 校验
不完整，以及 MCP prepare 仍依赖独立超时而非主动取消，均需按当前边界使用。
