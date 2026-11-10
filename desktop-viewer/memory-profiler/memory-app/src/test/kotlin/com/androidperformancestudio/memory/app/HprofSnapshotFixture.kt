@file:Suppress("MagicNumber", "TooManyFunctions")

package com.androidperformancestudio.memory.app

import com.androidperformancestudio.memory.model.PrimitiveType
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path

/** Identical object graph in standard HPROF and Android's segmented, heap-labelled format. */
internal object HprofSnapshotFixture {
    const val NODE = 1L
    const val CYCLE = 2L
    const val OTHER_NODE = 3L
    const val WEAK_TARGET = 4L
    const val WEAK_REFERENCE = 5L
    const val STRING = 6L
    const val STRING_BACKING = 7L
    const val DESTROYED_ACTIVITY = 8L
    const val LIVE_ACTIVITY = 9L
    const val NULL_FRAGMENT = 10L
    const val MISSING_FRAGMENT = 11L
    const val BITMAP = 12L
    const val PIXELS = 13L
    const val ROOT_ARRAY = 14L
    const val NODE_CLASS = 102L
    const val ARRAY_NAME = "[La.Node;"
    const val EXTRA_NODE_START = 1000L

    val objectIds: List<Long> = (1L..14L).toList() + (20L..27L).toList() + (100L..113L).toList()

    @Suppress("LongMethod")
    fun write(
        path: Path,
        idSize: Int,
        android: Boolean,
        extraNodes: Int = 0,
    ): Path {
        val writer = Writer(idSize, android)
        if (android) writer.heap(1, "zygote")
        writer.clazz(100, "java.lang.Object", size = 8)
        writer.clazz(101, "java.lang.Class", parent = 100, size = 24)
        val nodeFields =
            listOf(
                "next" to PrimitiveType.OBJECT,
                "nil" to PrimitiveType.OBJECT,
                "unknown" to PrimitiveType.OBJECT,
                "flag" to PrimitiveType.BOOLEAN,
                "byteValue" to PrimitiveType.BYTE,
                "charValue" to PrimitiveType.CHAR,
                "shortValue" to PrimitiveType.SHORT,
                "intValue" to PrimitiveType.INT,
                "floatValue" to PrimitiveType.FLOAT,
                "doubleValue" to PrimitiveType.DOUBLE,
                "longValue" to PrimitiveType.LONG,
            )
        writer.clazz(NODE_CLASS, "a.Node", 100, 64, nodeFields)
        writer.clazz(103, "b.Node", NODE_CLASS, 64)
        writer.clazz(104, ARRAY_NAME, 100, 0)
        writer.clazz(105, "java.lang.ref.Reference", 100, 16, listOf("referent" to PrimitiveType.OBJECT))
        writer.clazz(106, "java.lang.ref.WeakReference", 105, 16)
        writer.clazz(
            107,
            "java.lang.String",
            100,
            24,
            listOf("value" to PrimitiveType.OBJECT, "offset" to PrimitiveType.INT, "count" to PrimitiveType.INT),
        )
        writer.clazz(
            108,
            "android.app.Activity",
            100,
            24,
            listOf("mDestroyed" to PrimitiveType.BOOLEAN, "mFinished" to PrimitiveType.BOOLEAN),
        )
        writer.clazz(109, "q.Screen", 108, 24)
        writer.clazz(
            110,
            "android.app.Fragment",
            100,
            32,
            listOf("mFragmentManager" to PrimitiveType.OBJECT, "mIndex" to PrimitiveType.INT),
        )
        writer.clazz(111, "q.Fragment", 110, 32)
        writer.clazz(
            112,
            "android.graphics.Bitmap",
            100,
            40,
            listOf(
                "mWidth" to PrimitiveType.INT,
                "mHeight" to PrimitiveType.INT,
                "mRowBytes" to PrimitiveType.INT,
                "mId" to PrimitiveType.LONG,
                "mSourceId" to PrimitiveType.LONG,
                "mBuffer" to PrimitiveType.OBJECT,
            ),
        )
        writer.clazz(113, "example.Singleton", 100, 8, staticReferences = listOf("root" to ROOT_ARRAY))
        writer.root(113)
        if (android) writer.heap(2, "app")
        writer.node(NODE, NODE_CLASS, CYCLE)
        writer.node(CYCLE, NODE_CLASS, NODE)
        writer.node(OTHER_NODE, 103, CYCLE)
        writer.node(WEAK_TARGET, NODE_CLASS, 0)
        writer.instance(WEAK_REFERENCE, 106, listOf(PrimitiveType.OBJECT to WEAK_TARGET))
        writer.instance(
            STRING,
            107,
            listOf(PrimitiveType.OBJECT to STRING_BACKING, PrimitiveType.INT to 1L, PrimitiveType.INT to 5L),
        )
        writer.primitiveArray(STRING_BACKING, PrimitiveType.CHAR, "xhello!".map { it.code.toLong() })
        writer.instance(DESTROYED_ACTIVITY, 109, listOf(PrimitiveType.BOOLEAN to 1L, PrimitiveType.BOOLEAN to 0L))
        writer.instance(LIVE_ACTIVITY, 109, listOf(PrimitiveType.BOOLEAN to 0L, PrimitiveType.BOOLEAN to 0L))
        writer.instance(NULL_FRAGMENT, 111, listOf(PrimitiveType.OBJECT to 0L, PrimitiveType.INT to 4L))
        // No encoded value is not an encoded null reference, even with the same class declaration.
        writer.instance(MISSING_FRAGMENT, 111, emptyList())
        writer.instance(
            BITMAP,
            112,
            listOf(
                PrimitiveType.INT to 4L,
                PrimitiveType.INT to 3L,
                PrimitiveType.INT to 16L,
                PrimitiveType.LONG to 77L,
                PrimitiveType.LONG to 88L,
                PrimitiveType.OBJECT to PIXELS,
            ),
        )
        writer.primitiveArray(PIXELS, PrimitiveType.BYTE, listOf(1, -1, 3))
        val primitives =
            listOf(
                PrimitiveType.BOOLEAN to listOf(1L, 0L),
                PrimitiveType.BYTE to listOf(-7L, 0L),
                PrimitiveType.CHAR to listOf('Z'.code.toLong(), 0L),
                PrimitiveType.SHORT to listOf(-123L, 0L),
                PrimitiveType.INT to listOf(-456L, 0L),
                PrimitiveType.FLOAT to listOf(1.5f.toRawBits().toLong(), 0L),
                PrimitiveType.DOUBLE to listOf((-2.25).toRawBits(), 0L),
                PrimitiveType.LONG to listOf(-3L, 0L),
            )
        primitives.forEachIndexed { index, (type, values) -> writer.primitiveArray(20L + index, type, values) }
        val extraIds = List(extraNodes) { EXTRA_NODE_START + it }
        extraIds.forEach { writer.node(it, NODE_CLASS, 0) }
        writer.objectArray(
            ROOT_ARRAY,
            104,
            listOf(NODE, OTHER_NODE, WEAK_REFERENCE, STRING, DESTROYED_ACTIVITY, LIVE_ACTIVITY) +
                listOf(NULL_FRAGMENT, MISSING_FRAGMENT, BITMAP, NODE_CLASS, 0, NODE) +
                (20L..27L).toList() + extraIds,
        )
        Files.write(path, writer.build())
        return path
    }

    private class Writer(
        private val idSize: Int,
        private val android: Boolean,
    ) {
        private val records = ByteArrayOutputStream()
        private val heapBytes = ByteArrayOutputStream()
        private val heap = DataOutputStream(heapBytes)
        private val strings = linkedMapOf<String, Long>()
        private var serial = 0

        init {
            require(idSize == 4 || idSize == 8)
        }

        private fun string(value: String): Long =
            strings.getOrPut(value) {
                val id = 100_000L + strings.size
                record(
                    1,
                    payload {
                        id(id)
                        write(value.toByteArray(Charsets.UTF_8))
                    },
                )
                id
            }

        fun heap(
            number: Int,
            name: String,
        ) {
            val nameId = string(name)
            heap.writeByte(0xfe)
            heap.writeInt(number)
            heap.id(nameId)
        }

        @Suppress("LongParameterList")
        fun clazz(
            objectId: Long,
            name: String,
            parent: Long = 0,
            size: Int,
            fields: List<Pair<String, PrimitiveType>> = emptyList(),
            staticReferences: List<Pair<String, Long>> = emptyList(),
        ) {
            val nameId = string(name)
            record(
                2,
                payload {
                    writeInt(++serial)
                    id(objectId)
                    writeInt(0)
                    id(nameId)
                },
            )
            heap.writeByte(0x20)
            heap.id(objectId)
            heap.writeInt(0)
            heap.id(parent)
            repeat(5) { heap.id(0) }
            heap.writeInt(size)
            heap.writeShort(0)
            heap.writeShort(staticReferences.size)
            staticReferences.forEach { (field, target) ->
                heap.id(string(field))
                heap.writeByte(PrimitiveType.OBJECT.hprofType)
                heap.id(target)
            }
            heap.writeShort(fields.size)
            fields.forEach { (field, type) ->
                heap.id(string(field))
                heap.writeByte(type.hprofType)
            }
        }

        fun node(
            objectId: Long,
            classId: Long,
            next: Long,
        ) {
            instance(
                objectId,
                classId,
                listOf(
                    PrimitiveType.OBJECT to next,
                    PrimitiveType.OBJECT to 0L,
                    PrimitiveType.OBJECT to 999_999L,
                    PrimitiveType.BOOLEAN to 1L,
                    PrimitiveType.BYTE to -7L,
                    PrimitiveType.CHAR to 'Z'.code.toLong(),
                    PrimitiveType.SHORT to -123L,
                    PrimitiveType.INT to -456L,
                    PrimitiveType.FLOAT to 1.5f.toRawBits().toLong(),
                    PrimitiveType.DOUBLE to (-2.25).toRawBits(),
                    PrimitiveType.LONG to -3L,
                ),
            )
        }

        fun instance(
            objectId: Long,
            classId: Long,
            values: List<Pair<PrimitiveType, Long>>,
        ) {
            val bytes = payload { values.forEach { (type, bits) -> value(type, bits) } }
            heap.writeByte(0x21)
            heap.id(objectId)
            heap.writeInt(0)
            heap.id(classId)
            heap.writeInt(bytes.size)
            heap.write(bytes)
        }

        fun objectArray(
            objectId: Long,
            classId: Long,
            elements: List<Long>,
        ) {
            heap.writeByte(0x22)
            heap.id(objectId)
            heap.writeInt(0)
            heap.writeInt(elements.size)
            heap.id(classId)
            elements.forEach { heap.id(it) }
        }

        fun primitiveArray(
            objectId: Long,
            type: PrimitiveType,
            values: List<Long>,
        ) {
            heap.writeByte(0x23)
            heap.id(objectId)
            heap.writeInt(0)
            heap.writeInt(values.size)
            heap.writeByte(type.hprofType)
            values.forEach { heap.value(type, it) }
        }

        fun root(objectId: Long) {
            heap.writeByte(0x05)
            heap.id(objectId)
        }

        fun build(): ByteArray =
            payload {
                write((if (android) "JAVA PROFILE 1.0.3" else "JAVA PROFILE 1.0.2").toByteArray(Charsets.US_ASCII))
                writeByte(0)
                writeInt(idSize)
                writeLong(1234)
                write(records.toByteArray())
                writeByte(if (android) 0x1c else 0x0c)
                writeInt(0)
                writeInt(heapBytes.size())
                write(heapBytes.toByteArray())
                if (android) {
                    writeByte(0x2c)
                    writeInt(0)
                    writeInt(0)
                }
            }

        private fun record(
            tag: Int,
            bytes: ByteArray,
        ) {
            DataOutputStream(records).apply {
                writeByte(tag)
                writeInt(0)
                writeInt(bytes.size)
                write(bytes)
            }
        }

        private fun payload(write: DataOutputStream.() -> Unit): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use(write)
            return bytes.toByteArray()
        }

        private fun DataOutputStream.id(value: Long) {
            if (idSize == 4) writeInt(value.toInt()) else writeLong(value)
        }

        private fun DataOutputStream.value(
            type: PrimitiveType,
            bits: Long,
        ) {
            when (type) {
                PrimitiveType.OBJECT -> id(bits)
                PrimitiveType.BOOLEAN, PrimitiveType.BYTE -> writeByte(bits.toInt())
                PrimitiveType.CHAR, PrimitiveType.SHORT -> writeShort(bits.toInt())
                PrimitiveType.FLOAT, PrimitiveType.INT -> writeInt(bits.toInt())
                PrimitiveType.DOUBLE, PrimitiveType.LONG -> writeLong(bits)
                PrimitiveType.UNKNOWN -> error("A fixture value must have a declared HPROF type")
            }
        }
    }
}
