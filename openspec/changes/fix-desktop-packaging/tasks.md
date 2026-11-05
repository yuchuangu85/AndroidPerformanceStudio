# Tasks: fix-desktop-packaging

## 1. exec-bit 修复泛化

- [ ] 1.1 在 `desktop-app/build.gradle.kts` 定义可执行资源清单（`adb-tools/adb`、`perfetto-tools/trace_processor_shell`、compose-agent-bundle 原生 agent），将现有 repack 的单一 `binaryName` 逻辑改为按清单遍历
- [ ] 1.2 repack 后校验清单内全部文件可执行，失败时使构建失败（保留现有 dpkg-deb 不可用时的告警降级路径，但降级时必须显式告警 adb 执行位风险）
- [ ] 1.3 `createDistributable`/`createReleaseDistributable` 的 app-image 产物同样按清单校验（Compose 插件拷贝丢权限的同一根因）

## 2. .desktop 修补与校验

- [ ] 2.1 deb repack 步骤中定位 `usr/share/applications/*.desktop` 与 app-image 内 `lib/*.desktop`，校验/补写 `StartupWMClass=<packageName>` 与 `Icon=` 指向的文件存在
- [ ] 2.2 输出明确构建日志（修补了什么、校验结果），幂等可重复执行

## 3. runtime 完整性

- [x] 3.1 本地执行 `packageDeb` 复现并判定 `lib/runtime/bin` 缺失原因：系 Compose Desktop 插件 jlink 剥离 native commands 的系统性行为（启动器经 JNI 加载 JVM），结论记录于 design.md D4
- [x] 3.2 repack 校验步骤改为检查 runtime 功能性核心（`lib/modules` + `lib/server/libjvm.so`），不要求 `bin/java`

## 4. 拼写统一

- [x] 4.1 全仓（含构建脚本、文案、系统属性名引用）将 `AndroidPerfermanceStudio`/`androidperfermancestudio` 替换为正确拼写，逐一审查替换点是否为用户可见或影响包名/路径
- [x] 4.2 更新受影响的测试断言与文档引用，`grep -ri perfermance` 归零（build 生成目录除外）
- [x] 4.3 在发布说明/AGENTS.md 标注包名变更（旧包需卸载重装）

## 5. 验证

- [x] 5.1 全量 `:desktop-app:packageDeb` 构建，按清单逐项校验产物（adb 执行位、trace_processor_shell 执行位、StartupWMClass、Icon 存在、runtime/bin）
- [x] 5.2 卸载旧 deb → 安装新 deb：Dock 图标显示、窗口标题/应用名正确拼写、adb 功能正常
- [x] 5.3 运行受影响模块测试（desktop-app、adb-gateway 文案相关）
