# Electron 功能对齐矩阵

更新时间：2026-09-11
分支：`feature/electron-rewrite`

本表回答一个问题：**换栈之后，Kotlin / Compose 桌面应用具备的能力，Electron 侧还有哪些没有对齐。**

状态说明：

- ✅ 已迁移：Electron 侧有实现，且证据列指向可核验的对照（golden 用例、测试文件或人工对照日期）。
- 📝 部分迁移：主体可用，但矩阵缺口列列出的部分尚未对齐。
- ⏳ 未开始：Electron 侧没有实现。

维护规则：

1. 新增能力时补一行，而不是改写状态词。
2. 「证据」列必须可核验：golden 用例名、测试文件名，或「人工对照 + 日期」。写「已完成」不算证据。
3. 能自动化的断言直接固化进测试，矩阵引用测试名，不靠人记得更新。
4. CI 证据写 workflow 名 + run id 与结论，不写「已验证」。

## CI 首跑（2026-09-11）暴露的问题

在被推上 CI 之前，Electron 应用**从未真正渲染过**：`--dir` 冒烟只检查产物存在，不启动它。首次 CI 运行（`Electron` + `Electron packages` + golden 三条流水线）逐个暴露出以下缺陷，全部已修并复跑通过：

| 缺陷 | 根因 | 影响 |
| --- | --- | --- |
| `ERR_MODULE_NOT_FOUND: packages/<name>/src/index.ts` | 只有 `@aps/contracts` 被排除出 external，其余 18 个纯 TS 包在运行时被 `require` | 应用无法启动 |
| `Cannot read properties of undefined (reading 'en')` | 词条 `shell.language.simplifiedChinese` 与偏好值 `simplified_chinese` 拼写不一致，`translate` 抛错导致整页卸载 | 白屏 |
| HPROF 聚合 1.72×/1.79× | 深度分析按「每字段一个对象」存储，20 万实例多分配近百万小对象 | 性能闸门失败 |
| HPROF cyclic/parse 1.82× | 两个合成形状解析工作量相同，逐形状比较等于比噪声 | 闸门在噪声上失败 |
| ART `parseAndProject` 1.56× | 3 次测量取中位数=1 个样本，单轮 73% 尖峰有 1/3 概率成为上报值 | 闸门在噪声上失败 |
| 帧率闸门测不到滚动 | 默认展开两层=4 行，滚动范围为 0 | 门禁形同虚设（绿但无意义） |
| harness 测完后被判失败 | DevTools socket 挂住事件循环，120s 看门狗以退出码 2 结束 | 绿跑被判红 |
| 打包 `Unknown target: deb,rpm` 等四项 | CLI 目标语法、deb/rpm 元数据、Windows 二进制后缀、macOS 资源路径 | 五组合全部无法产出 |

教训写进维护规则：**门禁必须在真实产物上启动应用**，只检查文件存在会给出没有证据价值的绿。

## 外壳与平台基座

| 能力 | Kotlin 位置 | Electron 落点 | 状态 | 证据 | 缺口 |
| --- | --- | --- | --- | --- | --- |
| 导航：14 目的地、9 张首页卡、已访问页面保活 | `AppDestination` / `AppNavigator` | `apps/desktop/src/shared/destinations.ts` | ✅ | `destinations.test.ts` 断言 14/9/保活 | – |
| 设置迁移（`java.util.prefs` 三平台底层） | `desktop-app` 设置层 | `packages/settings` | ✅ | `legacy-prefs-source.test.ts`、`legacy-macos-plist.ts` / `legacy-windows-registry.ts` / `legacy-linux-xml.ts` | 未用 Kotlin 真实写出的 plist / 注册表 / XML 做端到端对照 |
| 主题（明暗、跟随系统） | Compose 主题 | `shared/theme.ts` + `App.tsx` | ✅ | `theme.test.ts` | – |
| 国际化 en/zh | 47 个 `strings.xml`、2,936 条 | `shared/i18n.ts` + 各面板自带词条 | 📝 | `i18n.test.ts` | 不是从 `strings.xml` 抽取，而是按需重写；未做全量文案覆盖核对 |
| ADB 发现优先级、参数向量不过 shell、输入校验 | `platform-core/adb-core` | `packages/platform-adb` | ✅ | `adb-locator.test.ts`、`adb-input-validator.test.ts` | 未在真实设备上执行 |
| Perfetto 固定 v57.2 + SHA-256，不允许 PATH 回退 | `platform-perfetto` | `packages/platform-perfetto` | ✅ | `tool-resolver.test.ts`、`trace-processor-manifest.json` | – |
| 应用数据目录 `~/.android-performance-studio/` | 各 feature 直接构造路径 | `source-backend.ts`、`ai-service.ts` 用该目录 | 📝 | `source-workspaces.db`、`analysis-sessions.db` 已共用 | 其余 feature 的会话存在 Electron `userData`，与 Kotlin 分叉 |
| 凭据存储 | macOS Keychain，其余平台仅内存 | `packages/ai-core/src/safe-storage-credentials.ts` | ✅ | `safe-storage-credentials.test.ts`、`isPersistentBackend` 保留「无系统密钥即不落盘」 | – |
| 进程模型与安全 | – | `main/index.ts` 窗口配置 | ✅ | `contextIsolation: true`、`sandbox: true`、`nodeIntegration: false` | `utilityProcess`/`worker_threads` 下沉未做；IPC 参数未加 schema 校验 |
| **应用能从构建产物启动并完成交互** | – | `apps/desktop` + `e2e/ui-perf.mjs` | ✅ | CI `Electron` run `34626821255`：帧率闸门在 xvfb 下启动应用、打开目的地、展开层级并完成滚动与命中测量 | 首次跑通；此前从未真正渲染过（见下） |

## Profiler 与分析器

| 能力 | Kotlin 位置 | Electron 落点 | 状态 | 证据 | 缺口 |
| --- | --- | --- | --- | --- | --- |
| Layout Inspector：协议 v1、可见窗口树采集、三栏工作区、隐藏层级、命中测试、虚拟化树 | `layout-inspector` (23,652 行) | `packages/layout-inspector` + `LayoutInspectorPanel` | 📝 | 采集走与 Kotlin 同一条 `cmd window dump-visible-window-views` 路径，解码器按 `VisibleWindowHierarchyParserTest` 的同一份编码夹具与断言逐条对照（`visible-window-views.test.ts`）；`layout-capture-service.test.ts`（原生优先、uiautomator 兜底、截图像素定标、截图失败不致命）、`tree.test.ts`（`depth-index` 编号 + `id/name` + 短类名）、`details.test.ts`（六段属性、缺失字段为 `—`、Float 渲染为 `8.0`）、`foreground-activity.test.ts` | 画布已按 `CanvasGeometry`/`PreviewZoomState`/`PreviewPanState`/`ViewBoundsOverlay` 移植：缩放 0.5–2.5、平移钳制、仅应用裁剪（圆角 24）、全量可见 bounds 叠加与 hover/选中三色边框、同点轮选；`canvas.test.ts` 逐条移植参考实现的 CanvasGeometryTest / PreviewPanStateTest / PreviewZoomStateTest / ViewBoundsOverlayTest 断言。缺：FINDINGS 分析引擎（4 条规则与问题列表）、TIMELINE、层级搜索/隔离、Compose 检查、capture archive |
| Trace Analyzer（内置 Perfetto UI） | `perfetto-viewer` | `TraceAnalyzerPanel` + `aps-perfetto://` | ✅ | `trace-capture-service.test.ts`、`trace-store.test.ts` | 依赖打包资源，见「打包」行 |
| CPU Profiler（simpleperf） | `simpleperf-viewer` (46,238 行) | `packages/simpleperf-profiler` + `profile-analysis` | ✅ | golden corpus 3 例 + `perf.test.ts` 比值闸门 | 未接真实设备；百万 sample 口径未单独验证 |
| Method Recording（ART trace） | `parser-art-trace` | `packages/art-trace` | ✅ | golden corpus 3 例 + `perf.test.ts` | `parse` 单段约为 JVM 的 2.4×，只报告不断言 |
| Memory Profiler：HPROF、直方图、支配树、泄漏 | `memory-profiler` (15,590 行) | `packages/memory-profiler` | ✅ | golden 磁盘夹具 + corpus 4 例 + JVM 比值闸门 | – |
| Memory 深度分析：Activity/Fragment/static/Handler 启发式、Bitmap 实例、Activity 泄漏报表、Heap diff | `MemoryDeepAnalysis.kt`、`HeapDiffAnalyzer.kt` | `packages/memory-profiler/src/deep-analysis.ts` | 📝 | 模块已移植，含字段名引用链 | 无 Kotlin golden 对照；实例浏览只对本次运行的会话有效 |
| Bitmap dump（API 35 `am dumpheap -b png`） | `BitmapDumpParser.kt` 等 831 行 | `bitmap-dump.ts` + `bitmap-model.ts` | 📝 | 流式分块提取，PNG 不进内存 | 无 Kotlin golden 对照；未在 API 35 设备上跑过 |
| Native heap（heapprofd） | `NativeHeapTraceParser.kt` 等 830 行 | `native-heap-trace.ts` + `native-heap-adapter.ts` | 📝 | processor 优先、wire 兜底，结果记录来源与原因 | 无 Kotlin golden 对照；未接真实设备 |
| Java heap trace（Perfetto `java_hprof`） | `JavaHeapTraceParser.kt` 等 887 行 | `java-heap-trace.ts` + `heap-graph-bridge.ts` + `java-heap-adapter.ts` | 📝 | 分块组装、id delta、描述符类名 | 无 Kotlin golden 对照 |
| Frame Profiler | `frame-profiler` | `packages/frame-profiler` + 面板 | ✅ | `gfxinfo.test.ts`、`analysis.test.ts`、`session.test.ts` | 未接真实设备 |
| Startup Profiler | `startup-profiler` | `packages/startup-profiler` + 面板 | ✅ | `parsers.test.ts`、`experiment.test.ts`、`session.test.ts` | 未接真实设备 |
| Battery Profiler | `battery-profiler` | `packages/battery-profiler` + 面板 | ✅ | `parser.test.ts`、`analysis.test.ts`、`conditions.test.ts` | 未接真实设备 |
| Network Profiler（默认拒绝的脱敏） | `network-profiler` | `packages/network-profiler` + 面板 | ✅ | `redactor.test.ts`、`har.test.ts` | 未接真实设备 |
| Benchmark Regression | `benchmark-regression` | `packages/benchmark-regression` + 面板 | ✅ | `parser.test.ts`、`analyzer.test.ts` | – |
| GPU Inspector（外部 AGI） | `gpu-inspector-integration` | `packages/gpu-inspector` + 面板 | ✅ | `toolchain.test.ts`、`artifact-index.test.ts` | 未在装有 AGI 的机器上跑过 |
| Source Workspace：Local / GitHub / AOSP、索引、解析、内容寻址缓存 | `source-workspace` | `packages/source-workspace` + `main/source-backend.ts` | ✅ | `source-workspace.test.ts`；handlers 全部走共享库 | 未对真实 Android 源码树的规模验证 |
| AI 分析：OpenAI 传输、会话仓库、证据绑定、源码感知 | `ai-core` + `SourceAwareLayoutAiAnalysisClient.kt` | `packages/ai-core` + `main/ai-service.ts` | 📝 | `gateway.test.ts`、`openai-client.test.ts`、`session-repository.test.ts` | 无 IPC schema 校验；未用 Kotlin 真实写出的 `analysis-sessions.db` 对照 |

## 打包、发布与兼容

| 能力 | Kotlin 位置 | Electron 落点 | 状态 | 证据 | 缺口 |
| --- | --- | --- | --- | --- | --- |
| 六格式安装包（DMG/PKG/MSI/EXE/DEB/RPM × 5 组合 = 10 资产） | `release.yml` + jpackage | `electron-builder.yml` + `.github/workflows/electron-package.yml` | 📝 | CI `Electron packages` run `34626821255`：Linux x64 DEB+RPM、Linux arm64 DEB+RPM、Windows x64 MSI+EXE、macOS arm64 DMG+PKG **实际产出并通过包内资产校验**（4/5 组合）| macOS x64 组合未通过，原因是 runner 上 `dmgbuild` 的 `hdiutil: couldn't eject "disk2" - Resource busy`，属基础设施抖动；未签名未公证（D5 有意为之） |
| 运行时资产随包（Perfetto UI、trace_processor、图标） | `desktop-app/build.gradle.kts` 资源段 | `extraResources` + `build/icon.*` | ✅ | 每个打包 job 都对产物执行 `scripts/verify-package.mjs`：断言 `perfetto-ui/index.html`、`perfetto-tools/trace_processor_shell`（Windows 为 `.exe`）存在，且二进制 SHA-256 与 `trace-processor-manifest.json` 一致；4/5 组合已通过 | – |
| 版本注入与产物命名 | `-PappVersion` | `scripts/set-version.mjs` + `artifactName` | ✅ | 命名契约与 Kotlin 一致 | – |
| capture archive 导入/导出 | `CaptureArchiveCodec` | – | ⏳ | – | 未迁移 |
| 多窗口协议 | `capture` 多 window | `layout-inspector` 模型带 `windows` + 面板窗口选择器 | ✅ | `codec` 覆盖 windows；`layout-capture-service.test.ts` 断言多窗口解析、`defaultWindowId` 取节点最多的窗口；面板在两个以上窗口时显示选择器并切换树/画布/属性 | – |
| 文档随包分发 | `docs-user` / `docs-user-zh` | – | ⏳ | – | 未打包，应用内也没有打开入口 |

## 已知的有意偏离

| 偏离 | 原因 | 记录位置 |
| --- | --- | --- |
| 不托管 Firefox Profiler UI | Electron 的 CPU Profiler 用自研 SVG 火焰图；`gecko.ts` 保留导入/导出能力 | `packages/simpleperf-profiler/src/gecko.ts` |
| HPROF 原始 dump 不在会话中保留 | 可复现且可达 GB 级；代价是实例浏览只对本次运行的会话有效 | `main/memory-heap-cache.ts` |
| ART trace 投影保留线程表里没有的线程事件 | 避免静默丢数据 | `packages/art-trace` 注释 |
| ProGuard 映射不重写 heap dump 的字符串表 | 惰性解析类名，重写字符串表会连带改动只是"看起来像类名"的常量 | `packages/memory-profiler/src/proguard.ts` |

## 与 issue #21 完成定义（DoD）的对照

| DoD 条目 | 状态 | 说明 |
| --- | --- | --- |
| D1–D5 验收项全部满足 | 📝 | D2 解析器闸门已达标；D4 的 `utilityProcess` 与 IPC schema 未做 |
| 9 项 Profiler + Layout Inspector + Trace Analyzer + AI/Source 能力对齐并通过 golden / 人工对照 | 📝 | 全部有实现；golden 只覆盖 HPROF / SIMPLEPERF / ART trace 三条解析链路 |
| 性能门槛全部达标 | 📝 | 解析器闸门（HPROF / simpleperf / ART）与 10k 层级均已达标；UI 闸门现在同时测量滚动、命中与画布缩放（`e2e/ui-perf.mjs` 的 `zoomCovered` 不再恒为 false），本机 10k 节点三项均为 120fps / p95 ≈ 9ms；CI 帧率样本仍只有一两轮 |
| 六格式本地/CI 冒烟通过 | 📝 | 四组合已真实产出并校验（run `34626821255`）；macOS x64 待重跑 |
| 既有设置 / SQLite / 归档可读 | 📝 | 设置与 source/ai 两个库共用；其余 feature 的会话路径分叉；归档未迁移 |
| 文档更新，旧 Compose 路径归档 | ⏳ | 见 Phase 4 |
