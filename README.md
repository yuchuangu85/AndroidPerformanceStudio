# AndroidPerfermanceStudio

Android 布局复杂度检测工具的多形态仓库。

## 方案目录

| 目录 | 形态 | 当前状态 |
| --- | --- | --- |
| [`android-studio-plugin/`](android-studio-plugin/) | Android Studio 插件 | 规划占位，尚未开发 |
| [`desktop-viewer/`](desktop-viewer/) | Compose Desktop 独立应用 | 已有第一阶段可运行纵向版本 |
| [`web-ui-http-server/`](web-ui-http-server/) | Web UI + App 内 HTTP Server | 规划占位，尚未开发 |

总体规划参见 [`docs/layout-complexity-inspector-three-solutions-plan.md`](docs/layout-complexity-inspector-three-solutions-plan.md)。

Firefox Profiler 通过固定提交的 Git Submodule 存放在
[`third_party/firefox-profiler`](third_party/firefox-profiler)，独立构建说明见
[`third_party/README.md`](third_party/README.md)。选择 Firefox Profiler 本地引擎时，该前端不会
嵌入 Compose 窗口；应用仅在 `127.0.0.1` 提供本地页面和 profile 数据，并跳转到系统浏览器打开。
原 Firefox Profiler 引擎仍打开官方网站。

## 当前工程边界

现有代码只实现 **Desktop 方案**。Desktop 工程按功能收拢代码：Layout Inspector 位于
`desktop-viewer/layout-inspector/`，Simpleperf CPU Profiler 位于
`desktop-viewer/simpleperf-viewer/`。`desktop-viewer/desktop-app/` 只负责主入口、原生窗口和
打包，不承载 Layout Inspector 业务实现。协议模型、分析引擎和 Android Debug Agent 目前只由 Layout
Inspector 消费，因此保留在该功能目录内。

当 Android Studio 插件或 Web 方案开始实际复用这些模块后，再将稳定公共模块提升为仓库级独立构建，避免提前制造名义上的“共享内核”。

## 运行 Desktop 方案

```bash
cd desktop-viewer
./gradlew :desktop-app:run
```
