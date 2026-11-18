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

## MCP 内存工具

`aps-mcp` 是 APS 自有的只读 MCP Server。它只接受一个明确选定的 HPROF 和
同目录下的 `aps-memory-worker`，通过 stdio 提供以下有界工具：

- `memory_snapshot_summary`：快照元数据、摘要和来源摘要。
- `memory_leak_findings`：HPROF 启发式泄漏候选、生命周期候选和引用链。
- `memory_class_histogram`：可按 heap/class 搜索的有界类直方图。
- `memory_object_detail`：单个对象的字段、引用和 GC Root 证据。

构建：

```sh
cargo build --locked -p aps-memory --bin aps-memory-worker --bin aps-mcp
```

Server 不接受任意文件读取、Shell、网络或写文件请求；响应不包含 HPROF 路径，源摘要变化
会使当前 session 失效。APS 内置 AI 在 Memory HPROF 分析的 prepare 阶段启动该 Server，
预取摘要、泄漏发现和类直方图，把结果冻结为 `mcp-memory-*` evidence 后再进入现有
审批和 Provider 请求流程。MCP 查询失败会 fail closed，不会回退到未经绑定的旧结果。
