# AndroidPerfermanceStudio

Android 布局复杂度检测工具的多形态仓库。

## 方案目录

| 目录 | 形态 | 当前状态 |
| --- | --- | --- |
| [`android-studio-plugin/`](android-studio-plugin/) | Android Studio 插件 | 规划占位，尚未开发 |
| [`desktop-viewer/`](desktop-viewer/) | Compose Desktop 独立应用 | 已有第一阶段可运行纵向版本 |
| [`web-ui-http-server/`](web-ui-http-server/) | Web UI + App 内 HTTP Server | 规划占位，尚未开发 |

总体规划参见 [`docs/layout-complexity-inspector-three-solutions-plan.md`](docs/layout-complexity-inspector-three-solutions-plan.md)。

## 当前工程边界

现有代码只实现 **Desktop 方案**。协议模型、分析引擎和 Android Debug Agent 虽然按可复用边界设计，但目前只由 Desktop 方案消费，因此与其 Gradle 工程一起放在 `desktop-viewer/` 中。

当 Android Studio 插件或 Web 方案开始实际复用这些模块后，再将稳定公共模块提升为仓库级独立构建，避免提前制造名义上的“共享内核”。

## 运行 Desktop 方案

```bash
cd desktop-viewer
./gradlew :desktop-app:run
```
