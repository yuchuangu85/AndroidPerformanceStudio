package dev.agentperf.adb

import dev.agentperf.protocol.AgentCapabilities
import dev.agentperf.protocol.Bounds
import dev.agentperf.protocol.CaptureFrame
import dev.agentperf.protocol.DisplayInfo
import dev.agentperf.protocol.LayoutSnapshot
import dev.agentperf.protocol.ProtocolCodec
import dev.agentperf.protocol.CURRENT_PROTOCOL_VERSION
import dev.agentperf.protocol.UiNode
import dev.agentperf.protocol.ViewAttributes
import dev.agentperf.protocol.ViewNode
import dev.agentperf.protocol.WindowSnapshot
import dev.agentperf.protocol.WindowType
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class AdbFallbackUnavailableException(
    packageName: String,
    cause: Throwable,
) : IllegalStateException("Unable to capture foreground app $packageName through ADB", cause)

internal class AdbFallbackCapture(
    private val serial: String,
    private val packageName: String,
    private val processRunner: ProcessRunner,
    private val protocolCodec: ProtocolCodec = ProtocolCodec(supportedMajor = 1),
) {
    fun capture(): CaptureFrame = try {
        val screenshot = checkedRun(AdbCommandFactory.captureScreenshot(serial)).stdoutBytes
        val (width, height) = PngDimensions.read(screenshot)
        val windows = captureHierarchy()
        val defaultWindow = windows.maxBy { it.root.nodeCount() }
        val snapshot = LayoutSnapshot(
            protocolVersion = CURRENT_PROTOCOL_VERSION,
            packageName = packageName,
            capturedAtEpochMillis = System.currentTimeMillis(),
            display = DisplayInfo(widthPx = width, heightPx = height, density = 1f),
            capabilities = AgentCapabilities(
                viewHierarchy = true,
                screenshots = true,
            ),
            root = defaultWindow.root,
            windows = windows,
            defaultWindowId = defaultWindow.id,
        )
        CaptureFrame(
            snapshotJson = protocolCodec.encodeSnapshot(snapshot),
            screenshotPng = screenshot,
        )
    } catch (error: Throwable) {
        throw AdbFallbackUnavailableException(packageName, error)
    }

    private fun captureHierarchy(): List<WindowSnapshot> {
        val visibleWindows = processRunner.run(
            AdbCommandFactory.dumpVisibleWindowViews(serial),
        )
        if (visibleWindows.exitCode == 0) {
            runCatching {
                VisibleWindowHierarchyParser.parseWindows(
                    zipBytes = visibleWindows.stdoutBytes,
                    packageName = packageName,
                )
            }.getOrNull()?.let { return it }
        }
        val hierarchy = checkedRun(AdbCommandFactory.dumpHierarchy(serial)).stdout
        val root = UiAutomatorHierarchyParser.parse(hierarchy)
        return listOf(
            WindowSnapshot(
                id = "window:uiautomator",
                title = packageName.substringAfterLast('.'),
                type = WindowType.ACTIVITY,
                bounds = root.bounds,
                root = root,
            ),
        )
    }

    private fun UiNode.nodeCount(): Int = 1 + children.sumOf { it.nodeCount() }

    private fun checkedRun(arguments: List<String>): ProcessResult =
        processRunner.run(arguments).also { result ->
            if (result.exitCode != 0) throw AdbCommandException(arguments, result)
        }
}

internal object UiAutomatorHierarchyParser {
    private val boundsPattern = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

    fun parse(output: String): UiNode {
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
        val root = document.documentElement.childNodes
            .asElementSequence()
            .firstOrNull { it.tagName == "node" }
            ?: throw IllegalArgumentException("UI Automator hierarchy has no root node")
        return root.toViewNode("root")
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

    private fun org.w3c.dom.NodeList.asElementSequence(): Sequence<Element> =
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
