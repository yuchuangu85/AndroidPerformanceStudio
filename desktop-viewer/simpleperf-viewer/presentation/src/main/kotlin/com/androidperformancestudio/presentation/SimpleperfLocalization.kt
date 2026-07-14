@file:Suppress(
    "FunctionName",
    "LongParameterList",
    "MatchingDeclarationName",
    "MaxLineLength",
    "ReturnCount",
)

package com.androidperformancestudio.presentation

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.material3.Text as MaterialText

enum class SimpleperfLanguage {
    SIMPLIFIED_CHINESE,
    ENGLISH,
}

private val LocalSimpleperfLanguage = staticCompositionLocalOf { SimpleperfLanguage.ENGLISH }

@Composable
internal fun SimpleperfLocalization(
    language: SimpleperfLanguage,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalSimpleperfLanguage provides language, content = content)
}

@Composable
internal fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    MaterialText(
        text = translateSimpleperfText(text, LocalSimpleperfLanguage.current),
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        onTextLayout = onTextLayout,
        style = style,
    )
}

internal fun translateSimpleperfText(
    text: String,
    language: SimpleperfLanguage,
): String {
    if (language == SimpleperfLanguage.ENGLISH) return text
    CHINESE_TEXT[text]?.let { return it }
    CHINESE_PREFIXES.firstOrNull { text.startsWith(it.first) }?.let { (english, chinese) ->
        return chinese + text.removePrefix(english)
    }
    INC_EXC_PATTERN.matchEntire(text)?.let { match ->
        return "包含 ${match.groupValues[1]} · 独占 ${match.groupValues[2]}"
    }
    EVERY_EVENTS_PATTERN.matchEntire(text)?.let { match ->
        return "每 ${match.groupValues[1]} 个事件"
    }
    RENDERED_FRAMES_PATTERN.matchEntire(text)?.let { match ->
        return "已渐进渲染 ${match.groupValues[1]} 个可见帧"
    }
    if (text.endsWith(" hotspot")) {
        return translateSimpleperfText(text.removeSuffix(" hotspot"), language) + "热点"
    }
    if (text.startsWith("• ")) {
        return "• " + translateSimpleperfText(text.removePrefix("• "), language)
    }
    return text
}

private val CHINESE_PREFIXES =
    listOf(
        "Loading " to "正在加载 ",
        "Language: " to "语言：",
        "Theme: " to "主题：",
        "Selected target: " to "已选目标：",
        "ABI: " to "ABI：",
        "Root: " to "Root：",
        "Scope: " to "范围：",
        "Simpleperf: " to "Simpleperf：",
        "Events: " to "事件：",
        "Limits: " to "限制：",
        "Event: " to "事件：",
        "Rate: " to "采样率：",
        "Duration: " to "时长：",
        "Call graph: " to "调用图：",
        "Lost samples: " to "丢失样本：",
        "Unwind errors: " to "回溯错误：",
        "Unknown symbols: " to "未知符号：",
        "Empty stacks: " to "空调用栈：",
        "Completed: " to "已完成：",
        "Inclusive " to "包含 ",
        "Exclusive " to "独占 ",
        "Render next " to "继续渲染 ",
    )

private val CHINESE_TEXT =
    mapOf(
        "Device & Target" to "设备与目标",
        "Select an Android device and a profile target." to "选择 Android 设备和性能采集目标。",
        "Open Session" to "打开会话",
        "Refreshing…" to "刷新中…",
        "Refresh" to "刷新",
        "Continue to Capture" to "继续采集",
        "Devices" to "设备",
        "No USB devices found." to "未发现 USB 设备。",
        "Online" to "在线",
        "Unavailable" to "不可用",
        "Device capability" to "设备能力",
        "Select an online device to inspect its capabilities." to "选择在线设备以检查其能力。",
        "Apps / Processes / PID / Threads" to "应用 / 进程 / PID / 线程",
        "Search package, process, user or PID" to "搜索包名、进程、用户或 PID",
        "Apps" to "应用",
        "Processes" to "进程",
        "Threads" to "线程",
        "Capture Configuration" to "采集配置",
        "Back to Device & Target" to "返回设备与目标",
        "Sampling template" to "采样模板",
        "Advanced parameters" to "高级参数",
        "Event" to "事件",
        "Frequency" to "频率",
        "Period" to "周期",
        "Events per sample" to "每次采样事件数",
        "Duration seconds (blank = manual stop)" to "持续秒数（留空表示手动停止）",
        "Call graph" to "调用图",
        "Scope" to "范围",
        "Capture status" to "采集状态",
        "Stop and analyze" to "停止并分析",
        "Cancel" to "取消",
        "Start capture" to "开始采集",
        "Parameters" to "参数",
        "Manual stop" to "手动停止",
        "Command preview" to "命令预览",
        "Copy command" to "复制命令",
        (
            "Preview and execution share the same argument list. Template values are shown above; " +
                "device capability replacements will be applied by the tool selection stage."
        ) to
            "预览与执行使用相同的参数列表。上方显示模板值；工具选择阶段会根据设备能力进行替换。",
        "None" to "无",
        "Ready to capture" to "可以开始采集",
        "Preparing simpleperf…" to "正在准备 simpleperf…",
        "Recording…" to "采集中…",
        "Stopping gracefully…" to "正在安全停止…",
        "Pulling perf.data…" to "正在拉取 perf.data…",
        "Capture cancelled; logs were retained" to "采集已取消；日志已保留",
        "App CPU Basic" to "应用 CPU 基础",
        "General-purpose app CPU hotspot sampling." to "通用的应用 CPU 热点采样。",
        "UI Thread Focus" to "UI 线程聚焦",
        "High-frequency sampling for a selected UI thread." to "对选定 UI 线程进行高频采样。",
        "Native Hotspot" to "Native 热点",
        "CPU cycle sampling for native computation hotspots." to "针对 Native 计算热点进行 CPU 周期采样。",
        "Low Overhead" to "低开销",
        "Reduced frequency with frame-pointer call graphs." to "降低频率并使用帧指针调用图。",
        "System Process" to "系统进程",
        "Conservative sampling for a root-accessible system process." to "对可通过 root 访问的系统进程进行保守采样。",
        "Back" to "返回",
        "Close report" to "关闭报告",
        "Session package" to "会话包",
        "Raw protobuf" to "原始 protobuf",
        "Screenshot" to "截图",
        "External open guide" to "外部打开指南",
        "Overview" to "概览",
        "Timeline" to "时间线",
        "Top functions" to "热门函数",
        "Call tree" to "调用树",
        "Flame graph" to "火焰图",
        "Diagnostics" to "诊断",
        "Samples" to "样本",
        "Event weight" to "事件权重",
        "Lost rate" to "丢失率",
        "Data quality" to "数据质量",
        "Top threads" to "热门线程",
        "Artifacts" to "产物",
        "Reset range" to "重置范围",
        "Drag across the timeline to select a range. W/S zoom, A/D pan, Ctrl+wheel zooms." to
            "在时间线上拖动以选择范围。W/S 缩放，A/D 平移，Ctrl+滚轮缩放。",
        "Thread filter" to "线程筛选",
        "Search function or library" to "搜索函数或库",
        "Descending" to "降序",
        "Ascending" to "升序",
        "Function / Library" to "函数 / 库",
        "Inclusive" to "包含",
        "Exclusive" to "独占",
        "Navigate" to "导航",
        "Path" to "路径",
        "Flame" to "火焰图",
        "Call Tree" to "调用树",
        "Reverse Call Tree" to "反向调用树",
        "Find function in call paths" to "在调用路径中查找函数",
        "MATCH" to "匹配",
        "Reset" to "重置",
        "Click a frame to drill down; double-click the canvas to reset. Search matches are highlighted." to
            "单击帧以深入查看；双击画布重置。搜索匹配项会高亮显示。",
        "Recommendations" to "建议",
        "Click to inspect evidence" to "单击查看证据",
        "System" to "跟随系统",
        "Light" to "浅色",
        "Dark" to "深色",
        "Language" to "语言",
        "Theme" to "主题",
        "Simplified Chinese" to "简体中文",
        "English" to "英文",
        "READY" to "就绪",
        "LIMITED" to "受限",
        "BLOCKED" to "不可用",
        "INFO" to "信息",
        "WARNING" to "警告",
        "CRITICAL" to "严重",
        "FRAME POINTER" to "帧指针",
        "DWARF" to "DWARF",
        "USER" to "用户态",
        "KERNEL" to "内核态",
        "BOTH" to "全部",
        "Widths and percentages are sample/event weights; they are not exact wall-clock durations." to
            "宽度和百分比表示样本/事件权重，并非精确的墙上时钟耗时。",
        "No lost samples, unwind failures, unknown symbols, or empty stacks were detected." to
            "未检测到丢失样本、回溯失败、未知符号或空调用栈。",
        "Profile quality issues can reduce confidence in hotspot and call-chain results." to
            "Profile 质量问题会降低热点和调用链结果的可信度。",
        "Lost samples" to "丢失样本",
        "Unwind failures" to "回溯失败",
        "Unknown symbols" to "未知符号",
        "Empty stacks" to "空调用栈",
        "Function" to "函数",
        "Library" to "库",
        "Inclusive share" to "包含占比",
        "Exclusive weight" to "独占权重",
        "Thread" to "线程",
        "Weight share" to "权重占比",
        "Sample weight semantics" to "样本权重语义",
        "Sample/event weights are statistical evidence, not exact wall-clock durations." to
            "样本/事件权重是统计证据，而不是精确的墙上时钟耗时。",
        "Keep the raw profile and capture metadata for reproducible analysis." to
            "保留原始 Profile 和采集元数据，以便复现分析。",
        "Reduce sampling frequency if loss is high." to "如果丢失率较高，请降低采样频率。",
        "Provide binary_cache, unstripped libraries, and ProGuard mapping for symbols." to
            "提供 binary_cache、未剥离符号的库以及 ProGuard mapping。",
        "Use DWARF call graphs when frame-pointer unwinding is incomplete." to
            "帧指针回溯不完整时使用 DWARF 调用图。",
        "Open the flame graph and inspect callers and callees around this function." to
            "打开火焰图，检查该函数周围的调用方和被调用方。",
        "Re-capture the same workload after optimization and compare sample weights." to
            "优化后重新采集相同负载并比较样本权重。",
        "Filter the timeline to this thread and inspect its top functions." to
            "将时间线筛选到该线程并检查其热门函数。",
        "Move avoidable blocking or CPU-heavy work away from latency-sensitive threads." to
            "将可避免的阻塞或 CPU 密集工作移出延迟敏感线程。",
        "Use repeated captures and comparable workloads before drawing performance conclusions." to
            "在得出性能结论前，请使用可比较负载进行多次采集。",
    )

private val INC_EXC_PATTERN = Regex("inc (.+) · exc (.+)")
private val EVERY_EVENTS_PATTERN = Regex("every (.+) events")
private val RENDERED_FRAMES_PATTERN = Regex("Rendered (.+) visible frames progressively")
