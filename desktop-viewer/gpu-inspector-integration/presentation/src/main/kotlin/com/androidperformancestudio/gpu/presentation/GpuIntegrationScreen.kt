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
    chinese: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Android GPU Inspector", style = MaterialTheme.typography.titleLarge)
                Text(
                    state.capability?.executable?.toString()
                        ?: if (chinese) "未配置 AGI" else "AGI is not configured",
                )
                Text(
                    "${state.capability?.version ?: "Unknown version"} · " +
                        "${state.capability?.launchMode ?: "UNAVAILABLE"}",
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
            if (chinese) "最近 GPU 产物" else "Recent GPU artifacts",
            style = MaterialTheme.typography.titleLarge,
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.artifacts) { artifact ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("${artifact.kind} · ${artifact.path.fileName}")
                            Text(
                                "${artifact.sizeBytes / 1024} KiB · ${artifact.sha256.take(12)}…",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            artifact.warnings.forEach { warning ->
                                Text(warning, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { actions.onVerifyArtifact(artifact) }) {
                                Text(if (chinese) "校验" else "Verify")
                            }
                            OutlinedButton(
                                enabled = artifact.openCapability != ArtifactOpenCapability.NONE,
                                onClick = { actions.onOpenArtifact(artifact) },
                            ) {
                                Text(if (chinese) "打开" else "Open")
                            }
                        }
                    }
                }
            }
        }
    }
}
