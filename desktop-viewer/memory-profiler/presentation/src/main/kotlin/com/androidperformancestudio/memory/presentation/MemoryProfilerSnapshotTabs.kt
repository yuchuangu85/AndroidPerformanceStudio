@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
                .background(MaterialTheme.colorScheme.surface)
                .horizontalScroll(rememberScrollState())
                .height(42.dp)
                .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            localizedStringResource(Res.string.snapshots, language),
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
    val shape = RoundedCornerShape(0.dp)
    val containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    TooltipArea(tooltip = {
        androidx.compose.material3.Surface(shadowElevation = 4.dp) {
            Text(evidence.accessibilityLabel, Modifier.widthIn(max = 500.dp).padding(10.dp))
        }
    }) {
        Row(
            modifier =
                Modifier
                    .widthIn(min = 120.dp, max = 260.dp)
                    .height(40.dp)
                    .clip(shape)
                    .background(containerColor)
                    .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                    .clickable(role = Role.Tab) { onSelectSnapshot(snapshot.id) }
                    .semantics {
                        contentDescription = evidence.accessibilityLabel
                        this.selected = selected
                    }.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                evidence.name,
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MemoryToolButton("\u00D7", evidence.closeLabel) { onCloseSnapshot(snapshot.id) }
        }
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
