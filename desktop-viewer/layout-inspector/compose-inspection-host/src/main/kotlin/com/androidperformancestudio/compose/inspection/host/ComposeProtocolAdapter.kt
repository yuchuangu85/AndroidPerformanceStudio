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

class ComposeProtocolAdapter(private val detailLimits: ComposeDetailLimits = ComposeDetailLimits()) {
    internal fun newDetailBudget(): ComposeDetailBudget = ComposeDetailBudget(detailLimits)

    fun convert(
        frameId: String,
        generation: Int,
        tree: LayoutInspectorComposeProtocol.GetComposablesResponse,
        parameters: LayoutInspectorComposeProtocol.GetAllParametersResponse? = null,
        recompositionObservation: RecompositionObservation? = null,
        detailBudget: ComposeDetailBudget = newDetailBudget(),
        collectCoverage: Boolean = true,
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
        val convertedDetails = parameters?.toDetails(detailBudget, truncations, collectCoverage)
        val details = convertedDetails?.details.orEmpty()
        val coverage = if (collectCoverage) {
            buildList {
                addAll(convertedDetails?.coverage.orEmpty())
                roots.forEachNode { node ->
                    if (node.id !in details) {
                        DETAIL_FIELDS.forEach { field ->
                            add(
                                ComposeDetailCoverage(
                                    node.id,
                                    field,
                                    ComposeDetailCoverageState.NOT_COLLECTED,
                                    reason = if (detailBudget.groupsOmitted) {
                                        "details not returned or omitted by detail group limit"
                                    } else null,
                                ),
                            )
                        }
                    }
                }
            }
        } else emptyList()
        return ComposeInspectionFrame(
            frameId = frameId,
            generation = generation,
            mode = ComposeInspectionMode.FULL,
            capabilities = capabilities(parameters != null, recompositionObservation != null),
            roots = roots,
            details = details,
            coverage = coverage,
            completeness = if (budget.exhausted || detailBudget.exhausted) {
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
        val budget = newDetailBudget()
        check(budget.takeGroup()) { DETAIL_RESOURCE_LIMIT_MESSAGE }
        val detail = response.parameterGroup.toDetail(strings, budget, mutableListOf(), null)
        // This return type cannot distinguish a genuinely empty field from an entirely omitted one.
        check(!budget.exhausted) { DETAIL_RESOURCE_LIMIT_MESSAGE }
        return detail
    }

    fun convertParameterDetails(response: LayoutInspectorComposeProtocol.GetParameterDetailsResponse): ComposeValue {
        val strings = response.stringsList.associate { it.id to it.str }
        val state = FieldState(newDetailBudget())
        return response.parameter.convert(strings, state, 0)
            ?: error(DETAIL_RESOURCE_LIMIT_MESSAGE)
    }

    private fun LayoutInspectorComposeProtocol.GetAllParametersResponse.toDetails(
        budget: ComposeDetailBudget,
        truncations: MutableList<ComposeTruncation>,
        collectCoverage: Boolean,
    ): DetailCollection {
        val strings = stringsList.associate { it.id to it.str }
        val details = linkedMapOf<Long, ComposableDetail>()
        val coverage = if (collectCoverage) mutableListOf<ComposeDetailCoverage>() else null
        for (group in parameterGroupsList) {
            if (!budget.takeGroup()) {
                truncations.addOnce("details", "detail group limit exceeded")
                break
            }
            details[group.composableId] = group.toDetail(strings, budget, truncations, coverage)
        }
        return DetailCollection(details, coverage.orEmpty())
    }

    private fun LayoutInspectorComposeProtocol.ParameterGroup.toDetail(
        strings: Map<Int, String>,
        budget: ComposeDetailBudget,
        truncations: MutableList<ComposeTruncation>,
        coverage: MutableList<ComposeDetailCoverage>?,
    ): ComposableDetail {
        fun LayoutInspectorComposeProtocol.Parameter.isModifier() = strings[name].equals("modifier", ignoreCase = true)
        val modifierCount = parameterList.count { it.isModifier() }
        fun field(name: String, values: Sequence<LayoutInspectorComposeProtocol.Parameter>, count: Int): List<ComposeValue> {
            val state = FieldState(budget)
            val converted = buildList {
                for (parameter in values) {
                    add(parameter.convert(strings, state, 0) ?: break)
                }
            }
            coverage?.add(
                ComposeDetailCoverage(
                    nodeId = composableId,
                    field = name,
                    state = when {
                        state.reasons.isEmpty() -> ComposeDetailCoverageState.COLLECTED
                        converted.isEmpty() && count > 0 -> ComposeDetailCoverageState.NOT_COLLECTED
                        else -> ComposeDetailCoverageState.TRUNCATED
                    },
                    recursionDepth = state.depth,
                    loadedElements = converted.size,
                    totalElements = count,
                    reason = state.reasons.takeIf { it.isNotEmpty() }?.joinToString("; "),
                ),
            )
            state.reasons.forEach { truncations.addOnce(name, it) }
            return converted
        }
        return ComposableDetail(
            nodeId = composableId,
            anchorHash = parameterList.firstOrNull()?.reference?.anchorHash ?: 0,
            parameters = field("parameters", parameterList.asSequence().filterNot { it.isModifier() }, parameterCount - modifierCount),
            modifiers = field("modifiers", parameterList.asSequence().filter { it.isModifier() }, modifierCount),
            mergedSemantics = field("mergedSemantics", mergedSemanticsList.asSequence(), mergedSemanticsCount),
            unmergedSemantics = field("unmergedSemantics", unmergedSemanticsList.asSequence(), unmergedSemanticsCount),
        )
    }

    private fun LayoutInspectorComposeProtocol.Parameter.convert(
        strings: Map<Int, String>,
        state: FieldState,
        depth: Int,
    ): ComposeValue? {
        if (depth > state.budget.limits.maxDepth) {
            state.limited("detail depth limit exceeded")
            return null
        }
        if (!state.budget.takeValue()) {
            state.limited("detail value limit exceeded")
            return null
        }
        val initialViolations = state.violations
        val rawName = strings[name].orEmpty()
        val convertedName = state.text(rawName)
        val rawValue = renderValue(strings, minOf(state.budget.remainingTextChars, ComposeDetailBudget.MAX_STRING_CHARS))
        val value = rawValue?.let { state.text(it.text, it.originalSize) }
        // Do not retain arbitrarily many unnamed placeholders after the shared text budget is spent.
        if (convertedName.isEmpty() && value.orEmpty().isEmpty() && (rawName.isNotEmpty() || rawValue?.originalSize?.let { it > 0 } == true)) {
            return null
        }
        state.depth = maxOf(state.depth, depth)
        val elements = buildList {
            for (element in elementsList) {
                add(element.convert(strings, state, depth + 1) ?: break)
            }
        }
        return ComposeValue(
            name = convertedName,
            type = type.name,
            value = value,
            elements = elements,
            reference = reference.takeIf { hasReference() }?.let {
                ComposeParameterReference(
                    composableId = it.composableId,
                    parameterIndex = it.parameterIndex,
                    compositeIndex = it.compositeIndexList,
                    kind = it.kind.name,
                    anchorHash = it.anchorHash,
                )
            },
            originalSize = rawValue?.originalSize?.takeIf { it > value.orEmpty().length },
            truncated = hasReference() || state.violations > initialViolations,
        )
    }

    private fun LayoutInspectorComposeProtocol.Parameter.renderValue(strings: Map<Int, String>, maxChars: Int): RenderedValue? {
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
                val parts = listOf(resourceValue.namespace, resourceValue.type, resourceValue.name).map { strings[it].orEmpty() }
                return RenderedValue(parts.joinToString(":") { it.take(maxChars) }.take(maxChars), parts.sumOf { it.length } + 2)
            } else null
            LayoutInspectorComposeProtocol.Parameter.Type.LAMBDA,
            LayoutInspectorComposeProtocol.Parameter.Type.FUNCTION_REFERENCE,
            -> if (hasLambdaValue()) {
                val file = strings[lambdaValue.fileName].orEmpty()
                val suffix = ":${lambdaValue.startLineNumber}"
                return RenderedValue((file.take(maxChars) + suffix).take(maxChars), file.length + suffix.length)
            } else null
            else -> null
        }
        return rawValue?.let { RenderedValue(it, it.length) }
    }

    private fun MutableList<ComposeTruncation>.addOnce(field: String, reason: String) {
        if (none { it.field == field && it.reason == reason }) add(ComposeTruncation(field = field, reason = reason))
    }

    private class FieldState(val budget: ComposeDetailBudget) {
        val reasons = linkedSetOf<String>()
        var depth: Int = 0
        var violations: Int = 0
            private set

        fun limited(reason: String) {
            budget.reachedLimit()
            reasons += reason
            violations++
        }

        fun text(raw: String, originalSize: Int = raw.length): String {
            val result = budget.takeText(raw)
            if (originalSize > ComposeDetailBudget.MAX_STRING_CHARS) {
                limited("per-value string limit exceeded")
            }
            if (result.length < minOf(originalSize, ComposeDetailBudget.MAX_STRING_CHARS)) {
                limited("detail text character limit exceeded")
            }
            return result
        }
    }

    private data class DetailCollection(val details: Map<Long, ComposableDetail>, val coverage: List<ComposeDetailCoverage>)
    private data class RenderedValue(val text: String, val originalSize: Int)

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

    private fun List<ComposableRoot>.forEachNode(action: (ComposableNode) -> Unit) {
        val pending = ArrayDeque<ComposableNode>()
        for (root in this) {
            root.nodes.asReversed().forEach { pending.addLast(it) }
            while (pending.isNotEmpty()) {
                val node = pending.removeLast()
                action(node)
                node.children.asReversed().forEach { pending.addLast(it) }
            }
        }
    }

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
        const val SYSTEM_CREATED_FLAG = 0x1
        const val DETAIL_RESOURCE_LIMIT_MESSAGE = "Compose detail resource limit exceeded; requested detail was not published"
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
