# Android Performance Studio：11 个性能工具，一个桌面应用

> **Android Performance Studio** 是一个开源、跨平台的 Android 性能分析工作台：从 Layout Inspector 到 Perfetto，从 HPROF 到 batterystats，采集、查看、关联、定位在一个窗口里完成。本地优先。

## 一、性能问题最难的地方，不是修，是找

“首页滑动有点卡”“启动要两秒多”“内存又涨了 200MB”。

这三句话，背后往往是三套完全不同的工具链。

滑动卡顿，你要打开 Android Studio Profiler 抓 CPU，或者用 Perfetto 抓系统 trace，盯着 RenderThread 和 SurfaceFlinger 看；内存涨了，你要导 HPROF，翻类直方图、支配树和引用链；启动慢，你要跑 Macrobenchmark，看 TTID、TTFD 和 Baseline Profile；耗电异常，你要导 batterystats，再去 Battery Historian 里对 Wakelock 和 Alarm；再加上 simpleperf 看 Native 热点、AGI 看 GPU、OkHttp 抓网络……

每一个工具都能给你一段真相。但没有一个地方，把这几段真相拼在一起。

更消耗人的是重复动作：换一个工具，就要重新选设备、重新选进程、重新对齐时间轴。分析完一轮，截图在桌面、trace 在 Downloads、HPROF 在某个临时目录，过两周再回来看，连当时抓的是哪个版本、哪台机器都说不清。

Android Performance Studio 想解决的，就是这条断掉的“证据链”。

## 二、它不是又一个 Profiler，而是一个工作台

![](../../media/AndroidPerformanceStudio00.png)

*主页：连接设备、快捷操作、分析模块和最近分析产物集中在一个入口。*

打开应用，首页同时回答三个问题：现在连着哪台设备、最近分析过哪些产物、接下来能做什么。设备与进程选择是全局上下文，在任何一个模块里切换，其他模块看到的都是同一套上下文。

这是它和“把十一个工具各做成一个 Tab”最大的区别：**共享上下文，但不共享数据模型。**

每个分析器仍然是独立的 included build，有自己的采集路径、解析模型、证据边界和测试。Perfetto 的 trace 不会被硬塞进 HPROF 的内存模型，启动实验也不会复用帧分析的数据结构。共享的是入口、设备、进程、时间基准、产物管理和导航——而不是把所有证据揉成一个巨大的 JSON。

技术上，它是一个 Compose Desktop（Kotlin）应用：macOS、Windows、Linux 都有原生安装包，自带 Java 运行时和准备好的分析器资源；支持中英文、明暗主题、跟随系统与显示缩放；在线靠 ADB 采集，离线可以直接分析导入的产物。

安装包自带运行时，意味着用安装包不需要 Git、JDK、Node、Python，也不需要 Android SDK/NDK 的构建工具链——只有设备采集时才需要 Platform Tools 里的 adb。

## 三、十一个模块，逐一摆上台面

下面每一张截图，都是一次真实采集或查看时的界面（演示设备为 Android 15 / API 35）。先看全局，再逐个模块；图片信息密度不低，手机上建议点开大图或横屏查看。

### 1. Layout Inspector：先把“层级”看清楚

![](../../media/AndroidPerformanceStudio01.png)

*Layout Inspector：左侧层级树、中央截图与边界、右侧节点属性、底部 Findings。*

左边是 View 层级树，中间是设备截图与 bounds overlay，右边是节点属性，底部是 Findings 和时间线。选中树上的某个节点，画布上同步高亮它的边界；反过来在画布上点一下，树也会定位到对应节点。

截图里这次采集有 **167 个节点、最大深度 21**，超过阈值 10，Findings 直接给出一条 “Deep hierarchy” 警告；时间线还记录了一组快照之间的节点变化：**+167 / -73**。

层级深不一定是问题，但当一个页面里堆了二十多层 FrameLayout 和 wrapper，测量、布局、绘制三段的成本都会肉眼可见地涨上来。先把它看见，才谈得上收拾。

需要说清楚的是 Compose 检查的边界：完整运行时 Compose 树需要经过校验的检查运行时、Android API 29+ 上的可调试进程、以及精确匹配的 Compose Inspector；View 或 Semantics 回退是另一种证据，不能当作完整的 Composable 树。工具会在界面上把降级原因标出来。

### 2. CPU Profiler：找热点，但别只看火焰图

![](../../media/AndroidPerformanceStudio02.png)

*CPU Profiler：线程时间线、正反向调用树、函数详情与采样质量指标。*

这次采集是 **439 个样本、11 条线程、2.65 秒，有效采样率 166Hz，丢失样本 0**。先看时间轴上的线程轨道：RenderThread、mali-compiler、binder、mali-mem-pur 各自在什么时候干活，一眼能对上。

调用树支持正向和反向，选中从 `__start_thread` 到 `DrawFrameTask::postAndWait` 的这条链路，可以看到下层大量时间落在 libGLES_mali.so 的 Skia / GL 调用里；右侧会同步给出 Total、Self、Samples、Threads 和调用路径。

有意思的是顶部热点函数：`<unknown-symbol>`，占比 30.3%。**符号没还原，本身就是一条结论**——先补 debug info 或者换采集方式，再谈优化，否则很容易对着一个空名字“优化”半天。

### 3. Memory Profiler：把“内存涨了”拆成可复核的证据

![](../../media/AndroidPerformanceStudio03.png)

*Memory Profiler：类直方图、实例列表、字段与引用，以及证据可用状态。*

这次堆里是 **27,109 个类、492,862 个实例、shallow 20.2MB**。类列表里 byte[]、String、Object[]、HashMap$Node 依次排开，每一行都能下钻到实例、字段和引用。

更值得看的是 EmojiCompat 这个例子：这两个实例的 shallow size 加起来只有 20B，但 retained size 是 470.3KB。只盯 shallow size，会漏掉真正把内存攥在手里的持有关系。

支配树、引用链、快照差异都在；按采集路径不同，还能看 Native Heap、Bitmap 转储和 ART 分配采样。

还有一个容易被忽略的细节：Native Size 和 Retained 在汇总里显示的是 **Unavailable**。读不到就是读不到，工具不替你猜。

### 4. Frame Profiler：卡顿要能落到具体一帧

![](../../media/AndroidPerformanceStudio04.png)

*Frame Profiler：帧时间线、deadline miss 聚类、单帧阶段拆分与布局预览。*

这次 132 帧：**P50 7.17ms、P95 15.89ms、最差 66.79ms，deadline miss 率 2.4%**。时间线把全部帧按“好帧 / 高延迟 / 自身 Jank / SurfaceFlinger 导致 / 丢帧”分色，顶部标出 miss 聚类。

三次 miss 被拆成 SEVERE 81.51ms、MAJOR 56.28ms、MINOR 30.30ms 三档。点进最严重的那一帧，能看到它 66.79ms、预算 15.67ms、错过 4 次 vsync、活动类型是 Animation，阶段拆分里 Input 占了 3.62ms。

这一帧到底是输入慢、动画慢还是绘制慢，Frame Profiler 给的是可继续往下查的入口——比如直接 “Open Layout” 跳到这一帧对应的布局证据，用关联而不是猜测去定位。

页面底部也很坦诚：FrameMetrics Agent 不可用，回退到 gfxinfo 轮询；平台 Jank 信号拿不到时显示 “—”。deadline miss 和平台 Jank 是两种信号，不能混着讲。

### 5. Startup Profiler：把“启动慢”变成可重复的实验

![](../../media/AndroidPerformanceStudio05.png)

*Startup Profiler：Cold/Warm/Hot 实验、逐轮指标、编译与 Baseline Profile 证据。*

5 轮 Cold 启动：**TTID 中位数 2087ms，P95 2205ms，单轮在 1815ms 到 2205ms 之间，MAD 118ms**。单看一个数字没有意义，五轮放在一起，才看得出这个页面的启动到底稳不稳定。

同一屏上还并排放着 TTFD、First Frame、P95。这次 TTFD 和 First Frame 显示不可用，右侧的证据说明写得很直白：应用没有上报 Fully Drawn；Startup Agent 不可用时回退到平台计时。Baseline Profile 的状态是“未验证”，编译模式是 run-from-apk——这些状态都如实标了出来，反而让结论更可信。

启动不该是一个数，而应该是一组可重复的实验：请求模式、观测模式、每轮记录、waterfall、CSV / JSON 导出，缺一不可。

### 6. Battery Profiler：电量问题要按 UID 归因

![](../../media/AndroidPerformanceStudio06.png)

*Battery Profiler：实验会话、电池快照、UID 资源归因与热状态。*

这次按 UID 10277 做归因：11 秒实验，Wakelock 0、Wakeup Alarm 0、网络 0B，峰值温度 53.25°C。Wakelock、Alarm、Job、Sensor、Energy、Network、History 分栏排列，点一行就能看到对应证据。

Battery 面板里有两个容易被混为一谈的东西：**电池快照是某个时刻的采样**，**History 是另一条独立时间轴**。工具把它们分开展示，而不是假装可以互相插值。

能量的可用性也有等级（这次是 ENERGY_ENHANCED），不是每台设备、每个版本都能给出同样细的字段。结果可以导出，也可以转成传统 Battery Historian 的输入。

### 7. GPU Inspector：老实当一个入口

![](../../media/AndroidPerformanceStudio07.png)

*GPU Inspector：产物索引、SHA-256 完整性校验、图形上下文与外部工具能力检测。*

一个索引产物、SHA-256 校验通过、60.7 MiB；设备信息里 Mali-G57 MC2、驱动 v1.r38p1、OpenGL ES / EGL / Vulkan 后端都列了出来。

右边 AGI 能力区写着：版本未知、Unsupported，原因是没找到 AGI 的可执行文件。**没装就写没装，不假装支持。**

校验通过的 trace 可以一键交给 Perfetto 继续分析；配置好 AGI 之后，也能交给 AGI。GPU Inspector 自己不是完整的 GPU Profiler，它是索引、校验和跳转的入口——这个定位它写在了界面上，也写在了文档里。

### 8. 还有四个模块，这次没有截图

- **Trace Analyzer**：采集或导入 Perfetto trace，用 trace_processor 做查询和诊断，保留最近会话、导出原始 trace，也能打开准备好的 Perfetto UI。
- **Network Profiler**：分析配置了 `NetworkProfiler.eventListenerFactory` 的 OkHttp 采集数据或导入的 HAR，看请求活动、阶段耗时、失败、域名和连接/TLS 证据。它不是整机抓包，WebView、Cronet、URLConnection 和 Native Socket 不在范围内。
- **Benchmark Regression**：导入 AndroidX Benchmark JSON，选已保存结果作为基线和当前版本，在可比性与统计约束下比较并导出报告；可选的 Macrobenchmark 编排复用已有测试工程。
- **Source Workspaces**：只读注册 Local、GitHub、AOSP 源码，管理不可变快照、索引和构建证据，把受支持的性能证据解析成可检查的源码定位候选。

## 四、把它们串起来：一次滑动卡顿的排查路径

如果只用这套工具去查一次“首页滑动卡顿”，我会按这个顺序走，而不是一上来就抓 Perfetto：

1. **先用 Frame Profiler 定范围。** 抓一段 10 秒左右的帧数据，看 P50、P95 和最差帧，确认 miss 是偶发还是成片，以及聚类落在哪些帧上。
2. **对着最差的一帧看阶段拆分。** Input、Animation、Layout、Draw、Sync 哪一段占大头，决定了下一步往哪走：Draw 重就去看 GPU 和 Skia，Input 重就去看手势处理和主线程排队。
3. **在 Trace Analyzer 里对齐同一时间窗。** 同一台设备、同一段采集，直接看 RenderThread、SurfaceFlinger、binder 的调度；不用再抓一次、再对一次时间轴。
4. **需要 Native 热点时，再用 CPU Profiler 补 simpleperf。** 如果热点还是 `<unknown-symbol>`，先补符号，别急着改代码。
5. **回 Layout Inspector 验证结构。** 看这一帧对应的页面是不是层级过深、有没有重复测量；如果是 Compose，就确认完整检查是否可用、有没有回退。
6. **改完之后回到同一个工作台做回归。** 启动、帧耗时、Benchmark 都用同一台设备、同一套产物记录，结论才可比较。

这条路不一定每次都能走通——设备不支持 FrameTimeline、采集 Agent 不可用、符号缺失，都会让某一步降级。但降级本身也是结论：你知道自己现在站在哪块证据上，而不是在十一个工具之间凭感觉跳。

## 五、四个我坚持的设计选择

如果只把这当成一份功能清单，就低估它了。下面四条，才是它和“十一个工具的合集”最不一样的地方。

**第一，证据分层。** 原始产物、解析模型、界面展示和最终结论，是四种不同的东西。一条 trace 能被漂亮地画出来，不代表能把某个函数判定为根因；一张截图能说明布局深，不代表能证明它导致了卡顿。文档里反复强调的“可展示不等于可证明因果”，就是这条线。

**第二，缺失就显示缺失。** TTFD 没有 Fully Drawn 事件，就显示 Unavailable；Native Size 读不到，就写读不到；AGI 版本未知，就写未知。把 0 填进去，或者用一个估算值冒充测量值，是对分析者最坏的欺骗——因为你会在一个假数字上做真决策。

**第三，关联不是因果。** 跨工具跳转、时间轴重叠、UID 归因，这些是排查线索，不是判决书。Frame 里某一帧卡了、同一时间某个 SDK 在跑，只能说“值得一起看”，不能说“就是它干的”。

**第四，本地优先，AI 显式触发。** 默认的采集和分析都在本机完成。AI 辅助分析需要你主动触发，并且只发送你选中的证据；源码片段还需要工作区授权。注册一个源码工作区，不等于把整个工程上传。

## 六、一些工程侧的事实

- 技术栈是 Compose Desktop + Kotlin，按功能拆成多个 included build，各自有测试任务。
- 上游资源固定版本：Perfetto UI、Firefox Profiler、trace_processor 都按固定版本准备，不跟最新的“可能可用”。
- 发布矩阵覆盖十个安装包：macOS arm64 / x64 的 DMG、PKG，Windows x64 的 MSI、EXE，Linux x64 / arm64 的 DEB、RPM。
- 安装包自带 JRE 和分析器资源；用安装包起步，不需要 Git、JDK、Node、Yarn、Python 或 Android SDK/NDK 构建工具链。
- 项目还在持续迭代（0.7.x），已知的能力缺口会写在文档里，而不是消失在宣传语里。

## 七、怎么开始

1. 到 [GitHub Releases](https://github.com/yuchuangu85/AndroidPerformanceStudio/releases) 下载对应系统和架构的安装包。
2. 需要在线采集的话，准备好 Android SDK Platform Tools，打开 USB 调试并在设备上授权 adb。
3. 打开 **Settings**，配置 Android SDK 位置、语言、主题和显示缩放；源码和 AI 设置按需配置。
4. 首页直接 **Start Capture**，或者用 **Import Trace / Analyze HPROF** 分析离线产物。
5. 更细的流程看[中文用户指南](https://github.com/yuchuangu85/AndroidPerformanceStudio/blob/main/docs-user-zh/README.md)。

**它适合谁**：需要把多种证据串起来看的 Android 性能、稳定性工程师；要啃复杂页面、启动、内存、功耗和回归分析的团队。

**它暂时不适合谁**：想替代 IDE 断点调试的人；需要整机网络抓包的人；需要完整 GPU 深度分析的人——那件事仍然应该交给 AGI、RenderDoc 这类专业工具。

## 八、写在最后

做这个工具的过程中，我越来越确信一件事：性能排查里最贵的不是工具本身，而是**把证据重新找回来、重新对齐、重新解释一遍的成本**。

Android Performance Studio 未必能替你下结论，但它至少想把证据摆齐、把边界说清、把上下文留住。

性能优化没有银弹，但可以让证据不再流浪。

项目是开源的：[github.com/yuchuangu85/AndroidPerformanceStudio](https://github.com/yuchuangu85/AndroidPerformanceStudio)。如果它对你有用，欢迎 Star；发现 bug 或者想要某个模块，也欢迎开 Issue。

如果这篇文章对你有帮助，点个“赞”和“在看”，也欢迎转给正在查性能问题的同事。

---

*文中截图来自实际采集与查看过程，受设备、系统版本、目标应用和证据来源影响，部分字段会显示为不可用——这正是工具想表达的状态。*
