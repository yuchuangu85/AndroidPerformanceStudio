package com.androidperformancestudio.aiplatform.provider.codex

import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.ai.AnalysisScope
import com.androidperformancestudio.ai.AnalysisScopeKind
import com.androidperformancestudio.ai.AnalysisSessionId
import com.androidperformancestudio.ai.PerformanceEvidence
import com.androidperformancestudio.ai.ProfilerKind
import com.androidperformancestudio.aiplatform.model.Permission
import com.androidperformancestudio.aiplatform.model.ProviderAvailability
import com.androidperformancestudio.aiplatform.model.ProviderEvent
import com.androidperformancestudio.aiplatform.model.ProviderRunRequest
import com.androidperformancestudio.aiplatform.model.ProviderUnavailableCode
import com.androidperformancestudio.aiplatform.providers.LocalProviderConfigResolver
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CodexCliProviderTest {
    @Test
    fun `unverified no-tools capability cannot launch model even with installed runtime`() =
        runBlocking {
            if (!Files.isExecutable(Path.of("/bin/sh"))) return@runBlocking
            val work = Files.createTempDirectory("aps-codex-test-")
            val executable = work.resolve("codex")
            Files.writeString(
                executable,
                "#!/bin/sh\nif [ \"\$1\" = --version ]; then printf 'codex-cli 0.166.0'; exit 0; fi\necho MODEL_REQUEST > '${work.resolve(
                    "unsafe",
                )}'\n",
            )
            Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
            try {
                val provider = CodexCliProvider(executable, LocalProviderConfigResolver(work.resolve("home"), emptySet()))
                assertEquals(ProviderAvailability.Unavailable(ProviderUnavailableCode.UNSUPPORTED_CAPABILITY), provider.discover())
                val request =
                    ProviderRunRequest(
                        "run",
                        1,
                        "simpleperf",
                        "codex",
                        "test-model",
                        AnalysisRequest(
                            AnalysisSessionId("session"),
                            ProfilerKind.SIMPLEPERF,
                            AnalysisScope(AnalysisScopeKind.CURRENT_SELECTION, "node"),
                            listOf(PerformanceEvidence("evidence-1", "node", "bounded", "{}")),
                            emptyList(),
                            "v1",
                            "v1",
                        ),
                        setOf(Permission.READ_EVIDENCE, Permission.PROVIDER_NETWORK),
                    )
                assertEquals(listOf(ProviderEvent.Failure("E_PROVIDER_UNSUPPORTED_CAPABILITY", false)), provider.run(request).toList())
                assertFalse(Files.exists(work.resolve("unsafe")))
            } finally {
                work.toFile().deleteRecursively()
            }
        }

    @Test
    fun `missing runtime is visible and does not fall back`() =
        runBlocking {
            assertEquals(
                ProviderAvailability.Unavailable(ProviderUnavailableCode.MISSING_RUNTIME),
                CodexCliProvider(Path.of("/definitely-missing-codex")).discover(),
            )
        }
}
