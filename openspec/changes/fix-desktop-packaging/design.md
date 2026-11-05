# Design: fix-desktop-packaging

## Context

Compose Desktop 的打包插件把 app 资源（`prepareProfilerAppResources` Sync 产物）
拷入 jpackage app-image 时使用 `File.copyTo`，丢失 POSIX 可执行位。现有补救是
`desktop-app/build.gradle.kts` 中挂在 `packageDeb`/`packageRpm`/
`createDistributable` 上的 `doLast`：解包 deb → chmod `trace_processor_shell` →
重新打包。该逻辑写成单一 `binaryName = "trace_processor_shell"`，因此：

- `adb-tools/adb`（应 0755）实测以 0644 出货（/opt/.../adb-tools/adb）；
- `AdbExecutableLocator` 的 `bundledCandidate` 对不可执行的内置 adb 直接抛
  `AdbNotExecutableException`，干净用户机上 adb 完全不可用（本机因 PATH/SDK
  中存在其他 adb 而被掩盖）。

另有：安装的 `/usr/share/applications/androidperfermancestudio-AndroidPerfermanceStudio.desktop`
缺少 `StartupWMClass`，GNOME Dock 无法把运行中的窗口匹配到 .desktop → 任务栏
无图标（用户报告的问题 1）；应用名拼写 `AndroidPerfermanceStudio` 遍布
窗口名/包名/错误文案；本地构建的 deb 中 `lib/runtime/` 缺少 `bin/` 目录。

## Goals / Non-Goals

**Goals:**
- 打包产物内所有应可执行的二进制在 .deb/.rpm 与 app-image 中均为可执行，并在
  构建时自动校验失败即 fail。
- 安装的 .desktop 含 `StartupWMClass`，Dock 正常显示应用图标。
- 用户可见文案中的应用名拼写统一为 AndroidPerformanceStudio。
- 查明 runtime `bin/` 缺失原因并消除或记录。

**Non-Goals:**
- 不替换 Compose Desktop 打包插件或迁移到自定义 jpackage 流程。
- 不处理 Windows/macOS 打包（权限模型不同，未观察到问题）。
- 不做旧包自动迁移/卸载（文档说明卸载重装即可）。

## Decisions

### D1: exec-bit 修复改为"可执行清单"驱动

**选择**：在 build.gradle.kts 定义可执行文件相对路径清单
（`adb-tools/adb`、`perfetto-tools/trace_processor_shell`、以及
`compose-agent-bundle` 内的原生 agent `.so`——以目录约定/扩展名判定），
repack `doLast` 遍历清单 chmod + 校验；app-image 路径（`createDistributable`）
同样校验。清单来源与 `AdbExecutableLocator`/`TraceProcessorToolResolver`
等运行时定位点对齐，新增可执行资源时同步维护。

**替代方案**（否决）：对资源目录全量递归 `setExecutable` 按扩展名（`.so`/无扩展
大文件）猜测——误伤文档/图片资源，且静默放过清单遗漏；在 `prepareProfilerAppResources`
的 `doLast` chmod 源目录——源目录权限正确，问题出在插件拷贝阶段，无效果。

### D2: .desktop 修补与校验并入同一 repack 步骤

**选择**：deb repack 时定位 `usr/share/applications/*.desktop`（及 app-image 内
`lib/*.desktop`），校验 `StartupWMClass=` 并与**窗口实际 WM_CLASS** 对齐后写入；
`Icon=` 指向的文件存在性一并校验。校验失败/修补完成均在
`task.logger.lifecycle` 输出明确日志。

**WM_CLASS 取值（已实测验证）**：普通 JDK 的 AWT X11 工具包从启动器主类名派生
WM_CLASS（`.`→`-`，如 `com.androidperformancestudio.desktop.MainKt` →
`com-androidperformancestudio-desktop-MainKt`），且**忽略 `awt.appclass` 系统属性**
（JBR 特有支持；打包 runtime 由 Compose 插件从 Gradle JDK（Temurin）jlink 而来）。
因此 repack 从 payload 的 `lib/app/*.cfg` 读取 `app.mainclass` 派生
`StartupWMClass`，不依赖 jpackage 版本行为，也不引入会被 JBR/Temurin 差异破坏的
第二个事实源。已安装实例实测：运行窗口 WM_CLASS 与 StartupWMClass 完全一致。

**理由**：与 D1 同一生命周期钩子，无新增构建环节。

### D3: 拼写修正采用全仓替换 + 兼容审查

**选择**：用户可见字符串全部改为 `AndroidPerformanceStudio`；包名
`packageName = "AndroidPerformanceStudio"`（影响 deb 包名/安装路径，标记
BREAKING）；内部标识（如 `jvmArgs -Dapple.awt.application.name`）同步修正。
替换后全局 `grep -ri perfermance` 必须为 0 处（构建脚本生成目录除外）。

**风险点**：`androidperfermancestudio.adbToolsDir` 系统属性名（`AdbExecutableLocator.kt:40`）
若在打包脚本/文档中引用需同步；`APP_DISPLAY_NAME` 若被设置存储/单测断言引用需同步。

### D4: runtime `bin/` 缺失以调查结论收尾

**调查结论（已本地复现验证）**：用完整 Temurin 21 JDK 本地执行 `packageDeb`，
产物 `lib/runtime` 同样缺少 `bin/` 与 `lib/jli/`——这是 **Compose Desktop 插件
的系统性 jlink 行为**（剥离 native commands，`bin/java` 不进入 runtime image），
而非构建环境污染或损坏。启动器经 JNI 加载 `lib/runtime/lib/server/libjvm.so`，
应用可正常运行（已安装实例验证）。

**选择**：校验改为检查 runtime 的功能性核心（`lib/modules` +
`lib/server/libjvm.so`），不要求 `bin/java`；结论记录于本文与 spec。

**替代方案**（否决）：强行要求 `bin/java` 存在——与插件行为冲突，会导致所有
构建失败。

## Risks / Trade-offs

- [包名变更导致已装用户升级失败] → 发布说明注明先卸载旧包；版本号保持递增。
- [可执行清单遗漏未来新增的二进制] → 校验步骤fail-loud + 文档化"新增可执行
  资源须同步清单"；清单覆盖现有全部已知运行时定位点。
- [jpackage 不同版本对 .desktop 内容差异] → 修补逻辑幂等（已存在则跳过），
  校验兜底。
- [全局拼写替换波及非用户可见标识] → 替换范围审查 + 全量构建 + 相关模块测试。

## Migration Plan

1. 落地 gradle repack 校验/修补与拼写替换。
2. 本地 `packageDeb` 全量构建，按校验清单逐项验证（adb 执行位、StartupWMClass、
   runtime bin、图标存在）。
3. 卸载旧 deb → 安装新 deb → Dock 图标、应用名、SystemUI 抓取（配合
   fix-capture-nonfinite-floats）实测。
4. 回滚 = revert；旧包继续可用（包名不同互不冲突）。

## Open Questions

- `compose-agent-bundle` 内是否有除 `agent.so` 外的其他可执行文件 → 实现时
  以 bundle 构建产物清单为准。
