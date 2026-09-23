# 桌面端公共控件与主题

## 模块边界

`desktop-viewer/ui-components` 是供各功能页共享的 Compose Desktop UI 模块。
功能模块依赖公共控件，不在各页面复制同类控件或定义独立的全局明暗配色；
采集、解析和 ADB 等非 UI 模块不依赖该模块。

| 入口 | 用途 |
| --- | --- |
| `ViewerTheme.kt` | `ViewerTheme`、`LocalViewerColors`、`ViewerTypography`、`ViewerDimensions`；集中定义配色、排版和控件尺寸 |
| `HeaderToolbar.kt` | 功能页统一顶部工具栏和返回首页入口 |
| `DropdownSelector.kt` | 通用下拉选择；应用/进程列表可通过 `searchable = true` 开启输入过滤 |
| `SegmentedSelector.kt` | 类似参考图的胶囊式**单选**控件，支持 2、3 和更多选项 |
| `button/`、`radiobutton/`、`switch/`、`search/` | 按控件类型组织的按钮、选择项、开关和搜索控件 |
| `UiLanguage.kt`、`LocalizedStringResources.kt` | 显式语言选择及资源文案，不依赖操作系统当前语言 |

## SegmentedSelector 使用约定

- 使用受控状态：`selectedItem` 由调用方保存，点击非选中项时通过
  `onItemSelected` 通知调用方；再次点击当前项不会重复回调。
- 选中项绘制圆角高亮，所有项采用相同的文字层级；轨道和高亮分别从
  `ViewerColors.segmentedTrack`、`ViewerColors.segmentedSelected` 获取，
  不把参考图的绿色固定到控件中。
- 支持 `enabled` 和 `itemEnabled`；通过单选语义及 `selectorDescription` 提供无障碍信息。
- 默认最大宽度来自 `ViewerDimensions.segmentedMaxWidth`，超过宽度后可水平滚动；
  如页面需要更宽，传入 `maxWidth`。
- 选项文案由页面提供 `itemLabel`；组件不维护功能页业务状态，也不强制一个默认选项。

```kotlin
var selected by remember { mutableStateOf("画面") }
SegmentedSelector(
    items = listOf("首页", "画面", "佳节", "时间", "更多"),
    selectedItem = selected,
    onItemSelected = { selected = it },
    itemLabel = { it },
    selectorDescription = "内容分类",
)
```

相同 API 可用于两项（如“列表 / 网格”）和三项（如“全部 / 活动 / 已完成”）。
只有在 UI 确实需要互斥选择时使用此控件，不替换列表筛选、复选或多选控件。

## 主题与视觉约束

页面入口用 `ViewerTheme(darkTheme, displayScale, accentColor)`；
对话框按需使用 `rememberViewerThemeContext()` / `ProvideViewerThemeContext()` 继承主题。
公共控件从 `LocalViewerColors.current` 读取颜色，从 `ViewerTypography` 取字体，
从 `ViewerDimensions` 取高度与圆角等尺寸。新增共享视觉常量时先归入
`ViewerTheme.kt`，而不是在功能页散落固定色值。
控件覆盖色仅用于明确的局部需求，不应代替统一主题。

参考图用于对齐胶囊轮廓、项间距和被选中项的层次；绿色按页面背景处理，
不在公共控件中硬编码为固定品牌色。
