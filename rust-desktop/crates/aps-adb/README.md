# aps-adb

基于 `aps-platform` 的只读 Android 目标发现；不依赖第三方库。

`AdbLocator::locate` 首先检查已配置的可执行文件（显式路径无效时直接失败，
不会静默回退），然后依次检查 `ADB_PATH`、`ADB`、`ANDROID_HOME`、
`ANDROID_SDK_ROOT`、PATH 中的绝对路径，以及主机惯用的 SDK 目录。
它会验证普通文件和可执行文件属性，并返回规范化路径。
它不会执行候选文件、检查凭据文件、读取 Compose 设置，也不会将批处理文件
作为 Windows 可执行文件接受。只保留相关的环境变量键。目前尚未打包 Rust ADB
资源包：经校验和/清单验证的资源包集成仍是独立的发布门禁，而不是未经验证的回退方案。

`AdbClient` 操作是阻塞的，应在 UI 后台执行器上运行：

- `list_devices`：`adb devices -l`，保留 online、offline、unauthorized、
  no-permission 以及未来未知状态。
- `device_properties`：对选定 serial 执行 `shell getprop`，只暴露身份、
  Android release/SDK 和 ABI 字段。
- `list_processes`：对选定 serial 执行 `shell ps -A -o PID,PPID,USER,NAME`。
- `device_snapshot`：按顺序记录上述属性和进程观测，不是原子快照，也不能证明
  进程实例身份持续有效。

每条选定目标命令都带有经过显式校验的 `-s` serial。不暴露任意远程 shell
输入、install、push、root、forward 或采集变更 API。适配器采用有界输出解析，
返回经过清理的命令失败信息，不包含捕获的 stderr。调用发现操作可能会启动正常的
本地 ADB server；实现期间未运行任何 ADB 命令或设备操作。

`tests/` 下提供了纯解析器和伪文件系统测试源，但在完整重写实现之前会按计划不执行。
原生 ADB、真实设备、跨平台以及打包资源包验收仍待完成。
