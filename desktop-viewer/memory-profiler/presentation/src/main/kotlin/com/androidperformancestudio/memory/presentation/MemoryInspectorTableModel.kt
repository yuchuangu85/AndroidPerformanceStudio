package com.androidperformancestudio.memory.presentation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.memory.model.HeapCapability
import com.androidperformancestudio.memory.model.HeapValueKind

internal enum class MemoryInspectorColumn(
    val preferredWidth: Dp,
) {
    LABEL(500.dp),
    DEPTH(150.dp),
    NATIVE_SIZE(110.dp),
    SHALLOW_SIZE(120.dp),
    RETAINED_NATIVE_SIZE(150.dp),
    RETAINED_SIZE(130.dp),
}

internal data class MemoryInspectorSort(
    val column: MemoryInspectorColumn,
    val descending: Boolean,
)

internal fun inspectorColumns(state: MemoryProfilerState): List<MemoryInspectorColumn> =
    buildList {
        add(MemoryInspectorColumn.LABEL)
        add(MemoryInspectorColumn.DEPTH)
        val native = HeapCapability.NATIVE_SIZE in state.snapshotSummary?.capabilities.orEmpty()
        if (native) add(MemoryInspectorColumn.NATIVE_SIZE)
        add(MemoryInspectorColumn.SHALLOW_SIZE)
        if (state.selectedInstanceDetail?.retainedNativeSize != null) add(MemoryInspectorColumn.RETAINED_NATIVE_SIZE)
        add(MemoryInspectorColumn.RETAINED_SIZE)
    }

internal fun hasInspectorFields(detail: MemoryInstanceDetail): Boolean =
    detail.fields.isNotEmpty() || (detail.isArray && (detail.elementCount ?: 0) > 0)

internal fun hasInspectorContent(detail: MemoryInstanceDetail?): Boolean =
    detail != null && (hasInspectorFields(detail) || detail.references.isNotEmpty())

internal fun inspectorObjectLabel(
    objectId: Long,
    className: String,
    representedClassName: String? = null,
): String {
    val name = representedClassName?.let { "${it.substringAfterLast('.')}.class" } ?: className.substringAfterLast('.')
    return "$name@$objectId (0x${java.lang.Long.toHexString(objectId)})"
}

internal fun MemoryInstanceDetail.inspectorLabel(): String =
    inspectorObjectLabel(
        objectId = objectId,
        className = className,
        representedClassName = representedClassName,
    )

internal fun inspectorFieldLabel(
    field: MemoryInstanceField,
    references: Boolean,
): String {
    if (references) return inspectorReferenceLabel(field)
    val name = field.name.removePrefix("[").removeSuffix("]")
    val prefix = if (name.isEmpty()) "" else "$name = "
    return prefix + inspectorFieldValue(field)
}

private fun inspectorReferenceLabel(field: MemoryInstanceField): String {
    val identity =
        field.targetObjectId?.let { id ->
            inspectorObjectLabel(id, field.targetClassName ?: "<unknown>", field.representedClassName)
        } ?: field.displayValue
    return if (field.name.isEmpty()) identity else "${field.name} in $identity"
}

private fun inspectorFieldValue(field: MemoryInstanceField): String =
    when {
        field.valueKind == HeapValueKind.PRIMITIVE -> field.displayValue
        field.valueKind == HeapValueKind.NULL || field.targetObjectId == 0L -> "null"
        field.targetObjectId == null -> field.displayValue
        else -> {
            val simpleName =
                (field.representedClassName ?: field.targetClassName ?: "<unknown>").substringAfterLast('.')
            "{$simpleName}" + (field.stringPreview?.let { " $it" } ?: "")
        }
    }

internal fun inspectorMetric(
    field: MemoryInstanceField,
    column: MemoryInspectorColumn,
): Long? =
    when (column) {
        MemoryInspectorColumn.LABEL -> null
        MemoryInspectorColumn.DEPTH -> field.depth?.toLong()
        MemoryInspectorColumn.NATIVE_SIZE -> field.nativeSize
        MemoryInspectorColumn.SHALLOW_SIZE -> field.shallowSize
        MemoryInspectorColumn.RETAINED_NATIVE_SIZE -> field.retainedNativeSize
        MemoryInspectorColumn.RETAINED_SIZE -> field.retainedSize
    }

internal fun inspectorFieldComparator(sort: MemoryInspectorSort): Comparator<IndexedValue<MemoryInstanceField>> {
    val comparator =
        if (sort.column == MemoryInspectorColumn.LABEL) {
            compareBy<IndexedValue<MemoryInstanceField>> { it.value.name }
        } else {
            compareBy { inspectorMetric(it.value, sort.column) ?: -1L }
        }
    return if (sort.descending) comparator.reversed() else comparator
}
