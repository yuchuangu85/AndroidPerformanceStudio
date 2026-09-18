@file:Suppress("FunctionName", "LongMethod", "MaxLineLength", "ktlint:standard:function-naming")

package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.memory.model.BitmapDumpComparison
import com.androidperformancestudio.memory.model.BitmapDumpSession
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.bitmap_average_content
import com.androidperformancestudio.memory.presentation.generated.resources.bitmap_content_memory
import com.androidperformancestudio.memory.presentation.generated.resources.bitmap_dump_comparison
import com.androidperformancestudio.memory.presentation.generated.resources.bitmap_dump_comparison_summary
import com.androidperformancestudio.memory.presentation.generated.resources.bitmap_dump_gallery
import com.androidperformancestudio.memory.presentation.generated.resources.bitmap_dump_image
import com.androidperformancestudio.memory.presentation.generated.resources.bitmap_dump_loading
import com.androidperformancestudio.memory.presentation.generated.resources.bitmap_dump_no_session
import com.androidperformancestudio.memory.presentation.generated.resources.bitmap_dump_summary
import com.androidperformancestudio.memory.presentation.generated.resources.bitmap_duplicate_groups
import com.androidperformancestudio.memory.presentation.generated.resources.bitmap_exported_images
import com.androidperformancestudio.memory.presentation.generated.resources.bitmap_image_unavailable
import com.androidperformancestudio.memory.presentation.generated.resources.bitmap_java_pss
import com.androidperformancestudio.memory.presentation.generated.resources.bitmap_largest_image
import com.androidperformancestudio.memory.presentation.generated.resources.bitmap_native_pss
import com.androidperformancestudio.memory.presentation.generated.resources.bitmap_native_ratio
import com.androidperformancestudio.memory.presentation.generated.resources.bitmap_png_storage
import com.androidperformancestudio.memory.presentation.generated.resources.bitmap_total_pss
import com.androidperformancestudio.memory.presentation.generated.resources.bitmap_unique_images
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import java.nio.file.Files
import org.jetbrains.skia.Image as SkiaImage

@Composable
public fun MemoryProfilerBitmapDumpPage(
    session: BitmapDumpSession?,
    comparison: BitmapDumpComparison?,
    isLoading: Boolean,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(8.dp)
                .testTag("memory-profiler-bitmap-dump-page"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.material3.Text(
                localizedStringResource(Res.string.bitmap_dump_gallery, language),
                fontWeight = FontWeight.Bold,
            )
            session?.let {
                androidx.compose.material3.Text(
                    "${it.packageName} · pid ${it.pid} · API ${it.sdkLevel}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = ViewerTypography.bodyCompact.fontSize,
                )
            }
        }
        when {
            session != null ->
                BitmapDumpGalleryContent(
                    session = session,
                    comparison = comparison,
                    language = language,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            isLoading ->
                androidx.compose.material3.Text(
                    localizedStringResource(Res.string.bitmap_dump_loading, language),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else ->
                androidx.compose.material3.Text(
                    localizedStringResource(Res.string.bitmap_dump_no_session, language),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
}

@Composable
private fun BitmapDumpGalleryContent(
    session: BitmapDumpSession,
    comparison: BitmapDumpComparison?,
    language: UiLanguage,
    modifier: Modifier,
) {
    Column(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(4.dp))
                .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                .padding(8.dp),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 180.dp),
            modifier = Modifier.fillMaxSize().testTag("memory-profiler-bitmap-grid"),
            contentPadding = PaddingValues(0.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                BitmapDumpSummaryMetrics(session = session, language = language)
            }
            if (session.images.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    androidx.compose.material3.Text(
                        localizedStringResource(Res.string.bitmap_dump_no_session, language),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(session.images, key = { it.recordIndex }) { bitmap ->
                    BitmapDumpImageCard(bitmap = bitmap, language = language)
                }
            }
            comparison?.let { diff ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        androidx.compose.material3.Text(
                            localizedStringResource(Res.string.bitmap_dump_comparison, language),
                            fontWeight = FontWeight.Bold,
                        )
                        androidx.compose.material3.Text(
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
        }
    }
}

@Composable
private fun BitmapDumpSummaryMetrics(
    session: BitmapDumpSession,
    language: UiLanguage,
) {
    val summary = session.summary
    val averageBytes =
        if (summary.exportedImageCount == 0) 0L else summary.estimatedBitmapBytes / summary.exportedImageCount
    val largestImage = session.images.maxByOrNull { it.width.toLong() * it.height }
    val metrics =
        buildList {
            add(
                localizedStringResource(Res.string.bitmap_content_memory, language) to
                    formatBytes(summary.estimatedBitmapBytes),
            )
            add(
                localizedStringResource(Res.string.bitmap_png_storage, language) to
                    formatBytes(summary.totalPngBytes),
            )
            add(
                localizedStringResource(Res.string.bitmap_average_content, language) to
                    formatBytes(averageBytes),
            )
            add(
                localizedStringResource(Res.string.bitmap_exported_images, language) to
                    integer(summary.exportedImageCount),
            )
            add(
                localizedStringResource(Res.string.bitmap_unique_images, language) to
                    integer(summary.uniqueImageCount),
            )
            add(
                localizedStringResource(Res.string.bitmap_duplicate_groups, language) to
                    integer(summary.duplicateGroupCount),
            )
            largestImage?.let {
                add(
                    localizedStringResource(Res.string.bitmap_largest_image, language) to
                        "${it.width}×${it.height} · ${formatBytes(it.estimatedMemoryBytes)}",
                )
            }
            summary.bitmapNativeHeapRatioPercent?.let {
                add(
                    localizedStringResource(Res.string.bitmap_native_ratio, language) to
                        "${"%.1f".format(java.util.Locale.US, it)}%",
                )
            }
            session.memorySnapshot?.let { memory ->
                add(
                    localizedStringResource(Res.string.bitmap_total_pss, language) to
                        formatBytes(memory.totalPssBytes),
                )
                add(
                    localizedStringResource(Res.string.bitmap_java_pss, language) to
                        formatBytes(memory.javaHeapPssBytes),
                )
                add(
                    localizedStringResource(Res.string.bitmap_native_pss, language) to
                        formatBytes(memory.nativeHeapPssBytes),
                )
            }
        }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.material3.Text(
            localizedStringResource(
                Res.string.bitmap_dump_summary,
                language,
                summary.exportedImageCount,
                summary.uniqueImageCount,
                summary.duplicateGroupCount,
                formatBytes(summary.estimatedBitmapBytes),
            ),
            fontWeight = FontWeight.Bold,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth().testTag("memory-profiler-bitmap-summary-metrics"),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            metrics.forEach { (label, value) ->
                BitmapSummaryMetricCard(label = label, value = value)
            }
        }
    }
}

@Composable
private fun BitmapSummaryMetricCard(
    label: String,
    value: String,
) {
    Column(
        modifier =
            Modifier
                .width(150.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        androidx.compose.material3.Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = ViewerTypography.bodyCompact.fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        androidx.compose.material3.Text(value, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BitmapDumpImageCard(
    bitmap: com.androidperformancestudio.memory.model.BitmapDumpImage,
    language: UiLanguage,
) {
    val image =
        remember(bitmap.file) {
            runCatching {
                if (!Files.isRegularFile(bitmap.file)) return@runCatching null
                SkiaImage.makeFromEncoded(Files.readAllBytes(bitmap.file)).toComposeImageBitmap()
            }.getOrNull()
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("bitmap-dump-image-card-${bitmap.recordIndex}")
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
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
            ) {
                androidx.compose.material3.Text(
                    localizedStringResource(Res.string.bitmap_image_unavailable, language, bitmap.file.fileName.toString()),
                    modifier = Modifier.padding(8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = ViewerTypography.bodyCompact.fontSize,
                )
            }
        }
        androidx.compose.material3.Text(
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
            fontSize = ViewerTypography.bodyCompact.fontSize,
        )
    }
}

private fun Int.withSign(): String = if (this >= 0) "+$this" else toString()

private fun Long.withSign(): String = if (this >= 0) "+$this" else toString()
