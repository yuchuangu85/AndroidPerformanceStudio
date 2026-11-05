# Design: add-device-screencast

## Context

- 参考实现来源：aya（Electron + `@yume-chan/scrcpy` + WebCodecs）验证了"推 server jar + adb reverse 隧道 + 宿主机本地监听"的连接模型；GitHub 调研确认纯 JVM 内嵌 scrcpy 协议在 IntelliJ 插件（elkidz/scrcpy-studio，jcodec 软解）中已被完整验证过，架构可直接借鉴（仅借鉴思路，该项目无开源许可证，不复制代码）。
- 本应用是 Kotlin Compose Desktop（JVM 21），`platform-core/adb-core` 已有进程级 `AdbClient`（`shell`/`push`/`forward` 等），但**没有 `reverse`，也没有流式 socket 抽象**——投屏所需的持续字节流直接用 `java.net.Socket` 处理，不强行塞进 `AdbClient`。
- 导航结构：`desktop-app` 的 `AppDestination` 枚举 + `AppNavigator`，新模块按现有 profiler 模块模式接入。
- scrcpy 协议本身是 Apache-2.0 的官方文档（`docs/protocol.md`），server jar（v3.1，~100KB）可合法随应用分发，设备端无需 root。

## Goals / Non-Goals

**Goals:**
- 应用内实时观看设备屏幕（video 流解码渲染，帧延迟以百毫秒内为目标）。
- 反向控制：鼠标触摸/滚轮/键盘/导航键/剪贴板粘贴。
- 会话生命周期可靠：连接、设备掉线、手动停止、模块关闭时的资源清理（设备端 server 进程、reverse 隧道、本地 socket）。
- 与现有模块一致的导航、设置、双语文案集成。

**Non-Goals:**
- 音频转发（scrcpy 3.x 音频流可后续追加）。
- 录屏/截图（可后续追加；截图已有 `adb exec-out screencap` 路径）。
- 与 Layout Inspector 的实时画面联动（有想象空间，但独立成后续 change）。
- 无线配对、多设备同时投屏、硬解（JavaCV/FFmpeg）。
- 替代外部 scrcpy 桌面客户端。

## Decisions

### D1. 内嵌协议客户端，不 spawn 外部 scrcpy 二进制

**选择**：JVM 进程内实现 scrcpy 协议客户端；仅把 `scrcpy-server` jar 作为资源分发到设备端。

**理由**：与产品"应用内统一工具"定位一致；外部进程方案（ScrcpyHub/ADBDesk 路线）窗口风格割裂、无法未来与 Layout Inspector 联动。aya 与 scrcpy-studio 均验证了内嵌模型的可行性。

**备选**：spawn 外部 scrcpy（工作量小但体验割裂，弃）；vlcj/mpv 混合渲染（引入重原生依赖，弃）。

### D2. 连接模型：ServerSocket + adb reverse（学 aya/scrcpy-studio）

```
┌─ 桌面应用（JVM）────────────────────────────────────────┐
│  ServerSocket(0) 随机端口                                │
│      ▲ adb reverse localabstract:scrcpy_XXXXXXXX tcp:PORT│
│      │                                                   │
│  adb push scrcpy-server → adb shell app_process 启动     │
│                                                          │
│  video socket   ──▶ ScrcpyVideoStreamReader              │
│  control socket ──▶ ScrcpyControlWriter（写）/ 读设备消息 │
└──────────────────────────────────────────────────────────┘
```

流程：`adb push` server jar → `adb reverse localabstract:<name> tcp:<port>` → `adb shell CLASSPATH=... app_process ... Server <version> <options>` 启动设备端 → `accept()` 两次获得 video / control socket（video 先连，control 靠首字节检测，同 aya 的判定逻辑：读到 audio metadata 值即 audio/control 流区别对待；本版无音频，control 流首包按 scrcpy v3 协议判别）。

**理由**：reverse 隧道免去宿主机防火墙问题，且是 scrcpy 官方默认模型；`ServerSocket(0)` 避免端口冲突。

**备选**：`adb forward` + server 主动连（需要设备端访问宿主 IP，模拟器/USB 下更脆弱，弃）。

### D3. H.264 解码用 jcodec 纯 Java 软解

**选择**：`org.jcodec:jcodec:0.2.5`，`H264Decoder` → `Picture` → AWT `BufferedImage` → Compose `ImageBitmap`。

**理由**：零原生依赖、零分发负担、已有 IntelliJ 插件先例验证可行；调试工具场景下降载后的软解 CPU 开销可接受。

**实现要点**（踩坑后确认的正确用法）：
- **Annex-B 配置**：scrcpy 的 codec config packet 是 Annex-B 格式（start-code 分隔的 SPS/PPS），不是 mp4 的 `AVCDecoderConfigurationRecord`，不能用 `createH264DecoderFromCodecPrivate`。改为把配置 NAL 前置到下一个关键帧一起交给 `decodeFrameFromNals`，由 jcodec 的内联注册路径处理。
- **必须提供输出平面缓冲**：`decodeFrameFromNals(nals, buffer)` 把解码像素写入调用方提供的 `byte[][]`（YUV420 三平面）；传空数组会在宏块写入时越界崩溃。按会话视频尺寸预分配（上限 4K）并跨帧复用。
- **视频元数据是 12 字节**：v3.x codec meta 为 `codec_id(u32) + width(u32) + height(u32)`（大端），与 `@yume-chan/scrcpy` 官方实现一致。
- **像素转换直通栅格**：jcodec `AWTUtil` 输出 `TYPE_3BYTE_BGR`；直接从 `DataBufferByte` 按 `scanlineStride`/`pixelStride` 逐行打包成 Skia N32（BGRA、补不透明 alpha），绕过 `BufferedImage.getRGB` 的逐像素 ColorModel 开销。注意 `ComponentSampleModel.getOffset(0, y)` 返回的是首采样（B 通道，band offset 2）位置，不是像素起点，需减去 band offset。

**备选**：JavaCV/FFmpeg 硬解（性能好但引入 ~100MB 原生库分发，留作后续升级路径）；JavaFX MediaPlayer（线程模型冲突，弃）。

### D4. control 消息手写二进制编解码

**选择**：按 scrcpy 官方协议手写 `ScrcpyControlWriter`（injectTouch/injectScroll/injectKeycode/setClipboard/back/home/recents）与设备消息读取（clipboard 变化、ACK）。

**理由**：control 协议是小而稳定的二进制格式（类型字节 + 定长字段），手写约几百行 Kotlin，无依赖。协议文档（Apache-2.0）足够。

**备选**：移植 `@yume-chan/scrcpy`（TypeScript，不可用）；引第三方 JVM scrcpy 库（调研未发现成熟维护的库）。

### D5. 模块结构与分层

```
desktop-viewer/device-screencast/
  screencast-core/        # 协议 + 会话（纯 JVM，可测试）
    protocol/             # control writer、video stream reader、control message reader
    session/              # ScrcpySession（协程作用域、生命周期、清理）
    server/               # server jar 资源管理、启动命令构造、push/reverse 编排
  screencast-presentation/ # Compose UI：画布、坐标换算、输入拦截、工具栏
  screencast-app/          # 与 desktop-app 的集成（destination、设置）
```

- `screencast-core` 依赖 `adb-core`（用 `AdbClient.push/shell/reverse/removeReverse`），不依赖 Compose。
- 解码线程产出帧到 `MutableStateFlow<ImageBitmap?>` 或 `SharedFlow`，presentation 层 `Canvas` 绘制。

### D6. `AdbClient` 增加 reverse 能力

`AdbClient` 增加 `reverse(serial, remote, local)` 与 `removeReverse(serial, remote)`，与现有 `forward`/`removeForward` 完全对称（走同一 `executeText` 管道，`AdbInputValidator` 增加 reverse endpoint 校验）。

### D7. 生命周期与清理

- 会话持有 `CoroutineScope(SupervisorJob() + Dispatchers.IO)`；停止 = cancel scope + 关闭 sockets + `adb reverse --remove` + kill 设备端 server（`adb shell kill` server 记录的 pid，或 `app_process` shell 断开即随会话退出——v3.x server 在 socket 断开后自行退出）。
- 设备掉线（socket EOF / adb devices 移除）→ 会话进入 `Disconnected` 状态并通知 UI；重启 = 全新会话。
- 模块 evict（`AppNavigator.evict`）时销毁会话。
- scid/socket name 用随机生成，避免同设备多会话冲突。

**实现修正（真机验证发现）**：
- **服务器 shell 不得使用默认超时**：`adb shell` 的 AdbClient 默认 30s 超时会杀掉 shell 及其子进程（设备端 server），导致会话固定在约 30s 时"自动断开"。服务器 shell 用 24h 超时长跑（进程运行器拒绝无限时长），由会话停止时的协程取消正常终止。
- **UI 层设备列表/选中态用 `StateFlow` 暴露**：普通 getter 快照不会触发 Compose 重组，刷新成功后下拉框仍显示旧空列表；帧像素转换放 `Dispatchers.IO` 并 `conflate()`（解码落后时只保留最新帧），不在 UI 线程做逐像素处理。
- **JVM `Dispatchers.Main` 需要显式依赖**：Compose Desktop 无默认 Main dispatcher，presentation 模块需引 `kotlinx-coroutines-swing`。
- **adb 可执行路径用共享 AdbLocator 解析**：不能把 SDK 目录直接传给 `DefaultAdbClient`（需 `platform-tools/adb` 可执行文件路径）；走 `defaultAdbCommandExecutable(AdbConfiguration(androidSdkPath = ...))`，失败回退系统 PATH。
- **清理时序**：取消服务器 shell 后延迟 ~500ms 再 `remove reverse` / `rm jar`，给设备端进程树退出时间；`removeReverse` 失败重试一次并打印警告，避免孤儿隧道静默残留。

### D8. scrcpy-server 分发

把 `scrcpy-server-v3.1` 提交到 `device-screencast/screencast-core/src/main/resources/scrcpy/scrcpy-server`（~100KB，Apache-2.0 许可证文件一并提交）。启动时从 classpath 解出临时文件再 `adb push`（adb push 需要真实文件路径）。版本校验：启动时对 server 文件做 SHA-256 记录日志，防资源损坏。

## Risks / Trade-offs

- **[jcodec 软解 CPU 占用]** → 用启动参数把编码流降载到软解预算内（`max_size=1280`、`max_fps=10`、`video_bit_rate=2Mbps`），使服务器产出 ≈ 宿主消费、通道不积压（积压最终会撑爆缓冲并杀死设备端 server）；帧经 `conflate` 合流后仍显示最新画面。若需更高清晰度/帧率，后续 change 引入 JavaCV/FFmpeg 硬解（D3 的升级路径，协议层不变）。
- **[scrcpy v3.1 协议兼容]** → server 与客户端协议版本锁定 v3.1；连接时校验 server 输出的版本行；scrcpy 官方文档明确协议兼容性仅同一大版本内保证。
- **[设备端 server 残留]** → v3.x server 在宿主断开后自行退出；停止流程仍显式取消服务器 shell（24h 超时长跑协程）、延迟后 `reverse --remove`（失败重试一次）并 `rm` 已推送 jar；会话启动失败时按顺序回滚（kill shell → remove reverse → 清理本地）。服务器 stdout/stderr 捕获到应用日志，断开原因可诊断。
- **[control 流与 audio 流混淆]** → 本版禁用音频（`audio=false` 启动参数），server 只开 video + control 两条流，连接判定逻辑简化。
- **[jcodec 对个别 H.264 流的兼容性]**（如某些设备特有的 profile）→ 启动参数限制 baseline/main profile 或降低分辨率作为降级手段；失败时 UI 给出明确错误与"重试"。
- **[Compose 画布高频重绘性能]** → 帧以 `ImageBitmap` 复用缓冲，`Canvas` 按帧到达驱动重绘；目标 30fps 下 Compose 重绘开销可控。
- **[许可合规]** → scrcpy-server 是 Apache-2.0，随应用分发需在关于页/文档保留许可证声明。

## Migration Plan

- 纯新增模块 + `AdbClient` 接口扩展（`DefaultAdbClient` 同步实现，现有调用方不受影响）。
- 无数据迁移；回滚 = 移除导航入口与模块注册即可（模块代码可保留不发布）。
- server jar 二进制入库一次；后续升级 scrcpy 版本 = 替换资源文件 + 校验协议兼容。

## Open Questions

- 画布交互细节（hover 高亮、缩放模式）在实现 tasks 中按现有 Canvas 命中测试模式（Layout Inspector 的 `CanvasHitTester` 先例）微调，不阻塞设计。
