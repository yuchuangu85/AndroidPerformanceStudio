# GPU Inspector

## 功能定位

GPU Inspector 负责 AGI 工具发现、GPU artifact 索引、SHA-256/大小校验、设备图形上下文记录和外部 AGI/Trace Analyzer 入口。它不是 AGI 解码器，也不替代 AGI 的完整 GPU UI 或 GPU capture 采集器。

当前状态：

- **已接入**：本地 artifact 导入、分类、持久 catalog、校验/重定位/移除、设备图形元数据、AGI version/help 探测和受控打开路由。
- **条件能力**：AGI 可执行文件、help 参数、设备驱动、ABI、artifact 完整性和外部工具版本决定能否真正打开。
- **未完成验收**：真实 AGI capture、所有产物打开矩阵、跨平台打包和完整 GPU 专项分析仍开放。

## 功能点

- artifact 分类：AGI system/frame profile、Perfetto trace、截图、外部报告和未知类型。
- 记录大小、SHA-256、类型、路径、可选设备/包/API/AGI 版本、备注和 warning。
- catalog 持久化并按 SHA-256 去重；同一内容多路径合并为多个已验证位置。
- 状态：Available、Missing、SizeChanged、ContentChanged、Unreadable。
- verify、reveal、relocate、remove from catalog；移除 catalog 不删除用户源文件。
- 通过 ADB 读取型号、Android/API、GPU vendor/renderer、driver、OpenGL ES、Vulkan 和图形实现。
- AGI 按保存偏好、PATH、平台默认发现，探测 `--version`/`--help` 并只接受 help 明确广告的参数。
- AGI 状态区分 VerifiedCli、GuiOnly、Unsupported；只有明确支持 `--open-artifact`/`--artifact` 才自动传入产物。
- Perfetto GPU frequency/memory/render stage 可以通过受控查询摘要，但不等于替代 AGI。

## 原理与数据流

```text
AGI discovery / ADB graphics context / local artifact
  -> bounded header/type/hash probe
  -> persistent GPU catalog + validation state
  -> explicit AGI argv or Trace handoff
  -> external viewer / controlled Perfetto summary
```

当前 Rust 入口：

- `rust-desktop/crates/aps-app/src/import/gpu`：catalog、页面、工具链和 panel。
- `rust-desktop/crates/aps-gpu`：artifact 元数据、图形上下文、校验和 Perfetto GPU 模型。

捕获时间必须来自 artifact/设备证据；文件 mtime 只用于文件状态，不能冒充 GPU capture 时间。relocate 必须同时匹配大小和 SHA-256。

## 使用方法

1. 在 GPU Inspector 选择或导入 artifact。
2. 查看类型、大小、digest、设备/包/API/AGI 元数据和状态。
3. 执行 Verify；文件变更后先重新定位/重新导入，不继续使用旧缓存。
4. 配置或发现 AGI，查看 version/help 能力状态。
5. 若 CLI 契约明确支持，使用 Open Artifact；否则启动 AGI GUI 后在外部工具打开。
6. 对 Perfetto trace 选择 Trace Analyzer handoff；对 GPU 专项 profile 保留 AGI 作为原始解释工具。

## 证据边界与限制

- artifact 被索引不等于 AGI 能解码；校验通过只证明文件身份/完整性。
- AGI GUI 启动成功不等于产物打开成功；两者状态分开。
- GPU vendor/driver/context 是设备环境证据，不是某个 App GPU 时间的因果证明。
- Perfetto GPU frequency/render stage 是受控摘要，不是完整 AGI pipeline 分析。
- catalog 移除不删除源文件，重新定位必须匹配 digest。

## 故障排查

- artifact ContentChanged：源文件发生变化，重新导入或定位，不继续使用旧结果。
- AGI GuiOnly：help 未声明安全的 artifact argv，必须在外部 GUI 操作。
- 图形上下文缺失：检查 ADB、设备权限、驱动/API 支持和命令输出；不要用系统默认值补齐。
- Trace handoff 无结果：确认 trace 文件完整、Trace Processor 资源和对应 GPU 数据源可用。
