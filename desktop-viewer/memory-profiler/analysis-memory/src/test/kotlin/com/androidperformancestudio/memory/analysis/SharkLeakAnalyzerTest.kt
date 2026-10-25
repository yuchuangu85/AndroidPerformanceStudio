@file:Suppress("MagicNumber")

package com.androidperformancestudio.memory.analysis

import com.androidperformancestudio.memory.analysis.SharkHprofFixture.Companion.REFERENCE
import com.androidperformancestudio.memory.model.ActivityLeakEntry
import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.LeakCanaryStatus
import com.androidperformancestudio.memory.model.LeakSuspect
import shark.HprofHeader
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.HprofWriter
import shark.LibraryLeakReferenceMatcher
import shark.ObjectReporter
import shark.PrimitiveType
import shark.ReferencePattern.InstanceFieldPattern
import shark.ValueHolder.BooleanHolder
import shark.ValueHolder.IntHolder
import shark.ValueHolder.ReferenceHolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SharkLeakAnalyzerTest {
    @Test
    fun `missing hprof fails even when parsed heap is empty`() {
        val result = SharkLeakAnalyzer().analyze(HeapDump(), Path.of("missing.hprof"))

        assertEquals(LeakCanaryStatus.FAILED, result.status)
        assertEquals(0, result.candidateObjectCount)
        assertNotNull(result.analysisDurationMillis)
    }

    @Test
    fun `valid heap with no lifecycle or watched candidates returns no candidates`() =
        withHeap(build = {
            root("value" to instance(heapClass("fixture.Ordinary")))
        }) { path, _ ->
            val progress = mutableListOf<Int>()
            val result = SharkLeakAnalyzer(progress = progress::add).analyze(HeapDump(), path)

            assertEquals(LeakCanaryStatus.NO_CANDIDATES, result.status)
            assertEquals(0, result.candidateObjectCount)
            assertEquals(0, result.totalLeaks)
            assertTrue(result.analyzedObjectCount > 0)
            assertNotNull(result.analysisDurationMillis)
            assertTrue(progress.all { it in 0..100 })
            assertEquals(progress.sorted(), progress)
            assertEquals(100, progress.last())
        }

    @Test
    fun `class level heuristics do not nominate alive instances`() =
        withHeap(build = {
            root("alive" to instance(activityClass(), BooleanHolder(false)))
        }) { path, _ ->
            val heapDump =
                HeapDump(
                    leakSuspects = listOf(LeakSuspect("fixture.Screen", "large retained class")),
                    activityLeaks = listOf(ActivityLeakEntry("fixture.Screen", destroyedInstanceCount = 1)),
                )
            val result = SharkLeakAnalyzer().analyze(heapDump, path)

            assertEquals(LeakCanaryStatus.NO_CANDIDATES, result.status)
            assertEquals(0, result.candidateObjectCount)
        }

    @Test
    fun `same class alive and destroyed objects keep exact raw ids and root to leak order`() =
        withHeap(build = {
            val screen = activityClass("obfuscated.a")
            val destroyed = instance(screen, BooleanHolder(true))
            val alive = instance(screen, BooleanHolder(false))
            val nodeClass = heapClass("fixture.Node", fields = listOf("next" to REFERENCE))
            val child = instance(nodeClass, ReferenceHolder(destroyed))
            val parent = instance(nodeClass, ReferenceHolder(child))
            val root = root("chain" to parent, "alive" to alive)
            listOf(root, parent, child, destroyed)
        }) { path, ids ->
            val deobfuscatedHeap = HeapDump(leakSuspects = listOf(LeakSuspect("example.Screen", "renamed class")))
            val result = SharkLeakAnalyzer().analyze(deobfuscatedHeap, path)

            assertEquals(LeakCanaryStatus.COMPLETED, result.status, result.message)
            assertEquals(1, result.candidateObjectCount)
            val leak = result.applicationLeaks.single()
            assertEquals(ids.last(), leak.leakingObjectId)
            assertEquals("obfuscated.a", leak.leakingClassName)
            assertEquals(ids, leak.trace.map { it.objectId })
            assertEquals(listOf("chain", "next", "next", null), leak.trace.map { it.referenceName })
            assertEquals("STICKY_CLASS", leak.gcRootType)
            assertEquals("NOT_LEAKING", leak.trace.first().leakingStatus)
            assertEquals("LEAKING", leak.trace.last().leakingStatus)
            assertTrue(
                leak.trace
                    .last()
                    .leakingStatusReason
                    .orEmpty()
                    .contains("mDestroyed"),
            )
            assertTrue(leak.trace.dropLast(1).all { it.isSuspect })
            assertFalse(leak.trace.last().isSuspect)
            assertTrue(leak.trace.flatMap { it.labels }.none { it.contains("analysis.objectId=") })
            assertNotNull(leak.retainedHeapByteSize)
            assertNotNull(result.analysisDurationMillis)
        }

    @Test
    fun `multiple leaking instances of the same class keep distinct ids`() =
        withHeap(build = {
            val screen = activityClass()
            val first = instance(screen, BooleanHolder(true))
            val second = instance(screen, BooleanHolder(true))
            root("first" to first, "second" to second)
            setOf(first, second)
        }) { path, ids ->
            val result = SharkLeakAnalyzer().analyze(HeapDump(), path)

            assertEquals(LeakCanaryStatus.COMPLETED, result.status, result.message)
            assertEquals(2, result.candidateObjectCount)
            assertEquals(ids, result.applicationLeaks.map { it.leakingObjectId }.toSet())
        }

    @Test
    fun `platform and support fragments require absent manager and activation history`() {
        listOf("android.app.Fragment", "android.support.v4.app.Fragment").forEach { fragmentName ->
            withHeap(build = {
                val fragment =
                    heapClass(
                        fragmentName,
                        fields = listOf("mFragmentManager" to REFERENCE, "mIndex" to PrimitiveType.INT.hprofType),
                    )
                val manager = instance(heapClass("fixture.Manager"))
                val detached = instance(fragment, ReferenceHolder(0), IntHolder(0))
                val alive = instance(fragment, ReferenceHolder(manager), IntHolder(1))
                root("detached" to detached, "alive" to alive)
                detached
            }) { path, detached ->
                val result = SharkLeakAnalyzer().analyze(HeapDump(), path)

                assertEquals(LeakCanaryStatus.COMPLETED, result.status, result.message)
                assertEquals(1, result.candidateObjectCount)
                assertEquals(detached, result.applicationLeaks.single().leakingObjectId)
            }
        }
    }

    @Test
    fun `never added platform and support fragments remain unknown with null manager`() {
        listOf("android.app.Fragment", "android.support.v4.app.Fragment").forEach { fragmentName ->
            listOf(false, true).forEach { hasIndexField ->
                withHeap(build = {
                    val fields =
                        listOf("mFragmentManager" to REFERENCE) +
                            if (hasIndexField) listOf("mIndex" to PrimitiveType.INT.hprofType) else emptyList()
                    val fragment = heapClass(fragmentName, fields = fields)
                    val fresh =
                        if (hasIndexField) {
                            instance(fragment, ReferenceHolder(0), IntHolder(-1))
                        } else {
                            instance(fragment, ReferenceHolder(0))
                        }
                    root("fresh" to fresh)
                    fresh
                }) { path, fresh ->
                    assertUnknownLifecycle(path, fresh)
                    val result = SharkLeakAnalyzer().analyze(HeapDump(), path)

                    assertEquals(LeakCanaryStatus.NO_CANDIDATES, result.status, result.message)
                    assertEquals(0, result.candidateObjectCount)
                    assertEquals(0, result.totalLeaks)
                }
            }
        }
    }

    @Test
    fun `retaining legacy fragment is not a leak solely because manager is null`() =
        withHeap(build = {
            val fragment =
                heapClass(
                    "android.app.Fragment",
                    fields =
                        listOf(
                            "mFragmentManager" to REFERENCE,
                            "mIndex" to PrimitiveType.INT.hprofType,
                            "mRetaining" to PrimitiveType.BOOLEAN.hprofType,
                        ),
                )
            val retaining = instance(fragment, ReferenceHolder(0), IntHolder(0), BooleanHolder(true))
            root("retaining" to retaining)
            retaining
        }) { path, retaining ->
            assertUnknownLifecycle(path, retaining)
            assertEquals(LeakCanaryStatus.NO_CANDIDATES, SharkLeakAnalyzer().analyze(HeapDump(), path).status)
        }

    @Test
    fun `retained objectwatcher still identifies a fragment with no lifecycle history`() =
        withHeap(build = {
            val fragment =
                heapClass("android.support.v4.app.Fragment", fields = listOf("mFragmentManager" to REFERENCE))
            val watched = instance(fragment, ReferenceHolder(0))
            val keyedClass = watchedReferenceClass(referenceClasses())
            root("watched" to watched, "reference" to watchedReference(keyedClass, watched))
            watched
        }) { path, watched ->
            assertUnknownLifecycle(path, watched)
            val result = SharkLeakAnalyzer().analyze(HeapDump(), path)

            assertEquals(LeakCanaryStatus.COMPLETED, result.status, result.message)
            assertEquals(1, result.candidateObjectCount)
            val leak = result.applicationLeaks.single()
            assertEquals(watched, leak.leakingObjectId)
            assertTrue(
                leak.trace
                    .last()
                    .leakingStatusReason
                    .orEmpty()
                    .contains("ObjectWatcher"),
            )
        }

    @Test
    fun `androidx detached but alive fragment is not destroyed in old and kotlin registries`() {
        listOf("mState", "state").forEach { stateField ->
            withHeap(build = {
                val enumClass = heapClass("java.lang.Enum", fields = listOf("name" to REFERENCE))
                val stateClass = heapClass("androidx.lifecycle.Lifecycle\$State", superclass = enumClass)
                val destroyedState = instance(stateClass, ReferenceHolder(javaString("DESTROYED")))
                val aliveState = instance(stateClass, ReferenceHolder(javaString("RESUMED")))
                val registryClass =
                    heapClass("androidx.lifecycle.LifecycleRegistry", fields = listOf(stateField to REFERENCE))
                val destroyedRegistry = instance(registryClass, ReferenceHolder(destroyedState))
                val aliveRegistry = instance(registryClass, ReferenceHolder(aliveState))
                val fragment =
                    heapClass(
                        "androidx.fragment.app.Fragment",
                        fields =
                            listOf(
                                "mLifecycleRegistry" to REFERENCE,
                                "mDetached" to shark.PrimitiveType.BOOLEAN.hprofType,
                            ),
                    )
                val destroyed = instance(fragment, ReferenceHolder(destroyedRegistry), BooleanHolder(true))
                val detachedAlive = instance(fragment, ReferenceHolder(aliveRegistry), BooleanHolder(true))
                val unknown = instance(fragment, ReferenceHolder(0), BooleanHolder(true))
                root("destroyed" to destroyed, "alive" to detachedAlive, "unknown" to unknown)
                destroyed
            }) { path, destroyed ->
                val result = SharkLeakAnalyzer().analyze(HeapDump(), path)

                assertEquals(LeakCanaryStatus.COMPLETED, result.status, result.message)
                assertEquals(1, result.candidateObjectCount)
                assertEquals(destroyed, result.applicationLeaks.single().leakingObjectId)
            }
        }
    }

    @Test
    fun `androidx initialized registry only leaks with removal history and no manager`() {
        listOf("mState", "state").forEach { stateField ->
            withHeap(build = {
                val enumClass = heapClass("java.lang.Enum", fields = listOf("name" to REFERENCE))
                val stateClass = heapClass("androidx.lifecycle.Lifecycle\$State", superclass = enumClass)
                val initialized = instance(stateClass, ReferenceHolder(javaString("INITIALIZED")))
                val registryClass =
                    heapClass("androidx.lifecycle.LifecycleRegistry", fields = listOf(stateField to REFERENCE))
                val registry = instance(registryClass, ReferenceHolder(initialized))
                val fragmentClass =
                    heapClass(
                        "androidx.fragment.app.Fragment",
                        fields =
                            listOf(
                                "mLifecycleRegistry" to REFERENCE,
                                "mPreviousWho" to REFERENCE,
                                "mFragmentManager" to REFERENCE,
                            ),
                    )
                val previousWho = ReferenceHolder(javaString("previous-fragment-identity"))
                val manager = instance(heapClass("fixture.Manager"))
                val removed = instance(fragmentClass, ReferenceHolder(registry), previousWho, ReferenceHolder(0))
                val fresh = instance(fragmentClass, ReferenceHolder(registry), ReferenceHolder(0), ReferenceHolder(0))
                val managed = instance(fragmentClass, ReferenceHolder(registry), previousWho, ReferenceHolder(manager))
                root("removed" to removed, "fresh" to fresh, "managed" to managed)
                listOf(removed, fresh, managed)
            }) { path, ids ->
                assertUnknownLifecycle(path, ids[1])
                assertUnknownLifecycle(path, ids[2])
                val result = SharkLeakAnalyzer().analyze(HeapDump(), path)

                assertEquals(LeakCanaryStatus.COMPLETED, result.status, result.message)
                assertEquals(1, result.candidateObjectCount)
                assertEquals(ids.first(), result.applicationLeaks.single().leakingObjectId)
                assertTrue(
                    result.applicationLeaks
                        .single()
                        .trace
                        .last()
                        .leakingStatusReason
                        .orEmpty()
                        .contains("removal history=true"),
                )
            }
        }
    }

    @Test
    fun `objectwatcher includes retained exact referent and excludes cleared or unretained watches`() =
        withHeap(build = {
            val watchedClass = heapClass("fixture.Watched")
            val watched = instance(watchedClass)
            val unretained = instance(watchedClass)
            val keyedClass = watchedReferenceClass(referenceClasses())
            root(
                "watched" to watched,
                "unretained" to unretained,
                "ref" to watchedReference(keyedClass, watched),
                "duplicate" to watchedReference(keyedClass, watched),
                "unretainedRef" to watchedReference(keyedClass, unretained, retained = false),
                "clearedRef" to watchedReference(keyedClass, 0),
            )
            watched
        }) { path, watched ->
            val result = SharkLeakAnalyzer().analyze(HeapDump(), path)

            assertEquals(LeakCanaryStatus.COMPLETED, result.status, result.message)
            assertEquals(1, result.candidateObjectCount)
            val leak = result.applicationLeaks.single()
            assertEquals(watched, leak.leakingObjectId)
            assertTrue(
                leak.trace
                    .last()
                    .labels
                    .any { it.startsWith("key = ") },
            )
            assertTrue(
                leak.trace
                    .last()
                    .labels
                    .contains("retainedDurationMillis = 50"),
            )
        }

    @Test
    fun `weak and keyed referents never supply a strong leak path`() =
        withHeap(build = {
            val destroyed = instance(activityClass(), BooleanHolder(true))
            val weakClass = referenceClasses()
            val keyedClass = watchedReferenceClass(weakClass)
            val watched = instance(heapClass("fixture.Watched"))
            root(
                "weak" to instance(weakClass, ReferenceHolder(destroyed)),
                "keyed" to watchedReference(keyedClass, watched),
            )
        }) { path, _ ->
            val result = SharkLeakAnalyzer().analyze(HeapDump(), path)

            assertEquals(LeakCanaryStatus.COMPLETED, result.status, result.message)
            assertEquals(2, result.candidateObjectCount)
            assertEquals(0, result.totalLeaks)
        }

    @Test
    fun `runtime finalizer and cleaner links do not supply leak paths but strong paths still do`() {
        val runtimeLinks =
            listOf(
                "java.lang.ref.Finalizer" to "prev",
                "java.lang.ref.Finalizer" to "element",
                "java.lang.ref.Finalizer" to "next",
                "java.lang.ref.FinalizerReference" to "prev",
                "java.lang.ref.FinalizerReference" to "element",
                "java.lang.ref.FinalizerReference" to "next",
                "sun.misc.Cleaner" to "prev",
                "sun.misc.Cleaner" to "next",
            )
        runtimeLinks.forEach { (className, fieldName) ->
            listOf(false, true).forEach { strongPath ->
                withHeap(build = {
                    val destroyed = instance(activityClass(), BooleanHolder(true))
                    val runtimeClass = heapClass(className, fields = listOf(fieldName to REFERENCE))
                    val runtime = instance(runtimeClass, ReferenceHolder(destroyed))
                    if (strongPath) root("runtime" to runtime, "strong" to destroyed) else root("runtime" to runtime)
                    destroyed
                }) { path, destroyed ->
                    val result = SharkLeakAnalyzer().analyze(HeapDump(), path)

                    assertEquals(LeakCanaryStatus.COMPLETED, result.status, result.message)
                    assertEquals(1, result.candidateObjectCount)
                    assertEquals(if (strongPath) 1 else 0, result.totalLeaks, "$className.$fieldName")
                    if (strongPath) {
                        assertEquals(destroyed, result.applicationLeaks.single().leakingObjectId)
                        assertEquals(
                            "strong",
                            result.applicationLeaks
                                .single()
                                .trace
                                .first()
                                .referenceName,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `finalizer watchdog java locals are ignored without ignoring ordinary thread locals`() {
        listOf("FinalizerWatchdogDaemon", "ordinary-worker").forEach { threadName ->
            withHeap(build = {
                val destroyed = instance(activityClass(), BooleanHolder(true))
                javaLocal(threadName, destroyed)
                destroyed
            }) { path, destroyed ->
                val result = SharkLeakAnalyzer().analyze(HeapDump(), path)

                assertEquals(LeakCanaryStatus.COMPLETED, result.status, result.message)
                assertEquals(1, result.candidateObjectCount)
                assertEquals(if (threadName == "FinalizerWatchdogDaemon") 0 else 1, result.totalLeaks)
                if (threadName != "FinalizerWatchdogDaemon") {
                    val leak = result.applicationLeaks.single()
                    assertEquals(destroyed, leak.leakingObjectId)
                    assertEquals("LOCAL", leak.trace.first().referenceType)
                }
            }
        }
    }

    @Test
    fun `explicit core reference matcher preserves library classification and labels`() =
        withHeap(build = {
            val destroyed = instance(activityClass(), BooleanHolder(true))
            val manager = heapClass("fixture.LibraryOwner", fields = listOf("retained" to REFERENCE))
            root("manager" to instance(manager, ReferenceHolder(destroyed)))
            destroyed
        }) { path, destroyed ->
            val matcher =
                LibraryLeakReferenceMatcher(
                    pattern = InstanceFieldPattern("fixture.LibraryOwner", "retained"),
                    description = "Synthetic library retention for adapter classification coverage.",
                )
            val result = SharkLeakAnalyzer(additionalReferenceMatchers = listOf(matcher)).analyze(HeapDump(), path)

            assertEquals(LeakCanaryStatus.COMPLETED, result.status, result.message)
            assertTrue(result.applicationLeaks.isEmpty())
            val leak = result.libraryLeaks.single()
            assertEquals(destroyed, leak.leakingObjectId)
            assertTrue(leak.trace.flatMap { it.labels }.any { it.startsWith("Library leak match:") })
        }

    @Test
    fun `candidate scan progress propagates cancellation`() =
        withHeap(build = {
            val ordinary = heapClass("fixture.Ordinary")
            repeat(600) { instance(ordinary) }
        }) { path, _ ->
            val cancellation = CancellationException("cancel discovery")
            var discoveryCallbacks = 0
            val analyzer =
                SharkLeakAnalyzer { value ->
                    if (value == 5 && ++discoveryCallbacks == 2) throw cancellation
                }

            assertSame(cancellation, assertFailsWith<CancellationException> { analyzer.analyze(HeapDump(), path) })
        }

    @Test
    fun `path traversal progress propagates cancellation caught by shark`() =
        withHeap(build = {
            var next = instance(activityClass(), BooleanHolder(true))
            val node = heapClass("fixture.Node", fields = listOf("next" to REFERENCE))
            repeat(600) { next = instance(node, ReferenceHolder(next)) }
            root("chain" to next)
        }) { path, _ ->
            val cancellation = CancellationException("cancel paths")
            val progress = mutableListOf<Int>()
            val analyzer =
                SharkLeakAnalyzer { value ->
                    progress += value
                    if (value >= 48 && progress.count { it == value } > 1) throw cancellation
                }

            assertSame(cancellation, assertFailsWith<CancellationException> { analyzer.analyze(HeapDump(), path) })
            assertTrue(progress.all { it in 0..100 })
        }

    private fun assertUnknownLifecycle(
        path: Path,
        objectId: Long,
    ) {
        path.toFile().openHeapGraph().use { graph ->
            val reporter = ObjectReporter(graph.findObjectById(objectId))
            LifecycleObjectInspector.inspect(reporter)
            assertTrue(reporter.leakingReasons.isEmpty())
            assertTrue(reporter.notLeakingReasons.isEmpty())
        }
    }

    private fun <T> withHeap(
        build: SharkHprofFixture.() -> T,
        assertions: (Path, T) -> Unit,
    ) {
        val path = Files.createTempFile("shark-analysis-", ".hprof")
        try {
            val data =
                HprofWriter
                    .openWriterFor(path.toFile(), HprofHeader(identifierByteSize = Long.SIZE_BYTES))
                    .use { writer -> SharkHprofFixture(writer).build() }
            path.toFile().openHeapGraph().use { graph -> assertTrue(graph.objectCount > 0) }
            assertions(path, data)
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
