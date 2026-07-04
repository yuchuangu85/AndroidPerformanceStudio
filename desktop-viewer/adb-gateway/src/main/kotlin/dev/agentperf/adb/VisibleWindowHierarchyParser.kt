package dev.agentperf.adb

import dev.agentperf.protocol.Bounds
import dev.agentperf.protocol.EdgeInsets
import dev.agentperf.protocol.UiNode
import dev.agentperf.protocol.ViewAttributes
import dev.agentperf.protocol.ViewNode
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

internal object VisibleWindowHierarchyParser {
    fun parse(
        zipBytes: ByteArray,
        packageName: String,
    ): ViewNode {
        require(zipBytes.isNotEmpty()) { "Visible-window hierarchy is empty" }
        val roots = ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            buildList {
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name.contains(packageName)) {
                        val encoded = zip.readEntryBytes()
                        add(EncodedHierarchyDecoder(encoded).decode())
                    }
                    zip.closeEntry()
                }
            }
        }
        return roots.maxWithOrNull(
            compareBy<ViewNode> { it.nodeCount() }
                .thenBy { it.bounds.width.toLong() * it.bounds.height },
        ) ?: throw IllegalArgumentException(
            "Visible-window hierarchy has no window for $packageName",
        )
    }

    private fun ZipInputStream.readEntryBytes(): ByteArray {
        val bytes = readNBytes(MAX_ENTRY_BYTES + 1)
        require(bytes.size <= MAX_ENTRY_BYTES) { "Visible-window hierarchy entry is too large" }
        return bytes
    }

    private fun UiNode.nodeCount(): Int = 1 + children.sumOf { it.nodeCount() }

    private const val MAX_ENTRY_BYTES = 16 * 1024 * 1024
}

private class EncodedHierarchyDecoder(
    bytes: ByteArray,
) {
    private val input = DataInputStream(ByteArrayInputStream(bytes))
    private var valueCount = 0

    fun decode(): ViewNode {
        val values = buildList {
            while (true) {
                try {
                    add(readValue(depth = 0))
                } catch (_: EOFException) {
                    break
                }
            }
        }
        val rootIndex = values.indexOfFirst { it is EncodedMap }
        require(rootIndex >= 0) { "Encoded hierarchy has no root view" }
        val root = values[rootIndex] as EncodedMap
        val propertyIndex = values.drop(rootIndex + 1)
            .filterIsInstance<EncodedMap>()
            .lastOrNull()
            ?: throw IllegalArgumentException("Encoded hierarchy has no property index")
        val propertyNames = propertyIndex.values.mapNotNull { (id, value) ->
            (value as? String)?.let { id to it }
        }.toMap()
        val prefix = values.take(rootIndex)
            .chunked(2)
            .mapNotNull { pair ->
                val id = pair.getOrNull(0) as? Short ?: return@mapNotNull null
                propertyNames[id]?.let { name -> name to pair.getOrNull(1) }
            }
            .toMap()
        return root.toViewNode(
            propertyNames = propertyNames,
            path = "root",
            parentLeft = (prefix["window:left"] as? Number)?.toInt() ?: 0,
            parentTop = (prefix["window:top"] as? Number)?.toInt() ?: 0,
            parentScrollX = 0,
            parentScrollY = 0,
            parentVisible = true,
        )
    }

    private fun readValue(depth: Int): Any {
        require(depth <= MAX_DEPTH) { "Encoded hierarchy is too deeply nested" }
        checkValueCount()
        return when (val signature = input.readUnsignedByte().toChar()) {
            'Z' -> input.readBoolean()
            'B' -> input.readByte()
            'S' -> input.readShort()
            'I' -> input.readInt()
            'J' -> input.readLong()
            'F' -> input.readFloat()
            'D' -> input.readDouble()
            'R' -> readString()
            'M' -> readMap(depth + 1)
            else -> throw IllegalArgumentException(
                "Unsupported encoded hierarchy type: $signature",
            )
        }
    }

    private fun readMap(depth: Int): EncodedMap {
        val values = linkedMapOf<Short, Any>()
        while (true) {
            val key = readValue(depth) as? Short
                ?: throw IllegalArgumentException("Encoded hierarchy map key is not a short")
            if (key.toInt() == 0) break
            values[key] = readValue(depth)
        }
        return EncodedMap(values)
    }

    private fun readString(): String {
        val length = input.readUnsignedShort()
        require(length <= MAX_STRING_BYTES) { "Encoded hierarchy string is too large" }
        return ByteArray(length)
            .also(input::readFully)
            .toString(Charsets.UTF_8)
    }

    private fun checkValueCount() {
        valueCount += 1
        require(valueCount <= MAX_VALUES) { "Encoded hierarchy has too many values" }
    }

    private companion object {
        const val MAX_DEPTH = 512
        const val MAX_STRING_BYTES = 32_767
        const val MAX_VALUES = 1_000_000
    }
}

private data class EncodedMap(
    val values: Map<Short, Any>,
) {
    fun toViewNode(
        propertyNames: Map<Short, String>,
        path: String,
        parentLeft: Int,
        parentTop: Int,
        parentScrollX: Int,
        parentScrollY: Int,
        parentVisible: Boolean,
    ): ViewNode {
        val properties = namedProperties(propertyNames)
        val left = properties.int("layout:left")
        val top = properties.int("layout:top")
        val right = properties.int("layout:right")
        val bottom = properties.int("layout:bottom")
        val absoluteLeft = parentLeft - parentScrollX + left +
            properties.number("drawing:translationX").roundToInt()
        val absoluteTop = parentTop - parentScrollY + top +
            properties.number("drawing:translationY").roundToInt()
        val alpha = properties.number("drawing:alpha", default = 1f)
        val visible = parentVisible &&
            properties.int("misc:visibility") == VISIBLE &&
            alpha > 0f
        val resourceName = (properties["id"] as? String)
            ?.takeUnless { it.isBlank() || it == "NO_ID" }
        val children = properties.entries
            .asSequence()
            .filter { (name, value) ->
                name.startsWith(CHILD_PREFIX) && value is EncodedMap
            }
            .sortedBy { (name, _) -> name.removePrefix(CHILD_PREFIX).toIntOrNull() }
            .mapIndexed { index, (_, value) ->
                (value as EncodedMap).toViewNode(
                    propertyNames = propertyNames,
                    path = "$path/$index",
                    parentLeft = absoluteLeft,
                    parentTop = absoluteTop,
                    parentScrollX = properties.int("scrolling:scrollX"),
                    parentScrollY = properties.int("scrolling:scrollY"),
                    parentVisible = visible,
                )
            }
            .toList()
        return ViewNode(
            id = path,
            className = (properties["meta:__name__"] as? String)
                ?.takeIf(String::isNotBlank)
                ?: "android.view.View",
            bounds = Bounds(
                left = absoluteLeft,
                top = absoluteTop,
                right = absoluteLeft + (right - left).coerceAtLeast(0),
                bottom = absoluteTop + (bottom - top).coerceAtLeast(0),
            ),
            visible = visible,
            alpha = alpha,
            children = children,
            resourceName = resourceName,
            text = (properties["text:text"] as? String)?.takeIf(String::isNotBlank),
            attributes = properties.toViewAttributes(propertyNames),
        )
    }

    private fun namedProperties(propertyNames: Map<Short, String>): Map<String, Any> =
        values.mapNotNull { (id, value) ->
            propertyNames[id]?.let { name -> name to value }
        }.toMap()

    private fun Map<String, Any>.toViewAttributes(
        propertyNames: Map<Short, String>,
    ): ViewAttributes {
        val layoutParams = (get("layoutParams") as? EncodedMap)
            ?.namedProperties(propertyNames)
            .orEmpty()
        val elevation = floatOrNull("drawing:elevation")
        val translationZ = floatOrNull("drawing:translationZ")
        return ViewAttributes(
            visibility = intOrNull("misc:visibility")?.toVisibilityLabel(),
            elevation = elevation,
            z = if (elevation != null || translationZ != null) {
                (elevation ?: 0f) + (translationZ ?: 0f)
            } else {
                null
            },
            translationX = floatOrNull("drawing:translationX"),
            translationY = floatOrNull("drawing:translationY"),
            translationZ = translationZ,
            rotation = floatOrNull("drawing:rotation"),
            rotationX = floatOrNull("drawing:rotationX"),
            rotationY = floatOrNull("drawing:rotationY"),
            scaleX = floatOrNull("drawing:scaleX"),
            scaleY = floatOrNull("drawing:scaleY"),
            pivotX = floatOrNull("drawing:pivotX"),
            pivotY = floatOrNull("drawing:pivotY"),
            padding = EdgeInsets(
                left = int("padding:paddingLeft"),
                top = int("padding:paddingTop"),
                right = int("padding:paddingRight"),
                bottom = int("padding:paddingBottom"),
            ),
            margin = layoutParams.takeIf { it.isNotEmpty() }?.let {
                EdgeInsets(
                    left = it.int("leftMargin"),
                    top = it.int("topMargin"),
                    right = it.int("rightMargin"),
                    bottom = it.int("bottomMargin"),
                )
            },
            layoutWidth = layoutParams.intOrNull("width"),
            layoutHeight = layoutParams.intOrNull("height"),
            measuredWidth = intOrNull("measurement:measuredWidth"),
            measuredHeight = intOrNull("measurement:measuredHeight"),
            minWidth = intOrNull("measurement:minWidth"),
            minHeight = intOrNull("measurement:minHeight"),
            scrollX = intOrNull("scrolling:scrollX"),
            scrollY = intOrNull("scrolling:scrollY"),
            clipBounds = (get("drawing:clipBounds") as? String)?.toBoundsOrNull(),
            clipChildren = booleanOrNull("drawing:clipChildren"),
            clipToPadding = booleanOrNull("drawing:clipToPadding"),
            opaque = booleanOrNull("drawing:opaque"),
            willNotDraw = booleanOrNull("drawing:willNotDraw"),
            hardwareAccelerated = booleanOrNull("drawing:hardwareAccelerated"),
            layerType = intOrNull("drawing:layerType")?.toLayerTypeLabel(),
            enabled = booleanOrNull("misc:enabled"),
            clickable = booleanOrNull("misc:clickable"),
            focusable = booleanOrNull("focus:isFocusable"),
            focused = booleanOrNull("focus:isFocused"),
            selected = booleanOrNull("misc:selected"),
        )
    }

    private fun Map<String, Any>.int(name: String): Int =
        (get(name) as? Number)?.toInt() ?: 0

    private fun Map<String, Any>.intOrNull(name: String): Int? =
        (get(name) as? Number)?.toInt()

    private fun Map<String, Any>.floatOrNull(name: String): Float? =
        (get(name) as? Number)?.toFloat()

    private fun Map<String, Any>.booleanOrNull(name: String): Boolean? =
        get(name) as? Boolean

    private fun Map<String, Any>.number(
        name: String,
        default: Float = 0f,
    ): Float = (get(name) as? Number)?.toFloat() ?: default

    private companion object {
        const val CHILD_PREFIX = "meta:__child__"
        const val VISIBLE = 0
    }
}

private fun Int.toVisibilityLabel(): String = when (this) {
    0 -> "VISIBLE"
    4 -> "INVISIBLE"
    8 -> "GONE"
    else -> "UNKNOWN($this)"
}

private fun Int.toLayerTypeLabel(): String = when (this) {
    0 -> "NONE"
    1 -> "SOFTWARE"
    2 -> "HARDWARE"
    else -> "UNKNOWN($this)"
}

private fun String.toBoundsOrNull(): Bounds? {
    val match = RECT_PATTERN.matchEntire(this) ?: return null
    return Bounds(
        left = match.groupValues[1].toInt(),
        top = match.groupValues[2].toInt(),
        right = match.groupValues[3].toInt(),
        bottom = match.groupValues[4].toInt(),
    )
}

private val RECT_PATTERN =
    Regex("""Rect\((-?\d+),\s*(-?\d+)\s*-\s*(-?\d+),\s*(-?\d+)\)""")
