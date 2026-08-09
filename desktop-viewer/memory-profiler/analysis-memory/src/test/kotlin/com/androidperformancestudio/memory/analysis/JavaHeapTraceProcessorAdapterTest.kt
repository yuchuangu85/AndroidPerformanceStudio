package com.androidperformancestudio.memory.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaHeapTraceProcessorAdapterTest {
    @Test
    fun `maps java hprof classes objects references and roots without fabricating class sizes`() {
        val result =
            JavaHeapTraceProcessorAdapter().mapFixture(
                classesCsv =
                    """|id,class_name,superclass_id,classloader_id,kind
                    |10,java.lang.Object,0,0,normal
                    |11,com.example.Item,10,0,normal
                    """.trimMargin(),
                objectsCsv =
                    """|id,self_size,type_id,heap_type,root_type
                    |100,24,11,app,jni_global
                    |101,16,11,app,[NULL]
                    """.trimMargin(),
                referencesCsv = "owner_id,owned_id,field_name\n100,101,next\n",
            )

        assertEquals(2, result.heapDump.instances.size)
        assertEquals(
            "com.example.Item",
            result.heapDump.instances
                .first()
                .className,
        )
        assertEquals(
            24L,
            result.heapDump.instances
                .first()
                .shallowSize,
        )
        assertEquals(
            1,
            result.heapDump.instances
                .first()
                .references.size,
        )
        assertEquals(1, result.heapDump.gcRoots.size)
        assertFalse(
            result.heapDump.classes
                .first { it.name == "com.example.Item" }
                .instanceSizeKnown,
        )
        assertTrue(JavaHeapCapabilities.REFERENCES in result.availableCapabilities)
    }

    @Test
    fun `preserves nullable processor fields as explicitly unknown`() {
        val result =
            JavaHeapTraceProcessorAdapter().mapFixture(
                classesCsv = "id,class_name,superclass_id,classloader_id,kind\n10,[NULL],[NULL],[NULL],[NULL]\n",
                objectsCsv = "id,self_size,type_id,heap_type,root_type\n100,[NULL],10,[NULL],[NULL]\n",
                referencesCsv = "owner_id,owned_id,field_name\n",
            )

        val heapClass = result.heapDump.classes.single()
        val instance = result.heapDump.instances.single()
        assertEquals(com.androidperformancestudio.memory.model.HeapClass.UNKNOWN_CLASS_NAME, heapClass.name)
        assertFalse(heapClass.superClassObjectIdKnown)
        assertFalse(heapClass.classLoaderObjectIdKnown)
        assertFalse(instance.shallowSizeKnown)
        assertFalse(JavaHeapCapabilities.SHALLOW_SIZES in result.availableCapabilities)
        assertTrue(result.heapDump.warnings.any { "unknown" in it.message })
    }
}
