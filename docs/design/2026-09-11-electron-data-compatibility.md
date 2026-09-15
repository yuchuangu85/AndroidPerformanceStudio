# Electron 重写：数据兼容性与回退路径

- 状态：Accepted（2026-09-11）
- 分支：`feature/electron-rewrite`
- 关联：`docs/adr/0036-rewrite-the-desktop-app-on-electron-without-jvm.md`、`docs/records/electron-feature-parity.md`

## 问题

ADR-0036 把「既有数据格式可读」列为兼容义务：Java Preferences 设置、`~/.android-performance-studio/` 下的各 feature SQLite 库、capture archive。重写期间两套应用共存，所以「读得到」还意味着「两边看到的不是两份不同的真相」。

现状盘点（`feature/electron-rewrite`）：

| 数据 | Kotlin 位置 | Electron 位置 | 现状 |
| --- | --- | --- | --- |
| 设置 | `java.util.prefs`（macOS plist / Windows 注册表 / Linux XML） | Electron `userData/settings.json` | 启动时一次性迁移读取，保留三个平台各自的解析器 |
| 源码工作区 | `~/.android-performance-studio/source-workspaces.db` | 同一文件（`source-backend.ts`） | **共用**，同一 DDL；Kotlin → Electron 与 Electron → Kotlin 的仓储写读 fixture 均已验证 |
| AI 分析会话 | `~/.android-performance-studio/analysis-sessions.db` | 同一文件（`ai-service.ts`） | **共用**，同一 DDL；Kotlin → Electron 与 Electron → Kotlin 的仓储写读 fixture 均已验证 |
| Perfetto trace 会话 | `~/.android-performance-studio/`（perfetto-storage） | Electron `userData/traces` | 分叉 |
| Layout 抓取 | layout-inspector 自己的目录 | Electron `userData/layout-captures` | 分叉 |
| Startup Profiler | `startup.db` + `StartupJsonExporter` / `StartupJsonImporter` v1 | Electron `startup-sessions` JSON + main-process JSON/SQLite import/export | **JSON 双向可交换；SQLite 单向只读导入**：Electron 用 read-only handle 校验 `startup_sessions` / `startup_runs` 后导入 `.db` / `.sqlite`，本地 JSON 持久化仍与 Kotlin `startup.db` 分叉 |
| Frame / Battery / Network / Benchmark / GPU / Memory / Method / CPU 会话 | 各自 `*.db` | Electron `userData/<feature>`（JSON） | 分叉 |
| capture archive | `CaptureArchiveCodec` + `RecentPathStore` | Electron `CaptureArchiveCodec` + File 菜单导入/导出 + `RecentPathStore` | **可交换**：Kotlin 与 Electron 均以真实 codec/service 写出 v2 `.apinspect` fixture，并由另一侧实际导入全部 required/optional payload；两侧共用 `recent-layout-inspector-archives.txt` 的最近归档路径历史 |

## 决策

**重写期间：只有「不可再生」的数据共用路径，可再生的会话数据留在 Electron 自己的目录，并把这个偏离写进矩阵。**

理由：分叉的代价取决于数据能否重建。

- **源码工作区与 AI 分析会话不可再生**：前者是索引过的快照（重建要重新拉取仓库并重新索引，GitHub/AOSP 还要网络与凭据），后者是花钱调用模型得到的结论。这两个库已经共用同一文件与 DDL，且均已有两种写入方向的独立 fixture。
- **其余会话是可再生的派生数据**：一个 frame/startup/battery 会话的输入是设备上的采集，重新采一次就得到；会话文件本身只是那一轮的汇总。Startup 是当前受控例外：双方以公开 JSON v1 双向交换，Electron 还可把 Kotlin SQLite 单向、只读导入为本地 JSON；它不共用或写回 `startup.db`。为其余会话逐一实现 Kotlin 的 SQLite schema 与迁移，成本是 8 个 feature × (schema + 迁移)，换来的只是不用重采。
- **代价必须可见**：用户换回旧应用时，看不到 Electron 期间新产生的会话；反之亦然。这不是静默丢失（原始数据仍在各自的采集流程里），但确实是一次体验断裂，所以矩阵的缺口列与本节都写明。

## 后果与未闭合项

1. issue #21 的 DoD「既有设置 / SQLite / 归档可读」仍**只能算部分满足**：设置、两个共享 SQLite 库的双向 fixture、`.apinspect` v2 的双向 codec/service fixture、Startup JSON v1 的双向 fixture，以及 Electron 对 Startup SQLite 的只读导入已成立；没有 Startup SQLite 写回、共享数据库或并发访问证据，其余 feature 会话仍不互通。
2. `.apinspect` v1/v2 的导入/导出已成为 Layout 抓取的跨应用交换格式；当前 v2 的双向 codec/service fixture 已覆盖 required/optional payload。Startup SQLite 的只读导入验证了高频会话的最小路径；下一步应评估其他 feature 是否值得采用同一模式，而不是全量移植。
3. 设置迁移目前只有单测层面的合成输入；用旧应用真实写出的 plist / 注册表 / XML 做端到端对照仍是缺口。

## 共享 SQLite 的跨运行时夹具 — 2026-09-14

共享路径不能只靠相同 DDL 声称互通；夹具必须由其中一侧的真实仓储写入、另一侧的真实仓储读回。当前证据分为两个方向：

### Kotlin 写入 → Electron 读取

- `desktop-viewer/source-workspace` 的 `writeElectronInteropFixture` 生成 `packages/source-workspace/src/fixtures/kotlin-source-workspace.db`；`source-workspace.test.ts` 会复制该不可变夹具、先检查原始 SQLite 列，再交给 Electron 的 `SqliteSourceWorkspaceRepository` 读取。
- `desktop-viewer/ai-core` 的同名任务生成 `packages/ai-core/src/fixtures/kotlin-analysis-sessions.db`；`gateway.test.ts` 断言 session 的 provider、ISO 时间与 JSON ID 数组，finding 的证据/候选 ID，SHA-256 evidence payload hash（不保存 payload body），以及带/不带行号和 content hash 的候选顺序，再交给 Electron 的 `SqliteAnalysisSessionRepository` 读取。

### Electron 写入 → Kotlin 读取（AI 会话与 Source Workspace）

`gateway.test.ts` 还使用 Electron 的 `SqliteAnalysisSessionRepository` 调用 `saveSession`、`saveRequest`、`saveResult` 写入完整的 fixture。它覆盖 provider、ISO 时间、父 session、JSON ID 数组、finding/候选顺序、空 range/content hash，以及只保存 evidence SHA-256 而不保存 payload body。测试正常运行时只写临时目录；以下显式命令会刷新 Kotlin 测试资源：

```sh
APS_ELECTRON_ANALYSIS_SESSION_FIXTURE_PATH=../desktop-viewer/ai-core/src/test/resources/electron-analysis-sessions.db \
  corepack pnpm@12.3.4 --dir electron-viewer exec vitest run \
    packages/ai-core/src/gateway.test.ts -t "Electron-created analysis-sessions SQLite fixture"
```

`AiAnalysisGatewayTest` 将资源复制到临时数据库后，通过 Kotlin 的 `SqliteAnalysisSessionRepository` 读取并断言同一字段。fixture 生成测试会在所有 Electron 读回完成后 checkpoint WAL 并切换至 `DELETE` journal，因此版本库只保存独立的 `.db`，不依赖 `-wal` 或 `-shm` 辅助文件。

`source-workspace.test.ts` 同样使用 Electron 的 `SqliteSourceWorkspaceRepository` 写入 GitHub provider metadata、snapshot、file、symbol、带 Kotlin column-default (`startColumn/endColumn = 1`) 的 range candidate 及 null-range candidate。显式刷新 Kotlin 测试资源的命令为：

```sh
APS_ELECTRON_SOURCE_WORKSPACE_FIXTURE_PATH=../desktop-viewer/source-workspace/src/test/resources/electron-source-workspace.db \
  corepack pnpm@12.3.4 --dir electron-viewer exec vitest run \
    packages/source-workspace/src/source-workspace.test.ts -t "Electron-created source-workspace SQLite fixture"
```

`SourceWorkspaceIntegrationTest` 通过 Kotlin 的 `SqliteSourceWorkspaceRepository` 读回并断言 provider、snapshot、source index、range columns 与 null range。该 fixture 也在最后 checkpoint WAL 并切换至 `DELETE` journal。

这组证据证明当前 AI 与 Source Workspace schema/序列化的双向仓储读写互操作；它**不**证明两个运行时并发访问同一数据库，或真实用户历史库的迁移行为。

## Startup Profiler JSON v1 双向互操作 — 2026-09-14

Startup 是可再生会话中第一个同时具备显式文件契约和 SQLite 只读迁移边界的 feature。Electron 与 Kotlin **没有**共享 `startup.db`；两端继续保存各自的会话数据库/JSON。JSON 互操作边界是 Kotlin `StartupJsonExporter` / `StartupJsonImporter` 的 schema v1：Electron 主进程用 native file picker 导入或导出，renderer 只传递 session ID，不能读任意路径。

Kotlin → Electron 的 `kotlin-startup-report.json` 由 `:startup-export-adapters:writeElectronInteropFixture` 调用真实 Kotlin exporter 生成；Electron `kotlin-json.test.ts` 导入该 fixture。导入器拒绝非 v1、空或超过 100 条的 run、重复 run ID、非法 enum、缺少原始 `am start` evidence、无效 JSON 和超过 16 MiB 的文件。Kotlin 的 `deviceLocalId` 是 pseudonym，导入结果固定标为 `origin: IMPORTED` 和 `deviceSerial: IMPORTED`，仅作为 `sourceDeviceLocalId` 保留；它绝不被当作可用于 ADB 的 serial。若 legacy v1 没有 context，Electron 也不虚构 package/component。

Electron → Kotlin 的 `electron-startup-report.json` 由 `exportKotlinStartupJson()` 实际写出，Kotlin `StartupExportersTest` 使用真实 `StartupJsonImporter` 读回。电子端不会把真实 ADB serial 或其它原始设备标识输出为 Kotlin context；缺失 context 在 Kotlin 端按无可验证设备/包上下文处理。对于 Electron **captured** 会话，JSON 包含每 run timing、warnings、TTID/TTFD evidence 与 raw `am start`/event-log evidence；没有 Kotlin agent/compilation/environment/Perfetto evidence 时以该模型的 unavailable/absent 表示，而不是编造它们。对于 Kotlin **imported** 会话，Electron 将已 schema-gated 的原始 v1 文档与会话一起持久化并在导出前再次验证后原样写回，因此不会把 Kotlin 的 agent、milestone、phase、compilation、environment 或 trace evidence 伪造为空值或静默删除。

`StartupSessionStore` 回归覆盖两个导入持久化边界：legacy 无 context 会话在 index 中保持无 package，且 `origin`/source filename 往返；有 context 的 Kotlin pseudonym 以 provenance 持久化而从不成为 device serial。session-level context 只会在**所有** run 都有相同 context 时提升；混合/冲突 context 保持无 session identity。互操作 fixture 同时暴露并修复了 Kotlin `StartupAnalyzer.statistics()` 在全部 TTFD 缺失时把 missing run 记为 present 的错误：现在 `count = 0`、`missingCount = 1`，并有 unit test 锁定。以上是当前文件格式和 fixture 证据，**不**证明真实设备采集、历史真实用户 `startup.db` 迁移、两运行时并发访问或发行包内行为。


## Startup SQLite 只读导入 — 2026-09-15

Electron 的 Startup 面板另有专门的 `.db` / `.sqlite` 导入动作。`startup-sqlite-import.ts` 使用 `@aps/storage` 的 `DatabaseSync(path, { readOnly: true })`，先验证 `startup_sessions`、`startup_runs` 和 required columns，随后才读取记录；它绝不调用 Kotlin `SqliteStartupSessionStore.open()`，后者会 `CREATE TABLE` / `ALTER TABLE`，不满足导入源不可变约束。为让主数据库 SHA-256 确实绑定所读快照，导入拒绝 `-wal`、`-shm` 或 `-journal` sidecar，且在 hash/读取前后都校验 device/inode/size/mtime 与 hash；因此用户须先关闭 Kotlin。导入还限制源主库至 64 MiB、`startup_sessions` 至 1,000 行、`startup_runs` 至 100,000 行，避免 main-process 同步 materialization 无界增长。每个导入会话固定标为 `origin: IMPORTED` / `deviceSerial: IMPORTED`，把 Kotlin 的 `device_serial` pseudonym 放入 `sourceDeviceLocalId`，并保留 `sourceDatabaseSha256` 与文件名，绝不将 pseudonym 用作 live ADB serial。

适配器把 session/run、nullable platform metrics 和 0/1 `complete` 映射到 Electron 模型；`ttid_source` / `ttfd_source` 可被保留，但 SQLite 没有保存完整 evidence confidence，因此 Electron 只能标记为 `INFERRED`，从不升格为 `EXACT`。面板会明确显示 imported origin、source 文件/pseudonym/SHA-256、每 run 的 TTID/TTFD source+confidence+reason 和限制 warning；milestone、phase、compilation、environment、trace 详情尚无 Electron 模型，用户必须保留源数据库。`startup-sqlite-import.test.ts` 覆盖空 warnings、哈希/sidecar 不变、active WAL 拒绝、64 MiB 限制、schema table/column 缺失拒绝、pseudonym 边界、nullable metrics、warmup/measure 划分与 confidence 降级；这只是按 Kotlin DDL 构造的本地 SQLite fixture，不是 Kotlin writer 的真实历史库或 Kotlin UI 回开证据。


### 证据分层

- **A — 本地实现**：JSON 的单个已打开文件句柄 16 MiB 受限读取；SQLite 的 read-only schema/sidecar/identity/hash/row/size 边界；main/preload IPC、会话 index 与 i18n/UI 导入导出动作。
- **B — fixture / golden corpus**：真实 Kotlin writer → Electron importer 和真实 Electron writer → Kotlin importer 的双向 v1 fixture。
- **C — 外部运行时证据**：真实 Android capture、可信 Compose bundle、打包/签名/公证与 CI 仍分别缺证，不能从本地测试推导。
- **D — 有意分歧**：Frame、Battery、Network、Memory、Method、CPU 等继续各自持久化；Startup 的 SQLite 只读导入不等同于共享 schema、写回、并发访问或 rich evidence 的无损转换。

## CPU / Method `.apsession.zip` 评估 — 2026-09-14

Kotlin `SessionPackageService` 是通用 session-directory ZIP transport：它将每个 regular file 写进 `<name>.apsession.zip`，以排序的 `apsession-manifest.txt`（`schema=1`、SHA-256、相对路径）完整性清单约束内容，并在导入时拒绝 Zip Slip、重复 entry、未列清单文件、hash 不匹配、symlink 和超出 4 GiB 单 entry / 8 GiB 总量 / 100,000 entry 的包。未知但已列清单的 regular file 会被保留；这是 archive 完整性层，而不是 CPU 或 Method 的业务 schema。

Electron 目前可直接导入 ART `.trace`，也可直接导入 simpleperf protobuf / `perf.data` / Gecko profile；它**尚未**导入或导出 Kotlin `.apsession.zip`。不能把 Electron 的 `method.trace + session.json` 或 `report.pb + session.json` 误称为 Kotlin session package：Kotlin CPU session reopen 依赖 `perf.data`，并可读取 `simpleperf.protobuf`、`profile.sqlite`、symbols、mapping 与 `capture-artifact.json`；Method Recording 的 Kotlin controller 也尚无显式 package action。下一步若实现此边界，应先以 Kotlin 的 `golden.apsession.zip` 建立 ZIP+manifest+unknown-file 的跨运行时 golden，再分别定义 CPU re-open 与 Method artifact consumer，避免用通用 archive 通过来伪装 profiler 语义已对齐。

## Kotlin `.apinspect` 写入 → Electron 读取 fixture — 2026-09-14

`layout-inspector:presentation:writeElectronInteropFixture` 使用真实 Kotlin `CaptureArchiveService` 生成 `electron-viewer/apps/desktop/src/main/fixtures/kotlin-capture.apinspect`。fixture 不是手写 ZIP：它经过 Kotlin 的 Protocol、AnalysisReport、AiAnalysisReport、TimelineHistory 与 Compose safe-redaction serializers，再由 `CaptureArchiveCodec` 写入 v2 manifest 和 SHA-256。显式刷新命令为：

```sh
cd desktop-viewer
./gradlew :layout-inspector:presentation:writeElectronInteropFixture \
  -PfixturePath=../electron-viewer/apps/desktop/src/main/fixtures/kotlin-capture.apinspect
```

该 archive 覆盖全部九个成员：manifest、layout snapshot、PNG screenshot、Compose inspection、static analysis、AI analysis、timeline history、raw ZIP 和 raw text。Electron 的 `capture-archive-service.test.ts` 先以 `CaptureArchiveCodec.read` 验证 manifest/entry integrity 和 Kotlin payload，再通过 `CaptureArchiveService.import` 写入 `LayoutCaptureStore` 并读回 screenshot、Compose JSON、raw pair 和三个 report/history JSON。fixture 同时锁定 Kotlin `AiAnalysisReportJson` 对无 provenance 报告输出 `"provenance": null` 的合法形式；Electron 现将 null 与缺省 provenance 一样视为“未捕获 provenance”，而不是拒绝 archive。

此证据证明 **Kotlin 写入 → Electron 读取** 当前 `.apinspect` v2 的 required/optional payload 互操作；Electron 反向 fixture 的证据在下一节。它不证明任意历史 archive 的迁移，或真实设备采集。

## Electron `.apinspect` 写入 → Kotlin 读取 fixture — 2026-09-14

`capture-archive-service.test.ts` 使用 Electron 的真实 `LayoutCaptureStore` 和 `CaptureArchiveService.export` 写出 `desktop-viewer/layout-inspector/presentation/src/test/resources/electron-capture.apinspect`。它不是手写 ZIP：Electron codec 为 v2 manifest 的所有八个 payload entry 写入 size 与 SHA-256，再由 Kotlin 的真实 `CaptureArchiveService.import` 在 `CaptureArchiveServiceTest` 中读取。

fixture 覆盖与反向 Kotlin fixture 相同的九个成员：manifest、layout snapshot、PNG screenshot、Compose inspection、static analysis、AI analysis、timeline history、raw ZIP 和 raw text。Kotlin 断言 snapshot/display、PNG、raw pair、static finding、AI model/finding、timeline diff，以及 Compose document；Electron 读回测试同时锁定它自己写出的 manifest integrity 与 payload。AI report 显式使用 `"provenance": null`，验证 Kotlin 输出的无 provenance 语义也被 Electron writer 正确发出。

两组 fixture 共同证明当前 `.apinspect` v2 的 **Kotlin ↔ Electron** codec/service required/optional payload 互操作。它们仍不证明两个应用对同一 archive 的并发操作、任意历史 v1/v2 archive 的迁移，或真实设备采集。

## 回退路径

两个应用共存期间，回退到旧 Compose 应用必须是无损的。检查清单：

- [ ] 旧应用仍能从 `~/.android-performance-studio/` 打开它自己的库；Electron 只对 `source-workspaces.db` 与 `analysis-sessions.db` 写入，且写入使用同一 DDL。
- [ ] Electron 不删除、不改名任何旧应用创建的文件。
- [ ] Electron 对旧库的读取在迁移前是只读的（`packages/storage` 的 `openReadOnlyDatabase`）。
- [ ] 设置在 Electron 侧写入自己的 JSON，不回写 `java.util.prefs`，因此旧应用的设置不被改写；`archive.snapshotSizeMultiplier` 迁移到 Electron JSON 时按 Kotlin 的 `1..10` 约束归一化，并继续控制快照、解压后总量和归档字节总量限制。
- [ ] 卸载 Electron 应用不会带走 `~/.android-performance-studio/`。

## 进程与边界约束

2026-09-14，D4 的重解析要求已在 Electron 主进程路径闭合：每次 HPROF 抓取、ART 实时抓取与 `.trace` 导入/缓存重建、simpleperf 抓取与 retained protobuf 缓存重建、以及离线 protobuf / gzip Gecko 导入与缓存重建，都会创建新的 electron-vite `?nodeWorker`。字节通过 transfer list 交给 worker；worker 的异常、错误反序列化、无响应退出或非零退出会拒绝该次请求；ART/simpleperf 的正常解析失败仍以原有 `StudioResult` 返回。HPROF worker 同时返回派生 `MemorySession` 和原始 `HprofParseResult`，因此 raw dump 删除后当前会话的实例浏览缓存仍可用。`parser-worker-runner.test.ts` 临时构建真实 CJS electron-vite bridge 并运行 `node:worker_threads`，覆盖三种解析、gzip Gecko 和失败传播；正式 `electron-vite build` 也发出了 `out/main/parser-worker-*.js` 并从 `out/main/index.js` 引用。

2026-09-14 已为五个破坏性 remove IPC 通道增加运行时 opaque ID 校验（类型、路径分隔符、NUL 和长度）；`trace:importFromPath` 先校验绝对路径；`memory:diff` 在读取持久化 JSON 前校验两个不同的 opaque session ID 与匹配模式；`source:reindex/search/resolve/read/setAiUpload` 在访问共享 workspace/index 前校验 ID、查询上限、索引相对路径、同意布尔值和所有 evidence 联合类型；`ai:saveConfiguration/saveCredential/analyze/findings` 在凭据持久化、layout snapshot 读取或外部网络访问前校验模型、HTTP(S) endpoint、secret 尺寸、capture/workspace/session ID 和 build identity；`frame`、`trace`、`cpu`、`startup`、`battery`、`method`、`memory`、`bitmap` 与 `nativeHeap` 的 capture IPC 都在打开 ADB 或创建临时目录前校验结构、Android serial/package、枚举及 Kotlin/Electron 对齐的数值范围。Bitmap 会话读取与新增的 `bitmap:image` 也先校验 opaque session ID；后者还校验 `0..1,000,000` 的 record index，并仅从 main-process-owned `<id>/images/` 读取、在 32 MiB 上限内返回一个 PNG data URL。本轮已补齐 method/CPU/内存实例快照、trace open/reveal、GPU open/reveal/relocate、所有 profiler session load、layout archive/load 等存储选择请求的运行时校验：opaque ID、方向/实现方式、bounded search/thread text、transform kind/path/function ID、内存 class/heap/limit 与对象 ID 均在访问 store、解析器或文件系统前拒绝畸形输入。本轮还将非存储型边界纳入同一 fail-closed 合同：`shell:updateSettings` 只接受已知的深层设置字段、Kotlin 对齐的枚举/整数范围、绝对 Android SDK 路径与 ARGB 颜色；`shell:openDestination`、`shell:openUserGuide` 和 `menu:updateState` 分别验证闭合 destination/language/menu-state 结构；`layout:capture` 保留空 serial 的“Auto device”语义，但在调用 ADB 前拒绝非法 serial、非对象 options、非布尔 archive、未知 target 和额外字段。因此 System UI/foreground app 与 auto-scan 的非归档选项不再依赖 TypeScript 注解或 truthiness。

## 2026-09-14 Compose agent capture boundary

The Electron host follows the Kotlin stable-frame contract whenever it is given a verified, authenticated inspector session: it performs View-A/Compose-A/View-B/Compose-B/View-C and accepts only the middle pair after structural equality checks. Compose trees are requested serially per View root with the corresponding generation; bulk parameter extraction is intentionally not part of this stability protocol.

The agent bundle is a hard runtime trust boundary. Electron verifies its manifest and hashes before ADB use, injects only into one debuggable PID, sends the patched 256-bit session token before outer-protocol commands, and cleans temporary/private copied artifacts. A missing bundle or version-matched Compose inspector artifact must not alter normal layout capture behavior. Compose inspection JSON is persisted as optional `.apinspect` v2 content, preserving v1 imports and captures without Compose details.

The normal capture IPC is now the integration point for verified Compose enhancement, not an alternate UI-only API. It never substitutes a partial Agent result: the ordinary View capture/screenshot completes first, then an available trusted bundle may enrich the snapshot with grafted Compose nodes and an optional archive payload. The package contract is deliberately fail-closed for release construction: the bundle must be present under `build/compose-agent` and is verified again at runtime before any ADB injection.

## 2026-09-14 shared application-data root

Before Electron becomes ready it now redirects `userData` to the Kotlin-compatible `~/.android-performance-studio` root. Startup then copies only absent top-level entries from the historical Electron default directory, so a prior Electron session is preserved while a pre-existing Kotlin record always wins. All session stores that derive from `app.getPath("userData")` consequently share the same root as the source, AI, credential, device-identity and Perfetto paths already used.

## 2026-09-14 Capture Archive optional payload compatibility

`.apinspect` is a lossless interchange boundary for its optional report/history members as well as its required snapshot. Electron validates the Kotlin wire shape of `report/analysis-report.json`, `report/ai-analysis-report.json`, and `timeline/history.json` during archive import, stores their original JSON beside the managed capture, and re-exports those bytes without reserializing historical Kotlin evidence. Invalid optional JSON fails the archive import; it is not silently retained as arbitrary renderer input.

The renderer receives the persisted payloads through `LayoutCaptureDetail`: the archived static report supersedes local recomputation, archived AI findings/provenance are visibly marked as historical, and timeline history is shown as the same metadata/diff-only sequence Kotlin archives. Because that schema does not contain historical snapshots/screenshots, Electron does not pretend those older frames are inspectable; only the member matching the archive snapshot can be represented as a full layout capture.

For Electron-originated captures, an export supplies the deterministic local static analysis when no imported report exists. It deliberately does not invent optional AI or timeline members. This retains Kotlin's independent-optional-entry semantics while avoiding a false claim that an Electron-only AI session or transient React timeline is attached to a capture archive.

## 2026-09-14 AI citation disambiguation

Source navigation remains bound to a persisted AI session and its source-candidate IDs. Electron now uses Kotlin's direct-open rule for one candidate and explicit chooser rule for multiple candidates. It does not resolve a new current-tree candidate merely to avoid a chooser, preserving historical AI provenance and the `CURRENT`/`STALE` source-workspace policy.

## 2026-09-14 Method Recording lifecycle parity

Electron now follows Kotlin Method Recording's control-plane contract without giving the renderer arbitrary ADB or filesystem authority. For the selected device, the main process reads `dumpsys package packages` and `ps -A -o PID,NAME`, retains only debuggable or `profileableByShell` application processes, maps `package:remote` names back to their package, and exposes only the typed result to the renderer. Immediately before starting a trace it repeats that lookup, so an exited or PID-reused process cannot be profiled from a stale picker entry.

The main process owns one live recording signal. `method:stop` merely sets that signal; it never kills an ADB process or removes the trace itself. The existing capture owner observes the signal during the 100 ms recording wait, then preserves the Kotlin finalization order: `am profile stop <pid>`, device-file flush polling, pull, parse, store, and unconditional local/device cleanup. A second recording is rejected while the first owns that signal, and a stale completion cannot release a newer recording. The renderer keeps standalone `.trace` import independent of device/process availability.

Evidence: `method-capture-service.test.ts`, `method-recording-lifecycle.test.ts`, `MethodRecordingPanel.test.tsx`, and the lower-level `packages/art-trace/src/capture.test.ts`. This is source/test parity only; a real device must still prove Android/API/package behavior.

## 2026-09-14 Retained HPROF for historical Memory browsing

Kotlin Memory capture keeps raw/converted HPROF files in its session directory; Electron now retains the raw HPROF required for equivalent instance/detail browsing. During capture the Electron main process stages `<id>.hprof.pending` in its own `memory-sessions` directory **before** the parser worker receives a transferable byte buffer. A successful parse commits it as `<id>.hprof` together with the derived JSON session; a parse or session-save failure removes the staged/final artifact. The renderer receives no filesystem path and continues to query only typed instance/detail IPC.

The in-memory heap cache remains an optimization limited to three entries. On a cache miss, the main process loads the session metadata and its retained raw HPROF, checks that the entry is a regular file within the same 2 GiB capture limit, reparses it in a fresh worker, and recaches the derived graph. Sessions created before this change, or sessions with missing/corrupt/oversize raw evidence, deliberately produce no instance rows rather than presenting stale or invented data. This restores Electron historical browsing behavior but does not make its JSON/raw directory layout interoperable with Kotlin's SQLite session stores.

Evidence: `memory-capture-service.test.ts`, `memory-session-store.test.ts`, `memory-persisted-heap.test.ts`, and the existing worker bridge tests. Real-device HPROF capture and cross-runtime Memory-session migration remain separate evidence gaps.

## Generic `.apsession.zip` container boundary — 2026-09-14

Kotlin Simpleperf’s `SessionPackageService` is a **generic integrity container**, not a declaration that every profiler understands every member. Electron now has the corresponding main-process-only `SessionPackageCodec`: it reads and writes the root `apsession-manifest.txt` (`schema=1`), verifies SHA-256 and exact regular-file membership, rejects duplicate entries, ZIP Slip paths, encrypted/unsupported ZIP data, symlinks in an export source, and bounded extraction overages, and preserves manifest-listed unknown files and empty directories. Its ZIP reader supports Kotlin `ZipOutputStream` data-descriptor archives and ZIP64 metadata; the in-memory Electron implementation still rejects archives larger than the Node `Buffer` capacity rather than streaming an unbounded input.

The transport has actual cross-runtime fixture evidence in both directions: Electron reads Kotlin’s generated `golden.apsession.zip`, while Kotlin `SessionPackageService` imports the byte-pinned `electron-session-package.apsession.zip` emitted by Electron’s deterministic writer. The Electron test compares a fresh writer output byte-for-byte with that committed fixture so a writer change cannot silently leave Kotlin’s fixture stale.

This only closes the **container** contract for arbitrary profiler data. The CPU profiler now consumes it through the existing Import action and exposes a dedicated Export session package action (`cpu:import` / `cpu:export`, preload, and CPU panel). A package import must contain `perf.data`; if `capture-artifact.json` is present Electron decodes it with the shared Kotlin-compatible `CaptureArtifactJson` v1 validator and verifies its SHA-256 before locating or executing host `simpleperf`. Electron then regenerates the protobuf from `perf.data`, optional `symbols/`, and `mapping.txt` rather than trusting a possibly stale packaged protobuf.

Imported CPU packages retain their full verified source as `source-session/`, so re-export packages that exact directory—including manifest-listed files unknown to Electron. Native Electron captures now retain raw `perf.data`, a derived `simpleperf.protobuf`, and a valid v1 `capture-artifact.json` alongside the Electron report/record; the store writes all session members through a private staging directory and only publishes it after rename. A CPU package is exportable only when this retained raw evidence exists: report-only legacy/protobuf/Gecko imports return a clear unavailable error instead of creating a package Kotlin cannot reopen. This establishes the semantic CPU contract for Kotlin `OfflineProfileImporter.importCapturedSession`; it does not make Method sessions package-compatible, and no device-produced Electron archive has yet been opened by a Kotlin UI in this revision.
