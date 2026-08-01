package com.androidperformancestudio.importing

import java.nio.file.Files
import java.nio.file.Path

public data class ImportSource(
    val path: Path,
) {
    public val fileName: String
        get() = path.fileName?.toString().orEmpty()
}

public data class ImportWarning(
    val code: String,
    val message: String,
)

public sealed interface ImportResult<out T> {
    public data class Success<T>(
        val value: T,
        val warnings: List<ImportWarning> = emptyList(),
    ) : ImportResult<T>

    public data class Failure(
        val reason: ImportFailureReason,
        val message: String,
        val cause: Throwable? = null,
    ) : ImportResult<Nothing>
}

public enum class ImportFailureReason {
    SOURCE_NOT_FOUND,
    SOURCE_NOT_REGULAR_FILE,
    SOURCE_NOT_READABLE,
    UNSUPPORTED_FORMAT,
    INVALID_CONTENT,
    IO,
    INTERNAL,
}

public fun interface Importer<T> {
    public suspend fun import(source: ImportSource): ImportResult<T>
}

public object ImportSourceValidator {
    public fun validate(path: Path): ImportResult<ImportSource> =
        when {
            Files.notExists(path) ->
                ImportResult.Failure(
                    reason = ImportFailureReason.SOURCE_NOT_FOUND,
                    message = "Import source does not exist: $path",
                )
            !Files.isRegularFile(path) ->
                ImportResult.Failure(
                    reason = ImportFailureReason.SOURCE_NOT_REGULAR_FILE,
                    message = "Import source is not a regular file: $path",
                )
            !Files.isReadable(path) ->
                ImportResult.Failure(
                    reason = ImportFailureReason.SOURCE_NOT_READABLE,
                    message = "Import source is not readable: $path",
                )
            else -> ImportResult.Success(ImportSource(path.toAbsolutePath().normalize()))
        }
}
