@file:Suppress("MagicNumber")

package com.androidperformancestudio.memory.analysis

import shark.GcRoot
import shark.HprofRecord.HeapDumpRecord.GcRootRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.StaticFieldRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import shark.HprofRecord.LoadClassRecord
import shark.HprofRecord.StringRecord
import shark.HprofWriter
import shark.PrimitiveType
import shark.ValueHolder
import shark.ValueHolder.LongHolder
import shark.ValueHolder.ReferenceHolder

/** Real HPROF records emitted through Shark's writer, including inherited fields and 64-bit IDs. */
internal class SharkHprofFixture(
    private val writer: HprofWriter,
) {
    private var nextId = 0x1_0000_0000L
    private var nextClassSerial = 1
    private var nextThreadSerial = 1
    val objectClass = heapClass("java.lang.Object", superclass = 0)
    private val stringClass = heapClass("java.lang.String", fields = listOf("value" to REFERENCE))

    fun heapClass(
        name: String,
        superclass: Long = objectClass,
        fields: List<Pair<String, Int>> = emptyList(),
        staticFields: List<Pair<String, ValueHolder>> = emptyList(),
    ): Long {
        val id = nextId++
        writer.write(LoadClassRecord(nextClassSerial++, id, 0, string(name)))
        val fieldRecords = fields.map { (name, type) -> FieldRecord(string(name), type) }
        val staticFieldRecords =
            staticFields.map { (name, value) -> StaticFieldRecord(string(name), typeOf(value), value) }
        writer.write(ClassDumpRecord(id, 0, superclass, 0, 0, 0, 32, staticFieldRecords, fieldRecords))
        return id
    }

    fun instance(
        classId: Long,
        vararg fields: ValueHolder,
    ): Long {
        val id = nextId++
        writer.write(InstanceDumpRecord(id, 0, classId, writer.valuesToBytes(fields.toList())))
        return id
    }

    fun javaString(value: String): Long {
        val arrayId = nextId++
        writer.write(CharArrayDump(arrayId, 0, value.toCharArray()))
        return instance(stringClass, ReferenceHolder(arrayId))
    }

    fun root(vararg references: Pair<String, Long>): Long {
        val id =
            heapClass(
                "fixture.Root$nextId",
                staticFields = references.map { (name, target) -> name to ReferenceHolder(target) },
            )
        writer.write(GcRootRecord(GcRoot.StickyClass(id)))
        return id
    }

    fun activityClass(name: String = "fixture.Screen"): Long {
        val activityClass =
            heapClass("android.app.Activity", fields = listOf("mDestroyed" to PrimitiveType.BOOLEAN.hprofType))
        return heapClass(name, superclass = activityClass)
    }

    fun javaLocal(
        threadName: String,
        localObjectId: Long,
    ): Long {
        val threadClass = heapClass("java.lang.Thread", fields = listOf("name" to REFERENCE))
        val thread = instance(threadClass, ReferenceHolder(javaString(threadName)))
        val serial = nextThreadSerial++
        writer.write(GcRootRecord(GcRoot.ThreadObject(thread, serial, 0)))
        writer.write(GcRootRecord(GcRoot.JavaFrame(localObjectId, serial, -1)))
        return thread
    }

    fun referenceClasses(): Long {
        val referenceClass = heapClass("java.lang.ref.Reference", fields = listOf("referent" to REFERENCE))
        return heapClass("java.lang.ref.WeakReference", superclass = referenceClass)
    }

    fun watchedReferenceClass(weakReferenceClass: Long): Long =
        heapClass(
            "leakcanary.KeyedWeakReference",
            superclass = weakReferenceClass,
            fields =
                listOf(
                    "key" to REFERENCE,
                    "description" to REFERENCE,
                    "watchUptimeMillis" to PrimitiveType.LONG.hprofType,
                    "retainedUptimeMillis" to PrimitiveType.LONG.hprofType,
                ),
            staticFields = listOf("heapDumpUptimeMillis" to LongHolder(100)),
        )

    fun watchedReference(
        classId: Long,
        referentId: Long,
        retained: Boolean = true,
    ): Long =
        instance(
            classId,
            ReferenceHolder(javaString("watch-$referentId")),
            ReferenceHolder(javaString("fixture object was destroyed")),
            LongHolder(0),
            LongHolder(if (retained) 50 else -1),
            ReferenceHolder(referentId),
        )

    private fun string(value: String): Long {
        val id = nextId++
        writer.write(StringRecord(id, value))
        return id
    }

    private fun typeOf(value: ValueHolder): Int =
        when (value) {
            is ReferenceHolder -> REFERENCE
            is LongHolder -> PrimitiveType.LONG.hprofType
            else -> error("Unsupported fixture static field $value")
        }

    companion object {
        const val REFERENCE = PrimitiveType.REFERENCE_HPROF_TYPE
    }
}
