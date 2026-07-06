package dev.agentperf.desktop

import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal enum class CaptureArchiveOperation {
    IMPORT,
    EXPORT,
}

internal sealed interface CaptureArchiveUiState {
    data object Idle : CaptureArchiveUiState

    data class Working(
        val operation: CaptureArchiveOperation,
    ) : CaptureArchiveUiState

    data class Success(
        val operation: CaptureArchiveOperation,
        val path: Path,
        val rawArtifactsIncluded: Boolean = true,
    ) : CaptureArchiveUiState

    data class Failure(
        val operation: CaptureArchiveOperation,
        val message: String,
    ) : CaptureArchiveUiState
}

internal fun captureArchiveDefaultFileName(
    packageName: String,
    capturedAtEpochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val safePackageName = packageName
        .replace(UNSAFE_FILE_NAME_CHARACTER, "_")
        .ifBlank { "capture" }
    val timestamp = ARCHIVE_TIMESTAMP_FORMATTER
        .withZone(zoneId)
        .format(Instant.ofEpochMilli(capturedAtEpochMillis))
    return "$safePackageName-$timestamp.apinspect"
}

private val UNSAFE_FILE_NAME_CHARACTER = Regex("""[^A-Za-z0-9._-]""")
private val ARCHIVE_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
