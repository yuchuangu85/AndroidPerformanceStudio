# GPU Inspector

## 功能介绍

GPU Inspector 集成 Android GPU Inspector（AGI）的发现、工具链、artifact 索引和校验，并支持把 GPU trace 交给 Trace Analyzer 继续分析。

## 详细设计方案

```text
SDK/AGI discovery
  -> toolchain validation
  -> artifact index + checksum
  -> launch/import AGI workflow
  -> optional Perfetto trace handoff
```

### 模块职责

- `gpu-inspector-integration/agi-toolchain`：AGI 可执行文件和版本发现。
- `agi-artifact-index`：artifact、ABI、版本、hash 和路径索引。
- `gpu-integration-model`：GPU capture context、artifact metadata 和能力状态。
- `gpu-integration-app` / `presentation`：工具发现、导入、校验和打开操作。

## 使用方案

1. 配置或发现 Android GPU Inspector。
2. 查看当前工具版本、设备和 ABI 能力。
3. 导入或选择 GPU capture artifact。
4. 执行 hash、结构和版本校验。
5. 使用 AGI 查看 GPU 专项信息，或将 trace 交给 Trace Analyzer。

## 输出与限制

- APS 负责发现、索引、验证和跨工具入口，不复制 AGI 的全部图形界面。
- GPU artifact 的可读性依赖 AGI 版本、设备驱动、ABI 和产物完整性。
- Trace Analyzer handoff 是关联入口，不改变 GPU 原始证据的来源。
