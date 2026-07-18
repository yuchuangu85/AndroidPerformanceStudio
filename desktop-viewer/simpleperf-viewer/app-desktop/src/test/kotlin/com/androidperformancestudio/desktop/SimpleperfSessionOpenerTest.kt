package com.androidperformancestudio.desktop

import com.androidperformancestudio.presentation.SimpleperfEngine
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class SimpleperfSessionOpenerTest {
    @Test
    fun `local engine opens the session in Android Performance Studio`() =
        runTest {
            val opened = mutableListOf<String>()
            val opener = sessionOpener(SimpleperfEngine.LOCAL, opened)

            opener.open(SESSION)

            assertEquals(listOf("local:$SESSION"), opened)
        }

    @Test
    fun `local Firefox engine opens the session in the bundled profiler`() =
        runTest {
            val opened = mutableListOf<String>()
            val opener = sessionOpener(SimpleperfEngine.FIREFOX_PROFILER_LOCAL, opened)

            opener.open(SESSION)

            assertEquals(listOf("firefox-local:$SESSION"), opened)
        }

    @Test
    fun `official Firefox engine opens the session on profiler firefox com`() =
        runTest {
            val opened = mutableListOf<String>()
            val opener = sessionOpener(SimpleperfEngine.FIREFOX_PROFILER, opened)

            opener.open(SESSION)

            assertEquals(listOf("firefox-official:$SESSION"), opened)
        }

    @Test
    fun `engine changes apply to later open actions`() =
        runTest {
            var engine = SimpleperfEngine.LOCAL
            val opened = mutableListOf<String>()
            val opener = sessionOpener({ engine }, opened)

            opener.open(SESSION)
            engine = SimpleperfEngine.FIREFOX_PROFILER
            opener.open(SESSION)

            assertEquals(
                listOf("local:$SESSION", "firefox-official:$SESSION"),
                opened,
            )
        }

    private fun sessionOpener(
        engine: SimpleperfEngine,
        opened: MutableList<String>,
    ): SimpleperfSessionOpener = sessionOpener({ engine }, opened)

    private fun sessionOpener(
        engine: () -> SimpleperfEngine,
        opened: MutableList<String>,
    ): SimpleperfSessionOpener =
        SimpleperfSessionOpener(
            selectedEngine = engine,
            openLocal = { session -> opened += "local:$session" },
            openLocalFirefoxProfiler = { session -> opened += "firefox-local:$session" },
            openOfficialFirefoxProfiler = { session -> opened += "firefox-official:$session" },
        )

    private companion object {
        val SESSION: Path = Path.of("profiles", "capture")
    }
}
