# Rust + GPUI Kit 全项目迁移方案

## 状态

本文档是迁移规划，不代表迁移已经开始，也不修改当前 Compose/Kotlin 实现。

目标是将桌面端重构为 Rust + GPUI Kit 主程序，同时保留 Android 端 Agent 的 Kotlin/Java 实现，并通过分阶段 parity、性能和发布验证逐步替换现有 Compose Desktop 应用。

## 1. 目标架构

```text
Rust GPUI Kit 主程序
├── GPUI UI、导航、窗口、菜单
├── Rust 应用状态和生命周期
├── Rust profiler 数据引擎
├── Rust SQLite、文件、session、导出
├── Rust ADB 和外部工具编排
└── Android 端 Agent 继续使用 Kotlin/Java
```

这不是单纯的 UI 换皮，而是一个新的 Rust 桌面产品重建项目。Android 端 Agent 依赖 Android SDK、Java/Kotlin 生命周期和 Android Runtime，不应为了统一语言而全部迁移到 Rust。

当前 Compose 版本继续作为行为基准和回退实现。现有 UI 规模约为 330 个 Compose 源文件、约 9 万行 Compose 相关代码，并通过多个 Gradle composite build 组织 CPU、Memory、Network、Perfetto、Layout Inspector 等模块。迁移范围覆盖 UI、状态、窗口、测试、打包和应用编排。

## 2. 技术基线

在新 Rust 应用开始实现前固定以下版本和约束：

- Rust toolchain 版本。
- `gpui-kit` 版本。
- `Cargo.lock`。
- 匹配的 GPUI snapshot 和 platform crate。
- 是否启用 `gpui-fast`。
- macOS、Windows、Linux 支持版本。
- 图形后端、字体、CJK 和 IME 策略。
- AccessKit 无障碍支持边界。
- 应用签名、安装包和第三方 notices 规则。

GPUI 和 GPUI Kit 处于快速演进阶段，不能依赖未锁定的 Git HEAD 或浮动依赖。应用只能使用一套相互匹配的 GPUI 来源，避免把不同 snapshot 的类型混入同一个 Rust workspace。

## 3. 并行重建策略

初期不修改现有 `desktop-viewer` 的 Compose 主程序，新增独立 Rust workspace：

```text
AndroidPerformanceStudio/
├── desktop-viewer/       # 现有 Compose 版本，行为 oracle 和回退实现
├── rust-desktop/         # 新 Rust + GPUI Kit 版本
│   ├── Cargo.toml
│   ├── rust-toolchain.toml
│   └── crates/
└── Android Agent modules/ # 原有 Android Kotlin/Java 模块
```

这样可以：

- 用同一份 profile 对比旧版和新版。
- 在迁移期间保持当前应用可用。
- 使用现有 Compose 测试作为行为参考。
- 单独验证 GPUI 的平台和图形问题。
- 避免把当前未提交的 Rust、Memory、Network 工作混入迁移。

## 4. Rust Workspace 结构

建议按领域拆分 crate，不要把所有逻辑放入单一 `aps-app`：

```text
crates/
├── aps-app                 # 主程序、导航、窗口、菜单
├── aps-ui                  # GPUI 页面和交互
├── aps-ui-foundation       # 主题、字体、颜色、尺寸、图标、文案
├── aps-contracts           # Artifact、Session、Device、Error、Evidence
├── aps-protocol            # 子进程和外部工具的版本化协议
├── aps-platform            # 文件、进程、路径、权限、系统服务
├── aps-adb                 # ADB 定位、设备发现、命令执行
├── aps-storage             # SQLite schema、迁移、查询
├── aps-capture             # Capture 状态机和外部工具编排
├── aps-cpu                 # Simpleperf parser、索引、调用树、时间线
├── aps-memory              # HPROF、引用图、Dominator、Leak 分析
├── aps-network             # HAR 流式解析和网络模型
├── aps-perfetto            # Trace Processor 生命周期和 SQL 查询
├── aps-frame               # Frame/Jank 分析
├── aps-startup             # Startup 分析
├── aps-battery             # Battery 分析
├── aps-gpu                 # AGI 工具和 GPU 产物管理
├── aps-benchmark           # Benchmark 对比
├── aps-layout-inspector    # 桌面端 Inspector 主机
├── aps-ai                  # Provider、模型、请求、结果
├── aps-source              # Source Workspace 和源码授权
└── aps-export              # JSON、CSV、PNG、Firefox 等导出
```

每个分析域应能够单独测试、替换后端和限制内存。UI 只依赖领域 contracts，不直接依赖 parser、ADB 或具体外部工具实现。

## 5. 分阶段迁移

### 阶段 0：基线与契约冻结

不迁移 UI，先记录现有行为：

- 进程启动到首窗口。
- 首窗口到 Home 可交互。
- CPU session 打开时间。
- Overview ready 时间。
- Stack Chart 首帧时间。
- 空闲 RSS。
- 普通 profile RSS。
- 大 profile 峰值 RSS。
- 关闭 profile 后 retained RSS。
- Stack Chart 滚动、缩放、hover、选择延迟。
- 取消查询和关闭 session 的完成时间。

CPU 压力测试必须使用真实大 profile。已有测量曾出现 237,703 个 Stack Chart blocks 和 86,705 个交互覆盖候选，不能只用小型 synthetic fixture 判断迁移收益。

退出条件：

- 有固定的 benchmark 输入和运行脚本。
- 有摘要、调用树、Flame Graph、Stack Chart 的旧版结果基线。
- 已知缺陷和已知 parity gap 已记录。
- 现有 Compose 版本的行为 oracle 不再随意变化。

### 阶段 1：Rust/GPUI 空壳应用

只实现桌面基础能力：

- GPUI 主窗口。
- 应用名称和图标。
- 主菜单和窗口最小尺寸。
- Home 页面骨架。
- 设置页面骨架。
- 主题切换。
- 语言切换。
- 文件选择器、剪贴板和退出。
- macOS、Windows、Linux 最小打包。

这一阶段不接 profiler 数据。当前 Compose 应用直接使用 `ComposeWindow`、AWT/Swing、JBR 和 macOS window chrome，新应用需要建立独立 platform adapter，不能假设 GPUI 自动提供相同的窗口行为。

### 阶段 2：基础数据和平台层

迁移：

- Path、文件访问、临时目录和权限检查。
- Host process runner。
- ADB 定位、设备列表、设备属性和取消。
- Capture session 目录。
- Artifact hash、source digest、provenance。
- SQLite schema 和 migration。
- 统一错误模型。
- 外部工具生命周期。

继续作为外部组件：

- `adb`。
- `simpleperf`。
- `trace_processor_shell`。
- Android 端 Agent。
- Android GPU Inspector 工具。
- Firefox Profiler 外部页面。

Rust 负责桌面端编排，Android 端 Agent 和第三方工具不重写。

### 阶段 3：CPU Profiler 垂直切片

这是第一条完整迁移路径，顺序如下：

1. Rust 解析 Simpleperf protobuf。
2. Rust 生成 SQLite 或 native snapshot。
3. Rust 建立持久化 profile session。
4. Rust 支持线程、事件、时间范围和方向查询。
5. Rust 提供 Call Tree 分页。
6. Rust 提供 Flame Graph viewport 数据。
7. Rust 提供 Stack Chart viewport 数据。
8. GPUI 实现 Overview、Top Functions、Call Tree、Flame Graph、Stack Chart。
9. 最后接入 Capture 和 Firefox 导出。

必须避免每次分页重新解析和聚合输入。Rust engine 打开 profile 后应保持 session、索引和空间查询结构，分页只返回当前页。

Stack Chart 应采用：

```text
Rust:
  viewport query
  pixel aggregation
  spatial index
  hit-test
  selected item details

GPUI:
  one custom Element / canvas
  limited semantic nodes
  keyboard focus target
  selected/hover overlay
```

不应为每个 block 创建独立 GPUI 组件，否则会把当前 Compose 的交互节点问题原样迁移。

CPU 阶段退出条件：

- Call Tree、Flame Graph、Stack Chart 的权重、线程、时间范围、方向和选择行为与旧版一致。
- 键盘导航、搜索、上下文菜单和无障碍行为一致。
- 大 profile 首帧不劣于优化后的 Compose 基线。
- 总 RSS 不高于 Compose 基线，CPU 压力场景目标降低 20% 以上。
- Stack Chart P95 交互延迟目标降低 30% 以上。
- 取消查询不会留下 Rust worker 或临时文件。

### 阶段 4：Memory、Network、Perfetto

#### Memory

- Rust 负责 HPROF parser、索引、引用图和查询。
- UI 只接收 class list、leak findings、instance page 和 details。
- 不把完整 HeapDump 重新组装回 UI 进程。
- 大图分析可用受控子进程隔离内存和崩溃。

#### Network

- Rust 负责流式 HAR 解析、大小限制和摘要。
- 逐步迁移 redaction、entry mapping 和 NetworkAnalyzer，并以旧 Kotlin 结果做 parity。
- URL、headers、body 必须继续经过显式授权和有界传输。

#### Perfetto

- 不重写 Trace Processor。
- Rust 负责工具定位、启动、查询、取消、结果解码和缓存。
- SQL 结果统一映射到 Rust domain contract。

### 阶段 5：其他 Profiler 和 Layout Inspector

按数据复杂度迁移：

- Frame Profiler：Rust 模型和查询，Android Frame Agent 保留。
- Startup Profiler：Rust 解析和聚合，AndroidX Startup Agent 保留。
- Battery Profiler：Rust 解析 bugreport、Battery Snapshot 和差值，ADB 采集保留。
- GPU：Rust 管理 AGI 产物和外部工具，GPU Inspector 工具不重写。
- Benchmark：Rust 做 baseline、diff、统计和导出。
- Layout Inspector：桌面端 UI 和协议主机迁移，Android View/Compose Agent 继续 Kotlin/Java。

### 阶段 6：AI 和 Source Workspace

放到后期，因为它们不是 CPU UI 性能关键路径，且安全边界复杂。

Rust 可以接管：

- HTTP transport。
- SSE/stream decoder。
- Provider/model catalog。
- Bounded response parser。
- Session persistence。
- Source candidate index。

必须保留：

- credential 不进入 UI state。
- source upload 必须显式授权。
- provider、model、agent 配置必须可审计。
- source candidate 必须绑定 workspace、snapshot 和 hash。
- AI 结果不能伪造源码路径和行号。

## 6. UI 迁移原则

建立 `aps-ui-foundation`，重新实现当前 `ViewerTheme` 的产品设计令牌：

- Colors。
- Typography。
- Dimensions。
- Density。
- Accent。
- Dark/light theme。
- Localization。
- Focus/keyboard states。
- Accessibility roles。

GPUI Kit 的默认组件只作为基础设施。CPU 时间线、Flame Graph、Stack Chart、Memory object graph 等高密度视图应使用自定义 GPUI Element，而不是强行套用通用组件。

UI 状态应按领域拆分：

```text
AppState
├── navigation
├── settings
├── device
├── capture
├── session
├── cpu
├── memory
├── network
├── perfetto
├── ai
└── sourceWorkspace
```

高频 hover、viewport、拖拽和选中状态应留在对应页面或 custom Element 内，不能每次都更新根应用状态。

## 7. 进程和协议边界

即使最终全部使用 Rust，也不建议把所有逻辑放进一个进程：

```text
aps-app
├── UI / navigation / settings
├── normal-size queries
└── controlled child processes
    ├── HPROF large graph analysis
    ├── untrusted import parsing
    ├── external tool adapters
    └── optional CPU/Network workers
```

子进程用于：

- 隔离大 HPROF 崩溃。
- 限制内存、时间和输出。
- 支持取消和强制回收。
- 处理不可信输入。
- 方便替换分析引擎。

统一协议采用有界、版本化、带 digest 和 generation 的 framed protocol：

```text
request:
  protocolVersion
  sourceDigest
  sessionId
  generation
  operation
  bounded arguments

response:
  protocolVersion
  requestId
  sourceDigest
  generation
  status
  bounded payload
```

大数据不返回完整报告，只返回：

- summary。
- page。
- viewport。
- hit-test result。
- selected detail。
- bounded diagnostics。

## 8. 测试和发布门禁

需要建立五类证据：

1. **Rust domain tests**
   - parser、storage、query、cancellation、source digest、malformed input。
2. **Cross-engine parity tests**
   - Compose/Kotlin 输出与 Rust 输出对比。
   - 使用相同不可变输入。
   - 明确记录已知 parity gap。
3. **GPUI headless tests**
   - focus、keyboard、selection、pagination、accessibility、layout bounds。
4. **Native GUI tests**
   - macOS、Windows、Linux、文件选择器、输入法、菜单、resize、high-DPI。
5. **Packaged release tests**
   - `.app`/DMG、Windows installer/portable、Linux packages、bundled ADB、Trace Processor、Rust engines、签名和 notices。

Compose 截图测试、GPUI headless 测试和真实安装包验收属于不同证据类别，不能互相替代。

## 9. 最终切换条件

只有同时满足以下条件，才删除 Compose 主程序：

- 所有核心 profiler 至少有 Rust 正式实现。
- 所有 Android Agent 和外部工具路径通过新桌面端验证。
- 关键数据契约 parity 通过。
- CPU、Memory、Network、Perfetto 的取消和失败恢复通过。
- 新应用在目标平台完成 native 和 packaged 验证。
- 大 profile 的总 RSS 不高于旧版，CPU/Memory 压力场景达到预设收益。
- GPUI Kit 升级流程固定了版本、锁文件和回归矩阵。
- 旧 Compose 实现只剩无业务价值的历史代码，而不是仍承担 fallback。

## 10. 推荐垂直切片

```text
第 1 个垂直切片：Rust GPUI CPU Profiler
第 2 个垂直切片：Rust GPUI Memory Profiler
第 3 个垂直切片：Rust GPUI Network + Perfetto
第 4 个垂直切片：其他 Profiler
第 5 个垂直切片：AI / Source / Layout Inspector
最后：统一入口、打包、切换、删除 Compose
```

首个实现目标应是独立的 CPU Profiler 页面，而不是直接迁移整个主窗口。它能够同时验证 GPUI 高密度绘制、Rust 数据引擎、分页协议、内存隔离、键盘交互、无障碍和打包路径，是最有信息量的垂直切片。

## 11. 方案结论

推荐最终架构：

```text
Rust + GPUI Kit 桌面主程序
+ Rust profiler 数据平面
+ 版本化 bounded protocol
+ 受控子进程隔离大数据分析
+ Android Agent 保留 Kotlin/Java
+ Compose 作为迁移期间的行为 oracle
```

迁移的核心不是把 Kotlin 代码逐行翻译成 Rust，而是重新定义数据、状态和进程边界。最关键的执行原则是：

1. 先冻结行为和性能基线。
2. 先完成 CPU 垂直切片。
3. 旧实现与 Rust 实现并行验证。
4. 只传输摘要、分页和 viewport 数据。
5. 通过真实 profile、原生 GUI 和安装包验证后再扩大范围。
6. 最后才删除 Compose 和 Kotlin 桌面实现。

当前没有进行代码修改、迁移实现或 GPUI/Compose 性能对照测试。
