package com.androidperformancestudio.compose.inspection.host

import com.androidperformancestudio.compose.inspection.CapabilityAvailability
import com.androidperformancestudio.compose.inspection.ComposableDetail
import com.androidperformancestudio.compose.inspection.ComposableNode
import com.androidperformancestudio.compose.inspection.ComposableRoot
import com.androidperformancestudio.compose.inspection.ComposeCapability
import com.androidperformancestudio.compose.inspection.ComposeCapabilityState
import com.androidperformancestudio.compose.inspection.ComposeDetailCoverage
import com.androidperformancestudio.compose.inspection.ComposeDetailCoverageState
import com.androidperformancestudio.compose.inspection.ComposeFrameCompleteness
import com.androidperformancestudio.compose.inspection.ComposeInspectionFrame
import com.androidperformancestudio.compose.inspection.ComposeInspectionMode
import com.androidperformancestudio.compose.inspection.ComposeParameterReference
import com.androidperformancestudio.compose.inspection.ComposeSourceLocation
import com.androidperformancestudio.compose.inspection.ComposeTruncation
import com.androidperformancestudio.compose.inspection.ComposeValue
import com.androidperformancestudio.compose.inspection.RecompositionObservation
import com.androidperformancestudio.protocol.Bounds
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol

class ComposeProtocolAdapter {
    fun convert(
        frameId: String,
        generation: Int,
        tree: LayoutInspectorComposeProtocol.GetComposablesResponse,
        parameters: LayoutInspectorComposeProtocol.GetAllParametersResponse? = null,
        recompositionObservation: RecompositionObservation? = null,
    ): ComposeInspectionFrame {
        val strings = tree.stringsList.associate { it.id to it.str }
        val truncations = mutableListOf<ComposeTruncation>()
        val budget = NodeBudget(MAX_NODES)
        val roots = tree.rootsList.map { root ->
            ComposableRoot(
                viewId = root.viewId,
                nodes = root.nodesList.mapNotNull { it.convert(strings, budget, truncations) },
                viewsToSkip = root.viewsToSkipList,
            )
        }
        val details = parameters?.toDetails().orEmpty()
        val coverage = if (parameters == null) {
            roots.flatMap { root -> root.nodes.flatMap { it.subtreeNodes() } }.flatMap { node ->
                DETAIL_FIELDS.map { field ->
                    ComposeDetailCoverage(node.id, field, ComposeDetailCoverageState.NOT_COLLECTED)
                }
            }
        } else {
            details.keys.flatMap { nodeId ->
                DETAIL_FIELDS.map { field ->
                    ComposeDetailCoverage(
                        nodeId = nodeId,
                        field = field,
                        state = ComposeDetailCoverageState.COLLECTED,
                        recursionDepth = DEFAULT_RECURSION_DEPTH,
                    )
                }
            }
        }
        return ComposeInspectionFrame(
            frameId = frameId,
            generation = generation,
            mode = ComposeInspectionMode.FULL,
            capabilities = capabilities(parameters != null, recompositionObservation != null),
            roots = roots,
            details = details,
            coverage = coverage,
            completeness = if (budget.exhausted) {
                ComposeFrameCompleteness.INCOMPLETE_RESOURCE_LIMIT
            } else {
                ComposeFrameCompleteness.COMPLETE
            },
            truncations = truncations,
            recompositionObservation = recompositionObservation,
        )
    }

    private fun LayoutInspectorComposeProtocol.ComposableNode.convert(
        strings: Map<Int, String>,
        budget: NodeBudget,
        truncations: MutableList<ComposeTruncation>,
    ): ComposableNode? {
        if (!budget.take()) {
            if (truncations.none { it.field == "tree" }) {
                truncations += ComposeTruncation(
                    field = "tree",
                    reason = "node limit exceeded",
                    retainedSize = MAX_NODES.toLong(),
                )
            }
            return null
        }
        val layout = if (hasBounds() && bounds.hasLayout()) bounds.layout else null
        val fileName = strings[filename]
        return ComposableNode(
            id = id,
            anchorHash = anchorHash,
            name = strings[name] ?: "<unknown composable>",
            bounds = layout?.let { Bounds(it.x, it.y, it.x.saturatedPlus(it.w), it.y.saturatedPlus(it.h)) }
                ?: Bounds(0, 0, 0, 0),
            hostedViewId = viewId.takeIf { it != 0L },
            source = fileName?.let {
                ComposeSourceLocation(packageHash, it, lineNumber, offset)
            },
            systemCreated = flags and SYSTEM_CREATED_FLAG != 0,
            flags = flagNames(flags),
            recomposeCount = recomposeCount,
            skipCount = recomposeSkips,
            children = childrenList.mapNotNull { it.convert(strings, budget, truncations) },
        )
    }

    fun convertDetail(response: LayoutInspectorComposeProtocol.GetParametersResponse): ComposableDetail {
        val strings = response.stringsList.associate { it.id to it.str }
        return response.parameterGroup.toDetail(strings)
    }

    fun convertParameterDetails(response: LayoutInspectorComposeProtocol.GetParameterDetailsResponse): ComposeValue {
        val strings = response.stringsList.associate { it.id to it.str }
        return response.parameter.convert(strings)
    }

    private fun LayoutInspectorComposeProtocol.GetAllParametersResponse.toDetails(): Map<Long, ComposableDetail> {
        val strings = stringsList.associate { it.id to it.str }
        return parameterGroupsList.associate { group -> group.composableId to group.toDetail(strings) }
    }

    private fun LayoutInspectorComposeProtocol.ParameterGroup.toDetail(strings: Map<Int, String>): ComposableDetail {
        val converted = parameterList.map { it.convert(strings) }
        val (modifiers, regularParameters) = converted.partition { it.name.equals("modifier", ignoreCase = true) }
        return ComposableDetail(
            nodeId = composableId,
            anchorHash = parameterList.firstOrNull()?.reference?.anchorHash ?: 0,
            parameters = regularParameters,
            modifiers = modifiers,
            mergedSemantics = mergedSemanticsList.map { it.convert(strings) },
            unmergedSemantics = unmergedSemanticsList.map { it.convert(strings) },
        )
    }

    private fun LayoutInspectorComposeProtocol.Parameter.convert(strings: Map<Int, String>): ComposeValue {
        val rawValue = when (type) {
            LayoutInspectorComposeProtocol.Parameter.Type.STRING,
            LayoutInspectorComposeProtocol.Parameter.Type.ITERABLE,
            -> strings[int32Value]
            LayoutInspectorComposeProtocol.Parameter.Type.BOOLEAN -> (int32Value == 1).toString()
            LayoutInspectorComposeProtocol.Parameter.Type.DOUBLE -> doubleValue.toString()
            LayoutInspectorComposeProtocol.Parameter.Type.FLOAT,
            LayoutInspectorComposeProtocol.Parameter.Type.DIMENSION_DP,
            LayoutInspectorComposeProtocol.Parameter.Type.DIMENSION_SP,
            LayoutInspectorComposeProtocol.Parameter.Type.DIMENSION_EM,
            -> floatValue.toString()
            LayoutInspectorComposeProtocol.Parameter.Type.INT32,
            LayoutInspectorComposeProtocol.Parameter.Type.COLOR,
            -> int32Value.toString()
            LayoutInspectorComposeProtocol.Parameter.Type.INT64 -> int64Value.toString()
            LayoutInspectorComposeProtocol.Parameter.Type.RESOURCE -> if (hasResourceValue()) {
                listOf(resourceValue.namespace, resourceValue.type, resourceValue.name)
                    .map { strings[it].orEmpty() }.joinToString(":")
            } else null
            LayoutInspectorComposeProtocol.Parameter.Type.LAMBDA,
            LayoutInspectorComposeProtocol.Parameter.Type.FUNCTION_REFERENCE,
            -> if (hasLambdaValue()) {
                "${strings[lambdaValue.fileName].orEmpty()}:${lambdaValue.startLineNumber}"
            } else null
            else -> null
        }
        val value = rawValue?.take(MAX_STRING_CHARS)
        return ComposeValue(
            name = strings[name] ?: "",
            type = type.name,
            value = value,
            elements = elementsList.map { it.convert(strings) },
            reference = reference.takeIf { hasReference() }?.let {
                ComposeParameterReference(
                    composableId = it.composableId,
                    parameterIndex = it.parameterIndex,
                    compositeIndex = it.compositeIndexList,
                    kind = it.kind.name,
                    anchorHash = it.anchorHash,
                )
            },
            originalSize = rawValue?.length?.takeIf { it > MAX_STRING_CHARS },
            truncated = hasReference() || rawValue?.length?.let { it > MAX_STRING_CHARS } == true,
        )
    }

    private fun capabilities(
        detailsCollected: Boolean,
        observingRecompositions: Boolean,
    ): List<ComposeCapabilityState> = listOf(
        available(ComposeCapability.FULL_TREE),
        detailCapability(ComposeCapability.PARAMETERS, detailsCollected),
        detailCapability(ComposeCapability.MODIFIERS, detailsCollected),
        detailCapability(ComposeCapability.MERGED_SEMANTICS, detailsCollected),
        detailCapability(ComposeCapability.UNMERGED_SEMANTICS, detailsCollected),
        available(ComposeCapability.SOURCE_LOCATION),
        observationCapability(ComposeCapability.RECOMPOSITION_COUNTS, observingRecompositions),
        observationCapability(ComposeCapability.SKIP_COUNTS, observingRecompositions),
        ComposeCapabilityState(
            ComposeCapability.STATE_READS,
            CapabilityAvailability.UNAVAILABLE,
            "experimental capability deferred",
        ),
    )

    private fun available(capability: ComposeCapability) =
        ComposeCapabilityState(capability, CapabilityAvailability.AVAILABLE)

    private fun detailCapability(capability: ComposeCapability, collected: Boolean) =
        ComposeCapabilityState(
            capability,
            if (collected) CapabilityAvailability.AVAILABLE else CapabilityAvailability.NOT_REQUESTED,
        )

    private fun observationCapability(capability: ComposeCapability, observing: Boolean) =
        ComposeCapabilityState(
            capability,
            if (observing) CapabilityAvailability.AVAILABLE else CapabilityAvailability.NOT_REQUESTED,
        )

    private fun ComposableNode.subtreeNodes(): List<ComposableNode> =
        listOf(this) + children.flatMap { it.subtreeNodes() }

    private fun Int.saturatedPlus(other: Int): Int = (toLong() + other).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    private fun flagNames(flags: Int): List<String> = buildList {
        FLAG_NAMES.forEach { (bit, name) -> if (flags and bit != 0) add(name) }
    }

    private class NodeBudget(private var remaining: Int) {
        var exhausted: Boolean = false
            private set

        fun take(): Boolean {
            if (remaining <= 0) {
                exhausted = true
                return false
            }
            remaining--
            return true
        }
    }

    private companion object {
        const val MAX_NODES = 100_000
        const val MAX_STRING_CHARS = 64 * 1024
        const val DEFAULT_RECURSION_DEPTH = 2
        const val SYSTEM_CREATED_FLAG = 0x1
        val DETAIL_FIELDS = listOf("parameters", "modifiers", "mergedSemantics", "unmergedSemantics")
        val FLAG_NAMES = listOf(
            0x1 to "SYSTEM_CREATED",
            0x2 to "HAS_MERGED_SEMANTICS",
            0x4 to "HAS_UNMERGED_SEMANTICS",
            0x8 to "INLINED",
            0x10 to "NESTED_SINGLE_CHILDREN",
            0x20 to "HAS_DRAW_MODIFIER",
            0x40 to "HAS_CHILD_DRAW_MODIFIER",
        )
    }
}
