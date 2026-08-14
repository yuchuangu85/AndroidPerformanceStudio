# 待办事项

本文件只索引已经形成设计、但尚未实施的工程事项。具体范围和验收标准以链接的设计文档为准。

## 待实施

- [ ] [将 AOSP Winscope 改为源码驱动的增量构建](../desktop-viewer/docs/design/2026-08-14-aosp-winscope-source-build-design.md) — 引入固定版本源码，以源码为事实来源；Gradle 在源码变化时重建浏览器产物，未变化时复用经过校验的预编译缓存，并由 CI 保证源码、manifest 与发布资源一致。
