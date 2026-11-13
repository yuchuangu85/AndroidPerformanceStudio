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
中的 `presentationOmissions` 明确报告。原始 HPROF 字段/字符串详情仍属于敏感的
本地证据；本 crate 不实现持久化、导出、AI 上传或隐式授权。

现有 engine fixture 对齐、相同输入的 Kotlin 对比、worker 崩溃和取消压力测试、RSS
测量、打包以及跨平台资格验证都是独立的验证门禁；编译此适配器并不意味着这些门禁
已经通过。
