# Battery Profiler

## 功能定位

Battery Profiler 通过 `dumpsys batterystats`、电池状态、thermalservice 和可选系统模型数据，分析一组已授权实验中的电量、UID 资源和系统活动。它不是硬件电能计，也不把系统归因直接解释为单 App 因果。

当前状态：

- **已接入**：BatteryReport schema v1 导入、Interactive/Timed/Repeated/Online 模式、运行列表、wakelock/alarm/job/sensor/network/history/thermal 证据、baseline 描述性比较、JSON/CSV 导出和会话保存。
- **条件能力**：实时 capture 需要设备、ADB、权限、稳定 UID/进程和支持的 batterystats/thermalservice 字段。
- **未完成验收**：原始 dumpsys 包、完整实验环境采集、Historian 导入/解析和真机矩阵仍开放。

## 功能点

- Interactive：用户操作期间采样；Timed：固定时长；Repeated：多轮运行；Online：长时间轮询。
- 配置边界：单次约 5–600 秒、轮询约 5–60 秒、最多 10 轮、最多 1024 个快照；Online 轮询不低于 30 秒。
- 读取 batterystats checkin/history、电池状态、UID 资源、wakelock、alarm、job、sensor、energy、network 和 warnings。
- 读取 thermalservice 状态、温度峰值和 throttled sample；保留性能窗口关联。
- 支持当前报告与上一份已发布报告的描述性 baseline 比较。
- 需要明确确认的全局 reset 和 bugreport 生成与普通分析分离。
- 输出 JSON、CSV、会话包和 Battery Historian 所需的原始输入（可用时）。

## 原理与数据流

```text
experiment config + target identity
  -> reset/start/workload/poll/stop
  -> batterystats + battery state + thermalservice + optional model
  -> bounded parser/normalizer
  -> run/metric/evidence model
  -> baseline, export and GPUI workspace
```

当前 Rust 入口：

- `rust-desktop/crates/aps-app/src/import/battery`：capture、model、workspace、baseline 和导出。
- `rust-desktop/crates/aps-battery`：报告模型、解析、能力和统计边界。

每轮绑定设备 serial、PID、包名和 UID；结束快照读取 history。中途计数器 reset、缺字段、样本不连续或目标变化不会被恢复成有效零值。

## 使用方法

1. 选择设备、包名/进程和 Interactive/Timed/Repeated/Online 模式。
2. 设置时长、轮次、轮询、workload 和可选 baseline。
3. 显式确认实时采集；等待目标绑定、快照和结束 history 完成。
4. 查看电量、UID 资源、wakelock、alarm、network、thermal 和 warnings。
5. 只有身份、workload、配置、充电状态、网络计数器和快照连续性都可比时，才查看 baseline 差异。
6. 导出 JSON/CSV；reset 或 bugreport 走单独确认入口。

## 证据边界与限制

- 系统模型能耗不是硬件电能测量；thermal/temperature 不能单独证明 App 限频。
- batterystats 是系统统计和归因，不是每条电量消耗的直接测量。
- baseline 比较是描述性，不自动构成发布回归门禁。
- 缺失、不可用、计数器 reset 和不连续样本保持 unknown/unavailable。
- 设备厂商、Android 版本、电源 HAL、后台策略、屏幕/doze/网络条件会改变结果。

## 故障排查

- 没有数据：检查 ADB 授权、目标 UID、batterystats 能力和实验是否有足够时长。
- 差值为 unknown：检查快照连续性、计数器 reset、充电状态和网络计数器。
- thermal 没有字段：设备/版本可能不支持对应 thermalservice 传感器；不要用零替代。
- reset/bugreport 失败：确认用户授权、设备存储空间和系统权限；成功后才清除旧分析。
