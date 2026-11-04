# 页面 02：Layout Inspector

![Layout Inspector 设计参考](images/02-layout-inspector.png)

## 7.1 当前支撑度

当前实现与目标页面高度一致，主要是视觉重排和统一组件迁移。

现有关键能力：

- Device selector；
- capture target；
- window selector；
- manual/auto scan；
- hierarchy tree；
- hierarchy search；
- isolation；
- hidden layer；
- screenshot/layout canvas；
- zoom/pan/scroll；
- properties；
- findings；
- archive import/export；
- source correlation；
- AI analysis；
- Memory / Frame 等跨工具关联入口。

## 7.2 目标布局

```text
Feature Toolbar
Device | Target | Window | Capture | Auto | Import | Export | Search

┌────────────────┬──────────────────────────────┬───────────────────────┐
│ Hierarchy      │ Canvas                       │ Properties            │
│ Search         │ Screenshot                   │ Overview / Layout      │
│ Tree           │ Bounds Overlay               │ Draw / Accessibility   │
│                │ Zoom/Pan                     │ Compose properties     │
├────────────────┴──────────────────────────────┴───────────────────────┤
│ Findings / Timeline / Diff                                             │
└────────────────────────────────────────────────────────────────────────┘
```

## 7.3 UI 改造原则

### 左侧 Hierarchy

- 宽度：280–360dp，可拖拽；
- Search 固定顶部；
- 选中行、hover、hidden、search match 使用 Design System token；
- Disclosure / layer visibility / ID 等行为全部保留；
- 大列表继续虚拟化，不改为普通 Column。

### 中央 Canvas

- Canvas 为最大空间；
- Canvas toolbar 只保留与画布相关操作：Fit、Zoom、Mode、Layers；
- Hidden layer summary 使用 chip/banner，不占大块内容；
- Screenshot / Layout-only / Waiting 状态视觉统一。

### 右侧 Properties

将当前详情内容按 tab/section 组织：

```text
Overview
Layout
Drawing
Accessibility
Compose（有证据时）
Evidence
```

但底层字段仍来自当前 node/detail 模型。

### Bottom Findings

- 默认 220–280dp；
- 可折叠；
- severity + rule + evidence；
- 点击 Finding 同步 selected node；
- AI 建议必须区分 AI conclusion 与 rule evidence。

## 7.4 需要改动的源码

现有：

```text
layout-inspector/presentation/.../LayoutInspectorMainPage.kt
HierarchySearchState.kt
HiddenLayerState.kt
FindingSelectionState.kt
...
```

建议把大文件逐步抽出 UI 子组件，但**不要同时改业务逻辑**：

```text
presentation/workspace/
  LayoutInspectorWorkspace.kt
  HierarchyPanel.kt
  InspectorCanvasPanel.kt
  PropertiesPanel.kt
  FindingsPanel.kt
```

## 7.5 当前落地与布局说明（2026-10-20）

- 中间工作区（Hierarchy / Canvas / Properties / Findings）移除了左右和底部的 8dp 页面级外边距，内容直接贴齐窗口内容区边缘。
- 顶部 Header、Correlation 提示和水平分隔线保持不变；各 pane 自身的边框、列表和 Canvas 绘制不变。

## 7.6 验收

- 所有现有 Layout Inspector 测试继续通过；
- 树/Canvas/Properties/Findings selection 同步行为不变；
- Hidden layer/Isolation/Search 快捷键不退化；
- archive import/export 行为不变；
- resize 后 pane state 稳定；
- 10k node 场景不因 UI 包装出现明显性能回退。

---
