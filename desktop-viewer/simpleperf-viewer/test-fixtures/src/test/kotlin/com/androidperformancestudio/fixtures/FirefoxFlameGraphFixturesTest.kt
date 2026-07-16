package com.androidperformancestudio.fixtures

import com.androidperformancestudio.profileanalysis.CallStackTransform
import com.androidperformancestudio.storage.ProfileProjectionRequest
import com.androidperformancestudio.storage.SQLiteSampleStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FirefoxFlameGraphFixturesTest {
    @Test
    fun `compatibility matrix names every Firefox parity row and upstream baseline`() {
        val matrix = FirefoxFlameGraphFixtures.compatibilityMatrix()

        assertEquals("9dd90d380ee711f209c4dcd89beec244eb6d3654", FirefoxFlameGraphFixtures.UPSTREAM_BASELINE_COMMIT)
        assertEquals(
            listOf("mixed", "native", "managed", "kernel", "recursive", "source-less", "million-sample", "deep-stack"),
            matrix.map { it.id },
        )
        assertTrue(matrix.all { it.intentionalDifferences == listOf("Compose styling", "Android data terminology") })
    }

    @Test
    fun `compatibility session imports queries transforms and has source-less fallback artifacts`() {
        val session = FirefoxFlameGraphFixtures.writeCompatibilitySession(Files.createTempDirectory("firefox-fixture-"))

        SQLiteSampleStore.open(session.resolve("profile.sqlite")).use { store ->
            assertEquals(8L, store.sampleCount())
            assertEquals(
                listOf("renderFrame", "runLoop", "android.os.Handler.dispatchMessage"),
                store.topFunctions(limit = 3).map { it.symbolName },
            )
            val graph = store.projectCore(ProfileProjectionRequest()).flameGraph
            assertTrue(graph.callNodes.ids.isNotEmpty())
            val focused =
                store
                    .projectCore(
                        ProfileProjectionRequest(
                            callStackAnalysis =
                                graph.query.copy(
                                    transforms = listOf(CallStackTransform.CollapseResource("/system/lib64/libui.so")),
                                ),
                        ),
                    ).flameGraph
            assertTrue(focused.callNodes.ids.size <= graph.callNodes.ids.size)
        }
        assertTrue(session.resolve("symbols/system/lib64/libui.so").toFile().isFile)
        assertTrue(session.resolve("binary_cache/system/lib64/source-less.so").toFile().isFile)
    }
}
