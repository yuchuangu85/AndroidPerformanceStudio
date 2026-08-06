package com.androidperformancestudio.memory.analysis

import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapClass
import com.androidperformancestudio.memory.model.HeapDiffMatchMode
import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.HeapInstance
import com.androidperformancestudio.memory.model.HeapRoot
import com.androidperformancestudio.memory.model.HeapRootKind
import com.androidperformancestudio.memory.model.ObjectReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryDeepAnalysisTest {
    @Test
    fun `lengauer tarjan computes immediate dominators and retained sizes for a diamond`() {
        val result =
            DominatorTreeAnalyzer().analyze(
                heap(
                    instance(1, "Root", refs = listOf(ref("left", 2), ref("right", 3))),
                    instance(2, "Left", refs = listOf(ref("shared", 4))),
                    instance(3, "Right", refs = listOf(ref("shared", 4))),
                    instance(4, "Shared"),
                    roots = listOf(HeapRoot(1, HeapRootKind.JNI_GLOBAL)),
                ),
            )

        assertNull(result.immediateDominators.getValue(1))
        assertEquals(1, result.immediateDominators.getValue(2))
        assertEquals(1, result.immediateDominators.getValue(3))
        assertEquals(1, result.immediateDominators.getValue(4))
        assertEquals(40, result.retainedSizes.getValue(1))
        assertEquals(10, result.retainedSizes.getValue(4))
    }

    @Test
    fun `multiple roots cycles and unreachable objects are handled without loops`() {
        val result =
            DominatorTreeAnalyzer().analyze(
                heap(
                    instance(1, "RootA", refs = listOf(ref("shared", 3))),
                    instance(2, "RootB", refs = listOf(ref("shared", 3))),
                    instance(3, "CycleA", refs = listOf(ref("cycle", 4))),
                    instance(4, "CycleB", refs = listOf(ref("cycle", 3))),
                    instance(5, "Unreachable"),
                    roots =
                        listOf(
                            HeapRoot(1, HeapRootKind.JNI_GLOBAL),
                            HeapRoot(2, HeapRootKind.THREAD_OBJECT),
                        ),
                ),
            )

        assertNull(result.immediateDominators.getValue(3))
        assertEquals(3, result.immediateDominators.getValue(4))
        assertNull(result.immediateDominators.getValue(5))
        assertTrue(5 !in result.reachableObjectIds)
        assertEquals(10, result.retainedSizes.getValue(5))
    }

    @Test
    fun `leak rules include stable shortest root chain and bitmap metadata`() {
        val activityClass = HeapClass(objectId = 100, name = "com.example.MainActivity", instanceSize = 24)
        val singletonClass =
            HeapClass(
                objectId = 101,
                name = "com.example.Manager",
                staticReferences = listOf(ref("static activity", 2)),
            )
        val heap =
            HeapDump(
                classes = listOf(activityClass, singletonClass),
                instances =
                    listOf(
                        instance(1, "com.example.MainActivity"),
                        instance(2, "com.example.MainActivity"),
                        instance(3, "com.example.LeakingHandler", refs = listOf(ref("this$0", 2))),
                        HeapInstance(
                            objectId = 4,
                            classObjectId = 103,
                            className = "android.graphics.Bitmap",
                            shallowSize = 11L * 1024 * 1024,
                            primitiveFields = mapOf("mWidth" to 1080, "mHeight" to 1920),
                        ),
                    ),
                gcRoots =
                    listOf(
                        HeapRoot(101, HeapRootKind.STICKY_CLASS),
                        HeapRoot(3, HeapRootKind.THREAD_OBJECT),
                        HeapRoot(4, HeapRootKind.JNI_GLOBAL),
                    ),
            )

        val result = MemoryDeepAnalyzer().analyze(heap)

        assertTrue(result.leakSuspects.any { it.reason.contains("Static/singleton") })
        assertTrue(result.leakSuspects.any { it.reason.contains("Handler") })
        assertTrue(result.leakSuspects.all { it.confidence in 0f..1f })
        assertTrue(
            result.leakSuspects
                .filter { it.className.endsWith("Activity") }
                .all { it.referenceChain.isNotEmpty() },
        )
        assertEquals(1080, result.bitmapInstances.single().width)
        assertEquals(1920, result.bitmapInstances.single().height)
        assertEquals(1080L * 1920L * 4L, result.bitmapInstances.single().estimatedPixelBytes)
        assertEquals(1080L * 1920L * 4L, result.bitmapInstances.single().nativeSizeBytes)
        assertEquals(11L * 1024 * 1024, result.bitmapInstances.single().javaSizeBytes)
        assertTrue(result.leakSuspects.any { it.className == "android.graphics.Bitmap" })
    }

    @Test
    fun `heap diff classifies exact class name growth and removal without rename guesses`() {
        val diff =
            HeapDiffAnalyzer().diff(
                before = listOf(ClassStats("Old", 2, 20), ClassStats("Stable", 1, 10)),
                after = listOf(ClassStats("New", 3, 30), ClassStats("Stable", 2, 20)),
            )

        assertEquals(listOf("New"), diff.added.map { it.className })
        assertEquals(listOf("Old"), diff.removed.map { it.className })
        assertEquals(listOf("Stable"), diff.changed.map { it.className })
    }

    @Test
    fun `heap diff omits unchanged classes before ranking changes`() {
        val before = (1..20).map { ClassStats("Stable$it", 1, 10) } + ClassStats("Removed", 2, 20)
        val after = (1..20).map { ClassStats("Stable$it", 1, 10) }

        val diff = HeapDiffAnalyzer().diff(before, after)

        assertEquals(listOf("Removed"), diff.entries.map { it.className })
    }

    @Test
    fun `reachability first keeps unreachable objects at their shallow size`() {
        val heap =
            heap(
                instance(1, "Root", refs = listOf(ref("child", 2))),
                instance(2, "Child"),
                instance(3, "Garbage"),
                instance(4, "GarbageBig", refs = listOf(ref("more", 5))),
                instance(5, "GarbageChild"),
                roots = listOf(HeapRoot(1, HeapRootKind.JNI_GLOBAL)),
            )

        val result = DominatorTreeAnalyzer().analyze(heap)

        assertNull(result.immediateDominators.getValue(3))
        assertNull(result.immediateDominators.getValue(5))
        assertEquals(10, result.retainedSizes.getValue(3))
        assertEquals(10, result.retainedSizes.getValue(4))
        assertEquals(10, result.retainedSizes.getValue(5))
        assertEquals(setOf(1L, 2L), result.reachableObjectIds)
    }

    @Test
    fun `reachability first reports progress monotonic from 0 to 100`() {
        val heap =
            heap(
                instance(1, "Root", refs = listOf(ref("child", 2))),
                instance(2, "Child"),
                roots = listOf(HeapRoot(1, HeapRootKind.JNI_GLOBAL)),
            )

        val progress = mutableListOf<Int>()
        DominatorTreeAnalyzer().analyze(heap) { progress += it }

        assertEquals(0, progress.first())
        assertEquals(100, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun `leak whitelist excludes long lived framework holders retaining an activity`() {
        val frameworkClass =
            HeapClass(
                objectId = 101,
                name = "android.app.ResourcesManager",
                staticReferences = listOf(ref("static activity", 2)),
            )
        val heap =
            HeapDump(
                classes = listOf(frameworkClass),
                instances = listOf(instance(2, "com.example.MainActivity")),
                gcRoots = listOf(HeapRoot(101, HeapRootKind.STICKY_CLASS)),
            )

        val result = MemoryDeepAnalyzer().analyze(heap)

        assertTrue(result.leakSuspects.none { it.reason.contains("Static/singleton") })
    }

    @Test
    fun `weak reference only reachable bitmap is not a leak suspect`() {
        val heap =
            HeapDump(
                instances =
                    listOf(
                        instance(1, "java.lang.ref.WeakReference", refs = listOf(ref("referent", 4))),
                        HeapInstance(
                            objectId = 4,
                            classObjectId = 103,
                            className = "android.graphics.Bitmap",
                            shallowSize = 11L * 1024 * 1024,
                        ),
                    ),
                gcRoots = listOf(HeapRoot(1, HeapRootKind.JNI_GLOBAL)),
            )

        val result = MemoryDeepAnalyzer().analyze(heap)

        assertTrue(result.leakSuspects.none { it.className == "android.graphics.Bitmap" })
    }

    @Test
    fun `activity leak report tracks destroyed but retained activities`() {
        val heap =
            HeapDump(
                instances =
                    listOf(
                        HeapInstance(1, 100, "com.example.MainActivity", primitiveFields = mapOf("mDestroyed" to 1L)),
                        HeapInstance(2, 100, "com.example.MainActivity", primitiveFields = mapOf("mDestroyed" to 0L)),
                    ),
                gcRoots =
                    listOf(
                        HeapRoot(1, HeapRootKind.JNI_GLOBAL),
                        HeapRoot(2, HeapRootKind.JNI_GLOBAL),
                    ),
            )

        val result = MemoryDeepAnalyzer().analyze(heap)

        val entry = result.activityLeaks.single()
        assertEquals("com.example.MainActivity", entry.className)
        assertEquals(2, entry.liveInstanceCount)
        assertEquals(1, entry.destroyedInstanceCount)
        assertTrue(result.leakSuspects.any { it.className == "com.example.MainActivity" && it.confidence == 0.9f })
        assertTrue(result.leakSuspects.all { !it.requiresManualVerification })
    }

    @Test
    fun `heap diff with hierarchy depth keeps same name classes separate`() {
        val before =
            listOf(
                ClassStats("com.example.Foo", 2, 20, hierarchyDepth = 3),
                ClassStats("com.example.Foo", 1, 10, hierarchyDepth = 4),
            )
        val after =
            listOf(
                ClassStats("com.example.Foo", 1, 20, hierarchyDepth = 3),
                ClassStats("com.example.Foo", 2, 10, hierarchyDepth = 4),
            )

        val diff = HeapDiffAnalyzer().diff(before, after, HeapDiffMatchMode.CLASS_NAME_AND_HIERARCHY)

        assertEquals(2, diff.entries.size)
        assertTrue(diff.entries.all { it.matchedBy == HeapDiffMatchMode.CLASS_NAME_AND_HIERARCHY })
    }

    private fun heap(
        vararg instances: HeapInstance,
        roots: List<HeapRoot>,
    ): HeapDump = HeapDump(instances = instances.toList(), gcRoots = roots)

    private fun instance(
        id: Long,
        className: String,
        refs: List<ObjectReference> = emptyList(),
    ): HeapInstance = HeapInstance(id, id + 1000, className, shallowSize = 10, references = refs)

    private fun ref(
        field: String,
        target: Long,
    ): ObjectReference = ObjectReference(field, target)
}
