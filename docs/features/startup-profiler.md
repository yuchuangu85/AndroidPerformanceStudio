# Startup Profiler

## 功能定位

Startup Profiler 分析 Cold、Warm、Hot 和重复启动实验，保留每轮请求模式、实际观测模式、TTID/TTFD 可用性、阶段证据、编译/profile 状态和异常原因。它也支持导入/导出 Startup CSV/JSON，并通过 CLI/MCP 对已有报告做只读分析。

当前状态：

- **已接入**：启动报告导入、运行列表、TTID/TTFD 可用性、阶段/统计、CSV/JSON 导出、CLI/MCP 文件分析，以及 Rust 页面中的启动实验编排。
- **条件能力**：设备、包名、组件、用户/UID、Android 版本、Perfetto、采样权限和目标 App 行为决定结果完整性。
- **未完成验收**：Cold/Warm/Hot 设备矩阵、完整基线/profile 工作流、停止/取消、Open Trace 交接和所有 milestone 的真机证据仍开放。

## 功能点

- Cold：force-stop 后启动，记录新一轮进程和启动事件。
- Warm/Hot：执行受支持的预热/返回 Home 语义，准备动作不计入正式测量。
- 多轮 iteration、cohort 统计、运行筛选、异常轮次和缺失字段。
- TTID（首次显示）和 TTFD（`reportFullyDrawn`）分开保存；`thisTime`/`totalTime`/`waitTime` 不替代它们。
- 可选 CPU 热点：围绕启动动作录制最长约 12 秒 Perfetto，保留调度、wakeup、FrameTimeline、主线程样本和包名限定调用栈。
- 阶段与 ART attribution：GC、JIT/dex2oat、class loading、verification/initialization 分开统计。
- baseline/current 比较：统计差异、阶段差异、置信区间和 Regression/Improvement/NoChange/Inconclusive/Insufficient/Incompatible 状态。
- CLI：`aps analyze startup.json --kind startup`；MCP 绑定 Startup CSV/JSON 后支持摘要和运行筛选，不启动设备实验。

## 原理与数据流

```text
device + package + requested mode
  -> target validation / force-stop / warm-up
  -> am start + startup events + optional Perfetto
  -> milestone parser and phase model
  -> cohort statistics / comparison / CPU evidence
  -> GPUI page, CSV/JSON, CLI/MCP
```

当前 Rust 入口：

- `rust-desktop/crates/aps-app/src/import/startup`：捕获、模型、报告、CPU evidence 和页面状态。
- `rust-desktop/crates/aps-startup`：启动事件、阶段、统计和比较模型。
- `rust-desktop/crates/aps-cli`：Startup 文件分析和结构化输出。

实验前验证设备、包名、进程、安装身份、用户、UID、版本和组件；启动后再核对目标进程实例。真实 milestone 必须来自设备事件/trace 的同一时间基础，不能用主机时钟或 Activity Manager 总耗时补齐。

## 使用方法

### 设备实验

1. 选择设备、包名、启动模式、重复次数和编译/profile 配置。
2. 先确认目标身份、用户/UID、包版本和设备状态。
3. 运行实验，等待每轮的启动、首帧和可用阶段证据完成。
4. 查看每轮结果，再看 cohort p50/p90、阶段分布和异常轮次。
5. 开启 CPU 热点时检查 trace、符号、调度覆盖和样本限制。
6. 导出 CSV/JSON；同条件下保留另一组作为 baseline 做比较。

### 文件分析

```sh
aps analyze startup.json --kind startup
aps analyze startup.json --scope startup-runs   --requested-mode cold --observed-mode unknown --missing-metric ttid
```

CLI/MCP 只读已有报告，不会连接设备、启动应用、重建日志或执行新实验。

## 证据边界与限制

- 首帧、fully drawn、Displayed 和用户可交互不是同一个 milestone；缺失时保持 unknown。
- 单次启动不能形成稳定性结论；应保留每轮原始事件、模式和丢失状态。
- CPU sample 是采样窗口证据，不是方法调用次数或逐方法耗时；没有目标样本不等于线程没有运行。
- Baseline Profile/编译模式与指标相关，不单独证明代码修改因果。
- Startup Perfetto 与 milestone 的重叠保留时钟误差、覆盖率和窗口边界，不自动归因到 Binder、GC 或某行代码。

## 故障排查

- TTID/TTFD 缺失：检查目标是否真正启动、事件日志/trace 是否覆盖、App 是否调用 `reportFullyDrawn`。
- Warm/Hot 与 Cold 混淆：查看 requested/observed mode；准备过程不能被当作正式启动时间。
- CPU 热点为空：检查 Android 11+、Perfetto 数据源、采样权限、trace 是否在启动前就绪和符号匹配。
- 比较为 Incompatible：核对设备、构建、包版本、编译模式、profile、场景和 metric 单位。
- 真实设备、profile 生成、Open Trace、停止/取消和 Release 验收参考功能对等审计。
