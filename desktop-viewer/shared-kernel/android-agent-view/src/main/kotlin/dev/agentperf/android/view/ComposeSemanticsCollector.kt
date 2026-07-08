package dev.agentperf.android.view

import dev.agentperf.protocol.Bounds
import dev.agentperf.protocol.ComposeNode

internal object ComposeSemanticsCollector {
    fun collect(
        composeView: Any,
        path: String,
        screenOffsetX: Int = 0,
        screenOffsetY: Int = 0,
    ): ComposeNode? = runCatching {
        val owner = composeView.callNoArg("getSemanticsOwner") ?: return null
        val root = owner.callNoArg("getUnmergedRootSemanticsNode") ?: return null
        root.toComposeNode(path, screenOffsetX, screenOffsetY)
    }.getOrNull()

    private fun Any.toComposeNode(path: String, screenOffsetX: Int, screenOffsetY: Int): ComposeNode {
        val semanticsId = callNoArg("getId") as? Int
        val nodePath = semanticsId?.let { "$path/$it" } ?: path
        val properties = semanticsProperties()
        return ComposeNode(
            id = nodePath,
            className = properties.role ?: "ComposeSemantics",
            bounds = semanticsBounds(screenOffsetX, screenOffsetY),
            visible = true,
            alpha = 1f,
            children = children().map { child ->
                child.toComposeNode(path, screenOffsetX, screenOffsetY)
            },
            semanticsRole = properties.role,
            text = properties.text,
            semanticProperties = properties.values,
        )
    }

    private fun Any.children(): List<Any> =
        (callNoArg("getChildren") as? Iterable<*>)
            ?.mapNotNull { it }
            ?: emptyList()

    private fun Any.semanticsBounds(screenOffsetX: Int, screenOffsetY: Int): Bounds {
        val rect = callNoArg("getBoundsInRoot") ?: callNoArg("getBoundsInWindow")
        return Bounds(
            left = rect.floatProperty("getLeft").roundToInt() + screenOffsetX,
            top = rect.floatProperty("getTop").roundToInt() + screenOffsetY,
            right = rect.floatProperty("getRight").roundToInt() + screenOffsetX,
            bottom = rect.floatProperty("getBottom").roundToInt() + screenOffsetY,
        )
    }

    private fun Any.semanticsProperties(): SemanticsProperties {
        val values = mutableMapOf<String, String>()
        val config = callNoArg("getConfig") as? Iterable<*> ?: return SemanticsProperties()
        config.forEach { rawEntry ->
            val entry = rawEntry as? Map.Entry<*, *> ?: return@forEach
            val name = entry.key?.callNoArg("getName")?.toString() ?: return@forEach
            values[name] = entry.value.normalizeSemanticsValue()
        }
        return SemanticsProperties(
            role = values["Role"],
            text = values["Text"] ?: values["ContentDescription"] ?: values["TestTag"],
            values = values.toSortedMap(),
        )
    }

    private fun Any?.normalizeSemanticsValue(): String = when (this) {
        null -> ""
        is Iterable<*> -> joinToString(separator = ", ") { it.normalizeSemanticsValue() }
        else -> toString()
    }

    private fun Any?.floatProperty(name: String): Float =
        (this?.callNoArg(name) as? Number)?.toFloat() ?: 0f

    private fun Float.roundToInt(): Int = java.lang.Math.round(this)

    private fun Any.callNoArg(name: String): Any? {
        val method = javaClass.methods.firstOrNull { method ->
            method.name == name && method.parameterTypes.isEmpty()
        } ?: return null
        return runCatching { method.invoke(this) }.getOrNull()
    }

    private data class SemanticsProperties(
        val role: String? = null,
        val text: String? = null,
        val values: Map<String, String> = emptyMap(),
    )
}
