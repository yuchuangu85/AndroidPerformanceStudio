@file:Suppress("FunctionName", "ktlint:standard:function-naming", "LongMethod")

package com.androidperformancestudio.gpu.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.gpu.model.AgiCapability
import com.androidperformancestudio.gpu.model.AgiLaunchMode
import com.androidperformancestudio.gpu.model.ArtifactLocationStatus
import com.androidperformancestudio.gpu.model.ArtifactOpenRoute
import com.androidperformancestudio.gpu.model.GpuArtifact
import com.androidperformancestudio.gpu.model.GpuArtifactKind
import com.androidperformancestudio.gpu.model.GpuDeviceContext
import com.androidperformancestudio.gpu.presentation.generated.resources.*
import com.androidperformancestudio.gpu.presentation.generated.resources.Res
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioCard
import com.androidperformancestudio.ui.studio.StudioTokens
import java.nio.file.Path

public enum class ArtifactPrimaryAction { OPEN_AGI, LAUNCH_AGI, OPEN_PERFETTO, OPEN_DESKTOP, NONE }

public data class GpuArtifactAvailability(
    val locationStatus: ArtifactLocationStatus,
    val resolvedPath: Path?,
    val primaryAction: ArtifactPrimaryAction,
) {
    public val canOpen: Boolean
        get() = locationStatus == ArtifactLocationStatus.AVAILABLE && resolvedPath != null && primaryAction != ArtifactPrimaryAction.NONE
}

public data class GpuIntegrationState(
    val capability: AgiCapability? = null,
    val artifacts: List<GpuArtifact> = emptyList(),
    val availabilityByArtifactId: Map<String, GpuArtifactAvailability> = emptyMap(),
    val devices: List<GpuDeviceContext> = emptyList(),
    val selectedDeviceSerial: String? = null,
    val selectedArtifactId: String? = null,
    val isBusy: Boolean = false,
    val isRefreshingDevices: Boolean = false,
    val isInspectingDevice: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val deviceError: String? = null,
    val hubTab: GpuHubTab = GpuHubTab.OVERVIEW,
    val artifactFilter: String = "",
)

public data class GpuIntegrationActions(
    val onOpenArtifact: (GpuArtifact) -> Unit,
    val onVerifyArtifact: (GpuArtifact) -> Unit,
    val onRevealArtifact: (GpuArtifact) -> Unit,
    val onRelocateArtifact: (GpuArtifact) -> Unit,
    val onRefreshDevices: () -> Unit = {},
    val onSelectDevice: (String) -> Unit = {},
    val onSelectArtifact: (String) -> Unit = {},
    val onOpenTraceAnalyzer: () -> Unit = {},
    val onSelectHubTab: (GpuHubTab) -> Unit = {},
    val onFilterArtifacts: (String) -> Unit = {},
    val onLaunchAgi: () -> Unit = {},
    val onImportArtifact: () -> Unit = {},
)

/**
 * Legacy single-column GPU integration screen. The desktop application renders
 * [GpuAnalysisHubScreen] instead; this composable is retained only for its localization contract,
 * while the state and action model below is shared by both surfaces.
 */
@Composable
public fun GpuIntegrationScreen(
    state: GpuIntegrationState,
    actions: GpuIntegrationActions,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(StudioTokens.workspacePadding),
        verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
    ) {
        StudioCard(Modifier.fillMaxWidth(), contentPadding = 0.dp) {
            Column(
                Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    localizedStringResource(Res.string.android_gpu_inspector, language),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    state.capability?.executable?.toString()
                        ?: localizedStringResource(Res.string.agi_is_not_configured, language),
                )
                Text(
                    localizedStringResource(
                        Res.string.capability_summary,
                        language,
                        state.capability?.version
                            ?: localizedStringResource(Res.string.unknown_version, language),
                        state.capability?.launchMode?.displayName(language)
                            ?: localizedStringResource(Res.string.unavailable, language),
                    ),
                )
                Text(
                    localizedStringResource(Res.string.tool_responsibility_hint, language),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.capability?.warnings.orEmpty().forEach { warning ->
                    Text(
                        localizedWarning(warning, language),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(
            localizedStringResource(Res.string.recent_gpu_artifacts, language),
            style = MaterialTheme.typography.titleLarge,
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap)) {
            items(state.artifacts) { artifact ->
                val availability = state.availabilityByArtifactId[artifact.id]
                StudioCard(Modifier.fillMaxWidth(), contentPadding = 0.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                localizedStringResource(
                                    Res.string.text,
                                    language,
                                    artifact.kind.displayName(language),
                                    artifact.path.fileName,
                                ),
                            )
                            Text(
                                localizedStringResource(
                                    Res.string.kib,
                                    language,
                                    artifact.sizeBytes / 1024,
                                    artifact.sha256.take(12),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            artifact.warnings.forEach { warning ->
                                Text(
                                    localizedWarning(warning, language),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            availability?.let {
                                Text(
                                    it.locationStatus.displayName(language),
                                    style = MaterialTheme.typography.bodySmall,
                                    color =
                                        if (it.locationStatus == ArtifactLocationStatus.AVAILABLE) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedButton(
                                enabled = !state.isBusy && availability?.resolvedPath != null,
                                onClick = { actions.onVerifyArtifact(artifact) },
                            ) {
                                Text(localizedStringResource(Res.string.verify, language))
                            }
                            OutlinedButton(
                                enabled =
                                    !state.isBusy &&
                                        availability?.canOpen == true,
                                onClick = { actions.onOpenArtifact(artifact) },
                            ) {
                                Text(
                                    availability?.primaryAction?.displayName(language)
                                        ?: localizedStringResource(Res.string.unavailable, language),
                                )
                            }
                            OutlinedButton(
                                enabled = !state.isBusy,
                                onClick = { actions.onRevealArtifact(artifact) },
                            ) {
                                Text(localizedStringResource(Res.string.show_in_files, language))
                            }
                            OutlinedButton(
                                enabled = !state.isBusy,
                                onClick = { actions.onRelocateArtifact(artifact) },
                            ) {
                                Text(localizedStringResource(Res.string.relocate, language))
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun GpuHubTab.displayName(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            GpuHubTab.OVERVIEW -> Res.string.tab_overview
            GpuHubTab.ARTIFACTS -> Res.string.tab_artifacts
            GpuHubTab.RELATED_EVIDENCE -> Res.string.tab_related_evidence
            GpuHubTab.TOOL_CAPABILITY -> Res.string.tab_tool_capability
        },
        language,
    )

internal fun GpuArtifactKind.displayName(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            GpuArtifactKind.AGI_SYSTEM_PROFILE -> Res.string.artifact_kind_agi_system_profile
            GpuArtifactKind.AGI_FRAME_PROFILE -> Res.string.artifact_kind_agi_frame_profile
            GpuArtifactKind.PERFETTO_TRACE -> Res.string.artifact_kind_perfetto_trace
            GpuArtifactKind.SCREENSHOT -> Res.string.artifact_kind_screenshot
            GpuArtifactKind.EXTERNAL_REPORT -> Res.string.artifact_kind_external_report
            GpuArtifactKind.UNKNOWN -> Res.string.artifact_kind_unknown
        },
        language,
    )

internal fun AgiLaunchMode.displayName(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            AgiLaunchMode.VERIFIED_CLI -> Res.string.launch_mode_verified_cli
            AgiLaunchMode.GUI_ONLY -> Res.string.launch_mode_gui_only
            AgiLaunchMode.UNSUPPORTED -> Res.string.launch_mode_unsupported
        },
        language,
    )

internal fun ArtifactPrimaryAction.displayName(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            ArtifactPrimaryAction.OPEN_AGI -> Res.string.open_in_agi
            ArtifactPrimaryAction.LAUNCH_AGI -> Res.string.launch_agi
            ArtifactPrimaryAction.OPEN_PERFETTO -> Res.string.open_in_perfetto
            ArtifactPrimaryAction.OPEN_DESKTOP -> Res.string.open_with_desktop
            ArtifactPrimaryAction.NONE -> Res.string.unavailable
        },
        language,
    )

internal fun ArtifactOpenRoute.displayName(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            ArtifactOpenRoute.AGI -> Res.string.open_in_agi
            ArtifactOpenRoute.PERFETTO -> Res.string.open_in_perfetto
            ArtifactOpenRoute.DESKTOP -> Res.string.open_with_desktop
            ArtifactOpenRoute.NONE -> Res.string.unavailable
        },
        language,
    )

internal fun ArtifactLocationStatus.displayName(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            ArtifactLocationStatus.AVAILABLE -> Res.string.artifact_available
            ArtifactLocationStatus.MISSING -> Res.string.artifact_missing
            ArtifactLocationStatus.SIZE_CHANGED -> Res.string.artifact_size_changed
            ArtifactLocationStatus.CONTENT_CHANGED -> Res.string.artifact_content_changed
            ArtifactLocationStatus.UNREADABLE -> Res.string.artifact_unreadable
        },
        language,
    )

internal fun localizedWarning(
    warning: String,
    language: UiLanguage,
): String =
    when (warning) {
        "Android GPU Inspector executable was not found. Configure its local path." ->
            localizedStringResource(Res.string.warning_agi_executable_not_found, language)
        "AGI version probe timed out; GUI launch remains available." ->
            localizedStringResource(Res.string.warning_agi_probe_timed_out, language)
        "AGI help probe timed out; CLI capabilities were not verified." ->
            localizedStringResource(Res.string.warning_agi_help_probe_timed_out, language)
        "AGI help probe failed; CLI capabilities were not verified." ->
            localizedStringResource(Res.string.warning_agi_help_probe_failed, language)
        "AGI version could not be determined." ->
            localizedStringResource(Res.string.warning_agi_version_unknown, language)
        "No stable automation arguments were detected; AGI will be launched in GUI-only mode." ->
            localizedStringResource(Res.string.warning_agi_gui_only, language)
        "Unknown artifact format; it is indexed as opaque evidence." ->
            localizedStringResource(Res.string.warning_unknown_artifact, language)
        "Opening an artifact through this configured executable was not verified." ->
            localizedStringResource(Res.string.warning_agi_artifact_open_unverified, language)
        "Opening an artifact through this AGI executable was not verified; launch AGI and open it from the application." ->
            localizedStringResource(Res.string.warning_agi_artifact_open_unverified, language)
        else -> warning
    }
