package com.androidperformancestudio.compose.inspection.host

import com.androidperformancestudio.compose.inspection.CapabilityAvailability
import com.androidperformancestudio.compose.inspection.ComposeCapability
import com.androidperformancestudio.compose.inspection.ComposeDetailCoverageState
import com.androidperformancestudio.compose.inspection.ComposeFrameCompleteness
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComposeProtocolAdapterTest {
    @Test
    fun `adapter preserves raw system nodes source counts and details`() {
        val strings = listOf(
            string(1, "SystemWrapper"),
            string(2, "Content"),
            string(3, "Screen.kt"),
        )
        val content = node(2, 22, 2).toBuilder()
            .setFilename(3).setPackageHash(9).setLineNumber(42).setOffset(7)
            .setRecomposeCount(5).setRecomposeSkips(3).build()
        val system = node(1, 11, 1).toBuilder().setFlags(1).addChildren(content).build()
        val tree = LayoutInspectorComposeProtocol.GetComposablesResponse.newBuilder()
            .addAllStrings(strings)
            .addRoots(LayoutInspectorComposeProtocol.ComposableRoot.newBuilder().setViewId(100).addNodes(system))
            .build()
        val parameterStrings = listOf(string(10, "title"), string(11, "Hello"), string(12, "modifier"))
        val group = LayoutInspectorComposeProtocol.ParameterGroup.newBuilder()
            .setComposableId(2)
            .addParameter(stringParameter(10, 11))
            .addParameter(stringParameter(12, 11))
            .addMergedSemantics(stringParameter(10, 11))
            .build()
        val parameters = LayoutInspectorComposeProtocol.GetAllParametersResponse.newBuilder()
            .addAllStrings(parameterStrings).addParameterGroups(group).build()

        val frame = ComposeProtocolAdapter().convert("frame", 4, tree, parameters)

        val systemNode = frame.roots.single().nodes.single()
        val appNode = systemNode.children.single()
        assertTrue(systemNode.systemCreated)
        assertEquals("Screen.kt", appNode.source?.fileName)
        assertEquals(5, appNode.recomposeCount)
        assertEquals("Hello", frame.details.getValue(2).parameters.single().value)
        assertEquals("modifier", frame.details.getValue(2).modifiers.single().name)
        assertEquals(
            CapabilityAvailability.AVAILABLE,
            frame.capabilities.single { it.capability == ComposeCapability.PARAMETERS }.availability,
        )
    }

    @Test
    fun `details are explicitly not requested when absent`() {
        val tree = LayoutInspectorComposeProtocol.GetComposablesResponse.newBuilder()
            .addStrings(string(1, "Content"))
            .addRoots(LayoutInspectorComposeProtocol.ComposableRoot.newBuilder().addNodes(node(1, 1, 1)))
            .build()
        val frame = ComposeProtocolAdapter().convert("frame", 1, tree)
        assertEquals(4, frame.coverage.count { it.nodeId == 1L })
        assertTrue(frame.details.isEmpty())
    }

    @Test
    fun `detail group budget preserves all nodes and marks omitted groups not collected`() {
        val adapter = ComposeProtocolAdapter(ComposeDetailLimits(maxGroups = 1))
        val parameters = parameters(
            group(1, stringParameter(10, 11)),
            group(2, stringParameter(10, 11)),
        )

        val frame = adapter.convert("bounded", 1, tree(1, 2, 3), parameters)

        assertEquals(3, frame.roots.single().nodes.size)
        assertEquals(setOf(1L), frame.details.keys)
        assertEquals(ComposeFrameCompleteness.INCOMPLETE_RESOURCE_LIMIT, frame.completeness)
        assertEquals(ComposeDetailCoverageState.COLLECTED, frame.coverage.single { it.nodeId == 1L && it.field == "parameters" }.state)
        assertTrue(frame.coverage.filter { it.nodeId == 2L }.all { it.state == ComposeDetailCoverageState.NOT_COLLECTED && it.reason != null })
        assertTrue(frame.coverage.filter { it.nodeId == 3L }.all { it.state == ComposeDetailCoverageState.NOT_COLLECTED })
        assertTrue(frame.truncations.any { it.field == "details" && it.reason.contains("group") })
    }

    @Test
    fun `value budget is shared across parameters modifiers and semantics with field precise coverage`() {
        val nested = stringParameter(10, 11).toBuilder().addElements(stringParameter(10, 11)).build()
        val raw = group(1, nested).toBuilder()
            .addParameter(stringParameter(12, 11))
            .addMergedSemantics(stringParameter(10, 11))
            .addUnmergedSemantics(stringParameter(10, 11))
            .build()

        val frame = ComposeProtocolAdapter(ComposeDetailLimits(maxValues = 3)).convert("values", 1, tree(1), parameters(raw))
        val detail = frame.details.getValue(1)

        assertEquals(1, detail.parameters.single().elements.size)
        assertEquals(1, detail.modifiers.size)
        assertTrue(detail.mergedSemantics.isEmpty())
        assertTrue(detail.unmergedSemantics.isEmpty())
        assertEquals(ComposeDetailCoverageState.COLLECTED, frame.coverage.single { it.field == "parameters" }.state)
        assertEquals(ComposeDetailCoverageState.COLLECTED, frame.coverage.single { it.field == "modifiers" }.state)
        assertEquals(ComposeDetailCoverageState.NOT_COLLECTED, frame.coverage.single { it.field == "mergedSemantics" }.state)
        assertEquals(ComposeFrameCompleteness.INCOMPLETE_RESOURCE_LIMIT, frame.completeness)
        assertTrue(frame.truncations.any { it.reason.contains("value") })
    }

    @Test
    fun `detail depth budget preserves parent values references and reports partial field`() {
        val reference = LayoutInspectorComposeProtocol.ParameterReference.newBuilder()
            .setComposableId(1).setAnchorHash(22).setParameterIndex(3)
        val nested = stringParameter(10, 11).toBuilder()
            .setReference(reference)
            .addElements(stringParameter(10, 11).toBuilder().addElements(stringParameter(10, 11)))
            .build()

        val frame = ComposeProtocolAdapter(ComposeDetailLimits(maxDepth = 1)).convert("depth", 1, tree(1), parameters(group(1, nested)))
        val value = frame.details.getValue(1).parameters.single()

        assertEquals(22, value.reference?.anchorHash)
        assertTrue(value.elements.single().elements.isEmpty())
        assertTrue(value.elements.single().truncated)
        assertEquals(ComposeDetailCoverageState.TRUNCATED, frame.coverage.single { it.field == "parameters" }.state)
        assertEquals(ComposeFrameCompleteness.INCOMPLETE_RESOURCE_LIMIT, frame.completeness)
        assertTrue(frame.truncations.any { it.reason.contains("depth") })
    }

    @Test
    fun `text budget includes names and values and keeps omission metadata`() {
        val raw = parameters(group(1, stringParameter(10, 11), stringParameter(10, 11)))
        val frame = ComposeProtocolAdapter(ComposeDetailLimits(maxTextChars = 7)).convert("text", 1, tree(1), raw)
        val values = frame.details.getValue(1).parameters

        assertTrue(values.sumOf { it.name.length + it.value.orEmpty().length } <= 7)
        assertEquals("ti", values.first().value)
        assertEquals(5, values.first().originalSize)
        assertTrue(values.first().truncated)
        assertEquals(ComposeDetailCoverageState.TRUNCATED, frame.coverage.single { it.field == "parameters" }.state)
        assertEquals(ComposeFrameCompleteness.INCOMPLETE_RESOURCE_LIMIT, frame.completeness)
    }

    @Test
    fun `detail budgets reset between independent adapter conversions`() {
        val adapter = ComposeProtocolAdapter(ComposeDetailLimits(maxGroups = 1, maxValues = 1))
        repeat(2) {
            val frame = adapter.convert("frame-$it", it, tree(1), parameters(group(1, stringParameter(10, 11))))
            assertEquals("title", frame.details.getValue(1).parameters.single().name)
            assertEquals(ComposeFrameCompleteness.COMPLETE, frame.completeness)
        }
    }

    @Test
    fun `detail budget can be shared across window root conversions`() {
        val adapter = ComposeProtocolAdapter(ComposeDetailLimits(maxGroups = 1, maxValues = 1))
        val budget = adapter.newDetailBudget()

        val first = adapter.convert("multi-root", 1, tree(1), parameters(group(1, stringParameter(10, 11))), detailBudget = budget)
        val second = adapter.convert("multi-root", 1, tree(2), parameters(group(2, stringParameter(10, 11))), detailBudget = budget)

        assertEquals("title", first.details.getValue(1).parameters.single().name)
        assertTrue(second.details.isEmpty())
        assertEquals(ComposeFrameCompleteness.INCOMPLETE_RESOURCE_LIMIT, second.completeness)
        assertTrue(second.coverage.all { it.state == ComposeDetailCoverageState.NOT_COLLECTED })
    }

    @Test
    fun `hierarchy only probes do not allocate missing detail coverage`() {
        val frame = ComposeProtocolAdapter().convert("probe", 1, tree(1, 2, 3), collectCoverage = false)

        assertEquals(3, frame.roots.single().nodes.size)
        assertTrue(frame.details.isEmpty())
        assertTrue(frame.coverage.isEmpty())
        assertEquals(ComposeFrameCompleteness.COMPLETE, frame.completeness)
    }

    @Test
    fun `upstream pageable references retain their address without reporting a local resource limit`() {
        val reference = LayoutInspectorComposeProtocol.ParameterReference.newBuilder()
            .setComposableId(1).setAnchorHash(22).setParameterIndex(3)
            .addCompositeIndex(4).addCompositeIndex(5)
            .setKind(LayoutInspectorComposeProtocol.ParameterReference.Kind.MERGED_SEMANTICS)
        val raw = group(1).toBuilder().addMergedSemantics(stringParameter(10, 11).toBuilder().setReference(reference)).build()

        val frame = ComposeProtocolAdapter().convert("reference", 1, tree(1), parameters(raw))
        val value = frame.details.getValue(1).mergedSemantics.single()

        assertEquals(listOf(4, 5), value.reference?.compositeIndex)
        assertEquals("MERGED_SEMANTICS", value.reference?.kind)
        assertTrue(value.truncated)
        assertEquals(ComposeDetailCoverageState.COLLECTED, frame.coverage.single { it.field == "mergedSemantics" }.state)
        assertEquals(ComposeFrameCompleteness.COMPLETE, frame.completeness)
        assertTrue(frame.truncations.isEmpty())
    }

    @Test
    fun `per string truncation records incomplete coverage and original value size`() {
        val longValue = "x".repeat(ComposeDetailBudget.MAX_STRING_CHARS + 1)
        val raw = parameters(group(1, stringParameter(10, 20))).toBuilder().addStrings(string(20, longValue)).build()

        val frame = ComposeProtocolAdapter().convert("long-string", 1, tree(1), raw)
        val value = frame.details.getValue(1).parameters.single()

        assertEquals(ComposeDetailBudget.MAX_STRING_CHARS, value.value?.length)
        assertEquals(longValue.length, value.originalSize)
        assertTrue(value.truncated)
        assertEquals(ComposeDetailCoverageState.TRUNCATED, frame.coverage.single { it.field == "parameters" }.state)
        assertEquals(ComposeFrameCompleteness.INCOMPLETE_RESOURCE_LIMIT, frame.completeness)
    }

    @Test
    fun `on demand detail rejects local limits instead of presenting omitted fields as empty`() {
        val nested = stringParameter(10, 11).toBuilder().addElements(stringParameter(10, 11)).build()
        val raw = group(1, nested).toBuilder().addMergedSemantics(stringParameter(10, 11)).build()
        val response = LayoutInspectorComposeProtocol.GetParametersResponse.newBuilder()
            .addAllStrings(parameters().stringsList).setParameterGroup(raw).build()

        listOf(
            ComposeDetailLimits(maxGroups = 0),
            ComposeDetailLimits(maxValues = 1),
            ComposeDetailLimits(maxDepth = 0),
            ComposeDetailLimits(maxTextChars = 7),
        ).forEach { limits ->
            val failure = assertThrows(IllegalStateException::class.java) {
                ComposeProtocolAdapter(limits).convertDetail(response)
            }
            assertEquals("Compose detail resource limit exceeded; requested detail was not published", failure.message)
        }
    }

    @Test
    fun `on demand detail accepts upstream references without mistaking them for local limits`() {
        val reference = LayoutInspectorComposeProtocol.ParameterReference.newBuilder().setComposableId(1).setAnchorHash(22)
        val parameter = stringParameter(10, 11).toBuilder().setReference(reference).build()
        val response = LayoutInspectorComposeProtocol.GetParametersResponse.newBuilder()
            .addAllStrings(parameters().stringsList).setParameterGroup(group(1, parameter)).build()

        val detail = ComposeProtocolAdapter().convertDetail(response)

        assertEquals("title", detail.parameters.single().value)
        assertEquals(22, detail.parameters.single().reference?.anchorHash)
        assertTrue(detail.parameters.single().truncated)
    }

    @Test
    fun `on demand parameter expansion rejects an omitted root rather than replacing existing evidence`() {
        val response = LayoutInspectorComposeProtocol.GetParameterDetailsResponse.newBuilder()
            .addAllStrings(parameters().stringsList).setParameter(stringParameter(10, 11)).build()

        listOf(ComposeDetailLimits(maxValues = 0), ComposeDetailLimits(maxTextChars = 0)).forEach { limits ->
            val failure = assertThrows(IllegalStateException::class.java) {
                ComposeProtocolAdapter(limits).convertParameterDetails(response)
            }
            assertEquals("Compose detail resource limit exceeded; requested detail was not published", failure.message)
        }
    }

    @Test
    fun `on demand parameter expansion preserves a retained root with explicit subtree truncation`() {
        val reference = LayoutInspectorComposeProtocol.ParameterReference.newBuilder().setComposableId(1).setAnchorHash(22)
        val parameter = stringParameter(10, 11).toBuilder().setReference(reference)
            .addElements(stringParameter(10, 11)).build()
        val response = LayoutInspectorComposeProtocol.GetParameterDetailsResponse.newBuilder()
            .addAllStrings(parameters().stringsList).setParameter(parameter).build()

        val value = ComposeProtocolAdapter(ComposeDetailLimits(maxValues = 1)).convertParameterDetails(response)

        assertEquals("title", value.value)
        assertEquals(22, value.reference?.anchorHash)
        assertTrue(value.elements.isEmpty())
        assertTrue(value.truncated)
    }

    private fun tree(vararg ids: Long) = LayoutInspectorComposeProtocol.GetComposablesResponse.newBuilder()
        .addStrings(string(1, "Content"))
        .addRoots(LayoutInspectorComposeProtocol.ComposableRoot.newBuilder().addAllNodes(ids.map { node(it, 1, 1) }))
        .build()

    private fun parameters(vararg groups: LayoutInspectorComposeProtocol.ParameterGroup) =
        LayoutInspectorComposeProtocol.GetAllParametersResponse.newBuilder()
            .addAllStrings(listOf(string(10, "title"), string(11, "title"), string(12, "modifier")))
            .addAllParameterGroups(groups.toList()).build()

    private fun group(id: Long, vararg values: LayoutInspectorComposeProtocol.Parameter) =
        LayoutInspectorComposeProtocol.ParameterGroup.newBuilder().setComposableId(id).addAllParameter(values.toList()).build()

    private fun node(id: Long, anchor: Int, name: Int) =
        LayoutInspectorComposeProtocol.ComposableNode.newBuilder()
            .setId(id).setAnchorHash(anchor).setName(name)
            .setBounds(
                LayoutInspectorComposeProtocol.Bounds.newBuilder().setLayout(
                    LayoutInspectorComposeProtocol.Rect.newBuilder().setX(1).setY(2).setW(3).setH(4),
                ),
            ).build()

    private fun string(id: Int, value: String) =
        LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(id).setStr(value).build()

    private fun stringParameter(name: Int, value: Int) =
        LayoutInspectorComposeProtocol.Parameter.newBuilder()
            .setName(name).setType(LayoutInspectorComposeProtocol.Parameter.Type.STRING).setInt32Value(value).build()
}
