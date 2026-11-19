# Benchmark Regression MCP

[总览](README.md) | [Memory](memory.md)

## 当前状态
- 当前已实现 MCP 接口：**0**，Benchmark 模块没有注册 MCP 工具。
- 当前 `aps-mcp` 服务端只注册 `memory_*` 工具，不提供回归比较接口。
- 统一 `aps-mcp` provider/namespace 是架构方向，不代表 Benchmark 已完成模块注册。
- 本地 CLI、Gradle 编排、发布门槛和 AI 分析不等于 MCP 支持。

## 使用方式与 MCP 输入输出
- 目前没有可调用的 Benchmark MCP 接口，也没有本模块的 MCP 启动模式。
- MCP 输入：**未暴露**；基线、当前运行、策略和项目目录不是 MCP 参数。
- MCP 输出：**未暴露**；回归报告、门槛决策与导出工件不是 MCP 工具结果。
- 现有独立 CLI 不是 MCP server，不能把其选项当作工具 schema。

## 现有领域能力（非 MCP）
- `BenchmarkRun` 导入 AndroidX Benchmark JSON，保留用例、原始样本和设备/构建元数据。
- 运行身份来自输入 JSON 字节的 SHA-256；无有效用例的输入会被拒绝。
- `compare` 比较基线与当前运行，输出指标差异、可比性问题、原因和置信度。
- 分类使用中位数差异的双样本 BCa 区间，并结合明确的实际变化阈值。
- `evaluate_gate` 将不兼容、阻断回归、无法确定和通过区分为不同决策。
- 本地适配器提供存储、趋势、报告导出，以及 Macrobenchmark 命令构建与阻塞运行。

## 约束与证据边界
- 输入上限为 64 MiB、10,000 个用例；重复用例身份会被拒绝。
- 指标缺失、方向未知、原始样本过少或单位不匹配不能伪造成有效回归分数。
- 设备、场景、编译与启动条件影响队列可比性，仅有两个报告文件不足以证明可比。
- 无法确定或不兼容不是通过；门槛决策也不代表已发布 Release。
- Rust 与 Compose 的统计方法不同，不能承诺相同输入的分类逐位一致。
- Gradle 编排会执行本地测试项目，不能将其描述为只读查询或 MCP 入口。
- 本页仅核对源码，不声明 MCP、真实设备 Macrobenchmark 或发布 CI 验收通过。

## 代码依据
- [Benchmark API](../../../rust-desktop/crates/aps-benchmark/src/lib.rs)
- [导入与统计边界说明](../../../rust-desktop/crates/aps-benchmark/README.md)
- [回归比较与门槛](../../../rust-desktop/crates/aps-benchmark/src/compare.rs)
- [Macrobenchmark 编排](../../../rust-desktop/crates/aps-benchmark/src/orchestrate.rs)
- [当前 MCP 工具注册](../../../rust-desktop/crates/aps-memory/src/bin/aps_mcp.rs)
