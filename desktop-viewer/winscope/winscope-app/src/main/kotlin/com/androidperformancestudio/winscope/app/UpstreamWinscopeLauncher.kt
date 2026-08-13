package com.androidperformancestudio.winscope.app

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.winscope.model.WinscopeAnnotation
import com.androidperformancestudio.winscope.model.WinscopeSession
import com.androidperformancestudio.winscope.storage.WinscopeSessionFiles
import java.nio.file.Files
import java.nio.file.Path

class UpstreamWinscopeLauncher(
    private val server: UpstreamWinscopeServer = UpstreamWinscopeServer(),
    private val sessionFiles: WinscopeSessionFiles = WinscopeSessionFiles(),
    private val assetsDirectory: Path? = UpstreamWinscopeServer.tryFindAssetsDirectory(),
) : AutoCloseable {
    private var evidencePackage: Path? = null

    fun open(
        session: WinscopeSession,
        annotations: List<WinscopeAnnotation>,
    ): StudioResult<Unit> {
        val assets =
            assetsDirectory
                ?: return failure("UPSTREAM_WINSCOPE_ASSETS_MISSING", "Packaged upstream Winscope assets are unavailable")
        when (val started = server.start(assets)) {
            is StudioResult.Failure -> return started
            is StudioResult.Success -> Unit
        }
        val generated =
            try {
                Files.createTempFile("aps-winscope-${session.id.take(8)}-", ".winscope.zip")
            } catch (exception: Exception) {
                return failure(
                    "UPSTREAM_WINSCOPE_TEMP_FILE_FAILED",
                    exception.message ?: "Unable to create temporary Winscope evidence",
                    ErrorCategory.IO,
                    exception,
                )
            }
        when (val exported = sessionFiles.export(session.copy(annotations = annotations), generated, true)) {
            is StudioResult.Failure -> {
                Files.deleteIfExists(generated)
                return exported
            }
            is StudioResult.Success -> Unit
        }
        return when (val opened = server.openEvidence(generated)) {
            is StudioResult.Failure -> {
                Files.deleteIfExists(generated)
                opened
            }
            is StudioResult.Success -> {
                evidencePackage?.let(Files::deleteIfExists)
                evidencePackage = generated
                StudioResult.Success(Unit)
            }
        }
    }

    override fun close() {
        server.close()
        evidencePackage?.let(Files::deleteIfExists)
        evidencePackage = null
    }
}

private fun <T> failure(
    code: String,
    message: String,
    category: ErrorCategory = ErrorCategory.CONFIGURATION,
    cause: Throwable? = null,
): StudioResult<T> =
    StudioResult.Failure(
        StudioError(
            category = category,
            code = code,
            message = message,
            cause = cause,
        ),
    )
