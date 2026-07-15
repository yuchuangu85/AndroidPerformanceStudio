# Android Performance Studio V0.1 Release Checklist

## 自动化门禁

- [x] `./gradlew check`：JUnit、ktlint、detekt。
- [x] 百万样本 PoC：导入不 OOM，Top/Timeline/Flame CPU 准备达到门槛。
- [x] Android 10/API 29 到 Android 16/API 36、arm64-v8a/armeabi-v7a/x86_64 合成 protobuf 兼容矩阵。
- [x] Android NDK r27b device simpleperf 的四种 ABI 资产随桌面应用打包，提取后 ELF magic 与 SHA-256 自动校验。
- [x] Golden 会话在含空格/中文路径完成导入、分析、JSON/CSV 导出、打包、重开。
- [x] 会话包清单/hash、zip-slip、重复条目、符号链接、条目/总大小上限测试。
- [x] 本机 macOS portable app image 与 DMG 生成。
- [x] 本机 macOS portable app 使用精简运行时启动，持续运行且标准输出/错误无异常。
- [x] GitHub Actions [run 29274136077](https://github.com/yuchuangu85/AndroidPerfermaceStudio/actions/runs/29274136077) 的 macOS/Windows/Linux clean runner 构建与打包全绿。
- [ ] GitHub Actions 上传 DMG/MSI/DEB/RPM/portable artifacts；上传为非阻断步骤，额度耗尽时仍须在发布前补齐。

## Profile database migration

- [x] Migration works on a copy; `profile.sqlite` is replaced only after the migrated candidate and retained evidence pass verification.
- [x] The first successful migration retains `profile.v1.sqlite` and its SHA-256 in `migration.properties`.
- [x] Backup and metadata publication uses fail-closed hard links; if publication, verification, or migration fails, the application opens the original database in legacy read-only mode.
- [x] Availability is reported as exactly one of: Available, Empty, Not collected, Unavailable, Unauthorized, Failed, or Not applicable.
- [x] Users must copy the complete session directory before attempting manual SQLite repair.

## 人工/硬件门禁

- [ ] profileable 真机执行一次 Start → Stop → pull → report。
- [ ] 真机取消后确认设备临时文件删除且本地日志保留。
- [ ] Windows 10/11 安装、启动、卸载；路径空格/中文；ADB 与截图。
- [ ] Ubuntu X11 和 Wayland 安装/启动；DEB、RPM、portable；ADB 与截图限制说明。
- [ ] macOS 13+ Apple Silicon 安装/启动；Intel 若发布则单列验证。
- [ ] 正式 DMG/MSI/Linux 包签名、公证或仓库签名策略确认。

## 正确性对照

- [x] `simpleperf report --sort symbol` 参数适配和 exclusive 权重百分比容差比较测试。
- [x] `report_html.py` 成功且非空 HTML 验证。
- [x] Android Studio/Perfetto 外部打开入口和语义说明。
- [ ] 使用同一真实 Golden `perf.data` 人工比较 sample 数、线程、Top、CallTree/Flame 路径。

## 文档与交付物

- [x] `README.md`、需求、产品设计、开发计划、技术 ADR。
- [x] `docs/user-guide.md`、`docs/troubleshooting.md`。
- [x] `docs/release-notes-v0.1-rc.md` 记录能力、验证证据和已知限制。
- [x] 可复现生成的 `golden.apsession.zip` 示例会话。
- [x] 安装器版本因 macOS jpackage 约束使用 `1.0.0`；产品里程碑仍为 V0.1。
- [ ] 各平台安装包 SHA-256 随 GitHub Release artifacts 发布。

只有全部必须门禁完成后才标记正式 V0.1；在真机、三平台安装和签名门禁完成前，产物属于 release candidate。
