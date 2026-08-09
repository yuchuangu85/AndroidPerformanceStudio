---
status: superseded by ADR-0007
---

# ADR-0004: 抽跨模块共享的 DeviceSession 设备层

抽一个跨 profiler 共享的 **DeviceSession** 薄层，统一负责：探测设备 API level、拉取/推送文件、启动 perfetto/simpleperf/`am profile` 等捕获命令、拉回产物。捕获类功能（实时指标、native/java 分配、方法录制）都通过它连设备，而不是各模块自持 ADB 代码。

**Why**：Q5 定下模拟器/root 优先后，多个捕获功能需要同一套"连设备 → 跑命令 → 拉产物"逻辑；不抽层则每加一个捕获功能都要重写一遍设备交互。这是 Phase C/D 两个全新捕获功能的地基。

**Consequences**：已有模块（如 memory-profiler 的 ADB dumpheap、native 捕获）需要逐步迁移到该层，迁移按 Phase 推进，不一次性重构。
