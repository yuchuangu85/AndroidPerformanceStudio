# P0 百万记录 / SQLite / Canvas 选型 PoC

- 执行日期：2026-07-13；Firefox flame graph 复测日期随 JSON `generatedAt` 更新。
- 目标：验证 WBS-008 与 Firefox flame graph parity 的技术方向，记录百万记录导入耗时、峰值内存、投影取消和交互帧准备表现。
- 原始结果：`docs/poc-results/p0-performance-macos-arm64.json`
- Firefox flame graph 结果：`docs/poc-results/firefox-flame-graph-macos-arm64.json`

## 1. 复现命令

```bash
./gradlew :test-fixtures:runP0PerformancePoc
./gradlew :test-fixtures:runFlameGraphPerformancePoc
```

任务固定使用 `-Xms256m -Xmx1g`，每次覆盖当前平台对应的 JSON 报告。测试数据通过 Kotlin `Sequence` 逐条生成，不在内存中预先持有 100 万条记录。

## 2. 首轮结果

执行环境：macOS arm64、JDK 21.0.10、18 个可用处理器。

| 指标 | 结果 | P0 临时门槛 | 结论 |
|---|---:|---:|---|
| SQLite 导入 100 万记录 | 7541.260 ms | < 10 s | 通过 |
| 导入吞吐 | 138095 records/s | 仅记录 | 已记录 |
| 峰值堆增量 | 160926928 bytes（约 153.47 MiB） | < 512 MiB | 通过 |
| SQLite 文件大小 | 74149888 bytes（约 70.71 MiB） | 仅记录 | 已记录 |
| `COUNT(*)` | 16.995 ms | < 1 s | 通过 |
| Top 20 symbols | 669.926 ms | < 1 s | 通过 |
| Timeline 100 万记录索引构建 | 17.333 ms | < 1 s | 通过 |
| Timeline 帧准备 P95 / Max | 0.174 / 0.191 ms | P95 < 16.67 ms | 通过 |
| FlameGraph 10 万节点帧准备 P95 / Max | 0.389 / 2.733 ms | P95 < 16.67 ms | 通过 |

Firefox flame graph 复测额外记录：`visibleNodeCount`、`scrollFrameP95Milliseconds`、`hoverReprojectionCount`、`selectionReprojectionCount` 和 `cancellationOutcome`。门禁要求 hover/selection 不重新投影，取消语义为 latest-generation-wins。

临时门槛用于 P0 选型淘汰，不替代 P6 在三平台 clean VM、真实 protobuf/符号分布和真实 UI 输入下的发布指标。

## 3. 实现约束

1. SQLite 使用规范化 schema v1、共享 Frame/Callsite、xerial sqlite-jdbc、WAL、`PreparedStatement`、实体缓存和有界批次；输入为懒序列。
2. Timeline 先构建固定大小密度索引，再按 viewport 投影到有限像素列；`TimelineCanvas` 不接收原始 sample。
3. FlameGraph 投影按 viewport 裁剪，边缘吸附到 2 device-pixel 倍数并保留 Firefox 的 0.8 device-pixel 右侧缝隙；吸附后宽度非正的节点不绘制，避免为 10 万节点逐个创建 UI 组件。
4. FlameGraph hover、选择和滚动只更新 viewport/selection/details 状态，不重新执行 SQLite flame projection。
5. 帧统计是交给 Compose Canvas 前的 CPU 侧 render model 准备耗时，不包含 GPU 光栅化、显示合成或操作系统输入分发。

## 4. 后续门禁

- 在 Windows、Linux clean runner 上运行同一任务并保留独立 JSON。
- 接入 Simpleperf protobuf 流式解析后，用真实 Record 替换合成规范化记录复测。
- P4 UI 集成时记录实际 Canvas 绘制、键盘、鼠标滚轮和上下文菜单操作的端到端输入延迟和掉帧。
- P6 用真实百万 sample 会话复测数据库大小、导入事务恢复和查询计划。
