# 已验证的 GPU 产物索引

基于现有 Kotlin `AgiArtifactIndexer`（位于
`gpu-inspector-integration/agi-artifact-index`）改编而来的只读元数据和 SHA-256 索引。
不包含 AGI 实现、GPU
解码器、设备捕获、进程启动、HTML 渲染、文件导出、索引持久化或递归文件系统搜索。

`GpuArtifact::import` 读取一个明确选定的常规文件（最大 8 GiB），根据扩展名/32
字节前缀检测暂定格式，并计算共享的 `SourceDigest`。分类/打开路由元数据不能证明
工件的解析器有效性、工具可用性、GPU 兼容性或执行权限。原始工件不会复制到 UI
状态。绝对路径不会被故意进行 Debug 格式化；它们仅保留在本地，不得进入遥测或
AI 输入。

`resolve_location` 会检查每个已注册位置、字节大小和 SHA-256；第一个副本缺失不会
阻止选择另一个有效副本。不同状态包括 Available、Missing、SizeChanged、
ContentChanged 和 Unreadable。`relocate` 只接受大小和摘要相同的用户选定副本。
`GpuArtifactIndex::import` 按摘要去重，保留所有有界位置，不会把重命名的重复副本
重新解释为不同格式。限制为 10,000 个工件、每个工件 64 个位置，以及每个展示页
200 项。

哈希扫描是同步的；应在 UI 线程之外运行导入和验证。取消检查发生在哈希计算前后，
而不是哈希计算期间。大输入的硬取消延迟、内容变更竞态、持久化存储和相同输入的
Kotlin 一致性仍是明确的资格验证工作，不能从代码存在本身推断出来。
