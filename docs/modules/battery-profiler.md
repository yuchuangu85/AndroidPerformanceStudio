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
   - 可选重置全局 batterystats（`dumpsys batterystats --reset`）
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

- **BatteryExperimentConfig**：实验配置（mode、durationSeconds、pollingIntervalSeconds、measuredRuns、launchApp）
- **BatterySnapshot**：单个采集快照，包含 uidStats、deviceState、history、rawEvidence
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

## 优化建议与改进点

> 以下内容不替换已有设计，仅作为可考虑的更好实现方式或优化点补充。每条标注 **影响**（高/中/低）与 **可行性**（高/中/低），便于按优先级排序落地。

### 1. batterystats --reset 的全局副作用与替代方案【影响:高 / 可行性:中】

**当前实现问题**：`dumpsys batterystats --reset` 是全局操作，会清空整台设备的电池统计，影响其他正在进行的测量，且需要 `DUMP_SOURCE` / `PACKAGE_USAGE_STATS` 等权限，在用户设备上不易获得。

**更好的实现方式**：
- **首选**：放弃 `--reset`，统一采用 **baseline → final 差值法**（文档已支持 baseline 对比，建议把它作为唯一主路径，把 reset 降级为可选的"洁净环境"辅助）。
- 进一步引入 **`cmd battery unplug` + `cmd battery set` 模拟电源**：在可控实验中通过 `cmd battery unplug` 让设备进入"非充电态"再采集，避免充电状态污染数据，比 reset 更安全且可逆（结束实验时 `cmd battery reset`）。
- 如必须 reset，提供"自动复位"流程：实验结束后立即重新采集一份新的 baseline 给后续实验使用，降低"reset 污染其他实验"的概率。

**补充建议**：在 `BatteryExperimentConfig` 增加 `resetPolicy` 枚举（`NONE` / `BATTERY_STATS_RESET` / `UNPLUG_ONLY`），明确语义而非用布尔开关。

### 2. Online 轮询的观察者效应【影响:中 / 可行性:高】

**当前实现问题**：Online 模式以 5-60 秒间隔轮询 `dumpsys batterystats`/`dumpsys battery`。`dumpsys` 本身会触发 Binder 调用、唤醒部分统计，可能引入能耗噪声，对"低能耗监控"场景构成自干扰。

**更好的实现方式**：
- **轮询期间只读 `dumpsys battery` 轻量字段**（level/temperature/voltage/charging），不触发完整的 batterystats；完整 batterystats 仅在用户显式"采样"时拉取一次。
- 提供"轮询能耗自校准"开关：在采集前后各做一次空转（不操作设备）作为本底噪声，从最终结果中扣除。
- 考虑用 **`dumpsys batteryproperties`**（部分设备支持）替代部分 `dumpsys battery` 查询，避免重复解析。

### 3. Energy 估算的精度与可信度【影响:高 / 可行性:中】

**当前实现问题**：文档中 EnergyEstimate 区分了 `HARDWARE_COUNTER` 与 `SYSTEM_MODEL` 两类 source，但未说明 power_profile.xml 的获取与校准。Android 的 powerProfile.xml 由厂商配置，不同设备精度差异极大，部分设备只有粗粒度的电流估算。

**更好的实现方式**：
- **明确标注 powerProfile.xml 来源**：在 `EnergyEstimate` 中增加 `powerProfileSource`（`DEVICE_OEM` / `OVERRIDDEN`）与 `powerProfileVersion`（如有），让用户知道估算基准。
- **支持自定义 power_profile.xml 覆盖**：允许用户在桌面端上传/编辑 power_profile.xml 并推送到分析链路，便于校准实验。
- 对 `SYSTEM_MODEL` 类估算，confidence 应显著低于 `HARDWARE_COUNTER`，并在 UI 上用不同颜色/置信区间区分，避免把"模型估算"当成"实测值"。
- 长期看，优先采集 **`/sys/class/power_supply/.../current_now` 与 `voltage_now`** 的实测电流（如设备可读），用实测功率积分代替模型估算。

### 4. Battery Historian bugreport 的现代化替代【影响:中 / 可行性:中】

**当前实现问题**：bugreport 体积大、耗时长（10-60 秒甚至更久）、隐私敏感（账号、SSID、应用列表）。Battery Historian 本身依赖 Go runtime，部署门槛高。

**更好的实现方式**：
- 优先用 **`bugreportz --progress` + `dumpsys batterystats --history`** 单独提取 history 部分，避免整份 bugreport；很多场景下只需 history 即可。
- 评估用 **Perfetto `battery_stats` 数据源**（Android 12+ 可通过 `perfetto` 命令直接抓取 batterystats history 的 protobuf 形式），替代 bugreport + Battery Historian 的链路，体积更小、字段更结构化、可在应用内 Perfetto Viewer 直接查看。
- 对 bugreport 增加 **隐私脱敏预处理**：在 `BatteryHistorianAdapter` 生成 bugreport 后、落盘前，提供一个"脱敏副本"生成选项（移除/打码 `Account`、`Wifi SSID`、`PhoneService` 等 line），原始 bugreport 仅在用户显式选择"保留敏感数据"时保存。

### 5. PowerState 与外部变量的记录【影响:中 / 可行性:高】

**当前实现问题**：`deviceState` 记录电量百分比、温度、电压、充电状态，但未记录屏幕亮度、CPU/GPU 频率、网络制式、信号强度等关键外部变量。这些变量对能耗影响极大，缺失会导致实验难以复现。

**更好的实现方式**：
- 在 `BatterySnapshot.deviceState` 中扩展记录：
  - 屏幕亮度（`settings get system screen_brightness` / `dumpsys display`）
  - CPU/GPU 频率（`/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq`）
  - 网络制式与信号（`dumpsys telephony.registry` 的 serviceState + signalStrength）
  - Doze / Doze idle 状态（`dumpsys deviceidle`）
- 在实验开始前自动"稳定化"：建议固定亮度、关闭自动亮度、固定网络制式、禁用 Doze（`dumpsys deviceidle disable`），结束后恢复，降低变量噪声。

### 6. Repeated 模式的样本独立性与时序【影响:中 / 可行性:高】

**当前实现问题**：Repeated 模式多次运行只做 min/max/median/P90 等统计，但多次运行之间存在状态依赖（缓存预热、GC 残留、温度累积、电池电量随时间下降），样本并不严格独立。

**更好的实现方式**：
- 每次重复之间插入 **冷却间隔**（如 30-60 秒）并检测温度回落再开始下一次，避免热累积污染。
- 记录每次运行的 **设备温度**，并在统计中标注"温度漂移"作为数据质量告警（`dataQualityWarnings`）。
- 提供 **盒须图 + 时序图** 展示多次运行，让用户直观看到是否存在随时间漂移的趋势，而非只看汇总统计。

### 7. 采集会话的并发与中断恢复【影响:低 / 可行性:中】

**当前实现问题**：`SqliteBatterySessionStore` 持久化实验数据，但未说明采集过程被中断（设备断开、App 崩溃、进程被杀）时的恢复策略。

**更好的实现方式**：
- 在采集会话开始时写一条 `session_started` 记录，结束时写 `session_completed`；启动时检测"已开始未结束"的会话并提供"恢复"或"标记为失败"选项。
- 对 Repeated 模式，每完成一次 run 即落盘一次（增量持久化），避免整段实验因意外中断而全部丢失。

### 8. WakeLock / Alarm / Job 归因的局限说明【影响:低 / 可行性:高】

**当前实现问题**：文档未提示 batterystats 的归因是基于 UID 的粗粒度统计，无法区分同一 UID 内不同子组件的精确触发，且部分 wakelock 为系统 wakelock（如 `*alarm*`、`*com.google.android.gms*`），归因到具体 App 存在歧义。

**更好的实现方式**：
- 在分析结果中显式区分 **App-owned wakelock** 与 **system wakelock**，并对 system wakelock 标注 `attributionAmbiguous=true`，避免误导用户。
- 对 Job/Alarm 触发，若 batterystats 提供了 source package，优先用 source package 归因，而非仅按 UID 归因。
