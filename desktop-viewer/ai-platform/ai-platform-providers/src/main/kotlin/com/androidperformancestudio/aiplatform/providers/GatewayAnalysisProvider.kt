package com.androidperformancestudio.aiplatform.providers

import com.androidperformancestudio.ai.AiAnalysisGateway
import com.androidperformancestudio.ai.AiRequestException
import com.androidperformancestudio.ai.AiRequestFailureKind
import com.androidperformancestudio.aiplatform.model.AiProviderDriver
import com.androidperformancestudio.aiplatform.model.ProviderAvailability
import com.androidperformancestudio.aiplatform.model.ProviderCapability
import com.androidperformancestudio.aiplatform.model.ProviderDescriptor
import com.androidperformancestudio.aiplatform.model.ProviderEvent
import com.androidperformancestudio.aiplatform.model.ProviderRunRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

/** Adapts the existing gateway. Credentials remain inside the injected gateway, never in events. */
public class GatewayAnalysisProvider(
    providerId: String,
    private val model: String,
    private val gateway: AiAnalysisGateway,
    private val availability: suspend () -> ProviderAvailability = { ProviderAvailability.Available("ai-core-v1") },
    localOnly: Boolean = false,
) : AiProviderDriver {
    init {
        require(model.isNotBlank() && model.length <= 128)
    }

    override val descriptor: ProviderDescriptor =
        ProviderDescriptor(
            providerId,
            if (localOnly) {
                setOf(ProviderCapability.STRUCTURED_ANALYSIS, ProviderCapability.LOCAL_ONLY)
            } else {
                setOf(ProviderCapability.STRUCTURED_ANALYSIS)
            },
        )

    override suspend fun discover(): ProviderAvailability = availability()

    override fun run(request: ProviderRunRequest): Flow<ProviderEvent> =
        flow {
            val checked = request.snapshot()
            validateRequest(checked, descriptor)
            checkProvider(checked.model == model, "E_PROVIDER_MODEL")
            val completed =
                withTimeoutOrNull(checked.budget.timeoutMillis) {
                    emit(ProviderEvent.Started)
                    val result = validateResult(checked, gateway.analyze(checked.analysis))
                    emit(ProviderEvent.StructuredResult(result))
                    emit(ProviderEvent.Completed)
                    true
                }
            if (completed == null) {
                currentCoroutineContext().ensureActive()
                emit(ProviderEvent.Failure("E_PROVIDER_TIMEOUT", retryable = true))
            }
        }.catch { failure -> emitSanitizedFailure(failure) }
}

internal suspend fun kotlinx.coroutines.flow.FlowCollector<ProviderEvent>.emitSanitizedFailure(failure: Throwable) {
    currentCoroutineContext().ensureActive()
    when (failure) {
        is CancellationException -> throw failure
        is ProviderValidationFailure -> emit(ProviderEvent.Failure(failure.code, retryable = false))
        is AiRequestException ->
            when (failure.kind) {
                AiRequestFailureKind.AUTHENTICATION -> emit(ProviderEvent.Failure("E_PROVIDER_AUTHENTICATION", retryable = false))
                AiRequestFailureKind.RATE_LIMIT -> emit(ProviderEvent.Failure("E_PROVIDER_RATE_LIMIT", retryable = true))
                AiRequestFailureKind.TIMEOUT -> emit(ProviderEvent.Failure("E_PROVIDER_TIMEOUT", retryable = true))
                AiRequestFailureKind.NETWORK -> emit(ProviderEvent.Failure("E_PROVIDER_NETWORK", retryable = true))
                AiRequestFailureKind.HTTP -> emit(ProviderEvent.Failure("E_PROVIDER_HTTP", retryable = false))
            }
        is Exception ->
            emit(
                ProviderEvent.Failure(
                    if (StructuredAnalysisCodec.errorCode(failure) ==
                        "E_PROVIDER_CONNECTION_CHANGED"
                    ) {
                        "E_PROVIDER_CONNECTION_CHANGED"
                    } else {
                        "E_PROVIDER_FAILURE"
                    },
                    retryable = false,
                ),
            )
        else -> throw failure
    }
}
