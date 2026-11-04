package com.androidperformancestudio.memory.analysis

import com.androidperformancestudio.memory.model.HeapClass
import com.androidperformancestudio.memory.model.HeapField
import com.androidperformancestudio.memory.model.HeapInstance
import com.androidperformancestudio.memory.model.HeapObject
import com.androidperformancestudio.memory.model.HeapObjectArray
import com.androidperformancestudio.memory.model.HeapPrimitiveArray
import com.androidperformancestudio.memory.model.HeapValueKind
import com.androidperformancestudio.memory.model.PrimitiveType

/** Typed field values and metrics over the query's existing object indexes. */
internal class InstanceValueMetadata(
    private val objectById: Map<Long, HeapObject>,
    private val heapClassById: Map<Long, HeapClass>,
    private val retainedSizes: Map<Long, Long>,
    private val depthOf: (Long) -> Int?,
) {
    private val classObjectType = heapClassById.values.firstOrNull { it.name == HeapClass.CLASS_OBJECT_CLASS_NAME }

    fun instanceFields(instance: HeapInstance): List<FieldValue> {
        val metadata = instanceFieldMetadata(instance)
        val primitiveDeclarations =
            metadata
                .withIndex()
                .filter { it.value.type != PrimitiveType.OBJECT }
                .associate { it.value.name to it.index }
        val depth = depthOf(instance.objectId)
        val remaining =
            (
                instance.primitiveFields.map { (name, value) ->
                    val type = primitiveDeclarations[name]?.let { metadata[it].type }
                    primitiveField(name, value, type, depth)
                } +
                    instance.references.map { reference ->
                        objectField(reference.fieldName, reference.targetObjectId, reference.targetClassName)
                    }
            ).toMutableList()
        return buildList {
            metadata.forEachIndexed { declarationIndex, field ->
                // The parser's name-keyed primitive map retains the last inherited declaration.
                if (field.type != PrimitiveType.OBJECT && primitiveDeclarations[field.name] != declarationIndex) {
                    return@forEachIndexed
                }
                val index =
                    remaining.indexOfFirst {
                        it.name == field.name && (it.targetObjectId != null) == (field.type == PrimitiveType.OBJECT)
                    }
                if (index >= 0) add(remaining.removeAt(index))
            }
            addAll(remaining)
        }
    }

    fun primitiveArrayFields(
        array: HeapPrimitiveArray,
        start: Int,
        limit: Int,
    ): List<FieldValue> {
        val count = minOf(limit, (array.elementCount - start).coerceAtLeast(0))
        val values =
            if (count > 0) {
                array.valueSource?.read(start, count)?.takeIf { it.size == count }
            } else {
                null
            }
        val depth = depthOf(array.objectId)
        return values
            ?.mapIndexed { index, value -> primitiveField("[${start + index}]", value, array.primitiveType, depth) }
            .orEmpty()
    }

    fun objectField(
        name: String,
        objectId: Long,
        fallbackClassName: String? = null,
    ): FieldValue =
        FieldValue(
            name = name,
            displayValue = renderObjectReference(objectId, heapClassById[objectId]?.name ?: classNameOf(objectId)),
            targetObjectId = objectId,
            targetClassName = classNameOf(objectId) ?: fallbackClassName,
            depth = if (objectId == 0L) null else depthOf(objectId),
            valueKind = valueKindOf(objectId),
            shallowSize = shallowSizeOf(objectId),
            retainedSize = if (objectId == 0L) 0 else retainedSizes[objectId],
            nativeSize = if (objectId == 0L) 0 else (objectById[objectId] as? HeapInstance)?.nativeSizeBytes,
            representedClassName = heapClassById[objectId]?.name,
            stringPreview = stringPreviewOf(objectId),
            childCount = fieldCountOf(objectId),
        )

    fun valueKindOf(objectId: Long): HeapValueKind =
        when {
            objectId == 0L -> HeapValueKind.NULL
            objectId in heapClassById -> HeapValueKind.CLASS
            else ->
                when (val obj = objectById[objectId]) {
                    is HeapObjectArray, is HeapPrimitiveArray -> HeapValueKind.ARRAY
                    is HeapInstance ->
                        if (obj.className == "java.lang.String") HeapValueKind.STRING else HeapValueKind.OBJECT
                    else -> HeapValueKind.UNKNOWN
                }
        }

    fun stringPreviewOf(objectId: Long): String? =
        (objectById[objectId] as? HeapInstance)
            ?.takeIf { it.className == "java.lang.String" }
            ?.let { instance ->
                val arrayId = instance.references.firstOrNull { it.fieldName == "value" }?.targetObjectId
                val array = objectById[arrayId] as? HeapPrimitiveArray
                array?.let { backing ->
                    readStringValues(instance, backing)?.let { formatStringPreview(backing.primitiveType, it) }
                }
            }

    private fun instanceFieldMetadata(instance: HeapInstance): List<HeapField> =
        buildList {
            val visited = mutableSetOf<Long>()
            var classId = instance.classObjectId
            while (classId != 0L && visited.add(classId)) {
                val heapClass = heapClassById[classId] ?: break
                addAll(heapClass.instanceFields)
                classId = heapClass.superClassObjectId
            }
        }

    private fun shallowSizeOf(objectId: Long): Long? =
        when {
            objectId == 0L -> 0
            objectId in heapClassById -> classObjectType?.takeIf { it.instanceSizeKnown }?.instanceSize
            else -> objectById[objectId]?.takeIf { it.shallowSizeKnown }?.shallowSize
        }

    private fun fieldCountOf(objectId: Long): Int? =
        when (val obj = objectById[objectId]) {
            is HeapObjectArray -> obj.elementCount
            is HeapPrimitiveArray -> obj.elementCount
            is HeapInstance -> {
                val observedCount = obj.primitiveFields.size + obj.references.size
                if (obj.classObjectId in heapClassById) {
                    maxOf(observedCount, instanceFieldMetadata(obj).size)
                } else {
                    observedCount.takeIf { it > 0 }
                }
            }
            else -> if (objectId == 0L) 0 else null
        }

    private fun classNameOf(objectId: Long): String? =
        if (objectId in heapClassById) HeapClass.CLASS_OBJECT_CLASS_NAME else objectById[objectId]?.className
}

private fun primitiveField(
    name: String,
    rawValue: Long,
    type: PrimitiveType?,
    depth: Int?,
): FieldValue {
    val byteWidth = type?.byteWidth?.takeIf { it > 0 }?.toLong()
    return FieldValue(
        name = name,
        displayValue = renderPrimitive(rawValue, type),
        targetObjectId = null,
        targetClassName = null,
        depth = depth,
        valueKind = HeapValueKind.PRIMITIVE,
        shallowSize = byteWidth,
        retainedSize = byteWidth,
        nativeSize = 0,
        childCount = 0,
    )
}

private fun renderPrimitive(
    rawValue: Long,
    type: PrimitiveType?,
): String =
    when (type) {
        PrimitiveType.BOOLEAN -> (rawValue != 0L).toString()
        PrimitiveType.BYTE -> rawValue.toByte().toString()
        PrimitiveType.SHORT -> rawValue.toShort().toString()
        PrimitiveType.CHAR -> rawValue.toInt().toChar().toString()
        PrimitiveType.INT -> rawValue.toInt().toString()
        PrimitiveType.FLOAT -> Float.fromBits(rawValue.toInt()).toString()
        PrimitiveType.DOUBLE -> Double.fromBits(rawValue).toString()
        else -> rawValue.toString()
    }

private fun renderObjectReference(
    objectId: Long,
    className: String?,
): String =
    when {
        objectId == 0L -> "null"
        className.isNullOrEmpty() -> "0x${objectId.toString(HEX_RADIX)}"
        else -> "0x${objectId.toString(HEX_RADIX)} \u00b7 $className"
    }

private fun readStringValues(
    instance: HeapInstance,
    array: HeapPrimitiveArray,
): LongArray? {
    val offset = instance.primitiveFields["offset"]?.toInt() ?: 0
    if (offset !in 0..array.elementCount) return null
    val count = instance.primitiveFields["count"]?.toInt() ?: (array.elementCount - offset)
    val supportedType = array.primitiveType == PrimitiveType.CHAR || array.primitiveType == PrimitiveType.BYTE
    val validRange = count >= 0 && count <= array.elementCount - offset
    val boundedCount = minOf(count, MAX_STRING_PREVIEW_LENGTH)
    return if (supportedType && validRange) {
        array.valueSource?.read(offset, boundedCount)?.takeIf { it.size == boundedCount }
    } else {
        null
    }
}

private fun formatStringPreview(
    type: PrimitiveType,
    values: LongArray,
): String {
    val text =
        if (type == PrimitiveType.CHAR) {
            String(CharArray(values.size) { values[it].toInt().toChar() })
        } else {
            ByteArray(values.size) { values[it].toByte() }.toString(Charsets.UTF_8)
        }
    val content = if (text.length == MAX_STRING_PREVIEW_LENGTH) text.dropLast(1) + "..." else text
    return "\"$content\""
}

private const val MAX_STRING_PREVIEW_LENGTH = 1024
private const val HEX_RADIX = 16
