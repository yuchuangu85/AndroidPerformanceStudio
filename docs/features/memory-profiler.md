# Memory Profiler

## 功能介绍

Memory Profiler 用于抓取、导入和调查 Java HPROF、Native heap 以及 Bitmap dump。当前能力包括类直方图、对象实例、引用链、Dominator/retained size、快照 diff、Bitmap 分析和 LeakCanary/Shark 兼容分析。

## 详细设计方案

```text
ADB dumpheap / import HPROF
  -> probe
  -> optional hprof-conv
  -> parse/index
  -> heap graph
  -> histogram + dominator + reference query
  -> LeakCanary/Shark or native analysis
  -> themed workspace / export
```

### 模块职责

- `memory-profiler/capture-memory`：ADB dumpheap、pull、hprof-conv、Native trace 和 Agent 连接。
- `parser-hprof`：Android/Java HPROF 识别、解析、索引和 warning。
- `memory-model`：HeapDump、HeapClass、HeapInstance、snapshot capability、leak report。
- `analysis-memory`：直方图、Dominator、对象调查、Bitmap、Shark adapter。
- `memory-storage-sqlite`：最近会话和 session 元数据。
- `memory-export-adapters`：CSV、JSON、Markdown 和原始 artifact 导出。
- `presentation`：Dashboard、Class List、Dominator、Diff、Native Heap、Memory Leaks 页面。
- `memory-app`：设备/进程、任务取消、导入、会话和菜单编排。

### LeakCanary 实时链路

目标 debug App 可注入 `android-agent-leakcanary` AAR 和 protocol JAR。Agent 通过 ContentProvider 自动启动，监听 Activity/Fragment、弱引用回收和 retained object，桌面端通过 ADB forward + token session 轮询事件。

## 实现方案

离线 HPROF 分析和实时 Agent 是两条证据通道：

- Shark 负责主机侧 heap graph/leak trace；不把启发式候选冒充成 LeakCanary 证明。
- Agent 负责 App 侧生命周期、弱引用和实时事件；LeakCanary 依赖通过 Android 工程 debug 注入。
- Native heap 通过 heapprofd/Perfetto 证据分析，不伪装成 Java HPROF 对象图。

## 使用方案

### 离线/快照模式

1. 选择设备和可调试进程，点击 Dump Heap，或导入 `.hprof`。
2. 等待 probe、转换、解析和分析完成。
3. 在 Class List 查找类与实例，在 Dominator 查看 retained size。
4. 打开 Memory Leaks 查看 Shark 引用链。
5. 需要时导入 `mapping.txt` 并导出调查报告。

### 实时模式

1. 构建 bridge bundle：

```bash
cd desktop-viewer/memory-profiler
./gradlew -PincludeLeakCanaryAgent=true :android-agent-leakcanary:bundleLeakCanaryAgent
```

2. 在 Memory Profiler 选择 Inject Agent，选择 Android 工程。
3. 重新构建并安装 debug APK。
4. 选择设备和进程，点击 Memory Leaks。
5. 查看 Activity/Fragment destroyed、Object retained/collected 等实时事件。

## 输出与限制

- HPROF、Native trace、Bitmap dump、分析 JSON/CSV/Markdown 分属不同 artifact。
- 已安装 APK 不能假设可以无损注入；注入需要重建、签名和重新安装。
- 当前真机端到端 Agent 验收仍需单独执行。
