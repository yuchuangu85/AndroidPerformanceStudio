package com.androidperformancestudio.perfetto.app

import com.androidperformancestudio.contracts.CaptureArtifact
import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Prepare file evidence before publishing a new active trace or attempting to open its viewer. */
internal fun prepareTraceArtifact(
    traceFile: Path,
    artifact: CaptureArtifact?,
    importArtifact: (Path) -> CaptureArtifact,
): StudioResult<CaptureArtifact> =
    try {
        if (!Files.isRegularFile(traceFile)) {
            StudioResult.Failure(
                StudioError(
                    category = ErrorCategory.IO,
                    code = "TRACE_FILE_UNAVAILABLE",
                    message =
                        "Trace file is missing or is not a regular file: $traceFile. " +
                            "Re-import an existing trace or record a new capture.",
                ),
            )
        } else {
            StudioResult.Success(artifact ?: importArtifact(traceFile))
        }
    } catch (exception: IOException) {
        traceOpenFailure(traceFile, exception)
    } catch (exception: IllegalArgumentException) {
        traceOpenFailure(traceFile, exception)
    } catch (exception: SecurityException) {
        traceOpenFailure(traceFile, exception)
    }

private fun traceOpenFailure(
    traceFile: Path,
    exception: Exception,
): StudioResult.Failure =
    StudioResult.Failure(
        StudioError(
            category = ErrorCategory.IO,
            code = "TRACE_OPEN_FAILED",
            message = "Failed to open trace $traceFile: ${exception.message ?: "Trace file is unavailable"}",
            cause = exception,
        ),
    )
