@file:Suppress(
    "FunctionNaming",
    "LongMethod",
    "MagicNumber",
    "MaxLineLength",
    "TooManyFunctions",
    "ktlint:standard:function-naming",
)

package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.memory.model.BitmapDumpComparison
import com.androidperformancestudio.memory.model.BitmapDumpSession
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapSummary
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.activity
import com.androidperformancestudio.memory.presentation.generated.resources.bitmap_analysis
import com.androidperformancestudio.memory.presentation.generated.resources.bitmap_dump_comparison
import com.androidperformancestudio.memory.presentation.generated.resources.bitmap_dump_comparison_summary
import com.androidperformancestudio.memory.presentation.generated.resources.bitmap_dump_gallery
import com.androidperformancestudio.memory.presentation.generated.resources.bitmap_dump_image
import com.androidperformancestudio.memory.presentation.generated.resources.bitmap_dump_summary
import com.androidperformancestudio.memory.presentation.generated.resources.bitmap_entry
import com.androidperformancestudio.memory.presentation.generated.resources.class_histogram
import com.androidperformancestudio.memory.presentation.generated.resources.class_name
import com.androidperformancestudio.memory.presentation.generated.resources.classes
import com.androidperformancestudio.memory.presentation.generated.resources.cleanup_warning
import com.androidperformancestudio.memory.presentation.generated.resources.count
import com.androidperformancestudio.memory.presentation.generated.resources.count_value
import com.androidperformancestudio.memory.presentation.generated.resources.heap_diff
import com.androidperformancestudio.memory.presentation.generated.resources.heap_diff_entry
import com.androidperformancestudio.memory.presentation.generated.resources.heap_size
import com.androidperformancestudio.memory.presentation.generated.resources.import_or_dump_an_hprof_file_to_show_class_histogram
import com.androidperformancestudio.memory.presentation.generated.resources.in_progress
import com.androidperformancestudio.memory.presentation.generated.resources.leak_suspect_summary
import com.androidperformancestudio.memory.presentation.generated.resources.leak_suspect_title
import com.androidperformancestudio.memory.presentation.generated.resources.leak_suspects
import com.androidperformancestudio.memory.presentation.generated.resources.no_class_changes_between_the_latest_two_heap_dumps
import com.androidperformancestudio.memory.presentation.generated.resources.no_leak_suspects_detected
import com.androidperformancestudio.memory.presentation.generated.resources.objects
import com.androidperformancestudio.memory.presentation.generated.resources.overview
import com.androidperformancestudio.memory.presentation.generated.resources.reference_chain_entry
import com.androidperformancestudio.memory.presentation.generated.resources.retained
import com.androidperformancestudio.memory.presentation.generated.resources.shallow
import com.androidperformancestudio.memory.presentation.generated.resources.unavailable
import com.androidperformancestudio.memory.presentation.generated.resources.warning
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.nio.file.Files
import java.text.NumberFormat
import java.util.Locale
import org.jetbrains.skia.Image as SkiaImage

@Composable
public fun MemoryProfilerScreen(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
    language: UiLanguage = UiLanguage.ENGLISH,
    modifier: Modifier = Modifier,
) {
    val presentedState = MemoryProfilerPresenter.present(state)
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ErrorAndWarnings(presentedState, actions, language)
            Overview(summary = presentedState.summary, activityCount = presentedState.activityCount, language = language)
            Histogram(
                classes = presentedState.classes,
                sort = presentedState.sort,
                actions = actions,
                highlightedClassName = presentedState.highlightedClassName,
                language = language,
            )
            LeakSuspectsPhaseTwo(presentedState, language)
            HeapDiffSection(presentedState.heapDiff, language)
            BitmapSection(presentedState.bitmapInstances, language)
            BitmapDumpGallery(presentedState.bitmapDumpSession, presentedState.bitmapDumpComparison, language)
        }
    }
}

@Composable
private fun BitmapDumpGallery(
    session: BitmapDumpSession?,
    comparison: BitmapDumpComparison?,
    language: UiLanguage,
) {
    if (session == null) return
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(4.dp))
                .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(localizedStringResource(Res.string.bitmap_dump_gallery, language), fontWeight = FontWeight.Bold)
        Text(
            localizedStringResource(
                Res.string.bitmap_dump_summary,
                language,
                session.summary.exportedImageCount,
                session.summary.uniqueImageCount,
                session.summary.duplicateGroupCount,
                formatBytes(session.summary.estimatedBitmapBytes),
            ),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(session.images, key = { it.recordIndex }) { bitmap ->
                Column(
                    modifier =
                        Modifier
                            .width(180.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                            .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val image =
                        remember(bitmap.file) {
                            runCatching {
                                SkiaImage.makeFromEncoded(Files.readAllBytes(bitmap.file)).toComposeImageBitmap()
                            }.getOrNull()
                        }
                    if (image != null) {
                        Image(
                            bitmap = image,
                            contentDescription = "Bitmap ${bitmap.recordIndex}",
                            modifier = Modifier.fillMaxWidth().height(120.dp),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxWidth().height(120.dp),
                            color = MaterialTheme.colorScheme.surface,
                        ) {}
                    }
                    Text(
                        localizedStringResource(
                            Res.string.bitmap_dump_image,
                            language,
                            bitmap.recordIndex,
                            bitmap.width,
                            bitmap.height,
                            formatBytes(bitmap.estimatedMemoryBytes),
                            bitmap.duplicateCount,
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp,
                    )
                }
            }
        }
        comparison?.let { diff ->
            Text(localizedStringResource(Res.string.bitmap_dump_comparison, language), fontWeight = FontWeight.Bold)
            Text(
                localizedStringResource(
                    Res.string.bitmap_dump_comparison_summary,
                    language,
                    diff.before.exportedImageCount,
                    diff.after.exportedImageCount,
                    (diff.after.exportedImageCount - diff.before.exportedImageCount).withSign(),
                    (diff.after.estimatedBitmapBytes - diff.before.estimatedBitmapBytes).withSign(),
                ),
            )
        }
    }
}

@Composable
private fun ErrorAndWarnings(
    state: MemoryProfilerState,
    actions: MemoryProfilerActions,
    language: UiLanguage,
) {
    state.operationMessage?.let { message ->
        MessageCard(
            title = localizedStringResource(Res.string.in_progress, language),
            body = message,
            tone = MessageTone.INFO,
        ) {
            CircularProgressIndicator(Modifier.width(24.dp).height(24.dp))
        }
    }
    state.error?.let { error ->
        MessageCard(
            title = error.title,
            body = error.detail,
            tone = MessageTone.ERROR,
        ) {
            Button(onClick = actions.onRetry) { Text(error.retryLabel) }
        }
    }
    state.cleanupWarning?.let { warning ->
        MessageCard(
            title = localizedStringResource(Res.string.cleanup_warning, language),
            body = warning,
            tone = MessageTone.WARNING,
        )
    }
    state.warning?.let { warning ->
        MessageCard(
            title = localizedStringResource(Res.string.warning, language),
            body = warning,
            tone = MessageTone.WARNING,
        )
    }
}

@Composable
private fun MessageCard(
    title: String,
    body: String,
    tone: MessageTone,
    trailing: @Composable (() -> Unit)? = null,
) {
    val palette =
        when (tone) {
            MessageTone.INFO ->
                MessageCardPalette(
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                    MaterialTheme.colorScheme.primary,
                )
            MessageTone.ERROR ->
                MessageCardPalette(
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer,
                    MaterialTheme.colorScheme.error,
                )
            MessageTone.WARNING ->
                MessageCardPalette(
                    MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.onTertiaryContainer,
                    MaterialTheme.colorScheme.tertiary,
                )
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(palette.container, RoundedCornerShape(4.dp))
                .border(1.dp, palette.border, RoundedCornerShape(4.dp))
                .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.content, fontWeight = FontWeight.Bold)
            Text(body, color = palette.content)
        }
        trailing?.invoke()
    }
}

private enum class MessageTone { INFO, ERROR, WARNING }

private data class MessageCardPalette(
    val container: Color,
    val content: Color,
    val border: Color,
)

@Composable
private fun Overview(
    summary: HeapSummary,
    activityCount: Int,
    language: UiLanguage,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(localizedStringResource(Res.string.overview, language), fontWeight = FontWeight.Bold)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            MetricCard(localizedStringResource(Res.string.heap_size, language), formatBytes(summary.shallowSize), Modifier.weight(1f))
            MetricCard(localizedStringResource(Res.string.objects, language), integer(summary.objectCount), Modifier.weight(1f))
            MetricCard(localizedStringResource(Res.string.classes, language), integer(summary.classCount), Modifier.weight(1f))
            MetricCard(
                localizedStringResource(Res.string.activity, language),
                localizedStringResource(Res.string.count_value, language, integer(activityCount)),
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier,
) {
    Column(
        modifier =
            modifier
                .background(
                    MaterialTheme.colorScheme.surfaceContainer,
                    RoundedCornerShape(4.dp),
                ).border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(4.dp),
                ).padding(8.dp),
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Histogram(
    classes: List<ClassStats>,
    sort: MemoryHistogramSort,
    actions: MemoryProfilerActions,
    highlightedClassName: String?,
    language: UiLanguage,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(4.dp))
                .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(localizedStringResource(Res.string.class_histogram, language), fontWeight = FontWeight.Bold)
        HistogramHeader(sort, actions, language)
        if (classes.isEmpty()) {
            Text(localizedStringResource(Res.string.import_or_dump_an_hprof_file_to_show_class_histogram, language))
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(classes, key = { it.className }) { stats ->
                    HistogramRow(
                        stats = stats,
                        highlighted = stats.className == highlightedClassName,
                        onClick = { actions.onHighlightClass(stats.className) },
                        language = language,
                    )
                }
            }
        }
    }
}

@Composable
private fun HistogramHeader(
    sort: MemoryHistogramSort,
    actions: MemoryProfilerActions,
    language: UiLanguage,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(localizedStringResource(Res.string.class_name, language), Modifier.weight(1f), fontWeight = FontWeight.Bold)
        SortHeader(
            label = localizedStringResource(Res.string.count, language),
            headerSort = MemoryHistogramSort.Count,
            currentSort = sort,
            actions = actions,
            modifier = Modifier.width(96.dp),
        )
        SortHeader(
            label = localizedStringResource(Res.string.shallow, language),
            headerSort = MemoryHistogramSort.Shallow,
            currentSort = sort,
            actions = actions,
            modifier = Modifier.width(112.dp),
        )
        Text(localizedStringResource(Res.string.retained, language), Modifier.width(140.dp), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SortHeader(
    label: String,
    headerSort: MemoryHistogramSort,
    currentSort: MemoryHistogramSort,
    actions: MemoryProfilerActions,
    modifier: Modifier,
) {
    Text(
        text = if (currentSort == headerSort) "$label ↓" else label,
        modifier = modifier.clickable { actions.onSortHistogram(headerSort) },
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun HistogramRow(
    stats: ClassStats,
    highlighted: Boolean,
    onClick: () -> Unit,
    language: UiLanguage,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(
                if (highlighted) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                RoundedCornerShape(3.dp),
            ).clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stats.className,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(integer(stats.instanceCount), Modifier.width(96.dp))
        Text(formatBytes(stats.shallowSize), Modifier.width(112.dp))
        Text(
            text = stats.retainedSize?.let(::formatBytes) ?: localizedStringResource(Res.string.unavailable, language),
            modifier = Modifier.width(140.dp),
        )
    }
}

@Composable
private fun LeakSuspectsPhaseTwo(
    state: MemoryProfilerState,
    language: UiLanguage,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(4.dp))
                .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(localizedStringResource(Res.string.leak_suspects, language), fontWeight = FontWeight.Bold)
        if (state.leakSuspects.isEmpty()) {
            Text(localizedStringResource(Res.string.no_leak_suspects_detected, language))
        } else {
            state.leakSuspects.forEach { suspect ->
                var expanded by remember(suspect) { mutableStateOf(false) }
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(3.dp),
                            ).clickable { expanded = !expanded }
                            .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        localizedStringResource(
                            Res.string.leak_suspect_title,
                            language,
                            if (expanded) "▾" else "▸",
                            suspect.className,
                            suspect.reason,
                        ),
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        localizedStringResource(
                            Res.string.leak_suspect_summary,
                            language,
                            suspect.retainedSize?.let(::formatBytes) ?: "—",
                            (suspect.confidence * 100).toInt(),
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (expanded) {
                        suspect.referenceChain.forEachIndexed { index, reference ->
                            Text(
                                localizedStringResource(
                                    Res.string.reference_chain_entry,
                                    language,
                                    "  ".repeat(index),
                                    reference.fieldName,
                                    reference.targetClassName.ifBlank { reference.targetObjectId.toString() },
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeapDiffSection(
    diff: com.androidperformancestudio.memory.model.HeapDiff?,
    language: UiLanguage,
) {
    if (diff == null) return
    val changedEntries = diff.entries.filter { it.countDelta != 0 || it.shallowSizeDelta != 0L }
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(4.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(localizedStringResource(Res.string.heap_diff, language), fontWeight = FontWeight.Bold)
        if (changedEntries.isEmpty()) {
            Text(localizedStringResource(Res.string.no_class_changes_between_the_latest_two_heap_dumps, language))
        } else {
            changedEntries.take(10).forEach { entry ->
                Text(
                    localizedStringResource(
                        Res.string.heap_diff_entry,
                        language,
                        entry.className,
                        entry.beforeCount,
                        entry.afterCount,
                        entry.countDelta.withSign(),
                        entry.shallowSizeDelta.withSign(),
                    ),
                )
            }
        }
    }
}

@Composable
private fun BitmapSection(
    bitmaps: List<com.androidperformancestudio.memory.model.BitmapInstanceStats>,
    language: UiLanguage,
) {
    if (bitmaps.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(4.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(localizedStringResource(Res.string.bitmap_analysis, language), fontWeight = FontWeight.Bold)
        bitmaps.take(8).forEach { bitmap ->
            Text(
                localizedStringResource(
                    Res.string.bitmap_entry,
                    language,
                    bitmap.objectId,
                    bitmap.width ?: "?",
                    bitmap.height ?: "?",
                    formatBytes(bitmap.retainedSize),
                ),
            )
        }
    }
}

private fun Int.withSign(): String = if (this >= 0) "+$this" else toString()

private fun Long.withSign(): String = if (this >= 0) "+$this" else toString()

private fun integer(value: Int): String = NumberFormat.getIntegerInstance(Locale.US).format(value)

private fun formatBytes(bytes: Long): String =
    when {
        bytes >= BYTES_PER_MB -> "%.1f MB".format(Locale.US, bytes.toDouble() / BYTES_PER_MB)
        bytes >= BYTES_PER_KB -> "%.1f KB".format(Locale.US, bytes.toDouble() / BYTES_PER_KB)
        else -> "$bytes B"
    }

private const val BYTES_PER_KB = 1024.0
private const val BYTES_PER_MB = 1024.0 * 1024.0
