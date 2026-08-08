# ADR-0001: 泄漏检测以自研引用链分析为主引擎，LeakCanary/Shark 为可选备用

Find Memory Leaks 的分析引擎，我们选自研的支配树 + 引用链分析（已在 WIP 中实现：`DominatorTreeAnalyzer`、`ReferenceChainFinder`、`LeakWhitelist`、置信度评分），而非直接嵌入 LeakCanary 的 Shark 库作为主引擎。

**Why**：自研引擎与现有 `HeapDump` 数据模型同构、不依赖设备端预装、置信度与白名单可定制，且已是分阶段交付里 Phase A 的既定基础。LeakCanary/Shark 保留为**可插拔的备用分析引擎**，用于对自研结果做交叉校验。

**Considered Options**：
- 自研分析（采用）——与 HeapDump 模型同构、完全可控、不引入外部依赖。
- 直接嵌入 Shark 作为主引擎——成熟、经过 LeakCanary 社区验证，但强绑定其模型与置信度逻辑，且与现有 WIP 重复。
