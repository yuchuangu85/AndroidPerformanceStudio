# Battery Profiler

## 功能作用

Battery Profiler 是一个 Android 电池与能耗分析工具，核心功能包括：

- **电池数据采集**：通过 ADB 执行 `dumpsys batterystats` 获取设备电池统计信息
- **多种采集模式**：
  - **Interactive（交互式）**：手动开始/停止，适合在手动操作 App 场景下采集
  - **Timed（定时）**：设定固定时长，自动开始和停止
  - **Repeated（重复）**：多次自动重复采集，用于统计稳定性
  - **Online（在线轮询）**：低频轮询（5-60 秒间隔），实时监控电量变化
- **能耗分析**：`BatteryAnalyzer` 解析 batterystats 数据并计算：
  - **UID 级别**：按 App 聚合资源使用，支持 Package/UID/SharedUID/Device 四种归因范围
  - **Wakelock 分析**：统计各 Wakelock 的持有次数和时长
  - **Alarm 分析**：统计 Alarm 触发次数和类型
  - **Job 分析**：统计 JobScheduler 任务的执行情况
  - **Sensor 分析**：统计传感器使用次数和时长
  - **Network 分析**：统计移动网络/WiFi/蓝牙的收发字节和包数
  - **Energy 估算**：基于硬件计数器或系统模型的能耗估算（mAh 或 µWs）
- **Battery Historian 集成**：支持生成 Battery Historian 兼容的 bugreport，用于深入分析电量事件时间线
- **电池状态采集**：记录设备状态（电量百分比、温度、电压、充电状态）
- **Baseline 对比**：支持配对实验的前后对比
- **结果持久化**：通过 `SqliteBatterySessionStore` 保存实验数据

## 实现原理

### 采集流程

1. **设备选择 → 目标枚举**：通过 ADB 列出可归因 UID 的应用包（`cmd package list packages -U`）
2. **基线采集**：
   - 普通实验不重置全局 batterystats；重置仅作为带警告的独立高级操作
   - 采集 baseline 快照（`dumpsys batterystats --checkin` + `dumpsys batterystats`）
3. **实验执行**：
   - 可选自动启动目标 App（`am start`）
   - 根据模式执行采集：Interactive 等待用户停止 / Timed 定时停止 / Repeated 多次循环
4. **最终采集**：获取最终快照 + 历史事件数据
5. **解析**：`BatteryStatsParser` 解析 checkin 格式的 batterystats 数据，提取 UID 级别的资源使用统计

### 数据分析

- `BatteryAnalyzer` 计算 baseline 和 final 之间的差值（Delta）：
  - **Wakelock Delta**：持续时间差值、计数差值
  - **Alarm Delta**：触发次数差值
  - **Network Delta**：收发字节差值
  - **Energy Delta**：能耗估算差值
- 多次运行（Repeated 模式）时的统计分析（min/max/median/mean/P90/P95/stddev/MAD）

### Battery Historian 集成

- `BatteryHistorianAdapter` 通过 ADB 执行 `bugreportz` 生成包含 batterystats history 的 bugreport
- 用户可将生成的 bugreport 导入 Google Battery Historian 进行可视化分析

### 数据结构

- **BatteryExperimentConfig**：实验配置（mode、durationSeconds、pollingIntervalSeconds、measuredRuns、launchApp、cooldownSeconds）
- **BatterySnapshot**：单个采集快照，包含 uidStats、deviceState、conditions、history、rawEvidence
- **BatteryRun**：单次实验运行，包含 baseline（基线快照）、samples（中间采样）、finalSnapshot（最终快照）
- **BatteryRunDelta**：计算后的差值数据，包含 wakelocks、alarms、jobs、sensors、network、energy
- **EnergyEstimate**：能耗估算，包含 component、energyMah/energyUws、source（HARDWARE_COUNTER/SYSTEM_MODEL 等）、confidence

### 数据流

```
[Android Device] --ADB--> [dumpsys batterystats] --> [BatteryStatsParser]
    --> [BatterySnapshot[]] --> [BatteryAnalyzer] --> [BatteryRunDelta[]]
    --> [SqliteBatterySessionStore] (持久化)
    --> [Compose UI: BatteryProfilerScreen]

[ADB bugreportz] --> [BatteryHistorianAdapter] --> [bugreport.zip] (for Battery Historian)
```

### Export

- **JSON**：完整分析结果导出
- **CSV**：Delta 数据表格导出
- **Raw Evidence Bundle**：原始 batterystats 数据打包导出

### 注意事项

- batterystats 是全局计数器，需要 reset 或 baseline 对比才能获得单 App 的增量数据
- Energy 估算依赖设备硬件支持，部分设备可能只提供 RESOURCE_BASIC 级别数据
- Battery Historian bugreport 包含隐私敏感信息（账号、SSID、应用列表等）

## 优化评审与落地结果

本轮评审采用以下兼容约束：JSON 保持 `schemaVersion=1` 且新增字段必须可选；CSV 表头不变；Raw Bundle 保留既有路径并只允许新增文件；SQLite 只增量加列/加表；现有枚举值和操作入口不删除或改名。详见 desktop-viewer 的 ADR-0005。

### 1. 全局 reset 与设备状态修改【部分采纳】

普通实验统一使用 baseline → final 差值，不会自动执行 `batterystats --reset`、模拟 unplug、修改亮度、网络或 Doze。现有“重置统计”保留为独立高级操作，并继续显示不可撤销的全局副作用确认。未增加 `resetPolicy`；模拟电池状态不能替代物理断开 USB 供电。

### 2. Online 轮询观察者效应【已落地】

Online 中间样本只执行 `dumpsys battery`，记录电量、温度、电压和供电状态。完整 checkin/report/history 只在起止 Battery Snapshot 采集，UID 资源结果仍由 baseline/final 差值产生。未增加空转自校准或 `batteryproperties` 分支。

### 3. Energy 精度与归因【部分采纳】

`SYSTEM_MODEL` 始终标记为 `MODELED`；整机电流、Perfetto `android.power` 或 power rail 数据只能属于 `DEVICE` 范围，不能冒充目标 UID 实测。当前链路继续使用设备 OEM batterystats 模型，不支持上传 `power_profile.xml`，也不读取权限和设备语义不稳定的 sysfs 电流节点。Perfetto 能量采集如需接入，应作为独立能力实现。

### 4. Battery Historian【保留兼容入口】

完整 bugreport 导出保留并标记为 **Legacy**，继续在落盘前提示账号、SSID、应用列表和设备标识等隐私风险。单独 history 不是 Battery Historian 输入的兼容替代；未实现无法保证覆盖完整 bugreport 内容的文本脱敏器。

### 5. 外部实验条件【已落地】

baseline/final 以只读方式记录屏幕亮度、自动亮度模式、屏幕状态、Doze deep/light 状态和默认网络传输类型；条件变化会产生数据质量告警。Raw Bundle 可新增 `conditions.txt`，既有文件路径不变。不记录瞬时 CPU/GPU 频率或完整 telephony dump。

### 6. Repeated 样本独立性【已落地】

`BatteryExperimentConfig.cooldownSeconds` 默认 30 秒、可设为 0，仅在 Repeated 的相邻轮次间等待。若某轮 baseline 温度相对首轮漂移至少 3°C，结果产生告警；不会无限等待温度恢复。本轮复用现有逐轮表格和统计，不增加盒须图或时序图。

### 7. 会话中断与增量持久化【已落地】

SQLite 会话状态为 `RUNNING` / `COMPLETED` / `INTERRUPTED`。实验开始写入 `RUNNING`，每完成一轮立即保存该轮，正常结束写入 `COMPLETED`；取消、失败或下次启动发现遗留运行态时写入 `INTERRUPTED`。已完成轮次保留，未完成轮次不续跑，后续测量创建新实验。旧数据库通过增量加列迁移。

### 8. WakeLock / Alarm / Job 归因【修正后采纳】

结果统一表述为 UID-attributed resource，不从名称推断组件所有权。Shared UID 会明确产生包级归因歧义告警；框架代理名称只提示“UID 归因不能证明组件所有权”。仅当原始记录明确提供 source package 时才可采用包归因，不使用名称猜测。
