package com.androidperformancestudio.desktop

import com.androidperformancestudio.compose.inspection.ComposeCompilerClass
import com.androidperformancestudio.compose.inspection.ComposeCompilerFunction
import com.androidperformancestudio.compose.inspection.ComposeCompilerReport
import com.androidperformancestudio.compose.inspection.ComposeCompilerStability
import com.androidperformancestudio.presentation.generated.resources.Res
import com.androidperformancestudio.presentation.generated.resources.*
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.nio.file.Path
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(10)
class ComposeCompilerReportImportTest {
    @Test
    fun `directory and each supported report file reach parser unchanged`() = runBlocking {
        val parsed = mutableListOf<Path>()
        var loaded: ComposeCompilerReport? = null
        val importer = ComposeCompilerReportImport(
            parseReport = { selected -> parsed.add(selected); REPORT },
            loadReport = { loaded = it },
            workContext = EmptyCoroutineContext,
        )
        val selections = listOf(Path.of(REPORT.sourceDirectory)) + REPORT.files.map(Path::of)

        selections.forEach { selected ->
            assertTrue(importer.importReport { selected })
            assertSame(REPORT, loaded)
            assertEquals(ComposeCompilerReportImportState.Success(REPORT), importer.uiState)
            importer.dismissResult()
            assertEquals(ComposeCompilerReportImportState.Idle, importer.uiState)
        }
        assertEquals(selections, parsed)
    }

    @Test
    fun `cancelled selection does not parse replace or show a result`() = runBlocking {
        var parsed = false
        var loaded = REPORT
        val importer = ComposeCompilerReportImport(
            parseReport = { parsed = true; REPORT },
            loadReport = { loaded = it },
            workContext = EmptyCoroutineContext,
        )

        assertFalse(importer.importReport { null })
        assertFalse(parsed)
        assertSame(REPORT, loaded)
        assertEquals(ComposeCompilerReportImportState.Idle, importer.uiState)
    }

    @Test
    fun `parse or chooser failure is visible and leaves previous report loaded`() = runBlocking {
        for (chooserFails in listOf(false, true)) {
            var loaded = REPORT
            val importer = ComposeCompilerReportImport(
                parseReport = { throw IllegalArgumentException("No readable usable reports: /empty") },
                loadReport = { loaded = it },
                workContext = EmptyCoroutineContext,
            )

            assertFalse(importer.importReport {
                if (chooserFails) throw IllegalArgumentException("Chooser unavailable")
                Path.of("/empty")
            })
            assertSame(REPORT, loaded)
            assertEquals(
                ComposeCompilerReportImportState.Failure(
                    if (chooserFails) "Chooser unavailable" else "No readable usable reports: /empty",
                ),
                importer.uiState,
            )
        }
    }

    @Test
    fun `duplicate import cannot reopen chooser or replace in flight report`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var chooseCalls = 0
        var loadCalls = 0
        val importer = ComposeCompilerReportImport(
            parseReport = { entered.complete(Unit); release.await(); REPORT },
            loadReport = { loadCalls++ },
            workContext = EmptyCoroutineContext,
        )
        val job = launch {
            assertTrue(importer.importReport { chooseCalls++; Path.of(REPORT.sourceDirectory) })
        }
        entered.await()
        assertEquals(ComposeCompilerReportImportState.Working, importer.uiState)
        importer.dismissResult()
        assertEquals(ComposeCompilerReportImportState.Working, importer.uiState)
        assertFalse(importer.importReport { chooseCalls++; Path.of("/other") })
        assertEquals(1, chooseCalls)
        assertEquals(0, loadCalls)

        release.complete(Unit)
        job.join()
        assertEquals(1, loadCalls)
        assertEquals(ComposeCompilerReportImportState.Success(REPORT), importer.uiState)
    }

    @Test
    fun `cancelled import discards late parser result and retains previous report`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var loaded = REPORT
        val importer = ComposeCompilerReportImport(
            parseReport = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                    REPORT.copy(sourceDirectory = "/replacement")
                }
            },
            loadReport = { loaded = it },
            workContext = EmptyCoroutineContext,
        )
        val job = launch { importer.importReport { Path.of("/replacement") } }
        entered.await()
        job.cancel()
        release.complete(Unit)
        job.join()

        assertTrue(job.isCancelled)
        assertSame(REPORT, loaded)
        assertEquals(ComposeCompilerReportImportState.Idle, importer.uiState)
    }

    @Test
    fun `cancellation and fatal errors escape instead of becoming import failures`() {
        for (failure in listOf(CancellationException("cancel import"), OutOfMemoryError("synthetic failure"))) {
            var loaded = false
            val importer = ComposeCompilerReportImport(
                parseReport = { throw failure },
                loadReport = { loaded = true },
                workContext = EmptyCoroutineContext,
            )
            val thrown = assertThrows(failure.javaClass) {
                runBlocking { importer.importReport { Path.of(REPORT.sourceDirectory) } }
            }

            // Coroutine stack-trace recovery may rethrow a copy, so compare type and message.
            assertEquals(failure.javaClass, thrown.javaClass)
            assertEquals(failure.message, thrown.message)
            assertFalse(loaded)
            assertEquals(ComposeCompilerReportImportState.Idle, importer.uiState)
        }
    }

    @Test
    fun `localized success includes counts full paths and verbatim parser warnings`() {
        for (language in listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE)) {
            val report = REPORT.copy(warnings = listOf("Unable to parse app-classes.txt: permission denied"))
            val message = composeCompilerReportImportMessage(report, language)
            assertTrue(message.contains(localizedStringResource(
                Res.string.compose_reports_import_succeeded,
                language,
                3,
                2,
                1,
                REPORT.sourceDirectory,
            )))
            REPORT.files.forEach { assertTrue(message.contains(it)) }
            assertTrue(message.contains(report.warnings.single()))
            assertFalse(composeCompilerReportImportMessage(REPORT, language).contains(report.warnings.single()))
        }
    }

    private companion object {
        val REPORT = ComposeCompilerReport(
            sourceDirectory = "/reports/compose_compiler",
            files = listOf("app-composables.txt", "app-composables.csv", "app-classes.txt")
                .map { "/reports/compose_compiler/$it" },
            functions = listOf(ComposeCompilerFunction(name = "First"), ComposeCompilerFunction(name = "Second")),
            classes = listOf(ComposeCompilerClass(name = "State", stability = ComposeCompilerStability.STABLE)),
        )
    }
}
