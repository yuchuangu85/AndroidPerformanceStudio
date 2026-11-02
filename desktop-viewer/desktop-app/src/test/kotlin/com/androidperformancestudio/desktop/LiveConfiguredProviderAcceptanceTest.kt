package com.androidperformancestudio.desktop

import com.androidperformancestudio.ai.AiHttpTransport
import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.ai.AnalysisScope
import com.androidperformancestudio.ai.AnalysisScopeKind
import com.androidperformancestudio.ai.AnalysisSessionId
import com.androidperformancestudio.ai.JdkAiHttpTransport
import com.androidperformancestudio.ai.MacOsKeychainCredentialStore
import com.androidperformancestudio.ai.OpenAiAnalysisGateway
import com.androidperformancestudio.ai.OpenAiResponsesClient
import com.androidperformancestudio.ai.PerformanceEvidence
import com.androidperformancestudio.ai.ProfilerKind
import com.androidperformancestudio.aiplatform.app.AiPlatformRuntime
import com.androidperformancestudio.aiplatform.model.Permission
import com.androidperformancestudio.aiplatform.model.RunStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.Preferences

/** Explicit developer acceptance only; never reads credentials or makes requests in normal tests. */
class LiveConfiguredProviderAcceptanceTest {
    @Test
    fun `explicit live acceptance uses only the configured credential and synthetic evidence`() = runBlocking {
        assumeTrue(System.getenv("APS_RUN_LIVE_CONFIGURED_PROVIDER") == "true", "Real provider acceptance is opt-in")
        val provider = System.getenv("APS_LIVE_CONFIGURED_PROVIDER")
        check(provider in setOf("openai", "codex")) { "E_LIVE_PROVIDER_REQUIRED" }
        check(listOf("jdk.httpclient.HttpClient.log", "javax.net.debug").all { System.getProperty(it).isNullOrBlank() }) { "E_LIVE_UNSAFE_DIAGNOSTICS" }
        check(System.getProperty("os.name").contains("mac", true)) { "E_LIVE_CREDENTIAL_STORE_UNAVAILABLE" }
        val preferences = Preferences.userRoot().node("com/androidperformancestudio/ai")
        val endpoint = preferences.get("endpoint", OpenAiResponsesClient.DEFAULT_ENDPOINT)
        val uri = URI.create(endpoint)
        check(uri.scheme == "https" && uri.host == "api.openai.com" && uri.port == -1 &&
            uri.path == "/v1/responses" && uri.userInfo == null && uri.query == null && uri.fragment == null
        ) { "E_LIVE_ENDPOINT_NOT_QUALIFIED" }
        val model = System.getenv("APS_LIVE_MODEL") ?: preferences.get("model", "")
        check(model.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) && !model.startsWith("sk-")) { "E_LIVE_MODEL_REQUIRED" }
        val root = Files.createTempDirectory("aps-live-configured-").toRealPath()
        val sent = AtomicInteger()
        val httpStatus = AtomicInteger()
        var status = "FAILED_PRECONDITION"
        var code: String? = null
        try {
            val key = MacOsKeychainCredentialStore().read("openai:api-key")
            check(!key.isNullOrBlank()) { "E_LIVE_CREDENTIAL_REQUIRED" }
            status = "FAILED"
            val transport = JdkAiHttpTransport()
            AiPlatformRuntime.open(root.resolve("profile"), openAiAvailable = { true }, openAiGateway = { selectedModel ->
                OpenAiAnalysisGateway(OpenAiResponsesClient(key, selectedModel, endpoint, AiHttpTransport { request ->
                    val body = Json.parseToJsonElement(request.body).jsonObject
                    check(body.getValue("tool_choice").jsonPrimitive.content == "none") { "E_LIVE_TOOL_CONTRACT" }
                    check(body.getValue("tools") == JsonArray(emptyList())) { "E_LIVE_TOOL_CONTRACT" }
                    check(body.getValue("store").jsonPrimitive.content == "false") { "E_LIVE_PERSISTENCE_CONTRACT" }
                    check(sent.incrementAndGet() == 1) { "E_LIVE_RETRY_FORBIDDEN" }
                    transport.execute(request).also { response ->
                        httpStatus.set(response.statusCode)
                        if (response.statusCode in 200..299) {
                            val envelope = Json.parseToJsonElement(response.body).jsonObject
                            check(envelope["model"]?.jsonPrimitive?.content == selectedModel) { "E_LIVE_SERVED_MODEL_MISMATCH" }
                        }
                    }
                }, maxOutputTokens = 512))
            }).use { runtime ->
                val input = AnalysisRequest(
                    AnalysisSessionId.create(), ProfilerKind.SIMPLEPERF,
                    AnalysisScope(AnalysisScopeKind.CURRENT_SELECTION, "Synthetic acceptance fixture only, not a real performance report"),
                    listOf(PerformanceEvidence("synthetic-evidence", "simpleperf-hotspots",
                        "Synthetic selected work: 80 ms and 5 samples; no project data", """{"synthetic":true,"durationNs":80000000,"sampleCount":5}""")),
                    emptyList(), "live-acceptance-v1", "synthetic-only-v1",
                )
                val prepared = runtime.service.prepare(
                    "live-acceptance", input, "aps.simpleperf", checkNotNull(provider), model,
                    setOf(Permission.READ_EVIDENCE, Permission.PROVIDER_NETWORK),
                    "Analyze only this synthetic fixture. Return a short summary and at most one finding referencing synthetic-evidence. No tools or source context.",
                )
                check(sent.get() == 0) { "E_LIVE_IMPLICIT_REQUEST" }
                withTimeout(65_000) { runtime.service.approveAndRun(prepared.approval).join() }
                val terminal = runtime.service.awaitRun(prepared.approval.runId)
                code = terminal.errorCode
                check(terminal.status == RunStatus.SUCCEEDED) { "E_LIVE_PROVIDER_REJECTED" }
                val result = checkNotNull(terminal.result) { "E_LIVE_RESULT_MISSING" }
                check(result.sessionId == input.sessionId && result.model == model && result.summary.isNotBlank()) { "E_LIVE_RESULT_IDENTITY" }
                check(result.findings.all { finding ->
                    finding.performanceEvidenceIds.all { it == "synthetic-evidence" } && finding.sourceCandidateIds.isEmpty()
                }) { "E_LIVE_RESULT_REFERENCE" }
                check(sent.get() == 1) { "E_LIVE_REQUEST_COUNT" }
                status = "SUCCEEDED"
            }
        } catch (failure: Exception) {
            code = code ?: failure.message?.takeIf { it.matches(Regex("E_[A-Z0-9_]{1,80}")) } ?: "E_LIVE_ACCEPTANCE"
            throw IllegalStateException(code)
        } finally {
            val receipt = buildJsonObject {
                put("schemaVersion", 1)
                put("timestamp", Instant.now().toString())
                put("provider", provider)
                put("transport", "responses-api")
                put("model", model)
                put("endpointHost", uri.host)
                put("credentialReference", "macos-keychain:openai:api-key")
                put("syntheticEvidenceOnly", true)
                put("applicationRetries", 0)
                put("transportAttemptCount", sent.get())
                put("httpResponseReceived", httpStatus.get() > 0)
                if (httpStatus.get() > 0) put("httpStatus", httpStatus.get())
                put("maxOutputTokens", 512)
                put("status", status)
                code?.let { put("errorCode", it) }
            }
            val output = Path.of("build/reports/live-configured-provider/$provider.json")
            Files.createDirectories(output.parent)
            Files.writeString(output, receipt.toString())
            root.toFile().deleteRecursively()
        }
    }
}
