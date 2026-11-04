package com.androidperformancestudio.aiplatform.provider.codex

import com.androidperformancestudio.aiplatform.model.AiProviderDriver
import com.androidperformancestudio.aiplatform.model.ProviderAvailability
import com.androidperformancestudio.aiplatform.model.ProviderDescriptor
import com.androidperformancestudio.aiplatform.model.ProviderEvent
import com.androidperformancestudio.aiplatform.model.ProviderRunRequest
import com.androidperformancestudio.aiplatform.model.ProviderUnavailableCode
import com.androidperformancestudio.aiplatform.providers.ControlledProviderProcess
import com.androidperformancestudio.aiplatform.providers.LocalProviderConfigResolver
import com.androidperformancestudio.aiplatform.providers.ProviderProcessRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.nio.file.Files
import java.nio.file.Path

/**
 * Fail-closed adapter discovery, not an unrestricted `codex exec` bridge.
 * The verified 0.166 CLI offers sandbox policies, not a no-tools guarantee. No prompt is launched.
 */
public class CodexCliProvider(
    private val executable: Path,
    private val resolver: LocalProviderConfigResolver = LocalProviderConfigResolver(),
    private val process: ControlledProviderProcess = ControlledProviderProcess(),
) : AiProviderDriver {
    override val descriptor: ProviderDescriptor = ProviderDescriptor("codex", emptySet())

    override suspend fun discover(): ProviderAvailability {
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) return unavailable(ProviderUnavailableCode.MISSING_RUNTIME)
        val work = Files.createTempDirectory("aps-codex-probe-")
        try {
            val version =
                process.execute(
                    ProviderProcessRequest(
                        executable,
                        listOf("--version"),
                        workDirectory = work,
                        timeoutMillis = 5000,
                        maxOutputBytes = 4096,
                    ),
                )
            if (version.exitCode != 0 || !version.stdout.trim().matches(Regex("codex-cli [0-9]+\\.[0-9]+\\.[0-9]+"))) {
                return unavailable(ProviderUnavailableCode.INCOMPATIBLE_VERSION)
            }
            // Resolve metadata but never open auth.json or run a model in order to test credentials.
            resolver.resolve("codex")
            return unavailable(ProviderUnavailableCode.UNSUPPORTED_CAPABILITY)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            return unavailable(ProviderUnavailableCode.INCOMPATIBLE_VERSION)
        } finally {
            Files.deleteIfExists(work)
        }
    }

    override fun run(request: ProviderRunRequest): Flow<ProviderEvent> =
        flow {
            emit(ProviderEvent.Failure("E_PROVIDER_UNSUPPORTED_CAPABILITY", retryable = false))
        }

    private fun unavailable(code: ProviderUnavailableCode): ProviderAvailability = ProviderAvailability.Unavailable(code)
}
