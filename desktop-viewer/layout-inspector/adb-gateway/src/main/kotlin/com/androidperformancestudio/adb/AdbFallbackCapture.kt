package com.androidperformancestudio.adb

import com.androidperformancestudio.protocol.AgentCapabilities
import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.CaptureFrame
import com.androidperformancestudio.protocol.ComposeNode
import com.androidperformancestudio.protocol.DisplayInfo
import com.androidperformancestudio.protocol.LayoutSnapshot
import com.androidperformancestudio.protocol.ProtocolCodec
import com.androidperformancestudio.protocol.CURRENT_PROTOCOL_VERSION
import com.androidperformancestudio.protocol.UiNode
import com.androidperformancestudio.protocol.ViewAttributes
import com.androidperformancestudio.protocol.ViewNode
import com.androidperformancestudio.protocol.WindowSnapshot
import com.androidperformancestudio.protocol.WindowType
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.NodeList

/**
 * Thrown when no ADB capture stage could produce a usable hierarchy. The message carries a
 * per-stage failure summary (visible-window-dump / visible-window-parse / uiautomator /
 * screenshot / encode) so users see why the capture failed instead of an opaque blanket
 * statement; the underlying cause is preserved for logs.
 */
class AdbFallbackUnavailableException(
    packageName: String,
    stageFailures: List<String> = emptyList(),
    cause: Throwable,
) : IllegalStateException(buildString {
    append("Unable to capture foreground app $packageName through ADB")
    if (stageFailures.isNotEmpty()) {
        append(" (")
        append(stageFailures.joinToString("; ").take(MAX_MESSAGE_LENGTH))
        append(")")
    }
}, cause)

private const val MAX_MESSAGE_LENGTH = 500

/** Collects one reason per capture stage; the latest attempt of each stage wins. */
internal class CaptureStageFailures {
    private val stages = linkedMapOf<String, String>()

    fun record(stage: String, detail: Throwable) = record(stage, detail.message ?: detail.javaClass.simpleName)

    fun record(stage: String, detail: String?) {
        val summary = detail.orEmpty().lineSequence().firstOrNull().orEmpty().trim()
        if (summary.isNotEmpty()) stages[stage] = summary.take(MAX_STAGE_DETAIL_LENGTH)
    }

    fun summaries(): List<String> =
        stages.map { (stage, detail) -> "$stage: $detail" }.ifEmpty { emptyList() }

    private companion object {
        const val MAX_STAGE_DETAIL_LENGTH = 120
    }
}

internal class AdbFallbackCapture(
    private val serial: String,
    private val packageName: String,
    private val processRunner: ProcessRunner,
    private val protocolCodec: ProtocolCodec = ProtocolCodec(supportedMajor = 1),
    private val retrySleeper: (Long) -> Unit = Thread::sleep,
) {
    fun capture(): CaptureFrame {
        val stageFailures = CaptureStageFailures()
        return try {
            val hierarchy = captureHierarchy(stageFailures)
            val windows = hierarchy.windows
            val defaultWindow = windows.maxBy { it.root.nodeCount() }
            val screenshotCapture = captureScreenshotOrNull(stageFailures)
            val display = screenshotCapture?.display ?: inferDisplay(windows)
            val snapshot = LayoutSnapshot(
                protocolVersion = CURRENT_PROTOCOL_VERSION,
                packageName = packageName,
                capturedAtEpochMillis = System.currentTimeMillis(),
                display = display,
                capabilities = AgentCapabilities(
                    viewHierarchy = true,
                    composeSemantics = hierarchy.composeSemantics,
                    screenshots = screenshotCapture != null,
                ),
                root = defaultWindow.root,
                windows = windows,
                defaultWindowId = defaultWindow.id,
            )
            val snapshotJson = try {
                protocolCodec.encodeSnapshot(snapshot)
            } catch (error: Throwable) {
                stageFailures.record("encode", error.message ?: error.javaClass.simpleName)
                throw error
            }
            CaptureFrame(
                snapshotJson = snapshotJson,
                screenshotPng = screenshotCapture?.png ?: byteArrayOf(),
            )
        } catch (error: Throwable) {
            throw AdbFallbackUnavailableException(packageName, stageFailures.summaries(), error)
        }
    }

    private fun captureScreenshotOrNull(stageFailures: CaptureStageFailures): ScreenshotCapture? =
        runCatching {
            val png = checkedRun(AdbCommandFactory.captureScreenshot(serial)).stdoutBytes
            val (width, height) = PngDimensions.read(png)
            ScreenshotCapture(
                png = png,
                display = DisplayInfo(widthPx = width, heightPx = height, density = 1f),
            )
        }.onFailure { error ->
            // A missing screenshot degrades the capture instead of failing it; the reason is
            // still recorded so a failed capture report explains the missing preview too.
            stageFailures.record("screenshot", error)
        }.getOrNull()

    private fun inferDisplay(windows: List<WindowSnapshot>): DisplayInfo =
        DisplayInfo(
            widthPx = windows.maxOfOrNull { window ->
                maxOf(window.bounds.right, window.root.bounds.right)
            }?.coerceAtLeast(1) ?: 1,
            heightPx = windows.maxOfOrNull { window ->
                maxOf(window.bounds.bottom, window.root.bounds.bottom)
            }?.coerceAtLeast(1) ?: 1,
            density = 1f,
        )

    private fun captureHierarchy(stageFailures: CaptureStageFailures): CapturedHierarchy {
        repeat(NATIVE_HIERARCHY_ATTEMPTS) { attempt ->
            val visibleWindows = processRunner.run(
                AdbCommandFactory.dumpVisibleWindowViews(serial),
            )
            if (visibleWindows.exitCode != 0) {
                stageFailures.record(
                    "visible-window-dump",
                    commandFailureSummary(visibleWindows),
                )
            } else {
                runCatching {
                    VisibleWindowHierarchyParser.parseWindows(
                        zipBytes = visibleWindows.stdoutBytes,
                        packageName = packageName,
                    )
                }.onFailure { error ->
                    stageFailures.record("visible-window-parse", error)
                }.getOrNull()?.let { nativeWindows ->
                    return expandShallowComposeHierarchy(nativeWindows, stageFailures)
                }
            }
            if (attempt < NATIVE_HIERARCHY_ATTEMPTS - 1) {
                retrySleeper(NATIVE_HIERARCHY_RETRY_DELAY_MILLIS)
            }
        }
        return captureUiAutomatorHierarchy(stageFailures)
    }

    private fun expandShallowComposeHierarchy(
        nativeWindows: List<WindowSnapshot>,
        stageFailures: CaptureStageFailures,
    ): CapturedHierarchy {
        val native = CapturedHierarchy(nativeWindows, composeSemantics = false)
        if (nativeWindows.none { it.root.containsComposeHost() }) return native
        val accessibility = runCatching { captureUiAutomatorHierarchy(stageFailures) }.getOrNull() ?: return native
        return accessibility.takeIf {
            it.composeSemantics && it.windows.sumOf { window -> window.root.nodeCount() } >
                nativeWindows.sumOf { window -> window.root.nodeCount() }
        } ?: native
    }

    private fun captureUiAutomatorHierarchy(stageFailures: CaptureStageFailures): CapturedHierarchy = try {
        val output = checkedRun(AdbCommandFactory.dumpHierarchy(serial)).stdout
        val root = UiAutomatorHierarchyParser.parse(
            output = output,
            expectedPackageName = packageName,
        ).promoteComposeSemantics()
        CapturedHierarchy(
            windows = listOf(
                WindowSnapshot(
                    id = "window:uiautomator",
                    title = packageName.substringAfterLast('.'),
                    type = WindowType.ACTIVITY,
                    bounds = root.bounds,
                    root = root,
                ),
            ),
            composeSemantics = root.containsComposeHost(),
        )
    } catch (error: Throwable) {
        stageFailures.record("uiautomator", error)
        throw error
    }

    private fun commandFailureSummary(result: ProcessResult): String =
        "exit ${result.exitCode}: ${(result.stderr.ifBlank { result.stdout }).trim()}"

    private fun UiNode.nodeCount(): Int = 1 + children.sumOf { it.nodeCount() }

    private fun UiNode.containsComposeHost(): Boolean =
        className.endsWith(".ComposeView") ||
            className.endsWith(".AndroidComposeView") ||
            children.any { it.containsComposeHost() }

    private fun checkedRun(arguments: List<String>): ProcessResult =
        processRunner.run(arguments).also { result ->
            if (result.exitCode != 0) throw AdbCommandException(arguments, result)
        }

    private data class ScreenshotCapture(
        val png: ByteArray,
        val display: DisplayInfo,
    )

    private data class CapturedHierarchy(
        val windows: List<WindowSnapshot>,
        val composeSemantics: Boolean,
    )

    private companion object {
        const val NATIVE_HIERARCHY_ATTEMPTS = 3
        const val NATIVE_HIERARCHY_RETRY_DELAY_MILLIS = 75L
    }
}

internal object UiAutomatorHierarchyParser {
    private val boundsPattern = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

    fun parse(
        output: String,
        expectedPackageName: String? = null,
    ): ViewNode {
        val xml = output.substringBeforeLast("</hierarchy>", missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
            ?.plus("</hierarchy>")
            ?: throw IllegalArgumentException("UI Automator returned no hierarchy")
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val roots = document.documentElement.childNodes
            .asElementSequence()
            .filter { it.tagName == "node" }
            .toList()
        val root = expectedPackageName?.let { expected ->
            roots.asSequence()
                .flatMap { it.nodeDescendants() }
                .firstOrNull { it.getAttribute("package") == expected }
                ?: throw IllegalArgumentException(
                    "UI Automator hierarchy belongs to " +
                        roots.asSequence()
                            .flatMap { it.nodeDescendants() }
                            .map { it.getAttribute("package") }
                            .filter(String::isNotBlank)
                            .distinct()
                            .joinToString().ifBlank { "an unknown package" } +
                        ", not $expected",
                )
        } ?: roots.firstOrNull()
            ?: throw IllegalArgumentException("UI Automator hierarchy has no root node")
        return root.toViewNode("root")
    }

    private fun Element.nodeDescendants(): Sequence<Element> = sequence {
        yield(this@nodeDescendants)
        childNodes.asElementSequence()
            .filter { it.tagName == "node" }
            .forEach { child -> yieldAll(child.nodeDescendants()) }
    }

    private fun Element.toViewNode(path: String): ViewNode {
        val resourceName = getAttribute("resource-id").ifBlank { null }
        val visibleToUser = booleanAttribute("visible-to-user")
        val className = getAttribute("class").ifBlank { "android.view.View" }
        val children = childNodes.asElementSequence()
            .filter { it.tagName == "node" }
            .mapIndexed { index, child -> child.toViewNode("$path/$index") }
            .toList()
        val bounds = parseBounds(getAttribute("bounds"))
        val visible = visibleToUser ?: true
        val text = getAttribute("text").ifBlank { null }
        return ViewNode(
            id = path,
            className = className,
            bounds = bounds,
            visible = visible,
            alpha = 1f,
            children = children,
            resourceName = resourceName,
            text = text,
            attributes = ViewAttributes(
                visibility = visibleToUser?.let {
                    if (it) "VISIBLE_TO_USER" else "NOT_VISIBLE_TO_USER"
                },

                enabled = booleanAttribute("enabled"),
                clickable = booleanAttribute("clickable"),
                longClickable = booleanAttribute("long-clickable"),
                focusable = booleanAttribute("focusable"),
                focused = booleanAttribute("focused"),
                selected = booleanAttribute("selected"),
                contentDescription = getAttribute("content-desc").ifBlank { null },
                rawProperties = buildMap {
                    listOf(
                        "checkable", "checked", "scrollable", "password", "hint",
                    ).forEach { name ->
                        getAttribute(name).takeIf(String::isNotBlank)?.let { put(name, it) }
                    }
                },
            ),
        )
    }

    private fun Element.booleanAttribute(name: String): Boolean? =
        getAttribute(name)
            .takeIf(String::isNotBlank)
            ?.toBooleanStrictOrNull()

    private fun parseBounds(value: String): Bounds {
        val match = boundsPattern.matchEntire(value)
            ?: throw IllegalArgumentException("Invalid UI Automator bounds: $value")
        return Bounds(
            left = match.groupValues[1].toInt(),
            top = match.groupValues[2].toInt(),
            right = match.groupValues[3].toInt(),
            bottom = match.groupValues[4].toInt(),
        )
    }

    private fun NodeList.asElementSequence(): Sequence<Element> =
        (0 until length).asSequence().mapNotNull { item(it) as? Element }
}

private fun UiNode.promoteComposeSemantics(insideComposeHost: Boolean = false): UiNode {
    val isComposeHost = className.endsWith(".ComposeView") ||
        className.endsWith(".AndroidComposeView")
    val promotedChildren = children.map { child ->
        child.promoteComposeSemantics(insideComposeHost || isComposeHost)
    }
    if (!insideComposeHost || this !is ViewNode) {
        return when (this) {
            is ViewNode -> copy(children = promotedChildren)
            is ComposeNode -> copy(children = promotedChildren)
        }
    }
    val semanticClass = semanticClass(className)
    return ComposeNode(
        id = id,
        className = semanticClass.label,
        bounds = bounds,
        visible = visible,
        alpha = alpha,
        children = promotedChildren,
        semanticsRole = semanticClass.role,
        text = text,
        semanticProperties = buildMap {
            put("AccessibilityClassName", className)
            resourceName?.let { put("ResourceId", it) }
            attributes.contentDescription?.let { put("ContentDescription", it) }
            attributes.enabled?.let { put("Enabled", it.toString()) }
            attributes.clickable?.let { put("Clickable", it.toString()) }
            attributes.longClickable?.let { put("LongClickable", it.toString()) }
            attributes.focusable?.let { put("Focusable", it.toString()) }
            attributes.focused?.let { put("Focused", it.toString()) }
            attributes.selected?.let { put("Selected", it.toString()) }
            attributes.rawProperties.forEach { (name, value) ->
                put(name.replaceFirstChar(Char::uppercase), value)
            }
        },
    )
}

private fun semanticClass(accessibilityClassName: String): SemanticClass = when {
    accessibilityClassName.endsWith(".Button") ||
        accessibilityClassName.endsWith(".ImageButton") -> SemanticClass("Button", "Button")
    accessibilityClassName.endsWith(".CheckBox") -> SemanticClass("Checkbox", "Checkbox")
    accessibilityClassName.endsWith(".RadioButton") -> SemanticClass("RadioButton", "RadioButton")
    accessibilityClassName.endsWith(".Switch") ||
        accessibilityClassName.endsWith(".ToggleButton") -> SemanticClass("Switch", "Switch")
    accessibilityClassName.endsWith(".EditText") -> SemanticClass("TextField", "TextField")
    accessibilityClassName.endsWith(".TextView") -> SemanticClass("Text", null)
    accessibilityClassName.endsWith(".ImageView") -> SemanticClass("Image", "Image")
    accessibilityClassName.endsWith(".ScrollView") -> SemanticClass("ScrollContainer", null)
    accessibilityClassName.endsWith(".SeekBar") -> SemanticClass("Slider", "Slider")
    accessibilityClassName.endsWith(".ProgressBar") -> SemanticClass("ProgressIndicator", null)
    else -> SemanticClass("SemanticsNode", null)
}

private data class SemanticClass(
    val label: String,
    val role: String?,
)

internal object PngDimensions {
    fun read(png: ByteArray): Pair<Int, Int> {
        require(png.size >= PNG_HEADER_SIZE) { "Screenshot is not a valid PNG" }
        require(png.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) {
            "Screenshot is not a valid PNG"
        }
        require(png.copyOfRange(12, 16).contentEquals(IHDR)) { "PNG is missing IHDR" }
        return readInt(png, 16) to readInt(png, 20)
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
    )
    private val IHDR = "IHDR".toByteArray(Charsets.US_ASCII)
    private const val PNG_HEADER_SIZE = 24
}
