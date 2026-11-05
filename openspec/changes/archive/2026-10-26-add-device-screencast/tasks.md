# Tasks: add-device-screencast

## 1. 基础设施准备

- [x] 1.1 `adb-core`：`AdbClient` 接口新增 `reverse(serial, remote, local)` 与 `removeReverse(serial, remote)`，`DefaultAdbClient` 实现（对称 `forward`），`AdbInputValidator` 增加 reverse endpoint 校验
- [x] 1.2 `adb-core`：补充 `reverse`/`removeReverse` 单元测试（命令构造、校验失败路径）
- [x] 1.3 依赖接入：版本目录新增 `jcodec 0.2.5`；下载 scrcpy-server v3.1（含 LICENSE）入库到 `device-screencast/screencast-core/src/main/resources/scrcpy/`，记录 SHA-256
- [x] 1.4 Gradle：新建 `device-screencast` 模块（`screencast-core`、`screencast-presentation`、`screencast-app`），按现有模块模式配置 build.gradle.kts 并加入 settings.gradle.kts

## 2. screencast-core：协议层

- [x] 2.1 `ScrcpyControlWriter`：control 消息二进制编码（injectTouch、injectScroll、injectKeycode、setClipboard、back/home/recents 按 keycode），按 scrcpy v3.1 协议文档实现
- [x] 2.2 `ScrcpyVideoStreamReader`：video socket 读取（设备名、codec id、帧分帧），以及 control socket 设备消息读取（剪贴板等）
- [x] 2.3 协议层单元测试：control 消息字节序列对照协议文档断言；video reader 对构造的字节流分帧断言

## 3. screencast-core：连接与会话

- [x] 3.1 `ScrcpyServerLauncher`：从 classpath 解出 server jar 到临时文件、`adb push`、`adb reverse`、构造 `app_process` 启动命令（audio=false）、按序失败回滚（kill shell → remove reverse → 清理临时文件）
- [x] 3.2 `ScrcpySession`：ServerSocket 监听、accept 两次（video 先、control 判定）、握手读取设备名/codec、会话状态机（Idle/Connecting/Running/Disconnected/Failed）与 `StateFlow` 暴露
- [x] 3.3 视频解码循环：jcodec `H264Decoder` 解码帧 → `ImageBitmap` 发射到帧流；解码线程与 socket 读线程的缓冲管理（config packet 处理）
- [x] 3.4 控制写入与生命周期绑定：`ScrcpySession` 暴露 sendTouch/sendScroll/sendKey/setClipboard/导航键 API，内部经 `ScrcpyControlWriter` 写 control socket
- [x] 3.5 清理逻辑：stop()/scope cancel 时关闭 sockets、remove reverse、等待设备端退出；异常路径（EOF、解码崩溃）同样触发清理并进入 Disconnected/Failed
- [x] 3.6 会话层测试：状态机转换（含重复 start 幂等、失败回滚顺序）、control writer 集成 mock socket 断言写入字节

## 4. screencast-presentation：Compose UI

- [x] 4.1 设备选择 + 开始/停止控制区（状态机驱动按钮状态，复用现有设备选择模式）
- [x] 4.2 投屏画布：`Canvas` 按帧流重绘，contain 缩放与宽高比保持，坐标换算（画布坐标 → 设备坐标，含钳制）
- [x] 4.3 输入拦截：鼠标按下/移动/抬起/滚轮 → sendTouch/sendScroll；键盘焦点与按键注入；导航键工具栏（返回/主页/最近任务）
- [x] 4.4 剪贴板输入条：文本输入 + "发送到设备"（setClipboard + paste）
- [x] 4.5 状态与错误展示：连接中进度、断开/失败原因、重试入口；双语文案资源（zh/en）

## 5. desktop-app 集成

- [x] 5.1 `AppDestination` 新增 `SCREENCAST`（title/navigation label 资源、shouldMaximizeWindow、hasNativeFeatureMenu 按需）
- [x] 5.2 导航接线：CommercialAppShell/DesktopAppMainPage 注册投屏页面，模块 evict 时销毁会话
- [x] 5.3 双语字符串资源与文档：导航文案、关于页 scrcpy Apache-2.0 许可证声明；用户文档补投屏使用说明

## 6. 验证

- [x] 6.1 全模块 `gradlew test` 通过（adb-core、screencast-core）
- [x] 6.2 真机验证（`RealDeviceSmokeTest` 自动化 + 人工 GUI 抽查）：TB355FU 会话建立、H.264 帧解码（1280×800 降载流）、画面实时显示且颜色正确；触摸点击、滚轮滚动（幅度与实际一致）、导航键注入均正常；长会话稳定（服务器 shell 24h 超时修复后无自动断开）
- [x] 6.3 清理验证：正常停止后 `adb reverse --list` 清空、设备端无残留 server 进程；重复开启/停止会话稳定。清理加固：取消 shell 后延迟再清理 + `removeReverse` 失败重试，杜绝孤儿隧道
- [x] 6.4 验证期发现并修复的问题（详情见 design.md D3/D7 实现修正）：
  - jcodec 用法三坑：Annex-B 配置需前置到关键帧、`decodeFrameFromNals` 必须传输出平面缓冲、视频元数据为 12 字节（u32×3）
  - 像素转换：TYPE_3BYTE_BGR 栅格直通打包（含 `getOffset` band-offset 陷阱），颜色通道错位已用反射探针验证修复
  - 稳定性：服务器 shell 30s 默认超时杀会话（改 24h）；编码降载（max_size=1280/max_fps=10/2Mbps）匹配软解消费；视频 socket 2MiB 接收缓冲
  - UI：`kotlinx-coroutines-swing` 提供 Main dispatcher；设备列表/选中态改 StateFlow 触发重组；adb 路径走共享 AdbLocator；滚轮每格映射 1.0 滚动单位
