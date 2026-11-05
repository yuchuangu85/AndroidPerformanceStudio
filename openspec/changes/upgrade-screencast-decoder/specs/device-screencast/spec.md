# device-screencast Delta

## ADDED Requirements

### Requirement: 投屏画质档位可选且运行中可切换

投屏页面 SHALL 提供画质档位选择（流畅 / 高清 / 原生），由用户手动选择，默认档位为「高清」。档位 SHALL 映射为 scrcpy 服务器启动参数（分辨率上限 `max_size`、帧率上限 `max_fps`、码率上限 `video_bit_rate`）；「原生」档 SHALL 不限制分辨率（使用设备原始尺寸）。会话运行中切换档位时，应用 SHALL 以新档位参数停止并重新建立会话，UI SHALL 呈现连接中状态直至新会话恢复出图；档位 SHALL 支持在会话运行时选择（不因会话运行而禁用）。

#### Scenario: 默认档位为高清
- **WHEN** 用户进入投屏页面
- **THEN** 画质档位显示为「高清」

#### Scenario: 按档位参数建立会话
- **WHEN** 用户选择某一档位并通过画面区状态卡片发起投屏
- **THEN** 设备端服务器按该档位的 max_size/max_fps/video_bit_rate 参数启动，画面清晰度与档位预期一致

#### Scenario: 运行中切换档位
- **WHEN** 会话运行中用户切换画质档位
- **THEN** 当前会话被终止并以新档位参数重新建立，期间呈现连接中状态，恢复后按新档位渲染

#### Scenario: 原生档不限制分辨率
- **WHEN** 用户选择「原生」档并开始投屏
- **THEN** 会话不传 max_size 参数，视频流为设备原始分辨率

#### Scenario: 档位选择控件标注配置帧率上限
- **WHEN** 用户展开画质档位下拉框
- **THEN** 每个候选项显示其 `max_fps`（如「流畅 · 10fps」「高清 · 60fps」「原生 · 30fps」），且当前选中项的画质按钮上也显示该 fps

#### Scenario: 头部工具栏单行不溢出
- **WHEN** 投屏页面窗口宽度收窄
- **THEN** 头部控件保持单行排列不横向溢出；会话状态文本以单行省略号截断吸收剩余宽度，行尾控件（运行态为「停止投屏」按钮）固定在行尾

### Requirement: 投屏界面控件与全项目视觉体系一致

投屏页面的设备选择、画质选择、会话控制、导航键与文本输入控件 SHALL 使用 `ui-components` 共享组件（`DropdownSelector`/`ProfilerCompactButton`/`ProfilerCompactTextField`/`ProfilerToolbarStatus`）与 `ViewerColors`/`ViewerTypography` 主题，与其它 profiler 模块工具栏视觉一致，不得使用手写的下拉弹出层（非 `DropdownMenu` 的 `Surface`+`Column`）或与全局风格脱节的原生控件堆叠。下拉选择 SHALL 采用标准 `DropdownMenu`（圆角、阴影、点外部自动关闭、选中态语义高亮）。会话失败原因 SHALL 经状态条 error 优先呈现，不再以独立失败横幅重复；解码器降级提示 SHALL 以与主题一致的轻量 warning 横幅呈现。

#### Scenario: 控件使用共享组件与主题
- **WHEN** 用户打开投屏页面
- **THEN** 头部与底部工具栏背景、按钮高度与紧凑样式、下拉选择器样式与其它 profiler 模块一致，均取自 `ViewerColors`/`ViewerTypography`

#### Scenario: 下拉为标准弹出菜单
- **WHEN** 用户展开画质或设备下拉
- **THEN** 弹出层为标准 `DropdownMenu`（圆角阴影、点外部关闭），当前选中项有语义高亮而非纯文字前缀

#### Scenario: 失败原因进状态条不重复横幅
- **WHEN** 会话进入失败状态
- **THEN** 失败原因在头部状态条以 error 样式呈现，不再单独渲染失败横幅；解码器降级时另行以 warning 横幅提示

### Requirement: 画面区非运行态有统一状态展示且冻结帧不变形

投屏画面区在非运行态（空闲/连接中/已断开/失败）SHALL 呈现统一的状态卡片（图标或加载指示 + 主文案 + 可选副文案），状态文案由会话状态机驱动。停止或断开会话时，画面区 SHALL NOT 以变形方式渲染残留帧：当设备尺寸未知时 SHALL 不绘制帧，已知时 SHALL 按设备屏幕宽高比 contain 渲染。已断开与失败态 SHALL 在冻结的末帧之上叠加半透明变暗遮罩与状态卡片，让用户清晰感知会话已中断且能看到断开瞬间的屏幕内容。

#### Scenario: 空闲态展示引导卡片
- **WHEN** 会话处于空闲态
- **THEN** 画面区显示引导状态卡片，副文案引导点击此处开始投屏（未选设备时提示先选择设备），无残留帧

#### Scenario: 连接中展示加载卡片
- **WHEN** 用户通过画面区状态卡片发起投屏进入连接中态
- **THEN** 画面区显示带加载指示的状态卡片与连接文案，不再仅是朴素转圈

#### Scenario: 断开时冻结末帧不变形并变暗
- **WHEN** 会话因设备断开进入已断开态
- **THEN** 末帧按设备屏幕宽高比 contain 渲染（不被压成变形方块），其上叠加半透明变暗遮罩与「设备已断开」状态卡片

#### Scenario: 停止后无变形残帧
- **WHEN** 用户点击停止投屏
- **THEN** 画面区切到空闲状态卡片，不出现因尺寸回退导致的变形残帧闪现

#### Scenario: 失败态展示原因卡片
- **WHEN** 会话进入失败态
- **THEN** 画面区在变暗遮罩之上显示失败状态卡片，副文案含失败原因

#### Scenario: 状态卡片为开始/重试投屏入口
- **WHEN** 会话处于空闲态且已选择设备
- **THEN** 空闲状态卡片整体可点击，点击即开始投屏；卡片不渲染独立按钮，副文案引导「点击此处开始」，action 引导文字以强调色呈现

#### Scenario: 未选设备时入口禁用
- **WHEN** 会话处于空闲态但未选择设备
- **THEN** 空闲状态卡片不可点击，副文案提示先选择设备，点击不触发开始

#### Scenario: 断开与失败卡片可重试
- **WHEN** 会话处于已断开或失败态且设备仍可选
- **THEN** 状态卡片整体可点击，副文案引导「点击此处重新连接」，点击以当前设备重新开始投屏

#### Scenario: 头部不再有开始投屏按钮
- **WHEN** 会话处于非运行态
- **THEN** 头部工具栏不渲染「开始投屏」按钮；开始投屏的唯一入口为画面区状态卡片；运行态头部仍保留「停止投屏」按钮

### Requirement: FFmpeg 为主解码器且加载失败时回退

应用 SHALL 使用 JavaCV FFmpeg（avcodec 软解 + swscale 色彩转换）作为主 H.264 解码器，在原生分辨率下维持流畅解码；FFmpeg 原生库加载失败时，应用 SHALL 自动回退到内置 jcodec 解码器（配合「流畅」档位参数）并在 UI 提示已降级，投屏功能不因解码后端失败而不可用。

#### Scenario: FFmpeg 正常解码
- **WHEN** FFmpeg 原生库加载成功
- **THEN** 视频帧由 FFmpeg 解码并转换输出，原生分辨率下帧率显著高于降载水平

#### Scenario: FFmpeg 加载失败自动降级
- **WHEN** FFmpeg 原生库加载抛出链接错误或初始化异常
- **THEN** 会话改用 jcodec 解码器并以「流畅」档位参数运行，UI 显示降级提示，投屏仍可用


### Requirement: VA-API 硬件解码三层链与自动回退

应用 SHALL 在宿主机具备 VA-API 硬件解码条件时优先使用硬件解码（avcodec 绑定 VAAPI hwdevice/hwframes 上下文），不支持时自动回退至 FFmpeg 软解，再回退至 jcodec。硬件路径 SHALL 以运行时探测（`av_hwdevice_ctx_create` 成功）门控，而非仅依赖原生库加载成功——因设备节点权限或驱动初始化失败在首包解码时才暴露。探测 SHALL 仅执行一次（进程级缓存）。

#### Scenario: 硬件解码可用时启用
- **WHEN** 宿主机可访问 `/dev/dri/renderD128` 且发行版 libva 驱动初始化成功
- **THEN** 解码器以 VA-API 硬解路径打开（日志 `VA-API decoder opened`），原生分辨率下解码端 CPU 占用显著低于软解

#### Scenario: 硬解不可用时逐级回退
- **WHEN** VA-API 探测失败（无 render 组权限、驱动缺失或加载错误）
- **THEN** 解码器回退至 FFmpeg 软解；FFmpeg 软解亦不可用时回退至 jcodec；每级回退均不中断投屏功能

#### Scenario: JavaCPP 自带 libva 与系统驱动冲突的预载
- **WHEN** JavaCPP 解压的 libva 与系统 iHD 驱动 ABI 不匹配导致 hwdevice 创建失败
- **THEN** 应用在探测前以 `System.load` 预载发行版 libva，使动态链接器复用系统库，规避抢载导致的 EIO

### Requirement: 零拷贝帧管线

解码器接口 SHALL 以 BGRA 字节（Skia N32 布局）而非 AWT `BufferedImage` 作为帧产出，消除「解码器逐像素打包 INT_RGB → UI 逐像素拆回 BGRA」的双循环。解码路径 SHALL 经一次 swscale/转换直出 BGRA，UI 消费端 SHALL 以 Skia `installPixels` 一次性拷贝上屏，不得在帧管线中存在逐像素 Kotlin 循环。

#### Scenario: 帧管线无逐像素循环
- **WHEN** 任一解码后端产出帧并提交渲染
- **THEN** 解码器到画布的路径中不存在逐像素 Kotlin 循环，仅 swscale 转换与一次 memcpy

#### Scenario: 高清高帧率下流畅
- **WHEN** 用户在「高清」档位（1600×1000@60fps）下触发滚动或动画
- **THEN** 投屏画面流畅跟随，主机解码端 CPU 占用未饱和

### Requirement: 帧率档位上限按设备编码能力定档

「高清」档 SHALL 限定 60fps / 12Mbps（1600 上限分辨率），「原生」档 SHALL 限定 30fps / 20Mbps（不限制分辨率），「流畅」档 SHALL 限定 10fps / 2Mbps（1280 上限）。原生档不得提升至 60fps：实测设备端 H.264 编码器在原生分辨率 60fps 下排队引入 20ms+ 端到端延迟，触控响应明显劣化。应用 SHALL NOT 提供 120fps 档（桌面显示器垂直同步 ~60Hz，更高源帧率无观感收益）。

#### Scenario: 高清档 60fps 流畅且响应快
- **WHEN** 用户在「高清」档投屏并操作设备
- **THEN** 画面以 60fps 上限流畅呈现，触控到画面反馈的端到端延迟可接受

#### Scenario: 原生档保持 30fps 以避免编码排队
- **WHEN** 用户在「原生」档投屏
- **THEN** 服务器以 30fps 上限编码，设备端编码器不因高帧率排队而劣化触控响应

#### Scenario: 不提供超出显示器刷新率的档位
- **WHEN** 用户查看可用画质档位
- **THEN** 最高档帧率不超过 60fps，不存在 120fps 选项

### Requirement: 会话清理不可被竞态跳过

会话停止/重启（含档位切换触发的 stop+start）时，reverse 隧道移除与设备端 server jar 删除 SHALL 在不可取消的协程上下文中执行（`NonCancellable`），即使清理与下一会话启动竞态，也 MUST NOT 跳过 `adb reverse --remove`。档位切换后 `adb reverse --list` SHALL 无残留隧道；正常停止路径下设备端 SHALL 无残留 scrcpy 进程。

#### Scenario: 档位切换不残留隧道
- **WHEN** 会话运行中用户切换档位（触发 stop + 立即 start）
- **THEN** 旧会话的 reverse 隧道被移除，`adb reverse --list` 仅保留当前活跃会话的隧道

#### Scenario: 清理不被竞态取消
- **WHEN** stop 与新会话 start 紧接执行
- **THEN** 清理协程在 NonCancellable 上下文中完成 adb 反向隧道移除，不因并发被跳过

## MODIFIED Requirements

### Requirement: 投屏会话建立遵循 server 分发、reverse 隧道与协议握手流程

启动投屏时，应用 SHALL 按以下顺序建立会话：将随应用分发的 `scrcpy-server` jar 推送到设备 `/data/local/tmp`、建立 `adb reverse` 隧道（宿主机随机本地端口）、通过 `adb shell app_process` 以当前所选画质档位的编码参数启动设备端 server、接受 server 反连并完成协议握手（读取设备名与视频编解码信息）。任一步骤失败时，应用 SHALL 报告明确的失败原因并回滚已建立的资源（隧道、已推送文件、shell 进程）。

#### Scenario: 成功建立会话并显示画面
- **WHEN** 用户对一台已授权 adb 的设备点击「开始投屏」
- **THEN** 应用完成推送、隧道、启动、握手全流程，投屏画布开始实时显示设备画面

#### Scenario: 握手失败时回滚并报错
- **WHEN** 设备端 server 启动失败或反连超时
- **THEN** 应用 SHALL 显示失败原因（如"设备端 server 启动失败"），reverse 隧道被移除，不留下残留端口占用

#### Scenario: 设备未授权或离线
- **WHEN** 所选设备 adb 状态为 unauthorized/offline，或设备已断开
- **THEN** 应用 SHALL 显示设备不可用的明确提示，不开始投屏

#### Scenario: 服务器命令按档位参数化
- **WHEN** 应用构造设备端服务器启动命令
- **THEN** 编码参数（max_size/max_fps/video_bit_rate）取自当前所选画质档位，而非固定值

### Requirement: 投屏画面实时解码渲染

应用 SHALL 持续读取视频流、解码 H.264 帧，并按到达顺序渲染到画布；画布 SHALL 保持设备屏幕宽高比，窗口尺寸变化时按比例缩放（默认 contain 模式，不裁切不拉伸）。解码后端 SHALL 保证在所选档位的编码产出下持续跟上消费（FFmpeg 主路径；jcodec 回退路径配合「流畅」档位）。解码落后时应用 SHALL 只渲染最新帧（合流），解码失败或流中断时，应用 SHALL 停止渲染并呈现会话状态（错误或已断开），不得展示过期画面并暗示其为实时画面。

#### Scenario: 实时画面按宽高比显示
- **WHEN** 投屏会话处于运行状态且视频帧持续到达
- **THEN** 画布按设备屏幕宽高比缩放显示最新帧，无裁切无拉伸

#### Scenario: 高清档位下动画流畅
- **WHEN** 用户在「高清」档位下于设备端触发滚动或动画
- **THEN** 投屏画面以不低于档位帧率上限的可感知流畅度跟随，无长时间冻结

#### Scenario: 流中断时停止渲染
- **WHEN** 视频 socket EOF 或解码器报错
- **THEN** 会话进入「已断开」或「失败」状态，画布停止更新，UI 提示会话已中断并可重试
