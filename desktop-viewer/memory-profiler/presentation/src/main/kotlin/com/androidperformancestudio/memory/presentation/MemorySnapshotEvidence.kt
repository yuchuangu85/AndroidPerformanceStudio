package com.androidperformancestudio.memory.presentation

import com.androidperformancestudio.memory.model.HeapSnapshotSummary
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.snapshot_capabilities
import com.androidperformancestudio.memory.presentation.generated.resources.snapshot_counts
import com.androidperformancestudio.memory.presentation.generated.resources.snapshot_identity
import com.androidperformancestudio.memory.presentation.generated.resources.snapshot_mapping
import com.androidperformancestudio.memory.presentation.generated.resources.snapshot_provenance
import com.androidperformancestudio.memory.presentation.generated.resources.unavailable
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.time.Instant

internal fun HeapSnapshotSummary.evidenceLines(language: UiLanguage): List<String> {
    val unavailable = localizedStringResource(Res.string.unavailable, language)
    val sourceName = sourceFile?.fileName?.toString()?.takeIf(String::isNotBlank) ?: unavailable
    val inputFormat = format.takeIf(String::isNotBlank) ?: unavailable
    val idWidth = idSize.takeIf { it > 0 }?.let { "$it B" } ?: unavailable
    val recorded = capturedAt.takeUnless { it == Instant.EPOCH }?.toString() ?: unavailable
    val loaded = loadedAt.takeUnless { it == Instant.EPOCH }?.toString() ?: unavailable
    val fileSize = fileSizeBytes?.takeIf { it >= 0 }?.let { "$it B" } ?: unavailable
    val sourceHash = sourceFileDigest?.takeIf(String::isNotBlank) ?: unavailable
    val mappingHash = mappingDigest?.takeIf(String::isNotBlank) ?: unavailable
    val algorithm = analysisVersion.takeIf(String::isNotBlank) ?: unavailable
    val availableCapabilities =
        capabilities
            .map { it.name }
            .sorted()
            .joinToString()
            .ifEmpty { unavailable }

    return listOf(
        localizedStringResource(Res.string.snapshot_identity, language, id, sourceName, inputFormat, idWidth),
        localizedStringResource(Res.string.snapshot_counts, language, classCount, objectCount, warningCount),
        localizedStringResource(Res.string.snapshot_provenance, language, recorded, loaded, fileSize, sourceHash),
        localizedStringResource(Res.string.snapshot_mapping, language, mappingHash, algorithm),
        localizedStringResource(Res.string.snapshot_capabilities, language, availableCapabilities),
    )
}
