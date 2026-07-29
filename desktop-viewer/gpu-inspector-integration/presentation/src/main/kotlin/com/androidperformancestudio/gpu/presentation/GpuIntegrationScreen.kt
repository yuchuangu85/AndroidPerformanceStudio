@file:Suppress("FunctionName", "ktlint:standard:function-naming", "LongMethod")

package com.androidperformancestudio.gpu.presentation

import com.androidperformancestudio.ui.UiLanguage
import org.jetbrains.compose.resources.stringResource

import com.androidperformancestudio.gpu.presentation.generated.resources.Res
import com.androidperformancestudio.gpu.presentation.generated.resources.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.gpu.model.AgiCapability
import com.androidperformancestudio.gpu.model.ArtifactOpenCapability
import com.androidperformancestudio.gpu.model.GpuArtifact

public data class GpuIntegrationState(
    val capability: AgiCapability? = null,
    val artifacts: List<GpuArtifact> = emptyList(),
    val message: String? = null,
    val error: String? = null,
)

public data class GpuIntegrationActions(
    val onOpenArtifact: (GpuArtifact) -> Unit,
    val onVerifyArtifact: (GpuArtifact) -> Unit,
)

@Composable
public fun GpuIntegrationScreen(
    state: GpuIntegrationState,
    actions: GpuIntegrationActions,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(Res.string.android_gpu_inspector), style = MaterialTheme.typography.titleLarge)
                Text(
                    state.capability?.executable?.toString()
                        ?: stringResource(Res.string.agi_is_not_configured),
                )
                Text(
                    stringResource(Res.string.capability_summary, state.capability?.version ?: stringResource(Res.string.unknown_version), state.capability?.launchMode ?: stringResource(Res.string.unavailable), ),
                )
                state.capability?.warnings.orEmpty().forEach { warning ->
                    Text(
                        warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(
            stringResource(Res.string.recent_gpu_artifacts),
            style = MaterialTheme.typography.titleLarge,
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.artifacts) { artifact ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(stringResource(Res.string.text, artifact.kind, artifact.path.fileName))
                            Text(
                                stringResource(Res.string.kib, artifact.sizeBytes / 1024, artifact.sha256.take(12)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            artifact.warnings.forEach { warning ->
                                Text(warning, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedButton(onClick = { actions.onVerifyArtifact(artifact) }) {
                                Text(stringResource(Res.string.verify))
                            }
                            OutlinedButton(
                                enabled = artifact.openCapability != ArtifactOpenCapability.NONE,
                                onClick = { actions.onOpenArtifact(artifact) },
                            ) {
                                Text(stringResource(Res.string.open))
                            }
                        }
                    }
                }
            }
        }
    }
}
