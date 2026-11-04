package com.androidperformancestudio.profileanalysis

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallStackContractsTest {
    @Test
    fun `search parser trims blanks and preserves Firefox comma order`() {
        assertEquals(listOf("render", "libc"), parseFlameSearchTerms(" render, ,libc "))
    }

    @Test
    fun `call node path uses structural equality`() {
        assertEquals(
            CallNodePath(listOf(FlameFunctionId(1), FlameFunctionId(2))),
            CallNodePath(listOf(FlameFunctionId(1), FlameFunctionId(2))),
        )
    }

    @Test
    fun `call node path snapshots its source before map lookup`() {
        val source = mutableListOf(FlameFunctionId(1), FlameFunctionId(2))
        val path = CallNodePath(source)
        val idsByPath = mutableMapOf(path to FlameCallNodeId(7))
        val table = callNodeTable(idsByPath = idsByPath)

        source.clear()
        idsByPath.clear()

        assertEquals(listOf(FlameFunctionId(1), FlameFunctionId(2)), path.functions)
        assertEquals(FlameCallNodeId(7), table.findByPath(CallNodePath(listOf(FlameFunctionId(1), FlameFunctionId(2)))))
    }

    @Test
    fun `call node table snapshots constructor inputs and returned arrays`() {
        val ids = longArrayOf(7)
        val parentIndexes = intArrayOf(-1)
        val frameIds = longArrayOf(11)
        val depths = intArrayOf(0)
        val inclusiveWeights = longArrayOf(5)
        val selfWeights = longArrayOf(3)
        val sampleCounts = longArrayOf(2)
        val threadCounts = intArrayOf(1)
        val categories = mutableListOf<String?>("UI")
        val frames = mutableMapOf(11L to frame(11))
        val table =
            CallNodeTable(
                ids = ids,
                parentIndexes = parentIndexes,
                frameIds = frameIds,
                depths = depths,
                inclusiveWeights = inclusiveWeights,
                selfWeights = selfWeights,
                sampleCounts = sampleCounts,
                threadCounts = threadCounts,
                categories = categories,
                framesById = frames,
            )

        ids[0] = 99
        parentIndexes[0] = 99
        frameIds[0] = 99
        depths[0] = 99
        inclusiveWeights[0] = 99
        selfWeights[0] = 99
        sampleCounts[0] = 99
        threadCounts[0] = 99
        categories[0] = "mutated"
        frames.clear()
        table.ids[0] = 100
        table.parentIndexes[0] = 100
        table.frameIds[0] = 100
        table.depths[0] = 100
        table.inclusiveWeights[0] = 100
        table.selfWeights[0] = 100
        table.sampleCounts[0] = 100
        table.threadCounts[0] = 100

        assertContentEquals(longArrayOf(7), table.ids)
        assertContentEquals(intArrayOf(-1), table.parentIndexes)
        assertContentEquals(longArrayOf(11), table.frameIds)
        assertContentEquals(intArrayOf(0), table.depths)
        assertContentEquals(longArrayOf(5), table.inclusiveWeights)
        assertContentEquals(longArrayOf(3), table.selfWeights)
        assertContentEquals(longArrayOf(2), table.sampleCounts)
        assertContentEquals(intArrayOf(1), table.threadCounts)
        assertEquals(listOf("UI"), table.categories)
        assertEquals(frame(11), table.framesById[11])
        assertContentEquals(longArrayOf(2), table.selfSampleCounts)
        assertNull(table.categoryMetricsAt(0))
    }

    @Test
    fun `explicit self counts and nested category metrics are immutable value snapshots`() {
        val selfSamples = longArrayOf(2)
        val metrics = mutableListOf(CallNodeCategoryMetrics("UI", "Input", 2, 2, 5, 5))
        val categories = mutableListOf<List<CallNodeCategoryMetrics>>(metrics)
        val table = callNodeTable(selfSampleCounts = selfSamples, categoryMetrics = categories)
        val expected = callNodeTable(selfSampleCounts = longArrayOf(2), categoryMetrics = categories)

        selfSamples[0] = 99
        metrics.clear()
        categories.clear()
        table.selfSampleCounts[0] = 99

        assertEquals(2L, table.selfSampleCountAt(0))
        assertEquals(listOf(CallNodeCategoryMetrics("UI", "Input", 2, 2, 5, 5)), table.categoryMetricsAt(0))
        assertEquals(expected, table)
        assertEquals(expected.hashCode(), table.hashCode())
        assertNotEquals(table, callNodeTable(selfSampleCounts = longArrayOf(1)))
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (table.categoryMetricsAt(0) as MutableList<CallNodeCategoryMetrics>).clear()
        }
    }

    @Test
    fun `legacy self counts derive from direct children without treating grandchildren twice`() {
        val table =
            CallNodeTable(
                ids = longArrayOf(1, 2, 3, 4),
                parentIndexes = intArrayOf(-1, 0, 1, 0),
                frameIds = longArrayOf(11, 11, 11, 11),
                depths = intArrayOf(0, 1, 2, 1),
                inclusiveWeights = longArrayOf(5, 3, 1, 1),
                selfWeights = longArrayOf(1, 2, 1, 1),
                sampleCounts = longArrayOf(5, 3, 1, 1),
                threadCounts = intArrayOf(1, 1, 1, 1),
                categories = List(4) { null },
                framesById = mapOf(11L to frame(11)),
            )

        assertContentEquals(longArrayOf(1, 2, 1, 1), table.selfSampleCounts)
    }

    @Test
    fun `symbol identities distinguish Java string hash collisions and field boundaries`() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertNotEquals(FlameFunctionId.fromSymbol("Aa", "lib.so"), FlameFunctionId.fromSymbol("BB", "lib.so"))
        assertNotEquals(FlameFunctionId.fromSymbol("ab", "c"), FlameFunctionId.fromSymbol("a", "bc"))
        assertNotEquals(FlameFunctionId.fromSymbol("loopOnce", "a.so"), FlameFunctionId.fromSymbol("loopOnce", "b.so"))
        assertEquals(FlameFunctionId.fromSymbol("loopOnce", "a.so"), FlameFunctionId.fromSymbol("loopOnce", "a.so"))
    }

    @Test
    fun `sample weighting uses source time and sample id order without mutating facts or refilling filter gaps`() {
        val source =
            CallStackTable(
                mapOf(11L to frame(11)),
                listOf(
                    weightedStack(listOf(11)).copy(sampleId = 1, timestampNanos = 10_000_000, weight = 9),
                    weightedStack(listOf(11)).copy(sampleId = 9, timestampNanos = 0, weight = 2),
                    weightedStack(listOf(11)).copy(sampleId = 2, timestampNanos = 0, weight = 3),
                ),
            )

        val samples = source.withWeightType(CallStackWeightType.SAMPLES)
        assertEquals(CallStackWeightType.NANOSECONDS, source.weightType)
        assertEquals(listOf(9L, 2L, 3L), source.stacks.map { it.weight })
        assertTrue(source.stacks.all { it.tracedDurationNanos == null })
        assertEquals(CallStackWeightType.SAMPLES, samples.weightType)
        assertEquals(listOf(1L, 1L, 1L), samples.stacks.map { it.weight })
        assertEquals(listOf(1_000_000L, 10_000_000L, 0L), samples.stacks.map { it.tracedDurationNanos })
        val filtered = samples.copy(stacks = listOf(samples.stacks.last()))
        assertEquals(CallStackWeightType.SAMPLES, filtered.weightType)
        assertEquals(
            0L,
            filtered
                .withWeightType(CallStackWeightType.SAMPLES)
                .stacks
                .single()
                .tracedDurationNanos,
        )
        assertEquals(listOf(9L, 2L, 3L), source.withWeightType(CallStackWeightType.EVENTS).stacks.map { it.weight })
    }

    @Test
    fun `merged equal timestamps follow reverse exported thread order before sample id`() {
        val source =
            CallStackTable(
                mapOf(11L to frame(11)),
                listOf(
                    weightedStack(listOf(11)).copy(sampleId = 1, timestampNanos = 0, threadKey = "canonical:1"),
                    weightedStack(listOf(11)).copy(sampleId = 2, timestampNanos = 0, threadKey = "canonical:2"),
                    weightedStack(listOf(11)).copy(
                        sampleId = 3,
                        timestampNanos = 10_000_000,
                        threadKey = "canonical:1",
                    ),
                ),
            )
        val merged = source.withWeightType(CallStackWeightType.SAMPLES)
        assertEquals(listOf(10_000_000L, 0L, 1_000_000L), merged.stacks.map { it.tracedDurationNanos })
        assertEquals(listOf(1L, 2L, 3L), merged.stacks.map { it.sampleId })
        assertTrue(source.stacks.all { it.tracedDurationNanos == null })
    }

    @Test
    fun `Gecko cumulative millisecond reconstruction resolves cross thread ties without editing timestamps`() {
        val start = 80_000_000_000_000L
        val source =
            CallStackTable(
                mapOf(11L to frame(11)),
                listOf(
                    weightedStack(listOf(11)).copy(
                        sampleId = 1,
                        timestampNanos = start + 670_487,
                        threadKey = "canonical:2",
                    ),
                    weightedStack(listOf(11)).copy(
                        sampleId = 2,
                        timestampNanos = start + 1_090_098,
                        threadKey = "canonical:2",
                    ),
                    weightedStack(listOf(11)).copy(
                        sampleId = 3,
                        timestampNanos = start + 1_090_098,
                        threadKey = "canonical:1",
                    ),
                    weightedStack(listOf(11)).copy(
                        sampleId = 4,
                        timestampNanos = start + 11_090_098,
                        threadKey = "canonical:2",
                    ),
                ),
            )
        val merged = source.withWeightType(CallStackWeightType.SAMPLES)
        assertEquals(10_000_000, merged.stacks[1].tracedDurationNanos)
        assertEquals(0, merged.stacks[2].tracedDurationNanos)
        assertEquals(source.stacks.map { it.timestampNanos }, merged.stacks.map { it.timestampNanos })
        val preview =
            source
                .copy(stacks = source.stacks.drop(1).dropLast(1))
                .withWeightType(CallStackWeightType.SAMPLES, tracedTimeSource = source)
        assertEquals(listOf(1_000_000L, 0L), preview.stacks.map { it.tracedDurationNanos })
    }

    @Test
    fun `weight type and traced durations survive immutable copies and saturate timestamp overflow`() {
        val source =
            CallStackTable(
                mapOf(11L to frame(11)),
                listOf(
                    weightedStack(listOf(11)).copy(sampleId = 1, timestampNanos = Long.MIN_VALUE),
                    weightedStack(listOf(11)).copy(sampleId = 2, timestampNanos = Long.MAX_VALUE),
                ),
            ).withWeightType(CallStackWeightType.SAMPLES)
        assertEquals(listOf(Long.MAX_VALUE, 1_000_000L), source.stacks.map { it.tracedDurationNanos })
        assertEquals(
            Long.MAX_VALUE,
            source.stacks
                .first()
                .copy()
                .tracedDurationNanos,
        )
        assertEquals(
            CallStackWeightType.SAMPLES,
            CallStackAnalysisQuery(weightType = source.weightType).copy().weightType,
        )
        val snapshot =
            FlameGraphSnapshot(
                CallStackAnalysisQuery(),
                callNodeTable(),
                emptyRows(),
                1,
                null,
                emptyList(),
                weightType = CallStackWeightType.SAMPLES,
            )
        assertEquals(CallStackWeightType.SAMPLES, snapshot.copy().weightType)
    }

    @Test
    fun `sample weighting cannot hide a negative source weight`() {
        val source = CallStackTable(mapOf(11L to frame(11)), listOf(weightedStack(listOf(11)).copy(weight = -1)))

        assertFailsWith<IllegalArgumentException> { source.withWeightType(CallStackWeightType.SAMPLES) }
    }

    @Test
    fun `Gecko module categories keep JIT distinct from managed code`() {
        val cases =
            mapOf(
                "boot.art" to "User",
                "[kernel.kallsyms]" to "Kernel",
                "/system/module.ko" to "Kernel",
                "/system/lib64/libart.so" to "Native",
                "/data/app/base.vdex" to "DEX",
                "/system/framework/boot.oat" to "OAT",
                "[JIT app cache]" to "JIT",
            )
        cases.forEach { (resource, expected) ->
            assertEquals(expected, frame(11).copy(resource = resource).firefoxCategoryName())
            assertEquals(expected, firefoxCategoryName("work", resource))
        }
        assertEquals("Off-CPU", firefoxCategoryName("__schedule", "[kernel.kallsyms]"))
        assertEquals(
            "Off-CPU",
            frame(11).copy(symbolName = "__schedule idle", resource = "[kernel.kallsyms]").firefoxCategoryName(),
        )
    }

    @Test
    fun `Gecko categories use actual resources rather than Kotlin symbols or parent directory text`() {
        val symbols = listOf("androidx.kotlin.run", "app.something.work", "app.kallsyms.render", "__schedule")
        symbols.forEach { symbol ->
            assertEquals("JIT", firefoxCategoryName(symbol, "[JIT app cache]"))
            assertEquals("User", firefoxCategoryName(symbol, "boot.art"))
            assertEquals("DEX", firefoxCategoryName(symbol, "/data/app/package.ko.something/base.vdex"))
            assertEquals(
                "JIT",
                frame(11).copy(symbolName = symbol, resource = "[JIT app cache]").firefoxCategoryName(),
            )
        }
        assertEquals("Kernel", firefoxCategoryName("__schedule_work", "/system/module.ko"))
        assertEquals("Off-CPU", firefoxCategoryName("__schedule idle", "/system/module.ko"))
    }

    @Test
    fun `flame graph rows snapshot nested arrays and returned arrays`() {
        val row = intArrayOf(1, 2)
        val rowsSource = mutableListOf(row)
        val starts = doubleArrayOf(0.0, 0.5)
        val ends = doubleArrayOf(0.5, 1.0)
        val rows = FlameGraphRows(rowsSource, starts, ends, startsAtBottom = true)

        row[0] = 99
        rowsSource.clear()
        starts[0] = 99.0
        ends[0] = 99.0
        rows.nodeIndexesByRow[0][0] = 100
        rows.starts[0] = 100.0
        rows.ends[0] = 100.0

        assertContentEquals(intArrayOf(1, 2), rows.nodeIndexesByRow.single())
        assertContentEquals(doubleArrayOf(0.0, 0.5), rows.starts)
        assertContentEquals(doubleArrayOf(0.5, 1.0), rows.ends)
    }

    @Test
    fun `compact flame access copies only the requested row and resolves node metadata by index`() {
        val rows =
            FlameGraphRows(
                listOf(intArrayOf(0), intArrayOf(1, 2)),
                doubleArrayOf(0.0, 0.25, 0.5),
                doubleArrayOf(1.0, 0.5, 0.75),
                startsAtBottom = true,
            )
        val table =
            CallNodeTable(
                ids = longArrayOf(7, 8, 9),
                parentIndexes = intArrayOf(-1, 0, 1),
                frameIds = longArrayOf(11, 12, 13),
                depths = intArrayOf(0, 1, 2),
                inclusiveWeights = longArrayOf(9, 6, 3),
                selfWeights = longArrayOf(3, 3, 3),
                sampleCounts = longArrayOf(3, 2, 1),
                threadCounts = intArrayOf(1, 1, 1),
                categories = listOf("UI", "IO", "Native"),
                framesById = mapOf(11L to frame(11), 12L to frame(12), 13L to frame(13)),
            )

        val requestedRow = rows.nodeIndexesAt(1)!!
        requestedRow[0] = 99

        assertEquals(2, rows.rowCount)
        assertContentEquals(intArrayOf(1, 2), rows.nodeIndexesAt(1))
        assertEquals(null, rows.nodeIndexesAt(2))
        assertEquals(0.25, rows.startAt(1))
        assertEquals(0.75, rows.endAt(2))
        assertEquals(null, rows.startAt(3))
        assertEquals(FlameCallNodeId(8), table.nodeIdAt(1))
        assertEquals(0, table.parentIndexAt(1))
        assertEquals(frame(12), table.frameAt(1))
        assertEquals(6L, table.inclusiveWeightAt(1))
        assertEquals(3L, table.selfWeightAt(1))
        assertEquals(2L, table.sampleCountAt(1))
        assertEquals(1, table.threadCountAt(1))
        assertEquals(1, table.indexOf(FlameCallNodeId(8)))
        assertEquals(null, table.nodeIdAt(3))
        assertEquals(null, table.indexOf(FlameCallNodeId(99)))
    }

    @Test
    fun `node index lookup remains stable without reading the defensive ids copy`() {
        val size = 100_000
        val ids = LongArray(size) { it.toLong() + 10L }
        val table =
            CallNodeTable(
                ids = ids,
                parentIndexes = IntArray(size) { -1 },
                frameIds = LongArray(size) { 11L },
                depths = IntArray(size),
                inclusiveWeights = LongArray(size) { 1L },
                selfWeights = LongArray(size) { 1L },
                sampleCounts = LongArray(size) { 1L },
                threadCounts = IntArray(size) { 1 },
                categories = List(size) { null },
                framesById = mapOf(11L to frame(11)),
            )
        val defensiveCopy = table.ids
        defensiveCopy.fill(Long.MIN_VALUE)

        assertEquals(size - 1, table.indexOf(FlameCallNodeId(ids.last())))
        assertEquals(null, table.indexOf(FlameCallNodeId(Long.MIN_VALUE)))
    }

    @Test
    fun `stack query table and snapshot snapshot caller collections`() {
        val frameIds = mutableListOf(11L)
        val frameCategories = mutableListOf<String?>("Graphics")
        val stack = weightedStack(frameIds, frameCategories)
        val frames = mutableMapOf(11L to frame(11))
        val stacks = mutableListOf(stack)
        val table = CallStackTable(frames, stacks)
        val transforms = mutableListOf<CallStackTransform>(CallStackTransform.FocusFunction(FlameFunctionId(11)))
        val query = CallStackAnalysisQuery(transforms = transforms)
        val invalidTransforms = transforms.toMutableList()
        val snapshot = FlameGraphSnapshot(query, callNodeTable(), emptyRows(), 1, null, invalidTransforms)

        frameIds[0] = 99
        frameCategories[0] = "mutated"
        frames.clear()
        stacks.clear()
        transforms.clear()
        invalidTransforms.clear()

        assertEquals(listOf(11L), stack.frameIdsRootToLeaf)
        assertEquals(listOf("Graphics"), stack.categoriesRootToLeaf)
        assertEquals(frame(11), table.frame(11))
        assertEquals(listOf(stack), table.stacks)
        assertEquals(listOf(CallStackTransform.FocusFunction(FlameFunctionId(11))), query.transforms)
        assertEquals(listOf(CallStackTransform.FocusFunction(FlameFunctionId(11))), snapshot.invalidTransforms)
    }

    @Test
    fun `weighted stacks fallback categories per frame and reject misaligned snapshots`() {
        val fallback = weightedStack(listOf(11L, 12L))

        assertEquals(listOf("UI", "UI"), fallback.categoriesRootToLeaf)
        assertFailsWith<IllegalArgumentException> {
            weightedStack(listOf(11L, 12L), listOf("Graphics"))
        }
    }

    private fun frame(frameId: Long) =
        CallStackFrame(
            frameId = frameId,
            functionId = FlameFunctionId(frameId),
            symbolName = "frame-$frameId",
            resource = "lib.so",
            virtualAddress = frameId,
            implementation = FrameImplementation.NATIVE,
        )

    private fun weightedStack(
        frameIds: List<Long>,
        categoriesRootToLeaf: List<String?>? = null,
    ) = WeightedCallStack(
        sampleId = 1,
        timestampNanos = 2,
        weight = 3,
        threadKey = "thread",
        category = "UI",
        subcategory = null,
        frameIdsRootToLeaf = frameIds,
        categoriesRootToLeaf = categoriesRootToLeaf,
    )

    private fun callNodeTable(
        idsByPath: Map<CallNodePath, FlameCallNodeId> = emptyMap(),
        selfSampleCounts: LongArray? = null,
        categoryMetrics: List<List<CallNodeCategoryMetrics>>? = null,
    ) = CallNodeTable(
        ids = longArrayOf(7),
        parentIndexes = intArrayOf(-1),
        frameIds = longArrayOf(11),
        depths = intArrayOf(0),
        inclusiveWeights = longArrayOf(1),
        selfWeights = longArrayOf(1),
        sampleCounts = longArrayOf(1),
        threadCounts = intArrayOf(1),
        categories = listOf("UI"),
        framesById = mapOf(11L to frame(11)),
        idsByPath = idsByPath,
        selfSampleCounts = selfSampleCounts,
        categoryMetrics = categoryMetrics,
    )

    private fun emptyRows() = FlameGraphRows(emptyList(), doubleArrayOf(), doubleArrayOf(), startsAtBottom = true)
}
