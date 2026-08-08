package com.androidperformancestudio.memory.analysis

import com.androidperformancestudio.memory.model.HeapClass
import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.HeapInstance
import com.androidperformancestudio.memory.model.ObjectReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProguardMappingTest {
    @Test
    fun `parses class mappings and ignores member lines`() {
        val text =
            """
            com.example.MainActivity -> a.b.c:
                int mFoo -> a
                void onCreate() -> b
            com.example.Helper -> d.e:
            android.app.Activity -> android.app.Activity:
            """.trimIndent()

        val mapping = ProguardMappingParser.parse(text)

        assertEquals("com.example.MainActivity", mapping.originalName("a.b.c"))
        assertEquals("com.example.Helper", mapping.originalName("d.e"))
        assertEquals("android.app.Activity", mapping.originalName("android.app.Activity"))
        assertEquals("a.b.c", mapping.obfuscatedName("com.example.MainActivity"))
        assertNull(mapping.obfuscatedName("no.such.class"))
    }

    @Test
    fun `withDeobfuscation rewrites class names across classes instances and references`() {
        val mapping = ProguardMappingParser.parse("com.example.MainActivity -> a.b.c:\n")
        val dump =
            HeapDump(
                classes = listOf(HeapClass(objectId = 1, name = "a.b.c")),
                instances =
                    listOf(
                        HeapInstance(
                            objectId = 10,
                            classObjectId = 1,
                            className = "a.b.c",
                            references = listOf(ObjectReference("next", 20, targetClassName = "a.b.c")),
                        ),
                    ),
            )

        val result = dump.withDeobfuscation(mapping)

        assertEquals("com.example.MainActivity", result.classes.single().name)
        assertEquals("com.example.MainActivity", result.instances.single().className)
        assertEquals(
            "com.example.MainActivity",
            result.instances
                .single()
                .references
                .single()
                .targetClassName,
        )
    }

    @Test
    fun `empty mapping is a no-op`() {
        val dump = HeapDump(instances = listOf(HeapInstance(1, 2, className = "a.b.c")))
        assertEquals(dump, dump.withDeobfuscation(ProguardMappingParser.parse("")))
    }

    @Test
    fun `deobfuscation preserves java and descriptor array syntax`() {
        val mapping = ProguardMappingParser.parse("com.example.Item -> a.b.C:\n")
        val dump =
            HeapDump(
                classes =
                    listOf(
                        HeapClass(1, "a.b.C[]"),
                        HeapClass(2, "[[La/b/C;"),
                    ),
            )

        assertEquals(
            listOf("com.example.Item[]", "[[Lcom/example/Item;"),
            dump.withDeobfuscation(mapping).classes.map { it.name },
        )
    }

    @Test
    fun `obfuscated class heuristic ignores framework classes`() {
        assertTrue(isLikelyObfuscatedClassName("a.b.c"))
        assertTrue(isLikelyObfuscatedClassName("x.y.z.aa"))
        assertFalse(isLikelyObfuscatedClassName("android.app.Activity"))
        assertFalse(isLikelyObfuscatedClassName("java.lang.String"))
        assertFalse(isLikelyObfuscatedClassName("com.example.MainActivity"))
        assertFalse(isLikelyObfuscatedClassName("MainActivity"))
    }
}
