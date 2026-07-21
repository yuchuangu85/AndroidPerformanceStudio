package dev.agentperf.desktop

import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.math.roundToInt

internal class ViewerStrings private constructor(
    val language: ViewerLanguage,
) {
    private val chinese: Boolean
        get() = language == ViewerLanguage.SIMPLIFIED_CHINESE

    val settings: String get() = text("Settings", "设置")
    val layoutInspectorSettings: String
        get() = text("Layout Inspector Settings", "布局检查器设置")
    val canvasBorderColors: String get() = text("Canvas border colors", "画布边框颜色")
    val defaultViewBoundsColor: String get() = text("Default view bounds", "默认视图边框")
    val hoveredViewBoundsColor: String get() = text("Hovered view bounds", "鼠标悬停边框")
    val selectedViewBoundsColor: String get() = text("Selected view bounds", "点击选中边框")
    val captureArchive: String get() = text("Capture archive", "捕获归档")
    val layoutSnapshotArchiveLimit: String
        get() = text("Layout snapshot size limit", "布局快照大小上限")
    val layoutSnapshotArchiveLimitHint: String
        get() = text(
            "Adjusts import and export limits from 32 to 320 MiB; higher limits may use more memory.",
            "可将导入和导出上限从 32 MiB 调至 320 MiB；更高上限可能占用更多内存。",
        )
    val reset: String get() = text("Reset", "恢复默认")
    val actions: String get() = text("Actions", "操作")
    val view: String get() = text("View", "视图")
    val file: String get() = text("File", "文件")
    val importArchive: String get() = text("Import archive", "导入归档")
    val importScreenshot: String get() = text("Import screenshot", "导入截图")
    val exportArchive: String get() = text("Export", "导出")
    val chooseArchiveToImport: String
        get() = text("Choose archive to import", "选择要导入的归档")
    val chooseArchiveExportFile: String
        get() = text("Choose export file", "选择导出文件")
    val chooseScreenshotToImport: String
        get() = text("Choose screenshot to import", "选择要导入的截图")
    val importArchiveSucceededTitle: String
        get() = text("Import succeeded", "导入成功")
    val importArchiveFailedTitle: String
        get() = text("Import failed", "导入失败")
    val importScreenshotSucceededTitle: String
        get() = text("Screenshot imported", "截图已导入")
    val importScreenshotFailedTitle: String
        get() = text("Screenshot import failed", "截图导入失败")
    val exportArchiveSucceededTitle: String
        get() = text("Export succeeded", "导出成功")
    val exportArchiveFailedTitle: String
        get() = text("Export failed", "导出失败")
    val hideInvisibleHierarchyViews: String
        get() = text(
            "Hide invisible views in hierarchy",
            "隐藏层级结构中的不可见视图",
        )
    val hideInvisibleFindings: String
        get() = text(
            "Hide invisible-view findings",
            "隐藏问题列表中的不可见视图内容",
        )
    val hideHierarchyIndices: String
        get() = text("Hide hierarchy indices", "隐藏层级索引")
    val showHierarchyLayerVisibilityButtons: String
        get() = text(
            "Show visibility buttons in hierarchy",
            "显示层级结构中的显示按钮",
        )
    val showVisibleViewBounds: String
        get() = text("Show all visible view bounds", "显示全部可见视图边缘")
    val dismiss: String get() = text("OK", "确定")
    val autoScan: String get() = text("Auto scan", "自动扫描")
    val autoDevice: String get() = text("Auto device", "自动设备")
    val window: String get() = text("Window", "窗口")
    val selectWindow: String get() = text("Select window", "选择窗口")
    val refresh: String get() = text("Refresh", "刷新")
    val refreshOnce: String get() = text("Refresh once", "刷新一次")
    val hierarchy: String get() = text("HIERARCHY", "层级结构")
    val canvas: String get() = text("CANVAS", "画布")
    val properties: String get() = text("PROPERTIES", "属性")
    val findings: String get() = text("FINDINGS", "问题")
    val runAiAnalysis: String get() = text("Run AI", "AI 分析")
    val aiAnalysisRunning: String get() = text("AI running…", "AI 分析中…")
    val noLiveFrame: String get() = text("No live frame", "无实时画面")
    val waitingForFrame: String get() = text("Waiting for live device frame", "等待设备实时画面")
    val noFindings: String get() = text("No findings", "暂无问题")
    fun aiAnalysisSummary(summary: String): String = text("AI: $summary", "AI：$summary")
    fun aiAnalysisFailed(message: String): String = text("AI failed: $message", "AI 失败：$message")
    val timelineLiveCapture: String get() = text("TIMELINE  Live capture", "时间线  实时采集")
    val noApp: String get() = text("No app", "无应用")
    val noAvailableWindows: String get() = text("No available windows", "无可用窗口")
    val noSnapshotLoaded: String get() = text("No snapshot loaded", "尚未加载布局快照")
    val disconnected: String get() = text("Disconnected", "未连接")
    val connecting: String get() = text("Connecting", "连接中")
    val live: String get() = text("Live", "实时")
    val offlineArchive: String get() = text("Offline archive", "离线归档")
    val connectionFailed: String get() = text("Connection failed", "连接失败")
    val unavailable: String get() = text("Unavailable", "不可用")
    val appOnlyOn: String get() = text("APP ONLY ON", "仅应用 开")
    val appOnlyOff: String get() = text("APP ONLY OFF", "仅应用 关")
    val smallAreaHitTesting: String get() = text("SMALL HITS", "小面积优先")
    val zOrderHitTesting: String get() = text("Z-ORDER HITS", "Z 序优先")
    val hideLayer: String get() = text("Hide", "隐藏")
    val showLayer: String get() = text("Show", "显示")
    val toggleLayerVisibility: String
        get() = text(
            "Toggle this layer in canvas hit testing",
            "切换此层级是否参与画布命中测试",
        )
    val searchHierarchy: String get() = text("Search hierarchy", "搜索层级")
    val searchPrevious: String get() = text("Previous match", "上一个匹配")
    val searchNext: String get() = text("Next match", "下一个匹配")
    val searchNoMatch: String get() = text("No match", "无匹配")

    fun hiddenLayerSummary(count: Int): String = text(
        "Hidden $count · Clear",
        "已隐藏 $count · 清除",
    )

    fun archiveImportSucceeded(path: String): String = text(
        "Archive imported:\n$path",
        "归档已导入：\n$path",
    )

    fun archiveImportFailed(message: String): String = text(
        "Unable to import archive:\n$message",
        "无法导入归档：\n$message",
    )

    fun screenshotImportSucceeded(path: String): String = text(
        "Screenshot imported for the current layout:\n$path",
        "已为当前布局导入截图：\n$path",
    )

    fun screenshotImportFailed(message: String): String = text(
        "Unable to import screenshot:\n$message",
        "无法导入截图：\n$message",
    )

    fun archiveExportSucceeded(
        path: String,
        rawArtifactsIncluded: Boolean,
    ): String =
        if (rawArtifactsIncluded) {
            text(
                "Archive exported:\n$path",
                "归档已导出：\n$path",
            )
        } else {
            text(
                "Archive exported without raw Visible Window Views attachments:\n$path",
                "归档已导出，但未包含原始 Visible Window Views 附件：\n$path",
            )
        }

    fun archiveExportFailed(message: String): String = text(
        "Unable to export archive:\n$message",
        "无法导出归档：\n$message",
    )

    fun archiveLimitValue(limits: CaptureArchiveLimits): String =
        "${limits.maxSnapshotSizeMiB} MiB (${limits.snapshotSizeMultiplier}×)"

    fun connectionError(message: String): String {
        val deviceCount = authorizedDeviceCountError
            .matchEntire(message)
            ?.groupValues
            ?.get(1)
            ?: return message
        return text(
            message,
            "需要且只能连接一台已授权设备，当前检测到 $deviceCount 台",
        )
    }

    fun actionLabel(action: ViewerAction): String = when (action) {
        ViewerAction.TOGGLE_AUTO_SCAN -> autoScan
        ViewerAction.PREVIOUS_NODE -> text("Previous node", "上一个节点")
        ViewerAction.NEXT_NODE -> text("Next node", "下一个节点")
        ViewerAction.TOGGLE_SELECTED_NODE -> text("Collapse/expand node", "折叠/展开节点")
        ViewerAction.TOGGLE_HIERARCHY -> text("Hide left panel", "隐藏左侧栏")
        ViewerAction.TOGGLE_FINDINGS -> text("Hide bottom panel", "隐藏底部栏")
        ViewerAction.TOGGLE_DETAILS -> text("Hide right panel", "隐藏右侧栏")
        ViewerAction.TOGGLE_HIERARCHY_IDS -> text("Show layout IDs", "显示布局 ID")
        ViewerAction.OPEN_SETTINGS -> settings
    }

    fun viewOptionLabel(option: ViewDisplayOption): String = when (option) {
        ViewDisplayOption.HIDE_INVISIBLE_HIERARCHY_VIEWS -> hideInvisibleHierarchyViews
        ViewDisplayOption.HIDE_INVISIBLE_FINDINGS -> hideInvisibleFindings
        ViewDisplayOption.HIDE_HIERARCHY_INDICES -> hideHierarchyIndices
        ViewDisplayOption.SHOW_HIERARCHY_LAYER_VISIBILITY_BUTTONS -> showHierarchyLayerVisibilityButtons
        ViewDisplayOption.SHOW_VISIBLE_VIEW_BOUNDS -> showVisibleViewBounds
    }

    fun metrics(nodeCount: Int, maxDepth: Int, widestLevel: Int): String =
        if (chinese) {
            "$nodeCount 个节点 · 深度 $maxDepth · 最大宽度 $widestLevel"
        } else {
            "$nodeCount nodes · depth $maxDepth · width $widestLevel"
        }

    fun timelineDiff(added: Int, removed: Int, moved: Int): String =
        if (chinese) {
            "Δ +$added -$removed 移动 $moved"
        } else {
            "Δ +$added -$removed moved $moved"
        }

    val timelineBaseline: String get() = text("baseline", "基线")

    fun timelineFrameSummary(added: Int, removed: Int, moved: Int): String =
        if (chinese) {
            "+$added -$removed 移动 $moved"
        } else {
            "+$added -$removed moved $moved"
        }

    fun infoBadge(count: Int): String = text("INFO $count", "信息 $count")
    fun warningBadge(count: Int): String = text("WARN $count", "警告 $count")
    fun errorBadge(count: Int): String = text("ERROR $count", "错误 $count")

    fun findingTitle(ruleId: String): String = when (ruleId) {
        "layout.invisible-node" -> text("Invisible node", "不可见节点")
        "layout.excessive-children" -> text("Too many children", "子节点过多")
        "layout.overlapping-siblings" -> text("Overlapping sibling bounds", "兄弟节点区域重叠")
        "layout.deep-hierarchy" -> text("Deep hierarchy", "层级过深")
        else -> ruleId
    }

    fun findingMessage(
        ruleId: String,
        arguments: Map<String, String>,
        fallback: String,
    ): String = when (ruleId) {
        "layout.invisible-node" -> arguments["className"]?.let { className ->
            text(
                "$className exists but is currently invisible",
                "$className 节点存在但当前不可见",
            )
        }
        "layout.excessive-children" -> {
            val count = arguments["count"]
            val threshold = arguments["threshold"]
            if (count != null && threshold != null) {
                text(
                    "$count direct children, exceeding the threshold of $threshold",
                    "直接子节点数量 $count，超过阈值 $threshold",
                )
            } else {
                null
            }
        }
        "layout.overlapping-siblings" -> {
            val count = arguments["count"]
            val ratio = arguments["ratioPercent"]
            if (count != null && ratio != null) {
                text(
                    "$count siblings overlap by at least $ratio%; this is a structural rendering risk—confirm with GPU tools",
                    "$count 个兄弟节点的边界重叠比例至少为 $ratio%；这是结构性渲染风险，请使用 GPU 工具进一步确认",
                )
            } else {
                null
            }
        }
        "layout.deep-hierarchy" -> {
            val depth = arguments["depth"]
            val threshold = arguments["threshold"]
            if (depth != null && threshold != null) {
                text(
                    "Hierarchy depth $depth exceeds the threshold of $threshold",
                    "层级深度 $depth，超过阈值 $threshold",
                )
            } else {
                null
            }
        }
        else -> null
    } ?: fallback

    fun detailSection(english: String): String =
        if (!chinese) english else detailSectionsChinese[english] ?: english

    fun detailLabel(english: String): String =
        if (!chinese) english else detailLabelsChinese[english] ?: english

    fun noHighOverlapPairs(): String =
        text("No high-overlap child pairs · structural", "无高重叠子节点对 · 结构估算")

    fun overlapPairs(pairs: Int, maxRatio: Float): String =
        if (chinese) {
            "$pairs 对 · 最大 ${(maxRatio * 100).roundToInt()}% · 结构估算"
        } else {
            "$pairs ${if (pairs == 1) "pair" else "pairs"} · " +
                "max ${(maxRatio * 100).roundToInt()}% · structural"
        }

    fun subtreeComplexity(descendants: Int, depth: Int): String =
        text(
            "$descendants descendants · depth $depth",
            "$descendants 个后代 · 深度 $depth",
        )

    fun blending(alpha: Float): String =
        if (alpha < 1f) {
            text("Alpha $alpha requires blending", "Alpha $alpha 需要混合")
        } else {
            "Alpha 1.0"
        }

    private fun text(english: String, simplifiedChinese: String): String =
        if (chinese) simplifiedChinese else english

    companion object {
        fun forLanguage(language: ViewerLanguage): ViewerStrings = ViewerStrings(language)

        val English: ViewerStrings = ViewerStrings(ViewerLanguage.ENGLISH)
    }
}

internal val LocalViewerStrings = staticCompositionLocalOf { ViewerStrings.English }

private val authorizedDeviceCountError =
    Regex("""Expected exactly one authorized device, found (\d+)""")

private val detailSectionsChinese = mapOf(
    "RENDER RISKS" to "渲染风险",
    "IDENTITY" to "标识",
    "LAYOUT" to "布局",
    "DRAWING" to "绘制",
    "INTERACTION" to "交互",
    "RAW PROPERTIES" to "原始属性",
)

private val detailLabelsChinese = mapOf(
    "Class" to "类",
    "ID" to "ID",
    "Resource" to "资源",
    "Text" to "文本",
    "Content description" to "内容描述",
    "Bounds" to "边界",
    "Size" to "尺寸",
    "Local layout bounds" to "本地布局边界",
    "Local layout size" to "本地布局尺寸",
    "Visibility" to "可见性",
    "Tree depth" to "树深度",
    "Direct children" to "直接子节点",
    "Descendants" to "后代节点",
    "Subtree depth" to "子树深度",
    "Layout width" to "布局宽度",
    "Layout height" to "布局高度",
    "Layout params class" to "布局参数类",
    "Measured size" to "测量尺寸",
    "Minimum size" to "最小尺寸",
    "Padding" to "内边距",
    "Margin" to "外边距",
    "Scroll" to "滚动偏移",
    "Layout requested" to "请求布局",
    "Alpha" to "透明度",
    "Z" to "Z",
    "Elevation" to "高度",
    "Translation" to "位移",
    "Rotation" to "旋转",
    "Scale" to "缩放",
    "Pivot" to "轴心",
    "Background" to "背景",
    "Background color" to "背景色",
    "Foreground" to "前景",
    "Clip bounds" to "裁剪边界",
    "Clip children" to "裁剪子节点",
    "Clip to padding" to "裁剪到内边距",
    "Opaque" to "不透明",
    "Will not draw" to "不执行绘制",
    "Hardware accelerated" to "硬件加速",
    "Layer type" to "图层类型",
    "Enabled" to "启用",
    "Clickable" to "可点击",
    "Long clickable" to "可长按",
    "Focusable" to "可聚焦",
    "Focused" to "已聚焦",
    "Selected" to "已选中",
    "Overdraw estimate" to "过度绘制估算",
    "Subtree complexity" to "子树复杂度",
    "Hidden descendants" to "隐藏后代",
    "Blending" to "混合",
    "Layer cost" to "图层成本",
)
