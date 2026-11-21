# aps-storage

由 Rust 桌面端自有的 SQLite 元数据，独立于 Compose 数据库。此 crate 不提供文件系统
产物删除、原始 SQL、BLOB、完整堆、凭据、API 密钥、提示词、原始源内容或 AI 响应的持久化
API。它不是加密数据库：经过批准的元数据（包括显式注册的绝对本地源根目录和源标识符）
可由本地数据库所有者读取。调用方不得将秘密/源代码正文放入有界标签、来源链
字符串或其他合法元数据字段；语法校验不是秘密检测。

## 依赖证据与版本

固定使用 `rusqlite = "=0.37.0"`，仅启用 `bundled` 和 `limits` 功能；不存在浮动的
Git 依赖。实现前已检查官方标签证据：

- [v0.37.0 Cargo.toml](https://github.com/rusqlite/rusqlite/blob/v0.37.0/Cargo.toml)：
  MIT，edition 2021；`bundled` 选择随附的 libsqlite3 和现代绑定。
- [v0.37.0 README](https://github.com/rusqlite/rusqlite/blob/v0.37.0/README.md)：
  建议由应用自行控制数据库时使用随附的 SQLite。
- [事务 API](https://github.com/rusqlite/rusqlite/blob/v0.37.0/src/transaction.rs)
  和 [限制 API](https://github.com/rusqlite/rusqlite/blob/v0.37.0/src/limits.rs)
  证实了事务迁移和 `SQLITE_LIMIT_LENGTH` 控制。

此标签的清单未声明 `rust-version`；不臆造最低编译器版本。工作区的 Rust 1.99
工具链和平台 C 编译器必须由主集成构建检查。Rusqlite 使用 MIT 许可证；随附的 SQLite
属于公有领域。Cargo.lock 和打包的第三方声明仍由主代理管理。

## 打开与迁移

```rust
let mut storage = Storage::open(app_data_dir.join(DATABASE_FILE_NAME))?;
```

路径必须显式为绝对路径，文件名为 `aps-metadata.sqlite3`，且父目录已存在并由调用方
拥有。它不会从当前 Compose 应用中发现，也不会尝试导入/升级 Compose 数据库。
最终的 DB/WAL/SHM 符号链接会被拒绝。新的 Unix 数据库文件使用权限模式 0600；已建立的自有
数据库会在启用 WAL 前收紧权限。父目录的能力/竞态隔离仍由平台所有者负责，不是 SQLite 沙箱。

SQLite 应用 ID 为 `0x41505352`。没有此身份的非空数据库、外部应用 ID 或
更新的 `user_version`，会在修改日志模式前被拒绝。版本 0→1→2→3 在一个 IMMEDIATE
事务中迁移。迁移 v3 只将 Rust 所有的源码表替换为 Compose 一致性的
工作区/快照/文件/符号/候选项结构；产物、会话及其索引
保持不变，因此已有开发数据库会升级，而不是被拒绝。迁移失败时回滚会保留之前的
表/数据/user_version。提交前通过表/列探针拒绝不完整结构；公共 API
不暴露 SQL 执行器。

连接策略：foreign keys ON、trusted_schema OFF、busy timeout 5 s、必须使用 WAL、
synchronous FULL、每 1000 页自动建立检查点、8 MiB 的日志大小目标、
secure_delete ON，以及 1 MiB 的 SQLite 文本/BLOB/编码行上限。日志大小
不是硬性的 WAL 磁盘上限：活跃读取器可能延迟截断/检查点。目前还没有自定义检查点
调度器、磁盘配额、备份或恢复工具。

## 产物与会话

`put_artifact(&CaptureArtifact, created_unix_ms)` 校验完整契约，并存储有界的规范
元数据文档（最大 128 KiB），包括摘要、生产者/来源链、能力/完整度、
时钟和可选的进程身份。已有产物 ID 不能重新绑定到冲突的
内容/来源链；相同内容的重复插入会保留原始创建时间戳。不存储产物字节
或原始源。`artifact` 和 `list_artifacts` 返回类型化的索引列以及有界 JSON
元数据；这些行不是完整的堆快照。

`create_session(&ProfilerSession, label, now)` 以原子方式写入会话和有序的产物
引用；如果引用缺失，整个插入会回滚。`session(id)` 读取一致的元数据快照。
`open_session(id, expected, now)` 以事务方式更新最近使用时间，并返回相同代次的
快照。`list_sessions(page)` 返回较小的 `SessionSummary` 行，而不是所有关联的
产物元数据。`advance_generation(id, expected, next)` 是单调的比较交换操作。

`validate_binding(&QueryBinding { session_id, generation, source_digest })` 检查当前持久化
的代次，并确认不可变产物摘要属于该会话。在发布异步查询结果前
重新校验：校验不是租约，不能阻止未来的代次变化、文件系统内容重新哈希，
也不是执行查询的权限。

`delete_session_metadata(id, expected_generation)` 只删除会话/关联元数据。
它**不会**删除产物元数据、导入文件、采集目录或源码工作区文件。
这里有意不提供通用的产物或源码目录删除 API。

所有列表 API 将页面上限设为 100，使用稳定排序和读取事务保证计数/行一致性。
产物页面还将元数据载荷限制为 8 MiB（超限时应请求更小的页面）。会话
将完整产物引用限制为 10,000。存储的整数、JSON 和标识符校验会拒绝格式错误的行。

## 源码工作区

`SourceRepository`（由 `aps-source` 定义，此处为 `Storage` 实现）持久化 Compose 一致性的
源码模型：`source_workspaces`、`source_snapshots`、`source_files`、
`source_symbols` 和 `source_candidates`。工作区存储显示名称、提供方
种类/配置列（包括本地根目录以及用于存储兼容性的远程所有者/引用/项目
字段）、活动快照、阶段、进度、消息和每个工作区的 AI 上传
同意标记。快照通过 `ON DELETE CASCADE` 以外键绑定到工作区，
并存储修订版本、可选的脏内容摘要、清单哈希、创建时间戳、索引版本
和完整性。文件和符号从快照级联；`files()` 按相对路径排序，
`symbols()` 先按相对路径再按起始行排序。候选项存储证据 ID、
哈希位置、置信度、有界原因 JSON 数组、索引版本和完整性，
以确定性 ID 为键，并从快照级联。因此删除工作区会移除它拥有的每一行，
但绝不会触碰源码文件、产物或会话。

存储的文本、路径、哈希、阶段、语言、符号种类、置信度和行范围会在写入时以及再次
读取时校验；格式错误的行会被拒绝，而不会暴露给调用方。列表/读取 API 应用明确的
行上限，遇到过大的快照时返回错误，而不是静默截断。源码表由 Rust 所有：
不会读取或迁移 Compose 数据库，GitHub/AOSP 提供方及网络检索仍未实现（目前只往返
保存它们的元数据）。

## 证据边界

集成测试覆盖不可变来源链、事务式会话插入、最近使用时间/代次绑定、
仅删除元数据、拒绝外部/更新版本数据库、故意失败迁移后的回滚，以及源码
仓库的往返/级联。按照用户要求先完成重写再进行全面测试，这些测试**尚未执行**；
编译门禁 `cargo check -p aps-storage --all-targets --offline` 已通过，最终验证仍由
主代理负责。
