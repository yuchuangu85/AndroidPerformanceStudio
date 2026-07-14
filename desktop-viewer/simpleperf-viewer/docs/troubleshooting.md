# Android Performance Studio V0.1 故障排查

## ADB 或设备不可见

1. 执行 `./gradlew :device-adb:runAdbSelfCheck`，确认 ADB 路径和版本。
2. 执行 `adb devices -l`：
   - `unauthorized`：解锁设备并接受 RSA 授权；
   - `offline`：重插 USB，执行 `adb kill-server && adb start-server`；
   - `no permissions`：Linux 配置厂商 udev 规则并重新登录；
   - 空列表：检查 USB 模式、线缆、开发者选项和 USB 调试。
3. 多套 SDK 时优先统一 `ANDROID_HOME`，避免旧 `adb` 抢占 `PATH`。

## 目标不可采集或权限被拒绝

- 普通 user build 通常只能采集 debuggable/profileable App；系统进程或其他 UID 可能需要 root/userdebug。
- UI 的 Ready/Limited/Blocked 来自实际 `id -u`、build type、SDK 和 simpleperf 探测。
- 工具不会自动执行 `adb root`，避免改变设备状态。
- 核对当前选择的包名/PID/TID 是否仍存在；进程重启后需刷新并重新选择 PID。

## simpleperf 不存在或启动失败

- 优先使用设备自带 simpleperf。
- bundled simpleperf 必须匹配 ABI，推送后会校验 SHA-256、执行权限和 `--version`。
- host simpleperf 必须是当前桌面平台可执行文件；配置错误时重新选择并检查 `--version` 输出。
- 路径含空格/中文无需手工加引号，应用使用参数数组启动子进程。

## 采集取消、超时或应用崩溃

- 会话目录仍保留 `capture-command.txt`、`record.*.log/properties`、`pull.*`、`cleanup.*` 和 `session.properties`。
- `status=CANCELLED` 表示用户取消；`status=FAILED` 同时记录错误 category/code/message。
- 清理命令使用新的取消信号；若设备已断开，重新连接后可手动执行：

```bash
adb -s <serial> shell rm -f /data/local/tmp/aps/perf.data
```

## protobuf / perf.data 导入失败

- `perf.data` 必须由兼容的 host simpleperf 转换。
- protobuf 必须包含 10 字节 `SIMPLEPERF` magic、版本 1、LE32 长度前缀和 0 长度终止记录。
- 解析错误包含 record index 和 byte offset；原始输入不会删除，可更换 simpleperf 版本重试。
- 导入失败只删除可重建的 SQLite/WAL/SHM 和临时目录。

## unknown symbol 或调用栈截断

- Java/Kotlin 混淆代码提供 Proguard/R8 mapping；Native 代码提供未 strip 的 `.so`/符号目录。
- 使用 DWARF callgraph 时确认 App 可被采样并有足够 stack；必要时降低频率。
- Diagnostics 中查看 unknown、empty stack、unwind error code/raw code/address 的比例和证据。
- `ERROR_NOT_ENOUGH_STACK` 可提高记录栈空间；`ERROR_UNWIND_INFO` 通常需要 native unwind/debug 信息。

## 丢样率高或数据看起来异常

- 降低采样频率、减少同时 event 数、缩短采集时长。
- 比较结果时保持设备、event、frequency/period、callgraph 和目标一致。
- `event_count` 是采样权重，不等同精确毫秒；以 Diagnostics 和外部工具对比为准。

## Linux / Windows / macOS 启动问题

- macOS：未签名开发包可能需要在“隐私与安全性”中确认；正式发布仍需签名/公证。
- Windows：确认 MSI 位数与系统匹配，安全软件未隔离 bundled 工具。
- Linux：DEB/RPM 包含 runtime；Wayland 截图受桌面安全策略限制时可改用 X11 或系统截图。portable 目录不要只复制启动脚本。
