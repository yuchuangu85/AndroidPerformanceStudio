package com.androidperformancestudio.importing

import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ImportSourceValidatorTest {
    @Test
    fun `accepts a readable regular file and normalizes its path`() {
        val file = createTempDirectory("import-core").resolve("capture.trace").createFile()

        val result = assertIs<ImportResult.Success<ImportSource>>(ImportSourceValidator.validate(file))

        assertEquals(file.toAbsolutePath().normalize(), result.value.path)
        assertEquals("capture.trace", result.value.fileName)
    }

    @Test
    fun `rejects missing files and directories with structured reasons`() {
        val directory = createTempDirectory("import-core-directory")
        val missing = Path.of("missing-import-source-${System.nanoTime()}")

        val missingResult = assertIs<ImportResult.Failure>(ImportSourceValidator.validate(missing))
        val directoryResult = assertIs<ImportResult.Failure>(ImportSourceValidator.validate(directory))

        assertEquals(ImportFailureReason.SOURCE_NOT_FOUND, missingResult.reason)
        assertEquals(ImportFailureReason.SOURCE_NOT_REGULAR_FILE, directoryResult.reason)
    }
}
