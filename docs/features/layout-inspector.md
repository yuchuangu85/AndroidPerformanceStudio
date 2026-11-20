# Layout Inspector

## 功能定位

Layout Inspector 用于查看 Android 窗口的 View 层级、支持的 Compose 检查证据、截图、节点属性和结构问题。当前 Rust/GPUI 页面将一次检查建模为不可变的布局归档，再在其上提供临时展示投影。

当前状态：

- **已接入**：ADB/兼容 View 层级导入、截图与窗口选择、层级搜索、折叠、隐藏图层、隔离子树、画布边界、节点详情、结构 Findings、归档导入/导出基础路径。
- **条件能力**：完整 Compose 检查依赖目标进程、Agent/运行时能力、Java/Compose helper bundle、设备版本和用户确认；不满足时保留兼容回退。
- **未完成验收**：连续 Auto Scan、完整 Compose/JVMTI、设备端重组/参数分页、所有归档往返和原生设备矩阵仍需单独验收。

## 功能点

### 采集与来源

- 选择前台应用目标，独立于其他 profiler 的进程选择。
- 选择 Foreground App 或 System UI 采集目标；系统窗口模式要求 WindowManager 类型证据，不能用标题或包名猜测。
- 手动刷新和 Auto Scan；采集失败、设备断开、目标变化和取消会保留明确状态。
- 完整 Compose Agent 为显式授权能力；兼容 ADB/UIAutomator 路径不会伪装成完整 Compose 证据。

### 层级树

- 按窗口切换层级，保留原始节点 ID、父子关系、深度、类名、资源 ID、文本和可见性。
- 按 ID、类名和资源名搜索；搜索是展示过滤，不重编号原始证据。
- 折叠/展开只影响树的显示密度，不改变画布命中、Findings 或归档数据。
- 隐藏图层是可逆的临时展示状态：隐藏节点仍保留在树中，画布命中会穿透隐藏子树。
- **隔离子树**只显示选中节点及其后代；`上级` 返回父节点，`清除隔离` 恢复完整树。隔离同样作用于画布、截图裁剪、hover 和点击命中，但不修改归档。

### 画布与详情

- 按显示空间绘制截图和节点 bounds overlay，支持缩放、平移、Fit、App Only 和命中顺序切换。
- 隔离子树会按隔离根 bounds 对截图做像素级裁剪，不依赖完整截图偏移；没有隔离根时 App Only 才决定是否裁剪到窗口 bounds。
- 节点点击支持 Z-order 或小面积优先；选中画布节点会同步层级树并展开必要祖先。
- 详情页按 Overview、Layout、Drawing、Accessibility、Compose、Evidence 等类别展示可用字段；缺失字段显示为未知/不可用。
- Findings 包含不可见节点、子节点过多、同级重叠、层级过深等结构规则，并支持点击定位节点。

## 原理与数据流

```text
ADB / Compose Agent / archive import
  -> InspectorArchive + LayoutWindow + LayoutNode + screenshot
  -> immutable timeline frame
  -> hierarchy projection (search/collapse/hide/isolation)
  -> canvas projection (crop/hit/overlay)
  -> node details + structural findings + export
```

当前 Rust 入口：

- `rust-desktop/crates/aps-app/src/inspector.rs`：页面状态、采集编排、画布、详情、隔离和导出接线。
- `rust-desktop/crates/aps-app/src/inspector/hierarchy.rs`：层级/画布缓存、隔离根投影和共享节点存储。
- `rust-desktop/crates/aps-app/src/inspector/findings.rs`：规则筛选、计数和选择联动。
- `rust-desktop/crates/aps-layout-inspector`：协议、ADB/窗口解析、归档和边界验证。

隔离状态、隐藏状态、折叠状态和搜索状态均是展示层状态。`InspectorArchive` 仍是原始证据，Findings 使用原始节点集合分析，避免把展示过滤误当成设备真实可见性。

## 使用方法

1. 在首页或导航栏打开 Layout Inspector。
2. 选择设备和采集目标；没有设备时可导入 Inspector archive。
3. 点击刷新或开启 Auto Scan；等待来源标签显示 ADB view、Agent view、Compose 或离线归档。
4. 在左侧搜索节点，点击节点查看属性；使用 disclosure 控制折叠。
5. 需要排除遮挡层时先在层级行使用隐藏图层；需要只研究一个容器时选择 **隔离子树**。
6. 在画布使用 Fit、缩放、平移和命中顺序；点击画布节点可反向定位层级。
7. 在 Findings 中按级别筛选问题，点击问题跳转节点；必要时导出归档保留复核输入。

## 输入、输出与证据边界

输入可以是设备实时采集、已验证的 Inspector archive 或兼容 View/UIAutomator 结果。输出包括结构化归档、节点详情、规则 Findings、截图和导出文件。

- 截图、bounds、窗口和节点必须处于同一显示坐标空间；坐标不一致时应显示错误或不可用，而不是自动猜测。
- ADB fallback 证明的是当次 View/UIAutomator 投影，不证明目标 App 具备完整 Compose Runtime 检查能力。
- 隔离、隐藏和折叠不改变 Android 设备状态，也不改变原始 Findings 指标。
- 时间线和跨帧对应只有在节点身份可验证时才建立；路径相同不是稳定身份证明。
- 截图可见不等于像素级渲染因果；结构 Findings 不能证明 GPU 成本、重组原因或代码行因果。

## 故障排查与限制

- 没有层级：检查设备授权、WindowManager/UIAutomator 权限、目标窗口是否仍在前台，以及 archive 是否包含 `viewHierarchy`。
- 没有截图：归档可能只包含层级；详情和 Findings 仍可用，画布应显示无媒体状态。
- Compose 选项不可用：检查目标进程是否可调试、helper/runtime 是否准备、Compose 版本和用户授权；不要把回退层级当作完整 Compose。
- 隔离后显示完整截图：确认重新运行的是最新 Release，并重新点击一次隔离；当前实现按隔离根像素裁剪截图，隔离屏幕根节点时完整截图是预期结果。
- 真实设备、连续扫描、Agent 注入、完整 Compose 和打包验收必须参考 `rust-desktop/FUNCTIONAL_PARITY.md`，不能由单元测试替代。
