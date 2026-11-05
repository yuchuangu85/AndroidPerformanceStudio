# Rust HAR Engine

第一阶段的独立 HAR 摘要引擎。crate 仅依赖 Rust 标准库，不引入或复用新的
Rust 第三方 crate；现有 Kotlin `HarParser`/`NetworkAnalyzer` 入口保留，Kotlin
入口通过显式配置的 shadow 模式接入，不替换正式网络模型。

第二阶段新增实验性 `stream-entries` 传输与 Kotlin `NativeHarParser` 正式模型适配。
默认仍为旧 parser + 可选 summary shadow；没有默认切换，也没有把 NetworkAnalyzer
Rust 化。旧 parser 入口保留，单 entry 映射和 session 汇总抽取为两入口共享实现。

## 解析边界

- 从 `Read` 流读取根对象和 `log` 对象；`log.entries` 中每个 entry 单独解析并
  立即合并到摘要，避免把整个 HAR 或 entries 数组保存在内存中。
  内存随单个有界 entry 和 Top-10 变化，不随 entries 总量线性增长；尚无大文件 RSS 基准。
- 支持 HAR 1.1/1.2、请求 method/url/bodySize、开始时间、响应 status/httpVersion/
  bodySize/content.size、`time` 和七个 HAR timing phase。
- `ssl` phase 的输出 `parentKind` 固定为 `connect`；`-1` 是 HAR 的缺失值，负数
  字节值和其他负 timing 会被拒绝或计入 invalid（与字段语义一致）。
- 保留最多 10 条慢请求；不保留 URL、headers、body 或 producer 字符串。
- 每个 entry 有 2 MiB 字节预算、64 层嵌套预算、256 KiB 字符串预算、50,000 个
  JSON value 预算和 4,096 个 object member 预算。HAR 默认最多 1,000,000 entries。
  任一预算、截断 JSON、重复 key、非法 UTF-8/escape、非法数字或尾随 JSON 都使
  整个输入失败，不返回部分成功。

## CLI 输出契约 v1

```text
aps-har-engine summarize <file.har|->
```

`summarize-tsv <file.har|->` 仅输出 `formatVersion` 和 `entryCount` 两个数值，
供 Kotlin 共用 shadow client 使用。使用 `APS_HAR_ENGINE` 或 JVM 属性
`aps.harEngine.path` 显式配置 binary 后，HAR 导入执行此摘要比较；未配置时不运行。
`buildRustHarEngine` 和 `testRustHarEngine` 位于 desktop 根 Gradle 构建。

`-` 从 stdin 读取。成功时 stdout 输出一行 JSON，退出码为 0；失败时 stdout 为空，
stderr 输出诊断，退出码为 1。成功 JSON 顶层字段稳定为：

`protocolVersion`、`harVersion`、`entryCount`、`statusFamilies`、`requestBytes`、
`responseBytes`、`decodedResponseBytes`、`durations`、`phases`、
`slowestRequests`。数值单位为毫秒或字节；`phases` 包含 `blocked`、`dns`、
`connect`、`ssl`、`send`、`wait`、`receive`。输出只包含 HAR 可直接证明的摘要，
不会假造并发、连接复用或脱敏后的网络模型字段。

- 三个字节摘要各包含 `knownCount`、`unknownCount`、`totalBytes`。
- `durations` 和每个 phase 包含 `sampleCount`、`missingCount`、`invalidCount`、
  `meanMs`、`minMs`、`maxMs`；没有有效样本时三个耗时为 `null`。
  timing 缺省、`null` 或 `-1` 算 missing；其他负值算 invalid，不混入统计。
  SSL 包含在 connect 内，不应将两者相加，也不输出推测的 phase 起止时间。
- 每条慢请求包含零基 `entryIndex`、`method`、`startedDateTime`、`status`、
  `protocol`（可为 `null`）、`durationMs`。按耗时降序、原 entry 索引升序排序。
  开始时间仅保留原始非空字符串，未做日期合法性或时钟归一化。
- 支持空 entries（输出零计数）；未知 JSON 字段会被完整校验再丢弃。
  大于预算的嵌入 body 也会失败，即便不需要该字段；本阶段不是完整 HAR schema 验证器。
- Kotlin wrapper 应校验 `protocolVersion == 1`，忽略以后新增的字段；不依赖对象 key
  顺序或 stderr 的具体错误文本。v1 既有字段类型/语义不做破坏性变更。

## 构建与验证

需要 Rust 1.85 或更新版本：

```sh
cargo test --locked --offline
cargo clippy --locked --offline --all-targets -- -D warnings
cargo fmt -- --check
cargo run --release -- summarize ./sample.har
```

`Cargo.lock` 随 crate 一起维护，当前只包含本地 package，便于上层接入时确认
没有新增 Rust registry 依赖。

## 实验性正式模型适配

```sh
aps-har-engine stream-entries ./sample.har
```

该命令 stdout 是**私密数据通道**，不是摘要或诊断。不要保存到日志或发送给 AI。
它传输完整但单条有界的原 entry（包含 URL、headers、body），由 Kotlin 解析单条
JSON，调用共享旧模型映射和 Kotlin redactor。Rust 不伪造脱敏结果，不建全 HAR 树；
Kotlin 仅保留有界页和已脱敏模型，不读取完整 HAR bytes 或完整 HAR JSON 树。
两边都不创建原 entry 临时文件。最终 `NetworkCaptureResult.calls` 仍为内存列表，
并非磁盘分页数据库，也不声称整个正式模型内存与 entry 总量无关。

传输 v1 是 UTF-8 + ASCII 控制头的长度分帧协议，不依赖 JSON 换行或 key 顺序：

```text
APS-HAR-ENTRIES\t1\n
entry\t<zero-based-index>\t<byte-length>\n<raw JSON value bytes>\n
version\t3\n1.2\n
creator\t<byte-length>\n<raw JSON value bytes>\n
end\t<entry-count>\n
```

- `version`/`creator` 可出现在 entry 前后；creator 可省略，version 必须出现且为
  1.1/1.2。entry 索引连续。字段顺序保持输入顺序，原 JSON 数组/header/body 不丢失。
- entry 结构错误（如缺 request、null entry）仍传输，由共享 Kotlin mapper 跳过并
  产生只含索引的固定警告。语法错误、重复 key、非法 Unicode、超限、尾随 JSON
  拒绝整个导入。不把旧 summary 的严格语义验证改为跳过坏 entry。
- 失败可能已经输出部分帧。Kotlin 只有收到 end、校验计数/EOF 并确认进程退出码为
  0 后才接受该遍；任意失败都丢弃中间模型，不发布“部分成功”。stderr 丢弃，
  exceptions 不携带原 JSON 或 native 文本。共享 mapper 的坏 entry 警告也不带异常原文。
- Rust 沿用上述单值/深度/字符串/节点/member/entry 数预算。Kotlin 控制头至多
  128 bytes，单值至多 2 MiB，每页默认 16 条且原文累计至多 2 MiB（两者先到者），
  页大小可设 1..256，单遍通道累计上限为 `2 * maxBytes + 4096`。
- 默认输入上限 512 MiB，整体 deadline 120 秒，最多 1,000,000 entries；
  `maxBytes` 不可提高至 512 MiB 以上。取消 callback/线程中断和 deadline 在 pipe
  无输出时也轮询，失败/取消均关闭通道、销毁并等待 native 进程退出。
- 两遍 native 流：第一遍确定所有 entry 的最早合法 startedDateTime（包括最终被跳过
  的 entry），第二遍共享 mapper 生成正式模型。无合法时间时用 Instant.EPOCH。
  开始、两遍之间、结束分别流式 SHA-256 校验。输入应保持不可变；指纹检查不是原子
  文件快照，无法证明主动并发修改后恢复的情形。没有大文件 RSS/吞吐基准。
- missing/null/-1/invalid timing、SSL nested connect、creator、请求/响应 wire byte
  sizes 和 decoded size、error、header/URL redaction 均由旧 Kotlin 逻辑决定。
  Rust strict JSON 与 Kotlin lenient JSON、超大 body/entry 无法完全等价，明确 reject，
  不输出推测数据。NetworkAnalyzer 完整复用旧正式逻辑，未 Rust 化。

### Kotlin 入口与配置

直接调用 `NativeHarParser(executable).parse(path, checkCancelled)`，或给 desktop JVM
设置属性（不会新增 UI 控件）：

- `aps.harEngine.path=/absolute/path/aps-har-engine`（或 `APS_HAR_ENGINE`）指定 binary。
- `aps.harEngine.mode=shadow`：默认；旧 parser authoritative，已配置 binary 时沿用
  原 summary shadow，不把完整 entry 写入 shadow properties。
- `aps.harEngine.mode=native-experimental`：显式 native entry 传输，失败直接拒绝。
- `aps.harEngine.mode=native-with-legacy-fallback`：显式允许失败后用旧 parser，并添加
  固定 fallback warning。**fallback 会恢复旧 parser 的完整 HAR JVM 内存占用**。
  取消不会 fallback；未知 mode 拒绝配置。

### 跨语言验证

从 `desktop-viewer/` 运行，测试 binary 必须先构建；不设置环境变量时，依赖实际 Rust
的 Kotlin 测试会显式 skip，不能据此声称跨语言 parity：

```sh
cargo test --manifest-path network-profiler/native-engine-rust/Cargo.toml --locked --offline
APS_HAR_ENGINE_TEST="$PWD/network-profiler/native-engine-rust/target/debug/aps-har-engine" \
  ./gradlew :network-profiler:parser-har:test :network-profiler:network-app:test --offline --console=plain
```

Synthetic parity 比较完整 calls、session（只归一化随机 UUID）和旧 analyzer 摘要；覆盖
跨页、较大 body、Unicode、HAR 1.1/1.2、后置 creator/version、坏 entry baseline、
timing/SSL/byte/error/redaction，以及 native strict reject、显式 fallback、取消、
超时和进程回收、坏协议/尾随输出/非零退出、输入指纹变化。不是实际用户 HAR corpus、
GUI、打包、release 或性能验收。
