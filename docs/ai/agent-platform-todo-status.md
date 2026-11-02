# AI Agent Platform TODO 状态统计

对应主 TODO：[AI Agent Platform TODO](agent-platform-todo.md)

## 统计信息

- TODO 创建日期：**2026-10-30**
- 首次统计日期：**2026-10-30**
- 最近统计日期：**2026-10-31**
- 统计范围：独立 AI Platform、Codex/Claude/OpenAI provider、Agent/Skill 注册与导入、
  脚本运行时、全局 Compose 对话窗口和跨 Profiler 接线。
- 统计口径：只统计主 TODO 中带 checkbox 的实施项；已有 `ai-core`、Layout AI 或
  Simpleperf AI 接口不自动计入新平台 TODO 的完成项。

## 总览

| 状态 | 数量 | 占比 |
|---|---:|---:|
| 已完成 | 15 | 65.2% |
| 进行中 | 0 | 0% |
| 部分完成 | 7 | 30.4% |
| 未开始 | 0 | 0% |
| 阻塞 | 1 | 4.3% |
| **总计** | **23** | **100%** |

当前结论：**统一桌面已接线并通过本地集成、Compose 和打包验证，规划尚未全部完成。**
独立 composite 已扩展到 10 个模块，包含全局 Chat/设置、注册管理、引用式结构化历史、
Provider 进程、通用独立 child/脚本图与 11 类业务上下文。Codex Responses 路径已实现，CLI
仍不可用；Linux arm64 Python/Node 有原生证据，macOS/Windows 保持 fail closed。
真实 API 验收各一次超时，hosted/签名/公证外部前置缺失，不能宣布全部完成。百分比为四舍五入值。

## 阶段统计

| 阶段 | 内容 | 总数 | 已完成 | 进行中 | 部分完成 | 未开始 | 阻塞 |
|---|---|---:|---:|---:|---:|---:|---:|
| P0 | 契约和边界 | 4 | 4 | 0 | 0 | 0 | 0 |
| P1 | 独立模块和文件系统 | 3 | 3 | 0 | 0 | 0 | 0 |
| P2 | Provider Broker | 4 | 2 | 0 | 2 | 0 | 0 |
| P3 | Agent / Skill Runtime | 3 | 2 | 0 | 1 | 0 | 0 |
| P4 | 全局对话窗口和业务接线 | 4 | 3 | 0 | 1 | 0 | 0 |
| P5 | 发布和验收 | 5 | 1 | 0 | 3 | 0 | 1 |
| **总计** |  | **23** | **15** | **0** | **7** | **0** | **1** |

## TODO 明细

所有条目的创建日期均为 **2026-10-30**。状态变更时必须同时更新本表的状态、完成日期和证据列。

### P0：契约和边界

| ID | TODO | 状态 | 创建日期 | 完成日期 | 证据 / 下一步 |
|---|---|---|---|---|---|
| P0.1 | 冻结 `ai-platform-model`、Agent/Skill manifest、ProviderEvent、Permission、Approval 和 Conversation schema | 已完成 | 2026-10-30 | 2026-10-31 | `Manifest.kt` / `Provider.kt` / `Conversation.kt`、6 个 model/architecture tests；v1 in-process provider 契约，严格 manifest、权限、审批、conversation/audit codec；未冻结外部 SDK 协议 |
| P0.2 | 将 `ai-core` 明确为底层 contract/provider adapter，禁止业务直接依赖 Codex/Claude SDK 类型 | 已完成 | 2026-10-30 | 2026-10-31 | 独立 composite 依赖 `ai-core`；`PlatformArchitectureTest` 扫描业务源码禁止 provider SDK import；未引入 Codex/Claude SDK |
| P0.3 | 冻结 `~/.android-performance-studio` 目录、origin/trust/version/atomic install 规则 | 已完成 | 2026-10-30 | 2026-10-31 | ADR-0037 + `PlatformDirectories` / registry；builtin/imported 均 `<id>/<version>`，hash 固定、host-owned trust、原子发布及失败测试 |
| P0.4 | 写入 provider credential、脚本沙箱和全局 Chat 主入口 ADR | 已完成 | 2026-10-30 | 2026-10-31 | ADR-0037 固定凭证、权限和执行边界；UI/图编排接线与逐平台/发布证据分别记录在 P3/P4/P5 |

### P1：独立模块和文件系统

| ID | TODO | 状态 | 创建日期 | 完成日期 | 证据 / 下一步 |
|---|---|---|---|---|---|
| P1.1 | 创建 `desktop-viewer/ai-platform` composite build 及基础模块 | 已完成 | 2026-10-30 | 2026-10-31 | includeBuild + 10 模块；独立于 Profiler 实现 |
| P1.2 | 首次启动安装 builtin Agent/Skill，支持校验、扫描、禁用、撤销和 imported 原子导入 | 已完成 | 2026-10-30 | 2026-10-31 | 启动安装、独占 profile、全局设置管理；30 个 registry tests、隔离 bootstrap/reopen、取消交付及关闭测试；升级保留 imported/禁用/撤销状态 |
| P1.3 | 覆盖 malformed manifest、duplicate ID、path traversal、symlink、hash mismatch 和脚本超限 | 已完成 | 2026-10-30 | 2026-10-31 | registry 30 + model 6 tests：malformed/重复或歧义 ID、重复 JSON key、traversal、symlink、hash、脚本/文件超限、unknown runtime、atomic failure、锁冲突、版本选择与升级保留 |

### P2：Provider Broker

| ID | TODO | 状态 | 创建日期 | 完成日期 | 证据 / 下一步 |
|---|---|---|---|---|---|
| P2.1 | 实现 provider discovery、配置元信息 resolver、credential reference 和 capability matrix | 部分完成 | 2026-10-30 | — | LocalProviderConfigResolver 有界读取 allowlist 元信息，拒绝 symlink/损坏配置，不读取 auth 秘密；设置可检测 availability；缺完整 profile/custom config 选择与真实本机配置资格 |
| P2.2 | 接入现有 OpenAI gateway | 已完成 | 2026-10-30 | 2026-10-31 | GatewayAnalysisProvider 复用实际 OpenAiAnalysisGateway/OpenAiResponsesClient；5 个 fake-transport 集成测试（无真实网络），固定模型/引用/权限/凭据隔离 |
| P2.3 | 增加 Codex 和 Claude adapter，按实际 SDK/app-server/CLI capability 选择 transport | 部分完成 | 2026-10-30 | — | Codex 显式 Responses adapter 无工具/无存储/有界输出，9 个 Codex 模块测试；CLI 仍 unsupported，不回退。Codex/OpenAI 各一次实际合成传输超时，无成功推理；Claude 仍缺真实 API 凭据验收 |
| P2.4 | 增加 fake provider、streaming、timeout、cancel、approval、retry、child process 和 secret-redaction 测试 | 已完成 | 2026-10-30 | 2026-10-31 | Broker/Gateway、Claude/Codex fixtures 和 14 个进程回归：管道溢出、满 stdin 取消/超时、父退出/终止时派生子进程、环境最小化、错误脱敏、冻结请求、显式重试；不代表真实 SDK 流或所有 detached 进程均被捕获 |

### P3：Agent / Skill Runtime

| ID | TODO | 状态 | 创建日期 | 完成日期 | 证据 / 下一步 |
|---|---|---|---|---|---|
| P3.1 | 实现 Main Agent、Sub-Agent/Skill registry、provider broker、budget ledger 和 latest-generation-wins | 已完成 | 2026-10-30 | 2026-10-31 | 43 个 runtime tests：可信 imported 图、独立模型调用/会话、精确 child-run 审计、循环/深度/调用/共享输入输出时间预算、逐步撤权检查、取消/旧结果隔离；脚本预检先于 Provider。JVM 物理 CPU/内存不冒充 OS 硬限制 |
| P3.2 | 内置 Simpleperf、Source Evidence、Report Agent 及只读 Evidence/Source/Artifact Skill | 已完成 | 2026-10-30 | 2026-10-31 | 7 个 builtin 按冻结图实际运行；独立 child 结论和校验后的脚本输出以脱敏不可信派生分析反馈父 Agent，保留哈希/引用来源，不扩大证据 allowlist；4096 字符边界超限拒绝，不静默截断 |
| P3.3 | 实现 Python/Node sandbox，默认只读、无网络、无设备写和 bounded Artifact | 部分完成 | 2026-10-30 | — | bubblewrap/prlimit/seccomp helper 与产品/设置接线；Linux arm64 Python/Node 19 敌对场景、14 公开 API 检查通过，固定资源 ELF/SHA 校验；macOS/Windows 尚无合格后端，原生 x86_64/安装包仍待验证 |

### P4：全局对话窗口和业务接线

| ID | TODO | 状态 | 创建日期 | 完成日期 | 证据 / 下一步 |
|---|---|---|---|---|---|
| P4.1 | 在 `DesktopAppMainPage` 接入独立 `AiConversationWindowHost`，从所有页面提供入口 | 已完成 | 2026-10-30 | 2026-10-31 | 全局壳/菜单/AI 设置共享入口，独立窗口绑定不可变上下文；打开窗口、设置、历史与预检均不隐式发送 |
| P4.2 | 建立 Context Provider registry，接入 Layout、Simpleperf、Memory、Trace、Frame、Startup 等 | 已完成 | 2026-10-30 | 2026-10-31 | Layout、Simpleperf、Memory、Trace、Frame、Startup、Battery、Network、GPU、Benchmark、Source Workspace 共 11 类接线；切换/缺失/旧结果守卫；Source 只发布有界元信息，不发布路径/正文、不冒充性能测量 |
| P4.3 | 将 Layout AI 和 Simpleperf AI 接入统一 Main Agent，移除页面专用编排分支 | 已完成 | 2026-10-30 | 2026-10-31 | DesktopAiCoordinator 统一审批/执行；8 个回归覆盖冻结、选定 Agent、权限交集、独立 child 计数、取消和旧请求隔离；Web bridge 同一 client，原生 Firefox 节点映射不计入完成 |
| P4.4 | 支持对话恢复、child session、Finding/Evidence/Source 跳转和 Artifact preview | 部分完成 | 2026-10-30 | — | 有界结构化结果/调用审计/Artifact hash、历史只读恢复、HTML 校验预览、Finding 证据精确详情和授权源码跳转；缺业务 Profiler 光标跳转、独立 child session UI、完整历史 Source 重绑定与 Markdown 导出控件 |

### P5：发布和验收

| ID | TODO | 状态 | 创建日期 | 完成日期 | 证据 / 下一步 |
|---|---|---|---|---|---|
| P5.1 | macOS/Linux/Windows 创建目录、权限、provider discovery、script runtime 和 process 矩阵 | 部分完成 | 2026-10-30 | — | Linux arm64 的 Python/Node 原生隔离、进程与资源边界已验证；macOS 严格 profile/地址空间探针未通过，Windows 无合格后端；原生 x86_64、各平台真实 Provider 和安装包仍待验收 |
| P5.2 | 英文/中文、浅色/深色、1280x720/900x720 的 AI 设置和全局 Chat 测试/截图 | 已完成 | 2026-10-30 | 2026-10-31 | 9 个 Compose tests、40 张截图；新增可信 Agent 单选、默认关闭的脚本策略、冻结的模型/脚本调用次数；中文/英文与明暗/宽窄布局，零隐式发送 |
| P5.3 | secret egress、path traversal、untrusted prompt、script escape、provider timeout 和 stale context 对抗测试 | 部分完成 | 2026-10-30 | — | 新增 Linux 19 原生敌对与14 API 检查、派生结果/脚本键值脱敏与引用/预算守卫；真实 API 超时证据已记录，但仍无成功服务交互、跨平台原生与完整安全资格 |
| P5.4 | clean profile、已有目录升级、损坏配置、导入/回滚、无 provider 和无 credential 桌面冒烟 | 部分完成 | 2026-10-30 | — | 隔离 bootstrap/reopen、坏设置回退、原子导入/升级保留、profile 锁生命周期与 fake gateway Compose 已验证；缺实际安装包 clean/旧真实用户 profile 全流程 |
| P5.5 | hosted build、签名/打包和跨平台 provider runtime 资格验证 | 阻塞 | 2026-10-30 | — | 同 SHA 非发布 CI、Developer ID/Authenticode、公证/staple、JAR/JBR/工具完整性与唯一发布事务已实现并通过 fixture/复查；本机0签名身份、缺7个Apple签名环境引用、仓库未列出所需Secrets/Environment，当前源码未授权发布远端，因此没有 hosted/真实签名/公证结果 |

## 2026-10-31 本轮验证证据

从 `desktop-viewer` 串行执行，所有列出的测试均为本轮实际运行结果：

```sh
./gradlew :ai-platform:format --offline --console=plain
./gradlew :ai-platform:checkAll :ai-core:test \
  :platform-perfetto:platform-perfetto:test :desktop-app:test \
  :desktop-app:createDistributable --offline --console=plain
./gradlew :ai-core:test --offline --console=plain
```

- 平台：213 tests，212 通过、1 显式 live opt-in 跳过，零失败。model 6、registry 30、storage 28、
  providers 53、runtime 43、Claude 7、Codex 9、script runtime 14、app 14、presentation 9。
- `ai-core`：17 tests，零失败、零跳过，包括零工具/不存储/输出上限、拒绝工具/不完整/拒绝回复、
  commentary 与 final_answer 区分；预算测试显式返回 Unit，已确认由 JUnit 发现并执行。
- 桌面全量：265 tests，257 通过、8 跳过，零失败。跳过包括 7 个 Windows/JBR/PowerShell
  平台资格用例与 1 个默认关闭的真实配置 Provider 验收；不将跳过算作通过。
- platform-perfetto：35 tests，零失败、零跳过。包含 19 个 signed-resource fake-runner 回归；
  这不等同真实 codesign、双架构签名包或公证验收。
- 另行运行13个定向 suite（34 tests）及 ReportController（43 tests），合计77项通过，覆盖中立 payload、Layout、Simpleperf、
  Memory、Frame、Startup、Battery、Network、GPU、Benchmark、Trace、网页桥接/launcher 和
  ReportController；Source Workspace 的 12 tests 已包含在桌面全量中。
  一项额外 Controller 用例曾误等“已在 Call Tree 页面”的旧状态，现改为同时等待异步目标搜索值，
  不改生产过滤行为；原失败与重验日志分别归档，最终计数见证据 summary。
- `checkAll` 包含 10 个模块的编译、tests 和 ktlint。桌面编译及本机
  `createDistributable` 通过，产物位于
  `desktop-viewer/desktop-app/build/compose/binaries/main/app/AndroidPerfermanceStudio.app`。
- 40 张 Compose 截图位于
  `desktop-viewer/ai-platform/ai-platform-presentation/build/reports/ai-workspace/`，覆盖 Chat、
  Provider、Agent、Skill、Policy 的 8 种语言/主题/尺寸组合。已检查窄/宽聊天页及设置页图像，
  不作为原生 Windows/macOS 窗口控件或真实 Provider 验收。
- 普通测试使用隔离临时 profile 与假 Provider，不读取真实凭证或发起模型请求。
- 单独显式开启的配置验收通过现有 CredentialStore 使用 Keychain 引用，各发起一次 OpenAI/Codex
  合成输入传输尝试，均超时且未收到 HTTP 响应；没有自动重试，没有成功推理或真实账号资格。
  原回执 `requestsSent` 仅表示客户端 transport 调用次数；后续字段已改为 `transportAttemptCount`。
  没有保存密钥、原始回复或真实项目内容；Claude 没有 API 凭据，未做真实调用。
- Provider 本地进程测试实际启动固定 fixture executable，覆盖满 stdin、溢出、TERM resistant
  子进程、终止期间新子进程、正常退出清理与外部无关进程保护。只保证观察到的 handles；
  两次轮询间完全 detached 的进程、Windows Job Object 和任意恶意 executable 均无完整证明。
- Linux arm64 Ubuntu 24.04 Python 3.12.3/Node 18.19.1 在隔离容器内通过 19 个敌对场景与14个公开 API
  检查，使用实际 Kotlin argv、native seccomp helper、原有资源预算；没有放宽网络/host挂载。
  Mac 严格 profile/内存限制探针未通过，Windows 没有合格实现；保持 unavailable，不替换成普通进程。
  Main Agent 只调度已授信、显式授权、预检合格的脚本；Provider 自授 tool/approval 仍被拒绝。
- 会话保留引用；独立结构化结果/审计存储有界、脱敏的结论和 Artifact hash，不保存原始
  请求、源码、凭证或 provider 全文。恢复时不重放，也不把先前实时证据误当历史 canonical payload。
- CPU ledger 使用 elapsed time 保守上限，非精确进程树 CPU；JVM/transport 内部内存分配无进程级硬限制。
- registry/JDBC 路径预检不等于抵御同权限恶意进程的原子文件系统沙箱；非 POSIX ACL 和跨平台资格待验收。
- 发布脚本 51 个不同 fixture 通过（unittest discovery 共76次执行，含重复加载），actionlint 通过。
  已修复 extensionless JBR seal、JAR 嵌入 Mach-O、签名后 HPROF/TP校验，以及同版本/同 SHA 唯一发布事务，
  复查原4项问题没有剩余高/中风险发现。真实原生签名/公证与 GitHub 发布事务仍未执行。
- macOS 实际签名前置检查失败，缺少7个签名/Apple环境引用；本机0可用签名身份。仓库没有列出所需
  Secrets/Environment；新工作流仍未发布远端，本轮没有 commit、push、dispatch、tag或 Release 修改。
- 最终日志、XML、40张截图、Linux/Mac原生原始结果、API失败回执与阻塞条件归档在
  `.omx/verification/ai-platform-final-2026-10-31/`。本地成功不替代 hosted、真实签名/公证或安装包验收。

## 状态规则

- `未开始`：没有可运行实现或对应验证证据。
- `进行中`：已有实现分支或正在执行，但尚未满足条目全部验收条件。
- `部分完成`：部分子项有实现和证据，但仍存在明确缺口。
- `已完成`：实现、测试和适用的构建/运行验收均已完成，并在“证据 / 下一步”列写明证据。
- `阻塞`：经过至少一次恢复尝试，仍被外部依赖、权限或未决产品决策阻塞；必须写明阻塞原因。

## 更新规则

1. 每完成一个条目，更新本文件和主 TODO 的 checkbox。
2. 完成日期使用 `YYYY-MM-DD`；不要使用“最近”“今天”等相对日期。
3. 代码存在但没有对应测试或运行证据时，只能标记为 `进行中` 或 `部分完成`。
4. provider、脚本、凭证和跨平台验收必须分别记录，不能用单一桌面单元测试替代。
5. 统计发生变化时更新顶部的最近统计日期、阶段统计和总计。
