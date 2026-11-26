# Memory Profiler

## 功能定位

Memory Profiler 用于抓取或导入 Java HPROF，并在可用时关联 Native heap、ART runtime、Bitmap dump 和 LeakCanary/Shark 证据。它支持从类级别逐步深入到实例、字段、引用链、Dominator 和快照差异。

当前状态：

- **已接入**：HPROF 导入/转换、类直方图、实例列表、字段/引用展开、Dominator、retained size、快照 diff、导出和有界 AI/源码关联。
- **条件能力**：Native/ART/Bitmap/LeakCanary 依赖设备数据源、debug Agent、Perfetto/heap tooling、mapping 和目标工程配置。
- **未完成验收**：大真实堆的 RSS/取消、真机 Native/ART/Bitmap 完整往返和 LeakCanary Agent 端到端仍开放。

## 功能点

- Dump Heap 或导入 `.hprof`；必要时使用宿主 `hprof-conv` 生成标准副本。
- Class List 展示对象数、shallow size、retained size、heap/来源和可用性。
- Instance List 保留快照内对象 ID；详情支持字段、引用、数组页和对象路径。
- Dominator tree 用于识别保留内存的支配对象和 retained size。
- Reference path/nearest GC root 用于调查对象为何仍可达；未知边和缺失详情保持显式。
- Snapshot Diff 比较两个兼容快照的类、对象数和大小变化；不把不同来源混成同一快照。
- 实时内存对比可同时采样两台及以上设备；每台设备独立选择一个运行中的应用进程。当前数值和历史折线按设备区分颜色。
- Bitmap、Native Heap、ART GC/JIT/class loading 等属于独立证据通道，不伪装成 Java HPROF 对象图。
- 导出 CSV/JSON/Markdown/原始 artifact；报告会写入引擎、来源 digest、覆盖率和省略原因。

## 原理与数据流

```text
ADB dumpheap / imported HPROF
  -> bounded source probe and optional hprof-conv
  -> isolated memory worker
  -> parser/index/heap projection
  -> histogram / instance / reference / dominator queries
  -> snapshot diff / Shark adapter / exports
```

当前 Rust 入口：

- `rust-desktop/crates/aps-app/src/import/memory.rs` 及其子模块：workspace、任务、查询、导出和 UI 状态。
- `rust-desktop/crates/aps-memory`：隔离 worker、协议、快照和分页结果。
- `rust-desktop/crates/aps-hprof-engine`：HPROF 解析、索引和分析。
- `rust-desktop/crates/aps-app/src/import/memory/inspector.rs`：实例、字段、引用和路径交互。

原始 HPROF 不进入 UI 进程的 heap graph；worker 输出有界、类型化、不可变的快照投影。取消和关闭会释放源租约，避免 UI 直接持有任意堆对象。

## 使用方法

### HPROF

1. 选择设备和可调试进程，点击 Dump Heap，或导入 `.hprof`。
2. 等待来源校验、可选转换、解析、索引和摘要完成。
3. 从 Class List 找到可疑类，进入实例列表和对象详情。
4. 用 Fields/References、nearest GC root 和 Dominator 追查保留路径。
5. 导入匹配的 `mapping.txt`（如有），再导出报告或保存会话。

### Diff

1. 打开两个来源兼容的快照。
2. 选择 baseline/current，检查源 digest、时间、引擎和能力差异。
3. 按类、对象数、shallow/retained size 查看变化。
4. 对变化最大的类回到实例/引用路径，避免只根据聚合数字下结论。

### 多设备实时内存对比

1. 通过 ADB 连接并授权至少两台 Android 设备，在桌面端打开 **内存 → 实时对比**。
2. 在各设备下搜索并选择运行中的应用进程。可以选择相同包名，也可以选择不同包名或 `包名:子进程`；只采样已选设备。
3. 点击 **开始对比**。正常情况下每台设备约每秒独立采样一次，保留最近约两分钟曲线。各设备使用同一个会话时间轴；同一个指标图内使用统一的 Y 轴量程。
4. 顶部实时显示各设备 Total PSS、Total RSS、Swap PSS 数值。在分类页签查看 Java / Native 堆、代码、栈、图形、系统、堆 Size/Alloc/Free、映射/Dalvik 细分、SQLite 和内存预算等实际提供的指标，曲线每行三张；Objects（Views、Activities、Binder、Parcel、WebViews 等）只显示实时数值对比表，不绘图。HTML 报告沿用三列曲线与 Objects 数值表布局。未上报的值显示为不可用，显式的 0 仍显示为 0。数据库、资源和厂商扩展等原始明细可在各设备的报告中查看或复制。
5. 点击 **停止** 冻结本次曲线。若进程重启或设备掉线，该设备的采样会显示错误，其他设备可继续；要对比新 PID，请停止、刷新目标并重新开始。
6. 有采样记录后，点击 **导出 HTML 报告** 并选择新文件名。直接用浏览器离线打开即可查看所有已记录分类和指标的曲线，不限于当前页签。报告左侧为固定功能列表（应用摘要、内存明细、对象、SQL、预算、最新原始 meminfo），点击后在右侧显示对应内容，右侧独立上下滚动。报告保存点击导出时保留的滚动窗口（最近约两分钟），导出不会停止实时采样。“最新原始 meminfo”按设备横向并排且默认展开，使用等宽字体保留原始列对齐；窄屏或更多设备时横向滚动，原始文本不改写单位。

采样通过只读 `adb -s <序列号> shell dumpsys meminfo -a <PID>` 执行，不导出 HPROF，也不跟随包名自动切换 PID。离开实时对比页不会自动停止采样；请在不再需要时点击停止。跨 Android 版本可能有不同字段；原始数据和折线不填充设备未上报的值。

### Native/ART/Bitmap

先确认目标设备和采集能力，再分别打开对应页面。Native allocation、ART runtime slice 和 Bitmap dump 不是 HPROF 的同一对象空间，比较时必须保留各自的时间、heap、来源和覆盖率。

## 输出与证据边界

- Java HPROF 对象 ID 只在单个快照内稳定，不能跨快照直接当作身份。
- retained size、Dominator 和 LeakCanary/Shark 是不同分析模型；Shark 候选不是 LeakCanary 运行时证明。
- HPROF 成功不证明 Native Heap、ART、Bitmap 或 Agent 已在真机完成。
- 缺失 mapping、截断字段、未知引用、分页省略和 worker 失败必须保持可见。
- 真实设备、注入、签名、重启恢复和大堆性能属于独立验收门槛。

## 故障排查

- HPROF 无法解析：检查文件是否完整、是否为目标 Android 版本格式、是否需要 `hprof-conv`，以及 source digest 是否稳定。
- 内存高或取消慢：确认是否使用隔离 worker、输入是否超预算、是否同时运行多个导入；不要用旧 worker 进程判断新任务状态。
- 没有泄漏链：可能没有 Shark/LeakCanary 证据、mapping 缺失或对象边界不可用；显示不可用不等于没有泄漏。
- Diff 不可比：检查快照来源、引擎、heap/能力、digest 和采集时刻。
