@file:Suppress("ktlint:standard:function-naming")

package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.memory.model.HeapSnapshotSummary
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.close_snapshot
import com.androidperformancestudio.memory.presentation.generated.resources.snapshot_tab_capabilities
import com.androidperformancestudio.memory.presentation.generated.resources.snapshot_tab_counts
import com.androidperformancestudio.memory.presentation.generated.resources.snapshot_tab_loaded
import com.androidperformancestudio.memory.presentation.generated.resources.snapshot_tab_source
import com.androidperformancestudio.memory.presentation.generated.resources.snapshots
import com.androidperformancestudio.memory.presentation.generated.resources.unavailable
import com.androidperformancestudio.memory.presentation.generated.resources.unknown_snapshot
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.time.Instant

@Composable
@Suppress("FunctionNaming")
public fun MemoryProfilerSnapshotTabs(
    state: MemoryProfilerState,
    onSelectSnapshot: (String) -> Unit,
    onCloseSnapshot: (String) -> Unit,
    language: UiLanguage,
) {
    if (state.snapshotSummaries.isEmpty()) return
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            localizedStringResource(Res.string.snapshots, language),
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.snapshotSummaries.forEach { snapshot ->
            SnapshotTabCard(
                snapshot = snapshot,
                evidence = snapshot.tabEvidence(language),
                selected = snapshot.id == state.activeSnapshotId,
                onSelectSnapshot = onSelectSnapshot,
                onCloseSnapshot = onCloseSnapshot,
            )
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun SnapshotTabCard(
    snapshot: HeapSnapshotSummary,
    evidence: SnapshotTabEvidence,
    selected: Boolean,
    onSelectSnapshot: (String) -> Unit,
    onCloseSnapshot: (String) -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Column(
        modifier =
            Modifier
                .width(320.dp)
                .clip(shape)
                .background(containerColor)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                .clickable(role = Role.Tab) { onSelectSnapshot(snapshot.id) }
                .semantics {
                    contentDescription = evidence.accessibilityLabel
                    this.selected = selected
                }.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                evidence.name,
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            ProfilerCompactButton(
                text = "×",
                modifier =
                    Modifier.semantics {
                        contentDescription = evidence.closeLabel
                    },
                onClick = { onCloseSnapshot(snapshot.id) },
            )
        }
        Text(evidence.source, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(evidence.loaded, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(evidence.counts, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(evidence.capabilities, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal data class SnapshotTabEvidence(
    val name: String,
    val source: String,
    val loaded: String,
    val counts: String,
    val capabilities: String,
    val closeLabel: String,
) {
    val accessibilityLabel: String get() = listOf(name, source, loaded, counts, capabilities).joinToString(" · ")
}

internal fun HeapSnapshotSummary.tabEvidence(language: UiLanguage): SnapshotTabEvidence {
    val unavailable = localizedStringResource(Res.string.unavailable, language)
    val source =
        sourceFile
            ?.fileName
            ?.toString()
            ?.substringAfterLast('\\')
            ?.takeIf(String::isNotBlank) ?: unavailable
    val name =
        id.substringAfterLast('/').substringAfterLast('\\').takeIf(String::isNotBlank)
            ?: source.takeUnless { it == unavailable }
            ?: localizedStringResource(Res.string.unknown_snapshot, language)
    val loaded = loadedAt.takeUnless { it == Instant.EPOCH }?.toString() ?: unavailable
    val availableCapabilities =
        capabilities
            .map { it.name }
            .sorted()
            .joinToString()
            .ifEmpty { unavailable }
    return SnapshotTabEvidence(
        name = name,
        source = localizedStringResource(Res.string.snapshot_tab_source, language, source),
        loaded = localizedStringResource(Res.string.snapshot_tab_loaded, language, loaded),
        counts =
            localizedStringResource(
                Res.string.snapshot_tab_counts,
                language,
                classCount,
                objectCount,
                warningCount,
            ),
        capabilities = localizedStringResource(Res.string.snapshot_tab_capabilities, language, availableCapabilities),
        closeLabel = localizedStringResource(Res.string.close_snapshot, language, name),
    )
}
