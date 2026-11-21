# Rust + GPUI Kit 迁移执行记录

目标：实现 `desktop-viewer/docs/design/rust-gpui-kit-migration-plan.md` 的**全部内容**。
分支：`rewrite/rust-gpui`，基于 `889c055d`。
状态：实现进行中。这不是缩减范围的 MVP 完成声明。

## 执行约束

- 将 Compose 保留为行为基准/回退方案；在证明每个最终切换门槛之前不得移除它。
- Android Agents 继续使用 Kotlin/Java。第三方工具只复用，不重写。
- 11 月 11 日的全功能测试/对齐请求取代了更早的测试延期。下方历史延期测试条目保留
  原有的证据边界。新的领域、原生、设备和打包证据分别跟踪；测试套件通过不等于完成
  Compose 对齐。
- 本任务不意味着提交、推送、发布、凭据迁移、源代码上传或设备变更。
- 可见的导航目的地不能证明性能分析器已实现。

## 计划要求和下一步证据

1. **基线/契约（阶段 0）** — 待处理：不可变的代表性和大型 CPU 夹具、Compose
   摘要/树/flame/堆栈基准、启动/RSS/交互计时脚本及观测。在最终验证中收集性能
   观测，实现期间不得编造数字。
2. **桌面壳（阶段 1）** — 进行中：固定的工作区/toolchain、GPUI 窗口、菜单、图标、
   Home、Settings、主题/语言、选择器、剪贴板、退出；所有目标平台的原生包和最小包仍是
   验收门槛。
3. **平台/数据（阶段 2）** — 进行中：有界进程运行器、工具/ADB 解析、取消/回收、设备
   目录、独立数据路径。待处理：会话/制品摘要/来源信息、SQLite 迁移、
   工具生命周期适配器。
4. **CPU 切片（阶段 3）** — 进行中：复用现有 Rust protobuf 引擎、单次解析会话、
   prepared 查询分页。待处理：持久化原生会话、Overview/Top Functions/Call Tree/
   Flame Graph/Stack Chart 的全部交互、视口聚合/空间命中测试、采集、Firefox
   导出和对齐。
5. **Memory/Network（阶段 4）** — 待处理：引擎适配器、分页 UI、脱敏、有界授权内容。
   Perfetto Trace Analyzer 和采集切片记录在下方 2026-11-07 的对齐条目中。
6. **其他性能分析器/Inspector（阶段 5）** — 待处理：Frame、Startup、Battery、GPU、
   Benchmark 以及桌面 View/Compose inspection 宿主；保留 Android Agents。
7. **AI/Source（阶段 6）** — 待处理：提供方/SSE/目录/请求/storage、权限、不可变
   源码身份/索引、确定性导航。
8. **协议/导出边界** — 待处理：有界的版本/摘要/会话/代次请求和响应封装、
   子进程隔离、取消、JSON/CSV/PNG/Firefox 导出。
9. **综合验证** — 按 11 月 11 日请求进行中：领域、同输入对齐、GPUI 无头、原生 GUI、
   打包发布；macOS/Windows/Linux、IME/CJK/无障碍、通知、内置工具、签名、取消/失败恢复、
   代表性/大型性能剖析数据资源预算。
10. **最终切换** — 不具备条件：在计划第 9 节的每个门槛都以新鲜且匹配要求的证据满足前，
    保留 Compose。

每个未完成项目仍属于当前目标。仅编译通过不能关闭相应的功能、性能、平台或发布门槛。

## 实现检查点 — 2026-11-06

此检查点取代上方粗略的“待完成”实现标签，**不取代**尚未满足的验收门槛。未运行测试或
GUI 验收。

### 已连接的初始读取/导入工作流

- `aps-app/src/source.rs`：显式选择本地源码目录、有界清单/符号索引、文件
  分页、确定性候选项、带哈希校验的只读代码片段（含行号和独立行分页）。元数据查找覆盖
  整个清单，不受当前 100-文件页面或前 512 个文件限制。Refresh 创建新快照；
  Close 使待处理的选择器/索引/读取发布失效。不获取远程源码，也不上传。
- `aps-app/src/inspector.rs`：原生归档导入、窗口切换、分页层级树、几何
  画布和属性。有效的祖先可见性/alpha 以及本地隐藏会裁剪绘制/命中输入。Bounds
  在画布预算之前过滤；符合条件的选中节点可保留在 4,000-节点预算之外。Canvas
  选择会移动层级树页面。窄窗口中的三个面板可横向滚动，高显示屏缩放时不会裁剪下方
  屏幕。Close 使选择器/导入完成结果失效。这些是代码级修复，不是视觉/原生交互验收。
- `aps-layout-inspector`：摘要和解析器消费同一份冻结的压缩字节序列，消除独立重新打开
  路径所造成的身份 binding。权衡：解析期间最多额外占用 172 MiB 压缩输入内存；这
  不是经过测量的低 RSS 声明。截图内容已验证但未显示；实时采集、详细的 Compose/
  运行时属性和 multiframe 对齐仍未完成。
- `aps-method-recording` + `aps-app/src/method.rs`：有界的二进制-first ART
  夹具-布局 v2/v3/v4/v5 导入、真实 ENTER/EXIT/UNROLL 回放、时长统计、线程页面、
  Top Methods、call 树、区间/事件/诊断页面及选中的堆栈预览。主时钟
  和 CPU 时钟保持独立；method 事件不是 CPU 采样。传统文本-first `.trace`、录制
  传输和可图形缩放的时间线仍未完成。Close 会阻止结果发布，但不会协作式中断有界
  解析器工作。
- 外壳 entities 在目标页面切换期间保留独立的 CPU、Source、Inspector、Method 以及
  每个性能分析器的导入状态。导航标签描述的是初始连接切片，不是完整功能对齐。
- 之前已连接的 Memory 对象/支配者/leak 分页、Battery 报告、Benchmark 基线比较和
  GPU 验证仍位于工作区中。

### 已在编译层面集成的并行后端工作

- `aps-ai`：冻结的请求/一次性审批、提供方策略、异步 Chat
  Completions/SSE 传输、超时/取消、已验证结果和审计元数据存储。尚未接入应用的
  审批/结果 UI。未访问凭据，也未执行真实提供方请求。Keychain、实时
  模型刷新、Responses API、chat 历史和结果-body 持久化仍未完成。
- `aps-storage`：修复工作区编译期间发现的 migration 提交错误转换。存储持久性/运行
  时行为尚未验收。
- 本检查点没有将采集-controller 规划交付为 `aps-capture` crate。设备变更/采集
  仍未实现，而不是静默 mock。

### 新鲜证据（仅编译和静态检查）

来自 `rust-desktop/`：

- `cargo check --workspace --all-targets --offline`：通过，包括测试源代码编译；**未执行测试
  二进制文件**。修复了 Benchmark 夹具格式字符串，并显式限定 Rust 测试属性，
  以避免测试目标编译期间的 GPUI macro recursion。
- `cargo clippy --offline -p aps-app -p aps-source -p aps-layout-inspector
  --all-targets --no-deps`：完成并产生样式警告。严格的 `-D warnings` 未通过（源码 walker
  argument API 以及现有 CPU/Memory 嵌套 `if`；包含依赖的严格检查还报告 Startup/Battery/
  Benchmark）。
- 交接时记录了针对性的格式和空白检查。运行时、截图、同输入 Compose 对齐、设备采集、
  平台构建、打包/签名以及内存/性能测量仍未执行。

### 下一步实现依赖

1. 实际的采集 controller 以及明确的设备/进程-实例 confirmation，配合有界的自
   有远端路径和经过验证的 Simpleperf 转换契约。
2. AI 审批/提供方/结果 UI 和凭据-参考适配器；将性能分析器证据
   连接到确定性的源码 candidates，且不隐式上传。
3. 持久化会话/storage 集成、剩余的导出/tool/live Inspector 路径、传统 ART 格式
   以及完整性能分析器交互。
4. 在运行延期的 comprehensive 测试、原生 GUI、对齐、性能和发布门槛前，完成 migration
   实现 matrix。

## 实现检查点 — 续于 2026-11-06

用户重申了**完整**迁移目标。本检查点增加实现切片，不声明任何阶段或最终门槛已经关闭。
在实现完成前，之前关于禁止执行测试的规定仍然有效；编译测试目标不等于执行测试。

- 新增 `aps-capture`：显式的只读 CPU plan、一次性精确的 serial/PID/boot-ID/start-ticks
  confirmation、变更前复检、固定的 Simpleperf argv、有界的 64 MiB `adb exec-out cat`
  transfer、摘要/来源信息和精确的本地部分-文件清理。不进行广泛的 `pkill`，也不
  执行隐式设备命令。Remote Simpleperf 在宿主取消后仍可运行其有限时长；
  可选 converter output 可能在 post-运行大小 rejection 前继续增长。因此 GPUI 采集页面
  仍为**只读规划**；目前尚未向用户暴露 `confirm`/`run`。Perfetto 采集和打包后的 tool
  resolution 仍未完成。
- 新增 GPUI AI 页面，仅提供冻结的请求/证据/源码 candidate 预检。目前没有
  性能分析器准备运行，不存在凭据适配器或准确的 serialized-载荷审批 UI，
  也没有可用的审批/network call。`aps-ai` 传输实现未启用到产品中。
- Source registration/快照元数据现在持久化到独立的 Rust SQLite database。Recent
  条目以有界分页列出元数据；选择一项会显式重新注册、重新验证工作区身份并
  创建最新快照。启动时不会自动索引旧路径。Database 错误可见但不会阻塞本地查看。
  这一早期仅元数据切片已被 Source Workspaces 与 Compose 对齐的切片（2026-11-07）
  取代，后者还会在由 Rust 管理的本地缓存中保留内容寻址的源码 bodies。这不是
  持久化 CPU profile 会话实现。
- CPU UI 现在提供有界的当前查询 Call Tree/Top Functions CSV 复制操作，并在 Close 时
  使待处理的选择器失效。CSV 不是 Firefox/PNG/JSON 导出，也不是 all-页面导出。已修正
  原始 `perf.data` guidance，使其匹配现有 Kotlin Simpleperf `report-sample` converter，
  而不是未经验证的 `report_sample.py` claim。
- 导航暴露 AI 预检和采集规划，并显示 **Not migrated** 状态，而非
  operational 标签。其他路由仍只是初始切片。

最新本地静态证据：更新 `aps-capture` 的 lockfile 后，`cargo check --locked --workspace
--all-targets --offline` 通过；`cargo fmt --all` 和 `git diff --check` 完成。Test binaries、
设备命令、提供方请求、GUI 验收、跨平台软件包和对齐
**未运行**。编译会为尚未连接的 AI `present`/`has_preflight` methods 产生两个 dead-code
警告。Strict 工作区 Clippy 仍未完成。

下一组受实现阻塞的门槛：安全的端到端采集取消和 converter output bounding；
可信的凭据参考和准确的 AI 审批表面样式；CPU 快照保留策略/reconciliation
和完整会话对齐；完整性能分析器交互、导出和实时 Inspector；相同输入和
multi-platform 验证。不要仅根据这些切片移除 Compose 或宣称完整迁移。

### 上述检查点之后的后续实现

- 在下方的内容寻址的快照切片之前，CPU 导入只将不可变的
  SHA-256/长度/来源信息元数据和内容寻址的会话 association 写入仅供 Rust
  使用的 SQLite。最近的 CPU 元数据只有在显式重新选择文件并重新验证摘要/长度后才
  手动刷新和重新打开。后台查询/CSV 发布会重新检查会话代次 binding。
  解析前后的文件检查不是原子 filesystem 快照，相同字节共享一个 association，而不是
  一串导入事件的历史记录。Close 竞态可能留下已提交的元数据行，但不会发布旧的
  UI 结果。
- 通用制品选择器现在参与代次 invalidation，因此旧选择器结果无法撤销
  Close。Rust 设置数据-根节点 overrides 必须是绝对路径；不接受相对元数据/采集
  路径。
- Perfetto 导入会优先搜索经过验证的打包 `resources/perfetto-tools`；只有调试构建
  可以回退到源码-checkout tool。Release 构建绝不假设存在 developer checkout。
  macOS/Windows/Linux 上的工具打包和运行时使用尚未验证。

本次后续工作的最新静态证据：`cargo fmt --all --check`、
`cargo check --locked --workspace --all-targets --offline` 和
`git diff --check` 通过。测试源代码已编译但**未执行**；由于没有接入可信的性能分析器运行，
AI-预检 dead-code 警告仍然存在。

### CPU 内容寻址快照切片 — 2026-11-06

- CPU 导入现在会使用有界读取和 `atomic_write`，将经过验证的 SIMPLEPERF protobuf 字节
  复制到独立的由 Rust 管理的 `cpu-artifacts/<sha256>.simpleperf.pb` 目录。只有在重新检查
  持久化文件的 regular-文件身份、字节长度和 SHA-256 后，导入才被视为持久化完成。
- 最近的 CPU 元数据现在提供两条明确路径：不依赖原始源码文件打开已保存快照，
  或重新选择源码并重新计算哈希。快照 reopening 首先验证 SQLite 会话/代次
  binding，拒绝缺失、符号链接、大小变化或摘要不匹配的快照，并将内部快照路径
  排除在面向用户的源码标签之外。
- SQLite 仍仅保存元数据：源码路径和性能剖析数据字节不存储在 `aps-metadata.sqlite3`。
  快照保留策略/配额和 orphan reconciliation 仍是待解决的发布问题；本切片没有建立
  资源-预算、cross-引擎对齐或打包验收。
- 现有外壳 AI routing 间距也已针对 Method Recording 和 Layout Inspector 关闭，使用有界且
  不含路径的证据投影。AI 提供方/凭据和 network 验收门槛仍未完成。

本切片的最新静态证据：`cargo check --locked --offline -p
aps-app` 以及对四个改动应用文件的 rustfmt 检查通过。已为源码-less 快照重新打开
和 tamper rejection 添加测试源，但在当前 migration instruction 下**未执行**。`aps-app` 中
唯一剩余的编译警告是预先存在且尚未连接的 AI `present` / `has_preflight` 路径。

### CPU Firefox/Gecko 导出切片 — 2026-11-06

- `aps-cpu::CpuSession::export_firefox_profile()` 现在会输出有界的 AOSP Gecko v24 JSON profile，
  并以 gzip 压缩。它构建各线程的采样表、frame/stack/string 表格，将 Simpleperf
  nanoseconds 转换为 Gecko milliseconds，保留未知-符号 address 身份，并应用现有
  Kernel/原生/DEX/OAT/Off-CPU/JIT category rules。
- 原生 exporter 使用标准库实现的 stored DEFLATE 区块，将最终 gzip output 限制为 64 MiB，且
  从不写入目标页面。`aps-app` 增加了显式 save 操作，使用 absolute-路径和仅新建的
  发布语义，因此不会覆盖已有文件，失败时会删除部分 output。
- 这关闭了 CPU 切片的实现/导出边界，但没有关闭验收边界：
  browser 导入、Kotlin 相同输入 round-trip 对齐、大型 profile 的内存/延迟测量以及
  打包后的发布验证仍未完成。

本切片的最新静态证据：`cargo fmt --all`、
`cargo check --locked --workspace --all-targets --offline` 和
`git diff --check` 通过。未执行测试、browser 导入、GUI 交互或打包后的导出
验证。

### AI 提供方目录预检切片 — 2026-11-06

- 现有 `aps-ai` 审批/传输/结果生命周期已接入 GPUI；本切片补充缺失的目录
  边界，而不是创建第二套 send 状态机。`AiWorkspace` 现在持有注入的
  `ProviderCatalog`，并在创建 `PreparedRun` 前拒绝未注册的提供方/模型 combinations。
- 默认目录仅包含显式 loopback 提供方 `local` 的元数据，地址为
  `http://127.0.0.1:12345/v1`，模型为 `local-model`，凭据参考为不透明的
  `memory:manual`。目录不存储密钥，也不会执行 `/models` 或提供方请求。
  Endpoint 和凭据参考也必须与已注册提供方匹配，从而阻止在已注册提供方
  ID 下替换为自由文本端点。
- Streaming capability 会根据已注册的模型 descriptor 检查。现有的一次性凭据、
  显式 network/源码审批、取消、响应验证和 audit 元数据路径
  保持不变。

本切片的最新静态证据：`cargo fmt --all` 和
`cargo check --locked --workspace --all-targets --offline` 通过。`aps-app` 唯一剩余的警告是
现有未使用的 `present` / `has_preflight` alternate 预检条目。未执行提供方请求、
凭据查找、测试、GUI 或打包后的验证。

### CPU 远程采集租约切片 — 2026-11-06

- 已确认 CPU 采集现在只通过生成的受限外壳 wrapper 启动固定的 Simpleperf 命令，
  以获取准确的远端 PID。随后宿主在有界 deadline 内轮询 `/proc/<pid>/cmdline`，并验证
  命令仍同时包含 `simpleperf` 和生成的运行制品路径，之后才允许制品 pull。
- Host 取消只向该准确 PID 发送 `SIGINT`，并最多等待五秒让远端进程消失。停止
  失败或 PID 身份变化会进入 `CaptureState::CancellationUnverified`；不能将其改写为普通
  的成功取消。Local 清理仍严格只针对准确文件，不引入广泛的远端清理或 `pkill`。
- Remote 制品仍保留以支持显式恢复；远端 filesystem 配额和打包后的 Simpleperf
  resolution 仍未完成；没有运行时证据时，不接受真实设备 stop 行为。

本切片的最新静态证据：`cargo fmt --all`、
`cargo check --locked --workspace --all-targets --offline` 和
`git diff --check` 通过。未执行设备命令、测试、GUI 或软件包验证。

### Perfetto 有界查询缓存切片 — 2026-11-06

- `TraceSession` 现在会将已验证的 `QueryPage` 结果保存在有界的、由会话所有的
  缓存中，键为 `QueryPreset + offset + limit`；源码摘要隐含在所属会话中，每个
  页面仍在其契约中保留摘要。缓存最多保存八个页面和 2 MiB 的 cell 文本。
- 来源元数据变化会清空缓存，并在查询或缓存发布之前采取保守拒绝策略。现有的
  固定 SQL 预设、CSV 模式验证、有界文本解码、工具校验和/版本
  检查、ProcessRunner 取消和代次守卫保持不变。
- 这消除了重复的相同页面查询，但尚未创建预热 Trace Processor RPC context 或持久化的
  native 查询索引。缓存 misses 仍使用现有的有界一次性 CLI 进程；运行时 trace
  对齐和性能证据仍未完成。

本切片的最新静态证据：`cargo fmt --all`、
`cargo check --locked --workspace --all-targets --offline` 和
`git diff --check` 通过。未执行 trace processor 进程、测试、GUI 或打包后的验证。

### Layout Inspector 宿主协议切片 — 2026-11-06

- `aps-layout-inspector` 现在暴露 `InspectorProtocolSession`，作为宿主-side
  `aps-protocol` consumer 处理一个有界的 `inspector.frame.open` 请求。它绑定
  `ProfilerSessionId`、源码摘要和严格递增的代次，验证准确的
  软件包/frame/window/node 字段，检查 parent/深度 ancestry，并只返回第一个有界的
  层级树页面。
- Protocol 路径强制限制为 64 窗口、100,000 total 节点、200 returned 层级树行，
  通过 `FrameLimits` 限制文本/values/depth，并返回明确的
  取消/过期代次/错误响应。运行时值、语义、截图
  和原始属性仍从投影中排除。
- 这推进了 desktop 协议-宿主边界，但不宣称 live 设备采集：Android Agents
  仍为 Kotlin/Java，不打开 ADB/socket，会话也尚未接入真实传输或 GPUI Inspector
  entity。

本切片的最新静态证据：`cargo fmt --all`、
`cargo check --locked --workspace --all-targets --offline` 和
`git diff --check` 通过。已添加协议回归测试来源，但在当前完整-rewrite
验证 instruction 下未执行。

### Layout Inspector 协议到界面发布切片 — 2026-11-06

- `InspectorWorkspace` 现在通过单一的代次-安全的 `publish_protocol_frame` 路径发布已
  验证的 `InspectorProtocolFrame`。Local 归档导入使用同一发布路径，因此
  协议宿主和归档 fallback 共享窗口选择、层级树偏移、hidden 节点、
  选中节点、画布和属性状态，而不是维护分叉的 UI 状态状态转换。
- Stale 帧 generations 会被拒绝，取消仍由背景导入 task 所有；有界的
  归档/协议投影仍排除运行时值、语义、截图和原始属性。
- 这将宿主契约连接到 GPUI Inspector entity，但不宣称 live 采集：Android Agent/socket
  传输、ADB 生命周期、截图渲染和 native 设备验收仍未完成。

本切片的最新静态证据：`cargo fmt --all`、
`cargo check --locked --workspace --all-targets --offline` 和
`git diff --check` 通过。未执行测试、设备传输、GUI 或软件包验证。

### Inspector 工作区空状态修复 — 2026-11-06

- Rust Inspector 过去在未加载归档时会立即返回，只显示 Import 归档和 Close。现在
  层级树、Canvas、属性以及本地状态条会在空、loading 和失败状态
  下渲染。
- Pane containers 具有明确的可用-大小和 shrink 约束。层级树和 property
  行独立滚动；几何画布在测量到的视口内适配并居中，绘制和命中使用同一
  变换。
- Archive 导入、代次检查、选择、窗口 switching 和本地 hidden-节点行为
  保持原有所有权。不添加采样节点或合成发现项。实时传输、截图
  解码、Timeline 和 Findings 对齐仍未完成，不会因空面板而被暗示为已完成。
- 回归测试源码覆盖始终存在的面板以及 portrait/landscape/零尺寸画布
  约束。根据 rewrite 验证 instruction，这些测试已编写并完成编译检查，但未执行。
  `cargo check --all-targets` 和本地调试构建通过。Target-窗口截图和 native
  交互验收已尝试但未建立。

### Trace Analyzer 离线工作区切片 — 2026-11-06

- Rust Trace 路由现在渲染专用的 Trace Analyzer 工作区，而不是通用制品表格：包括
  import/cancel/close 控件、有界采集占位内容、Processes/Threads/Slices/CPU
  Scheduling 查询标签页、源码/查询元数据、分页以及可滚动的结果表面样式。
- 它复用经过验证的 `aps-perfetto` `TraceSession` 和固定的 `QueryPreset` 契约。不引入
  任意 SQL、PATH fallback、原始 tool output、设备采集或合成 trace 指标。
- 工作区仍以离线优先为优先。Sessions/书签、Result/Query/Search 详情标签页、
  诊断 insight 目录、外部 Perfetto UI server 和 ADB trace 采集仍是待完成的
  对齐工作。

最新静态证据：`cargo fmt --all`、`cargo check --locked -p aps-app
--all-targets --offline` 和 `git diff --check` 通过。未执行测试或原生 GUI 验收。

### Compose Trace Analyzer 对齐切片 — 2026-11-07

- 范围：Rust Trace 路由现在端到端对齐 Compose Perfetto Trace Analyzer，而不是复用通用
  导入表格：包括工作区会话侧栏和书签、按严重级别/category 划分并支持
  preset 限定与筛选的 insight 侧栏、四个摘要卡片、Result/Query/Search 详情标签页、
  切片搜索、以时间戳为锚点的 Perfetto UI 启动、原始轨迹和会话包导出、
  采集页面、设备/capability 刷新、AI 预检 context，以及双语文案目录
  (`values/trace.json`、`values-zh/trace.json`)。
- `aps-perfetto` 现在负责 Compose 诊断表面样式：一个预热
  `trace_processor_shell` 会话（`server unix --daemonize` + `query --remote`，显式使用
  `TraceSessionMode::Warm/OneShotFallback`，并在 Drop 时终止守护进程），
  完整的 35-查询目录和 8 个 preset 均按字节从 Kotlin 基准移植，包含发现项阈值
  (`findings.rs`)、有界严格 CSV 解码以及转义后的 `TraceSliceSearch`。对于 GPU 检查器，
  `QueryPreset`/`QueryPage` 保持不变；无效 trace 仍采取保守拒绝策略，且不存在任意 SQL、PATH
  回退、download 或 cloud connection。
- Capture 移植了 `PerfettoConfigTextBuilder`、capability detection、Compose 探测/template
  gating，以及有界的 adb 推送/start/stop/pull 会话（64 MiB `exec-out cat` 上限、代次/
  取消守卫）；采集页面渲染三个 Compose 面板。本切片未执行实时 hardware 采集，
  并将其记录为依赖设备的间距。
- Sessions/书签使用有界、原子的 JSON 索引和受管理的 trace copies；会话导出写入
  `session.json`（包括从采集获取的真实设备模型/SDK）、`trace.pftrace` 和
  `metadata.properties`；外部查看器仅绑定回环并打开 system 浏览器；AI context
  在现有审批对话框之前限制为仅含数值的、限制行数的并绑定 SHA-256，绝不包含线程
  名称、SQL 或路径。
- 验证：`cargo test -p aps-perfetto --offline` = 34 passed（35-查询对齐断言读取
  Kotlin 基准；pinned-tool integration 针对 `synthetic-empty.pftrace` 运行整个目录），
  `cargo test -p aps-app --offline` = 222 passed，`cargo test --workspace --offline --no-fail-fast`
  = 526 passed/0 failed，`cargo check --workspace --all-targets --offline` 和 `rustfmt` 通过。
  针对合成空 trace 的原生 GUI 检查覆盖导入、preset execution、摘要卡片、
  insight 筛选、Result/Query/Search 详情标签页、切片搜索、会话/书签行、
  采集面板、AI 已冻结的载荷审查、宽窄布局以及 English/Chinese 开关（截图位于
  `build/trace-analyzer/`）。
- 剩余缺口：实时 ADB 采集和真实设备 trace 尚未执行；GUI 取消可能使远端 perfetto pid
  持续到自身时长结束；超过 64 MiB 的 traces 会采取保守拒绝策略；会话/书签使用
  单进程 lock；查看器回退页面会解释回环 URL，而不是嵌入 cloud iframe；设备
  选择器+刷新仅在采集打开时出现，因为外壳标题栏已经暴露共享目标选择器。
### Compose Frame Analyzer 对齐切片 — 2026-11-07

- 范围：Rust Frame 路由现在渲染专用的 Frame Analyzer 工作区，而不是通用导入表格。
  它对齐 Compose 帧-性能分析器应用：设备/进程目标（共享标题栏宿主）、Import
  Perfetto FrameTimeline、Associate Perfetto trace、Capture FrameTimeline、Open trace、
  Start/Stop 在线采集、AI 预检、状态栏、筛选行、带 sparklines 的摘要
  卡片、时间线画布（缩放/平移/悬停/聚类 marks/帧选择）、带证据
  复制/阶段 breakdown/布局 handoff 的帧详情、带 sparklines 的 jank 聚类、problem
  帧表格，以及 File-菜单 gfxinfo 导入 + CSV/JSON 导出。
- `aps-frame` 增加了符合 Compose 的模块，同时保留旧导入页面使用的旧版
  FrameSession API：model.rs（FrameSource、ExpectedDurationSource、JankType、FrameStages、
  FrameSample、FrameCaptureSession、FrameSourceCapabilities）、gfxinfo.rs（完整的
  GfxInfoFrameStatsParser port，含警告/未知列/deadline 来源信息）、analysis.rs
  （FrameJankAnalyzer 阈值/严重级别/聚类、FrameAttributionAnalyzer、
  FrameTimelineClassification、FrameJankStatsCorrelator、FrameViewFilter/filter_analysis、
  format_millis）以及 frame_timeline.rs（Expected/实际 FrameTimeline 行映射和
  capability subtraction）。旧版根节点名称保持不变；冲突的四个 Compose types 位于
  aps_frame::analysis/模型。60 个 aps-frame 测试通过，包括 Kotlin-测试 ports 以及应用中
  35-行 pinned-tool FrameTimeline 检查。
- 运行时/界面：crates/aps-app/src/frame.rs 持有 controller 状态机（异步设备/进程
  发现、有界导入、在线采集轮询、FrameTimeline 采集、association、导出、
  AI、查看器、布局 handoff）；frame/page.rs + frame/model.rs 使用运行时受限的操作
  渲染 Compose 屏幕和时间线；值{,-zh}/frame.json 携带全部 129 个 Compose 键
  （named 占位内容）及 12 个 Rust 特有的显示键。外壳将 Destination::Frame 路由到
  工作区，使其留在标题栏目标 loop 中，在变更时使 AI 失效，并增加 File > Import
  gfxinfo FrameStats / Export CSV / Export JSON。
- Capture（frame/capture.rs）：优先使用 FrameMetrics Agent socket 协议的 adb
  设备/进程发现、有界 framed batches/JankStats 字段、遵循 Compose 去重/警告规则
  的 gfxinfo 轮询回退，以及使用固定命令、API gating 和设备 temp 清理的
  有界 Android 12+ FrameTimeline 采集。Support（frame/{会话,导出,ai,产物,perfetto}.rs）：
  有界原子 JSON 会话 store（Compose 内部使用 SQLite）、Compose 精确的 CSV 和 JSON
  schemaVersion 3 exporters、绑定到现有审批对话框的仅含数值的 FrameAiContext、
  采集制品元数据，以及通过新的固定 SQL aps-perfetto::frame_timeline 查询在
  pinned trace_processor 上导入 Perfetto FrameTimeline。
- 验证：`cargo test -p aps-frame --offline` = 60 passed；
  `cargo test -p aps-app --offline` = 276 passed；
  `cargo test --workspace --offline --no-fail-fast` = 634 passed / 0 failed；
  `cargo check --workspace --all-targets --offline` 和 `cargo fmt --all --check` 通过。使用
  合成 60-帧 gfxinfo 夹具的原生运行覆盖：导入、9 个聚类中的 13 个
  deadline misses、时间线缩放/选择/悬停、Deadline-misses 筛选、AI 已冻结的载荷
  审查（摘要计数器 + 一个选中帧，不含状态/路径）、CSV 导出（61 lines，
  Compose 列）、JSON 导出（schemaVersion 3：16 摘要、37 帧、10 聚类、18
  归因键）、English + Chinese 以及空状态；截图位于 build/frame-analyzer/。
- 缺口：没有硬件，因此实时 gfxinfo/代理-socket/FrameTimeline 采集仅做了单元测试；
  会话持久化使用有界 JSON，而不是 Compose 的 SQLite 模式（仅内部格式）；制品
  缺失-capability limitations 合并到来源信息警告；导出的 JSON maps 已排序（键/
  值相同，仅 insertion order 不同）；980px 窄布局在 Compose 的 1280px 最小窗口
  以下会裁剪固定三列 body；gpui 没有 floating 悬浮提示，因此 problem-帧证据可通过
  详情复制操作获取；旧的通用 Frame 导入页面 code 现在不可达，等待清理。
### Source 树-sitter 语法高亮（阶段 1）— 2026-11-07

- 只读 Source 查看器现在使用树-sitter 做语法高亮（Kotlin、Java、XML、C、C++），不再只
  依赖 line tokenizer。使用 `tree-sitter 0.25` 以及 `tree-sitter-java`、`tree-sitter-kotlin-ng`、
  `tree-sitter-xml`、`tree-sitter-c` 和 `tree-sitter-cpp`；复用同一 One Dark 配色和
  字节-偏移词元模型，因此选择、搜索、virtualization 和复制行为不变。
- 在 regex tokenizer 绘制文件后，Parsing 在后台执行器中运行，准备好后替换 span
  set（由代次守卫保护，因此切换文件不会应用过期的树）。Syntax 树在 task 内
  丢弃：不保留 AST 缓存或整个项目的解析状态；查看器中只保留词元 spans。超过 2 MiB
  的文件或没有 grammar 的语言继续使用现有 line tokenizer。
- 语法树处理覆盖 regex lexer 无法处理的场景：多行 Java 文本区块、nested Kotlin
  区块 comments、多行 annotation/comment spans、经树验证的字符串/numbers/keywords
  以及 XML tag/属性/值 structure。本阶段不包含索引、resolver、jump、LSP 或
  memory-索引变化；semantic 导航仍使用现有 heuristic 符号路径。
- 验证：12 个 `source::code_viewer` 测试通过，包括 Java 文本区块、nested Kotlin 区块
  comments、XML/C 词元、各行的词元边界和超大回退；
  `cargo test --workspace --offline --no-fail-fast` = 654 passed/0 failed，
  `cargo fmt --all --check` 通过。原生打开 Kotlin 夹具可渲染语法颜色（截图位于
  build/source-workspace/tree-sitter-highlighting.png）。
- 本调试构建的测量结果：100 KiB 生成的 Java 文件高亮约需 ~45 ms，500 KiB 约 ~218 ms，
  1 MiB 约 ~445 ms（在 UI 线程之外；regex 通过会立即绘制）。Resident memory 仅限于
  词元 lines，临时的树受 2 MiB 解析 cap 限制；不保留各文件的或各项目的树。
### Source 有界索引和流式索引构建（阶段 2）— 2026-11-07

- 索引构建不再在 RAM 中累积每个符号。`service::refresh` 解析清单、打开快照，
  然后通过 `Storage::begin_snapshot`、`append_files` 和 `append_symbols` 写入文件/符号
  batches（64 文件或 20 000 符号）；batch 之间仍会发布 progress。旧的一次性
  `save_snapshot` API 仍供有界调用者使用。Multi-line Kotlin/Java 参数 lists 会被规范化
  为 storage signature 契约，而不是让整个工作区失败。
- 1M-符号索引构建上限不再应用于流式路径。本机真实的 AOSP_Frameworks 冒烟检查：
  51 438 文件和 1 566 133 符号在 ~303 s（调试）内完成索引构建；indexer 进程
  保持在约 ~79 MB RSS，生成的元数据 DB 约为 ~1.1 GB。在此变更之前，同一棵树会因
  `source snapshot exceeds the supported symbol bound` 失败。
- 导航不再加载完整的 `files`/`symbols` vectors。`SourceRepository` 新增有界查找
  （`file`、`files_named`、`files_with_suffix`、`symbols_qualified`、`symbols_with_short_name`、
  `symbols_in_file`），默认使用内存实现；SQLite 通过 indexed SQL 覆盖它们，resolver 使用
  这些实现。每次查找的 candidate 结果上限为 256。Schema v4 只增加
  `source_symbols_file` 和 `source_symbols_kind_name` 索引（不重写表格），因此现有
  快照仍然有效。
- 未变化的 trees 会复用内容寻址的快照，而不是重写它：AOSP 刷新变为约
  ~14 s（清单 scan + 数量），而不是完整 re-索引。Index 中途失败会删除不完整的
  快照，并保留之前的当前活动的快照。
- Method/native 短名称查找使用持久化的 `dot_name`/`native_name` 列（模式 v5）
  以及 `(snapshot_id, dot_name)`、`(snapshot_id, native_name)` 索引。v5 migration 会
  添加列，并在构建索引前回填每个已有的符号行；无需 re-索引。AOSP
  1.5M-符号快照（调试）：有 156 个 candidates 的受管理的 method 查找从 ~639 ms
  降至 ~5.8 ms。Qualified type 查找保持有界（1 candidate；打开大型元数据 DB 后的
  首次查询是 cold 页面-缓存读取，之后查询很快），文件+软件包-哈希约 ~17 ms，
  进程 RSS 从约 ~45 MB 变为约 ~48 MB。
- 旧版完整-快照读取/save APIs 不再强制 100k-文件或 1M-符号兼容性上限
  （`save_snapshot`、`files`、`symbols`）。它们仍会 materialize 完整 vectors，因此大型
  快照应使用有界查找；导航、索引构建和文件树不再依赖 capped 读取。
  移除短名称 scan 和上限后，AOSP 元数据 DB 从约 ~1.1 GB 增长到约 ~1.5 GB。
- 验证：`cargo test --workspace --offline --no-fail-fast` = 657 passed/0 failed，
  `cargo check --workspace --all-targets` 和 `cargo fmt --all --check` 通过；storage 测试
  现在会将 SQLite 查找和 resolver candidates 与内存 repository 比较，被忽略的
  `manual_aosp_streaming_smoke` 记录大型树证据。

### Source 语义导航（阶段 3）— 2026-11-08

- Settings > Configuration 现在有一个 opt-in **Semantic 导航 (LSP)** 开关，默认关闭。
  打开后会显示 inline 警告，说明将启动本地语言 server 进程，可能使用数百 MB；
  只有显式 **Enable** 才会持久化 `sourceLspEnabled=true`（设置格式 v4），**Cancel**
  保持禁用。旧设置文件加载时也保持禁用。
- 启用后，在源码查看器中 Cmd/Ctrl-点击（或 Ctrl-点击）identifier 会请求
  `textDocument/definition`；Shift+Cmd/Ctrl-点击会请求 `textDocument/references`，并在
  查看器上方显示有界 usage list（200 条目）。点击 usage 会打开它；`×` 关闭列表。
  Modifier 提示只在功能启用时显示在源码面板中。
- Servers 仅限本地：client 通过 stdio 使用 JSON-RPC，每个工作区/语言保持一个
  会话，使用 `didOpen` 发送打开的 document，并以有界超时等待。不向机器外发送
  源码文本，也不打包 server。Discovery：Java 使用 PATH 上的 `APS_JDTLS` 或 `jdtls`，
  Kotlin 使用 `APS_KOTLIN_LSP` 或 `kotlin-lsp`，C/C++ 使用 `APS_CLANGD` 或 `clangd`；
  额外参数通过 `APS_*_ARGS`。缺少 server 时显示本地化的配置提示，而不是静默
  回退。
- LSP client 处理 server 返回的 canonical 路径（例如 /private/tmp 与 /tmp）、UTF-16
  positions、Location 和 LocationLink 结果；功能禁用或应用退出时会终止 server。Tree-sitter
  仍是 highlighter；LSP 仅用于 semantic definition/参考请求。
- 验证：单元测试覆盖 identifier/UTF-16/URI 转换、用于 initialize/definition/引用
  的 scripted stdio server，以及解析 definition 的 ignored clangd 冒烟检查测试；设置警告
  和持久化已在原生应用中验证。Java/Kotlin servers 是环境安装，不属于本仓库。
  最终门槛：671 passed/0 failed，`cargo check --workspace --all-targets` 和
  `cargo fmt --all --check` 通过。
### Network 摘要绑定的最小化分页缓存切片 — 2026-11-06

- `HarSession::open` 现在会计算并保留选定 HAR 的 SHA-256 `SourceDigest`。`HarSummary` 和每个
  `EntryPage` 都暴露该摘要，使最小化 network 证据始终绑定到准确的 imported 字节。
- 条目页使用会话持有的有界的缓存，最多保存八个页面和 512 KiB 的最小化的
  cell 文本。缓存 hits 仍会重新检查文件元数据和 SHA-256；元数据或摘要变化会
  清空缓存并采取保守拒绝策略。原始 URLs、头部、cookies、bodies 和解析器错误仍排除在
  所有 cached 值之外。
- 这移除了常见分页路径上重复的相同页面 scans，并关闭了发布的相同时间戳
  源码 replacement 间距。这还不是持久化的内容寻址的 HAR 索引，也不是 Kotlin
  完整保真的 NetworkAnalyzer 对齐实现。

本切片的最新静态证据：`cargo fmt --all`、
`cargo check --locked --workspace --all-targets --offline` 和 `git diff --check` 通过。未执行
测试、HAR 运行时导入、GUI 或软件包验证。

### Memory 有界查询响应缓存切片 — 2026-11-06

- `MemorySession` 现在只为现有的 `histogram`、`instances`、`detail`、`dominators` 和
  `findings` operations 缓存已验证的有界载荷。键是准确编码的 operation/argument
  值，因此堆、页面、筛选和对象身份仍属于查找契约。
- 缓存上限为八个条目和 2 MiB 的编码后的响应载荷；worker 失败、取消、
  超时和 close 会清空它。Heap/引擎或原始 HPROF graph 不会进入 UI 进程或缓存。
- 缓存 misses 仍会执行真实的 worker 查询，直方图/发现项仍可能在子进程内重新计算完整
  引擎视图。Persistent 查询索引、子进程 RSS 配额、相同输入对齐和运行时压力测试
  证据仍未完成。

### Memory 离线工作区重建 — 2026-11-07

- GPUI Memory 页面现在使用快照标签页和绑定证据的分析工作区，而不是旧的
  通用占位内容。Class 搜索/排序会在隔离的 worker 中分页前针对完整直方图计算；
  生成的摘要和堆 segments 保留整个快照的 meaning。
- Class 行可深入到实例和对象详情。Inspector 映射有界字段、GC-根节点路径、
  inbound 引用和 truncation 提示。Dominator 行和四类 heuristic 发现项仍可导航。
  Compare mode 从两个独立加载的 HPROF 会话计算有界的类-level 数量/浅层/保留
  差值；从不比较对象身份。
- 不支持的实时时间线/GC markers、原生堆、Bitmap Dump、sampled ART、LeakCanary 和
  不可用 HPROF 指标保持为明确缺口，而不是模拟证据。Heap/范围/regex/preset
  筛选、pin/forward 历史、映射导入、auxiliary 制品适配器、完整截图
  对齐和打包后的 GUI 验收仍未完成。

静态证据：集成后 `cargo fmt --all -- --check`、
`cargo check --locked --workspace --all-targets --offline` 和 `git diff --check` 通过。根据
rewrite 验证策略，未执行测试和原生 GUI 交互。

Memory 切片的最新静态证据：`cargo fmt --all`、
`cargo check --locked --workspace --all-targets --offline` 和 `git diff --check` 通过。未执行
HPROF worker、测试、GUI 或软件包验证。

### Memory Profiler 与 Compose 对齐的工作区 — 2026-11-07

- 通用 Memory 导入表格已替换为 typed GPUI 工作区，对齐 Compose Memory Profiler 页面：
  命令工具栏（Import HPROF、采集操作、Overview/Classes/Dominators/Diff 标签页、
  Bitmaps/原生 Heap/ART Samples/Leak Analysis 条目、Mapping、AI 分析）、快照标签页、
  证据/警告条以及 task/状态栏。
- Classes 视图现在对齐 `MemoryInvestigationWorkspace` + `MemoryProfilerClassListView`：primary
  筛选栏（堆、类范围、leak 筛选、arrange-by、类搜索、match case、regex）、
  八个 KPI 卡片（Leaks、Duplicate Bitmaps、Classes、Count、原生 Size、Shallow、Retained
  原生 Size、Retained）、堆-distribution 栏、可调整大小的 classifier 树表格
  （堆/软件包分组、lazy expansion、可排序的 Instances / 原生 Size / Shallow /
  Retained / Heap 列、leak 和 duplicate-bitmap 着色）以及 500-行有界的分页的
  实例 list。选择实例会内联打开对象检查器：字段 / References 标签页、
  sorted 行、带 back/forward 历史的对象 drill-in、pin/unpin、复制对象 id、字符串
  预览、truncation 提示、nearest-GC-根节点-仅筛选和 200-element array 分页。
- Dominators 遵循 `MemoryDominatorWorkspace`：整个快照的 KPI 卡片、堆 distribution、
  190 px 筛选面板（搜索、match case、regex）、带子进程页面和 leaf/未知 totals 的
  lazy-expanded 支配者树，以及 330 px 对象检查器。Diff 在类-level A/B 比较
  表格上方增加整个快照的 KPI/distribution（Class、A/B 数量、差值、A/B 保留、
  Δ 保留），使用 signed 差值，不比较 cross-dump 对象身份。Leak Analysis 展示
  HPROF heuristic 发现项（leak suspects、Activity / Fragment candidates、生命周期对象），
  并明确标记 LeakCanary 代理/live 数据不可用，而不是模拟。Bitmaps、原生 Heap 和
  ART Samples 使用 Compose 形态的页面以及空/不可用状态，但其 Rust 制品
  适配器和实时采集路径尚未实现。
- `aps-memory` 中新增 typed domain：`model.rs`（快照/元数据/类 stats、发现项、
  实例/详情/支配者页面）、`presenter.rs`（Compose presenter 对齐：堆和软件包
  分组、范围/leak/搜索筛选、摘要 null-vs-zero rules、duplicate-bitmap 身份、
  classifier 排序和 dashboard ordering）以及不依赖外部库的有界 regex subset。Worker 新增
  一次有界 `snapshot` 读取（元数据 + all-堆直方图 + 各堆的 histograms + 每种发现项
  kind 的前 200 行，16 MiB 预算，按各堆的回退），并提供 typed 500-行
  实例/支配者页面和 200-行 array/对象详情页面。Object ids 按 unsigned 64-bit
  值处理；缺失指标保持不可用。
- 验证：对 changed 文件运行 `rustfmt`、`cargo check --locked --workspace --all-targets
  --offline`、`cargo test -p aps-memory`（38 领域测试）、`cargo test -p aps-app memory::`
  （15 测试）和 `git diff --check` 通过。使用真实 8-字节-id HPROF 夹具（按引擎测试
  夹具 shape 构建）的 native 窗口截图覆盖 Classes + expanded 树 + 实例
  list + 对象检查器、Overview、Dominators、Diff、Leak Analysis、Bitmaps、原生 Heap 和
  ART Samples，位于 `build/memory-profiler/`（对齐 spec 位于
  `build/memory-profiler/parity-spec.md`）。
- 剩余缺口：live 设备采集（Dump Heap / Capture 原生 Heap / Record ART Samples / Dump
  Bitmaps / Inject Agent）和映射导入明确禁用而非模拟；LeakCanary 代理和 live monitoring
  尚未实现；Rust 中不存在原生 Heap / ART Samples / Bitmap 制品分析适配器和
  bitmap 采集会话；发现项表格限制为 worker 的前 200 行（totals 精确）；500 行
  之后的支配者子进程页面没有分页控件；比较 UI 需要两个 open 快照才
  有数据（已在不可用状态验证）；完整截图像素比较、打包后的发布
  和设备验收仍未完成。


### Memory Profiler Compose 像素对齐及映射与导出 — 2026-11-08

- Memory 页面现在使用 Compose 工作区风格的 ViewerColors，而不是外壳的平面式词元：
  面板 = 深色 base 上叠加强调色 8%，强调色着色的边框/分区表面样式、primaryContainer
  选择、Material 错误/tertiary 行着色，以及深色配色的精确文本/警告/错误/
  成功值。共享 primitives 位于 import/memory/widgets.rs。
- 工具栏、快照标签页、证据条和状态栏遵循 Compose 指标：24 px outlined
  按钮（选中视图使用强调色填充）、带 capture/view/aux groups 的 40 px 命令栏、带
  末端 close 控件的 28 px 快照标签页、13 px 证据文本以及 29 px 状态页脚。
- Classes：36 px 筛选栏（outlined 下拉框选择器）、可见的 18 px 复选框和 30 px
  outlined 搜索字段；44 px KPI 条；24 px 分区-背景表格头部，带强调色
  列 grid lines；20 px 行中的 13/16 紧凑正文字体文本；深度缩进、强调色折叠箭头、
  名称-列 edge 的 leak 警告 markers、错误/tertiary 行着色、统一的面板行
  （Compose 工作区 style 将两种行表面样式都映射到面板，因此没有斑马条纹）；实例面板
  和对象检查器共享 MemoryClassesPane 的实例标题/头部行，而不是在全宽的
  标题栏下方堆叠检查器。
- Inspector：标题/标签页/nearest-根节点行为 28 px，带 2 px 强调色 underline；没有非 Compose
  的历史/复制/pin 工具栏或指标 line；工具栏-背景头部带列 grid，
  13/16 行、disclosure + colored type badges、字段和参考标签格式、ASCII-hyphen
  不可用指标、capability/证据门控的列（HPROF 不显示 native 列），
  字段不带根节点行，References 带根节点 + inbound 引用。
- Dashboard、Dominators、Diff、Leak Analysis 以及 Bitmap/原生 Heap/ART 的空页面使用
  同样的词元重新设置样式：58 px Overview 指标卡片、44 px KPI strips、24 px 头部、
  20 px 紧凑正文字体行、带强调色标签的指标和 Compose badge/面板颜色。
- 功能对齐：
  - Mapping 导入：aps-memory open_with_mapping（原始/obfuscated -> original）、有界 mapping.txt
    解析器（8 MiB / 200k 条目）、使用 映射 重新打开 当前活动的 快照、保留 标签页身份，
    并在 证据 条 显示 映射 状态。加载 快照 后，File > Import > ProGuard/R8 Mapping
    和 工具栏 Mapping 操作 可用。
  - HPROF 导出通过 File > Export 提供：原始 HPROF（摘要-已验证的流式复制）、类
    直方图 CSV、有界 快照 JSON、有界 类-实例 CSV、对象 investigation JSON、
    类 diff CSV 和 investigation 报告 Markdown；native/ART/standard-HPROF/bitmap 行 仍
    禁用。所有写入均为 仅新建的、不覆盖现有内容的 且有界。
  - Diff 比较现在默认为 A = newest 非活动的快照、B = 当前活动的标签页，忽略过期的
    pairs，并在 当前活动的 标签页 变更时使已发布的 比较 失效。
- 验证：cargo check --locked --workspace --all-targets、aps-app 测试（memory 模块 suites）、
  aps-memory 映射/presenter/快照测试、rustfmt 和 git diff --检查。使用真实 8-字节-id
  HPROF 夹具的 native 窗口截图覆盖 Classes（树 + 实例 list + 对象
  检查器字段和 References）、Overview、Dominators、Diff、Leak Analysis、Bitmaps、
  原生 Heap 和 ART Samples，位于 build/memory-profiler/（v2-*.png、mapping-check*.png、
  refs-check.png）。
- 剩余缺口：live 采集（Dump Heap / Capture 原生 Heap / Record ART Samples / Dump Bitmaps /
  Inject Agent）仍没有 Rust 采集适配器，保持可见禁用；standard-HPROF 转换、
  native-堆/ART/bitmap 制品分析、LeakCanary 代理/live monitoring、inline nested
  检查器 expansion 和 draggable 检查器列 resizing 尚未实现；发现项表格仍
  限制为 worker 的前 200 行；500 行之后的支配者子节点没有分页控件；在
  打开第二个快照标签页前，比较表格显示空状态。



### Frame 离线工作区重建 — 2026-11-07

- GPUI Frame 页面现在遵循 Compose 工作区 structure：七个 KPI 卡片、源码/窗口/deadline
  控件、Canvas-渲染后的时长时间线，以及下方的聚类/problem-帧/选中-帧
  分析行。Timeline 分页、有界的缩放、悬停和选择使用已发布的查询页面，
  不再为每个源码帧创建一个 element。
- 窗口和 deadline 筛选、range 分页、缩放现在通过后台发布路径执行。
  已发布的 typed `FramePage` 一致驱动 KPI、时间线、表格、详情和导出投影；
  失败或取消的查询会恢复之前发布的筛选/range 状态。已移除之前的偏移-重置 bug
  和同步的渲染-time 完整-会话查询。`FrameSession::page` 现在只 materialize 请求的行，
  并在一次遍历中计算 filtered 摘要。
- Selected-帧证据包含源码、IDs/时间戳、时长、带来源信息的 known
  预算、deadline/missed-VSync 状态、flags、largest 阶段、所有可用阶段计时、原始
  来源状态标签、跨页面的 previous/next 导航和 clipboard 复制。Problem 行
  来自完整的 filtered 结果，而不只是可见的时间线页面。连续 deadline misses 形成只用于
  证据的聚类，带有界行和时间线 markers；不推断根节点-cause 归因。
  Unknown 预算保持不可用。对于 standalone gfxinfo 导入，Platform Jank/JankStats
  reasons、当前活动的刷新速率、聚类根节点-cause 归因、online 采集、Layout
  Inspector correlation 和 Perfetto association 仍明确为不可用状态。

本切片的静态验证使用格式检查、locked offline 所有目标 compilation 和 diff 检查。根据
rewrite 验证策略，Tests、原生 GUI 交互、真实设备采集、大输入性能
测量和打包验收仍是独立门槛。

Frame 切片的最新静态证据：`cargo fmt --all -- --check`、
`cargo check --locked --workspace --all-targets --offline` 和 `git diff --check` 通过。未执行
测试、原生 GUI、真实设备采集、大输入性能测量或软件包验证。

### 绑定源码的 AI 候选切片 — 2026-11-06

- Source 工作区现在会在用户显式选择确定性的 resolution candidate 后暴露有界的
  `AnalysisRequest`。Request 携带 candidate ID、工作区 ID、不可变的快照 ID、内容
  摘要、relative 路径和 validated line range；可选的经哈希校验的 snippet 会裁剪到 AI
  上下文预算内。
- 外壳 AI preparation 现在包括 `SourceWorkspaces`。仅选择目录、解析符号或打开
  源码文件不会创建 AI 请求。只包含被点击的 candidate，且在传输授权
  之前仍需要现有的显式源码上传同意。
- 未增加工作区-wide upload、远端源码 retrieval、凭据发现或源码正文
  持久化。如果快照身份、内容哈希或确定性的 resolution 发生变化，
  candidate 验证会采取保守拒绝策略。

本切片的最新静态证据：`cargo fmt --all`、
`cargo check --locked --workspace --all-targets --offline` 和 `git diff --check` 通过。未执行
提供方请求、源码 upload、测试、GUI 或打包后的验证。

### Settings 子页面和通用偏好设置切片 — 2026-11-06

- Rust 设置现在使用向后兼容的版本 2 偏好设置格式。现有版本 1 文件会在
  内存中迁移，并为新字段使用默认值；写入仍是原子的且由 Rust 所有。持久化的通用
  偏好设置包括语言、浅色/深色主题、显示 scale（100/110/125%）、导航
  标签可见性、强调色 preset 和 Simpleperf 采集时长/频率默认值。
- GPUI Settings 路由现在有 General、Configuration、Simpleperf、AI 和 About 子页面。General
  控件会立即应用并持久化主题/强调色/字体 scale；Simpleperf 步进器持久化采集
  页面创建时使用的有界采集默认值。Configuration 报告真实的 Rust 数据/元数据/
  采集 roots；AI 报告 registered loopback 目录，并将凭据排除在设置外；
  About 报告 Rust/GPUI 构建身份和 migration 状态。
- 本切片不宣称与旧版 Android SDK/AGI 配置、keychain/提供方持久化、custom
  颜色选择器或每个 Compose 性能分析器 setting 对齐。这些控件有意作为有界状态或
  future 边界可见，而不是产生未实现的副作用。

本切片的最新静态证据：`cargo fmt --all`、
`cargo check --locked --workspace --all-targets --offline` 和 `git diff --check` 通过。
`cargo run --locked -p aps-app` 在启动后保持运行 15 秒，没有 panic 或运行时错误；未执行
测试或外部操作。

### Layout Inspector Compose 布局移植切片 — 2026-11-06

- GPUI Layout Inspector 现在对齐 Compose `LayoutInspectorMainPage` 组合，而不是之前的
  最小三列页面：320 / flexible / 300 dp 面板行，带 7 dp 分隔条和 29 px 面板头部；
  240 dp 发现项条，带严重级别标签控件和表格头部；以及 29 px 状态栏。
- Pane 内容来自现有的 offline 归档投影：层级树行（20 px 行、14 px 缩进、
  对 id、类和资源名称的 collapse plus 搜索、每行的可见性开关）、带
  设备帧和 sky-blue 选中-red 边界的适配/缩放预览画布、带 Layout/Drawing/
  Accessibility categories 的节点属性，以及发现项头部中的 AI 条目。
- 不产生 live 采集、ADB、截图 bitmap、语义、发现项 detection 或时间线数据。
  这些区域渲染有界的仅布局状态（“offline”、“not captured”、zero 发现项数量），
  而不是合成内容。页面保留单一标题栏背景 rule，区域之间只使用边框
  和强调色着色分隔。
- 共享页面标题行和独立归档-导入行仍省略。Compact Inspector HeaderToolbar
  提供 offline 状态、禁用的 live 采集控件、可用的窗口 switching 和工作的面板
  可见性开关。Archive 导入仍位于 File（Cmd/Ctrl+Shift+O）下，native Layout Inspector
  菜单保留窗口 switching 和面板可见性操作。错误和进行中的导入 feedback
  仅在相关时显示。
- 外壳对 Layout Inspector 路由隐藏共享页面头部，并让页面占据完整高度，与 Compose
  页面一致；Compose 页面从其 40 px 工具栏开始。

本切片的最新 static/visual 证据：`cargo build --locked --offline -p aps-app` 通过；使用本地
one-帧夹具的调试运行验证了深色/浅色主题下的空状态、层级树、画布、
属性和发现项区域。未执行测试，也不宣称设备、导出、截图-解码或
打包后的验证。

### 专用 Network Profiler 界面和全量 HAR 查询 — 2026-11-06

- 已用 `aps-app/src/import/network.rs` 替换未完成的通用 Network branch，遵循 Compose 的 40dp
  工具栏、五个指标卡片、覆盖范围条、活动区域、Requests/Domains 标签页、八列
  请求表格和可滚动请求详情。Network 不再有外壳的重复页面标题/AI 行；自身
  工具栏打开相同的延期的 AI 预检操作。JSON/CSV save/复制保留现有有界且不覆盖的
  导出实现。
- 实际页面宽度 container 查询在 1000dp 以下堆叠表格/详情，在该断点以上使用
  并排面板。Header 和行共享可横向滚动的 900dp-最小表格；表格和详情独立
  垂直滚动。行是可通过键盘聚焦的按钮，带证据 accessibility 标签。English/
  Chinese 文本位于成对的 `network.json` 资源目录，并遵循共享主题/排版
  词元。
- `aps-network` 现在提供有界的最小化的会话索引以及 `EntryQuery`/`query_entries`：
  状态/method/搜索筛选和稳定的时长或响应-大小排序会在分页前作用
  于整个选中 HAR。UI 显示 matching/会话数量，并按 original 条目索引重新绑定
  选择。来源元数据/摘要验证和取消仍有效；失败或已取消的
  工作保留之前发布的页面/查询状态。
- 真实中位数和 P95 使用有限的非负 HAR 时长通过 `(n - 1) * p` linear interpolation
  计算，并统计缺失/无效数量。百分位数在不可变的 UI 会话内复用，而不是每个
  页面重复排序。HTTP 4xx/5xx 不会被重新标记为失败 calls；缺少证据时，outcome、
  覆盖范围和完整度保持 Unknown。
- 现有 `aps-network-rust-minimized-v1` 边界不变：不暴露 URLs/hosts、原始时间戳、
  头部、cookies、bodies 和任意 methods。因此活动/domain/phase/内容区域
  显示不可用/不-保留状态，而不是合成数据。实时 Agent 采集和 HAR 导出
  仍禁用。这不是完整 Network 功能对齐，也不声称与 Compose 达到 visual/像素 equivalence。
- 增加了整个会话的多页查询、稳定的排序、未知值、percentile interpolation、
  相同时间戳源码 replacement、取消、no-密钥 egress、响应式断点和 UI
  numeric 证据的回归测试源码。独立只读审查发现并促成了仅键盘
  选择、self-satisfying 布局断言和 repeated percentile 工作的修复；focused
  复检报告无阻塞项。

本次续工作的验证仅包括格式/static/compile/构建。根据用户的 rewrite 策略，Tests 仍未执行；
不宣称原生视觉、运行时 HAR 导入、导出交互、提供方、设备或打包后的
验收。Focused Clippy 完成但有现有工作区警告；没有警告指向新的 Network
UI/后端文件。证据命令和日志：

```text
cargo fmt --all --check
cargo check --locked --workspace --all-targets --offline
cargo clippy --locked -p aps-network -p aps-app --all-targets --offline
cargo build --locked --offline -p aps-app
git diff --check
/tmp/aps-network-check.log
/tmp/aps-network-clippy.log
/tmp/aps-network-build.log
```

### Settings 参考形态 UI 和偏好设置接线 — 2026-11-07

这取代了早先的五子页面 Settings 切片，**不取代**最终的功能/视觉对齐门槛。

- 新增六分区 176dp 设置导航和内嵌参考图标；移除旧的 technical-目录
  卡片，并扩展 About 复制。General 现在包括 System 语言/主题、全部六种显示 scale
  choices、native sidebar-标签开关、八个参考强调色 presets、HSV/rainbow hue
  控件、立即验证的 hex 草稿和重置。主题变化遵循 OS appearance；custom ARGB accents
  注入共享工作区颜色。Rem scaling 会在 startup 和变更时恢复；显式-像素性能分析器
  表面样式仍是缺口。
- 新增 Android SDK Browse/Apply/Clear，支持路径验证、canonicalization、有界独立的
  `android-sdk.conf`、原子的 replacement，以及在一个 transaction 下发布运行时 ADB。SDK
  设置在新建 locators 时优先；environment-已注入的 locators 保持确定性的。Settings
  导航不会启动 ADB 或采集。现有 operations 保留其 planned 可执行文件。旧版
  CPU/HAR 引擎在此明确不可用。
- 新增 Layout Inspector 视图/层级树、采集归档和边框-颜色卡片。Invisible
  层级树筛选、索引/资源 IDs/layer 按钮、边界、最小面积/Z 序 hit
  testing 和三个 ARGB 颜色已进入真实 offline 工作区。每个边框 preset 只影响自身字段。
  预览工具栏的边界开关会往返同步到偏好设置。Rules 适配器存在前，Findings
  保持为空。Archive multiplier 是 future 采集偏好设置，绝不会放宽固定的 172 MiB/156 MiB
  offline 导入限制。
- 新增 Simpleperf 的六组卡片、有界默认值和 native 悬浮提示 mode。新增默认值只应用于
  未修改的字段，并在已冻结的/running 采集期间排队。设备事件探测、采集
  templates、Period/范围/CallGraph changes 和 Firefox 查看器引擎仍明确禁用。
- 新增带持久化的 Provider/Agent/Skill/Policy 标签页的 fixed-标题 AI 设置。Provider 面板
  编辑并验证实际的会话内的目录输入，并在预检/running 请求期间锁定它们。
  它不持久化凭据、不注册任意端点、不刷新远端模型，也不发送请求。
  Extension import/trust 和策略/报告/历史控件仍因明确原因禁用；该 UI 不宣称支持
  Codex/Claude 或软件包-management。
- Version 3 设置保留原子的写入，拒绝损坏的/未知字段，并以默认值接受 v1/v2。
  新增兼容性、边界、颜色/HSV、SDK 文件 safety 和 Inspector hit/可见性 helpers
  的回归测试源码。根据 rewrite 策略，这些来源已编译但**未执行**。

新鲜证据：`cargo fmt --all -- --check`、`cargo check --locked --workspace --all-targets --offline`、
`cargo build --locked -p aps-app --offline` 和 targeted `cargo clippy` 均完成；rewrite 中仍有
Clippy/compiler 警告。Whitespace 验证包括 untracked Rust 文件，而不只是
`git diff --check`。原生 GUI、截图 diff、真实设备/提供方、打包和测试
验收均延期。在这些缺口存在时，不宣称设置行为完整或达到像素对齐。

### Home 参考形态仪表盘 — 2026-11-07

- 用两个真实指标、四个快捷键、十一个有序模块卡片（Source Workspaces 最后）、
  五列最近记录历史和 date/设备侧栏替换旧的 welcome/import/migration splash。Home 不再
  继承通用的页面标题/AI/captured-CPU 条。原生 File 操作复用 Home 导入处理器。
- 共享主题词元和成对的 English/Chinese `home.json` 目录驱动 presentation。
  实际容器布局保留 4dp 工作区/缺口、320dp 侧栏、900dp 双列阈值、
  92/78dp 卡片以及参考主列 grid 断点。整个页面垂直滚动；窄窗口的
  最近记录表格与表头/表体一起横向滚动。
- Shortcut 点击只负责导航，显式 Trace/HPROF native 文件选择器除外。Picker 取消或
  busy 目标不会改变路由；只有选择受支持的普通文件才会进入目标页面及其原始
  导入路径。
- Home 观察共享 ADB 发现，不增加 polling 或选择 side effects。Loading/失败/
  成功-空各不相同；online KPI 排除 offline、unauthorized、no-权限和未知
  设备，而列表仍保留它们。
- CPU 最近记录发现使用由 Rust 管理的元数据、只读不可变的 SQLite、严格 schema/索引
  检查以及文件/journal 前后验证。缺少元数据时显示空且不创建它；non-checkpointed
  WAL/journal 和无效索引采取保守拒绝策略。最多检查 200 会话，以获得六个可用快照
  条目。Passive 发现只统计快照；显式 Open 委托给现有完整 binding/摘要/重新打开
  flow。进入 Home 不会进行完整性能剖析文件解析，也不会把文件/索引/导入 time 重新标记为
  采集 time。
- 范围缺口：Layout、Trace 和 Memory 的持久化的最近记录适配器尚不存在于 Rust。页面会
  明确描述仅 CPU/部分覆盖范围，包括空状态。这不是完整的 four-源码历史
  或原生视觉对齐。
- 新增/编译了响应式阈值、模块/快捷键路由、表格 weights、本地化的 dates、
  设备状态/错误 separation、first launch 不使用写入、损坏的/缺失制品、
  不可变的只读 database 行为和 required 索引的回归测试源码。未执行测试。

验证证据仅为格式/static/所有目标 compilation 和应用构建；GUI/像素 matching、键盘
交互、运行时导入、真实设备和打包验收仍延期。日志：
`/tmp/aps-home-check.log`、`/tmp/aps-home-clippy.log`、`/tmp/aps-home-build.log`。

### Network 界面还原续作 — 2026-11-07

这建立在 11 月 6 日的专用 Network 页面之上，不是第二个通用性能分析器实现，也不
是完整 capture/data 对齐声明。

- 将 main 请求控件重做为参考形态的标签页、outcome/数量和 elastic 搜索，在
  在实际 1000dp 页面宽度断点以下使用两行 primary 行。现有整个会话的 HTTP
  状态/method/排序控件位于可展开 secondary 区域；折叠时当前活动的筛选仍按数量可见。
  Search 草稿和原始查询/分页语义保持不变。
- 表格表头/表体现在共享参考的八个列宽权重、900dp 最小宽度水平
  视口、semibold methods 和 HTTP 4xx/5xx 错误色调。Wide 面板保持 1.8:1，stacked 面板
  保持 1:1.2 比例。
- 工具栏导出 ordering 以及禁用原始-资源包/HAR/实时采集表面样式遵循参考，
  并提供明确的不可用 reasons。页面导出保持准确的最小化的-页面范围；不暴露原始
  内容来填充 UI。
- Detail 使用参考形态的 36dp 标题/URL 副标题、method/outcome/字节条、置信度
  legend、HAR-条目卡片、带 74dp 时长列和不可用 badges 的十个计时 phases，
  以及四个独立的表头/表体内容区块。不宣称已验证 exchange chain、measured phase
  计时或 captured 空内容。Copy-详情会重建有界最小化的 whitelist。
- 修复 short-窗口 collapse：外壳不再提供第二个垂直 Network 滚动容器，工具栏保持固定，
  natural 页面内容滚动，证据面板具有可用的最小 heights。只有详情标题固定；
  摘要、legends、phases、内容、复制操作和范围作为一个单元滚动，因此 explanatory
  文本不会把证据视口压缩为零。
- 修正 transfer 指标：完全未知大小显示 `—`；部分覆盖范围明确为 known subtotal；
  known zero/成功的空会话保持 zero。
- 审查发现既有 HAR 源码-身份 race：将路径名单独哈希可能与 parsed descriptor
  绑定到不匹配的内容。Adapter 现在对 cancellable 哈希/rewind/解析/re-哈希使用同一
  descriptor，并验证 handle 和路径元数据。Unix 还检查设备/inode/ctime。这是变更
  detection，不是 filesystem 沙箱，也不保证 privileged-writer safety。
- 回归测试源码覆盖布局 floors/自然高度所有权、精确列宽权重/状态
  色调、未知/部分字节摘要、控件/草稿行为、详情 phase order/scrolling/
  最小化的复制、相同时间戳源码 replacement 和 hashing 取消。仅编写/编译，未执行测试。

验证命令：`cargo fmt --all --check`、locked offline 工作区所有目标检查、focused
`aps-app`/`aps-network` Clippy、`aps-app` 构建以及 whitespace 检查。日志：
`/tmp/aps-network-ui-check.log`、`/tmp/aps-network-ui-clippy.log`、`/tmp/aps-network-ui-build.log`。
原生视觉、键盘、真实设备、live 采集、提供方和打包验收仍未完成。
`aps-network-rust-minimized-v1` 无法恢复 domain/活动/phase/内容数据；其明确的不可用
状态保持不变。

### Battery 参考形态工作区 — 2026-11-07

Battery 路由现在拥有专用工作区，而不是复用共享的导入摘要/表格。当前 Compose
Battery main 页面、会话工作区、结果和选中-运行面板作为参考；Compose
实现未修改。

- 包括 compact fixed 工具栏、会话侧栏、实际宽度响应式摘要卡片、源码
  归因、加权六列运行表格、八个资源标签页、证据检查器、thermal/性能
  窗口、诊断信息以及可复制的 fixed 页脚。统一使用 Studio 颜色、排版和 4dp
  间距。外壳不再向 Battery 添加重复的标题/AI 行或第二个垂直视口。
### Layout Inspector 工具栏和窗口菜单对齐 — 2026-11-07

Layout Inspector 头部和 native 窗口菜单现在遵循 Compose 页面，而不是早期仅支持
offline 的近似实现。Compose 仍是行为基准；未修改 Compose 源码。

- Capture 目标是 Compose `DropdownSelector`（强调色轮廓、强调色标签、末端折叠箭头），
  标签为 `目标: 前台应用` / `Target: Foreground app`，包含 `前台应用` 和 `系统界面` 菜单 items、
  选中 mode 的检查，以及跟随 choice 的标签。Offline 归档适配器没有 live 采集，
  因此选择只是 presentation 状态，不会启动设备 operation。
- Manual/automatic scan 是单个 `SegmentedSelector(COMPACT_OUTLINED)`-style
  控件 (`刷新` / `自动扫描`, 强调色轨道, 选中强调色填充, 细线
-  separators），固定在工具栏 right end、末端分隔线之前，对齐 Compose 的
  `Refresh | Auto scan` placement。两个 segments 因页面声明 live 采集不可用而继续
  显示禁用。
- Connection dot 和标签现在共享 Compose 警告色调；面板开关使用 Compose
  `PanelToggleButton` 指标（26 x 21、3 px 圆角、可见时 18% 强调色着色、无轮廓），
  不再使用 boxed 图标按钮。
- 原生窗口菜单现在是 Compose skeleton：File、View、Tools、Help 以及 macOS 应用
  菜单，并支持路由-aware 更新：外壳会在导航、面板可见性 changes 和
  布局-偏好设置 changes 时重建菜单。在 Layout Inspector 路由中，File 发布 `打开…` 和
  禁用的 `导出归档…` 占位内容（归档导出尚未实现）；View 发布三个面板开关
  和五个视图 options，checked 状态绑定共享的 `LayoutPreferences`；Tools 发布禁用的
  `自动扫描`、从偏好设置读取 checked 状态的 `显示层级 ID` 以及 `下一个窗口`；Help
  打开 AI 工作区。其他路由保留现有 File import/export 操作，并显示禁用的
  View/Tools 菜单，因此没有移除既有操作。
- 两个目录都包含语言环境键：`inspector_capture_target`、`inspector_refresh`、
  `inspector_auto_scan`、`shell_menu_view`、`shell_menu_tools`、`shell_menu_help` 和
  `shell_menu_open`；面板-开关字符串现在使用 Compose 的
  `隐藏左侧栏/隐藏底部栏/隐藏右侧栏` wording。
- 源码检查回归测试已更新：native 菜单操作在 `shell.rs` 的菜单 builder 中
  断言，`main.rs` 不得直接安装菜单。

证据：`cargo fmt --all`、`cargo check --locked --workspace --all-targets
--offline`、`cargo build --locked --offline -p aps-app`，以及一次原生调试运行；截图覆盖
空页面、打开的采集目标下拉框（两种 mode）、View 菜单开关前后的 checkmarks、
Tools/Help 菜单和 non-检查器 File 回退。原生运行使用工作区-本地的
`APS_RUST_DATA_DIR` Rust 设置复制，因为代理沙箱无法写入真实的
应用-support 目录；通过该方式验证了设置读取、菜单 rebuilding、偏好设置
持久化以及最终无 banner 的 UI。未执行测试；实时采集、归档导出和
节点-导航 tools 仍未实现。

### Layout Inspector 画布头部模式 — 2026-11-07

预览面板现在提供 Rust 移植中缺失的两个 Compose 画布-头部控件。Compose 仍是
行为基准；未修改 Compose 源码。

- `仅应用` 标签控件默认开启，并将预览源码裁剪到当前活动的窗口边界，再限制在显示
  内，行为与 `CanvasGeometry.sourceRect` / `CanvasWindowSource.sourceRect` 完全一致；无效
  或空边界回退到完整显示。Dimensions 显示源码大小（应用窗口开启时为
  `1080 × 2160`，关闭时为 `1080 × 2400`）；帧 corner 圆角遵循 Compose（开启为 `12`、
  关闭为 `2`）；指针命中测试在 `canvas_hit` 前映射回显示 space，因此状态栏
  偏移以下的选择仍准确。
- `小面积优先 / Z 序优先` 标签控件切换持久化的 `LayoutPreferences.hit_smallest`，复用现有
  `InspectorPreferencesChanged` 外壳持久化和最小面积命中顺序。Compose
  标签与着色保持一致：仅应用 on/off 使用 18%/8% 强调色表面样式，命中顺序使用 14%，
  bold 强调色 4dp 标签控件使用 8 x 4 内边距。
- 两个目录都新增语言环境键：`inspector_app_only_on`、`inspector_app_only_off`、
  `inspector_small_area_hits`、`inspector_z_order_hits`。
- 增加仅用于调试的 `APS_RUST_OPEN_INSPECTOR` launch seam（与现有
  `APS_RUST_OPEN_HPROF` seam 并列），使原生视觉检查可以加载真实归档 zip，而不必
  驱动 platform 文件选择器。Release 构建永不编译该 seam。
- 已编写但未执行（遵循验证策略）的回归测试覆盖：
  `preview_source_crops_to_app_bounds_only_when_app_only_is_on`,
  `preview_source_clamps_app_bounds_and_falls_back_when_empty`，以及针对两个标签控件 ids /
  `preview_source` 的源码检查断言。

证据：`cargo fmt --all --check`、`cargo check --locked --workspace
--all-targets --offline`、`cargo build --locked --offline -p aps-app`，以及使用本地生成的
one-帧归档进行的 native 窗口采集：
`build/layout-inspector-toolbar/preview-canvas-chips.png`（仅应用模式开启，1080 × 2160）、
`preview-app-only-off.png`（关闭，1080 × 2400）和
`preview-hit-order-z.png`（`Z 序优先`）。未执行测试；live 采集、截图显示和
节点导航仍未实现。

### Layout Inspector 属性对齐、截图画布和缩放浮层 — 2026-11-07

Node 属性面板现在对齐 Compose 的详情投影，而不是初始的结构子集；导入的截图会在
画布中渲染，缩放控件已移到 Compose 的右下角浮层。Compose 仍是行为基准；未修改 Compose 源码。

- `aps-layout-inspector` 现在保留这些分区显示的有界数据：节点 `text`（<=64 KiB）、
  `semanticsRole` 和协议中的 `ViewAttributes` 字段。`rawProperties`/`semanticProperties`
  按节点限制（256 个条目、名称 512 字节、值 8 KiB），同时受整个归档 4 MiB 的预算限制；
  后续节点保留结构属性，但不保留属性映射。截图 PNG 字节在现有摘要和大小验证后保留，
  用于宿主侧解码；适配器仍不解码像素。crate README 中旧的“不保留运行时值”边界已相应更新。
- 属性面板分类现在为 Overview / Layout / Drawing / Accessibility / 证据，各分类都渲染
  Compose 的分区和行：IDENTITY（类、ID、资源、文本）；LAYOUT（边界、尺寸、本地布局边界和
  尺寸、可见性、树深度、直接子节点、后代、子树深度、布局宽高、参数类型、测量及最小尺寸、
  内边距、外边距、滚动、是否请求布局）；DRAWING（alpha、z、抬升、平移、旋转、缩放、轴心、
  背景/backgroundColor/前景、clip 边界/子节点/toPadding、是否不透明、
  willNotDraw、硬件加速、图层类型）；INTERACTION（内容描述、语义角色、
  启用/clickable/longClickable/focusable/focused/选中）；EVIDENCE（渲染风险：过度绘制
  估算、子树复杂度、隐藏后代、混合、图层成本，以及排序后的 RAW PROPERTIES）。缺失值渲染
  为“—”，浮点数使用 Kotlin `Float.toString` 格式，尺寸将 -1/-2 映射为
  MATCH_PARENT/WRAP_CONTENT，值使用 11 sp 等宽字体，风险行使用 Compose 的警告及信息色调。
  分区标题为 29 dp，带细分隔线。五个分类标签页使用与头部 Refresh/Auto scan 控件相同的
  COMPACT_OUTLINED 分段轨道（单一强调色轮廓、选中项以强调色填充并配合 accentText、
  1 px 强调色分隔线、等宽选项）。
- 属性行现在使用 Compose 的交替条纹背景：偶数行 `detailRowDeep`（深色 #1C1C1E、浅色为白色），
  奇数行 `detailRowLight`（深色 #2C2C2E、浅色为白色）；警告和信息行在条纹上叠加 6% 色调。
  条纹颜色已加入 `StudioColors`，行索引按分区隔离，与 `DetailRowStripe.usesDeepBackground`
  保持一致。
- 修复了新的属性保留策略为此前已保存归档引入的回归：Compose 使用
  `encodeDefaults=false` 编码 `EdgeInsets`，因此 zero sides 会被省略，可为空的/有默认值的字段
  可能被省略或为 null，Compose 节点则会在节点上保留 `semanticProperties`。可选
  属性 readers 现在将缺失/null 视为不存在，省略的 EdgeInsets sides 默认为 0，
  节点-level 语义属性会合并到证据 map，格式异常的仅用于显示的属性
  会回退到默认值，而不是拒绝归档（structural 字段仍保持严格）。已增加对
  omitted-内缩/节点-语义 shape 的回归测试覆盖，并成功 native 加载匹配的归档，
  不再出现 “snapshot structure is invalid” 错误。
- Canvas 会在节点 overlays 之前绘制解码后的归档截图，使用与布局 rectangles
  相同的仅应用源码 crop 和 12/2 圆角；图像每个 published 帧只解码一次，任何
  解码失败都保留仅布局画布。Android 边界选择/悬停映射不变。
- Zoom/适配控件从居中的页脚行移到 Compose 的右下角浮层：圆角 8、面板
  @90% 填充、边框 @65%、内边距 6/4、24 x 24 按钮、42-wide 等宽值、强调色/muted
  符号状态，范围为 50%-250%，步进 25%。Footer 行及其 “geometry” 标签已删除。
- 两个目录都为新增分区、标签和风险值增加语言环境键
  (`inspector_detail_section_*`、`inspector_detail_*`、
  `inspector_details_evidence`, `inspector_value_unavailable`,
  `inspector_no_high_overlap_pairs`, `inspector_overlap_pairs`,
  `inspector_subtree_complexity`, `inspector_blending_alpha`,
  `inspector_alpha_one`）。`inspector_detail_alpha`/`inspector_detail_size`/
  `inspector_details_overview` 现在使用 Compose 的文案。

证据：`cargo fmt --all --check`、`cargo check --locked -p
aps-layout-inspector --all-targets --offline`、`cargo check --locked -p aps-app
--bin aps-app --offline` 和 `cargo build --locked --offline -p aps-app --bin
aps-app`。aps-app 所有目标检查当前被共享工作区中无关的、正在进行的
`shell/menubar.rs` 测试-目标 recursion-限制错误阻塞；二进制目标本身可以编译。原生
验证使用本地生成的、包含完整属性、文本、原始属性和 1080 x 2400
截图的归档；截图位于 `build/layout-inspector-parity/`。未执行测试（遵循延期的
验证策略）；已编写的覆盖范围包括属性-保留策略测试以及更早的
预览-源码/标签控件断言。

### 页面路由菜单栏 — 2026-11-07

Compose 窗口会为每个页面发布原生 File/View/Tools/Help 菜单。Rust 外壳现在发布当前活动的
路由的菜单，并在 macOS 上将其安装为屏幕顶部的原生应用菜单栏，保留 platform
菜单 placement 和原生渲染（禁用行、checkmarks 和应用菜单）。这只在
内容上偏离 Compose，而不偏离 placement：macOS 窗口内不绘制 Windows-style 菜单行。其他
platforms 回退到使用同一数据构建的 in-窗口条。

- `shell/menubar.rs` 持有路由菜单模型。`MenuGroup`、`MenuEntry` 和 `MenuLabel` 构建菜单
  数据（目录键以及 imported HPROF 文件名等运行时字符串）；`MenuHost` 为每个
  工作区发布 typed 命令 enum；`RouteMenuBuild` 将 groups 同时转换为原生 `Menu` 树
  和回退条行。菜单行将 `RouteMenuAction` id 带入与菜单一起构建的分发
  表格，因此 typed 命令无需字符串编码即可运行。Menus 从实时工作区状态
  重建，只有本地化的内容 signature 变化时才重新安装，因此 notifications 不会让打开的
  菜单闪烁。
- 外壳按路由组合 `route_menu_build`：Home 发布其导入表面样式，Settings 发布分区，
  Layout Inspector 发布归档、面板、视图和窗口 tools，一个共享 Analyze 菜单加上
  Help 菜单关闭每个性能分析器路由。Commands 回到发布它们的工作区，因此路由不会看到
  其他屏幕的状态。
- Route 菜单：CPU File/View/Tools、Trace File/Trace/View/Session、Frame File/Frames/View、
  Memory File/View，以及由导入数据驱动的工作区（Network/Startup/Battery/Benchmark/GPU）发布
  一个 File 菜单，其中包含各自的导入、当前页导出和基线/制品行。Source
  Workspaces 发布工作区/View，AI 发布 Session。菜单行根据实时状态使用
  `item_when`/`check`，因此页面导出在页面存在前保持禁用，memory 快照标签页
  按文件名显示，面板开关显示当前 checkmark。仅提供文件/导入 operations 的屏幕
  共享 File 标题，使命令 location 保持可预测。
- 空菜单不发布。macOS 应用菜单保持在栏的第一位。菜单命令通过
  `window.defer` 分发，因此工作区命令可以通过其外壳 handle 写回，而不会重新
  进入拥有菜单操作的外壳 update。
- 语言环境键：外壳菜单标题（`shell_menu_profile`、`shell_menu_heap`、`shell_menu_frames`、
  `shell_menu_startup`、`shell_menu_battery`、`shell_menu_network`、`shell_menu_gpu`、
  `shell_menu_benchmark`、`shell_menu_trace`、`shell_menu_workspace`、`shell_menu_session`、
  `shell_menu_analyze`、`shell_menu_settings_page`），以及各工作区目录中新增的操作
  标签。
- `main.rs` 不安装菜单；`Studio::new` 执行首次路由菜单刷新。`shell/menubar.rs` unit
  测试覆盖 group 组合、空-group suppression、分发-id assignment 和内容
  signature。
- 证据：`cargo fmt --all`、`cargo check --locked --offline --workspace --all-targets`、
  `cargo test --locked --offline -p aps-app`（291 passed），以及原生运行。截图位于
  `build/menubar-verification/`，覆盖 macOS 菜单栏的 Home（File/Help）、Trace（File/Trace/
  View/Session/Analyze/Help）、CPU Profile（Profile/View/Tools/Analyze/Help，原生 Profile
  菜单已打开）和 Settings，并确认没有 in-窗口菜单行。

### Compose Agent 开关和离线 Compose 检查 — 2026-11-07

Layout Inspector 头部现在暴露 Compose 工具栏的 Compose Agent opt-in，并用它展示当前归档
中捕获的 Compose inspection 数据。Compose 仍是行为基准；未修改 Compose 源码。

- `Compose Agent` 复选框（18 dp box + 11 sp 标签）紧邻 Refresh/Auto scan segmented 控件之前，
  与 Compose 工具栏 order 一致。Offline 构建无法注入或授权实时代理，因此只有 loaded 归档
  包含 `capture/compose-inspection.json` 时才启用；否则保持禁用和 dimmed。勾选它仅改变
  presentation 状态，绝不会启动设备 operation。
- 勾选后，Compose 节点会在 Accessibility 和证据之间增加 Compose category（没有数据
  的 categories 像 Compose 一样隐藏），Overview 标签页增加 COMPOSE SUMMARY 分区（源码
  location、参数数量、修饰符数量、merged/unmerged 语义数量）。Compose
  category 渲染 COMPOSE INSPECTION（mode/代次/完整度）、COMPOSE SOURCE（文件/line/
  偏移/软件包哈希）、RECOMPOSITION（recompose/skip 数量）、STATE READS（重组
  number、值、invalidated flag、top 帧）、PARAMETERS、MODIFIERS、MERGED SEMANTICS、
  UNMERGED SEMANTICS（nested 值展平为带 type/值/truncation 的 dotted 标签），以及
  选中节点的 DETAIL COVERAGE 记录。Node 查找使用归档的 `compose-inspection:<id>`
  prefix。
- `aps-layout-inspector` 现在从 sidecar 保留有界的各节点的 Compose 投影：节点（名称、
  源码、recompose/skip 数量、systemCreated、flags）、详情（四个值 lists；<=8 深度、
  每 list <=512 行、总共 <=200k 保留值、每字符串 4 KiB）和状态-读取 groups
  （<=20k 读取），以及现有覆盖范围记录和 capability 状态。`details_available` 现在反映
  是否保留了各节点的数据。可选 sidecar 解析失败会回退到空投影，而不
  拒绝已保存归档。
- 两个目录都新增了覆盖复选框、category、分区、标签和状态-读取行格式的
  语言环境键。

证据：`cargo fmt --all`、`cargo check --offline -p aps-layout-inspector
--all-targets`、`cargo build --offline -p aps-app --bin aps-app`，以及使用包含 v2 Compose
inspection sidecar 的归档进行原生运行（根节点 Card 节点、参数/修饰符/merged
语义、覆盖范围和状态读取）。截图：
`build/layout-inspector-parity/compose-agent-off.png`（sidecar 存在但条目看起来禁用的
unchecked 状态）、`compose-agent-on.png`（checked box 加 COMPOSE SUMMARY）和
`compose-category.png`（带源码、重组、状态读取和参数行的 Compose
category）。未执行测试；live Compose Agent 注入、授权和重组控件
仍未实现。

### 层级树深度与索引编号 — 2026-11-07

层级树行现在使用 Compose 的节点 numbering，而不是全局 preorder counter。Compose 仍是
行为基准；未修改 Compose 源码。

- 首个标记是 `{depth}-{index}`，使用各深度的 preorder counter（`nextIndexByDepth`），因此
  根节点为 `0-0`，子节点为 `1-0`、`1-1`，grandchildren 为 `2-0`… Counter 在 collapsing/
  invisible/搜索筛选之前遍历完整 preorder，因此 filtered 行保留原 number，与
  Compose 的 `TreeRowModel.number` 完全一致。
- 类标记现在是 simple 类名称（`FrameLayout` 而不是 `android.widget.FrameLayout`），
  `show-ids` 词元渲染 `id/{last resource segment}`（例如 `id/content`），类似 Compose 的
  `resourceLabel`；`hide-indices` 仍会移除 number 词元。
- 已编写（未执行）的覆盖范围：`hierarchy_numbers_use_per_depth_preorder`，以及断言
  `depth-index`、`id/...` 和简单类名词元的更新后偏好设置测试。

证据：`cargo fmt --all --check`、`cargo check --offline -p aps-app
--bin aps-app`、`cargo build --offline -p aps-app --bin aps-app`，以及一次原生采集；它在
`build/layout-inspector-parity/hierarchy-depth-index.png` 显示
`0-0 FrameLayout / 1-0 Toolbar / 1-1 FrameLayout / 2-0 LinearLayout / 2-1 RecyclerView /
2-2 View / 2-3 Composable / 2-4 View`。未执行测试。

### 可调整大小的检查器面板 — 2026-11-07

层级树、预览、属性和发现项面板之前是固定尺寸；分隔条只显示 resize cursor。
现在它们可以像 Compose 页面一样拖拽。Compose 仍是行为基准；未修改 Compose 源码。

- 层级树宽度和属性宽度可通过两个垂直分隔条拖拽调整；发现项高度
  可通过表格上方的水平分隔条调整。Composer 拖拽从指针按下 position 开始，
  并依据拖拽起始大小应用差值，因此受限的面板不会累积 drift。Sizes 仅是会话
  状态，与 Compose 的 `remember`-scoped `paneWidths` / `findingsHeightDp` 一致。
- 边界限制对齐 Compose：层级树 180–640 dp，属性 200–600 dp，画布至少保留 320 dp
  （两个 widths 还会受拖拽 start 时窗口宽度限制），发现项 56 dp 至多为窗口高度
  的 50%。预览在面板之间 flex，因此三个 resizes 都保持可交互。
- 分隔条 ids 现在各不相同（`inspector-splitter-hierarchy`、`inspector-splitter-properties`、
  `inspector-splitter-findings`）；工作区根节点持有拖拽移动/抬起处理器，并清除拖拽状态。

证据：`cargo fmt --all --check`、`cargo check --offline -p aps-app --bin aps-app`、
`cargo build --offline -p aps-app --bin aps-app`，以及对三个分隔条的原生合成拖拽：
`build/layout-inspector-parity/pane- resize-hierarchy.png`（层级树 widened）、
`pane-resize-findings.png`（发现项 taller）、`pane-resize-properties.png`（属性 wider）。
未执行测试。

### 可滚动层级树及预览平移与滚动条 — 2026-11-08

层级树 list 和预览画布之前是固定视口，没有可见滚动界面框架。Compose 仍是
行为基准；未修改 Compose 源码。

- 层级树行现在在一个双轴滚动容器中，类似 Compose 的 `horizontalScroll` + `LazyColumn`：
  uniform list 使用 unconstrained 水平 sizing，行按最长 estimated 标签
  （`hierarchy_content_width`）设置大小，垂直 + 水平 Scrollbar overlays 始终显示
  （`ScrollbarMode::Always`）。深层或 long trees 可横向平移，tall trees 可带可见栏垂直滚动。
- 预览画布现在支持 Compose `PreviewPanState` 平移：wheel 垂直平移（shift+wheel 水平
  平移），left-拖拽平移画布（无移动的 press 仍选择节点），两个滚动条绘制在画布
  edges，滑块跟踪平移。在水平/垂直栏轨道内拖拽会直接 jump/平移。平移按轴限制
  为 `+-(content - viewport) / 2`，内容适配时重置到 centre；缩放按钮（`+`/`-`/Fit）
  限制或重置平移。100% 时滚动条滑块仍以全长的轨道保持可见，与 Compose
  适配器一致。
- 已编写（未执行）覆盖范围：`preview_pan_clamps_to_content_overflow` 断言 fitting 和
  overflowing 内容的限制/限制 math。

证据：`cargo fmt --all --check`、`cargo check --offline -p aps-app --bin aps-app`、
`cargo build --offline -p aps-app --bin aps-app`，以及原生采集：
`build/layout-inspector-parity/hierarchy-scrollbars.png`（带两个滚动条的 47-节点树）
和 `preview-pan-scrollbars.png`（250% 预览平移到 corner、层级树双轴滚动、滑块
positions 已变更）。未执行测试。

### 可选择和可复制的属性值 — 2026-11-08

Node-属性值之前是 plain 文本，无法选择。Compose 的 `DetailRow` 会将值包在
`SelectionContainer` 中；Rust 面板现在以相同方式复用应用的整个窗口的可选择文本辅助程序。

- 每个详情行值（Overview/Layout/Drawing/Accessibility/Compose categories、原始
  属性和分区行）现在使用 `crate::studio::selectable` 渲染，即加入外壳的
  `TextSelectionLayer` 的 `SelectableText`。可拖拽选择，使用 Cmd+C（secondary+C）复制；选择
  可以跨越面板中的多个运行。等宽字体、色调颜色、大小和 ellipsis 仍继承周围行，
  因此布局和 alternate-行条纹不变。标签保持 plain 文本，与 Compose 一致，只有
  值位于 `SelectionContainer` 内。
- 不增加新的状态或事件 plumbing：外壳已安装的现有全局选择层负责
  窗口中每个可选择文本段的 highlight/复制行为。

证据：对 touched 文件运行 `rustfmt --check`、`cargo check --offline -p aps-app --bin aps-app`、
`cargo build --offline -p aps-app --bin aps-app`，以及原生运行，确认 property 值仍在
原位渲染（`build/layout-inspector-parity/selectable-properties.png`）。Concurrent second 应用
实例中合成 select-和-复制无法可靠驱动（另一个会话会抢键焦点），因此
复制依赖已经发布的窗口选择层，而不是新代码。未执行测试。

### 属性行的右键 Copy 菜单 — 2026-11-08

通过窗口选择层选择 property 文本之前已经可以复制，但没有易发现的操作。
Compose-style 行现在暴露 context 菜单。

- 每个节点-属性行带有一个 `ContextMenuExt` context 菜单，其中只有一个操作：
  `inspector_copy_value`（复制 / Copy）。右键任意行会在指针处打开 popup；选择复制时
  写入 clipboard：
  - 如果窗口选择层持有文本选择，则复制当前选择，因此 partially
    选中 值 只复制 选中 substring；
  - 否则复制行的完整值，因此没有事先拖拽时右键仍可复制 property。
- 菜单按行独立（行 element ids 组合 English 分区标题和行索引），因此不同分区
  中的 duplicate 值保持独立菜单状态。标签保持不可选择，与 Compose 一致。
- 语言环境目录新增 `inspector_copy_value`（Copy/复制）。

证据：对 touched 文件运行 `rustfmt --check`、`cargo check --offline -p aps-app --bin aps-app`、
`cargo build --offline -p aps-app --bin aps-app`，以及原生 right-点击采集，显示属性
行旁的复制 item（`build/layout-inspector-parity/property-row-copy-menu.png`）。注意：在此
concurrent 会话中合成 left-点击 delivery 到菜单 popup 不可靠（相同合成点击
也无法打开现有目标下拉框），因此没有端到端复现菜单-item activation；操作复用应用
中已使用的标准 `cx.write_to_clipboard` 路径。未执行测试。

### Layout Inspector 菜单对齐和窗口菜单快捷键 — 2026-11-08

Layout Inspector 窗口菜单现在对齐 Compose NativeViewerMenuBar，而不再只暴露面板开关。

- File 发布 Open… (Cmd+O)、Import (Import Screenshot…)、Open Recent（未最近记录文件、Clear
  Recent）和 Export (Export Capture Archive…)；非 macOS platforms 还发布 Settings，macOS 则将
  Settings 保留在应用菜单。Rust 没有归档导出、截图导入或
  最近归档持久化，因此这些行会在 Compose 显示不可用 capability 的相同
  位置禁用，而不是假装 operation 存在。
- View 首先列出三个面板开关（Hide left/bottom/right 面板，仅在面板 hidden 时 checked，
  与 Compose checked = !visible 一致），随后按 Compose 分组列出五个显示 options。
- Tools 发布 Auto scan（禁用：实时采集不可用）、Previous Node、Next Node、Toggle
  Selected Node（仅有选中节点时启用）、Show 层级树 IDs 和 Rust 特有的 Next 窗口
  行，并使用 Compose separators。
- 菜单模型新增子菜单（原生 MenuItem::submenu，在非 macOS 条中展平为头部加
  行）和每行可选的真实 GPUI 操作。由操作支持的行会在 macOS 菜单栏解析
  键 equivalents：Open… 使用路由-aware OpenActiveRouteFile 操作（Cmd+O），面板开关
  使用 ToggleInspectorHierarchy/Findings/Details（Cmd+1/2/3），树导航使用
  PreviousInspectorNode/NextInspectorNode/ToggleSelectedInspectorNode（Inspector 键 context
  下的 Up/Down/Enter），显示 options 和层级树 IDs 使用现有 ToggleLayout* 操作。
- Toggle Selected Node 有意保留命令和 Enter binding，但不显示快捷键标签：macOS 后端
  会把多字符键名称 enter 渲染为单个字母，容易误导。Up/Down 原生渲染为 arrows。
- Node 导航使用 InspectorWorkspace.visible_rows，因此 collapsed subtrees 和 hidden
  invisible 视图会像 Compose adjacentNodeId 一样跳过；开关会展开或折叠选中节点
  （Compose toggleExpandable），而不是改变 layer 可见性。
- 证据：cargo fmt --all、cargo check --locked --offline -p aps-app --all-targets、
  cargo test --locked --offline -p aps-app（298 passed, 2 ignored），以及
  build/menubar-verification/ 中的原生截图，覆盖 File 子菜单、带 Cmd+1/2/3 的
  View 菜单、Tools 菜单，并实际使用 Cmd+1/2/3 隐藏和恢复三个面板。


### 铺满容器的等宽属性分类标签页 — 2026-11-08

属性头部下的 category 标签页之前保留 8 px 水平内缩，使 segmented 轨道悬浮
在面板内。Tabs 现在使用整个面板宽度。

- 移除标签页行的水平内边距；`COMPACT_OUTLINED` 轨道与属性面板 edges 齐平。
- 每个标签页保留 `flex_1` 加 `min_w_0`，因此可见 categories 无论标签长度如何都均分完整
  宽度；强调色轮廓、选中填充、细线和 separators 不变。

证据：`rustfmt --check`、`cargo check --offline -p aps-app --bin aps-app`、
`cargo build --offline -p aps-app --bin aps-app`，以及显示五个等宽标签页在面板中无侧边距铺满
的原生采集（`build/layout-inspector-parity/property-tabs-full-bleed.png`）。未执行测试。

### 各页面窗口菜单与 Compose 对齐 — 2026-11-08

每个发布 Compose PageMenu 的页面现在都会在 Rust 窗口菜单中发布相同的 shape（macOS 使用
原生栏，其他平台使用条）。Shortcuts 遵循 Compose 的 reserved set：Open… Cmd+O
（路由-aware）、Open Folder… Cmd+Shift+O（CPU）、primary Export Cmd+E（CPU 报告 JSON、
Trace 会话软件包）、Settings Cmd+，以及 Layout Inspector 树键（Cmd+1/2/3、
Up/Down/Enter）。

- CPU：File > Open…、Open Folder…、Open Recent（已保存会话目录行）、Export（Compose 的
  三组内容：带 Cmd+E 的报告 JSON、会话软件包、截图；原始 protobuf、Firefox 性能剖析数据、
  folded 堆栈、PProf；simpleperf 报告、HTML 报告、open externally）、Close 会话，以及
  macOS 之外的 Settings；Tools > Configuration 在真实 Rust tools 上方发布 Compose 的三行
  采集设置行（OpenCaptureSettings 操作）。
- Trace：File > Open…、Open Recent（会话目录）、Export（带 Cmd+E 的会话软件包、
  原始 trace）、Close trace；保留现有 Trace/View/Session groups。
- Frame：File > Open…、Import（Perfetto FrameTimeline、Associate Perfetto Trace）、Export
  （CSV/JSON 仅在已有分析时可用）；保留带采集和时间线行的 Frames/View groups。
- Memory：File > Open…、Import（Java Heap、原生 Heap、ART Sample、Mapping；Rust 目前只接入
  HPROF，其余保持禁用）、Open Recent（禁用：没有持久化 memory recents）、Export（沿用
  Compose 的三组内容，但所有行禁用，因为页面控件尚未接入 memory 导出）；保留
  open-快照标签页和 View（视图 modes）group。
- Battery：File > Open…、Open Recent（禁用）、Export（页面 JSON/CSV 实时；原始证据
  禁用）、Tools > Reset 全局 BatteryStats（禁用：没有采集适配器）。
- Startup：File > Open…、Export（页面 CSV/JSON）。
- Network、Benchmark 和 GPU Inspector 没有 Compose 页面菜单；它们发布 File > Open…、Open Recent
  （禁用）、Export（save/复制页面行），以及页面的真实状态-driven 行（基线
  操作、GPU open/show in 文件）。
- Help 现在与宿主一致：User guide（打开内置的 docs-user site，缺失时禁用）和 AI 分析；
  Settings 只在 macOS 之外显示。
- 菜单模型：子菜单行可以携带真实 GPUI 操作（Submenu::item_action /
  item_action_when），这就是 Cmd+E 能出现在 macOS File > Export 内的原因。
- Build fix：aps-ui-foundation/build.rs 之前只监听资源目录的 mtime，目录 edits
  可能导致 TextKey 从过期的 JSON 生成；现在会为每个目录文件发出 rerun-if-changed。
- 证据：cargo fmt --all、cargo check --locked --offline --workspace --all-targets、
  cargo test --locked --offline -p aps-app（310 passed、3 ignored），以及
  build/menubar-verification/ 中 CPU File 菜单和 Export 子菜单（Cmd+O / Cmd+Shift+O / Cmd+E）、
  Trace File 菜单和 Memory File 菜单的原生截图。

### 折叠导航栏的悬浮提示 — 2026-11-08

导航侧栏折叠时只显示图标；现在悬停图标会在侧栏右侧悬浮提示中显示目标页面
标题（Compose 的 collapsed 侧栏行为）。Expanded mode 保留 visible 标签，不增加悬浮提示。
通过组件 library 的 Button 悬浮提示在 shell.rs 中实现（目标页面、AI 和 Settings 使用
`navigation_tooltip` 辅助程序），并增加源码检查回归测试，断言悬浮提示仅挂在 collapsed
branch。

### JetBrains IDEA 风格的窗口快捷键 — 2026-11-08

窗口菜单现在遵循 JetBrains IDEA 默认的 macOS 快捷键 conventions，针对性能分析器实际具备
的命令，而不是 Compose 的导出快捷键 conventions。

全局快捷键（命令属于页面-specific 时按路由-aware 处理）：

- Cmd+E Recent Files：CPU 打开最新已保存会话，Trace 打开最新已采集会话；没有最近记录
  目录的页面保持行禁用。
- Cmd+W Close：关闭当前活动的页面的 open document（CPU 会话、Trace trace、Method Recording）。
- Cmd+F Find：聚焦当前活动的页面的搜索字段（CPU 搜索、Layout Inspector 层级树搜索）。
- Cmd+B Go 至 Declaration：从证据导航到源码（CPU 选中帧；Frame 将帧与
  Layout Inspector 关联；Source 在 IDE 中打开选中 location）。
- Cmd+\ AI Assistant：准备当前活动的页面的 AI 分析（Analyze 菜单行）。
- Shift+Cmd+F12 Hide All Tool Windows：隐藏或恢复每个 Layout Inspector 面板。
- Shift+Cmd+] Select Next Tab：循环到下一个 Layout Inspector 窗口。
- Ctrl+R Run：启动当前活动的页面的采集（Trace 采集、Frame start 采集）。
- Cmd+1/2/3 保留三个 Inspector 面板的 IDEA tool-窗口 numbering，Up/Down/Enter 保留树
  导航。

之前 Compose-style 的 Cmd+E 导出快捷键和 Cmd+Shift+O Open Folder 快捷键已从键位映射
移除；对应菜单行仍可点击，但不再宣传快捷键。菜单行接入新的真实操作，macOS
会渲染键 equivalents；路由分发器复用页面的现有命令，而不是复制行为。


### Benchmark 回归测试完整 Compose 对齐 — 2026-11-08

Benchmark 路由是最后一个由导入数据驱动的通用工作区；现在已成为移植 Compose
`benchmark-regression` 功能 set 的专用页面。Compose 仍是只读行为基准；未修改
Compose 源码。

- 应用模块：`aps-app/src/benchmark.rs`（`BenchmarkWorkspace` 状态机、菜单栏、渲染）
  以及 `benchmark/{model,page,table,trend,rail,dialogs,ai}.rs`。旧的通用 benchmark 会话
  （基线/比较按钮、`Session::Benchmark` 和 `Session::Comparison`）及其 orphan
  投影文件已从导入工作区、路由菜单和导出 kind map 中移除。
- 页面：Compose 布局，包括可横向滚动的头部操作行（基线/当前 220px 运行选择器、
  Compare、Import Run、Run Benchmark、Export PDF/CSV、Share Report、Compare Settings、Open Trace
  in Perfetto、AI 分析）、四个摘要卡片、带搜索和严重级别/分类筛选
  的比较表格（weights 2.4/1/1/1.2/1/1/1.2、稳定的 `ComparisonKey` 选择、
  有界 2000-行渲染，并披露 shown/matching/total）、210px trend 面板、320px 可滚动报告
  侧栏（门槛摘要、策略摘要、最近记录报告、比较检查器）以及单行状态。
- `aps-benchmark` 增加 Compose ports：带内容寻址的 ids 的 SQLite 运行目录（JSON
  字节的 SHA-256，因此相同 re-导入会替换该行）、指标-历史查询、exported-报告
  JSON 索引、JSON/CSV/Markdown/JUnit/SARIF writers、不依赖外部库的 PDF 1.4 writer，以及
  Macrobenchmark plan/命令/流式 runner（取消、超时、kill+reap）/结果 locator。
  报告文件只通过用户的保存对话框写入并在本地编目；Share Report 会在平台文件
  manager 中显示该文件。
- 证据语义：当前-运行 cases 与相同身份基线指标比较；兼容性 issues
  使用 hard/soft flags 结构化；只有两次运行都达到最小原始采样数量时证据置信度
  才是 Exact；ReleaseGatePolicy/ReleaseGateResult（blocking patterns、allowed 回归测试、
  最小置信度、fail-on-回归、inconclusive handling）忠实移植 ReleaseGateEvaluator。
  判定结果仍来自 Rust BCa + practical 阈值分析器（`aps-benchmark-bca-median-v1`）；
  Compare Settings 在门槛策略旁暴露 relative/absolute 阈值、最小采样、BCa
  iterations、置信度 level 和同一设备 requirement。Baseline Profile experiment 比较
  与 trace pairs 一起移植。
- AI：页面发布有界的 Compose BenchmarkAiContext 载荷（选中行或八个摘要行、
  8 KiB 限制、摘要绑定的证据 id）；没有 published 报告时采取保守拒绝策略。
- 语言环境：每个应用-自有的字符串保持 English/Simplified Chinese 目录 pair；描述旧的
  仅会话内/禁用适配器的字符串已替换为当前真实行为。导入的身份、路径
  和诊断信息保持 verbatim。
- 仅调试构建使用的验证入口（遵循现有 `APS_RUST_OPEN_ROUTE`/`APS_WINDOW_SIZE` precedent，
  并从发布构建中编译排除）：`APS_RUST_BENCHMARK_DEMO` 选择 oldest/newest 已存储运行
  并进行比较，`APS_RUST_BENCHMARK_DEMO_SELECT` 选择首次比较，
  `APS_RUST_BENCHMARK_DIALOG=compare|orchestrate` 打开对话框，
  `APS_RUST_BENCHMARK_EXPORT` 将 demo PDF/CSV 报告写入报告索引。没有这些 variables
  时它们永不运行，也不增加 production 路径。

偏差/边界（明确）：

- 分析器计算保持 Rust 的 BCa 区间，而不是 Compose 的 MAD noise band；Compose 的可见
  策略表面样式已移植，但不会将数值区间转换为编造的 score。
- PDF 的 "Created at" 输出存储的 RFC3339 UTC instant（没有 timezone crate）；PDF base-14 字体
  将非 ASCII characters 替换为 '?'，而 JSON/CSV 保留精确文本。Runner 将 stderr 合并到与
  stdout 相同的有界的 stream。
- 未原生驱动导入选择器；原生验证通过相同模式 seed 运行目录，并让页面
  从源码路径重新解析夹具 JSON。Macrobenchmark 未针对 Gradle 或设备执行，命令/
  runner/locator 测试仅使用 sh 夹具。
- 对话框通过调试 seam 而非指针输入打开：工作区中 concurrent second 应用实例
  反复抢夺焦点，与 earlier 页面记录的限制相同。Escape-至-dismiss 和原生键盘/IME/
  打包后的门槛仍延期；不宣称发布资格验证。

证据（本工作区的最新证据）：

- 每个 touched 文件都运行了 `cargo fmt`；`cargo check --offline --workspace --all-targets`：通过。
- `cargo test --offline -p aps-benchmark`：65 passed / 0 failed（6 lib + 11 解析器 + 20 比较/
  门槛 + 3 experiment + 7 storage + 9 exporters/PDF/索引 + 9 orchestration/locator）。
- `cargo test --offline -p aps-app`：347 passed / 3 ignored / 0 failed，包括新增的 18 个
  `benchmark::` 状态/对话框/趋势/侧栏/AI 测试。
- `cargo build --offline -p aps-app --bin aps-app`：通过；原生采集位于
  `build/benchmark-parity/shots/`：`benchmark-demo.png`（populated 页面）、
  `benchmark-selected-exported.png`（选中行 + 检查器 + Recent Reports 中的 PDF/CSV
  报告）、`benchmark-compare-dialog.png`、`benchmark-orchestration-dialog.png`。Exported
  制品已从 disk 重新读取：`benchmark-regression.pdf` 以 `%PDF-1.4` 和其 xref 表格开头，
  CSV 带门槛列，`reports/index.json` 列出二者及大小。

### Settings：移除 CPU 分析引擎行 — 2026-11-08

Configuration 设置页不再显示 CPU 分析引擎选择器。Rust 是唯一 linked 引擎，
因此该行是 HAR 导入引擎行的不可操作重复；HAR 导入引擎选择器及其 supporting
explanation 保留。源码检查回归测试断言 Configuration 页面没有 CPU 引擎行，且
保留 HAR 引擎行。

### Settings 通用设置：主题颜色布局和分区分隔线 — 2026-11-08

General 设置页不再在 gradient 选择器上方显示多余的 "Custom color" heading。Colour 值
和 Restore 默认操作从选择器底部移到右侧列，垂直细线将自定义颜色
列与八个 preset swatches 分隔。Show sidebar 标签行现在既与上方 dropdowns 分隔，也与
下方主题颜色分区分隔，因此每个区块都作为独立分区阅读。源码检查测试
断言 heading 已移除、side 列 constant 和新增分隔线。

### Settings：移除 HAR 导入引擎行 — 2026-11-08

Configuration 页面也不再显示 HAR 导入引擎选择器：Rust 是唯一 linked 引擎，因此页面
现在只包含 Android SDK 路径卡片和 Semantic 导航 (LSP) 卡片。现已删除不再使用的引擎
下拉框辅助程序，并将回归测试重命名为 configuration_page_has_no_engine_rows，断言
两个引擎行都消失且剩余卡片保留。

### Settings：Android SDK 路径字段边框 — 2026-11-08

Configuration 页面的 Android SDK 路径输入现在渲染在可见字段帧内（字段背景、
细线边框、small 圆角、fixed 高度），而不是 bare 占位内容文本，与应用的其他
framed 输入一致。Configuration 回归测试断言字段颜色已应用。

### Settings：命中测试顺序使用单选按钮 — 2026-11-08

Layout Inspector 设置页面将 Hit-测试 order options（"Smallest area first" / "Z order"）
渲染为单选按钮，而不是之前的标签页-style segmented 按钮：一次只有一个单选 checked，
点击 unchecked option 会持久化新的 order。源码检查测试断言分区使用 Radio::new
且没有 tab_button。

### CPU 导入：在调用引擎之前按内容分类 — 2026-11-08

导入既不是 SIMPLEPERF protobuf 也不是 perf.data 的文件时，之前会原样显示引擎的
`invalid SIMPLEPERF magic`，但没有提示应导入什么。`CpuWorkspace::open_path` 现在先按内容
分类：perf.data（当前和旧版 magic，任一字节 order）仍通过选中宿主 simpleperf
转换，SIMPLEPERF streams 直接解析，gzip 归档、ZIP 会话软件包和未知字节则
显示本地化消息，列出 CPU 工作区接受的格式。Tests 覆盖分类表格、rejection
映射，以及作为最后回退的引擎诊断。

### Help > User guide：通过回环地址提供 docsify 资源包 — 2026-11-08

Help > User guide 之前打开 `file://…/docs-user/index.html`。Bundle 使用 docsify 和绝对
`basePath`（`/docs-user/`），因此浏览器请求 `file:///docs-user/README.md` 并渲染 Not Found；
文件:// XHR 也被阻止，使 guide 实际不可用。`user_guide` 现在按 Compose launcher rule 从
working 目录或可执行文件定位 site，在 127.0.0.1 的临时的 port 上提供服务（仅
GET/HEAD、守卫 traversal、目录 → index.html、有界头部和 socket 超时），并打开
`http://127.0.0.1:<port>/docs-user[-zh]/`。Five 测试覆盖 site location、percent-解码和
escape rejection、无 body 的 HEAD、404/405，以及通过回环 origin 对内置的 `docs-user-zh`
landing 页面和 markdown 的 end-至-end fetch。

证据（本工作区的最新证据）：由于当前 working 树中 concurrent AI-chat 工作正在 mid-refactor，
两项变更在只包含这些 edits 的分离式 worktree at HEAD 中验证：`cargo test --offline -p aps-app`
= 355 passed / 0 failed / 3 ignored，`cargo test --offline -p aps-ui-foundation` = 23 passed，
`cargo fmt -p aps-app -- --check` 通过，`cargo clippy -p aps-app` 未在已变更 lines 增加警告。
打包说明：Rust 应用还没有 doc 复制 step，因此只有 docs 位于 working 目录或可执行文件
上方时行才出现；资源包必须将 `docs-user/` 和 `docs-user-zh/` 复制到二进制旁。


### AI Analysis 与 Compose 对齐 — 2026-11-08

AI 路由现在是遵循 Compose 布局的对话式 AI 分析工作区，而不是早期仅支持
预检的表单；AI 设置页面已可用，二者背后的 Rust 自有引擎也已实现。Compose
仍是只读的行为基准。

- 工作区界面（`crates/aps-app/src/ai.rs` + `ai/**`）：带搜索的 260 dp 侧边栏，提供 Recent/Archived、
  置顶、重命名、分支、归档、复制 ID、删除菜单及确认对话框；56 dp 对话头部；居中的聊天列
  `clamp(0.64*available, 680, 920)`；空状态；带复制、编辑和分支操作的右对齐用户气泡；安全的
  Markdown 回答（标题、嵌套列表、任务项、引用、管道表格、分隔线，以及带语言、换行、复制和
  轻量词元着色的围栏代码），并提供带证据和源码标签的发现项、已验证报告及模型信息行；
  可重试或永久失败状态及重试操作；预检审批卡片（提供方和模型、证据及源码数量与字节数、
  计划中的模型和脚本调用、报告格式和语言、证据及候选行、历史对话展开区、SHA-256 和权限列表、
  Approve/Cancel）；活动面板开关；滚动控件；包含排队栏、绑定上下文行、是否包含源码的上下文
  菜单、模型选择器（加载、空、错误、刷新、选择状态）和 28 dp 发送、停止、排队按钮的输入区。
- 键盘操作：Enter 发送，Shift+Enter 换行，Up 调出上一个问题，500 ms 内两次 Escape 停止，
  Cmd/Ctrl+N 新建聊天，Cmd/Ctrl+K 聚焦搜索。聊天区域的 DSH 明暗配色、14/24 字体规格和
  8/16/20/28 圆角仅作用于本页。
- 引擎（`crates/aps-ai`）：`platform` 保存带版本的 AI 偏好设置/连接元数据和
  连接范围内的密钥存储（通过有界的 `security` 辅助程序访问 macOS 登录钥匙串，其他
  平台仅驻留内存，与 Compose 的内存回退一致）。`conversation` 是有界 SQLite 存储，
  使用与 Compose 兼容的对话/结果/对话记录/轮次/元数据结构，并支持
  恢复中断状态、置顶/重命名/归档、拒绝用户分支时的同会话级联删除、
  保留策略和清除历史。`responses` 移植不使用工具的非流式 Responses API 客户端
  （严格 JSON 模式、output_text 提取、拒绝响应/工具/不完整结果/密钥检测、
  NoFollow、2 min/1 MiB 边界）；`claude` 移植无工具的 Claude CLI 提供方（显式可执行文件
  探测、版本/标志检查、私有临时目录、stdin 载荷、环境范围的凭据，以及禁用
  工具/MCP/会话）；`report` 写入以 SHA-256 寻址的 HTML/Markdown 制品；`platform::import`
  和 `platform::catalog` 移植本地 Codex/Claude 配置导入与模型发现（OpenAI//models
  回退、Claude 分页、稳定的错误分类）。
- `runtime` 在摘要下冻结精确的提示词/审批事实，需要显式审批点击，通过选中的
  提供方执行一次，并通过对话存储持久化已验证的结果/制品。这些测试
  不执行真实提供方请求。
- 设置（`shell/settings_ai.rs`）：固定 AI 页面标题、按 Compose 顺序排列的
  Provider/Agent/Skill/Execution policy 标签页，并通过 `ai_settings_tab` 持久化；Codex/Claude/
  Custom 提供方卡片支持本地配置导入（已导入/不含密钥/5 种失败原因）、
  Claude API 密钥与 bearer 凭据种类、保存后清除的掩码凭据输入、保存前由
  运行时持有的导入密钥、带缓存/加载/空/错误/刷新状态的模型发现、
  端点和模型列表端点，以及会轮换修订版本的 Save-and-apply；内置
  `aps.main`/`aps.chat` 代理卡片（只读）和代理选择；制品权限开关、
  因沙箱不可用而禁用的脚本权限、HTML/Markdown 报告格式、
  Auto/English/Simplified Chinese 语言、
  保留策略显示和需要确认后执行的清除历史。所有新增文案位于英文/简体中文
  目录（每种语言 87 个聊天键、102 个设置键）。
- 外壳：AI 路由以全高的渲染工作区（没有性能分析器页面标题/操作行和设备
  目标栏，与 Compose 对话窗口一致）；`Studio::new` 在 `<Rust data dir>/ai` 下
  打开运行时，注入 GPUI HTTP client，并接通设置/源码候选回调。仅调试构建使用的
  seams（`APS_RUST_AI_DEMO`、
  `_DEMO_TOP`, `_DEMO_APPROVAL`, `_DEMO_MODELS`, `_DEMO_ARCHIVED`,
  `APS_RUST_OPEN_SETTINGS_AI`）渲染确定性的测试数据状态；发布构建不会读取这些变量。本地的
  `build/ai-analysis/mock_provider.py` 和 `fake-claude.sh` 仅是验证夹具，绝不是产品代码。

边界（明确）：这是单应用窗口中的 Rust 路由，不是第二个原生 Compose-style AI
窗口；Agent graph、sub-代理、Skills、sandboxed 脚本和软件包注册表/信任未移植
（built-in 代理 list 是 static 的，且永不授予脚本 execution）；Claude 通过 CLI 运行，而
不是 Anthropic HTTP API；Keychain access 使用 `/usr/bin/security`；活动面板是
会话内的 prepare/运行/取消转换 list，因为已持久化的 audit 时间线未暴露；提供方
answers 不按词元-by-词元渲染（Compose 也不显示部分 assistant 文本）；code-fence wrap
开关和模型菜单是 plain popups；无法从 textarea 状态检查 IME 组合，因此 Enter
suppression 基于修饰符/held-键。此处未执行实时提供方、interactive 点击-through
审批、跨平台、打包、像素 diff 或 IME 验收；这些仍是延期的门槛。

证据（最新，本工作区）：`cargo check --locked --offline --workspace
--all-targets` 通过；`cargo test --locked --offline -p aps-ai` = 65 lib + 16 contracts
+ 4 runtime tests passed；`cargo test --locked --offline -p aps-app` = 404 passed / 3
ignored / 0 failed（包括 48 项 AI 界面、Markdown、状态测试和 10 项设置测试）；
`rustfmt --edition 2024` 在每个 touched Rust 文件上通过。原生调试截图
（`build/ai-analysis/shots/`）：`final-empty.png`、`final-populated.png`、
`final-preflight.png`、`final-settings.png`。截图使用工作区内的 `APS_RUST_DATA_DIR` 副本获取；
未使用真实凭据、未发送提供方请求，也未执行设备命令。Compose 参考截图仍位于
`.omx/verification/dsh-chat-2026-10-31/screenshots/`.

### Simpleperf 设置页重写为与 Compose 对齐 — 2026-11-08

Settings > Simpleperf 现在对齐 Compose 的 one-页面卡片布局
（SimpleperfSettingsSectionContent / CaptureSettingsSection）：

- 未选择设备或目标时显示引导提示，使用 Compose 的采集参数提示文案。
- 采样模板：五个 Compose 模板以圆角行显示名称和说明；由于 Rust 采集后端没有模板模型，
  它们保持禁用，并附有简短的适配器说明。
- 采集配置：包含 "Event and rate" 标题、固定的 cpu-clock 事件字段、Frequency/Period 速率模式
  选项（Period 不受支持，保持禁用），以及实际可用的默认采集频率（Hz）和时长文本字段
  （新增的 InputState 字段会持久化到设置）；没有目标时还会显示 Compose 的选择目标提示。
- 高级参数：调用图和事件范围选项显示为禁用，因为 Rust 采集后端尚未对这些参数建模。
- 火焰图：提供帧信息框标题和说明，并将 Fixed / Follow mouse 选项接入
  simpleperf_follow_tooltip。
- Simpleperf 引擎：New engine (Local) 被选中且实际可用；Firefox Profiler 的 local/official
  选项保持禁用，因为目前仅接入原生查看器。
- 用户指南：提供 Compose 的说明文案和 "Open User Guide in Browser" 操作，通过现有
  user_guide URL 打开随应用提供的离线指南。

所有分区标题、副标题和控件标签复用 Compose 字符串，并增加 English 和
Simplified Chinese 目录条目。源码检查测试断言每个 Compose 分区、template
行、标签控件、采集字段和 user-guide 操作均存在。

### AI 设置页重建为与 Compose 对齐 — 2026-11-08

Settings > AI 现在遵循 Compose 的 AiSettingsPageContent /
AiPlatformManagementSettings 结构：使用相同的 Provider、Agent、Skill、Policy 分类栏
（Policy 标签的中文为执行策略），各分类使用相同的卡片表面样式。

- Provider：提供方选择器、当前已应用提供方的信息行，以及包含凭据提示、掩码密钥字段、
  模型选择器 + Refresh models、Model、Endpoint、Model-list endpoint 和 Save and apply 的连接卡片。
- Agent/Skill：沿用 Compose 的 ManagementExtensions（Import Agent/Import Skill 卡片，包含
  Package 目录字段、Browse、"Import verified package directory"、注册表不可用提示和
  "No packages installed"）。内置代理以软件包式卡片列出，包含强调色标题、Builtin 来源、
  Active 代理单选项和 Enabled 状态；仅聊天代理仍由系统自动选择。
- Policy：使用带强调色标题的 Compose 卡片，包括 Execution permissions（制品和脚本复选框
  及沙箱说明）、Report Output（格式和语言选择器）以及 Conversation history。
- 对话历史现在实现 Compose 的有界保留策略：提供 "Retain at most 100 conversations / 30 days"
  操作、行内确认、已移除数量结果和 E_AI_HISTORY_RETENTION 错误行。AiRuntime 新增
  retain_history()，由对话存储的保留机制支持（保留置顶项），替代此前只能清空全部历史的界面。

为每个新的 Compose 标签增加/更新 English 和 Simplified Chinese 字符串；回归测试
断言软件包卡片、保留策略操作和已移除的 open-分析 link。

### macOS 应用名称：Android Performance Studio — 2026-11-08

Unbundled macOS 构建之前在菜单栏、Dock 和 Force Quit dialog 中显示 cargo 二进制名称
（`aps-app`）。现在应用通过 build.rs（`-sectcreate __TEXT __info_plist`）将资源包元数据
嵌入可执行文件，因此 macOS 无需 .app 包装即可读取
CFBundleName/CFBundleDisplayName = "Android Performance Studio"：使用
crates/aps-app/Info.plist 和目标-仅构建脚本。Process 名称也会在 startup 时设置为
project 名称；每次菜单安装后都会重新应用 macOS 应用菜单标题，因此菜单栏、
Dock、Force Quit 和 About 表面样式都显示 Android Performance Studio。

### 移除标题栏强调色竖条 — 2026-11-08

"Android Performance Studio" 标题前的细强调色的垂直栏已移除；标题栏现在
直接从窗口控件和标题文本开始。Bottom 细线及其余外壳框架不变。


### CPU 导入：解压并导入 gzip 压缩的性能剖析文件 — 2026-11-08

Gzip-compressed CPU profile（perf.data 采集或通过 gzip 保存的 SIMPLEPERF protobuf 导出）
之前会被拒绝，并提示用户先解压。CPU 工作区现在会自行解压 wrapper，再导入其中的 profile：

- 内容分类会检查首个解压字节中的 gzip magic，因此载荷仍按内容而非
  picked 名称分类。SIMPLEPERF protobuf 或 perf.data 的 wrapper 会被接受；其他内容的 wrapper
  保持为 unclassified 载荷。
- 解压会流式写入 Rust 快照 scratch 目录，并独立于 picked 文件受限
  （SIMPLEPERF 载荷为 128 MiB，perf.data 为 512 MiB），每个 chunk 都检查取消。
  解压后的文件随后经过与 plain 文件相同的 perf.data 转换或 SIMPLEPERF 解析路由。
- 解压后持久的身份仍是 profile 自身的 SHA-256 和字节长度，因此重新选择
  同一归档（或导入 extracted 文件）会解析到同一 saved CPU 会话。Source 标签会写出
  picked 文件及 "(gzip) -> SIMPLEPERF protobuf [via <simpleperf>]"。
- 损坏的或 over-预算归档获得新的本地化的消息
  （profiler_cpu_source_gzip_decompression_failed、profiler_cpu_source_gzip_too_large）。旧的
  "decompress it yourself" 提示已从两个目录移除。
- 仅调试构建使用的 APS_RUST_OPEN_CPU launch seam 会像现有 HPROF/FRAME_JSON seams 一样在 startup
  打开 picked CPU 源码，使隔离的实例无需 native 文件选择器即可执行真实导入。

证据（最新，本工作区）：测试覆盖通过 wrapper 的分类、rejection 映射、
带快照持久化和最近记录重开的 gzip-wrapped SIMPLEPERF 导入、通过桩宿主
simpleperf 的 gzip-wrapped perf.data 转换、损坏的 wrappers 以及 decompression
上限/取消。cargo test --offline -p aps-app = 417 passed / 0 failed / 3 ignored
（final 运行），cargo test --offline -p aps-ui-foundation 通过。

真实的 841,474-字节 gzip SIMPLEPERF 夹具
（build/reports/cpu-firefox-reference/input-simpleperf.trace.gz）由使用 APS_RUST_DATA_DIR=<scratch>、
APS_WINDOW_SIZE=1320x860 和 APS_RUST_OPEN_CPU=<that .gz> 启动的调试二进制端到端导入：
CPU Profiler 显示 1,234 采样 / 15 线程 / 1.97 s / 626 Hz，Source 为
"simpleperf-cpu-clock.trace.gz (gzip) -> SIMPLEPERF protobuf"；持久化快照为 841,474
字节，SHA-256 与临时夹具测试计算的相同；Flame Graph 从解压 stream 渲染
AndroidRuntime/ZygoteInit/ActivityThread 堆栈。截图：build/cpu-gzip-import/cpu-gzip-overview.png
和 build/cpu-gzip-import/cpu-gzip-flame-graph.png（ignored 构建树）。cargo fmt 通过。

### 所有性能分析器的主题卡片表面样式 — 2026-11-08

- Memory 页面的 Compose 工作区风格的配色（强调色着色的面板、分区、边框和
  字段表面样式）已移入 `StudioColors::for_mode_with_accent`，`StudioColors::from_theme` 现在
  使用持久化的强调色颜色应用它。`import/memory/widgets::memory_colors` 已删除，因此
  Memory 页面与其他性能分析器工作区共享一套配色，而不是外壳的平面式词元。
- Dark 卡片：在 0x0E0E0E base 上叠加 8% 强调色；分区表面样式为 6%、卡片边框为 22%、
  字段为 0x1C1C1E、选中行为 32%；界面框架保持 0x29292B。Light 卡片为
  0xFAFAFB 工作区上的 white，分区为 0xFAFAFB，边框为 0xD1D1D6，字段为
  white，选中行为叠加在 white 卡片上的 20% 强调色。
- 验证：`cargo build -p aps-app --bins --offline` 和 `cargo test -p aps-app` 通过；深色主题
  下 CPU、Settings 和 Memory 工作区的 native 截图确认卡片、表格头部、
  KPI 块和设置卡片遵循强调色（见 DESIGN.md “Themed Card Surfaces”）。

### Memory 空状态视图 — 2026-11-08

- Memory Classes 视图在未加载快照时不再用居中的提示替换整个工作区。Primary
  筛选栏、八个 KPI 卡片、堆-distribution 卡片以及 classifier 和实例面板都会
  渲染不可用值与空-行提示，与已经保留自身结构的 Overview、Dominators 和
  Diff 视图保持一致。
- `render_memory_classes` 删除了早期的 `!loaded()` return 和废弃的 `memory_classes_empty`
  helper；KPI 值使用本地化的不可用占位内容，而不是归零的摘要；
  distribution 卡片会渲染空栏/legend，而不是消失。
- 验证：`cargo build -p aps-app --bins`、`cargo test -p aps-app`，以及 no-快照 Classes、
  Overview、Dominators 和 Diff 视图的 native 截图，位于 .dsh-tmp/shots（ignored
  scratch 树）。


### CPU 导入：支持 Firefox/Gecko v24 JSON 剖析文件（原始文件或 gzip）— 2026-11-08

导入 `Export Firefox profile` 生成的 profile（或任意 Firefox / Gecko v24 JSON profile）此前会
以 "Not a CPU profile" 失败，因为 CPU 工作区只接受 SIMPLEPERF protobufs 和 perf.data。
现在 CPU 工作区通过同一会话模型导入 Gecko profiles：

- `aps-cpu` 增加 Gecko v24 reader（`gecko.rs`），它移植自 Kotlin `GeckoProfileReader`：包括按
  线程 schema 定位的采样/frame/stack/string 表格、millisecond 时间戳、
  `symbol (in resource)` locations、将 null 堆栈作为堆栈-less 采样、leaf-至-根节点 chains
  （引擎调用链 order），以及针对版本、缺失表格、out-of-range 索引、
  非有限的/负 times、>512-帧 chains 和记录/节点预算的保守拒绝式验证。
  JSON 由共享的 `aps-json` 严格解析器限制为 64 MiB。
- `CpuSession::open` 会识别 JSON 对象，并与 protobuf 解析器共享一个 constructor，因此
  分页、flame graphs、导出和最近记录重开均保持不变。导入的 Gecko 采样的事件
  type 为 "samples"，unit weight 为 1。
- App 内容分类现在探测 64 KiB，而不是 10 字节，并在 perf.data 和 SIMPLEPERF
  之外识别 Gecko JSON 载荷（plain 或在 gzip 内）。Gzip-wrapped Gecko profiles 使用
  64 MiB 载荷预算，源码标签为 `name.gz (gzip) -> Gecko profile JSON`（plain：
  `<path> (Gecko profile JSON)`）。
- Import 提示词和未知-格式字符串现在会在两个目录中明确写出 Firefox/Gecko
  profiles。

接入导入时发现两个 defects：

- Firefox/Gecko exporter 以前以 leaf-first 构建 stackTable，导致此应用导出的 profiles 导入
  Firefox Profiler 后上下颠倒。现在改为根节点-至-leaf 构建堆栈；round-trip 测试
  （引擎 stream -> Firefox gzip 导出 -> Gecko reader）断言原始 leaf-至-根节点调用链
  按字节保持一致。
- Gzip 导入会解压到导入后消失的 scratch 目录，导致会重新读取 profile 路径的
  软件包导出失败。持久化后，会话会重新指向持久的快照
  (`CpuSession::rebind_path`)。

证据（最新，HEAD worktree 为 2ba3227f，仅包含这些 changes，因为共享 working 树中有并行
进行的 AI/network 工作）：`cargo test --offline -p aps-app -p aps-cpu` = 411 passed / 0 failed /
3 ignored 用于 aps-app，另有 aps-cpu 的 7 unit + 6 chart + 5 session tests，均为 all 通过；
`cargo fmt` 通过。报告中的 profile `/Users/yuchuan.gu/Workspace/Perfermance/Source/SimplePerf/perf_data.json.gz`
（Compose-era 导出，30,689 字节 gzipped）由使用 `APS_RUST_DATA_DIR=<scratch>` 和
`APS_RUST_OPEN_CPU=<that file>` 启动的调试二进制端到端导入：CPU Profiler 显示 9 个
线程中的 371 采样及 CPU 时钟时间线，Source 为 "perf_data.json.gz (gzip) -> Gecko
profile JSON"，Flame Graph 渲染 `__libc_init -> main -> android::AndroidRuntime::start -> ...`
堆栈。截图：build/cpu-gzip-import/gecko-cpu-overview.png 和
build/cpu-gzip-import/gecko-cpu-flame-graph.png（ignored 构建树）。

### Trace Analyzer：将操作状态合并到页脚 — 2026-11-08

- 导入及任何诊断或预设执行操作此前会在工作区末尾、设备页脚正上方渲染第二条圆角
  `trace-workspace-status` 状态条，导致底部同时出现两个状态栏。该状态条已删除；底部
  `trace-status-bar` 现在将设备选择和操作反馈（运行中、失败或上次完成状态）显示在同一行，
  中间以细线分隔。反馈在状态栏内超出时省略，仍可选择；失败使用错误色，并保持原有优先级
  （运行中 > 错误 > 完成）。
- 头部的提示行（已导入轨迹名称 · 时长及复制操作，`trace-workspace-notice`）已删除。
  `TraceWorkspace::notice` 一并移除：导入、采集完成和预设完成的文本现在由页脚状态报告
  （导入保留 "name · duration"，采集保留 "title · Completed · size"），设备刷新或工具失败
  则保留现有错误行。
- 这是有意偏离 Compose 工作区 `TraceWorkspaceStatus` 的设计；DESIGN.md 将其记录为
  Trace Analyzer Footer Status 决策。
- 证据：`cargo check --locked -p aps-app --all-targets --offline`、`rustfmt --check` 和
  `git diff --check` 均通过。1400x900 原生截图通过临时调试入口强制设置状态（截图后已移除），
  页脚显示 "Device: ... | Running…"；较长的错误行在同一状态栏内省略显示，不再出现第二条
  状态条：`build/trace-analyzer/trace-footer-status.png` 和
  `build/trace-analyzer/trace-footer-error.png`（已忽略的构建目录）。通过原生文件选择器实际
  导入零字节 Perfetto 夹具时，工具栏后直接显示摘要卡片，没有提示行，页脚显示
  `Device: ... | aaa-trace-fixture.pftrace · 0.000 ms`：
  `build/trace-analyzer/trace-import-footer-only.png`（已忽略的构建目录）。


### CPU 调用树：双轴滚动 — 2026-11-08

Call Tree 表格之前无法平移：virtual list 使用 `with_scroll_handle` 创建，该调用会将 list 的
滚动 container 替换为 bare div，因此共享的 `VirtualListScrollHandle` 从未收到 wheel 或
trackpad 输入，行被裁剪在面板宽度内，无法查看长名称。

- Call 树现在保留 virtual list 自身的垂直滚动 container（使用 `track_scroll` 而不是
  `with_scroll_handle`），并将头部和行包在双方共享的水平滚动容器
  (`cpu-call-tree-hscroll`) 中，因此树横向平移时头部仍与行对齐。
- Horizontal 内容宽度取最长渲染后的行（decimal 列 + 深度缩进 + 符号和
  路径的紧凑字体 estimate）；行至少按该宽度布局，`min_w` 允许面板增宽而不裁剪。
  Vertical 和水平滚动条叠加在 list 视口上。
- 两个 nested 滚动容器都调用 `restrict_scroll_to_axis`，因此垂直 wheel 不再泄漏为
  水平平移（反之亦然）；Top Functions list 获得自己的滚动 handle，键盘
  导航不再借用 call-树 handle。
- Cross-标签页焦点（Flame Graph -> Call Tree）现在会根据当前 collapse 状态将选中行
  滚动到可见区域。

证据（最新，HEAD worktree 为 9dd73270，仅包含此 change，因为共享 working 树中有进行中的
AI-目录 edits）：`cargo test --offline -p aps-app` 通过；使用
`APS_RUST_DATA_DIR=<scratch>` + `APS_RUST_OPEN_CPU=<Gecko profile>` 启动的调试二进制显示
Call Tree 可在 1462 行中垂直平移，并可水平平移以显示完整的
`libutil_thread_trampoline(void*) (__uniq:...)` 标签，头部与行同步滚动。截图：
build/cpu-gzip-import/call-tree-vscroll.png 和 build/cpu-gzip-import/call-tree-hscroll.png
（ignored 构建树）。


### 中性卡片表面样式 — 2026-11-08

每个性能分析器卡片、KPI 块、表格头部和设置卡片之前都使用强调色混合的
表面样式（深色 mode 下为近黑色 base 上的 8% 强调色），因此修改主题颜色会重绘整个
工作区；用户要求卡片背景永不使用主题颜色。

- `StudioColors::for_mode_with_accent` 不再将强调色合成到 `panel`、`section_background`、
  `border` 或 `field`。Dark 配色现在使用 Compose `ViewerPalettes.dark` neutral 表面样式
  （面板 `#2c2c2e`、分区 `#29292b`、字段 `#1c1c1e`、分隔线 `#48484a`、strong 边框
  `#636366`）；浅色配色保持在 `#fafafb` 工具栏工作区上的 white 卡片。
  `selected_row` 保留强调色选择着色，因此选择仍有主题。
- 移除页面-本地强调色/色调卡片填充：Memory Overview（`overview_metric_card`）、Home
  摘要指标、Frame 指标卡片、Network 摘要卡片以及 Trace 摘要 + 权威数据源
  卡片现在都绘制 `colors.panel`；它们的 semantic 色调仍保留在轮廓、标签、图标和
  栏上。
- 使用用户配置中的调试二进制验证（深色、暖阳橙 `#FFDB7A0E`）：Home、CPU、Trace、Memory、
  Frame、Network、GPU、Settings 和 AI 都显示 neutral 卡片填充，强调色仅用于标签、
  轮廓、控件和选择；浅色 mode 在工具栏工作区上渲染 white 卡片。截图：
  build/theme-neutral-card-surfaces/dark-*.png 和 build/theme-neutral-card-surfaces/light-*.png
  （ignored 构建树）。


### CPU 火焰图与堆栈信息卡片：跟随鼠标时保持可见 — 2026-11-08

当 Settings > Simpleperf > flame-graph 悬浮提示 mode = Follow mouse 时，floating facts 卡片总是
在指针右侧/下方打开，并受 chart *内容* 宽度（`chart_width.min(600) - 286`）和固定
420px 垂直 cap 限制。靠近右侧或底部边缘时卡片会被裁剪或停在远离指针的位置；
缩放 chart 后平移时，保存的悬停 position 仍在内容坐标中，而卡片却按视口
坐标布局。

- Chart 滚动 container 现在跟踪 `ScrollHandle`（`chart_scroll`）；悬停 positions 根据当前
  滚动偏移转换为视口坐标，卡片放置在滚动 handle 的真实视口边界
  上，而不是 chart 内容宽度上。
- `chart_tooltip_placement` 选择最佳可见侧：指针右侧放得下则放右侧，否则放左侧；下方
  放得下则放下方，否则放上方；最后将卡片完整限制在 visible chart 内。
  `chart_tooltip_height` 估算渲染后的行（符号、可选资源、inclusive/self、
  采样/share、可选线程、事件和时间戳），因此垂直 flipping 使用真实高度。
- Fixed mode 仍将卡片固定在 chart 的右上角。

证据：单元测试覆盖 preferred side、right-edge flip、bottom-edge flip 和 small-视口限制，
并有视口 tracking 的源码检查守卫。`cargo test --offline -p aps-app` = 428 passed /
0 failed / 3 ignored；`rustfmt --check` 通过。使用 `simpleperfFollowTooltip=true` 在 Gecko
profile 上进行 GUI 检查：悬停 flame graph 左侧时卡片在右侧/下方打开；靠近右边缘时向左
翻转；靠近底部时向上翻转。截图：build/cpu-gzip-import/tooltip-follow-right.png、
build/cpu-gzip-import/tooltip-follow-left.png 和 build/cpu-gzip-import/tooltip-follow-above.png
（ignored 构建树）。


### Layout Inspector 预览缩放：可点击符号、重置、滚轮与捏合 — 2026-11-08

Bottom-right 预览缩放控件（− / 100% / +）虽然显示，但点击符号经常无效：−/+ 符号
是拥有指针事件的子进程 `selectable` 运行，因此只有按钮的 24px 内边距接受点击。
Scale 标签也是只读文本段，所以 overlay 没有重置 affordance（只有画布头部的 Fit
按钮可以重置预览）。

- −/+ 符号现在是 plain 文本子节点，因此完整的 24×24 按钮 area 都可点击，并按 25% 步进
  调整 50%–250% 的缩放。
- Scale 标签（例如 100%）是 clickable 重置，会恢复 100% 并清除预览平移；画布头部
  中的 Fit 保留与 Compose 对齐的重置路径。
- 预览画布将 Ctrl/Cmd/Alt+wheel 作为缩放 steps，将 trackpad pinch 作为 continuous 缩放
  factor，限制在同一范围内，并重新限制预览平移。
- 增加源码检查回归测试测试，防止可选择的符号回归，并保护 wheel/pinch wiring。

证据：`cargo test --offline -p aps-app` = 429 passed / 0 failed / 3 ignored；`rustfmt --check`
通过。Debug 应用打开 `verify-inspector.zip`：点击 + 一次从 100% → 125%（画布 zooms），−
退回；点击 percentage 重置为 100%；在画布上使用 Ctrl+wheel 从 100% → 50%（两次
向外滚动）。截图：build/cpu-gzip-import/inspector-zoom-in.png、
build/cpu-gzip-import/inspector-zoom-reset.png 和 build/cpu-gzip-import/inspector-zoom-wheel.png
（ignored 构建树）。


### Source 工作区侧栏卡片内缩 — 2026-11-08

Source Workspaces 侧栏的工作区卡片距离左侧外壳分隔线 4 px，但距离分隔条的 1 px
line 为 7 px：外壳分隔线位于面板 edge，没有分隔条分隔槽；而分隔条将其 line 居中于
7 px hit area（两侧各 3 px 分隔槽），因此卡片的两个可见间距不同。

- 侧栏现在使用 `layout::SPLITTER_HALO`（3 px）作为起始内缩，`workspace_content_width` 从面板
  内容框中减去该值，因此卡片填满内容框且不会横向溢出（面板框 360 px、内容框 357 px、
  卡片 349 px）。搜索行保留 6 px 内边距，两侧也对称（9 px / 9 px）。布局回归测试已更新为
  考虑分隔槽宽度的计算方式。
- 使用已持久化的 AOSP_Frameworks 工作区上的调试二进制验证（1300x800 窗口、深色
  主题）：测量的像素运行显示卡片距离外壳分隔线 7 px、距离分隔条 line 7 px（之前为
  4 px / 7 px）；面板宽度保持 360 px，卡片宽度仅因 3 px 分隔槽改变。
  截图：build/source-workspace/source-rail-margins-before.png 和
  build/source-workspace/source-rail-margins-after.png（已忽略的构建目录）。


### Source 工作区侧栏最小宽度下的卡片操作 — 2026-11-08

将 Source Workspaces 侧栏拖到 220 px 最小后，工作区卡片的同步 / AI 源码上传：已禁止 /
移除按钮会绘制到卡片 right 边框之外：操作行是一条不换行的 flex line，其最小内容
宽度（~226 px）超过卡片 interior（~193 px = 220 px 侧栏 - 3 px 分隔线分隔槽 - 8 px 列
内边距 - 16 px 卡片内边距）。

- 操作行现在为全宽的并使用 `flex_wrap`，因此在最小宽度下第三个按钮会移到
  second 行；默认 360 px 侧栏下三个按钮仍能放在一行。标签得以保留，因此 AI upload
  状态不会被 ellipsized。
- 使用调试二进制和已持久化的 AOSP_Frameworks 工作区验证：900x700 窗口会将两个
  side 面板强制到 220 px 最小（body 窄于 `MIN_CONTENT_WIDTH`），截图显示按钮
  在卡片内部的之前/之后；1300x800 窗口显示 single-行布局未变。
  截图：build/source-workspace/source-card-actions-before.png、source-card-actions-after.png
  和 source-card-actions-default.png（已忽略的构建目录）。

### Memory Java 堆采集目标接线 — 2026-11-10

- Memory 工具栏的 Dump Heap 操作现在遵循标题栏设备和应用-进程选择，
  不再永久禁用。设备/进程 changes、取消和快照 switching 会使待处理的
  采集失效。仅选择目标永远不会启动设备工作。
- 阻塞式适配器复用共享 ADB locator，验证 online 设备和进程 descriptor，检查 boot
  ID/进程启动时间，执行显式 `am dumpheap`，拉取经过验证的原始 Android HPROF，并尝试只清理
  自身生成的设备路径。不需要根节点、重启、代理注入、`hprof-conv` 或新增依赖。
  Android dump 权限仍是权威边界，失败时会展示有界设备诊断信息。
- 现有隔离的 HPROF worker 会在 UI 发布新快照前解析并投影 captured 字节。失败时保留
  旧快照。快照 IDs 绑定 late 实例/详情/支配者/比较查询结果。
  Capture scratch 所有权随标签页和待处理导出管理；user 导出目标页面不能位于
  临时采集目录内。
- 原生堆、ART 采样、bitmap 采集和代理注入仍禁用，并提供明确的
  不可用悬浮提示。这是 Java 堆采集 addition，不是完整的 Memory 功能对齐或
  发布声明。
- 身份 observations 不是原子的，ADB pull 大小 watchdog 也不是 hard disk-写入上限。
  设备 disconnected 可能阻止远端清理。完整载荷验收仍由隔离的 worker
  负责。
- 已编写的 capture/target/lifecycle 回归测试源码仅编译，不执行。Test execution、live
  设备 dumping 和原生 GUI 验收依据现有 rewrite 策略仍延期。
- 新鲜本地证据：`cargo check --locked --offline -p aps-app --all-targets`、
  `cargo clippy --locked --offline -p aps-app --all-targets --no-deps`，应用和
  `aps-memory-worker` 构建已完成。修改过的 Rust 文件通过 `rustfmt` 检查，diff 通过
  whitespace 验证。Repository 警告仍存在；这些检查不建立真实设备或
  原生界面验收。


### Layout Inspector 实时采集：与 Compose 对齐的 ADB View 树 — 2026-11-10

Rust Layout Inspector 之前只能显示 accessibility 投影：live 刷新运行
`adb exec-out uiautomator dump /dev/tty`，并将结果标记为 "ADB compatible view"。Compose 版本
则使用 `adb exec-out cmd window dump-visible-window-views` 采集运行时 View 树，仅在失败时
回退到 UIAutomator。在同一设备上差异很明显：对于 launcher，Rust 页面显示一个 UIAutomator
窗口，而 Compose 显示 launcher 的两个真实窗口，以及每个节点的运行时类和
91 个原始 View 属性。

本切片将 Compose 采集路径（`AdbFallbackCapture.capture`、
`captureHierarchy`、`expandShallowComposeHierarchy` 和
`VisibleWindowHierarchyParser`）移植到 `aps-layout-inspector`：

- `crates/aps-layout-inspector/src/visible_window.rs`（新文件）解码
  `dump-visible-window-views` ZIP。每个条目都是 Android 的 `ViewHierarchyEncoder` wire
  格式（big-endian typed 值 `Z/B/S/I/J/F/D/R/M`、typed-short maps、尾部的 property-名称
  索引，以及 `window:left`/`window:top` prefix pairs）。该模块保留 Kotlin 基准的
  窗口筛选（软件包 containment、System UI 标题 list、Android 16 launcher taskbar）、
  窗口 id/标题/type derivation、absolute 边界合成（parent + 滚动 + drawing
  translation）、可见性 propagation、`ViewAttributes` 投影和排序后的原始属性
  （使用 Java `Float/Double.toString` 格式检查）。它还负责采用 accessibility 投影
  时的 UIAutomator `promoteComposeSemantics` 对齐。
- `crates/aps-layout-inspector/src/compatible.rs` 现在尝试运行时 View dump 三次（间隔 75 ms），
  失败后回退到 UIAutomator 投影；包含 `ComposeView`/`AndroidComposeView` 的窗口也会
  运行 accessibility 投影，并且仅在其包含 Compose 语义且节点更多时采用，行为
  与 `expandShallowComposeHierarchy` 完全一致。采用的 accessibility 节点使用 Kotlin 的
  structural ids（`root/0/1`）、窗口身份（`window:uiautomator`、type `ACTIVITY`、
  软件包-derived 标题）、blank 文本/资源/内容-description 筛选、`visible-to-user`
  映射和五个 accessibility 原始属性。System UI 目标不再要求前景应用，
  前景 marker priority 与 `AdbOutputParser.parseForegroundPackage` 一致。
- `crates/aps-layout-inspector/src/lib.rs` 现在将可选的仅用于显示的边界（`layoutBounds`、
  `clipBounds`）视为证据，而不是 structural 边界：真实 System UI 窗口的 inverted
  clip rect 之前会导致整个属性投影失败，并静默丢弃该节点的所有属性
  和原始属性。只有节点/窗口边界保持 order invariant。
- 两个目录的语言环境复制现在描述 ADB 视图-树采集、Compose-宿主 accessibility
  expansion 以及非原子的的层级树/截图 pair。crate README 记录了适配器及其边界。

独立对齐 audit（根据 Kotlin 基准编写的 Python decoder、对 recorded 设备字节的模拟-adb
replays、live 设备采集）报告 native 路径有 0 mismatches：333 launcher 节点、695 shaded
System UI 节点（全部 42 个 `ViewAttributes` 字段以及原始属性、ids、标题、kinds、
默认窗口），promoted Compose 投影除下述 intentional deviations 外也有 0 mismatches；
inverted-clip 和 adoption-condition 发现项已按上述方式修复。对于 `com.codemx.anrdemo`
（Compose），采集采用 accessibility 投影：66 节点、4 View + 62 Compose 节点、
structural ids `root/...` 以及 Kotlin semantic property 名称。

以下是 Kotlin fallback 的 deliberate deviations，由 Rust ingestion 契约或证据策略
要求：

- 运行时转储解码有明确上限：限制输入大小、单条目大小、解码字节数和条目数；深度上限为 63
  （更深的子节点丢弃），整个转储最多 100k 节点（后续子节点丢弃），绝对坐标限制为 +/-1e6，
  非有限的浮点属性值省略，alpha 限制为 `0..=1`。
- 无障碍投影的边界采用合理范围限制，而不是裁剪到显示区域（Kotlin 保留屏外或反向边界）；
  反向边转换为零尺寸，以保持结构不变量。
- `display.density` 使用 `wm density / 160`（测试设备上为 3.5），而 Kotlin 回退实现报告
  `1.0`；Rust 画布使用实际密度。
- 协议版本保持 `1.0`（Kotlin 的 `CURRENT_PROTOCOL_VERSION` 为 `1.1`）。
- 采集快照 JSON 携带窗口标题，但 Rust 的 `LayoutWindow` 和状态栏仍显示窗口 ID；Compose
  窗口选择器中的标题（例如 `NotificationShade`）仍是展示层缺口。
- 截图失败是致命错误，采集后还会重新检查 `wm size` 和前台应用，因此非原子的数据对会被拒绝，
  而不是发布；Kotlin 容许缺失截图，也不重新检查。
- 应用内代理采集路径（`run-as` 会话 + socket `CaptureFrame`）尚未移植；Kotlin 的
  `ComposeBridge` 辅助程序仍是需主动启用的完整 Compose 检查路径。因此 `capture_compatible`
  对齐的是 Compose 的 *ADB 回退* 采集，而非针对已插桩应用的代理采集。

证据：`cargo fmt -p aps-layout-inspector`、`cargo check --locked --offline -p
aps-layout-inspector --all-targets`（通过），以及在连接设备上的实时采集
（TCL T853K、Android 17/API 37、1280x2772 @ 560 dpi）：启动器有 2 个窗口 / 23+310 个节点
（深度 6/16），展开通知栏的 System UI 有 3 个窗口 / 81+594+20 个节点（深度 18/14/4，
反向裁剪节点保留 91 个原始属性），Compose 示例应用采用包含 66 个节点的提升后无障碍投影。
独立审计制品位于仓库外（`.dsh-tmp/inspector-visible-window/`、`/tmp/aps-insp/`）。
使用 `APS_RUST_OPEN_ROUTE=inspector` 运行调试二进制的原生冒烟检查，会渲染带设备连接和
新视图采集状态文案的 Inspector 页面（截图位于已忽略的构建目录
`build/live-capture-verification/inspector-route.png`）；由于本会话未获得 macOS 辅助功能
自动化权限，无法用脚本点击刷新，因此实时采集本身通过探针和独立审计验证，而不是通过 GUI。

已编写测试（wire-格式夹具、边界合成、子进程 ordering、筛选、ids/标题/types、
快照 shape、promotion、格式异常的/超大/深层输入、inverted clipBounds 保留策略、adoption
condition），但根据 rewrite 验证策略未执行。


### Layout Inspector 代理优先的实时采集 — 2026-11-10

上一切片对齐了 Compose 宿主的 ADB *回退* 采集。Compose 以代理-first 为主：
`LiveDeviceClient.connectWithFallback` 读取目标应用的 `files/agentperf/session.json`，通过
`adb forward` 的 abstract socket 与应用内 Kotlin/Java 代理通信，并采集真实运行时 View 树
及 PixelCopy 截图；只有代理会话无法读取时才回退到 ADB。本切片移植了该传输：

- `crates/aps-layout-inspector/src/agent_bridge.rs`（新文件）移植了
  `LiveDeviceClient.connectAgent`, `AgentSessionDescriptor`, `CaptureFrameCodec`
  以及 `ConnectedDeviceSession.capture` 的代理部分：有界的 `run-as` 会话读取、描述符验证
  （协议主版本 1、安全的 socket 名称、不含空白的令牌）、临时回环端口 + 带 RAII 移除守卫的
  `adb forward`、`PING <token>` -> `PONG 1.0` 身份验证、用于 `CAPTURE <token>` 的第二个连接，
  采用 `CAPTURE <snapshotBytes> <screenshotBytes>` / `ERROR <code> <message>` 消息分帧
  （头部 <=128 B、快照 8 MiB、截图 32 MiB、精确读取、socket 超时 5 s、adb 超时 30 s），
  从远端文本中脱敏令牌，使用多窗口全屏 `screencap -p` 替换截图并以 PNG 尺寸替换显示范围，
  验证截图和显示范围的一致性，
  并通过共享的有界 `parse_snapshot` ingestion 完成投影。Token 和帧字节永不记录
  到日志；每个 forward 都会在成功、错误和取消路径中移除。
- `crates/aps-layout-inspector/src/compatible.rs` 现在对前景应用优先尝试代理，随后
  再验证前景软件包并返回代理帧；`Unavailable` 会继续走现有的 ADB View/
  UIAutomator 采集。System UI 目标永不尝试代理。
- `InspectorProtocolFrame` 增加 `from_agent`，并传递到 GPUI `InspectorWorkspace`：代理帧
  不显示 ADB-兼容性提示，状态源码显示 `Agent view` / `Agent 视图`（新语言环境
  键）；ADB 帧保留现有提示和标签。

超出 Kotlin 的 deliberate strictness（证据 integrity）：只有无法读取的会话 descriptor 可以
触发回退；格式异常的 descriptors、forward 失败、authentication mismatches、远端
`ERROR`s、无效快照和失败的 multi-窗口 screencap 都是硬错误，而不是 Compose
的原始-帧/`runCatching` 尽力处理。Agent 截图必须匹配最终显示 extent，归档
ingestion 限制仍适用（深度 64、100k 节点、unique 节点 ids、structural 边界）。抽象
socket 已消失的过期的会话 descriptor（force-stopped 应用）同样是硬错误，与 Compose
一致；只有无法读取的 descriptor 才回退到 ADB。每次采集使用自身的 short-lived forward，
而不是 Compose 的 reusable 会话，因此 refreshes 会多付出一次 `run-as`/forward/AUTH
round-trip，但绝不会泄漏 forwarding socket。Compose *inspection* 的已注入的-代理路径
仍是独立的 Kotlin `ComposeBridge` 辅助程序；此传输用于视图/层级树采集。

证据：`cargo fmt -p aps-layout-inspector -- --check`、`cargo check --locked
--offline -p aps-layout-inspector --all-targets` 和 `cargo clippy --locked
--offline -p aps-layout-inspector --all-targets` 均通过，`aps-app` 携带新的状态来源成功构建。
代理传输针对模拟代理完成端到端验证（7/7 场景：单窗口、多窗口全屏截图替换、远端 ERROR、
身份验证不匹配、取消、运行-as 不可用、空描述符），并针对所连接设备（TCL T853K、Android
17/API 37）上真实启用代理的调试示例应用验证：实时 Rust 探针返回 `from_agent=true`，包名
`com.androidperformancestudio.sample`，一个 `window:1` ACTIVITY、8 个节点、深度 4，显示尺寸
1280x2772、密度 3.5，截图为 75740 B、1280x2772、SHA-256 为 `bac5132...`，与独立 Python
基准逐字节一致，结束后 adb forward 列表为空。独立基准比较了 8 个节点 / 451 个值以及
16/16 帧检查，报告 0 处不匹配；还通过启动器重新验证了非代理回退路径（fromAgent=false、
两个 ADB View 窗口）。基准制品位于仓库外的 `.dsh-tmp/inspector-agent/` 和
`/tmp/aps-insp/agent/`。

剩余缺口：真实 multi-窗口完整-显示 replacement 路径仅通过模拟 adb shim 验证（采样应用
只有一个窗口）；没有真实 Compose 应用可用于真实 Compose-语义节点（仅有合成
夹具）；会话 reuse 尚未实现；本次会话无 macOS accessibility automation 权限，
无法进行 interactive GUI 点击-through，因此代理帧通过 UI 调用的同一个
`capture_compatible` API 验证。

构建代理采样时发现一个 pre-现有 Kotlin-side issue（out of 范围，repo 来源未修改）：
`StartupMetricsAgent.kt:145` 在 first-帧回调触发两次时会重复移除帧指标 listener，
导致 API 37 上任何代理-启用应用崩溃（`agentperf-startup-metrics` 上出现 fatal
`IllegalArgumentException`）。Sample 应用的 canonical repo 构建也独立于本次移植而损坏
（AGP 9.4.1 built-in Kotlin/KGP classpath `BaseVariant` 失败，采样 `minSdk 21` 而
`androidx.activity 1.13.0` 要求 23）；设备验证使用了 repo 外 staged 构建，仅增加
了该 listener removal 守卫。


### 窗口标题栏居中显示当前页面名称 — 2026-11-10

应用绘制的标题条之前只在左侧显示 product 名称、右侧显示设备/进程目标，因此
不查看导航侧栏或页面本身就无法识别当前页面。Compose 会使用当前活动的目标页面
重新命名 native 窗口（`DesktopAppMainPage.kt` 设置
`windowTitle.value = navigator.destination.titleResource`），并在 hidden-标题栏条中绘制
该标题；Rust 窗口标题被隐藏（`titlebar.title: None`），所以条完全没有页面身份。

- `Studio::render` 现在将标题栏构建为三个区域：交通灯按钮 edge 上的 product 名称、
  中间的当前活动的目标页面本地化的名称（`Destination::label(language)`，与导航
  侧栏、Home 和菜单使用同一目录），以及末端 edge 的目标选择器。两侧区域
  使用相等的 `flex_1` 使标签在内容 box 中居中；由于 macOS 交通灯按钮内缩（78 px）
  比末端间距（16 px）宽 62 px，标签会将差值加入末端内边距，使文字落在窗口
  的真实水平中心，而不是偏右 31 px。
- 当窗口宽度小于 1140 px 时，固定的 200 px 设备/进程选择器、起始端名称和
  centered 标签无法共存，因此条保持旧的起始名称布局，避免与控件重叠。
  默认 1320 px 窗口及所有正常 desktop 大小都显示页面名称。
- 不增加新的语言环境键：标题复用两个目录中已有的目标页面标签，因此
  English/Simplified Chinese 遵循语言 setting。

证据：`cargo check --locked --offline -p aps-app --all-targets`（通过）和
`rustfmt --check --edition 2024 crates/aps-app/src/shell.rs`（通过）；已编写的源码检查测试
（`titlebar_centres_active_page_name`）确保居中标签、末端补偿和窄窗口阈值保留在渲染路径中
（按策略未执行）。使用 `APS_RUST_OPEN_ROUTE=...` 运行原生应用时，Layout Inspector 页面
显示 `布局检查器`，Home 显示 `首页`，1140x700 调试窗口显示 `基准回归分析`（没有重叠）；
截图位于已忽略的构建目录 `build/titlebar-page-name/`（宽布局为 `inspector-clean.png`、
`home.png`、`benchmark-1140.png`，1000x700 下展示回退布局的截图为 `narrow.png`）。


### 连接设备后 Layout Inspector 填满页面 — 2026-11-10

页面在头部中显示 `设备已连接`（设备 linked），随后却是空面板：进入 Layout
Inspector 只设置目标并等待手动点击 `刷新`，因此已连接设备看起来像采集失败。Header
状态还将 in-应用代理的 live 帧错误标记为 `离线归档`（offline 归档），因为状态
branch 只了解 ADB-compatible flag，之后便回退为 "archive present"。

- `InspectorWorkspace::capture_if_idle` 在进入页面时仅执行一次常规 `refresh`：前提是已选中
  设备序列号、尚未加载帧、没有采集正在进行，且没有待处理的 Compose 检查授权。它从不替换
  已有帧，并确保只有一个后台采集任务，因此 Refresh 按钮仍是后续刷新的手动入口。
- `Studio::new`/`Studio::navigate` 在进入 Layout Inspector 页面时触发该操作；页面已经打开时，
  若设备解析完成（首次启动、重新连接或从标题栏选择设备），`TargetSelectionChanged` 订阅
  也会触发。两条路径共用同一守卫，因此不会重复采集。
- 头部状态现在区分代理帧（`Agent 视图`）、ADB 帧（`ADB 视图采集`）和离线归档（`离线归档`）。
  底部状态栏此前已经如此处理；头部过去会将代理采集显示为 `离线归档`。
- 仅调试构建使用的验证入口 `APS_RUST_INSPECTOR_CAPTURE=<serial>` 会在 launch 时启动一次
  live 采集，无需 native 点击；仅使用 `APS_RUST_OPEN_ROUTE=inspector` 也足以验证新的
  auto-采集路径。

证据：`cargo check --locked --offline -p aps-app --all-targets` 通过；已编写的源码检查
测试（`inspector_page_auto_captures_once_on_entry`）保护 navigate/选择 wiring 和
`archive.is_some()` 守卫（按策略未执行）。使用 `APS_RUST_OPEN_ROUTE=inspector`、连接设备
运行时无需 manual 点击即可看到 Agent 采样应用层级树（8 节点，源码 `Agent 视图`）
和 non-代理采样层级树（73 节点，源码 `ADB 视图采集`）；截图位于 ignored
构建树（`build/inspector-capture-verify/auto-agent.png`、`auto-adb.png`）。


### 标题栏通知中心：页面错误和提示 — 2026-11-10

错误和 tips 之前以临时的本地状态分散在各个页面中（red lines、警告、
取消提示），没有历史，也无法整体复制。Title 栏现在在进程选择器后
以 bell 按钮结尾；点击后打开 anchored popover，每个页面错误或 tip 都会成为可复制的卡片。

- `crates/aps-app/src/notifications.rs`（新文件）负责有界历史和面板：`NoticeLevel`
  （Info/Warning/Error）、`NoticeSnapshot`、`NoticeSource`、`NoticeCard` 和
  `NotificationCenter`。Cards 按 newest-first 排列（<=200 卡片、每个源码 <=8 消息、
  每条消息 <=8 KiB）。工作区暴露其 *当前* 消息；center 仅为 changed 消息
  推送卡片，将重新出现的 identical 消息合并到既有卡片并增加 occurrence 数量；Clear
  时保留 last 快照，避免仍可见的错误再次弹出；面板打开时标记卡片为 seen，并从
  unseen 卡片推导 unread badge。每个卡片显示严重级别、源码页面、relative age 和数量；
  消息是可选择文本段，其自身 Copy 按钮复制 `[level] source (age) message`。Header
  增加 Copy all、Mark all 读取、Clear 和 close；标题栏 badge 显示 unseen 数量（上限 9+）。
- `shell.rs` 持有 `NotificationCenter` entity，每 500 ms 通过 `NoticeSource` 在
  `collect_notices` 中采样所有工作区，并接收 App-level 外壳提示和目标发现
  错误。Bell 按钮位于标题栏末端 edge（设备/进程选择器之后，在 AI 页面
  仍存在），popover 是标题栏下方的 absolute overlay，带整个窗口的 backdrop（点击关闭）
  和 Escape handling。外壳观察 center，因此 open/close 和 badge changes 会立即重绘。
- `NoticeSource` 适配器已加入 CpuWorkspace、AiWorkspace、FrameWorkspace（错误 + 警告）、
  BenchmarkWorkspace、SourceWorkspace（页面、列表、权限错误）、CaptureWorkspace（错误 +
  以 Info 记录的取消）、MethodWorkspace、TraceWorkspace（错误、工具、会话、搜索错误）、
  InspectorWorkspace、ImportWorkspace（共享错误、内存错误、GPU 错误、页面警告和提示；
  覆盖 Memory/Network/Startup/Battery/GPU 页面）以及 TargetsWorkspace（设备和进程发现错误）。
  消息会滤除空白、裁剪到 8 KiB，每个来源最多 8 条。
- 两个目录都增加了按钮、面板、levels、ages、空状态和操作的语言环境键
  (`shell_notification_*`)；notification 源码复用各目标页面的本地化的页面名称。

明确限制：采样基于状态，因此在一个 500 ms tick 内出现并清除的错误可能不会被记录；
battery 报告警告仅保留在 parsed 报告数据中，不会映射；neutral statuses（AI save
成功、benchmark 运行状态、memory loading 文本）会有意跳过。

证据：`cargo check --locked --offline -p aps-app --all-targets` 通过（仅其他位置存在
pre-现有警告）；`notifications.rs` 中 authored 单元测试覆盖快照
diff/coalescing/clear/unread/复制文本 logic（按策略未执行）。使用
`APS_RUST_NOTIFICATIONS_DEMO=1` 的 native 运行为每个严重级别 seed 一张卡片，使用
`APS_RUST_OPEN_NOTIFICATIONS=1` 打开面板验证；截图位于 ignored 构建树：
`build/notifications/badge.png`（unread badge 4）、`build/notifications/panel.png`（四张
demo/应用卡片）和 `build/notifications/panel-after-adapters.png`，其中真实 SourceWorkspace
失败（`cannot lock metadata database for migration`）与外壳设置提示一起被自动
收集。面板、backdrop 和 Escape close、复制按钮以及可选择消息文本段均已进行
视觉验证；各卡片的 copying 写入的文本与 Copy all 路径格式化的文本相同。


### 移除旧版行内错误和提示组件 — 2026-11-10

Pages 仍在新增 notification center 旁渲染旧的 inline 消息 banners，因此同一个错误会出现
两次。现在每个页面都通过 `NoticeSource` 暴露消息，已移除重复 widgets：

- Studio 外壳提示行（每页标题栏下方显示的红色行）。
- Layout Inspector 行内错误行。
- Import 工作区提示或错误横幅，以及各导入页面的提示或错误行。
- Method 工作区错误行和 Capture 工作区错误行。
- CPU 行内错误横幅和元数据不可用横幅。
- Battery 内容区错误或提示行。
- Source Workspaces 页面级错误横幅（移除不再使用的 `message_line` 辅助函数）。

字段本身未改变，仍是 notification 适配器与既有状态/验证 logic 的唯一源码。
进度/busy indicators、空状态、bottom 状态栏（快照身份、运行状态）以及
Layout Inspector 采集模式 explanation 都保留，因为它们是状态界面框架，而不是
notification stream 的副本。错误现在只会作为标题栏 bell 上的 unread 卡片出现，直到
面板被打开。

证据：`cargo check --locked --offline -p aps-app --all-targets` 通过；在 Home 上使用
`APS_RUST_NOTIFICATIONS_DEMO=1 APS_RUST_OPEN_NOTIFICATIONS=1` 的原生运行显示没有 inline
red 设置 line，而面板列出相同的 App-level 设置错误、真实 Source Workspaces
失败和 demo 卡片（`build/notifications/without-inline-banners2.png`）。


### 将 ADB 采集模式提示移入通知中心 — 2026-11-10

Layout Inspector 之前仍在 `HeaderToolbar` 与层级树面板之间绘制 ADB 兼容性
explanation 的 inline 行。该行已删除；相同文本现在作为 WARNING 卡片位于标题栏
notification 面板中，可复制，且不再占用层级树上方的垂直布局。

- `InspectorWorkspace::notice_snapshot`（现有 NoticeSource 适配器）现在在 published 帧
  来自 ADB 路径（`compatible_capture`）时发出 `InspectorCompatibleNotice` 作为
  `NoticeSnapshot::warning`。Polling notification center 会在下一个 tick 将其变为卡片；切换
  到代理采集会从当前快照移除它，但不会删除 historical 卡片（与其他页面
  提示一致）。
- Inline 渲染区块（`header_toolbar` 下方的 `self.compatible_capture.then(...)`）已删除；
  层级树面板现在直接从工具栏下方开始。工具栏中 compact 的 `ADB 视图采集` /
  `Agent 视图` 状态标签控件不变，bottom 状态栏源码标签也不变。

证据：`cargo check --locked --offline -p aps-app --all-targets` 通过，`inspector.rs` 上的
`rustfmt --check` 通过。使用
`APS_RUST_OPEN_ROUTE=inspector APS_RUST_OPEN_NOTIFICATIONS=1` 对非代理示例应用运行时，层级树
直接从工具栏下方开始，通知面板将 `警告 · 布局检查器 · ADB capture-mode explanation` 与
应用级设置错误及真实的 Source Workspaces 失败一起列出（`build/notifications/open-test-15s.png`）。

### Compose 编译器报告文件与文件夹导入及配置指南 — 2026-11-11

- 将 Kotlin `ComposeCompilerReportParser`/导入选择器/guide 契约移植到 Rust Layout
  Inspector。Bottom 状态下拉框对齐目标选择器，并提供报告 File 与报告 Folder。
  选定文件只加载同模块-prefix 的 siblings；folder 加载支持的 direct 子节点，不递归。
  CSV flags 和 TXT 参数记录按名称/overload order 合并。
- Import 拥有独立的背景生命周期和取消词元。Picker 取消或导入
  失败会保留旧 compiler 报告和 captured 帧。Success/失败结果保留数量、
  源码路径和解析器警告；结果可复制，成功的报告可从摘要重新打开。
  现有节点诊断信息和 capture/injection 授权不变。
- 唯一的运行时 function-名称/源码-文件 matches 会在 Overview/Compose 属性显示 static
  编译器标志和参数稳定性；缺失或 ambiguous matches 不附加猜测的 flags。
  Large 参数视图会明确标记显示 omissions/shortening；imported 证据不会截断。
- Settings > Layout Inspector 现在包含双语配置、代次、output/导入 steps
  和证据 limitations，并提供可复制的 Kotlin 2.x `composeCompiler` 配置及
  `./gradlew :app:compileReleaseKotlin`。未增加依赖、目标-project 构建 edits 或
  设备变更。
- 增加 11 个解析器、6 个 import/presentation-state 和 2 个设置-guide 回归 cases。
  根据 migration 测试延期 instruction，这些仅编译；未运行测试二进制、generated-报告
  冒烟检查或 native 文件-选择器/GUI flow。
- 验证：当前解析器/foundation 所有目标检查通过。并发的无关 capture/Startup/Perfetto
  edits 和 lockfile changes 中断了整个应用检查，因此最终验证使用 `4ea97b9e` 的
  临时归档加上报告实现/设置/目录文件，同时保留当前活动的 worktree。
  `cargo check --locked --offline -p aps-app -p aps-layout-inspector -p aps-ui-foundation
  --all-targets` 在该副本中通过；解析器/foundation `cargo clippy --locked --offline
  --all-targets --no-deps` 无警告通过。现有应用警告仍存在。Scoped rustfmt/
  diff 检查通过。Temporary 验证复制已自动删除。未提交/推送。

### 系统 Composable 设置说明及延后应用可见性 — 2026-11-11

- Layout Inspector 视图-设置卡片现在显示现有的 “Show system Composables (including Box)”
  开关及其完整 explanatory 文本：system-created Box/Row/Column 节点、隐藏它们以减少
  层级树 noise，以及在下一次采集或刷新时生效。两个 locales 均已更新。
- System-节点可见性仅在 validated 新增帧发布时锁定；修改已保存偏好设置不再立即
  改变当前树。Filtering 继续使用检查器的 system-created flag，绝不使用名称。
  现有默认/持久化契约和采集授权保持不变。
- 为显示 guide 和发布边界增加两个回归 cases，仅在现有测试执行
  deferral 下编译。Foundation 所有目标检查、scoped 格式检查和 diff 检查通过。Active
  整个应用检查被无关的并发 Network 采集字段阻塞，因此 `4ea97b9e` 临时归档
  加报告/设置 changes 通过了应用/foundation 所有目标检查。未执行测试、原生 GUI
  验收、设备 operations 或提交。

### 各数据工作区的导入耗时 — 2026-11-11

- 为 CPU、Trace、Method Recording、Frame、Memory、Startup、Battery、Network、GPU、Benchmark、
  Layout Inspector 归档和 Compose 编译器报告增加一个双语导入-time formatter
  及 bottom-状态 integration。复用现有性能分析器状态栏，不增加重复的页脚行。
- Monotonic 时钟在文件/folder 选择后启动，包含转换、解析和初始
  分析/查询投影。CPU 包含共享 overview/时间线查询；Memory 包含 typed
  快照；Benchmark 包含目录/指标/trend 读取，并在加载两侧时包含初始比较。
  Picker wait、设备录制、后续查询/导出以及 Frame trace-路径 association 不会被
  重新标记为导入工作。
- Memory 快照标签页保留各自时长；原生/ART/Bitmap datasets 有独立测量。
  GPU times 遵循制品摘要，Compose compiler-报告时间与 inspected 归档分开。
  失败的或已取消的工作不会覆盖已有时长；closing/replacing 数据会清除或恢复匹配的
  值。
- 增加 formatter、发布-边界、页脚、快照/摘要所有权和查询-preservation
  回归 cases。Active worktree 中验证通过：`cargo check --locked --offline --workspace
  --all-targets` 和 `cargo clippy --locked --offline -p aps-app -p aps-ui-foundation --all-targets
  --no-deps`（警告 remain），以及 scoped rustfmt 和 `git diff --check`。回归测试 cases
  仅按 rewrite 测试延期策略编译；未运行测试、native 文件-选择器/GUI 验收、
  设备检查或提交/推送。

### 实时采集开发后续记录 — 2026-11-11

- 取代早先关于 Startup、Battery、Network 和原生/ART/Bitmap 采集的“no Rust adapter”
  实现 notes；historical 验收记录不重写。本后续工作实现适配器，
  不代表运行时验收。
- CPU 现在记录远端 launch 身份/exit 状态，在可用时使用 user-space 应用-scoped
  Simpleperf 采样，并只清理自有的录制器制品。Inspector 负-double 格式检查
  和过时的设置 choice-标签控件断言已修正，没有重做这些页面。
- Startup/Battery/Network 具备显式目标审查/同意、有界传输、stop/取消和
  过期结果发布守卫。Battery 发出最小化的 UID-上限证据资源包；Network 不将
  原始 traffic 内容放入投影。
- 原生/ART 和 FrameTimeline 共享自有的 detached-会话 Perfetto 录制器。Unknown
  stop/清理状态保留精确恢复路径。原生/ART 保留 boot/进程身份和
  sampled-证据边界；Bitmap PNG extraction 在隔离的 worker 中运行，带图像/字节
  限制和 omission 数量。
- 高级内存 import/export 使用自身保留的制品，不会静默复用 Java 快照 AI
  证据。共享标题栏目标、菜单、取消、localization 和制品生命周期
  已接通。
- 最新工作区所有目标 compilation、desktop/worker 构建和 affected 模块 Clippy 通过；
  测试、设备 probes 和 GUI 验收未运行。整个工作区静态检查/格式仍有无关的未变更
  发现项。Android Agent Gradle 构建在 Kotlin compilation 之前因缺少 AGP
  `BaseVariant`/`KotlinAndroidTarget` 配置 support 而阻塞。未执行依赖-版本
  workaround、安装、提交或推送。
- 详细范围和证据：`LIVE_CAPTURE_DEVELOPMENT.md`；日志位于 repository 根节点
  `build/live-capture-development-20261111/`。这不是完整对齐、真实设备、提供方、
  软件包或发布通过。

## 功能测试与对齐续作 — 2026-11-11

当前请求明确启用 testing，并取代 historical 测试延期策略。`FUNCTIONAL_PARITY.md` 现在
记录完整的剩余 workflow/platform 范围，而不是将导航或 passing 测试视为对齐。

- 移植确定性的 Inspector 发现项；接通 Method 采集和 standard 文本-first ART 导入；
  增加持久化的 Battery 会话和 GPU 目录；增加整个会话的最小化的 Network
  JSON/CSV/部分-HAR 导出。
- 修复 CPU 远端 descriptor/控件-pipe 生命周期、SELinux-compatible flat Perfetto 录制
  路径、Warm launch Intent flags、multiline ADB 属性、Startup 指标 localization 和
  opt-in Network Agent plugin classloading。
- 新鲜的 `cargo test --locked --offline --workspace --no-fail-fast`：1184 passed、0 failed、
  3 ignored。真实 clangd 冒烟检查和手动 Tree-sitter 测量另行通过；AOSP large-工作区
  冒烟检查仍未执行。
- 工作区所有目标检查、desktop/worker 构建和 affected-软件包 Clippy 在已有警告
  下通过。完整格式检查仍报告未变的 `aps-app/src/studio.rs:118` difference。
  `git diff --check` 通过。Rust crate 源码/资源哈希在最终 integration 验证
  前后匹配。
- Real 采样 Method capture/import 产生 1613 事件、299 methods 和 14 线程。Warm retest
  在 PID 不变的情况下观察到 Warm，TTID 为 178 ms；Fully drawn 证据仍不可用。
  Battery live 采集通过。共享 Perfetto sched 采集产生可查询的 110133-字节 trace，
  包含 5022 sched 行。CPU 传输/取消现在可以完成并清理，但两次 live 运行
  都有 zero 采样；populated CPU/转换/导入验收仍未完成。
- 原生开发冒烟检查覆盖 15 路由，seeded Battery/GPU/Inspector 和真实 Method 数据，
  以及四个 narrow-窗口检查。这些不是 exhaustive 键盘、像素、Agent/提供方、
  软件包或跨平台验收。
- Network Agent 的 forced offline Kotlin compilation 和 desktop opt-out 检查通过，且未修改
  依赖版本。未执行安装、根节点、设备权限/设置 changes、
  提供方请求、提交或推送。

证据：`../build/rust-parity-audit-20261111/REPORT.md` 及其原始日志、截图、源码哈希
和有界的设备制品。完整目标仍当前活动的：richer CPU 采集、Inspector continuous/归档
flows、Startup/Battery 比较、Memory advanced workflows、Network 详情/reimport、AI
扩展、历史、性能和 delivery 门槛仍需继续完成。

## 功能对齐续作：CPU 和 Home — 2026-11-11

完整目标仍当前活动的。本 checkpoint 不取代 Compose，也不为每个已暴露 workflow 建立
native/设备/软件包对齐。

- 根据 Compose SQLiteProfileQueries 修正 CPU LostSituation 语义：sample_count 为
  producer-reported，lost_count 为 lost 采样。原生引擎、Rust 目录、KPI/详情速率
  以及 JSON/HTML/会话导出现在分别保留 reported 采样，不再编造 lost-事件指标。
- 重新打开上一续作保留的 populated 设备 protobuf：249 parsed、249 reported、0 lost、
  8 线程、1532 call-树行和 1010 top functions。CSV、JSON、gzip Firefox、HTML 和
  会话包导出均成功。本次相同输入检查不需要新的设备录制或
  提供方请求；原始设备采集和 native 交互仍是独立证据。
- 将 Settings 中的 DWARF / Frame Pointer / None 接入已冻结的 CPU plans、confirmation 文本和
  已执行的 Simpleperf arguments。实际模拟录制器 argv、plan immutability、成功、失败、
  取消和 unverified 清理路径覆盖三种 modes。Templates/事件/速率/范围仍未完成。
- 接通 four-源码 Home 历史和成功的导入发布。Trace 采集元数据在 MRU
  promotion 后保留；不存在/无效索引时间戳保持未知，而不是 1970。Reads 是被动、
  有界的且来源隔离的。
- Java 堆采集现在保留已验证的 private 复制，并注册背景历史。新建 copies 在
  registration 失败时回滚；现有证据保留。只有 originating 快照收到延迟
  错误。临时目录清理不变；配额和 crash-orphan 恢复仍开放。
- 修复 Startup 比较夹具，加入必需 Compose 空 fullyDrawnTimeMs 列，并保留
  缺失-TTFD 证据和全部比较断言。修正 Clippy 测试 constant 和此前的
  studio.rs 格式检查 difference，未改变 product 行为。
- 新鲜完整工作区测试：1259 passed、0 failed、3 ignored。Independent native Simpleperf
  引擎测试：45 passed。完整工作区格式检查、Clippy、所有目标检查和应用/worker
  构建通过；现有 Clippy 警告仍存在。
- 当前 native 进程成功将真实 CPU/HPROF/Trace 和 Inspector 夹具导入隔离的历史。
  截图在旧版采集和 ScreenCaptureKit 下均失败；CGSession 确认 screenLocked=1。
  不宣称截图、点击/重新打开、新增真实设备采集或打包验收已完成。

证据和剩余范围：
`../build/rust-parity-continuation-20261111/REPORT.md` 及其原始日志和源码哈希，以及
`FUNCTIONAL_PARITY.md`。未增加依赖，未提交或推送。

## CPU 采样和 Inspector 最近菜单续作 — 2026-11-11

- CPU 采样现在拥有 typed 事件/速率/范围/调用图配置。五个 Compose templates
  输入一个共享 Settings/采集草稿，prepared plans 保留不可变的参数。Frequency/
  Period、USER/KERNEL/BOTH 和所有调用图 modes 都到达实际录制器。Invalid 输入会保留，
  但无法 prepare plan；period 数量永远不会变成 stored Hz 偏好设置。
- 执行真实 Compose SamplingParameters/SamplingTemplate 类生成 46 个比较 cases，
  提交夹具/driver/源码哈希，并根据 Rust 验证规范化的采样 arguments。六个
  focused Compose 测试通过。这不建立目标-form、权限或 native 对齐。
- 修复缺失的 CPU 条目 point：工具栏、Tools 菜单和采集快捷键只打开规划器。显式
  Inspect 和 Confirm 仍是录制的门槛。
- Inspector 现在在 launch/条目时异步加载十个最近记录归档，使用现有 importer 重新打开，
  且只清除其 Layout 路径索引。同名归档使用与 Compose 相同的完整路径。FIFO 变更
  queue 和代次检查防止旧写入/读取撤销 clear。其他 Home 来源、saved 会话
  和归档字节得以保留。
- 审查发现并修复 mode-仅 Frequency 偏好设置持久化间距，以及错误拒绝 kernel
  采样的 outdated 失败消息。回归测试覆盖变更后 Hz 默认的 save/reload 和
  全部范围/调用图失败 cases。
- 最终工作区测试集：1292 passed、0 failed、3 ignored。独立 Simpleperf 引擎：
  45 passed。完整格式、Clippy、所有目标检查和应用/worker 构建在现有警告
  下通过，final 源码哈希保持一致。
- 原生 QA 使用隔离的数据根节点和只读 simulated ADB。Inspector 夹具导入且历史
  持久化。最新采集 attempts 再次被 confirmed locked macOS 会话阻塞；不宣称菜单点击、
  截图或 native 采样-控件验收已完成。QA 进程已回收。

当前证据：`../build/rust-parity-capture-recents-20261111/REPORT.md`。
目标仍当前活动的。CPU 时长/manual stop、capability/目标发现、other 性能分析器历史、
设备/native 验收以及 `FUNCTIONAL_PARITY.md` 中剩余的完整范围仍需继续处理。未
依赖、提交或推送。
