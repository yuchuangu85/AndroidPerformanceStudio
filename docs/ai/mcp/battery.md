# Battery Profiler MCP

[总览](README.md) | [Memory](memory.md)

## 当前状态
- 当前已实现 MCP 接口：**0**，Battery 模块没有注册 MCP 工具。
- 当前 `aps-mcp` 服务端只注册 `memory_*` 工具，不提供电池采集或查询。
- 统一 `aps-mcp` provider/namespace 是架构方向，不代表 Battery 已完成模块注册。
- 本地报告、采集控制和 AI 分析不能视为 MCP 支持。

## 使用方式与 MCP 输入输出
- 目前没有可调用的 Battery MCP 接口，也没有本模块的 MCP 启动模式。
- MCP 输入：**未暴露**；快照、UID、目标设备和采集配置不是 MCP 参数。
- MCP 输出：**未暴露**；资源差异、能耗报告和采集工件不是 MCP 工具结果。
- 不存在通过本模块 MCP 重置统计、生成 bugreport 或控制轮询的用法。

## 现有领域能力（非 MCP）
- `parse_snapshot` 解析 BatteryStats checkin、UID 模型能耗及电池状态等独立证据。
- `BatterySnapshot` 保留 UID、设备与启动身份，以及计时器、网络计数器和警告。
- `BatteryDelta::between` 在身份与累计计数器连续性成立时计算资源差异。
- `capture_live` 使用显式提供的 ADB 客户端，支持有界的交互、定时、重复和低频在线模式。
- `BatteryReport` 提供本地 JSON / CSV 报告导出；采集工件保留最小化证据。
- `reset_batterystats` 与 `generate_bugreport` 是独立显式领域 API，不由快照隐式调用。

## 约束与证据边界
- UID、设备、启动或统计周期不连续时，不能合成零差异或负使用量。
- 共享 UID 不能证明某个包独占全部资源消耗，必须保留范围警告。
- 系统模型 mAh 不是硬件能量测量；电量变化也不能直接称为 UID 能耗。
- 电池传感器温度不是 CPU/GPU 温度，热状态不能证明应用级限频。
- 字节、包数和移动射频活跃时长分别建模，不得混加。
- 全局统计重置影响设备所有应用；bugreport 可能包含敏感设备与用户信息。
- 本页仅核对源码，不声明真实设备能耗、MCP 或完整原始证据验收通过。

## 代码依据
- [电池快照与差异 API](../../../rust-desktop/crates/aps-battery/src/lib.rs)
- [实时采集边界](../../../rust-desktop/crates/aps-battery/src/capture.rs)
- [电池模块说明](../../../rust-desktop/crates/aps-battery/README.md)
- [当前 MCP 工具注册](../../../rust-desktop/crates/aps-memory/src/bin/aps_mcp.rs)
