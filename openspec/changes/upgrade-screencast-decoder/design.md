# Design: upgrade-screencast-decoder

## Context

`add-device-screencast` 已交付内嵌 scrcpy 投屏，但受 jcodec 纯 Java 软解吞吐限制（1280×800 下 ~10-15fps，原生分辨率 ~2fps），编码流被迫降载到 1280/10fps/2Mbps，画面文字模糊、无法流畅观察动画与滚动——而后者正是帧问题调试的核心场景。真机验证还确认了两个关键事实：

- 会话断连的真因是 adb shell 30s 默认超时（已修复为 24h），而非解码积压；TCP 反压链路本身自稳，解码慢只会让画面变慢不会断会话。
- `H264FrameDecoder` 是孤立的替换点：`ScrcpySession` 只依赖 `configure(payload)` / `decode(payload): BufferedImage?` 两个方法。

现需替换解码后端并引入用户可控的画质档位。

## Goals / Non-Goals

**Goals:**
- FFmpeg（JavaCV）软解替代 jcodec 为主解码器，原生分辨率下 30fps 可行。
- 投屏页面内画质档位选择（流畅/高清/原生，默认高清），运行中可切换。
- jcodec 保留为 FFmpeg 加载失败时的回退路径。

**Non-Goals:**
- 硬件加速解码（avcodec hwaccel / MediaCodec）——软解多线程已满足；留作后续。
- 音频转发、录屏、多设备同投（沿用 add-device-screencast 的 Non-Goals）。
- 跨平台原生库分发（当前宿主为 linux-x86_64；Windows/macOS 按需追加分类器）。

## Decisions

### D1. JavaCV FFmpeg 精确单平台依赖，不用 javacv-platform

**选择**：`org.bytedeco:ffmpeg:<ver>-<javacv ver>:linux-x86_64` + `org.bytedeco:javacpp` + `org.bytedeco:javacv`，约 100MB 原生库进 jar。

**理由**：`javacv-platform` 会拉全平台全模块（1-2GB），内部工具无跨平台分发需求；单分类器把体积控制在必要范围。将来支持其他宿主 OS 时按分类器追加。

**备选**：javacv-platform（体积不可接受，弃）；JNI 手写 FFmpeg 绑定（维护成本不可接受，弃）；保留 jcodec 只做档位（无法解决根本吞吐瓶颈，弃）。

### D2. 解码器实现为策略可换，接口形状保持

**选择**：抽出解码器接口 `ScreencastFrameDecoder { configure(payload); decode(payload): BufferedImage?; reset() }`，`FFmpegFrameDecoder` 为主实现，`H264FrameDecoder`（jcodec，现有类名保留）为回退实现；`ScrcpySession` 构造时注入。

**理由**：`ScrcpySession` 解码循环零改动；FFmpeg 原生库损坏/加载失败（UnsatisfiedLinkError）时自动降级 jcodec 低档位，投屏功能不致不可用。

流程对比：

```
jcodec 路径（现状，回退保留）:
  packet ─▶ Annex-B config 前置关键帧 ─▶ decodeFrameFromNals ─▶ YUV420平面前缀 ─▶ TYPE_3BYTE_BGR 打包 BGRA

FFmpeg 路径（新主路径）:
  packet ─▶ avcodec_send_packet ─▶ avcodec_receive_frame ─▶ sws_scale(YUV→BGRA) ─▶ 直出 BGRA
           （内建 Annex-B/参数集处理；无平面缓冲管理；无手写像素打包；
            色彩矩阵按流内 SPS/PPS 标记自动选择 BT.601/709）
```

**备选**：直接在 `ScrcpySession` 内替换解码调用（耦合 AVFrame 生命周期到会话，弃）。

### D3. 画质档位为启动参数三元组，切换 = 会话重启

**选择**：档位定义为 `(max_size, max_fps, video_bit_rate)`；`ScrcpyServerLauncher.serverCommand` 按档位参数化，原生档不传 `max_size`。运行中切换档位 = `stop()` → 以新档位 `start()`，UI 上表现为画面短暂清空后恢复（复用现有 Connecting 状态）。

**理由**：scrcpy 编码参数是启动期选项，3.1 的 `RESET_VIDEO` 控制消息只能重建编码器、不能改参数。重启会话实现简单、行为可预期（scid/socket 均随机新生成，无状态残留）。

**备选**：`RESET_VIDEO` + 重协商（scrcpy 3.1 不支持改参，弃）；每档常驻一条会话（资源翻倍，弃）。

### D4. 档位状态放在页面（会话级），不进全局设置

**选择**：`ScreencastPageController` 持有当前档位（`MutableStateFlow<ScreencastQuality>`，默认 HD），下拉框与会话控件同排；不持久化，每次进页面回到默认高清。

**理由**：档位是「本次投屏看什么」的临时选择，不是应用级偏好；页面级状态避免设置存储/同步的额外复杂度。若后续用户反馈需要记忆，再加持久化（小改动）。

**备选**：全局设置 + 页面记忆上次选择（引入设置存储依赖，暂不做）。

### D5. FFmpeg 解码线程模型

**选择**：`avcodec` 软解默认多线程（`thread_count` 交给 FFmpeg 自动决策，一般 = 核数）；解码仍跑在会话 `Dispatchers.IO` 协程，帧产出后沿用现有 `conflate` 合流到 UI。

**理由**：无需自管线程池；avcodec 内部并行是软解吞吐的主力。UI 合流逻辑与解码器无关，不变。


### D6. VA-API 硬解（本机实测结论）

**侦察结果**（任务 5.1，2026-10-27）：Intel iHD 驱动 24.1.0 硬解完全可用
（`VAProfileH264High: VAEntrypointVLD`）。两个宿主坑已解决：

1. **JavaCPP 自带 libva 抢载**：JavaCPP loader 解压的 libva.so.2（老版本）先于
   系统 libva 1.20 加载，与系统 iHD 驱动 ABI 不匹配 → `av_hwdevice_ctx_create`
   报 EIO(-5)。修复：`VaapiSupport.preloadSystemLibva()` 用 `System.load` 预载
   发行版 libva，动态链接器随后复用已加载的系统库。
2. **render 组**：`/dev/dri/renderD128` 为 `root:render 660`，用户须在 `render`
   组（`sudo usermod -aG render ts` + 重新登录）。

**三层解码链**（任务 5.2）：VAAPI 硬解（`FFmpegVaapiFrameDecoder`）→ FFmpeg 软解
（`FFmpegFrameDecoder`）→ jcodec（`H264FrameDecoder`）。硬解层以 `VaapiSupport.probe()`
运行时门控（探测 hwdevice 创建），装配层链式回退。真机 TB355FU（2560×1600）出图
验证通过，`[vaapi-decoder] VA-API decoder opened (pool=8)`。

**真机数据**（任务 6.2 用户验证）：2560×1600 原生档与 1600×1000 高清档出图正常，
滚动/动画流畅；三档运行中互切正常。

### D7. 零拷贝帧管线（任务 5.3）

解码器接口从 `BufferedImage` 改为 `DecodedByteFrame`（BGRA 字节，Skia N32 布局）：

- 三个解码器直出 BGRA bytes：VAAPI 路径 sws 一次转换 NV12→BGRA 后 bulk copy；
  软解路径同样；jcodec 路径 `Yuv420pToRgb` → 单次字节级 RGB→BGRA 重排。
- UI 消费端 `DecodedByteFrame.toComposeImageBitmap()` 用 Skia `installPixels`
  一次 memcpy，替代原先每帧两次全帧逐像素 Kotlin 循环（160 万像素 × 2 循环
  在 30fps 下 ≈ 1 亿次像素操作/秒的纯 CPU 开销）。
- 真机对比：改造前高清档明显卡顿，改造后流畅（用户验证 pass）。

**性能基准**（任务 5.4）：硬解路径已加 `[vaapi-decoder] perf: sws=…ms copy=…ms`
采样日志（每 100 帧），后续真机采样数据落在此处记录。软解基线由
`[ffmpeg-decoder] perf: sws=…ms pack=…ms total=…ms` 提供。

### D8. 帧率档位实测定案（60fps 实验）

设备 TB355FU 支持 120fps，但主机侧与设备侧的实测瓶颈决定了档位上限：

- **HD 1600×1000@60fps / 12Mbps**：响应快、滚动流畅（用户验证 pass）——
  相比最初 30fps 流畅度翻倍，成为高帧率档。
- **NATIVE 2560×1600@60fps / 30Mbps**：主机 CPU 仅 ~25%（解码端不饱和），
  但触控响应明显变慢——瓶颈在**设备端 H.264 编码器**：原生分辨率 60fps
  编码排队引入 20ms+ 端到端延迟。回退为 30fps / 20Mbps。
- **120fps 不采用**：桌面显示器垂直同步 ~60Hz，120fps 源只会被丢帧；
  USB2（480Mbps）带宽与设备编码负载均无收益。

**对象池（DecodedByteFrame 复用）暂不实施**：60fps 下主机 CPU 余量充足
（~25%），GC 压力未成为可观测瓶颈；引入跨帧引用会打破「消费者不得持有
帧引用」的接口约定，收益风险比不划算。留待 perf 采样显示 copy/GC 占比
显著时再评估。

### D9. 画质控件标注 fps + 头部单行布局（UI 打磨，并入本 change）

**背景**：画质下拉框本就出自本 change（任务 4.1），但其文案只显示档位
名（`画质：高清`），三档的帧率上限（10/60/30）藏在枚举 `maxFps` 里用户
看不到——而「流畅只有 10fps、远低于高清」正是三档核心取舍。同时头部
`ScreencastHeader` 单 `Row` 无权重，状态文本「正在投屏：{设备名}（{w}×{h}）」
无 `maxLines`/省略号，窗口窄时横向溢出。

**选择**：
- 画质控件把 `ScreencastQuality.maxFps` 暴露为 `· Nfps` 后缀——下拉每项
  `流畅 · 10fps` / `高清 · 60fps` / `原生 · 30fps`，按钮 `画质：高清 · 60fps`。
  纯展示，读枚举已有字段，零新数据源、零新数据流。
- 头部保持单行结构，仅微调：状态 `Text` 加 `weight(1f)` + `maxLines(1)` +
  `TextOverflow.Ellipsis`；剩余宽度把行尾控件顶右。Idle 态状态文本为空，
  `weight(1f)` 自然变成占位间隔，无需额外布局逻辑。（D12 后行尾控件仅剩
  「停止投屏」/「连接中…」，开始投屏入口已移至画面区状态卡片。）

**理由**：
- 选配置帧率（D9）而非实测帧率：`maxFps` 是档位定义的一部分，标注它让用户
  理解取舍；实测 fps 需在帧消费循环加时间戳窗口，属运行态指标，价值是
  「验证流是否真打满上限」，与「理解档位取舍」是两个问题，本次只做前者。
- 单行 + 状态吃权重而非拆双行/挪进画布浮层：本 change 已交付单行头部，
  最小改动达成目标；状态信息（设备名+分辨率）与控制同排符合现状心智模型。

**边界**：`Failed` 态下头部状态文本与下方 `ErrorMessage` 横幅会同时显示同一
失败消息——加省略号后头部截断，完整消息仍在横幅，可接受；若想更干净可让
`Failed` 态 `statusText` 退化为固定「投屏失败」文案，实现期定。

**不改**：`ScreencastPageController`、`ScreencastQuality` 枚举、会话状态机、
底部 `ControlBar`、`decoderFallbackMessage` 横幅、画布与坐标映射——纯
`ScreencastPage.kt` 头部两个 composable 的展示层改动。

### D10. 投屏页控件统一到 ui-components 体系（UI 美化重写）

**背景**：4a.1/4a.2 给画质下拉标了 fps、头部改了单行布局，但真机验证后发现
整体 UI 仍丑——根因不在 fps 标注或行布局，而在控件本身与全项目视觉体系脱节：
`ScreencastPage` 用 Material3 原生 `OutlinedButton` 当下拉触发器、手写
`Surface`+`Column`+`TextButton` 当弹出层（`QualityDropdown`/`DeviceDropdown`），
没有用 `DropdownMenu`，故无标准圆角/阴影、点外部不自动关闭、选中态只能用
`✓ ` 文字前缀模拟、菜单宽度靠魔法数。整页是一排平铺的 `OutlinedButton`，
无工具栏背景与统一紧凑高度。而项目 `ui-components` 已有成熟的
`DropdownSelector`/`SegmentedSelector`/`ProfilerCompactButton`/
`ProfilerCompactTextField`/`ProfilerToolbarStatus` + `ViewerColors`/
`ViewerTypography`，memory/network/layout 工具栏全部基于这套；且
`device-screencast:screencast-presentation` 已依赖 `libs.aps.ui.components`。

**选择**：把投屏页控件全部替换为 ui-components 共享组件——
- 设备/画质选择 → `DropdownSelector`（标准 `DropdownMenu`，圆角/阴影/点外
  关闭/选中态语义自动），画质 `itemLabel = "${label} · ${maxFps}fps"`；
- 头部/底部按钮 → `ProfilerCompactButton`（D12 后头部仅剩「停止投屏」/「连接中…」，
  「开始投屏」入口已移至画面区状态卡片，不再在头部）；
- 文本输入 → `ProfilerCompactTextField`；状态条 → `ProfilerToolbarStatus`；
- 头部/底部 `Row` 加 `ViewerColors.toolbar` 背景，padding 对齐 profiler 工具栏；
- `ConnectingHint`/`CenteredHint` 用 `ViewerColors.secondaryText` +
  `ViewerTypography.body`；删除手写 `QualityDropdown`/`DeviceDropdown`。

**错误提示取舍**（与用户确认）：会话失败原因走 `ProfilerToolbarStatus` 的
error 优先逻辑，**去掉** 原 `Failed` 横幅（不再重复）；解码器降级提示保留为一条
轻量 warning 横幅，改用 `ViewerColors.warning` 配色（原 `errorContainer` 与主题
脱节）。降级是 warning 而非 error（投屏仍可用，只是降档）。

**实施期既有技术债顺带修**：`ScreencastPageController` 的 `frameScope.launch`
块内 `% 100L`（日志采样）与 `stackTrace.take(5)`（stacktrace 摘要）是既有
magic number，detekt 无 baseline/豁免，阻塞 `:screencast-presentation:detekt`。
提为 companion 常量 `FRAME_LOG_INTERVAL_FRAMES`/`STACKTRACE_SUMMARY_LINES`。
`ScreencastHeader` 提取 `screencastStatusText` helper 解 `LongMethod`。

**理由**：复用共享组件 = 零新增依赖即对齐全项目视觉，且 `DropdownMenu` 的
弹出/关闭/选中态交由组件处理，消除手写下拉的全部粗糙点。比"继续打磨手写
下拉"代价更低、一致性更好。

**不改**：协议、会话、`ScreencastPageController` 逻辑（仅提常量）、画布与坐标
映射。文案保持硬编码中文，不引入 i18n（避免扩大范围）。

### D11. 画面区非运行态统一状态展示 + 冻结帧变形修复

**背景**：D10 修了工具栏视觉，但真机验证暴露画面区三个问题：
1. **停止/断开后画面变形**：`ScreencastCanvas` 用 `running?.width ?: 1` 取
   设备尺寸，会话离开 Running 后回退 1×1，`containViewport(1,1,画布W,画布H)`
   算出正方形视口，`drawImage` 把设备帧（如 1080×2400）压成变形方块。断开时
   `finish(Disconnected)` 不清 `_frame`，末帧残留并被压扁 + "已断开"文字叠在一起。
2. 连接中只有朴素 `CircularProgressIndicator`+文字，不显眼。
3. 断开只有单薄 `CenteredHint` 文字。

**选择**：
- **变形修复**：页面层 `remember { mutableStateOf<IntSize?>(null) }` 缓存最近一次
  Running 的 `width/height`，传给 `ScreencastCanvas` 作 `deviceSize`；`screenWidth/
  Height` 改为 `deviceSize?.width ?: 0`，无尺寸时不画（`screenWidth>0` 守卫已存在），
  杜绝 1×1 回退。controller 不动。
- **统一状态卡片**：新增 `StateCard`（图标符号或转圈 + 主文案 + 副文案，圆角
  `Surface` + `ViewerColors`/`ViewerTypography`）、`DimOverlay`（半透明变暗）、
  `StateTone`（NEUTRAL/ERROR/WARNING 驱动符号色）；`ScreencastStage` 编排 canvas +
  遮罩 + 卡片。空闲/连接中/断开/失败四态同一套卡片，仅符号/文案/tone 不同；连接中
  `loading=true` 显示转圈。
- **断开冻结末帧 + 变暗**（与用户确认）：不清断开态的末帧，按缓存尺寸正确比例
  渲染，叠 `DimOverlay(alpha=0.45f)` + "设备已断开"卡片——能看到断开瞬间屏幕内容
  又清晰感知中断，观感如视频通话断连。失败态同理。
- 删除旧 `ConnectingHint`/`CenteredHint`。

**理由**：缓存尺寸是最小且正确的变形修复（不引入 controller 状态/新数据流）；
统一卡片把四个零散的非运行态收敛成一致的呈现，与 D10 的"对齐全项目视觉"一脉相
承。符号用 Unicode（⚡/⚠/⊡）而非新增图标资源，零新增依赖。

**实施期重构**：`ScreencastPage` 因 canvas Box 逻辑膨胀超 detekt `LongMethod`，
拆出 `ScreencastStage` composable。

**不改**：协议、会话状态机、`ScreencastPageController` 逻辑、坐标映射、头部/底部
工具栏。文案硬编码中文。

### D12. 状态卡片作为开始/重试投屏入口（去掉头部开始按钮）

**背景**：D11 把空闲/连接中/断开/失败四态收敛成画面区中央的状态卡片后，头部
右侧仍保留「开始投屏」按钮，与中央卡片形成两个开始入口，冗余且视觉重心分散。
用户反馈：直接点中央卡片开始更自然。

**选择**（与用户确认）：
- 空闲/断开/失败三态卡片**整体** `clickable` 发起/重试投屏（断开=重新连接、
  失败=重试、空闲=开始投屏），连接中态卡片不可点。**不渲染独立按钮**——卡片本身
  即入口，副文案引导（「点击此处开始」/「点击此处重新连接」）+ action.label 作
  accent 引导文字表明可点；`clickable` 在桌面自带悬停手型反馈。
- 未选设备时卡片不可点、副文案提示「请先选择设备」。
- **删掉头部「开始投屏」按钮**，卡片成唯一开始入口；运行态头部仍保留「停止投屏」
  （停止是高频且需常驻，不适合塞回画面区）。

**实现**：新增 `StateCardAction(label, onClick, enabled)` data class 收敛 action
三参数（解 `LongParameterList`），`StateCard` 接收 `action: StateCardAction?`，
可点时 `Surface` 加 `Modifier.clickable` + 渲染 accent 引导文字（无按钮）；
`ScreencastStage` 新增 `onStart`/`canStart` 参数，按态构造 action 传入；
`ScreencastHeader` 移除 `onStart` 参数与「开始投屏」分支。`StateCard` 拆出
`StateCardSymbol` 子 composable 解 `LongMethod`。

**理由**：中央卡片是用户视觉焦点，做开始入口最顺；卡片本身可点比"卡片里再嵌一个
按钮"更简洁（少一层冗余控件，视觉更轻）。去掉头部按钮让头部回归"设备/画质/状态/
停止"控制职责。`StateCardAction` 让 action 参数从 3 个收敛为 1 个，可读性与
detekt 双赢。

**不改**：会话状态机、控制器、坐标映射、画布、降级横幅、底部 ControlBar。

## Risks / Trade-offs

- **[原生库分发 +100MB]** → 内部调试工具可接受；单分类器控制体积；按需扩展其他平台。
- **[JavaCPP 首次加载慢]** → 原生库首次解压到临时目录需数秒，表现为首次「开始投屏」到出画面稍慢；仅首次发生（同进程内后续复用）。
- **[FFmpeg 加载失败]**（宿主缺依赖、库损坏）→ 捕获 `UnsatisfiedLinkError`/加载异常，自动回退 jcodec 解码器 + 流畅档位，UI 提示"已降级"。
- **[高分辨率 CPU 占用]** → 原生档 2560×1600@30 软解占用可观（约数核）；档位说明中标注，用户可选低档平衡。
- **[档位切换闪断]** → 重启会话有 1-2 秒黑屏/连接态；可接受（非频繁操作）。
- **[VA-API 宿主依赖]** → 需要 render 组 + 发行版 libva 预载（见 D6）；不满足时自动回退软解，无需用户干预。
- **[jcodec 回退的兼容性坑]**（Annex-B/平面缓冲/打包）→ 均已在上一 change 解决并有测试，回退路径风险低。

## Migration Plan

- 纯增量：新依赖 + 新解码器类 + launcher 参数化；`H264FrameDecoder` 保留为回退。
- 回滚 = 移除 FFmpeg 依赖与档位 UI，恢复 launcher 硬编码参数（一次小 revert）。
- 无数据迁移。

## Open Questions

- JavaCV/FFmpeg 具体版本在实现时以 Maven Central 现存最新稳定版为准（javacv 1.5.x 系列）。
- 「原生」档在极端高分辨率设备（4K）下是否需要额外的 fps 限制保护，实现时按真机表现定。
