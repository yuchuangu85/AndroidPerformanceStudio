# aps-source

Rust 桌面应用的 Compose 一致性源码工作区领域。行为基准是
`desktop-viewer/source-workspace`（模型、提供方、缓存、索引器、基于索引的
解析器）；Rust 应用通过 `aps-app/src/source/service.rs` 使用此 crate。

UI 只能访问本地提供方。除 Rust 自有的内容寻址缓存外，不会进行
网络调用、凭据发现、隐式主目录扫描、源码上传或完整源码复制。

## 模型

- `SourceWorkspaceId`、`SourceSnapshotId`、`ResolutionCandidateId` 和
  `PerformanceEvidenceId` 是基于 SHA-256 的新类型封装，提供规范的
  `to_hex`/`from_hex` 和 `from_digest`。
- `SourceProviderConfig` 对 `Local { root }`、`GitHub { owner, repository,
  git_ref, credential_key }` 和 `Aosp { project, git_ref }` 建模；远程配置
  会为兼容性进行校验和持久化，但没有提供方实现。
- `SourceWorkspace` 携带显示名称、提供方配置、活动快照 ID、
  阶段（`Registering`、`ResolvingRevision`、`BuildingManifest`、`Indexing`、
  `Ready`、`Partial`、`Failed`）、进度、消息和 AI 上传同意标记。
- `SourceSnapshot` 携带不可变修订版本、可选的脏内容摘要、清单哈希、
  创建时间戳、索引版本和完整性。
- `SourceFile`/`SourceSymbol`/`SourceLocation`/`ResolutionCandidate` 与 Compose
  结构对齐；`ResolutionConfidence` 包含 `Exact`、`Probable` 和 `Weak`，而
  `BuildIdentityMatch` 允许调用方限制未经验证的结果。

`INDEX_VERSION` 保留为旧版格式标记；快照使用 Compose 索引版本
（创建时间的 Unix 毫秒值）。

## 本地提供方

`LocalSourceProvider` 实现 `SourceProvider`：

- `resolve_revision` 通过 `aps_platform::ProcessRunner`（无 shell，有界输出/超时）
  执行 `git -C <root> rev-parse HEAD`。缺少 Git 或根目录不是仓库时返回
  `unversioned`。脏工作区（或 `unversioned` 提交）会追加
  `-dirty-<digest>`，其中摘要是对按序排列、以换行连接的
  `relative:sha256(content)` 行计算的 SHA-256。
- `list_files` 遍历根目录时不跟随符号链接，跳过 Compose 目录名（`.git`、`.gradle`、
  `.idea`、`build`、`out`、`node_modules`），只接受 `kt, kts, java, xml, c, cc,
  cpp, cxx, h, hh, hpp`，拒绝非普通文件和超过 5 MiB 的文件，并按相对
  路径排序。不会预先计算内容哈希。
- `read_file` 要求有界的普通相对路径，且路径保持在根目录下；目标必须是普通的
  非符号链接文件，并受同样的 5 MiB 限制。

`SourceProviderRegistry` 将种类映射到提供方；`SourceProviderRegistry::local()`
包含当前发布的唯一实现。

## 内容缓存

`ContentAddressedSourceCache` 将字节存储在 `<root>/<hash[0..2]>/<hash[2..]>`。
创建父目录后，写入通过 `aps_platform::atomic_write` 完成；读取会拒绝缺失或非普通文件的
对象，并校验存储的哈希。该缓存永远不是隐式上传来源。

## 索引器

`StructuralSourceIndexer::index` 是 `StructuralSourceIndexer.kt` 的忠实移植，用于
Kotlin、Java、XML 资源和 C/C++ 声明。它精确复现 Compose 的正则表达式
语义（包括最左匹配和行号行为），并且有意是启发式而不是编译器
解析器：不会跳过注释/字符串内容，复杂语法可能索引不足。`max_symbols` 会
截断结果。

## 仓库与解析器

`SourceRepository` 是 Compose 仓库/索引视图的移植。这里由
`InMemorySourceRepository` 实现，存储 crate 中则由 `aps_storage::Storage`（SQLite）
实现。

`IndexedSourceResolver::resolve` 复现 `IndexedSourceResolver.kt`：类型、Android
资源、托管符号、符号化器源码路径、原生符号以及 Compose
文件/包哈希证据；候选 ID 确定性地使用
`sha256(snapshot:path:line:evidence)`；只通过仓库查询解析，绝不使用 AI
生成的路径或行号。在 `BuildIdentityMatch::Unverified`（桌面流程）下，
`Exact` 候选会降级为 `Probable`，原始原因为 `Build identity not verified`；
`SourceFileLine` 匹配使用每个包前缀的 Kotlin `String.hashCode`，因此与
Compose 完全一致。

## 测试

`tests/local_source.rs` 覆盖哈希/ID、提供方、缓存、全部四个索引器、仓库
级联、解析器置信度和路径策略。测试已编写但根据重写策略尚未执行；
编译门禁是
`cargo check -p aps-source --all-targets --offline`。
