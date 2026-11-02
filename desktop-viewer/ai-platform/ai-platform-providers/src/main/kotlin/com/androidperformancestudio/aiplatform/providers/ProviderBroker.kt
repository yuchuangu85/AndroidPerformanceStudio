package com.androidperformancestudio.aiplatform.providers

import com.androidperformancestudio.aiplatform.model.AiProviderDriver
import com.androidperformancestudio.aiplatform.model.ProviderAvailability
import com.androidperformancestudio.aiplatform.model.ProviderCapability
import com.androidperformancestudio.aiplatform.model.ProviderDescriptor
import com.androidperformancestudio.aiplatform.model.ProviderEvent
import com.androidperformancestudio.aiplatform.model.ProviderRunRequest
import com.androidperformancestudio.aiplatform.model.ProviderUnavailableCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

public class ProviderRegistry(
    drivers: List<AiProviderDriver>,
) {
    private data class Registration(
        val driver: AiProviderDriver,
        val descriptor: ProviderDescriptor,
    )

    private val registered: Map<String, Registration> =
        drivers
            .map { driver ->
                Registration(driver, driver.descriptor.let { it.copy(capabilities = it.capabilities.toSet()) })
            }.associateBy { it.descriptor.id }
            .also {
                require(it.size == drivers.size) { "E_DUPLICATE_PROVIDER" }
            }

    public fun descriptor(providerId: String): ProviderDescriptor? =
        registered[providerId]?.descriptor?.let { it.copy(capabilities = it.capabilities.toSet()) }

    public suspend fun discover(providerId: String): ProviderAvailability =
        registered[providerId]?.let { registration ->
            try {
                registration.driver.discover()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                ProviderAvailability.Unavailable(ProviderUnavailableCode.MALFORMED_CONFIG)
            }
        } ?: ProviderAvailability.Unavailable(ProviderUnavailableCode.MISSING_RUNTIME)

    internal fun driver(providerId: String): AiProviderDriver? = registered[providerId]?.driver
}

/** No provider fallback, tool execution, implicit approvals or provider-controlled selection. */
public class ProviderBroker(
    private val registry: ProviderRegistry,
) {
    public fun run(
        request: ProviderRunRequest,
        allowedProviders: Set<String>,
        requiredCapabilities: Set<ProviderCapability> = setOf(ProviderCapability.STRUCTURED_ANALYSIS),
    ): Flow<ProviderEvent> =
        flow {
            val checked = request.snapshot()
            val allowed = allowedProviders.toSet()
            val required = requiredCapabilities.toSet() + ProviderCapability.STRUCTURED_ANALYSIS
            checkProvider(checked.providerId in allowed, "E_PROVIDER_NOT_ALLOWED")
            val driver = registry.driver(checked.providerId) ?: throw ProviderValidationFailure("E_PROVIDER_UNREGISTERED")
            val descriptor = registry.descriptor(checked.providerId) ?: throw ProviderValidationFailure("E_PROVIDER_UNREGISTERED")
            checkProvider(descriptor.capabilities.containsAll(required), "E_PROVIDER_CAPABILITY")
            validateRequest(checked, descriptor)
            val completed =
                withTimeoutOrNull(checked.budget.timeoutMillis) {
                    val available = registry.discover(checked.providerId)
                    if (available is ProviderAvailability.Unavailable) {
                        throw ProviderValidationFailure("E_PROVIDER_${available.code.name}")
                    }
                    var started = false
                    var resultReceived = false
                    var outputBytes = 0L
                    var eventCount = 0
                    try {
                        driver.run(checked).collect { event ->
                            eventCount++
                            checkProvider(eventCount <= MAX_PROVIDER_EVENTS, "E_PROVIDER_EVENT_LIMIT")
                            when (event) {
                                ProviderEvent.Started -> {
                                    checkProvider(!started, "E_PROVIDER_PROTOCOL")
                                    started = true
                                    emit(event)
                                }
                                is ProviderEvent.StructuredResult -> {
                                    checkProvider(started && !resultReceived, "E_PROVIDER_PROTOCOL")
                                    val result = validateResult(checked, event.result)
                                    outputBytes += result.encodedSize()
                                    checkProvider(outputBytes <= checked.budget.maxOutputBytes, "E_OUTPUT_LIMIT")
                                    resultReceived = true
                                    emit(ProviderEvent.StructuredResult(result))
                                }
                                is ProviderEvent.TextDelta -> {
                                    checkProvider(started && !resultReceived, "E_PROVIDER_PROTOCOL")
                                    checkProvider(ProviderCapability.STREAMING in descriptor.capabilities, "E_PROVIDER_CAPABILITY")
                                    checkProvider(event.text.length <= checked.budget.maxOutputBytes, "E_OUTPUT_LIMIT")
                                    outputBytes += event.text.toByteArray(Charsets.UTF_8).size
                                    checkProvider(outputBytes <= checked.budget.maxOutputBytes, "E_OUTPUT_LIMIT")
                                    emit(event)
                                }
                                is ProviderEvent.Usage -> {
                                    checkProvider(started && event.inputTokens >= 0 && event.outputTokens >= 0, "E_PROVIDER_PROTOCOL")
                                    emit(event)
                                }
                                is ProviderEvent.Failure -> {
                                    val code = sanitizeFailureCode(event.code)
                                    emit(ProviderEvent.Failure(code, event.retryable))
                                    throw TerminalEventReceived()
                                }
                                ProviderEvent.Cancelled -> {
                                    emit(event)
                                    throw TerminalEventReceived()
                                }
                                ProviderEvent.Completed -> {
                                    checkProvider(started && resultReceived, "E_PROVIDER_PROTOCOL")
                                    emit(event)
                                    throw TerminalEventReceived()
                                }
                                is ProviderEvent.ToolCall -> throw ProviderValidationFailure("E_TOOL_UNSUPPORTED")
                                is ProviderEvent.ApprovalRequired -> throw ProviderValidationFailure("E_APPROVAL_UNSUPPORTED")
                            }
                        }
                        throw ProviderValidationFailure("E_PROVIDER_INCOMPLETE")
                    } catch (_: TerminalEventReceived) {
                        // Stop collecting after the first terminal event; late driver emissions cannot escape.
                    }
                    true
                }
            if (completed == null) {
                currentCoroutineContext().ensureActive()
                emit(ProviderEvent.Failure("E_PROVIDER_TIMEOUT", retryable = true))
            }
        }.catch { failure -> emitSanitizedFailure(failure) }
}

private class TerminalEventReceived : CancellationException()

/** Byte budgets do not bound empty deltas/usage events. */
internal const val MAX_PROVIDER_EVENTS: Int = 1024

private fun sanitizeFailureCode(code: String): String = code.takeIf { it in SAFE_FAILURE_CODES } ?: "E_PROVIDER_FAILURE"

private val SAFE_FAILURE_CODES =
    setOf(
        "E_PROVIDER_FAILURE",
        "E_PROVIDER_CONNECTION_CHANGED",
        "E_PROVIDER_TIMEOUT",
        "E_PROVIDER_AUTHENTICATION",
        "E_PROVIDER_RATE_LIMIT",
        "E_PROVIDER_NETWORK",
        "E_PROVIDER_HTTP",
        "E_PROVIDER_MODEL",
        "E_PROVIDER_MISMATCH",
        "E_PROVIDER_CREDENTIAL",
        "E_EVIDENCE_PERMISSION",
        "E_QUESTION_LIMIT",
        "E_PERMISSION_DENIED",
        "E_SOURCE_PERMISSION",
        "E_SOURCE_SNIPPET_PERMISSION",
        "E_NETWORK_PERMISSION",
        "E_INPUT_LIMIT",
        "E_INPUT_PAYLOAD",
        "E_OUTPUT_LIMIT",
        "E_RESULT_SESSION",
        "E_RESULT_MODEL",
        "E_RESULT_LIMIT",
        "E_FINDING_ID",
        "E_FINDING_CONFIDENCE",
        "E_RESULT_REFERENCES",
        "E_RESULT_EVIDENCE",
        "E_RESULT_CANDIDATE",
        "E_SESSION_ID",
        "E_POLICY_VERSION",
        "E_EVIDENCE_LIMIT",
        "E_EVIDENCE_ID",
        "E_CANDIDATE_LIMIT",
        "E_CANDIDATE_ID",
        "E_PROVIDER_EVENT_LIMIT",
        "E_PROVIDER_PROCESS_EXIT",
        "E_PROVIDER_PROCESS_START",
        "E_PROVIDER_PROCESS_IO",
        "E_PROVIDER_PROTOCOL",
        "E_PROVIDER_STDERR_LIMIT",
        "E_PROVIDER_MISSING_RUNTIME",
        "E_PROVIDER_UNSUPPORTED_CAPABILITY",
        "E_PROVIDER_INCOMPATIBLE_VERSION",
        "E_PROVIDER_MALFORMED_CONFIG",
        "E_PROVIDER_MISSING_CREDENTIAL",
        "E_PROVIDER_PERMISSION_DENIED",
    )
