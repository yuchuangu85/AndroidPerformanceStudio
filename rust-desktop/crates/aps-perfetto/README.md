# 固定版本的 Trace Processor 适配器

此 crate 复用真实的 Perfetto v59.1 可执行文件，绝不重新实现 trace 解析。
各主机的精确 SHA-256 值与现有规范文件
desktop-viewer/platform-perfetto/trace-processor-manifest.json 一致；虽然声明了
全部五个主机，但主机声明不是跨平台验证证据。解析器只接受显式的包/源代码检出
路径，在执行 --version 前检查 SHA-256，并在每次操作前重新检查校验和。
不存在未经验证的 PATH 回退或下载。Rust 打包必须独立包含该固定版本的可执行文件
及其 notice 文件。

## 每会话预热的 Trace Processor

经过验证的 v59.1 CLI 提供一个预热的 Unix 套接字 session：

    trace_processor_shell server unix --name SESSION --daemonize --quiet \
        --idle-timeout 30m TRACE_FILE
    trace_processor_shell query --remote SESSION --format csv --quiet "SELECT ..."
    trace_processor_shell server kill SESSION

TraceSession::open 会启动一个守护化 server，因此每个打开的 session 只解析一次
trace。Diagnostic、search 和 QueryPreset 分页查询都通过 query --remote 对该
server 执行。名称按 process/trace 生成
（aps_<pid>_<counter>_<first 8 digest hex characters>），符合固定版本的
[A-Za-z0-9][A-Za-z0-9_-]* 语法，且限制为 64 个字符。每个 client process 都以
env_clear = true 启动，因此 session 目录是确定的（已验证的 macOS 构建中为
/tmp/perfetto）；由于 --daemonize 会将工作目录改为 /，trace path 会被规范化。

生命周期保证：

- close() 和 Drop 会针对准确的、由其拥有的 session name 执行 server kill。
  server 启动失败和取消也会尽力 kill 该名称，因此不会留下部分启动的 daemon。
- daemon 以 --idle-timeout 30m 启动。如果主机进程崩溃导致 Drop 无法运行，
  固定工具会在空闲 30 分钟后回收孤儿 session，而不是让它永久泄漏。
- 除取消/超时/输出上限外，remote query 失败会将 session 标记为 degraded，
  并通过单次执行路径对该 query 重试一次。名称会一直保留到 close/Drop，确保 daemon 仍会停止。
- 如果主机上无法使用 daemonization 或 remote sessions（例如非 POSIX 的 --daemonize build），
  open 会先使用一个有界的单次 SELECT 验证 trace，再接受回退。无效 trace 仍会在
  open 时以 PerfettoError::InvalidTrace 失败即关闭。
- 降级是显式的：TraceSessionMode::Warm、TraceSessionMode::OneShotFallback 或
  TraceSessionMode::Closed，可通过 mode() / is_warm() 查看。单次执行路径是文档化的回退，
  不会在没有标记的情况下静默地逐 query 重新解析。
- close() 之后的 query 会以 PerfettoError::SessionClosed 失败。
- TraceSession 是 Send + Sync + 'static（app 持有 Arc<TraceSession>，并从后台执行器查询它）。

单次执行路径仍然存在：query --format csv --no-agent --quiet TRACE SQL。它用于
回退 session 和启动验证 query。每个 process 仍有有界 stdout（4 MiB）、有界 stderr
（64 KiB，绝不显示）、120 秒查询超时（warm startup 为 300 秒）、ProcessRunner 的
取消/终止/回收契约，以及固定工具的校验和/版本检查。
ProcessRunner 会终止直接子进程组；守护化 server 调用 setsid，因此它只会由显式的
server kill / 空闲回收器停止，不会因意外终止 client 的进程组而停止。

## 诊断目录、预设与发现结果

- diagnostics.rs 是 Compose PerfettoDiagnostics.all 的直接移植：包含 35 个
  DiagnosticQuery 条目，id、category、title、description、声明的列名/顺序和 SQL body
  均相同。预设与 PerfettoDiagnosticPresets（core、jank、scheduling、binder、io、memory、
  power、all）一致；diagnostic_preset() 复现 byId 回退到 core 的行为。
- DiagnosticCategory 是 Compose 分析枚举
  {Cpu, Binder, Graphics, Memory, Input, Io, Runtime, Power}，提供 name() 和
  display_name()。DiagnosticQuery::insight_category() 复现 Compose 的
  TraceInsightCategory 映射（frame_jank -> Frame、main_thread_blocked -> LongTask、
  binder -> Binder、graphics -> Rendering、cpu/memory/input，否则为 Other）。
- DiagnosticQuery::timestamp_column() 复现 Compose 的 timelineTimestampColumn() 推导；
  如果存在声明的 duration_ns column，duration_column() 会报告它。DiagnosticResult 只携带
  声明的 columns、保留 NULL 的 cells、schema_version = PINNED_VERSION（"v59.1"）以及
  truncated 标志。
- findings.rs 移植 DiagnosticFindings.evaluate：阈值相同（jank ratio 0.05、Binder 100/16 ms、
  blocked 500/100 ms、input 200/100 ms、wakeup 5000 us），每个 query evaluator 相同，
  first-row 与 max-with-subject 规则相同，启发式标志和空行的 NO_DATA 也相同。
  这里不会生成本地化字符串；UI 负责本地化 metric kinds。
- search.rs 移植 TraceSliceSearch：会丢弃控制字符，裁剪 term，限制为 120 个字符，转为小写，
  并嵌入单引号 SQLite literal，所有引号都加倍。空白 term 不产生 query。结果受 LIMIT 限制
  （默认 200；search_slices 接受明确的 1..=200 limit），并像 Compose 一样按 slice duration
  降序排列。search_slices_bounded() 还会返回截断标志。

## 结果解码与边界

- CSV 解码器接受包含逗号/CR/换行以及双引号的带引号 cell、LF/CRLF/CR 行结尾、空 cell、
  字面量 "[NULL]" 标记和 UTF-8。含糊的输出（未加引号的 cell 中出现引号、关闭引号后仍有
  数据、未闭合引号、无效 UTF-8、超过 16 列、单个 cell 超过 32 KiB、行数超过调用方上限）
  会以 InvalidResult 失败即关闭。上游 formatter 不会转义 trace text 中的引号，因此这类
  trace result 会被拒绝，而不是被错误解析。
- 每个 result set 在发布任何 row 之前，都会按照顺序针对 query 声明的 columns 进行校验；
  header drift 或行过短/过长都会失败即关闭。
- SQL NULL 是 None；空 cell 是 Some("")。"[NULL]" 文本标记按构造存在歧义，会映射为
  None（与 Compose 相同）。
- 单个 diagnostic page 上限为 MAX_DIAGNOSTIC_ROWS = 2000 行，每个 text cell 上限为
  MAX_DIAGNOSTIC_CELL_CHARS = 4096 个字符（在 UTF-8 边界处截断）。当页面达到行上限
  或 cell 被截断时，truncated 为 true。
- QueryPreset 分页保留现有的 LIMIT+1 前瞻、200 行页面上限、受限的十六进制编码名称预览
  以及八页/2 MiB session 缓存。

## 公共 API（由 aps-app/aps-gpu 使用）

    pub const PINNED_VERSION: &str
    pub const CORE_PRESET_ID: &str
    pub enum DiagnosticCategory { Cpu, Binder, Graphics, Memory, Input, Io, Runtime, Power }
    pub struct DiagnosticQuery { id, category, title, description, columns, sql }
    pub struct DiagnosticPreset { id, title, query_ids }
    pub struct DiagnosticResult { query_id, schema_version, columns, rows, truncated }
    pub enum DiagnosticFindingLevel { Critical, Warning, Info, NoData }
    pub enum DiagnosticMetricKind { JankyFrames, MaxLatencyMs, MaxBlockedMs,
        MaxWakeupUs, PeakRssKb, TopCpuMs, TopFrequencyMhz, TopStateMs, Rows }
    pub struct DiagnosticMetric { kind, value, total, subject }
    pub struct DiagnosticFinding { level, metric, heuristic }
    pub struct SearchHit { timestamp_nanos, duration_nanos, name, thread_label }
    pub fn diagnostic_queries() -> &'static [DiagnosticQuery]
    pub fn diagnostic_presets() -> &'static [DiagnosticPreset]
    pub fn diagnostic_query(id: &str) -> Option<&'static DiagnosticQuery>
    pub fn diagnostic_preset(id: Option<&str>) -> &'static DiagnosticPreset
    pub fn evaluate_finding(query, result) -> Option<DiagnosticFinding>
    pub fn TraceSession::open(tool, path, cancel) -> Result<TraceSession, PerfettoError>
    pub fn TraceSession::query(preset, offset, limit, cancel) -> Result<QueryPage, _>
    pub fn TraceSession::diagnostic(query, cancel) -> Result<DiagnosticResult, _>
    pub fn TraceSession::search_slices(term, limit, cancel) -> Result<Vec<SearchHit>, _>
    pub fn TraceSession::search_slices_bounded(term, limit, cancel) -> Result<SliceSearchResult, _>
    pub fn TraceSession::mode() / is_warm() / warm_session_name() / close()

现有的 QueryPreset、QueryPage、TraceProcessor 以及 TraceSession::open/query/
source_digest 行为会为 aps-gpu 和 aps-app import 保留。

## 验证证据与缺口

此 crate 的测试覆盖 catalog/preset 不变量；其中 oracle 测试会读取
PerfettoDiagnostics.kt 和 PerfettoDiagnosticPresets.kt，并比较每个 id、category、title、
description、列列表和 SQL body；还覆盖 findings 阈值、search 转义/边界、
CSV 边界情况、有界解码，以及使用 fake tool 的 warm-session 生命周期（warm、
degraded startup、lost server、close）。固定工具集成测试会针对
desktop-viewer/desktop-app/src/test/resources/fixtures/synthetic-empty.pftrace 运行全部
35 个诊断、core preset 和 slice search；如果固定工具或 fixture 缺失，则会正常跳过。
在已验证的 macOS arm64 主机上，固定版本 v59.1 二进制会启动 warm daemon（mode Warm），
提供完整 catalog，且 Drop 后不会留下 trace_processor_shell server 进程。

已知缺口：warm session 按 TraceSession 实例划分，不在 session 之间共享；丢失的 daemon
不会重启（session 会保持单次执行回退，直到重新打开）；远程查询超时无法证明
daemon 是否已完成被放弃的 query；非常大的 trace 会使同步 SHA-256 扫描和 warm startup
成为长时间运行的操作，它们依赖执行器调度以及 block 之间的取消，而不是硬性的
I/O 取消；除了 length/mtime 之外，无法检测时间戳相同的恶意文件替换；大型 trace 的
RSS、打包后的原生交互以及 Windows warm-session 行为仍未验证。
CrossDomainAttributionAnalyzer 有意未移植：它是 presentation 层重叠关联器，
没有任何诊断、preset、finding 或 search path 向它提供数据或从它消费数据（已通过在
perfetto-viewer 中 grep 验证），而固定 engine API 也不包含它。
