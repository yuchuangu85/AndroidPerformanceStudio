---
status: superseded by ADR-0006
---

# ADR-0003: 保持模块化多 Profiler 架构，暂缓统一时间线聚合层

产品层保持**模块化**：每项 Profiler 功能一个模块 + 统一桌面入口，暂缓合成 AS 式带统一时间线的单体 Profiler 界面。设备支持边界：模拟器 + 可 root 设备为完整子集，生产设备为降级子集。界面先做到**功能对等**，不做 AS 逐像素模仿（火焰图对 CPU、分配图对内存等少数高价值处例外）。

**Why**：模块化架构已被现有代码锁定（simpleperf-viewer、perfetto-viewer、memory-profiler 等均为独立模块），重排成本高；统一时间线依赖 8 项分析能力齐备之后才做得出来，属于最后阶段的目标而非当前约束。界面保真不应超前于分析能力。

**Consequences**：最终阶段以"统一 Session 数据模型"承载全部数据源（见 CONTEXT.md 的 *Session* 词条）。**当前不为它建抽象**——各功能保持独立产物，等 8 项分析能力齐备后再以统一 Session 聚合，避免投机设计。
