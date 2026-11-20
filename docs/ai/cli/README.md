# 不打开桌面：从性能文件或设备场景生成分析报告

`aps` 是 Rust 工作区的独立可执行文件。**默认只在本机解析并输出证据，不调用模型。**
CLI 和 [stdio MCP](../mcp/orchestrator.md) 使用同一套解析器和证据 ID。原来的 Memory、CPU、Frame MCP 和桌面入口不需要改配置。

## 构建与第一次调用

```sh
cd rust-desktop
cargo build --locked --release -p aps-cli -p aps-memory -p aps-benchmark
export PATH="$PWD/target/release:$PATH"
aps --help
aps capabilities
aps analyze /path/to/capture.har
```

Windows 使用对应的 `.exe`。复制二进制时，将 `aps`、`aps-orchestrator-mcp`、`aps-memory-worker`（HPROF）和 `aps-benchmark`（命令转发）放在同一目录。此处描述从源码构建的程序，不代表旧桌面安装包已包含 CLI。

### 已有本地文件或下载链接

```sh
aps analyze ./profile.simpleperf.pb --evidence-out ./evidence.json
aps analyze 'https://artifacts.example.org/test/frame-report.json' --format json
aps analyze ./startup.pftrace --kind trace
aps analyze ./frame-analysis.json --scope report
aps analyze ./frame-analysis.json --frame-id 17
```

- 根据内容选择解析器，不确定或超出预算时报错。Perfetto 也识别 `.pftrace` / `.perfetto-trace`；无标志、无后缀时用 `--kind trace`。统一 CLI 文件上限 256 MiB，各解析器可以更严格，大型转储可另用专用工具。
- HTTPS 是宿主获取一个用户明确指定的 URL，不是爬取页面或解析云盘分享页。只接受成功的完整 HTTP 200 响应；拒绝重定向、URL 用户信息、fragment 和明文 HTTP。不读代理/站点登录配置；连接超时 10 秒，总超时 90 秒。按实际字节数限流，临时文件随调用释放。带签名 URL 本身可能出现在调用者的 shell 历史中。
- 本地路径/下载地址不是证据身份：`input.sourceDigest` 是内容 SHA-256。每个 `evidence.items[]` 有 `id`、`label`、`value`；`null` 表示缺失而非 0。JSON 供脚本或外部 AI 使用；成功时 stdout 是 JSON，错误写 stderr，命令返回非零。显式输出路径需父目录已存在、文件尚不存在；不覆盖输入、证据或已有报告。

**复现桌面 Frame 分析：** 不指定范围时保留原聚合摘要和证据 ID。对同一份有效的 gfxinfo 或 v3 文件，`--scope report` 输出报告级指标和最多 8 行，`--frame-id 17` 只列出 ID 为 17 的帧（不是第 17 行），仍保留整份报告的统计背景。两者互斥；帧不存在时报错，不退回整份报告。桌面重新打开相同文件、选择相同范围时，证据按文件内容而非本地历史会话编号绑定。实时采集有独立身份；普通文件的 `appTraceOverlaps: null` 表示缺少可验证的探针凭据，并非实测零次重叠。

**可分析的输入：**
- **CPU**：SIMPLEPERF protobuf、Gecko v24 JSON；输出热点、样本及覆盖，采样权重不是方法耗时。原始 perf.data 在 Unix 需显式设置 `APS_SIMPLEPERF` 指向兼容、可信的宿主 Simpleperf 绝对路径（转换及原始/派生内容摘要会进入证据）；gzip/会话归档不是直接输入。
- **Frame / Trace**：gfxinfo FrameStats、APS v3 帧报告；Perfetto protobuf 可返回边界、CPU 调度和 FrameTimeline 观测。Trace 使用摘要与版本匹配的 `trace_processor_shell`（同目录，或宿主通过 `APS_TRACE_PROCESSOR` 指定绝对路径），不从 PATH 自动安装。高级帧凭据/基线比较仍用 [Frame MCP](../mcp/frame.md)。
- **Memory / Network**：HPROF 通过同目录 worker 验证来源并输出堆摘要/候选数量，不能直接确认泄漏；HAR 输出请求与耗时等聚合，不发送 URL、headers 和 body。
- **Benchmark / Startup / Method Recording**：AndroidX Benchmark JSON、APS Startup CSV / JSON v1、ART method trace。分别保留指标单位、启动指标缺失和方法记录时钟；单个 Benchmark 摘要不是回归判定；[基准回归 CLI](benchmark.md) 可比较明确指定的基线与当前报告。Startup 可按运行分页、按模式或缺失指标筛选，见 [启动分析 CLI](startup.md)。
- **Battery / Layout Inspector**：APS schema-v1 电池报告、经清单与身份校验的 Inspector ZIP。只输出有界观测，不将导入报告当作实时采集，也不向模型发送截图和节点值。

### 让 APS 调用模型并生成报告

```sh
# 先在本机显式配置 APS_CLI_AI_KEY（由调用方安全注入，不放进命令或 MCP 配置）
export APS_CLI_AI_ENDPOINT='https://provider.example.org/v1'
export APS_CLI_AI_MODEL='your-model'
aps analyze ./capture.har --evidence-out ./evidence.json \
  --ai review --question '耗时观测是否足以支持网络瓶颈判断？' --out ./report.md
```

CLI 使用 APS 已有的 **OpenAI 兼容 Chat Completions** 网关（不是桌面 Provider 首选项、订阅登录、Responses API 或任意本地模型命令）。HTTPS 或明确配置的本机回环 HTTP；运行时需要 `curl` 和兼容结构化响应的模型。模型只收到终端展示的精确请求，不会拿到原始工件或自由执行工具的权限。先产出证据，后在交互终端核对 endpoint、模型、请求内容与报告路径，输入本次专属确认词后才读取凭据、发出请求。没有第二次自动重试或备用模型。

报告支持 `.md`、`.html`。每一条 finding 必须引用本次冻结的证据；响应不合法、引用越界、用户取消、网络错误或落盘失败，均不会返回报告成功。已经输出的证据仍可用。GUI 与 CLI 分别记录分析状态和报告状态：桌面可保留已验证的回答并提示归档失败；CLI 显式要求 `--out` 时，写盘失败仍以非零退出，且不会再调用模型。无交互 CI 可运行纯证据模式，不应伪造终端确认；也可由受控的外部 MCP 客户端按自己的审批策略处理证据。

### 自动执行已有设备场景

默认只预览。创建一个本地 `feed.json`，指向**你信任的** AndroidX Macrobenchmark 工程；它的 Gradle 测试需实际实现滚动场景并生成下述结果文件：

```json
{
  "schemaVersion": "aps-scenario-v1",
  "kind": "macrobenchmark",
  "project": "../my-benchmarks",
  "result": "build/outputs/fresh-benchmark.json",
  "scenario": "scroll",
  "iterations": 5,
  "timeoutSeconds": 300
}
```

```sh
aps run ./feed.json --device emulator-5554
# 检查 plan 的工程、设备、wrapper、参数、结果路径；替换下方摘要后执行：
aps run ./feed.json --device emulator-5554 --approve '<approvalDigest>' \
  --evidence-out ./scroll-evidence.json --ai review --out ./scroll.md
```

`result` 相对工程目录，必须是本次**新生成的**常规 JSON；已有文件、越界路径、运行超时或测试未产生有效 Benchmark 数据均拒绝发布。预览摘要绑定设备、wrapper 字节、执行参数和输出位置，**不验证工程内所有脚本的可信性**；不要执行未审查的外部 Gradle 项目。场景是已实现测试的参数（startup、scroll、animation、page-switch），不是把自然语言转成任意 ADB/Shell。设备执行和将结果发给模型是两次独立授权。如已有依赖原命令的 CI，`aps run macro-plan|macro-run ...` 直接交给同目录的 `aps-benchmark` 并保留其参数与退出码；此兼容入口不等于上述带新工件校验的场景模式。

也可在 Unix 宿主上对已授权设备的进程做定时 CPU 采集，不需要 Gradle 项目：

```sh
aps capture cpu --device emulator-5554 --pid 1234 --seconds 10 \
  --adb /absolute/path/to/adb --converter /absolute/path/to/simpleperf \
  --artifact-dir /existing/captures --plan
```

移除 `--plan` 才进入交互确认，可附加 `--evidence-out` 和 `--ai review --out`。PID、设备 boot ID、进程启动 tick 须与计划匹配。只有原始记录和转换工件身份匹配、protobuf 解析成功且有样本才发布成功。预览仍会读取目标信息；不会开始录制。录制需要目标平台支持 Simpleperf、ADB 权限和兼容的宿主转换器。无符合条件的设备/宿主时应保留不可用或停止未确认状态。

### 用自然语言

在外部 AI 客户端的 stdio MCP 配置中将 command 设为 `aps` 的绝对路径，args 设为 `["mcp", "--input", "/absolute/path/to/capture.har"]`；已知 HTTPS 工件可改用 `["mcp", "--artifact-url", "https://artifacts.example.org/run/capture.har"]`，由宿主在启动时获取一次。然后问：「这份 HAR 的时延证据支持什么结论？引用证据 ID，并指出缺失项。」外部客户端可以根据摘要继续追问同一份工件：例如列出 HAR 最慢的 3 条请求，再比较阶段统计；或先找错过截止时间的帧，再看单帧证据。每个绑定最多 8 次 `aps_follow_up`，不绑定文件时只能发现能力、预览步骤。[工具契约和配置示例](../mcp/orchestrator.md)。

**未开放的操作不会伪装成成功：** 通用 GPU/AGI 工件解析、源码工作区查询、任意自然语言设备动作，以及除上述两类以外的专用自动录制流程，仍用原桌面/领域入口；MCP 工具不下载、不录制、不写报告、不授权模型。扩展时，离线解析加入共享证据适配器与 `aps capabilities`；有副作用的采集加入显式的 host 计划/确认流程，不往只读工具添加命令或路径参数。

能力发现里的 `deferred.capture` 和 `deferred.input` 是预留的稳定名称。例如 `aps capture startup` 或 `aps analyze file --kind agi` 目前返回 `E_CAPABILITY_UNAVAILABLE`，不会尝试运行设备命令或生成占位报告。
