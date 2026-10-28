package com.androidperformancestudio.memory.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryInspectorTreeTest {
    @Test
    fun `nearest references use each expanded objects depth and preserve original child identity`() {
        val root = detail(42, 5, references = listOf(field("owner", 2, 4)))
        val owner =
            detail(
                2,
                4,
                references = listOf(field("nearer", 3, 3), field("equal", 4, 4), field("unknown", 5)),
            )
        val entries =
            MemoryInspectorTreeBuilder(
                root,
                referencesTab = true,
                nearestOnly = true,
                expanded = setOf("references/0"),
                visibleCounts = emptyMap(),
                objects = mapOf(MemoryInspectorObjectKey(2) to MemoryInspectorObjectState.Loaded(owner)),
            ).build()
        val values = entries.filterIsInstance<MemoryInspectorTreeEntry.Value>()
        assertEquals(listOf("", "owner", "nearer"), values.map { it.field.name })
        assertEquals("memory-inspector-row-0-child-0", values.last().tag)
    }

    @Test
    fun `shared targets keep independent branches and ancestor cycles terminate`() {
        val root = detail(42, fields = listOf(field("left", 2), field("right", 2)))
        val owner = detail(2, fields = listOf(field("child", 3), field("back", 42)))
        val entries =
            MemoryInspectorTreeBuilder(
                root,
                referencesTab = false,
                nearestOnly = false,
                expanded = setOf("fields:0/0", "fields:0/1", "fields:0/0@0/1"),
                visibleCounts = emptyMap(),
                objects = mapOf(MemoryInspectorObjectKey(2) to MemoryInspectorObjectState.Loaded(owner)),
            ).build()
        val values = entries.filterIsInstance<MemoryInspectorTreeEntry.Value>()
        assertEquals(listOf("left", "child", "back", "right", "child", "back"), values.map { it.field.name })
        assertEquals(values.size, values.map { it.key }.distinct().size)
        assertNull(values.first { it.field.name == "back" }.loadKey)
        assertEquals(
            listOf(MemoryInspectorTreeMessage.CYCLE),
            entries.filterIsInstance<MemoryInspectorTreeEntry.Message>().map { it.kind },
        )
    }

    @Test
    fun `deep acyclic expansion has a visible boundary rather than unbounded recursion`() {
        var path = "fields:0/0"
        val expanded =
            buildSet {
                repeat(MAX_INSPECTOR_TREE_DEPTH + 3) {
                    add(path)
                    path += "@0/0"
                }
            }
        val objects =
            (2L..MAX_INSPECTOR_TREE_DEPTH + 3L).associate { id ->
                val child = detail(id, fields = listOf(field("next", id + 1)))
                MemoryInspectorObjectKey(id) to MemoryInspectorObjectState.Loaded(child)
            }
        val entries =
            MemoryInspectorTreeBuilder(
                detail(1, fields = listOf(field("next", 2))),
                referencesTab = false,
                nearestOnly = false,
                expanded = expanded,
                visibleCounts = emptyMap(),
                objects = objects,
            ).build()
        assertEquals(MAX_INSPECTOR_TREE_DEPTH + 1, entries.filterIsInstance<MemoryInspectorTreeEntry.Value>().size)
        assertEquals(MemoryInspectorTreeMessage.DEPTH_LIMIT, (entries.last() as MemoryInspectorTreeEntry.Message).kind)
    }

    @Test
    fun `null and primitive fields stay leaves even when paths were previously expanded`() {
        val root = detail(42, fields = listOf(field("null", 0), MemoryInstanceField("count", "1", null, null)))
        val entries =
            MemoryInspectorTreeBuilder(
                root,
                referencesTab = false,
                nearestOnly = false,
                expanded = setOf("fields:0/0", "fields:0/1"),
                visibleCounts = emptyMap(),
                objects = emptyMap(),
            ).build()
        assertEquals(2, entries.size)
        assertTrue(entries.filterIsInstance<MemoryInspectorTreeEntry.Value>().all { !it.expandable && !it.expanded })
    }

    @Test
    fun `empty cached continuation reports unavailable for root and nested arrays without another more action`() {
        val array = arrayDetail(2, 0, arrayFields(0, 200), 450)
        val objects =
            mapOf(
                MemoryInspectorObjectKey(2) to MemoryInspectorObjectState.Loaded(array),
                MemoryInspectorObjectKey(2, 200) to
                    MemoryInspectorObjectState.Loaded(arrayDetail(2, 200, emptyList(), 450)),
            )
        listOf(false, true).forEach { nested ->
            val prefix = if (nested) "fields:0/0@0" else "fields:0"
            val depth = if (nested) 1 else 0
            val entries =
                MemoryInspectorTreeBuilder(
                    root = if (nested) detail(42, fields = listOf(field("array", 2))) else array,
                    referencesTab = false,
                    nearestOnly = false,
                    expanded = if (nested) setOf("fields:0/0") else emptySet(),
                    visibleCounts = mapOf(prefix to 300),
                    objects = objects,
                ).build()
            val values = entries.filterIsInstance<MemoryInspectorTreeEntry.Value>().filter { it.depth == depth }
            assertEquals(array.fields.asReversed(), values.map { it.field })
            assertEquals((199 downTo 0).map { "$prefix/$it" }, values.map { it.key })
            assertTrue(entries.none { it is MemoryInspectorTreeEntry.More })
            val message = entries.filterIsInstance<MemoryInspectorTreeEntry.Message>().single()
            assertEquals(MemoryInspectorTreeMessage.ARRAY_UNAVAILABLE, message.kind)
            assertEquals("$prefix/empty", message.key)
            assertEquals(depth, message.depth)
        }
    }

    @Test
    fun `higher metric shared array pages preserve sibling rows and keys while prior batches remain cumulative`() {
        val left = "fields:0/0"
        val right = "fields:0/1"
        val array = arrayDetail(2, 0, arrayFields(0, 200), 300)
        val objects =
            mapOf(
                MemoryInspectorObjectKey(2) to MemoryInspectorObjectState.Loaded(array),
                MemoryInspectorObjectKey(1000) to
                    MemoryInspectorObjectState.Loaded(
                        detail(1000, fields = listOf(MemoryInstanceField("leaf", "1", null, null))),
                    ),
            )

        fun build(
            limit: Int,
            cache: Map<MemoryInspectorObjectKey, MemoryInspectorObjectState>,
        ): List<MemoryInspectorTreeEntry> =
            MemoryInspectorTreeBuilder(
                root = detail(42, fields = listOf(field("left", 2), field("right", 2))),
                referencesTab = false,
                nearestOnly = false,
                expanded = setOf(left, right, "$right@0/0"),
                visibleCounts = mapOf("$left@0" to limit),
                objects = cache,
            ).build()

        fun branch(
            entries: List<MemoryInspectorTreeEntry>,
            parentKey: String,
        ): List<MemoryInspectorTreeEntry.Value> =
            entries.filterIsInstance<MemoryInspectorTreeEntry.Value>().filter { it.key.startsWith("$parentKey@0/") }

        val initial = build(100, objects)
        val initialLeft = branch(initial, left)
        val initialRight = branch(initial, right)
        assertEquals((99 downTo 0).map { "[$it]" }, initialLeft.map { it.field.name })
        assertEquals(initialLeft.map { it.field }, initialRight.filter { it.depth == 1 }.map { it.field })
        assertTrue(initialRight.any { it.key == "$right@0/0@0/0" && it.field.name == "leaf" })

        val second = build(200, objects)
        val secondLeft = branch(second, left)
        assertEquals(200, secondLeft.size)
        assertTrue(secondLeft.containsAll(initialLeft))
        assertEquals(initialRight, branch(second, right))
        val more = second.filterIsInstance<MemoryInspectorTreeEntry.More>().single { it.parentKey == "$left@0" }
        assertEquals(100, more.count)
        assertEquals(MemoryInspectorObjectKey(2, 200), more.loadKey)

        val cachedPage =
            objects +
                (
                    MemoryInspectorObjectKey(2, 200) to
                        MemoryInspectorObjectState.Loaded(arrayDetail(2, 200, arrayFields(200, 100), 300))
                )
        val unchangedLimits = build(200, cachedPage)
        assertEquals(secondLeft, branch(unchangedLimits, left))
        assertEquals(initialRight, branch(unchangedLimits, right))

        val complete = build(300, cachedPage)
        val completeLeft = branch(complete, left)
        assertEquals(300, completeLeft.size)
        assertEquals((299 downTo 200).map { "[$it]" }, completeLeft.take(100).map { it.field.name })
        assertTrue(completeLeft.containsAll(secondLeft))
        assertEquals(initialRight, branch(complete, right))
        assertTrue(complete.none { it is MemoryInspectorTreeEntry.More && it.parentKey == "$left@0" })
        assertEquals(complete.size, complete.map { it.key }.distinct().size)
    }

    private fun arrayDetail(
        objectId: Long,
        start: Int,
        fields: List<MemoryInstanceField>,
        count: Int,
    ): MemoryInstanceDetail =
        detail(objectId, fields = fields).copy(
            isArray = true,
            arrayStart = start,
            elementCount = count,
        )

    private fun arrayFields(
        start: Int,
        count: Int,
    ): List<MemoryInstanceField> =
        List(count) { offset ->
            val index = start + offset
            field("[$index]", 1000L + index).copy(retainedSize = index + 1L)
        }

    private fun field(
        name: String,
        target: Long,
        depth: Int? = null,
    ): MemoryInstanceField = MemoryInstanceField(name, "Object@$target", target, "sample.Object", depth)

    private fun detail(
        objectId: Long,
        depth: Int? = null,
        fields: List<MemoryInstanceField> = emptyList(),
        references: List<MemoryInstanceField> = emptyList(),
    ): MemoryInstanceDetail =
        MemoryInstanceDetail(
            objectId = objectId,
            className = "sample.Object",
            shallowSize = 16,
            retainedSize = null,
            depth = depth,
            isArray = false,
            elementCount = null,
            fields = fields,
            references = references,
            referenceChain = emptyList(),
        )
}
