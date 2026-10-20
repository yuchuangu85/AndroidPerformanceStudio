# 页面 08：Battery Profiler / Network Profiler

> 历史设计图曾把电量与网络放在同一页（[设计参考](images/08-battery-network.png)）。
> 当前实现已拆分为两个独立的一级页面；设计图仅保留为两个页面的指标与证据布局参考。

## 13.1 两个独立页面，模型不合并

导航与首页分别提供两个入口：

```text
Battery Profiler
Network Profiler
```

实现上仍然是：

```text
BatteryProfilerController
NetworkProfilerController
```

各自页面独立挂载 `BatteryProfilerMainPage` / `NetworkProfilerMainPage`，不再共享标签页容器或业务事件模型；一方的采集失败、导入失败或空状态不影响另一方。

## 13.2 Battery Profiler 页面

### KPI

```text
Wakelock Duration
Wakeup Alarms
Network Bytes
Modeled Energy（只有 evidence 存在时）
```

### 主要区域

```text
Experiment summary
Runs
Battery snapshots / history
Wakelocks
Alarms
Jobs
Sensors
Energy
Network counters
Warnings
```

### History

按真实 `BatteryHistoryEventKind`：

```text
WAKELOCK
ALARM
JOB
SENSOR
NETWORK
APP_STATE
SCREEN
CHARGING
THERMAL
```

## 13.3 Network Profiler 页面

### Summary

```text
Requests
Failures
Transferred bytes
Median/slow request（从真实 call duration 聚合）
Evidence completeness
```

### Request List

字段：

```text
Method
Redacted URL
Status
Duration
Bytes
Protocol
Outcome
Source
```

### Request Detail

```text
Dispatcher Queue
DNS
Connect
TLS
Request Headers/Body
Server Wait
Response Headers/Body
Connection Held
```

必须显示 `NetworkConfidence`，尤其 HAR import 的 inferred/approximated phase。

## 13.4 Correlation

拆分页面后不再提供常驻的 Correlation 标签页。第一阶段不展示跨时钟关联；只有同时具备以下证据时，才能增加独立关联视图：

- 可比较的 time domain；
- capture window；
- clock mapping error bound；
- artifact provenance。

证据不足时不得把 Battery 事件与 Network 调用画在同一 X 轴，也不得用占位页面暗示关联已经可用。

## 13.5 验收

- Battery / Network 可从导航栏与首页分别进入，且在导航切换后各自保留独立状态；
- Battery / Network 可以独立使用；
- 一方失败不影响另一方；
- HAR inferred phase 有视觉区分；
- sensitive URL/headers 继续遵守 redaction；
- 不存在合并的 Battery & Network 标签页容器。

---

## 13.6 当前落地与证据边界（2026-10-20）

- 移除 `BatteryNetworkMainPage` 复合工作区与 `Battery | Network | Correlation` 标签页；`AppDestination` 恢复为 `BATTERY_PROFILER` 和 `NETWORK_PROFILER` 两个一级目的地，主页、导航栏、命令面板和窗口标题均分别呈现。
- Battery 增加可搜索、重新打开的持久化实验会话侧栏；新建仅清空当前查看状态，不删除已有会话，也不因打开历史证据而切换 ADB 采集目标。窄窗口侧栏移到内容上方。
- Battery 快照图使用真实 `capturedAt` / 电量样本，缺失样本断线；外部供电只表示已观测采样点，不外推充电区间。历史图按实际事件类型分轨，保留未知类型、无时间戳事件与原始证据，不将历史相对时钟强行映射为快照时间。
- Battery 资源区提供 Wakelocks / Alarms / Jobs / Sensors / Energy / Network / History / Warnings 标签、搜索、行选择及证据详情。未采集的最后触发时间、进程、线程和原因不补造；无能耗证据不显示 Modeled Energy KPI。
- SQLite 恢复保留网络分项、事件、快照顺序及热状态。旧会话或摘要 JSON 仅有流量总数时只显示总字节，分项不可用，不将总量归为 Wi-Fi 下载；历史会话 CSV 导出保留已知归因范围，未知范围标记 `UNKNOWN`。
- Network 增加请求活动图、脱敏字段搜索、调用结果筛选与 Domains 汇总；筛选同步影响列表和详情，窄窗口上下排列。活动图下半部是“请求结束时记录的字节分桶”，不是实测吞吐率；缺失字节证据的域名总量/分桶保持不可用。
- 脱敏搜索框使用与顶部工具栏输入框一致的 24dp 紧凑高度，不再使用默认 Material 搜索框高度。
- HAR 推断/估算阶段继续使用琥珀色虚线并显示置信度。无跨域时钟证据时不提供 Correlation 入口。
- 视觉沿用当前 `DESIGN.md` 的紧凑共享主题，不复制图片中的装饰横幅，不伪造图中数值、同比百分比或“高耗电”归因。

### 验证入口

在 `desktop-viewer` 下执行：

```bash
./gradlew :battery-profiler:battery-app:test \
  :battery-profiler:battery-app:ktlintCheck :battery-profiler:battery-app:detekt \
  :network-profiler:network-presentation:test \
  :network-profiler:network-presentation:ktlintCheck :network-profiler:network-presentation:detekt

./gradlew :desktop-app:test --tests '*BatteryNetworkSplitTest'
```

自动 Compose 截图由测试夹具提供，不注入生产数据。设置 `APS_BATTERY_PROFILER_SCREENSHOT_DIR` / `APS_NETWORK_PROFILER_SCREENSHOT_DIR` 可输出宽屏/窄屏截图；`APS_BATTERY_PROFILER_SCREENSHOT` / `APS_NETWORK_PROFILER_SCREENSHOT` 输出中文深色主截图。当前截图目录：`desktop-viewer/build/visual-captures/battery-network/`。

这些截图和本地测试不替代真实设备采集、原生窗口交互或打包应用验收。
