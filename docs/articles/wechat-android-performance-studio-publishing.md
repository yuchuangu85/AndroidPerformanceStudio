# 发布说明：公众号文章《Android Performance Studio：11 个性能工具，一个桌面应用》

正文文件：[wechat-android-performance-studio.md](./wechat-android-performance-studio.md)

## 1. 发布信息

**主推标题**

> Android Performance Studio：11 个性能工具，一个桌面应用

**备选标题**

1. Android Performance Studio：一个桌面应用，装下完整性能证据链
2. 11 个 Android 性能工具，一个窗口：Android Performance Studio 上手
3. 从 Layout Inspector 到 Perfetto：Android Performance Studio 做了什么
4. Android 性能排查，终于不用在十个窗口之间横跳了

> 名称拼写：官方名称是 **Android Performance Studio**（历史可执行文件名沿用 `Perfermance` 拼写，标题和正文不要写错）。

**摘要（公众号简介，不超过 120 字）**

> 从 Layout Inspector 到 Perfetto，从 HPROF 到 batterystats：采集、查看、关联、定位在一个桌面工作台完成。开源、跨平台、本地优先。

**备选摘要**

> 十一个性能模块共用一个设备上下文：捕获、查看、关联、验证，证据不再散落在十个工具和十个临时目录里。

**封面建议**

- 首选：`media/AndroidPerformanceStudio00.png`（主页）裁成 2.35:1，保留标题区和模块卡片。
- 备选：`media/AndroidPerformanceStudio02.png`（CPU Profiler），调用树和线程轨道的信息密度更适合做封面。
- 封面上保留应用名 **Android Performance Studio**（截图顶栏自带，或叠字），再配一行短标题；不要再加副标题，缩小到信息流尺寸后看不清。

## 2. 正文结构与字数

全文约 4000 字（正文 3400+ 汉字），按 8 节组织：

| 节 | 内容 | 作用 |
| --- | --- | --- |
| 一 | 性能排查的上下文割裂 | 开场共鸣 |
| 二 | 工作台定位 vs 工具合集 | 建立差异 |
| 三 | 十一个模块逐一展示（配 7 张模块图） | 主体 |
| 四 | 六步串联排查路径 | 把模块变成方法论 |
| 五 | 四个设计选择 | 观点，决定文章高度 |
| 六 | 工程侧事实 | 可信度 |
| 七 | 上手路径 | 转化 |
| 八 | 收尾与 CTA | 关注与 Star |

## 3. 图片清单

图片顺序与正文一致，全部来自 `media/`，正文用相对路径 `../../media/` 引用：

| 顺序 | 文件 | 内容 | 正文图注 |
| --- | --- | --- | --- |
| 1 | AndroidPerformanceStudio00.png | 主页 | 连接设备、快捷操作、分析模块和最近分析产物集中在一个入口 |
| 2 | AndroidPerformanceStudio01.png | Layout Inspector | 左侧层级树、中央截图与边界、右侧节点属性、底部 Findings |
| 3 | AndroidPerformanceStudio02.png | CPU Profiler | 线程时间线、正反向调用树、函数详情与采样质量指标 |
| 4 | AndroidPerformanceStudio03.png | Memory Profiler | 类直方图、实例列表、字段与引用，以及证据可用状态 |
| 5 | AndroidPerformanceStudio04.png | Frame Profiler | 帧时间线、deadline miss 聚类、单帧阶段拆分与布局预览 |
| 6 | AndroidPerformanceStudio05.png | Startup Profiler | Cold/Warm/Hot 实验、逐轮指标、编译与 Baseline Profile 证据 |
| 7 | AndroidPerformanceStudio06.png | Battery Profiler | 实验会话、电池快照、UID 资源归因与热状态 |
| 8 | AndroidPerformanceStudio07.png | GPU Inspector | 产物索引、SHA-256 完整性校验、图形上下文与外部工具能力检测 |

注意：第三节中是 7 张模块图（Layout、CPU、Memory、Frame、Startup、Battery、GPU），外加第二节的主页图，共 8 张。

正文里图片使用空 alt（`![](...)`），图注单独写成图片下方的斜体行。这样无论用哪种 Markdown 转公众号工具，都不会出现“alt 和图注重复两行”的问题；转换后请确认图注仍然是居中的小字。

## 4. 推荐发布流程

**方式 A：Markdown 导入（推荐）**

1. 用 [md2wechat](https://md2wechat.cn/) 或 [墨滴 mdnice](https://mdnice.com/) 导入 `docs/articles/wechat-android-performance-studio.md`。
2. 导入时保持文章在 `docs/articles/` 下的相对位置，工具才能把 `../../media/*.png` 上传到公众号素材库。
3. 在工具里选一套公众号主题，复制渲染结果，粘贴进公众号后台编辑器。
4. 粘贴后逐张点开图片，确认已上传为公众号图片而不是外链。

**方式 B：Typora 预览后再复制**

1. 用 Typora 打开 Markdown，确认 8 张图都能正常显示。
2. 导出为 HTML，在浏览器里全选复制到公众号后台。
3. 这种方法图片可能不会自动上传，需要手动替换一次。

**字体与排版建议**

- 正文字号 15–16px，行距 1.75，字色 #3f3f3f，两端留白。
- 二级标题用品牌色（建议取应用界面的蓝绿色，如 #2f6f6a 或 #3b82a0）。
- 图片宽度铺满内容区，图注 13px、灰色居中。
- 代码符号（如 `NetworkProfiler.eventListenerFactory`）保留行内代码底色，公众号编辑器支持。

## 5. 发布前检查清单

- [ ] **截图脱敏**：CPU Profiler 与 GPU Inspector 截图的底部或路径区包含本机用户名路径（`/Users/...`），GPU 截图还包含设备型号和 Trace 路径。如需匿名，发布前先裁剪或遮挡底部 20px。
- [ ] 主页截图右上角显示设备序列号（TCL T702W…），介意的话可以裁掉顶栏右侧。
- [ ] 标题里的“11 个”与正文一致：主页正好有 11 个模块卡片，不要改成“10 个”。
- [ ] 文中所有数字都来自截图（样本数、P50/P95、TTID、UID、温度等），发布前再对一遍。
- [ ] “Unavailable”“未验证”“Unsupported” 等表述不要在排版时被删掉，它们是文章的可信度来源。
- [ ] 结尾 GitHub 链接、中文用户指南链接可访问。
- [ ] 手机端预览：图片是否被裁切、表格（如有）是否溢出、代码符号是否换行难看。

## 6. 可选调整

- **短版（约 2500 字）**：保留第一、二、四节，第三节只留 Layout、CPU、Frame、Startup 四个模块，第五、七、八节压缩成一段。适合非 Android 泛技术号。
- **长版（约 8000 字）**：第三节每个模块补一段真实排查案例（例如“某次启动从 2.1s 降到 1.6s 的过程”），需要额外素材，当前版本没有虚构案例。
- **系列化**：可以拆成《布局与帧》《CPU 与 Trace》《内存与启动》《电量与 GPU》四篇，每篇 1–2 个模块 + 设计原则。
