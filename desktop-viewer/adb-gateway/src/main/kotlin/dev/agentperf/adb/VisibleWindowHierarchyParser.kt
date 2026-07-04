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
                        add(EncodedHierarchyDecoder(encoded).decodeDocument().toViewNode())
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

object VisibleWindowViewsTextRenderer {
    fun render(zipBytes: ByteArray): String {
        require(zipBytes.isZipArchive()) { "Visible-window hierarchy is not a ZIP archive" }
        val entries = ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            buildList {
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) {
                        add(entry.name to zip.readNBytes(MAX_TEXT_ENTRY_BYTES + 1).also { bytes ->
                            require(bytes.size <= MAX_TEXT_ENTRY_BYTES) {
                                "Visible-window hierarchy entry is too large: ${entry.name}"
                            }
                        })
                    }
                    zip.closeEntry()
                }
            }
        }
        require(entries.isNotEmpty()) { "Visible-window hierarchy ZIP has no window entries" }
        return buildString {
            appendLine("VISIBLE WINDOW VIEW DUMP")
            appendLine("Window count: ${entries.size}")
            appendLine()
            var parsed = 0
            entries.forEachIndexed { index, (name, bytes) ->
                appendLine(TEXT_SECTION_SEPARATOR)
                appendLine("WINDOW ${index + 1}/${entries.size}: $name")
                appendLine("Encoded bytes: ${bytes.size}")
                appendLine(TEXT_SECTION_SEPARATOR)
                if (bytes.isEmpty()) {
                    appendLine("No hierarchy payload was supplied for this window.")
                    appendLine()
                    return@forEachIndexed
                }
                runCatching {
                    EncodedHierarchyDecoder(bytes).decodeDocument()
                }.onSuccess { hierarchy ->
                    parsed += 1
                    appendLine("Property definitions: ${hierarchy.propertyNames.size}")
                    if (hierarchy.prefix.isNotEmpty()) {
                        appendLine()
                        appendLine("WINDOW PROPERTIES")
                        hierarchy.prefix.toSortedMap().forEach { (property, value) ->
                            appendLine("  $property: ${value.renderValue()}")
                        }
                    }
                    appendLine()
                    appendLine("VIEW TREE AND PROPERTIES")
                    hierarchy.root.appendTextTree(
                        output = this,
                        propertyNames = hierarchy.propertyNames,
                        path = "root",
                        indent = "",
                    )
                    appendLine()
                }.onFailure { error ->
                    appendLine("Parse error: ${error.message ?: error.javaClass.simpleName}")
                    appendLine()
                }
            }
            appendLine(TEXT_SECTION_SEPARATOR)
            appendLine("SUMMARY: parsed $parsed of ${entries.size} windows.")
        }
    }

    private const val MAX_TEXT_ENTRY_BYTES = 16 * 1024 * 1024
    private const val TEXT_SECTION_SEPARATOR =
        "========================================================================================================================"
}

private fun ByteArray.isZipArchive(): Boolean =
    size >= 4 &&
        this[0] == 'P'.code.toByte() &&
        this[1] == 'K'.code.toByte() &&
        this[2] in setOf(3.toByte(), 5.toByte(), 7.toByte()) &&
        this[3] in setOf(4.toByte(), 6.toByte(), 8.toByte())

private class EncodedHierarchyDecoder(
    bytes: ByteArray,
) {
    private val input = DataInputStream(ByteArrayInputStream(bytes))
    private var valueCount = 0

    fun decodeDocument(): DecodedHierarchy {
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
                val value = pair.getOrNull(1) ?: return@mapNotNull null
                propertyNames[id]?.let { name -> name to value }
            }
            .toMap()
        return DecodedHierarchy(
            prefix = prefix,
            root = root,
            propertyNames = propertyNames,
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

private data class DecodedHierarchy(
    val prefix: Map<String, Any>,
    val root: EncodedMap,
    val propertyNames: Map<Short, String>,
) {
    fun toViewNode(): ViewNode = root.toViewNode(
        propertyNames = propertyNames,
        path = "root",
        parentLeft = (prefix["window:left"] as? Number)?.toInt() ?: 0,
        parentTop = (prefix["window:top"] as? Number)?.toInt() ?: 0,
        parentScrollX = 0,
        parentScrollY = 0,
        parentVisible = true,
    )
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

    fun namedProperties(propertyNames: Map<Short, String>): Map<String, Any> =
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

private fun EncodedMap.appendTextTree(
    output: StringBuilder,
    propertyNames: Map<Short, String>,
    path: String,
    indent: String,
) {
    val properties = namedProperties(propertyNames)
    val className = properties["meta:__name__"] ?: "android.view.View"
    val resourceId = properties["id"] ?: "NO_ID"
    val visibility = properties["misc:visibility"] ?: "?"
    val left = properties["layout:left"] ?: "?"
    val top = properties["layout:top"] ?: "?"
    val right = properties["layout:right"] ?: "?"
    val bottom = properties["layout:bottom"] ?: "?"
    output.appendLine(
        "$indent- $className  path=$path  id=$resourceId  " +
            "bounds=($left,$top)-($right,$bottom)  visibility=$visibility",
    )
    properties.toSortedMap().forEach { (name, value) ->
        if (name.startsWith(CHILD_PROPERTY_PREFIX)) return@forEach
        value.appendTextProperty(output, name, "$indent    ", propertyNames)
    }
    properties.entries
        .asSequence()
        .filter { (name, value) ->
            name.startsWith(CHILD_PROPERTY_PREFIX) && value is EncodedMap
        }
        .sortedWith(
            compareBy<Map.Entry<String, Any>> {
                it.key.removePrefix(CHILD_PROPERTY_PREFIX).toIntOrNull() ?: Int.MAX_VALUE
            }.thenBy { it.key },
        )
        .forEachIndexed { index, (_, value) ->
            (value as EncodedMap).appendTextTree(
                output = output,
                propertyNames = propertyNames,
                path = "$path/$index",
                indent = "$indent  ",
            )
        }
}

private fun Any.appendTextProperty(
    output: StringBuilder,
    name: String,
    indent: String,
    propertyNames: Map<Short, String>,
) {
    if (this is EncodedMap) {
        output.appendLine("$indent$name:")
        namedProperties(propertyNames).toSortedMap().forEach { (nestedName, nestedValue) ->
            if (!nestedName.startsWith(CHILD_PROPERTY_PREFIX)) {
                nestedValue.appendTextProperty(
                    output = output,
                    name = nestedName,
                    indent = "$indent  ",
                    propertyNames = propertyNames,
                )
            }
        }
    } else {
        output.appendLine("$indent$name: ${renderValue()}")
    }
}

private fun Any.renderValue(): String = when (this) {
    is Boolean -> toString()
    is Float -> if (this % 1f == 0f) toInt().toString() else toString()
    is Double -> if (this % 1.0 == 0.0) toLong().toString() else toString()
    is String -> "'$this'"
    else -> toString()
}

private const val CHILD_PROPERTY_PREFIX = "meta:__child__"

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
