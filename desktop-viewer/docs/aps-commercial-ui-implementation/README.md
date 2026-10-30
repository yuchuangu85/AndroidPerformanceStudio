# Android Performance Studio 商业化 UI 规格

本目录保留共享交付约束、13 份仍有未实现或未验收需求的页面规格及配套图片，不作为当前完成清单。页面中的目标布局、建议模型和能力描述需结合源码、测试与验收证据核验。

## 共用规范

- 根 [DESIGN.md](../../../DESIGN.md) 是当前交互、主题、密度与窗口行为的权威；页面规格或历史参考图与其冲突时，以当前设计为准。
- [共用基础与交付规范](00-shared-foundation-and-delivery.md)：模块职责、证据/能力边界、共享交互、fixture 和交付验收条件。
- 资料读取和执行约定遵循仓库 `AGENTS.md`；本目录不再维护重复输入清单或按日期累积的实施日志。

## 功能页

1. [Home Dashboard](01-home-dashboard.md)
2. [Layout Inspector](02-layout-inspector.md)
3. [CPU Profiler](03-cpu-profiler.md)
4. [Trace Analyzer](04-trace-analyzer.md)
5. [Memory Profiler](05-memory-profiler.md)
6. [Frame Profiler](06-frame-profiler.md)
7. [Startup Profiler](07-startup-profiler.md)
8. [Battery & Network](08-battery-network.md)
9. [GPU Analysis Hub](09-gpu-analysis-hub.md)
10. [Benchmark Regression](10-benchmark-regression.md)
11. [Reports Center](11-reports-center.md)
12. [Settings](12-settings.md)
13. [Source Workspaces](13-source-workspaces.md)

## 使用约定

- 所有页面遵守共享证据边界和模块职责，不把未来规格或设计图当成已支持能力，不为匹配截图伪造指标、归因、置信度或测试数据。
- 页面中保留的旧布局和“当前落地”日期是局部记录，不覆盖根设计；尤其 Source Workspaces 已是一级导航，旧版 Shell 顶栏、常驻全局状态栏及其固定占位不再适用。
- 实施时读取对应页面、参考图、feature 源码/测试/ADR 及可用的真实或脱敏 fixture；图片仅作视觉参考，合成 fixture 不代表真机证据。

## 待验收

以下承接历史记录中未关闭的验收要求，统一跟踪见[仓库待办](../../../docs/TODO.md#原生界面与交付验收)。本次文档清理不关闭这些门禁；历史并行编译错误和临时日志不作为当前阻塞。

- **原生窗口与视觉**：在精确匹配当前构建的打包应用中导入归档，验证 Hierarchy/Canvas/Details/Findings 联动、显隐、分隔条拖拽、实际 1280x720 窗口缩放、首选宽度恢复、导航折叠、菜单/快捷键及深浅色、英中语言、display scale。按当前 `DESIGN.md` 核验 Layout、Frame、GPU、Startup 等页面视觉与交互；离屏截图、源码 wiring 和 app image 构建不等于原生 GUI 验收，其余页面也未因局部通过而完成整套复核。
- **跨平台与发布**：分别验证 macOS/Windows/Linux 打包和原生交互，尤其 macOS 标题栏选择器的焦点/下拉与 Windows JBR 的 DPI、最大化/还原、缩放、拖拽、Snap Layouts 和回退行为。签名、公证、安装升级、迁移及 Release 验证独立于 UI 与构建通过。
- **大数据真实交互**：10k 节点需实际导入、滚动、选择联动和缩放；至少 10,001 帧需实际选择、范围拖动和缩放，记录交互延迟与重组。现有合成归档、边界/导航测试不证明响应性能。
- **真机闭环**：仍需真实设备的 Layout 采集/归档、Frame 采集、Startup 实验和编译状态变更，以及设备图形上下文读取；本地 fixture、导入报告和截图不替代采集验收。
- **AGI 集成**：真实安装环境下验证发现/启动、制品 open/reveal/relocate 和校验恢复。GPU counter、shader、render pass、draw call、overdraw 等无稳定 parser/data source 的面板继续关闭，不把外部工具入口当成本地分析能力。
- **Startup 对比与 Profile 边界**：现有对比仅为当前/上一次实验，不是任意多构建/多设备列表；非 TTID 指标仅作描述性比较，不能冒充独立回归判定。Profile artifact/hash 是声明来源，不证明设备安装或内容验证；compiler state、Profile provenance、置信度和环境兼容性需分别核验，不补造版本、竞品结果或优化诊断。

## 素材与 Fixture 缺口

- 13 份页面规格中 12 份已链接对应视觉参考；Source Workspaces 缺少专属设计图，不用其他页面图片补齐。
- Reports 与 Settings 共用 [`reports-settings-v2-overview.png`](images/reports-settings-v2-overview.png) 和 [`reports-settings-v2-spec-board.png`](images/reports-settings-v2-spec-board.png)；[`reference-original.png`](images/reference-original.png) 是旧版组合素材，仅保留供追溯。
- [共享 fixture 清单](00-shared-foundation-and-delivery.md#fixture-与视觉素材) 覆盖 Layout、CPU、Trace、Memory、Frame、Startup、Battery、Network、GPU、Benchmark，仍需核实材料可用性；Home、Reports Center、Settings、Source Workspaces 未定义专属 fixture，缺口不视为已覆盖。
