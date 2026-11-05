# Tasks: upgrade-screencast-decoder

## 1. 依赖与档位模型

- [x] 1.1 版本目录新增 bytedeco/javacv 依赖（`javacpp`、`ffmpeg` 带 `linux-x86_64` 分类器、`javacv`），screencast-core 引入；确认本地 Maven 拉取成功
- [x] 1.2 `screencast-core` 新增 `ScreencastQuality` 档位模型（FLUENT/HD/NATIVE 及其 `maxSize`/`maxFps`/`videoBitRate` 参数，NATIVE 的 maxSize 为空），含单元测试（档位到启动参数片段的映射）

## 2. 解码器抽象与 FFmpeg 实现

- [x] 2.1 抽出 `ScreencastFrameDecoder` 接口（`configure`/`decode`/`reset`），现有 `H264FrameDecoder` 实现该接口（行为不变），`ScrcpySession` 改为构造注入解码器
- [x] 2.2 实现 `FFmpegFrameDecoder`：avcodec H.264 软解（自动多线程）+ swscale YUV→BGRA 输出 `BufferedImage`；config packet 与帧 packet 复用现有会话循环语义
- [x] 2.3 FFmpeg 原生库加载失败回退：装配层捕获加载错误降级 `H264FrameDecoder`，暴露「解码后端类型」供 UI 提示
- [ ] 2.4 解码器单元测试：FFmpeg 路径用构造的 Annex-B H.264 流（或录制样本）断言出帧；回退路径断言异常时返回 jcodec 实现

## 3. 启动参数与会话接线

- [x] 3.1 `ScrcpyServerLauncher` 按档位参数化 `serverCommand`（NATIVE 档省略 `max_size`）；现有测试补充档位参数断言
- [x] 3.2 `ScrcpySession`/`ScreencastDependencies` 传递档位：构造工厂接受 `ScreencastQuality`，切换档位 = stop + 新参数 start（复用幂等 start 与清理路径）

## 4. UI 档位选择

- [x] 4.1 头部工具栏新增画质档位下拉框（流畅/高清/原生，默认高清），会话运行时可点；切换即触发会话重启，期间呈现连接中状态
- [x] 4.2 降级提示：解码后端回退 jcodec 时在状态栏提示「已降级为兼容解码」；双语文案

## 4a. 档位控件 UI 打磨（并入本 change）

- [x] 4a.1 `QualityDropdown` 下拉项与按钮文案标注 `max_fps`：按钮 `画质：高清 · 60fps`，候选项 `✓ 高清 · 60fps` / `流畅 · 10fps` / `原生 · 30fps`；下拉 `Surface` 宽度由 160dp 调至容纳「原生 · 30fps」不截断
- [x] 4a.2 `ScreencastHeader` 单行布局收尾：状态 `Text` 加 `weight(1f)` + `maxLines(1)` + `TextOverflow.Ellipsis`；主操作（开始/停止）按钮固定行尾；窗口收窄时整体不溢出
- [x] 4a.3 窄窗口目测：Idle（状态文本空，weight 自然占位把按钮顶右）/ Running（长设备名 + 分辨率省略）/ Failed（头部截断 + 下方横幅完整消息并存，可接受）三态无溢出
- [x] 4a.4 投屏页控件改为 ui-components 共享组件，与全项目视觉体系一致：删除手写 `QualityDropdown`/`DeviceDropdown`（`OutlinedButton`+`Surface`+`Column`），改用 `DropdownSelector`（标准 `DropdownMenu`：圆角/阴影/点外关闭/选中态语义）；头部/底部按钮改用 `ProfilerCompactButton`、文本输入 `ProfilerCompactTextField`、状态条 `ProfilerToolbarStatus`；头部/底部 `Row` 加 `ViewerColors.toolbar` 背景，提示组件用 `ViewerColors`/`ViewerTypography`；`Failed` 原因并入状态条（error 优先）去掉失败横幅，解码器降级提示保留为 `ViewerColors.warning` 轻量横幅
- [x] 4a.5 detekt/编译验证：`LongMethod`（`ScreencastHeader` 提取 `screencastStatusText` helper）；顺带修 `ScreencastPageController` 既有 magic number（`100L`/`5` 提常量）；`:screencast-presentation:detekt`+`compileKotlin`、`:screencast-app:compileKotlin`、`:screencast-presentation:test` 全绿
- [x] 4a.6 修复停止/断开后画面变形：`ScreencastCanvas` 的 `screenWidth/Height` 从 `running?.width ?: 1`（非 Running 回退 1×1 → containViewport 算出正方形 → 末帧压扁）改为传入缓存的 `deviceSize`（页面层 `remember` 记录最近一次 Running 的 width/height），无尺寸时不画，杜绝 1×1 变形
- [x] 4a.7 画面区非运行态统一状态展示：新增 `ScreencastStage`（canvas + 变暗遮罩 + 状态卡片）与 `StateCard`/`DimOverlay`/`StateTone`；空闲/连接中/断开/失败 四态同一套居中卡片（图标符号或转圈 + 主文案 + 副文案），连接中卡片内放 loading 圈；断开/失败态冻结末帧按正确比例 + `DimOverlay` 半透明变暗 + 卡片；删除旧 `ConnectingHint`/`CenteredHint`；`ScreencastPage` 拆出 `ScreencastStage` 解 `LongMethod`
- [ ] 4a.8 运行目测：连接中状态卡片（转圈+文案）/ 停止切空闲无变形残帧 / 断开冻结末帧变暗+卡片 / 失败变暗+原因卡片 / 窄窗口卡片居中
- [x] 4a.9 状态卡片本身作为开始/重试投屏入口（无独立按钮）：空闲/断开/失败三态卡片整体 `clickable`（断开=重新连接、失败=重试、空闲=开始投屏），副文案作引导（「点击此处开始」/「点击此处重新连接」），action.label 以 accent 引导文字呈现而非按钮；未选设备时不可点且副文案提示「请先选择设备」；删掉头部右侧「开始投屏」按钮，卡片成唯一开始入口（运行中头部仍保留「停止投屏」）；连接中卡片不可点
- [ ] 4a.10 运行目测：空闲卡片点击开始投屏 / 未选设备时不可点 + 副文案提示 / 断开卡片点击重新连接 / 失败卡片点击重试 / 头部无「开始投屏」按钮 / 悬停手型反馈

## 5. 硬解与零拷贝（极致性能）

- [x] 5.1 VAAPI 可用性侦察：运行时探测 bytedeco FFmpeg 构建的 h264 硬解（av_hwdevice_ctx_create VA-API），记录可用性结果
- [x] 5.2 VA-API 硬解路径：hwdevice ctx + hwframes ctx，解码输出 GPU 帧，失败自动回退软解（三层链：VAAPI → FFmpeg 软解 → jcodec）
- [x] 5.3 零拷贝渲染：跳过 AWT BufferedImage——硬解读回直接产出 BGRA 字节 → Skia Bitmap installPixels；ScreencastFrameDecoder 接口适配
- [x] 5.4 性能基准：HD 档单帧端到端耗时对比（软解基线 vs 硬解），记录到 design.md

## 6. 验证

- [x] 6.1 全模块 `gradlew test` 通过（screencast-core 含新解码器测试）
- [x] 6.2 真机验证：「高清」档默认出图、文字清晰、滚动/动画流畅；「原生」档 TB355FU 2560×1600 下帧率与 CPU 占用记录在案；运行中三档互切正常
- [ ] 6.3 回退验证：模拟 FFmpeg 加载失败（如临时移除原生库路径）确认 jcodec 降级可用并有提示
- [ ] 6.4 清理回归：档位切换（重启会话）与停止后 `adb reverse --list` 清空、无设备端残留进程
