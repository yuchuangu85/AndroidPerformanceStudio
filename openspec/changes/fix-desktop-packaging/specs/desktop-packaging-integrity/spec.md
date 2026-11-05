# Spec Delta: desktop-packaging-integrity

## ADDED Requirements

### Requirement: 打包产物内可执行二进制保持可执行位

Linux 打包流程（.deb/.rpm 与可分发的 app-image）SHALL 保证应用资源中所有声明
为可执行的二进制（adb-tools/adb、perfetto-tools/trace_processor_shell、
compose-agent-bundle 原生 agent 等）在最终产物中具备可执行位，且打包任务
MUST 在产物校验失败时使构建失败。

#### Scenario: deb 内 adb 可执行

- **WHEN** 执行 `packageDeb` 并解包产物
- **THEN** `lib/app/resources/adb-tools/adb` 具有可执行位

#### Scenario: 清单遗漏导致构建失败

- **WHEN** 产物中存在清单内声明但不可执行的二进制
- **THEN** 打包任务以明确错误信息失败，而非静默出货损坏的包

### Requirement: Linux 桌面集成字段完整

打包流程 SHALL 确保安装的 .desktop 文件包含 `StartupWMClass` 与指向存在的
图标文件的 `Icon=`。`StartupWMClass` 的值 MUST 与运行窗口的 X11 WM_CLASS
对齐：由 payload 中 `lib/app/*.cfg` 的 `app.mainclass` 派生（`.` → `-`），
而非直接取打包 packageName（普通 JDK 的 AWT 忽略 `awt.appclass`，WM_CLASS
由主类名决定）。缺失时 MUST 自动补写/校验失败即报错，保证 GNOME Dock 能匹配
窗口并显示应用图标。

#### Scenario: Dock 图标正常显示

- **WHEN** 通过安装的 .desktop 启动应用
- **THEN** 窗口的 WM_CLASS（由 `app.mainclass` 派生）与 .desktop 的
  StartupWMClass 匹配，Dock 显示应用图标而非通用占位图标

### Requirement: 应用名拼写统一

用户可见的应用名 SHALL 统一为 `AndroidPerformanceStudio`（窗口标题、Dock
显示名、错误文案、打包 packageName），构建产物与源码中 MUST NOT 残留
"Perfermance" 拼写变体。

#### Scenario: 全仓无拼写残留

- **WHEN** 对源码与构建脚本执行大小写不敏感的 "Perfermance" 搜索
- **THEN** 无匹配（构建生成目录除外）

### Requirement: 打包 runtime 完整性

Linux 打包产物 SHALL 包含可运行的捆绑 Java runtime（`lib/runtime/lib/modules`
与 `lib/runtime/lib/server/libjvm.so` 存在），打包校验 MUST 对该结构进行检查，
缺失时构建失败。注：Compose Desktop 插件的 jlink 会剥离 `bin/`（启动器经 JNI
加载 JVM），`bin/java` 缺失不属于缺陷。

#### Scenario: runtime 完整性校验

- **WHEN** 执行打包任务并检查产物
- **THEN** `lib/runtime/lib/modules` 与 `lib/server/libjvm.so` 存在（否则构建以明确错误失败）
