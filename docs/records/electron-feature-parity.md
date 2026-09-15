# Electron 功能对齐矩阵

更新时间：2026-09-14
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
| 导航：14 目的地、13 张首页功能卡（每行 4 张、图标+标题+介绍）、无侧边栏、启动即最大化、已访问页面保活 | `AppDestination` / `AppNavigator` | `apps/desktop/src/shared/destinations.ts` + `App.tsx` | ✅ | `destinations.test.ts` 断言 14 个目的地、首页卡片集合 = 目的地 − HOME、顺序与保活，以及每张卡两种语言都有标题与介绍；`shell-layout.test.ts` 断言每行 4 张、样式表里没有侧边栏规则，以及贴边区块与细分隔线的布局契约（ADR-0039；首页卡片按 ADR-0041 例外保留） | 参考实现首页只画 9 张卡，GPU Inspector / 基准回归 / 方法录制在参考实现里没有任何入口；Electron 侧补齐为 13 张（有意偏离，见 ADR-0038）。卡片刻画的介绍逐字取自参考实现首页（`values{,-zh}/strings.xml`），仅 AI Analysis 的文案为本地新写。窗口启动即最大化（参考实现只在进入功能页时最大化，回首页不再缩回），见 ADR-0038 |
| 设置迁移（`java.util.prefs` 三平台底层） | `desktop-app` 设置层 | `packages/settings` | ✅ | `legacy-prefs-source.test.ts`、`legacy-macos-plist.ts` / `legacy-windows-registry.ts` / `legacy-linux-xml.ts`；`migrate.test.ts` 覆盖 `application.*`、`view.*`、`canvas.bounds.*`、`simpleperf.tooltipMode` / `simpleperf.engine` 与 `archive.snapshotSizeMultiplier`（1–10 钳制）到设置 JSON 的映射；`settings-service.test.ts` 覆盖迁移后持久化与重载 | 未用 Kotlin 真实写出的 plist / 注册表 / XML 做端到端对照 |
| 主题（明暗、跟随系统） | Compose 主题 | `shared/theme.ts` + `App.tsx` | ✅ | `theme.test.ts` | – |
| 统一设置页：通用 / 环境 / Layout Inspector / Simpleperf / AI 设置 / 关于 | `DesktopAppSettingsDialog`、`GeneralSettingsContent`、`LayoutInspectorSettingsContent`、`SimpleperfSettingsSectionContent`、`AiSettingsContent`、`AboutSettingsContent` | `apps/desktop/src/renderer/src/settings/*`、`packages/settings` | 📝 | `model.test.ts`（深层合并、取值校验、模板预设）、`store.test.ts`（分区往返、越界回退）、`migrate.test.ts`（view./canvas./simpleperf. 旧键迁移）、`EnvironmentSettings.test.ts`（设备、Trace Processor、设置迁移来源三行都在）、`e2e/ui-perf.mjs` 设置走查：6 个顶层页 + Simpleperf 6 个分节全部渲染后关闭 | 采集归档现在由原生 File 菜单导入/导出 Kotlin 兼容的 `.apinspect` v1/v2；“Open Recent”与 Kotlin 共用 `recent-layout-inspector-archives.txt`，按绝对规范路径持久化、去重、最多 10 条，并由主进程直接重新导入；使用指南已随包分发并通过 loopback 服务按语言打开；采样配置现在可持久化并切换 Kotlin 同样的 frequency (`-f`) / period (`-c`)、call graph 与 event scope。Simpleperf 引擎现在可选择 Electron 本地火焰图、内置 Firefox Profiler 或官方 Firefox Profiler；环境页为 Electron 侧新增，参考实现没有对应页（设备与 Trace Processor 两张状态卡原先挂在每个目的地页底部） |
| 国际化 en/zh | 47 个 `strings.xml`、2,936 条 | `shared/i18n.ts` + 各面板自带词条 | 📝 | `i18n.test.ts` | 不是从 `strings.xml` 抽取，而是按需重写；未做全量文案覆盖核对 |
| ADB 发现优先级、参数向量不过 shell、输入校验 | `platform-core/adb-core` | `packages/platform-adb` | ✅ | `adb-locator.test.ts`、`adb-input-validator.test.ts` | 未在真实设备上执行 |
| Perfetto 固定 v57.2 + SHA-256，不允许 PATH 回退 | `platform-perfetto` | `packages/platform-perfetto` | ✅ | `tool-resolver.test.ts`、`trace-processor-manifest.json` | – |
| 应用数据目录 `~/.android-performance-studio/` | 各 feature 直接构造路径 | `app-data-directory.ts` + Electron `userData` 预启动重定向 | ✅ | `app-data-directory.test.ts` 覆盖 Kotlin 根目录和仅补缺、不覆盖的旧 Electron session 迁移；所有 session stores 统一读 `app.getPath('userData')` | 尚未在已有真实 Kotlin/Electron 双端用户目录上做迁移演练 |
| 凭据存储 | macOS Keychain，其余平台仅内存 | `packages/ai-core/src/safe-storage-credentials.ts` | ✅ | `safe-storage-credentials.test.ts`、`isPersistentBackend` 保留「无系统密钥即不落盘」 | – |
| 进程模型与安全 | – | `main/index.ts` 窗口配置 + `shared/ipc-validation.ts` | 📝 | `contextIsolation: true`、`sandbox: true`、`nodeIntegration: false`；`ipc-validation.test.ts` 覆盖五个破坏性 remove、`trace:importFromPath`、`memory:diff`、全部 capture、method/CPU snapshot、memory instances/detail、profiler session load、GPU/layout/trace open/reveal/relocate、`source:reindex/search/resolve/read/setAiUpload` 和 `ai:saveConfiguration/saveCredential/analyze/findings` 的结构、opaque ID、索引相对路径、证据枚举、ADB serial、Android package、枚举和边界值；相关 capture-service 测试钉住畸形目标绝不执行 ADB | `utilityProcess`/`worker_threads` 下沉未做；其余非存储型设置/菜单请求与 layout capture options 尚未统一运行时 schema 校验 |
| **应用能从构建产物启动并完成交互** | – | `apps/desktop` + `e2e/ui-perf.mjs` | ✅ | CI `Electron` run `34626821255`：帧率闸门在 xvfb 下启动应用、打开目的地、展开层级并完成滚动与命中测量 | 首次跑通；此前从未真正渲染过（见下） |

## Profiler 与分析器

| 能力 | Kotlin 位置 | Electron 落点 | 状态 | 证据 | 缺口 |
| --- | --- | --- | --- | --- | --- |
| Layout Inspector：协议 v1、可见窗口树采集、三栏工作区、隐藏层级、命中测试、虚拟化树 | `layout-inspector` (23,652 行) | `packages/layout-inspector` + `LayoutInspectorPanel` | 📝 | 采集走与 Kotlin 同一条 `cmd window dump-visible-window-views` 路径，解码器按 `VisibleWindowHierarchyParserTest` 的同一份编码夹具与断言逐条对照（`visible-window-views.test.ts`）；`layout-capture-service.test.ts`（原生优先、uiautomator 兜底、截图像素定标、截图失败不致命）、`tree.test.ts`（`depth-index` 编号 + `id/name` + 短类名）、层级树横向滚动（标签不再省略号截断，列表按最长行撑宽，对应参考 LazyColumn 的 `widthIn(min = viewportWidth)`；显隐按钮按参考放在标签之前）、`details.test.ts`（六段属性、缺失字段为 `—`、Float 渲染为 `8.0`、`DetailRowStripe` 隔行底色按节重新开始：偶数行深色 #1C1C1E / 奇数行 #2C2C2E，节尾一条分隔线）、`foreground-activity.test.ts`、`pane-layout.test.ts`（`PaneLayoutTest.kt` 逐条移植：默认 375/300、拖拽只动相邻侧栏、画布最小宽 320、窗口收窄后的 fit） | 顶栏整行按参考 `HeaderToolbar` 复刻（等宽包名 · 设备「自动设备」兜底 · 「目标: 前台应用/系统界面」· 采集结果 · [窗口] | 状态读数 … | 刷新(56dp，自动扫描时隐藏) · 自动扫描开关(30×16) | metrics | 三个面板显隐按钮 26×21）；菜单栏按参考 `NativeViewerMenuBar` 复刻（操作 9 项、视图 5 项、⌘R/⌘1/⌘2/⌘3/⌘,，勾选与禁用随页面状态同步，`viewer-menu.test.ts` 钉住顺序/分组/快捷键/双语标签；文件菜单已包含归档导入/导出及持久化的“Open Recent”（同名条目显示完整路径、空列表/清除菜单、归档操作期间禁用），见 `menu.test.ts`、`recent-path-store.test.ts`）；顶部按参考 `HeaderToolbar` 复刻（40dp 行高、等宽包名、设备/采集/窗口内联选择器、状态读数与右侧动作按钮，不再是标题卡片加表单，`shell-layout.test.ts` 钉住行高与分隔线）；四栏齐备：HIERARCHY / CANVAS / PROPERTIES / FINDINGS。画布按 `CanvasGeometry`/`PreviewZoomState`/`PreviewPanState`/`ViewBoundsOverlay`/`PreviewScrollbarAdapter` 移植（缩放 0.5–2.5、平移钳制、仅应用裁剪圆角 12——参考为 24dp，按要求减半、全量可见 bounds 叠加、hover/选中三色边框、同点轮选、预览滚动条：只在溢出的轴上出现，点轨道跳转 + 拖拽平移，位置读 `scrollbarGeometry`/`panForScrollbar`）；分析引擎按 `LayoutAnalyzer` 移植（4 条规则、BFS 顺序、深度规则最后追加、metrics），底部 FINDINGS 面板复刻徽标计数与 `[节点号] 标题 · 说明` 行、双击跳转节点、可拖拽高度（默认 89 / 最小 56 / 上限一半）；三栏宽度按 `PaneLayout` 可拖拽（分隔条 7px、画布最小 320、默认 375/300），工作区撑满窗口高度、左侧树虚拟滚动跟随实际高度，FINDINGS 贴在窗口底边。`canvas.test.ts`、`analysis.test.ts`、`findings.test.ts` 逐条移植参考实现的同名测试断言。HIERARCHY 面板按参考 `HierarchyPane` 补齐：搜索栏（`hierarchy-search.ts`，label/`id/名称`/行号三项匹配、`1/3` 计数、◀▶ 环绕、命中片段高亮）、隔离子树（`hierarchy-isolation.ts`，隔离子树/上级/清除，树与画布切到子树并按根重排深度，快照变化后 `sanitize`）、键盘 ↑/↓/Enter/H（`tree.ts` 的 `adjacentNodeId`/`toggledCollapsed`/`revealedCollapsed`，选中即 `reveal` 展开祖先）、行内 8dp 强调色折叠箭头与 28×16 层显隐按钮、搜索配色对齐参考 `ViewerColors`（`--search-match`/`--search-current-match`/`--search-highlight`）；标题行只留计数与隔离按钮，三个显示选项回到「视图」菜单。`hierarchy-search.test.ts`、`hierarchy-isolation.test.ts`（移植 `HierarchyIsolationStateTest`）、`tree.test.ts` 钉住这些断言。时间线现按 `InspectorStore` 的 50 帧上限记录手动与自动扫描帧，并展示/测试 added、removed、bounds 与属性变更；原生 File 菜单现可导入/导出带完整性校验的 Kotlin 兼容 `.apinspect` v1/v2（`capture-archive-codec.test.ts`、`capture-archive-service.test.ts`）：raw `visible-window-views.zip` 与诊断 text 按 Kotlin 的成对契约持久化、导入、导出；archive capture 还会保留设备原始 ZIP 并生成受 8 MiB 限制的诊断文本（`layout-capture-store.test.ts`、`layout-capture-service.test.ts`、`visible-window-views.test.ts`）。面板显隐开关已实现。缺：Compose 检查尚未端到端可用（完整运行时组合树、参数、modifier、重组指标）。Electron 已补齐 AOSP `UIINSPCT` 帧协议、外层 Protobuf 信封、命令 ID 关联、断链/超时/Agent crash 处理、loopback TCP 客户端，以及与 Kotlin 相同的 Agent bundle ABI/manifest/大小/SHA-256/fingerprint 校验（`ui-inspector-protocol.test.ts`、`ui-inspector-client.test.ts`、`compose-agent-bundle.test.ts`）；当前仍没有可供校验的 Android JVMTI/Service/Payload/View/Compose Inspector 产物，不能标为已在真机运行；但 Electron 已完成 View/Compose 内层 Protobuf 适配、ABI/manifest/hash 校验、PID 注入/socket 等待、token-first 握手、版本精确 inspector AAR/JAR 解析与缓存、View-A/Compose-A/View-B/Compose-B/View-C 稳定帧、树 graft、重组/跳过计数属性展示、归档/IPC 持久化和 guarded `layout:capture` 接线。缺口仅剩实际 bundle、目标报告版本对应的精确 AAR（本机仅已发现 `ui-android:1.10.4` 候选）、真实设备运行和打包资产验证。 |
| Trace Analyzer（内置 Perfetto UI） | `perfetto-viewer` | `TraceAnalyzerPanel` + `aps-perfetto://` | ✅ | `trace-capture-service.test.ts`、`trace-store.test.ts` | 依赖打包资源，见「打包」行 |
| CPU Profiler（simpleperf） | `simpleperf-viewer` (46,238 行) | `packages/simpleperf-profiler` + `profile-analysis` + `firefox-profiler-service.ts` | 📝 | golden corpus 3 例 + `perf.test.ts` 比值闸门；`gecko-export.test.ts` 覆盖 Gecko v24 表/类别/读回；`firefox-profiler-service.test.ts` 覆盖原始 gzip 保留、本地静态站点与官方 CORS/private-network 单次回环传输；`.apsession.zip` 双向 fixture、CPU session-package import/export、raw `perf.data` retention、CaptureArtifact v1 hash/shape 与 failure-staging cleanup 均有定向回归 | CPU 面板现可导入/导出 Kotlin session package：导入以 `perf.data`+可选 symbols/mapping 重新转换并无损保留源目录，原生采集保存 `perf.data`/`simpleperf.protobuf`/Capture Artifact 后才可导出；report-only legacy/protobuf/Gecko session 明确不可伪造为 Kotlin package。未接真实设备；尚未用真实 Electron 采集包在 Kotlin UI 完成重开；Firefox 静态资源须在正式 macOS/Windows/Linux 包中实际校验；全局 Settings 页没有选中设备上下文，因此仅提供共享 fallback 建议与自由输入；百万 sample 口径未单独验证 |
| Method Recording（ART trace） | `parser-art-trace` + `MethodTraceCaptureSession` / `MethodRecordingController` | `packages/art-trace` + `main/method-capture-service.ts` + `method-recording-lifecycle.ts` + `method-session-store.ts` + 面板 | 📝 | golden corpus 3 例 + `perf.test.ts`；`method-capture-service.test.ts` 覆盖实时 capture、`.trace` 导入/扩展名/空文件/损坏/零事件拒绝、`dumpsys package packages` + `ps -A -o PID,NAME` 的可调试/profileable 进程发现、远程进程 package 映射、开始前 PID→package 实时重验以及协作式 early-stop；`method-recording-lifecycle.test.ts` 覆盖单活跃 main-process capture、Stop 信号与过期 completion 不释放新 capture；`MethodRecordingPanel.test.tsx` 覆盖设备关联进程选择器、Stop 控件及离线导入；`method-import-service.test.ts` 覆盖原生文件边界的常规文件/大小/读取与 classic ART import | 原生文件选择器可离线导入 ART `.trace`，会话明确标为 imported 并仅显示来源文件名，不伪造设备/PID/API；实时录制只从可调试或 `profileableByShell` 进程选择，开始前重新验证 PID 仍属于该包；Stop 只缩短等待，随后仍由 capture owner 完成 `am profile stop`、flush/pull/解析/清理。尚无真实设备证据，且 Kotlin SQLite profiler-session 互操作仍未完成 |
| Memory Profiler：HPROF、直方图、支配树、泄漏 | `memory-profiler` (15,590 行) | `packages/memory-profiler` | ✅ | golden 磁盘夹具 + corpus 4 例 + JVM 比值闸门 | – |
| Memory 深度分析：Activity/Fragment/static/Handler 启发式、Bitmap 实例、Activity 泄漏报表、Heap diff、历史实例浏览 | `MemoryDeepAnalysis.kt`、`HeapDiffAnalyzer.kt`、`MemoryCaptureSession` | `packages/memory-profiler/src/deep-analysis.ts` + `main/memory-diff-service.ts` + `memory-session-store.ts` + `memory-persisted-heap.ts` + Memory 面板 | 📝 | 模块已移植，含字段名引用链；新会话持久化完整 comparison histogram，`memory-diff-service.test.ts` 覆盖新增/删除/变更类、旧 top-N 会话的 partial 标记和无效 ID；新 HPROF 在 worker transfer 前先 staging 到 main-process-owned 会话目录，`memory-capture-service.test.ts` 覆盖持久化与解析失败清理，`memory-session-store.test.ts` 覆盖 stage/commit/read，`memory-persisted-heap.test.ts` 覆盖 cache miss 后按持久化 provenance 重解析；`deep-analysis.test.ts` 的 Kotlin 派生夹具覆盖 `Reference`/子类的 `referent` 弱边不会进入支配树或引用链，非 `referent` 边保持强可达 | 新抓取会话保留 raw HPROF，可跨重启浏览实例；旧 Electron 会话和损坏/超限 raw 证据仍只返回空实例结果。尚无完整 Kotlin golden corpus、真机证据或 Kotlin/Electron Memory SQLite 会话互操作 |
| Bitmap dump（API 35 `am dumpheap -b png`） | `BitmapDumpParser.kt`、`BitmapDumpAnalyzer.kt`、`BitmapDumpGallery` | `bitmap-dump.ts` + `bitmap-model.ts` + `BitmapDumpStore` + Memory 面板 | 📝 | `parseBitmapDump` 由公开的 Node 子入口 `@aps/memory-profiler/node` 导出，`index.test.ts` 覆盖其与 browser 主入口的边界；流式分块提取，PNG 不进内存。会话存储会在临时抓取目录删除前复制 PNG 至 `<userData>/bitmap-dumps/<id>/images/`，持久化 JSON 仅保留相对逻辑名，`bitmap:image` 以 opaque session ID + record index 逐张受限返回 data URL；`memory-artifact-stores.test.ts` 覆盖保留、旧 JSON 降级、删除清理和大小上限，`ipc-validation.test.ts` 覆盖 image 请求。图库按 24 条分页、预览按需加载；`bitmap-model.test.ts` 覆盖 SHA-256 的新增/删除/重复数量变化及稳定排序，面板支持默认前序会话或手动基线对比 | 尚无 API 35 Kotlin fixture 或真机证据；未保留 HPROF；PNG 单张 IPC 载荷超过 32 MiB 时显示不可用，而不会传入 renderer |
| Native heap（heapprofd） | `NativeHeapTraceParser.kt` 等 830 行 | `native-heap-trace.ts` + `native-heap-adapter.ts` | 📝 | `native-heap-trace.test.ts` 的 Kotlin 派生合成夹具覆盖同叶函数聚合、可信包序列隔离与严格/宽松的损坏包处理；processor 优先、wire 兜底，结果记录来源与原因 | 尚无完整 Kotlin golden corpus 或真机证据 |
| Java heap trace（Perfetto `java_hprof`） | `JavaHeapTraceParser.kt` 等 887 行 | `java-heap-trace.ts` + `heap-graph-bridge.ts` + `java-heap-adapter.ts` | 📝 | `java-heap-trace.test.ts` 的 Kotlin 派生合成夹具覆盖分包续传、ID delta、引用基址、runtime-internal / null 引用、元数据与损坏包；分块组装、描述符类名 | 尚无完整导出 golden corpus 或真机证据 |
| Frame Profiler | `frame-profiler` | `packages/frame-profiler` + 面板 | ✅ | `gfxinfo.test.ts`、`analysis.test.ts`、`session.test.ts` | 未接真实设备 |
| Startup Profiler | `startup-profiler` + `StartupJsonExporter` / `StartupJsonImporter` v1 + `SqliteStartupSessionStore` | `packages/startup-profiler` + 面板 + main-process JSON/SQLite interchange | 📝 | `parsers.test.ts`、`experiment.test.ts`、`session.test.ts`；`kotlin-json.test.ts` 导入真实 Kotlin writer fixture，并以原始文档回放锁定 rich evidence 的无损导出、禁止 partial context 提升；`startup-session-store.test.ts` 锁定 legacy 无 context 报告与 pseudonymous `deviceLocalId` 的持久化边界；`startup-sqlite-import.test.ts` 用 Kotlin DDL 形状的 SQLite fixture 锁定空 warnings、只读 source SHA-256/identity、不变性、active WAL 拒绝、64 MiB 边界、required table/column 拒绝、pseudonym 不作 ADB serial、nullable metrics、warmup/measure 划分和 evidence `INFERRED` 降级；`StartupProfilerPanel.test.tsx` 锁定 imported provenance、confidence/source/reason 与 limitation warning 可见；Kotlin `StartupExportersTest` 用真实 importer 读回 Electron fixture；`StartupAnalyzerTest` 锁定所有 TTFD 缺失时 `count=0` / `missingCount=n` | Kotlin Startup JSON v1 已双向 fixture 互操作；Electron 现可只读导入关闭 Kotlin 后、无 WAL/journal sidecar 的 `startup.db` `.db` / `.sqlite`，但不共享或写回数据库。milestone/phase/compilation/environment/trace rich evidence 仅保留在 source DB 并作为 UI-visible warning；尚无真实 Kotlin writer SQLite fixture、历史用户目录、并发访问、真机或 Kotlin UI 回开证据 |
| Battery Profiler | `battery-profiler` | `packages/battery-profiler` + 面板 | ✅ | `parser.test.ts`、`analysis.test.ts`、`conditions.test.ts` | 未接真实设备 |
| Network Profiler（默认拒绝的脱敏） | `network-profiler` | `packages/network-profiler` + 面板 | ✅ | `redactor.test.ts`、`har.test.ts` | 未接真实设备 |
| Benchmark Regression | `benchmark-regression` | `packages/benchmark-regression` + 面板 | ✅ | `parser.test.ts`、`analyzer.test.ts` | – |
| GPU Inspector（外部 AGI） | `gpu-inspector-integration` | `packages/gpu-inspector` + 面板 | ✅ | `toolchain.test.ts`、`artifact-index.test.ts` | 未在装有 AGI 的机器上跑过 |
| Source Workspace：Local / GitHub / AOSP、索引、解析、内容寻址缓存 | `source-workspace` | `packages/source-workspace` + `main/source-backend.ts` | ✅ | `source-workspace.test.ts`；Kotlin 的 `SqliteSourceWorkspaceRepository` 实际生成 checked-in SQLite fixture，Electron 先检查原始列再由仓储读取；Electron 的同一真实仓储也写入 `electron-source-workspace.db`，Kotlin `SourceWorkspaceIntegrationTest` 随后读回 GitHub provider、snapshot、file/symbol index、range columns 与 null range；两种 fixture 都在最终读回后 checkpoint WAL/DELETE journal；handlers 全部走共享库 | 未对真实 Android 源码树的规模验证；尚未验证两进程并发访问或真实历史库迁移 |
| AI 分析：OpenAI 传输、会话仓库、证据绑定、源码感知 | `ai-core` + `SourceAwareLayoutAiAnalysisClient.kt` | `packages/ai-core` + `main/ai-service.ts` + AI/Source 面板 | 📝 | `gateway.test.ts`、`openai-client.test.ts`、`session-repository.test.ts`；Kotlin 的 `SqliteAnalysisSessionRepository` 实际生成 checked-in `analysis-sessions.db` fixture，Electron 会检查原始 session/finding/evidence/candidate 列并以仓储读取 provider、ISO 时间、JSON 引用、payload hash、nullable range/hash 与顺序；Electron 的同一真实仓储也写入 `electron-analysis-sessions.db`，Kotlin `AiAnalysisGatewayTest` 随后读回并断言 provider、父 session、JSON 引用、finding/候选顺序、nullable range/hash 和仅 SHA-256 evidence；finding 的 `sourceCandidateIds` 经 `ai:sourceCandidate` 只读取同一已持久化 AI session 的 candidate，并按该 session 的 snapshot + content hash 重建 Source Workspace 位置，不重解析新符号；`ai-source-navigation.test.ts` 覆盖 snapshot/hash 失配时 fail closed，`ipc-validation.test.ts` 覆盖 session/candidate opaque ID。AI 面板对多个 candidate 显示本地化选择弹窗，保持次序、去重且取消不改变状态 | 尚未验证两进程并发访问或真实用户历史库迁移 |

## 打包、发布与兼容

| 能力 | Kotlin 位置 | Electron 落点 | 状态 | 证据 | 缺口 |
| --- | --- | --- | --- | --- | --- |
| 六格式安装包（DMG/PKG/MSI/EXE/DEB/RPM × 5 组合 = 10 资产） | `release.yml` + jpackage | `electron-builder.yml` + `.github/workflows/electron-package.yml` | 📝 | CI `Electron packages` run `34626821255`：Linux x64 DEB+RPM、Linux arm64 DEB+RPM、Windows x64 MSI+EXE、macOS arm64 DMG+PKG **实际产出并通过包内资产校验**（4/5 组合）| macOS x64 组合未通过，原因是 runner 上 `dmgbuild` 的 `hdiutil: couldn't eject "disk2" - Resource busy`，属基础设施抖动；未签名未公证（D5 有意为之） |
| 运行时资产随包（Perfetto UI、trace_processor、图标） | `desktop-app/build.gradle.kts` 资源段 | `extraResources` + `build/icon.*` | ✅ | 每个打包 job 都对产物执行 `scripts/verify-package.mjs`：断言 `perfetto-ui/index.html`、`perfetto-tools/trace_processor_shell`（Windows 为 `.exe`）存在，且二进制 SHA-256 与 `trace-processor-manifest.json` 一致；4/5 组合已通过 | – |
| 版本注入与产物命名 | `-PappVersion`（默认 0.4.6） | `scripts/set-version.mjs` + `artifactName` | ✅ | 命名契约与 Kotlin 一致；版本写在 `apps/desktop/package.json`（当前 0.4.6，与 Kotlin 默认版本同一号），`app.getVersion()` 与产物名都取自它；CI 只校验格式、不再用占位版本覆盖 | – |
| capture archive 导入/导出与最近文件 | `CaptureArchiveCodec` + `RecentPathStore` | `capture-archive-codec.ts` + `CaptureArchiveService` + 原生 File 菜单 + `RecentPathStore` | ✅ | `capture-archive-codec.test.ts` 覆盖 Kotlin v1/v2、路径/重复项/完整性/上限/原子替换保护；双向 codec/service fixture 由 Kotlin 与 Electron 分别真实写出九成员 v2 archive，并由另一侧导入；`recent-path-store.test.ts` 与 `menu.test.ts` 覆盖同名 Kotlin 最近文件、原子持久化、去重/上限、同名消歧、清除与操作中禁用 | 未以真实历史用户 archive 或设备抓取做端到端对照 |
| 多窗口协议 | `capture` 多 window | `layout-inspector` 模型带 `windows` + 面板窗口选择器 | ✅ | `codec` 覆盖 windows；`layout-capture-service.test.ts` 断言多窗口解析、`defaultWindowId` 取节点最多的窗口；面板在两个以上窗口时显示选择器并切换树/画布/属性 | – |
| 文档随包分发 | `docs-user` / `docs-user-zh` | `electron-builder.yml` + `UserDocumentationService` + 设置页入口 | ✅ | `user-documentation-service.test.ts` 断言 loopback 语言路由与缺资源失败；`verify-package.mjs` 要求两个 `index.html` | 完整安装包产物验证仍依赖可用的 Trace Processor 资源 |

## 已知的有意偏离

| 偏离 | 原因 | 记录位置 |
| --- | --- | --- |
| Firefox Profiler 线程标识降级 | Electron 从 retained protobuf 恢复的 `NormalizedSample` 没有 Kotlin SQLite 的 canonical `thread_key`；导出按 `(pid, tid, name)` 分组。它足以在 Firefox Profiler 中显示正常会话，但不能精确重现线程 ID 重用时的 Kotlin 分组 | `packages/simpleperf-profiler/src/gecko-export.ts`、`main/firefox-profiler-service.ts` |
| HPROF 原始 dump 不在会话中保留 | 可复现且可达 GB 级；代价是实例浏览只对本次运行的会话有效 | `main/memory-heap-cache.ts` |
| ART trace 投影保留线程表里没有的线程事件 | 避免静默丢数据 | `packages/art-trace` 注释 |
| ProGuard 映射不重写 heap dump 的字符串表 | 惰性解析类名，重写字符串表会连带改动只是"看起来像类名"的常量 | `packages/memory-profiler/src/proguard.ts` |

## 与 issue #21 完成定义（DoD）的对照

| DoD 条目 | 状态 | 说明 |
| --- | --- | --- |
| D1–D5 验收项全部满足 | 📝 | D2 解析器闸门已达标；D4 的 HPROF、ART 和 simpleperf（含 gzip Gecko）重解析现由每请求一个 `worker_threads` worker 完成，并有真实 electron-vite worker 测试与生产 chunk 证据；五个破坏性 remove、`trace:importFromPath`、`memory:diff`、所有 capture、`source:reindex/search/resolve/read/setAiUpload` 和 `ai:saveConfiguration/saveCredential/analyze/findings` 已在服务、持久化 JSON 或外部网络访问前做运行时输入校验；其余 renderer payload（包括 settings 深层 patch、destination/language、viewer menu state 与 layout capture 的 auto-device serial/option）也已在写 JSON、重建原生菜单、改变窗口或调用 ADB 前按闭合 schema 校验 |
| 9 项 Profiler + Layout Inspector + Trace Analyzer + AI/Source 能力对齐并通过 golden / 人工对照 | 📝 | 全部有实现；golden 只覆盖 HPROF / SIMPLEPERF / ART trace 三条解析链路 |
| 性能门槛全部达标 | 📝 | 解析器闸门（HPROF / simpleperf / ART）与 10k 层级均已达标；UI 闸门现在同时测量滚动、命中与画布缩放（`e2e/ui-perf.mjs` 的 `zoomCovered` 不再恒为 false），本机 10k 节点三项均为 120fps / p95 ≈ 9ms；CI 帧率样本仍只有一两轮 |
| 六格式本地/CI 冒烟通过 | 📝 | 四组合已真实产出并校验（run `34626821255`）；macOS x64 待重跑 |
| 既有设置 / SQLite / 归档可读 | 📝 | 设置与 source/ai 两个库共用，且均已有 Kotlin → Electron 与 Electron → Kotlin 的真实仓储 fixture；Kotlin `archive.snapshotSizeMultiplier` 现会迁移、持久化并驱动相同的 `.apinspect` 限额；Kotlin 与 Electron 的真实 `CaptureArchiveService` 均写出 v2 archive，并由另一侧 codec/service 实际读取，覆盖 snapshot、PNG、Compose、三类 report/history 与 raw pair；Startup 另有 JSON v1 双向 fixture 交换，但其 SQLite 数据库及其他 feature 的会话路径仍分叉 |
| 文档更新，旧 Compose 路径归档 | 📝 | 用户文档现随包并可从设置页打开；旧 Compose 路径归档与整份迁移文档仍需完成 |

## Compose Agent runtime parity — source implementation status (2026-09-14)

Electron now has unit-tested host-side implementations for the Kotlin Compose inspector protocol boundary:

- verified ABI-specific agent bundle loading (`manifest.properties`, regular files, bounded sizes and SHA-256);
- debuggable-app deployment with API/ABI/PID checks, session-token injection, pre-existing-socket rejection, socket readiness polling, staged-file cleanup and idempotent private-file cleanup;
- token-first `UIINSPCT` transport, correlated `CreateInspector` commands and inspector-ID validation;
- Kotlin-equivalent bounded stable capture: `View-A → Compose-A → View-B → Compose-B → View-C`, three attempts, serial per-root tree reads and Compose generation advancement;
- archive/store/IPC persistence of `capture/compose-inspection.json` as `.apinspect` v2 payload data.

Evidence: `compose-agent-bundle.test.ts`, `compose-agent-deployment.test.ts`, `ui-inspector-protocol.test.ts`, `ui-inspector-client.test.ts`, `compose-inspection-service.test.ts`, `compose-inspector-protocol.test.ts`, `compose-tree-merge.test.ts`, `view-inspector-protocol.test.ts`, `layout-capture-store.test.ts`, and `capture-archive-service.test.ts`.

This is **not yet real-device runtime parity**. The checked-out workspace has no verified AOSP bundle. A local Gradle cache contains `androidx.compose.ui:ui-android:1.10.4` with an `inspector.jar`, but no target has yet reported a Compose version, so that artifact cannot be treated as target-matched evidence. Therefore the guarded IPC path correctly keeps the native View/Compose-host fallback active while the bundle is absent. Real parity still requires: build the pinned patched bundle, resolve and verify the exact inspector artifact for the target-reported Compose version, package those verified assets, and capture a debuggable Compose app on a physical device/emulator.

Follow-up source integration on 2026-09-14: `layout:capture` now invokes the verified Compose lifecycle only when a native capture detects a Compose host **and** the packaged/development `build/compose-agent` bundle directory exists. The lifecycle resolves exact `ui`/`ui-android` artifacts from a digest-verified APS cache, local project/Gradle/Maven caches, then HTTPS repositories; it stages the exact digest into the private app directory before `CreateInspector`. Any optional Compose-path failure preserves the normal View hierarchy and screenshot. Packaging now declares `compose-agent` as a required runtime resource and its verifier requires the manifest, three ABI libraries, service/payload, and View inspector JAR. A current package build remains intentionally unproven because no such trusted bundle exists in this checkout.

## Capture Archive optional payload round-trip — 2026-09-14

Electron now preserves the three Kotlin optional archive members end to end instead of treating them as codec-only data:

- `report/analysis-report.json`
- `report/ai-analysis-report.json`
- `timeline/history.json`

The main-process import boundary validates their Kotlin JSON contracts before persistence. The local capture store keeps the original JSON in separate managed files, `CaptureArchiveService` passes it back to `CaptureArchiveCodec` unchanged on re-export, and `LayoutCaptureDetail` exposes the payloads to the renderer. This preserves historical static findings, AI findings/provenance, and timeline summaries/diffs without silently resolving them against current data.

For native Electron captures that have no stored static report yet, export writes the same deterministic layout analysis used by the renderer. It does not synthesize AI or timeline payloads: those remain absent unless captured/imported, as in the Kotlin optional-entry contract. Renderer import uses the persisted static report in preference to recomputation, presents archived AI findings as archived evidence, and shows the Kotlin history strip as timestamp/diff summaries. The archive format intentionally carries no historical per-frame snapshots/screenshots, so only the archive's current capture remains inspectable as a full layout.

Evidence: `capture-archive-payloads.test.ts`, `layout-capture-store.test.ts`, `capture-archive-codec.test.ts`, and `capture-archive-service.test.ts` cover Kotlin-shaped payload decoding, malformed-payload rejection, managed-store round trips/cleanup, ZIP round trips, and source-store → archive → destination-store preservation. Kotlin `CaptureArchiveServiceTest` imports the checked-in Electron-created nine-member v2 fixture; Electron’s test imports the corresponding Kotlin-created fixture. This proves current codec/service fixture interoperability in both directions, not real-device capture, historical user archive migration, or concurrent access.

## AI source-candidate choice — 2026-09-14

When an AI finding cites one source candidate, Electron opens it directly. When it cites more than one, Electron now opens a localized candidate-choice dialog before navigation, matching the Kotlin `sourceCandidateChoices` interaction rather than rendering several competing open actions. The dialog preserves candidate order, deduplicates repeated IDs, and cancellation leaves the active finding/session untouched. Evidence: `source-candidate-choice.test.ts`.


## Parser worker isolation — 2026-09-14

The Electron main process no longer synchronously parses HPROF, ART trace, simpleperf protobuf, or gzip Gecko profile JSON on the capture/import/cache-miss paths. `parser-worker-runner.ts` creates a fresh electron-vite `?nodeWorker` for each request and transfers the source `Uint8Array`; its protocol keeps ordinary ART/simpleperf parse errors as typed `StudioResult` values, while malformed responses, worker errors, deserialization errors, and an exit before a response reject the request. The HPROF operation returns both `MemorySession` and `HprofParseResult`, preserving current-process instance browsing after raw HPROF cleanup.

Production composition injects the bridge into heap capture, method capture/import/session reload, CPU capture/import, and CPU table reload. Offline Gecko handling moves both gzip decoding and JSON parsing into the worker. Evidence: service seam tests, `parser-worker-runner.test.ts` (temporary CJS electron-vite bridge plus real `node:worker_threads` workers), and the September 14 production build, which emitted `out/main/parser-worker-C3FFFk_k.js` referenced by `out/main/index.js`.
