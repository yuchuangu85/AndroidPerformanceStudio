# 功能完成状态清单

更新时间：2026-07-08  
时间来源：`git log --date=iso`，时区为提交记录中的 `+0800`。  
状态说明：

- ✅ 已完成：功能代码已合入 `main`。
- 🚀 已发布：功能已进入正式 release/tag 或发布流水线产物。
- 📝 设计/规划：已有设计或规划文档，但未实现完整功能。
- ⏳ 未开始：仓库中仅占位或 README 规划，未进入实现。

## 总览

| 功能/模块 | 状态 | 完成/记录提交时间 | Commit | 说明 |
| --- | --- | --- | --- | --- |
| Desktop Viewer 基础检查器 | ✅ 已完成 | 2026-07-02 19:36:57 +0800 | `6d8a28e` | 三栏层级、画布、属性 UI 基础能力。 |
| Debug Agent 零代码接入 | ✅ 已完成 | 2026-07-02 19:38:19 +0800 | `47d311e` | 通过 debug 集成和 AndroidX Startup 初始化，不新增 app 权限。 |
| Socket 认证与载荷边界 | ✅ 已完成 | 2026-07-02 22:14:27 +0800 | `3bc4621` | Agent socket 请求认证，限制 capture payload 分配边界。 |
| 前台 Activity 同步采集 | ✅ 已完成 | 2026-07-02 22:15:59 +0800 | `bdcc66d` | 一次采集同时得到前台 Activity 层级证据。 |
| 单授权设备 Agent 会话连接 | ✅ 已完成 | 2026-07-02 22:17:47 +0800 | `506f7f7` | 从桌面端连接唯一授权设备和 Agent session。 |
| 同步截图与布局状态 | ✅ 已完成 | 2026-07-02 22:22:26 +0800 | `d152323` | Desktop Inspector 渲染同步 frame：布局 + pixels。 |
| 跟随前台 Android 应用 | ✅ 已完成 | 2026-07-03 12:02:05 +0800 | `f07881e` | 从固定 fixture 切换为跟随 active app。 |
| 应用区域裁剪与选中 overlay 坐标统一 | ✅ 已完成 | 2026-07-03 16:07:53 +0800 | `76bf08e` | app crop 与 selection overlay 使用同一坐标空间。 |
| 真实比例画布展示 | ✅ 已完成 | 2026-07-03 16:45:35 +0800 | `d323103` | 使 live app 内容按真实比例可读。 |
| 三栏宽度拖拽调整 | ✅ 已完成 | 2026-07-03 17:49:44 +0800 | `8252f5f` | HIERARCHY/CANVAS/PROPERTIES 宽度可拖拽并受边界约束。 |
| Findings 面板与布局风险提示 | ✅ 已完成 | 2026-07-03 22:57:59 +0800 | `203ffa7` | 为真实层级展示可操作 findings。 |
| Findings 中文展示 | ✅ 已完成 | 2026-07-03 23:08:02 +0800 | `d9ca586` | 问题标题/消息支持中文。 |
| 层级路径编号 | ✅ 已完成 | 2026-07-04 00:13:12 +0800 | `695e9f7` | 节点获得结构性路径编号。 |
| 层级引用显示优化 | ✅ 已完成 | 2026-07-04 00:18:36 +0800 | `d5ce5c6` | 在用户检查 issue 的位置展示 hierarchy path。 |
| 紧凑垂直布局与 Findings 高度调整 | ✅ 已完成 | 2026-07-04 00:52:32 +0800 | `7de46d0` | 底部 findings 可拖拽调整并受可用高度约束。 |
| 紧凑操作区 | ✅ 已完成 | 2026-07-04 11:00:59 +0800 | `2afaa3d` | 使 live inspection controls 更紧凑、面向操作者。 |
| 渲染关键结构属性展示 | ✅ 已完成 | 2026-07-04 11:40:41 +0800 | `f4d3a67` | 暴露对渲染有意义的结构信息。 |
| 隐藏层级行视觉区分 | ✅ 已完成 | 2026-07-04 16:53:54 +0800 | `9d3b4fc` | 不可见/隐藏层级行更容易区分。 |
| 设置项可读性优化 | ✅ 已完成 | 2026-07-04 17:00:12 +0800 | `953baea` | 设置选择更易扫描和选择。 |
| 设备选择错误国际化 | ✅ 已完成 | 2026-07-04 23:17:11 +0800 | `e2ec334` | 设备数量错误按当前 viewer language 展示。 |
| ADB fallback 语义层级完整性恢复 | ✅ 已完成 | 2026-07-04 23:17:25 +0800 | `4134793` | 普通 app fallback 层级完整性恢复。 |
| 真实 View hierarchy fidelity in fallback | ✅ 已完成 | 2026-07-04 23:21:06 +0800 | `1005e70` | fallback capture 保留具体 View 层级保真度。 |
| 物理 View 证据导出 | ✅ 已完成 | 2026-07-04 23:43:08 +0800 | `709b575` | View dump 导出可发现、可解释，失败窗口不丢失。 |
| Native 菜单统一命令入口 | ✅ 已完成 | 2026-07-05 00:00:21 +0800 | `3c5653a` | 原窗口命令接入 native menu，避免重复行为。 |
| 产品名展示 | ✅ 已完成 | 2026-07-05 08:52:50 +0800 | `417a0c2` | 桌面应用以产品名展示。 |
| 视图显示偏好持久化 | ✅ 已完成 | 2026-07-05 09:13:48 +0800 | `6f0c531` | View 菜单暴露隐藏不可见节点、隐藏索引等显示选项。 |
| 已保存 View 选项跨面板生效 | ✅ 已完成 | 2026-07-05 09:15:04 +0800 | `2b461f5` | 层级、findings 等面板一致应用保存的显示偏好。 |
| Release 构建版本注入 | ✅ 已完成 | 2026-07-05 23:19:39 +0800 | `1202a9d` | release build 使用权威版本号。 |
| Native installer 发布 | 🚀 已发布 | 2026-07-05 23:21:04 +0800 | `a593f3b` | 从已验证 release build 发布原生安装包。 |
| Canvas 全部可见 bounds overlay | ✅ 已完成 | 2026-07-06 00:32:38 +0800 | `457d11c` | 可显示所有有效可见 View 边框，不削弱 selection。 |
| 可复现 main 分支 release | ✅ 已完成 | 2026-07-06 23:05:12 +0800 | `b4bac39` | 从 main 分支可复现原生发布。 |
| 六格式发布产物契约 | ✅ 已完成 | 2026-07-06 23:15:25 +0800 | `417d036` | Windows EXE/MSI、macOS DMG/PKG、Linux DEB/RPM。 |
| Capture archive 离线归档设计 | 📝 设计/规划 | 2026-07-06 23:40:59 +0800 | `919e9af` | 定义 portable capture archive。 |
| Capture archive 安全导入/导出 | ✅ 已完成 | 2026-07-06 23:56:31 +0800 | `d92bec5` | 归档导入/导出不信任归档内容，边界可验证。 |
| 离线 inspection session | ✅ 已完成 | 2026-07-06 23:58:07 +0800 | `8af8dd6` | 导入归档后进入明确 offline archive 状态。 |
| File 菜单归档导入/导出 | ✅ 已完成 | 2026-07-06 23:59:33 +0800 | `c32ac5a` | portable capture actions 从 File 菜单可发现。 |
| 完整 capture 在 live/offline 间移动 | ✅ 已完成 | 2026-07-07 00:02:42 +0800 | `c7af417` | 完整 capture 可在实时和离线检查间迁移。 |
| 多窗口协议描述 | ✅ 已完成 | 2026-07-07 00:33:48 +0800 | `e0aac15` | capture 可描述应用进程下多个 window。 |
| 多窗口状态按窗口隔离 | ✅ 已完成 | 2026-07-07 00:34:40 +0800 | `f5caf7b` | selection 等 inspection 状态按当前窗口作用域保存。 |
| 捕获应用所有窗口 | ✅ 已完成 | 2026-07-07 00:36:11 +0800 | `deb39eb` | 采集 attached application windows。 |
| 多窗口同屏坐标系 | ✅ 已完成 | 2026-07-07 00:37:31 +0800 | `d9e3e0e` | 多窗口 bounds 位于同一屏幕坐标空间。 |
| 窗口选择与画布聚焦同步 | ✅ 已完成 | 2026-07-07 00:42:24 +0800 | `770deeb` | window selector 与 canvas-driven focus 同步。 |
| 多窗口 Canvas selection 集成 | ✅ 已完成 | 2026-07-07 07:20:45 +0800 | `eac2955` | Canvas selection 支持多窗口场景。 |
| v0.1.3 release line | 🚀 已发布 | 2026-07-07 07:22:31 +0800 | `7436d5d` | 准备 0.1.3 release line。 |
| 归档导入 canvas 颜色 ARGB 修复 | ✅ 已完成 | 2026-07-07 10:44:30 +0800 | `e4317bb` | 导入颜色保持 ARGB 空间。 |
| UI exports 跨版本兼容 | ✅ 已完成 | 2026-07-07 11:17:03 +0800 | `022c062` | inspection UI export 跨版本兼容。 |
| 层级 ID 与面板可见性显式控制 | ✅ 已完成 | 2026-07-07 11:35:18 +0800 | `a8f7b70` | Header/menu 中控制 hierarchy ids 与面板显示。 |
| Linux installer 发布 | 🚀 已发布 | 2026-07-07 11:43:42 +0800 | `bd657b8` | 发布 Linux DEB/RPM 安装包。 |
| v0.1.5 corrected release | 🚀 已发布 | 2026-07-07 11:49:41 +0800 | `f3d65ab` | 修正 release 至 0.1.5。 |
| Native packaging 与应用标识打磨 | ✅ 已完成 | 2026-07-07 14:22:31 +0800 | `b1ed8e0` | 原生打包和 app identity polish。 |
| v0.1.6 release | 🚀 已发布 | 2026-07-07 14:23:54 +0800 | `8773202` | 准备 0.1.6 release。 |
| Desktop icon identity | ✅ 已完成 | 2026-07-07 20:28:15 +0800 | `609a611` | 桌面图标身份可验证。 |
| Release workflow Java setup 升级 | ✅ 已完成 | 2026-07-07 23:14:24 +0800 | `9e45e5e` | setup-java 升级并修复版本断言。 |
| Package app 查找 adb 修复 | ✅ 已完成 | 2026-07-08 09:36:33 +0800 | `98459c8` | 打包 DMG 后也能解析/查找 adb，不依赖 shell PATH。 |
| 刷新/自动扫描工具栏控制与主题/语言 | 🚀 已发布 | 2026-07-08 10:22:59 +0800 | `79349e2` | v0.1.8：刷新按钮、自动扫描顺序、主题切换、多语言等 toolbar polish。 |
| Release artifact actions Node 24 支持 | ✅ 已完成 | 2026-07-08 10:39:14 +0800 | `afec040` | upload/download artifact actions 升级，避免 Node 20 deprecation warning。 |
| Canvas 命中候选排序、同点轮选、隐藏层级穿透 | ✅ 已完成 | 2026-07-08 15:28:44 +0800 | `062fbf8` | 小面积优先/Z 序可切换；保留同点轮选；隐藏层级作为强制穿透手段。 |

## 规划/未完成能力

| 功能/模块 | 状态 | 记录时间 | Commit/来源 | 说明 |
| --- | --- | --- | --- | --- |
| Android Studio 插件方案 | ⏳ 未开始 | 2026-07-02 22:00:39 +0800 | `3a59003` / `README.md` | 仓库中为规划占位，尚未开发。 |
| Web UI + App 内 HTTP Server 方案 | ⏳ 未开始 | 2026-07-02 22:00:39 +0800 | `3a59003` / `README.md` | 仓库中为规划占位，尚未开发。 |
| Compose semantics 支持 | ⏳ 未开始 | README 当前 scope | `desktop-viewer/README.md` | 当前 scope 标注为 future work。 |
| Report persistence | ⏳ 未开始 | README 当前 scope | `desktop-viewer/README.md` | 当前 scope 标注为 future work。 |
| Timeline diff | ⏳ 未开始 | README 当前 scope | `desktop-viewer/README.md` | 当前 scope 标注为 future work。 |
| 多设备选择 | ⏳ 未开始 | README 当前 scope | `desktop-viewer/README.md` | 当前 live path 仍以一台授权设备为目标。 |

## Release 记录

| 版本/标签 | 状态 | 提交时间 | Commit | 说明 |
| --- | --- | --- | --- | --- |
| v0.1.2 | 🚀 已发布 | 2026-07-06 23:22:41 +0800 | `3c0996c` | 准备下一 installer release。 |
| v0.1.3 | 🚀 已发布 | 2026-07-07 07:22:31 +0800 | `7436d5d` | release line。 |
| v0.1.4 | 🚀 已发布 | 2026-07-07 11:43:42 +0800 | `bd657b8` | Linux installers。 |
| v0.1.5 | 🚀 已发布 | 2026-07-07 11:49:41 +0800 | `f3d65ab` | corrected release。 |
| v0.1.6 | 🚀 已发布 | 2026-07-07 14:23:54 +0800 | `8773202` | native packaging/app identity polish 后发布。 |
| v0.1.7 | 🚀 已发布 | 2026-07-07 23:14:24 +0800 | `9e45e5e` | release workflow / version assertion 更新。 |
| v0.1.8 | 🚀 已发布 | 2026-07-08 10:22:59 +0800 | `79349e2` | toolbar refresh/theme/language polish。 |

## 维护规则

新增功能合入时，请在对应模块下补充：

1. 功能名称。
2. 状态：✅ 已完成 / 🚀 已发布 / 📝 设计/规划 / ⏳ 未开始。
3. 提交时间：使用 `git log --date=iso -1 <commit>`。
4. Commit 短 SHA。
5. 一句话说明边界与限制。
