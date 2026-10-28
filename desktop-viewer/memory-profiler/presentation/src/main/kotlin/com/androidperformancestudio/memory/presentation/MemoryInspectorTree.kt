package com.androidperformancestudio.memory.presentation

internal const val MAX_INSPECTOR_TREE_DEPTH = 32
internal const val INSPECTOR_FIELD_BATCH = 100

internal data class InspectorNearestStep(
    val expand: MemoryInspectorTreeEntry.Value? = null,
    val selectedKey: String? = null,
    val waiting: Boolean = false,
)

internal fun inspectorNearestStep(entries: List<MemoryInspectorTreeEntry>): InspectorNearestStep {
    var index = entries.indexOfFirst { it is MemoryInspectorTreeEntry.Value && !it.root }
    var step: InspectorNearestStep? = null
    while (index >= 0 && index < entries.size && step == null) {
        val value = entries[index] as? MemoryInspectorTreeEntry.Value ?: break
        val next = entries.getOrNull(index + 1)
        step =
            when {
                !value.expandable -> InspectorNearestStep(selectedKey = value.key)
                !value.expanded -> InspectorNearestStep(expand = value)
                else -> expandedNearestStep(value, next)
            }
        index++
    }
    return step ?: InspectorNearestStep(selectedKey = "reference-root")
}

private fun expandedNearestStep(
    value: MemoryInspectorTreeEntry.Value,
    next: MemoryInspectorTreeEntry?,
): InspectorNearestStep? =
    when {
        next is MemoryInspectorTreeEntry.Message && next.kind == MemoryInspectorTreeMessage.LOADING ->
            InspectorNearestStep(waiting = true)
        next !is MemoryInspectorTreeEntry.Value || next.depth <= value.depth ->
            InspectorNearestStep(selectedKey = value.key)
        else -> null
    }

private fun MemoryInstanceDetail.asInspectorField(): MemoryInstanceField =
    MemoryInstanceField(
        name = "",
        displayValue = inspectorLabel(),
        targetObjectId = objectId,
        targetClassName = className,
        depth = depth,
        valueKind = valueKind,
        shallowSize = shallowSize.takeIf { shallowSizeKnown },
        retainedSize = retainedSize,
        nativeSize = nativeSize,
        retainedNativeSize = retainedNativeSize,
        representedClassName = representedClassName,
        stringPreview = stringPreview,
        childCount = references.size,
    )

internal sealed interface MemoryInspectorTreeEntry {
    val key: String
    val tag: String

    data class Value(
        override val key: String,
        override val tag: String,
        val field: MemoryInstanceField,
        val depth: Int,
        val expanded: Boolean,
        val loadKey: MemoryInspectorObjectKey?,
        val root: Boolean = false,
        val canExpand: Boolean = true,
    ) : MemoryInspectorTreeEntry {
        val expandable: Boolean get() = canExpand && this.field.targetObjectId?.let { it != 0L } == true
    }

    data class Message(
        override val key: String,
        override val tag: String,
        val kind: MemoryInspectorTreeMessage,
        val depth: Int,
    ) : MemoryInspectorTreeEntry

    data class More(
        override val key: String,
        override val tag: String,
        val parentKey: String,
        val count: Int,
        val loadKey: MemoryInspectorObjectKey?,
        val depth: Int,
    ) : MemoryInspectorTreeEntry
}

internal enum class MemoryInspectorTreeMessage {
    LOADING,
    UNAVAILABLE,
    CYCLE,
    DEPTH_LIMIT,
    NO_FIELDS,
    NO_REFERENCES,
    ARRAY_UNAVAILABLE,
}

/** Only visible branches are materialized; pagination follows Studio's cumulative 100-field batches. */
@Suppress("LongParameterList")
internal class MemoryInspectorTreeBuilder(
    private val root: MemoryInstanceDetail,
    private val referencesTab: Boolean,
    private val nearestOnly: Boolean,
    private val expanded: Set<String>,
    private val visibleCounts: Map<String, Int>,
    private val objects: Map<MemoryInspectorObjectKey, MemoryInspectorObjectState>,
    private val sort: MemoryInspectorSort =
        if (referencesTab) {
            MemoryInspectorSort(MemoryInspectorColumn.DEPTH, false)
        } else {
            MemoryInspectorSort(MemoryInspectorColumn.RETAINED_SIZE, true)
        },
    private val referenceRootExpanded: Boolean = true,
) {
    private val entries = mutableListOf<MemoryInspectorTreeEntry>()

    fun build(): List<MemoryInspectorTreeEntry> {
        if (referencesTab) {
            entries +=
                MemoryInspectorTreeEntry.Value(
                    "reference-root",
                    "memory-inspector-reference-root",
                    root.asInspectorField(),
                    0,
                    referenceRootExpanded,
                    null,
                    root = true,
                    canExpand = root.references.isNotEmpty(),
                )
            if (referenceRootExpanded) appendRows(root, "references", "memory-inspector-row", 1, setOf(root.objectId))
        } else {
            appendRows(root, "fields:${root.arrayStart}", "memory-inspector-row", 0, setOf(root.objectId))
        }
        return entries
    }

    private fun appendRows(
        detail: MemoryInstanceDetail,
        prefix: String,
        tagPrefix: String,
        depth: Int,
        ancestors: Set<Long>,
    ) {
        val limit = if (referencesTab) Int.MAX_VALUE else visibleCounts[prefix] ?: INSPECTOR_FIELD_BATCH
        val fields = collectedFields(detail)
        val indexed = fields.withIndex().filter { (_, field) -> includeField(detail, field) }
        // Shared cache updates must not expose additional array elements in an untouched branch.
        val visible = if (detail.isArray && !referencesTab) indexed.take(limit) else indexed
        val ordered = visible.sortedWith(inspectorFieldComparator(sort))
        ordered.take(limit).forEach { (index, field) ->
            appendValue("$prefix/$index", "$tagPrefix-$index", field, depth, ancestors)
        }
        if (ordered.isEmpty() && !referencesTab) {
            val kind =
                if (detail.isArray && (detail.elementCount ?: 0) > 0) {
                    MemoryInspectorTreeMessage.ARRAY_UNAVAILABLE
                } else {
                    MemoryInspectorTreeMessage.NO_FIELDS
                }
            appendMessage(prefix, tagPrefix.removeSuffix("-child"), kind, depth)
        }
        if (!referencesTab) appendMore(detail, fields.size, ordered.size, limit, prefix, tagPrefix, depth)
    }

    private fun includeField(
        detail: MemoryInstanceDetail,
        field: MemoryInstanceField,
    ): Boolean {
        if (!referencesTab || !nearestOnly) return true
        return detail.depth?.let { distance -> field.depth?.let { it < distance } } == true
    }

    private fun collectedFields(detail: MemoryInstanceDetail): List<MemoryInstanceField> {
        val fields = if (referencesTab) detail.references else detail.fields
        if (referencesTab || !detail.isArray) return fields
        val values = fields.toMutableList()
        var start = detail.arrayStart + detail.fields.size
        while (start < (detail.elementCount ?: start)) {
            val key = MemoryInspectorObjectKey(detail.objectId, start)
            val page = (objects[key] as? MemoryInspectorObjectState.Loaded)?.detail
            if (page == null || page.fields.isEmpty()) break
            values += page.fields
            start += page.fields.size
        }
        return values
    }

    @Suppress("LongParameterList")
    private fun appendMore(
        detail: MemoryInstanceDetail,
        loaded: Int,
        visible: Int,
        limit: Int,
        prefix: String,
        tagPrefix: String,
        depth: Int,
    ) {
        val total = if (detail.isArray) (detail.elementCount ?: loaded) - detail.arrayStart else visible
        val remaining = total - minOf(visible, limit)
        if (remaining <= 0 || loaded == 0) return
        val nextKey =
            if (detail.isArray && limit >= loaded && loaded > 0) {
                MemoryInspectorObjectKey(detail.objectId, detail.arrayStart + loaded)
            } else {
                null
            }
        when (val nextState = objects[nextKey]) {
            MemoryInspectorObjectState.Loading ->
                appendMessage(prefix, tagPrefix, MemoryInspectorTreeMessage.LOADING, depth)
            MemoryInspectorObjectState.Unavailable ->
                appendMessage(prefix, tagPrefix, MemoryInspectorTreeMessage.UNAVAILABLE, depth)
            is MemoryInspectorObjectState.Loaded ->
                if (nextState.detail.fields.isEmpty()) {
                    appendMessage(prefix, tagPrefix, MemoryInspectorTreeMessage.ARRAY_UNAVAILABLE, depth)
                } else {
                    appendMoreEntry(nextKey, minOf(INSPECTOR_FIELD_BATCH, remaining), prefix, tagPrefix, depth)
                }
            else ->
                appendMoreEntry(nextKey, minOf(INSPECTOR_FIELD_BATCH, remaining), prefix, tagPrefix, depth)
        }
    }

    private fun appendMoreEntry(
        nextKey: MemoryInspectorObjectKey?,
        count: Int,
        prefix: String,
        tagPrefix: String,
        depth: Int,
    ) {
        entries +=
            MemoryInspectorTreeEntry.More(
                "$prefix/more",
                "$tagPrefix-more",
                prefix,
                count,
                nextKey,
                depth,
            )
    }

    private fun appendValue(
        key: String,
        tag: String,
        field: MemoryInstanceField,
        depth: Int,
        ancestors: Set<Long>,
    ) {
        val target = field.targetObjectId?.takeIf { it != 0L }
        val cycle = target != null && target in ancestors
        val terminalRoot = referencesTab && nearestOnly && field.depth == 0
        val canExpand = target != null && field.childCount != 0 && !terminalRoot
        val loadKey = target?.takeUnless { cycle || depth >= MAX_INSPECTOR_TREE_DEPTH }?.let(::MemoryInspectorObjectKey)
        val isExpanded = canExpand && key in expanded
        entries += MemoryInspectorTreeEntry.Value(key, tag, field, depth, isExpanded, loadKey, canExpand = canExpand)
        if (!isExpanded) return
        when {
            cycle -> appendMessage(key, tag, MemoryInspectorTreeMessage.CYCLE, depth + 1)
            depth >= MAX_INSPECTOR_TREE_DEPTH ->
                appendMessage(key, tag, MemoryInspectorTreeMessage.DEPTH_LIMIT, depth + 1)
            else -> appendObject(key, tag, loadKey ?: return, depth + 1, ancestors + target)
        }
    }

    private fun appendObject(
        parentKey: String,
        parentTag: String,
        loadKey: MemoryInspectorObjectKey,
        depth: Int,
        ancestors: Set<Long>,
    ) {
        when (val state = objects[loadKey]) {
            null, MemoryInspectorObjectState.Loading ->
                appendMessage(parentKey, parentTag, MemoryInspectorTreeMessage.LOADING, depth)
            MemoryInspectorObjectState.Unavailable ->
                appendMessage(parentKey, parentTag, MemoryInspectorTreeMessage.UNAVAILABLE, depth)
            is MemoryInspectorObjectState.Loaded ->
                appendRows(state.detail, "$parentKey@${state.detail.arrayStart}", "$parentTag-child", depth, ancestors)
        }
    }

    private fun appendMessage(
        parentKey: String,
        parentTag: String,
        kind: MemoryInspectorTreeMessage,
        depth: Int,
    ) {
        val suffix =
            when (kind) {
                MemoryInspectorTreeMessage.LOADING -> "loading"
                MemoryInspectorTreeMessage.UNAVAILABLE -> "unavailable"
                MemoryInspectorTreeMessage.CYCLE -> "cycle"
                MemoryInspectorTreeMessage.DEPTH_LIMIT -> "depth-limit"
                else -> "empty"
            }
        entries += MemoryInspectorTreeEntry.Message("$parentKey/$suffix", "$parentTag-$suffix", kind, depth)
    }
}
