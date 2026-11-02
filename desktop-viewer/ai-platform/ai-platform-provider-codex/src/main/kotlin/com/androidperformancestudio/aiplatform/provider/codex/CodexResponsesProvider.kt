package com.androidperformancestudio.aiplatform.provider.codex

import com.androidperformancestudio.ai.AiAnalysisGateway
import com.androidperformancestudio.aiplatform.model.AiProviderDriver
import com.androidperformancestudio.aiplatform.model.ProviderAvailability
import com.androidperformancestudio.aiplatform.model.ProviderCapability
import com.androidperformancestudio.aiplatform.model.ProviderDescriptor
import com.androidperformancestudio.aiplatform.model.ProviderEvent
import com.androidperformancestudio.aiplatform.model.ProviderRunRequest
import com.androidperformancestudio.aiplatform.model.ProviderUnavailableCode
import com.androidperformancestudio.aiplatform.providers.GatewayAnalysisProvider
import com.androidperformancestudio.aiplatform.providers.StructuredAnalysisCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow

/** Configured Codex Responses connection, never a fallback to a tool-enabled CLI. */
public class CodexResponsesProvider(
    private val gateway: (String) -> AiAnalysisGateway,
    private val credentialAvailable: () -> Boolean,
    private val gatewayForRun: ((ProviderRunRequest) -> AiAnalysisGateway)? = null,
) : AiProviderDriver {
    override val descriptor: ProviderDescriptor = ProviderDescriptor("codex", setOf(ProviderCapability.STRUCTURED_ANALYSIS))

    override suspend fun discover(): ProviderAvailability =
        try {
            if (credentialAvailable()) {
                ProviderAvailability.Available("codex-responses-v1")
            } else {
                ProviderAvailability.Unavailable(ProviderUnavailableCode.MISSING_CREDENTIAL)
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            ProviderAvailability.Unavailable(ProviderUnavailableCode.PERMISSION_DENIED)
        }

    override fun run(request: ProviderRunRequest): Flow<ProviderEvent> =
        flow {
            StructuredAnalysisCodec.validateInput(request, descriptor)
            if (request.model.any(Char::isISOControl)) {
                emit(ProviderEvent.Failure("E_PROVIDER_MODEL", retryable = false))
                return@flow
            }
            val availability = discover()
            if (availability is ProviderAvailability.Unavailable) {
                emit(ProviderEvent.Failure("E_PROVIDER_${availability.code.name}", retryable = false))
                return@flow
            }
            val selectedGateway = if (gatewayForRun != null) gatewayForRun.invoke(request) else gateway(request.model)
            GatewayAnalysisProvider("codex", request.model, selectedGateway, { availability }).run(request).collect { emit(it) }
        }.catch { failure ->
            currentCoroutineContext().ensureActive()
            if (failure is CancellationException) throw failure
            emit(ProviderEvent.Failure(StructuredAnalysisCodec.errorCode(failure), retryable = false))
        }
}
