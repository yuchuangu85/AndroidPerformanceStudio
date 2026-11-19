# 进程隔离的 HPROF 适配器

`aps-memory-worker` 复用现有的 `aps-hprof-engine::Session` 协议及其解析器、
紧凑堆索引、图、支配树、发现结果、类型化 wire values 和 SHA-256 实现。面向 UI
的 `MemorySession` 只拥有一个子进程、有界协议消息、元数据和选定页面。它从不拥有
Engine 或 Heap，也不会在 UI 进程中重建完整对象图。

使用 `cargo build -p aps-memory --bin aps-memory-worker` 构建 worker，并将其
明确的可执行文件路径传给 `MemorySession::open`。调用方提供明确选定 HPROF 的
SHA-256 摘要；worker 会在打开前校验它。输入为只读，不启用缓存或磁盘图写入，
输入大小上限为 8 GiB，子进程接收清理后的环境。worker 是受信任的应用代码，不是
OS 安全沙箱；内存限制是进程隔离和协议边界，不是每个子进程的 RSS 配额。

阻塞调用应放在后台执行器上。所有者会在取消、超时、显式 close 和 Drop 时终止并
回收直接子进程。打开操作允许 600 秒；查询允许 120 秒。父进程为带帧响应最多分配
2 MiB，页面包含 1–200 行，类名过滤器最多包含 64 个名称，嵌套列表/字符串上限为
200 项/4 KiB，并明确报告省略内容。Engine 错误文本、stderr 和源路径不会暴露给 UI。

`histogram`、`instances`、`detail`、`dominators` 和 `findings` 都是真实的 engine
查询。面向 UI 的 session 现在维护一个有界缓存，最多保存八个编码后的查询 payload，
总大小为 2 MiB；缓存键由 operation 和精确的有界参数组成。worker 失败、取消和
close 会清空缓存。Histogram 和 findings 的缓存未命中仍会在子进程中完整计算，
然后在那里分页。这不满足未来持久化查询索引的延迟目标，worker 仍可能有较大的峰值
RSS；但它确实保证堆不会进入 UI 内存。
数组详情使用 engine 分页；普通字段在 worker 中分页。截断的链/引用会通过 payload
中的 `presentationOmissions` 明确报告。

`bitmap` 模块除隔离提取外，还提供 ARGB_8888 内容大小摘要（导出图像/唯一内容/
重复组/最大图/平均图）与跨 dump 的 SHA-256 内容比较。比较绝不使用对象 ID 作为
跨 dump 身份；新增、移除和重复计数变化分别报告。原始 HPROF 字段/字符串详情仍属于敏感的
本地证据；本 crate 不实现持久化、导出、AI 上传或隐式授权。

现有 engine fixture 对齐、相同输入的 Kotlin 对比、worker 崩溃和取消压力测试、RSS
测量、打包以及跨平台资格验证都是独立的验证门禁；编译此适配器并不意味着这些门禁
已经通过。

## 类级差异身份

`MemorySession` 对发送给 worker 的规范化 mapping 计算 SHA-256，并把该身份附在每份
`MemorySnapshot` 上；未映射时为 `None`。`compare_snapshots` 是表格与 AI 共用的类级
计算入口，只允许不同 HPROF 来源、有效的类覆盖，以及两端均未映射或 mapping 摘要相同。
该规则保守拒绝不同 mapping，不表示已经验证不同构建之间的兼容性，也不建立跨快照对象身份。

应用切换 A/B 后会使旧报告、导出资格和计算代次失效。Diff AI 只投影当前已发布报告的
前 32 行，在 16 KiB 增长证据预算内保留两端来源摘要、计数和可为空的 retained 增量；
证据身份也绑定 mapping 摘要。校验、溢出或预算失败会返回错误，不会降级为单快照泄漏分析。
切换或关闭标签时保留显式 A/B 及其方向；任一端点关闭后保持不可用，不自动替换快照。
Diff 视图中的 CSV 和调查报告导出同样要求当前已发布比较，不回退为单快照报告。
Diff 请求不附带活动快照的泄漏结果、源码候选或单端 MCP 查询，避免第三份快照混入。
普通单快照 AI/MCP 路径不受此变更影响，Provider 执行仍须经过现有审批。

## MCP 内存工具

`aps-mcp` 是 APS 自有的只读 MCP Server。它接受明确选定的目标 HPROF B、可选基线
HPROF A 和 `aps-memory-worker`，通过 stdio 始终注册以下 5 个有界工具：

- `memory_snapshot_summary`：快照元数据、摘要和来源摘要。
- `memory_leak_findings`：HPROF 启发式泄漏候选、生命周期候选和引用链。
- `memory_class_histogram`：可按 heap/class 搜索的有界类直方图。
- `memory_object_detail`：单个对象的字段、引用和 GC Root 证据。
- `memory_snapshot_diff`：复用 `aps_memory::compare_snapshots` 的类级 B-A 分页比较。

原有四个工具只读取 B。没有基线时第五个工具仍可发现，但调用返回 `-32001`。

构建：

```sh
cargo build --locked -p aps-memory --bin aps-memory-worker --bin aps-mcp
```

从 Rust 工作区启动，原有 `--hprof <B> --worker <worker>` 保持兼容；已知 flag 的
排列顺序可变，双快照增加可选参数 `--baseline-hprof <A>`：

```sh
./target/debug/aps-mcp \
  --hprof /absolute/path/to/after-B.hprof \
  --worker ./target/debug/aps-memory-worker \
  --baseline-hprof /absolute/path/to/before-A.hprof
```

Diff 参数只有 `offset?: uint`（默认 0）和 `limit?: uint`（默认 50，1–100），不接受
任意路径、mapping、方向或排序参数。输出 policy 为 `aps-memory-snapshot-diff-v1`，
固定 `direction: "B-A"`，以 `beforeSnapshotId`/`afterSnapshotId` 携带两端完整 SHA-256，
两端 mapping digest 均为 `null`；行保留 count、shallow、可空 retained 的两端值和增量。
先按已知 retained 增量绝对值降序、再按类名全局排序后分页，并报告总行数、`hasMore`
和输入/本页 coverage。缺失 retained 不补零，不建立跨 dump 对象身份，不验证不同构建。

Diff 对不支持或格式错误的参数返回 `-32602`；缺少基线、相同来源哈希、任一来源变化
或无效比较返回 `-32001`；超过既有 512 KiB payload 预算返回 `-32002`。每页返回前
均校验 A/B 两端来源，缓存页也不例外。完整字段、分页和 coverage 语义见
[Memory MCP 接口](../../../docs/ai/mcp/memory.md#memory_snapshot_diff)。

在完成初始化的同一会话中，第一页请求示例为：

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

外部 Client 的双快照配置、完整成功结果及缺少基线的错误示例，也在上述接口文档中维护。

Server 不接受任意文件读取、Shell、网络或写文件请求；响应不包含 HPROF 路径，打开时由
worker 校验文件来源摘要，禁用缓存的解析路径也对解析器实际持有的文件句柄再次校验摘要，
避免校验与重新打开之间的文件替换沿用旧身份。APS 内置 AI 在 Memory HPROF 分析的 prepare 阶段启动该 Server，
预取摘要、泄漏发现和类直方图，把结果冻结为 `mcp-memory-*` evidence 后再进入现有
审批和 Provider 请求流程。MCP 查询失败会 fail closed，不会回退到未经绑定的旧结果。

这三个预取工具均返回完整 `sourceDigest`。Host 校验上下文和每条响应的 SHA-256，要求
响应属于预期工具且各出现一次；错误、缺失、重复、歧义 JSON 或子进程非零退出均拒绝
整批结果，不发布部分 evidence。每条 evidence 使用 AI 契约的 32 KiB 预算，Host stdout
仍限制为 768 KiB，子进程超时为 45 秒。

MCP 不提供 mapping CLI 或工具参数。已应用 mapping 的快照不会启动这条未映射的补充
查询，仍可使用已解析且已映射的 HPROF evidence 进行普通 AI 分析。新增外部 diff 不
改变上述 3 项内置预取；Diff AI 仍只使用当前已发布并冻结的比较报告，`mcp: None`，
既不进入单快照 MCP 路径，也不调用外部 diff。

外部 diff 集成验证入口（在 Rust 工作区执行；不是已通过的运行或发布声明）：

```sh
cargo test --locked --offline -p aps-memory --test mcp_snapshot_diff
```
