# 全平台原生安装包格式设计

## 目标

将 AgentPerf Inspector 的 GitHub Release 扩展为 Compose Desktop 原生分发插件支持的全部 x64 安装包格式：

- Linux：DEB、RPM
- Windows：MSI、EXE
- macOS：DMG、PKG

每次发布必须先通过完整测试，再由三个操作系统任务分别生成两个安装包，最后将恰好六个固定命名的产物发布到同一个 GitHub Release。

## 范围

- 保持现有 x64 架构，不增加 ARM64 构建。
- 保持现有标签触发和手动触发方式。
- 保持现有版本解析、macOS 版本规范化和 GitHub Release 创建或覆盖逻辑。
- 不引入第三方打包插件；继续使用 Compose Desktop 基于 `jpackage` 的原生分发能力。
- 不在本次工作中增加代码签名、公证或 Linux 仓库发布。

## 架构

### Gradle 原生分发配置

`desktop-viewer/desktop-app/build.gradle.kts` 将声明六种 `TargetFormat`：

```kotlin
targetFormats(
    TargetFormat.Deb,
    TargetFormat.Rpm,
    TargetFormat.Msi,
    TargetFormat.Exe,
    TargetFormat.Dmg,
    TargetFormat.Pkg,
)
```

Compose Desktop 仍只会在当前宿主操作系统上执行对应格式的任务，因此 GitHub Actions 需要 Linux、Windows 和 macOS 三个独立打包任务。

### GitHub Actions 打包任务

发布工作流采用“每个平台一个任务”的结构：

- `package-linux` 在 x64 `ubuntu-latest` 上安装 RPM 构建工具，再依次执行 `packageDeb` 和 `packageRpm`。
- `package-windows` 在 `windows-latest` 上依次执行 `packageMsi` 和 `packageExe`。
- `package-macos` 在明确的 x64 `macos-15-intel` runner 上依次执行 `packageDmg` 和 `packagePkg`，避免 `macos-14` 当前映射到 ARM64 runner 后仍把产物错误标记为 x64。

每个任务只检索自己对应格式的输出目录，并要求每种格式恰好存在一个文件。文件复制到 `release-assets` 后使用稳定名称：

- `AgentPerf-Inspector-<version>-linux-x64.deb`
- `AgentPerf-Inspector-<version>-linux-x64.rpm`
- `AgentPerf-Inspector-<version>-windows-x64.msi`
- `AgentPerf-Inspector-<version>-windows-x64.exe`
- `AgentPerf-Inspector-<version>-macos-x64.dmg`
- `AgentPerf-Inspector-<version>-macos-x64.pkg`

每个平台将自己的两个文件作为一个 GitHub Actions artifact 上传。

### 发布数据流

1. `resolve` 验证版本并生成规范化版本号与标签。
2. `test` 在 Linux 上运行完整 Gradle 测试。
3. 三个平台打包任务都依赖 `resolve` 和 `test`。
4. `publish` 依赖三个打包任务，下载并合并全部 artifact。
5. `publish` 要求下载目录恰好包含六个文件，并逐一校验预期文件名。
6. 工作流创建新 Release，或使用 `--clobber` 更新同一标签下的现有资产。

## 错误处理

- 任一 Gradle 打包任务失败时，对应平台任务立即失败。
- Linux runner 无法安装或调用 `rpmbuild` 时，Linux 打包任务立即失败。
- 任一格式没有文件或出现多个候选文件时，准备资产步骤失败并输出格式名称及实际数量。
- 发布前缺少任何预期文件、出现额外文件或总数不是六个时，发布任务失败。
- 只有 `publish` 任务拥有 `contents: write`，其余任务保持只读权限。

## 测试与验证

自动化测试将验证：

- Gradle 配置包含六种 `TargetFormat`。
- 工作流包含 Linux、Windows、macOS 三个打包任务及正确 runner。
- 六个格式对应的 Gradle 任务均被调用。
- 六个稳定资产名称全部存在于工作流。
- `publish` 同时依赖三个平台任务，并严格校验六个资产。

本地验证包括：

- 先运行新增测试并确认因缺少格式而失败。
- 实施配置后运行目标测试并确认通过。
- 运行完整 `./gradlew test`。
- 在当前 macOS 环境实际运行 `packageDmg` 和 `packagePkg`。
- 解析工作流 YAML，并执行 `git diff --check`。

Windows MSI/EXE 与 Linux DEB/RPM 的真实打包只能由对应 GitHub-hosted runner 完成，因此本地不声明这四种安装包已经实际生成。
