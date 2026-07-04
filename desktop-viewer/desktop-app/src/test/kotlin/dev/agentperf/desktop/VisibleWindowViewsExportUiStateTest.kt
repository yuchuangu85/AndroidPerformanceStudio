package dev.agentperf.desktop

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class VisibleWindowViewsExportUiStateTest {
    @Test
    fun `export state reports success paths and failure message`() {
        val success = VisibleWindowViewsExportUiState.Success(Path.of("/tmp/output"))
        val failure = VisibleWindowViewsExportUiState.Failure("adb failed")

        assertEquals(Path.of("/tmp/output"), success.directory)
        assertEquals("adb failed", failure.message)
        assertSame(
            VisibleWindowViewsExportUiState.Exporting,
            VisibleWindowViewsExportUiState.Exporting,
        )
    }
}
