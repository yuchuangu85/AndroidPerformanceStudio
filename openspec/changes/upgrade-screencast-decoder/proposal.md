# Proposal: upgrade-screencast-decoder

## Why

当前投屏用 jcodec 纯 Java 软解，为匹配其消费能力把编码流降载到 1280 宽 / 10fps / 2Mbps，画面文字模糊、滚动与动画观察明显掉帧——而「看动画/滚动行为」正是调试帧问题时的核心诉求。jcodec 的解码吞吐是硬瓶颈，无法靠调参解决；FFmpeg（JavaCV 封装）软解吞吐高出 10-50 倍，可在原生分辨率下保持 30fps，画质与流畅的取舍难题直接消失。

## What Changes

- **解码器替换**：`H264FrameDecoder`（jcodec）重实现为 JavaCV FFmpeg（`avcodec` 软解 + `swscale` YUV→BGRA），保持现有 `configure`/`decode` 接口形状，`ScrcpySession` 解码循环基本不动；jcodec 实现保留为 FFmpeg 原生库加载失败时的回退。
- **质量档位**：投屏页面内新增画质选择下拉框（流畅 / 高清 / 原生），手动选择，默认「高清」。档位映射为 scrcpy 启动参数（`max_size` / `max_fps` / `video_bit_rate`）：

  | 档位 | max_size | max_fps | video_bit_rate | 预期 |
  |------|----------|---------|----------------|------|
  | 流畅 | 1280 | 10 | 2M | 最低延迟，画面最轻量（jcodec 回退档） |
  | 高清（默认） | 1600 | 60 | 12M | 文字清晰，动画流畅；60fps 流畅度翻倍 |
  | 原生 | 设备原始尺寸（不传 max_size） | 30 | 20M | 最大清晰度；30fps 上限规避设备端编码排队延迟 |

- **档位切换**：会话运行中切换档位 = 以新参数停止并重启会话（scrcpy 编码参数为启动期选项），对用户表现为画面短暂刷新。
- **依赖**：新增单平台 JavaCV/FFmpeg 依赖（`org.bytedeco:ffmpeg` + `javacv`，linux-x86_64 分类器，约 100MB 原生库），不引入 `javacv-platform` 全家桶。
- **VA-API 硬解（实现期扩展）**：在 FFmpeg 软解之上新增硬件解码路径（`FFmpegVaapiFrameDecoder`，avcodec 绑定 VAAPI hwdevice/hwframes 上下文），三层解码链：VAAPI 硬解 → FFmpeg 软解 → jcodec。硬解以运行时探测门控（探测 hwdevice 创建成功），探测缓存为进程级一次性。宿主机两个坑已处理：JavaCPP 自带 libva 与系统驱动 ABI 冲突时预载发行版 libva（`System.load`）；render 组节点权限。
- **零拷贝帧管线（实现期扩展）**：解码器接口从 AWT `BufferedImage` 改为 `DecodedByteFrame`（BGRA 字节，Skia N32 布局），消除「解码器逐像素打包 INT_RGB → UI 逐像素拆回 BGRA」双循环；UI 以 Skia `installPixels` 一次 memcpy 上屏。
- **帧率定档（实现期实测）**：高清 60fps / 原生 30fps（原生 60fps 实测因设备端编码排队劣化触控响应而放弃）；不提供 120fps（显示器 60Hz 垂直同步无收益）。
- **会话清理加固（实现期修复）**：档位切换触发 stop+start 时，清理协程在 `NonCancellable` 上下文执行 `adb reverse --remove`，避免竞态跳过残留隧道。

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `device-screencast`: 「实时解码渲染」需求变更——解码后端升级为 FFmpeg（jcodec 回退）并进一步引入 VA-API 硬解三层链与零拷贝帧管线；新增画质档位选择与运行中切换的行为要求，帧率按设备编码能力定档（高清 60fps / 原生 30fps）；「投屏会话建立」需求变更——启动参数按所选档位参数化，会话清理在不可取消上下文执行以防竞态残留隧道。

## Impact

- **代码**：
  - `device-screencast/screencast-core`：`H264FrameDecoder` 重写为 FFmpeg 实现（新 `FFmpegFrameDecoder`），`ScrcpyServerLauncher.serverCommand` 按档位参数化，`ScrcpySession` 构造接受解码器与档位参数。
  - `device-screencast/screencast-presentation`：头部工具栏新增画质档位下拉框（会话运行中可点），`ScreencastPageController` 传递档位、处理切换重启。
  - `device-screencast/screencast-app`：装配 FFmpeg 解码器（含 jcodec 回退逻辑）。
- **依赖**：`gradle/libs.versions.toml` 新增 `javacpp`/`ffmpeg`（bytedeco，linux-x86_64 分类器）/`javacv`；jcodec 依赖保留。
- **系统**：JavaCPP 首次加载会解压原生库到临时目录（首次投屏启动慢数秒）；解码多线程（avcodec 自动多核），高分辨率下 CPU 占用上升但可接受。
- **风险**：原生库分发体积 +100MB（内部调试工具可接受）；JavaCPP 平台锁定 linux-x86_64，其他宿主平台需后续按分类器扩展。
