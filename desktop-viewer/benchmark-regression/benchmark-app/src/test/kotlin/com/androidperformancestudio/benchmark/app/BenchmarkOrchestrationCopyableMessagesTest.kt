package com.androidperformancestudio.benchmark.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class BenchmarkOrchestrationCopyableMessagesTest {
    @Test
    fun `plan failures running status and output use copyable text with existing output boundary`() {
        val dialog =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/benchmark/app/BenchmarkOrchestrationDialog.kt"),
            )
        listOf(
            "text = it.message(language)",
            "text = localizedStringResource(Res.string.orchestrate_running, language)",
            "text = it,",
            "text = output.takeLast(OUTPUT_TAIL_CHARACTERS)",
        ).forEach { argument ->
            assertTrue(Regex("CopyableText\\(\\s*" + Regex.escape(argument)).containsMatchIn(dialog), argument)
        }
        assertTrue(dialog.contains(".heightIn(max = 180.dp)"))
        assertTrue(dialog.contains(".horizontalScroll(rememberScrollState())"))
        assertTrue(Regex("\\bText\\(localizedStringResource\\(Res.string.orchestrate_start, language\\)\\)").containsMatchIn(dialog))
        assertTrue(Regex("\\bText\\(localizedStringResource\\(Res.string.orchestrate_cancel, language\\)\\)").containsMatchIn(dialog))
    }
}
