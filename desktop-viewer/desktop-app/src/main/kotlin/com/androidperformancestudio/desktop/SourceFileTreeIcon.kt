package com.androidperformancestudio.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.source.SourceLanguage
import com.androidperformancestudio.ui.LocalViewerColors

internal enum class SourceFileTreeIconKind {
    KOTLIN,
    JAVA,
    XML,
    NATIVE,
    GENERIC,
}

internal fun SourceLanguage.sourceFileTreeIconKind(): SourceFileTreeIconKind =
    when (this) {
        SourceLanguage.KOTLIN -> SourceFileTreeIconKind.KOTLIN
        SourceLanguage.JAVA -> SourceFileTreeIconKind.JAVA
        SourceLanguage.XML -> SourceFileTreeIconKind.XML
        SourceLanguage.C,
        SourceLanguage.CPP,
        -> SourceFileTreeIconKind.NATIVE
        SourceLanguage.OTHER -> SourceFileTreeIconKind.GENERIC
    }

@Composable
internal fun SourceFileTreeFolderIcon(expanded: Boolean) {
    val colors = LocalViewerColors.current
    Canvas(Modifier.size(14.dp)) {
        val corner = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
        val iconColor = colors.info
        drawRoundRect(
            color = iconColor.copy(alpha = 0.86f),
            topLeft = Offset(1.dp.toPx(), 3.dp.toPx()),
            size = Size(8.dp.toPx(), 4.dp.toPx()),
            cornerRadius = corner,
        )
        drawRoundRect(
            color = iconColor,
            topLeft = Offset(1.dp.toPx(), if (expanded) 5.dp.toPx() else 6.dp.toPx()),
            size = Size(12.dp.toPx(), if (expanded) 7.dp.toPx() else 6.dp.toPx()),
            cornerRadius = corner,
        )
    }
}

@Composable
internal fun SourceFileTreeFileIcon(kind: SourceFileTreeIconKind) {
    val colors = LocalViewerColors.current
    val iconColor = when (kind) {
        SourceFileTreeIconKind.KOTLIN -> colors.accent
        SourceFileTreeIconKind.JAVA -> colors.warning
        SourceFileTreeIconKind.XML -> colors.success
        SourceFileTreeIconKind.NATIVE -> colors.info
        SourceFileTreeIconKind.GENERIC -> colors.mutedText
    }
    Canvas(Modifier.size(14.dp)) {
        val corner = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
        val stroke = Stroke(1.dp.toPx())
        drawRoundRect(
            color = iconColor.copy(alpha = 0.15f),
            topLeft = Offset(2.dp.toPx(), 1.dp.toPx()),
            size = Size(10.dp.toPx(), 12.dp.toPx()),
            cornerRadius = corner,
        )
        drawRoundRect(
            color = iconColor,
            topLeft = Offset(2.dp.toPx(), 1.dp.toPx()),
            size = Size(10.dp.toPx(), 12.dp.toPx()),
            cornerRadius = corner,
            style = stroke,
        )
        when (kind) {
            SourceFileTreeIconKind.KOTLIN -> {
                drawLine(
                    color = iconColor,
                    start = Offset(4.dp.toPx(), 4.dp.toPx()),
                    end = Offset(10.dp.toPx(), 10.dp.toPx()),
                    strokeWidth = 1.2.dp.toPx(),
                )
                drawLine(
                    color = iconColor,
                    start = Offset(10.dp.toPx(), 4.dp.toPx()),
                    end = Offset(4.dp.toPx(), 10.dp.toPx()),
                    strokeWidth = 1.2.dp.toPx(),
                )
            }
            SourceFileTreeIconKind.JAVA,
            SourceFileTreeIconKind.NATIVE,
            -> {
                drawLine(
                    color = iconColor,
                    start = Offset(4.dp.toPx(), 5.dp.toPx()),
                    end = Offset(10.dp.toPx(), 5.dp.toPx()),
                    strokeWidth = 1.2.dp.toPx(),
                )
                drawLine(
                    color = iconColor,
                    start = Offset(4.dp.toPx(), 8.dp.toPx()),
                    end = Offset(9.dp.toPx(), 8.dp.toPx()),
                    strokeWidth = 1.2.dp.toPx(),
                )
            }
            SourceFileTreeIconKind.XML -> {
                drawLine(
                    color = iconColor,
                    start = Offset(4.dp.toPx(), 5.dp.toPx()),
                    end = Offset(6.dp.toPx(), 7.dp.toPx()),
                    strokeWidth = 1.2.dp.toPx(),
                )
                drawLine(
                    color = iconColor,
                    start = Offset(6.dp.toPx(), 7.dp.toPx()),
                    end = Offset(4.dp.toPx(), 9.dp.toPx()),
                    strokeWidth = 1.2.dp.toPx(),
                )
                drawLine(
                    color = iconColor,
                    start = Offset(10.dp.toPx(), 5.dp.toPx()),
                    end = Offset(8.dp.toPx(), 7.dp.toPx()),
                    strokeWidth = 1.2.dp.toPx(),
                )
                drawLine(
                    color = iconColor,
                    start = Offset(8.dp.toPx(), 7.dp.toPx()),
                    end = Offset(10.dp.toPx(), 9.dp.toPx()),
                    strokeWidth = 1.2.dp.toPx(),
                )
            }
            SourceFileTreeIconKind.GENERIC -> {
                drawLine(
                    color = iconColor,
                    start = Offset(4.dp.toPx(), 6.dp.toPx()),
                    end = Offset(10.dp.toPx(), 6.dp.toPx()),
                    strokeWidth = 1.2.dp.toPx(),
                )
                drawLine(
                    color = iconColor,
                    start = Offset(4.dp.toPx(), 9.dp.toPx()),
                    end = Offset(8.dp.toPx(), 9.dp.toPx()),
                    strokeWidth = 1.2.dp.toPx(),
                )
            }
        }
    }
}
