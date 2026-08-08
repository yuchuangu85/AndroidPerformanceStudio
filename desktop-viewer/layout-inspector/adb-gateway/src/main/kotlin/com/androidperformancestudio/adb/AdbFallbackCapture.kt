package com.androidperformancestudio.adb

import com.androidperformancestudio.protocol.AgentCapabilities
import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.CaptureFrame
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

class AdbFallbackUnavailableException(
    packageName: String,
    cause: Throwable,
) : IllegalStateException("Unable to capture foreground app $packageName through ADB", cause)

internal class AdbFallbackCapture(
    private val serial: String,
    private val packageName: String,
    private val processRunner: ProcessRunner,
    private val protocolCodec: ProtocolCodec = ProtocolCodec(supportedMajor = 1),
    private val retrySleeper: (Long) -> Unit = Thread::sleep,
) {
    fun capture(): CaptureFrame = try {
        val hierarchy = captureHierarchy()
        val windows = hierarchy.windows
        val defaultWindow = windows.maxBy { it.root.nodeCount() }
        val screenshotCapture = captureScreenshotOrNull()
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
        CaptureFrame(
            snapshotJson = protocolCodec.encodeSnapshot(snapshot),
            screenshotPng = screenshotCapture?.png ?: byteArrayOf(),
        )
    } catch (error: Throwable) {
        throw AdbFallbackUnavailableException(packageName, error)
    }

    private fun captureScreenshotOrNull(): ScreenshotCapture? =
        runCatching {
            val png = checkedRun(AdbCommandFactory.captureScreenshot(serial)).stdoutBytes
            val (width, height) = PngDimensions.read(png)
            ScreenshotCapture(
                png = png,
                display = DisplayInfo(widthPx = width, heightPx = height, density = 1f),
            )
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

    private fun captureHierarchy(): CapturedHierarchy {
        repeat(NATIVE_HIERARCHY_ATTEMPTS) { attempt ->
            val visibleWindows = processRunner.run(
                AdbCommandFactory.dumpVisibleWindowViews(serial),
            )
            if (visibleWindows.exitCode == 0) {
                runCatching {
                    VisibleWindowHierarchyParser.parseWindows(
                        zipBytes = visibleWindows.stdoutBytes,
                        packageName = packageName,
                    )
                }.getOrNull()?.let { nativeWindows ->
                    return expandShallowComposeHierarchy(nativeWindows)
                }
            }
            if (attempt < NATIVE_HIERARCHY_ATTEMPTS - 1) {
                retrySleeper(NATIVE_HIERARCHY_RETRY_DELAY_MILLIS)
            }
        }
        return captureUiAutomatorHierarchy()
    }

    private fun expandShallowComposeHierarchy(nativeWindows: List<WindowSnapshot>): CapturedHierarchy {
        val native = CapturedHierarchy(nativeWindows, composeSemantics = false)
        if (nativeWindows.none { it.root.containsComposeHost() }) return native
        val accessibility = runCatching { captureUiAutomatorHierarchy() }.getOrNull() ?: return native
        return accessibility.takeIf {
            it.composeSemantics && it.windows.sumOf { window -> window.root.nodeCount() } >
                nativeWindows.sumOf { window -> window.root.nodeCount() }
        } ?: native
    }

    private fun captureUiAutomatorHierarchy(): CapturedHierarchy {
        val output = checkedRun(AdbCommandFactory.dumpHierarchy(serial)).stdout
        val root = UiAutomatorHierarchyParser.parse(
            output = output,
            expectedPackageName = packageName,
        )
        return CapturedHierarchy(
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
    }

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
    ): UiNode {
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
        val children = childNodes.asElementSequence()
            .filter { it.tagName == "node" }
            .mapIndexed { index, child -> child.toViewNode("$path/$index") }
            .toList()
        return ViewNode(
            id = path,
            className = getAttribute("class").ifBlank { "android.view.View" },
            bounds = parseBounds(getAttribute("bounds")),
            visible = visibleToUser ?: true,
            alpha = 1f,
            children = children,
            resourceName = resourceName,
            text = getAttribute("text").ifBlank { null },
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
