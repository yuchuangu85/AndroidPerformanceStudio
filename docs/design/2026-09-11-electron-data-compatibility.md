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
| 源码工作区 | `~/.android-performance-studio/source-workspaces.db` | 同一文件（`source-backend.ts`） | **共用**，两侧互相可读 |
| AI 分析会话 | `~/.android-performance-studio/analysis-sessions.db` | 同一文件（`ai-service.ts`） | **共用**，同一 DDL |
| Perfetto trace 会话 | `~/.android-performance-studio/`（perfetto-storage） | Electron `userData/traces` | 分叉 |
| Layout 抓取 | layout-inspector 自己的目录 | Electron `userData/layout-captures` | 分叉 |
| Frame / Startup / Battery / Network / Benchmark / GPU / Memory / Method / CPU 会话 | 各自 `*.db` | Electron `userData/<feature>`（JSON） | 分叉 |
| capture archive | `CaptureArchiveCodec` | 未迁移 | 缺口 |

## 决策

**重写期间：只有「不可再生」的数据共用路径，可再生的会话数据留在 Electron 自己的目录，并把这个偏离写进矩阵。**

理由：分叉的代价取决于数据能否重建。

- **源码工作区与 AI 分析会话不可再生**：前者是索引过的快照（重建要重新拉取仓库并重新索引，GitHub/AOSP 还要网络与凭据），后者是花钱调用模型得到的结论。这两个库已经共用同一文件，且写入方向是双向的。
- **其余会话是可再生的派生数据**：一个 frame/startup/battery 会话的输入是设备上的采集，重新采一次就得到；会话文件本身只是那一轮的汇总。为它们逐一实现 Kotlin 的 SQLite schema 与迁移，成本是 9 个 feature × (schema + 迁移)，换来的只是不用重采。
- **代价必须可见**：用户换回旧应用时，看不到 Electron 期间新产生的会话；反之亦然。这不是静默丢失（原始数据仍在各自的采集流程里），但确实是一次体验断裂，所以矩阵的缺口列与本节都写明。

## 后果与未闭合项

1. issue #21 的 DoD「既有设置 / SQLite / 归档可读」**只能算部分满足**：设置与两个共享库成立，其余会话与 archive 不成立。
2. 若日后要闭合，最小路径是：先做 capture archive 的导入（它是唯一的跨应用交换格式），再把高频使用的 2–3 个 feature 会话做只读导入，而不是全量移植。
3. 设置迁移目前只有单测层面的合成输入；用旧应用真实写出的 plist / 注册表 / XML 做端到端对照仍是缺口。

## 回退路径

两个应用共存期间，回退到旧 Compose 应用必须是无损的。检查清单：

- [ ] 旧应用仍能从 `~/.android-performance-studio/` 打开它自己的库；Electron 只对 `source-workspaces.db` 与 `analysis-sessions.db` 写入，且写入使用同一 DDL。
- [ ] Electron 不删除、不改名任何旧应用创建的文件。
- [ ] Electron 对旧库的读取在迁移前是只读的（`packages/storage` 的 `openReadOnlyDatabase`）。
- [ ] 设置在 Electron 侧写入自己的 JSON，不回写 `java.util.prefs`，因此旧应用的设置不被改写。
- [ ] 卸载 Electron 应用不会带走 `~/.android-performance-studio/`。

## 另一个未闭合的边界约束

D4 要求重解析下沉到 `utilityProcess`/`worker_threads`，目前未实现：HPROF、simpleperf、ART trace 的解析都在主进程。这不属于数据兼容，但同属「尚未闭合的进程与边界约束」，一并留待后续。
