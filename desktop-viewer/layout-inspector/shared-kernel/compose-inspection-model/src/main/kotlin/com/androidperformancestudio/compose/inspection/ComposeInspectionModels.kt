package com.androidperformancestudio.compose.inspection

import com.androidperformancestudio.protocol.Bounds
import kotlinx.serialization.Serializable

const val COMPOSE_INSPECTION_SCHEMA_VERSION = 1

@Serializable
enum class ComposeInspectionMode { FULL, SEMANTICS_ONLY }

@Serializable
enum class ComposeCapability {
    FULL_TREE,
    PARAMETERS,
    MODIFIERS,
    MERGED_SEMANTICS,
    UNMERGED_SEMANTICS,
    SOURCE_LOCATION,
    RECOMPOSITION_COUNTS,
    SKIP_COUNTS,
    STATE_READS,
}

@Serializable
enum class CapabilityAvailability { AVAILABLE, UNAVAILABLE, NOT_REQUESTED }

@Serializable
data class ComposeCapabilityState(
    val capability: ComposeCapability,
    val availability: CapabilityAvailability,
    val reason: String? = null,
)

@Serializable
enum class ComposeFrameCompleteness { COMPLETE, INCOMPLETE_RESOURCE_LIMIT, INCOMPLETE_CAPTURE_ERROR }

@Serializable
enum class ComposeDetailCoverageState { COLLECTED, NOT_COLLECTED, TRUNCATED, FAILED }

@Serializable
enum class ComposeArchivePrivacy { SAFE_REDACTED, FULL_FIDELITY }

@Serializable
data class ComposeInspectorArtifact(
    val group: String = "androidx.compose.ui",
    val artifact: String,
    val version: String,
    val sha256: String,
    val source: String,
    val certified: Boolean,
)

@Serializable
data class ComposeInspectionDocument(
    val schemaVersion: Int = COMPOSE_INSPECTION_SCHEMA_VERSION,
    val packageName: String,
    val capturedAtEpochMillis: Long,
    val frame: ComposeInspectionFrame,
    val artifact: ComposeInspectorArtifact? = null,
    val privacy: ComposeArchivePrivacy = ComposeArchivePrivacy.SAFE_REDACTED,
)

@Serializable
data class ComposeInspectionFrame(
    val frameId: String,
    val generation: Int,
    val mode: ComposeInspectionMode,
    val capabilities: List<ComposeCapabilityState>,
    val roots: List<ComposableRoot>,
    val details: Map<Long, ComposableDetail> = emptyMap(),
    val coverage: List<ComposeDetailCoverage> = emptyList(),
    val completeness: ComposeFrameCompleteness = ComposeFrameCompleteness.COMPLETE,
    val truncations: List<ComposeTruncation> = emptyList(),
    val recompositionObservation: RecompositionObservation? = null,
)

@Serializable
data class ComposableRoot(
    val viewId: Long,
    val nodes: List<ComposableNode>,
    val viewsToSkip: List<Long> = emptyList(),
)

@Serializable
data class ComposableNode(
    val id: Long,
    val anchorHash: Int,
    val name: String,
    val bounds: Bounds,
    val hostedViewId: Long? = null,
    val source: ComposeSourceLocation? = null,
    val systemCreated: Boolean = false,
    val flags: List<String> = emptyList(),
    val recomposeCount: Int? = null,
    val skipCount: Int? = null,
    val children: List<ComposableNode> = emptyList(),
)

@Serializable
data class ComposeSourceLocation(
    val packageHash: Int,
    val fileName: String,
    val lineNumber: Int,
    val offset: Int,
)

@Serializable
data class ComposableDetail(
    val nodeId: Long,
    val anchorHash: Int,
    val parameters: List<ComposeValue> = emptyList(),
    val modifiers: List<ComposeValue> = emptyList(),
    val mergedSemantics: List<ComposeValue> = emptyList(),
    val unmergedSemantics: List<ComposeValue> = emptyList(),
)

@Serializable
data class ComposeValue(
    val name: String,
    val type: String,
    val value: String? = null,
    val elements: List<ComposeValue> = emptyList(),
    val reference: ComposeParameterReference? = null,
    val originalSize: Int? = null,
    val truncated: Boolean = false,
)

@Serializable
data class ComposeParameterReference(
    val composableId: Long,
    val parameterIndex: Int,
    val compositeIndex: List<Int> = emptyList(),
    val kind: String,
    val anchorHash: Int,
)

@Serializable
data class ComposeDetailCoverage(
    val nodeId: Long,
    val field: String,
    val state: ComposeDetailCoverageState,
    val recursionDepth: Int = 0,
    val loadedElements: Int = 0,
    val totalElements: Int? = null,
    val reason: String? = null,
)

@Serializable
data class ComposeTruncation(
    val nodeId: Long? = null,
    val field: String,
    val reason: String,
    val originalSize: Long? = null,
    val retainedSize: Long? = null,
)

@Serializable
data class RecompositionObservation(
    val startedAtEpochMillis: Long,
    val stoppedAtEpochMillis: Long? = null,
    val active: Boolean,
    val continuous: Boolean,
)
