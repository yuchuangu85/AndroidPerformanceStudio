@file:Suppress("LongMethod", "MagicNumber", "MaxLineLength")

package com.androidperformancestudio.network.export

import com.androidperformancestudio.network.analysis.NetworkSummary
import com.androidperformancestudio.network.model.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

public class NetworkExporter {
    private val json = Json { prettyPrint = true }

    public fun writeJson(result: NetworkCaptureResult, summary: NetworkSummary, output: Path) = write(
        output,
        json.encodeToString(
            buildJsonObject {
                put("schemaVersion", 1)
                put("sessionId", result.session.id)
                put("startedAt", result.session.startedAt.toString())
                put("status", result.session.status.name)
                put("source", result.session.coverage.instrumentationMode.name)
                put("droppedEvents", result.session.coverage.droppedEvents)
                put(
                    "summary",
                    buildJsonObject {
                        put("callCount", summary.callCount)
                        put("failureCount", summary.failureCount)
                        summary.medianDurationMs?.let { put("medianDurationMs", it) }
                        summary.p95DurationMs?.let { put("p95DurationMs", it) }
                        put("requestBytes", summary.totalRequestBytes)
                        put("responseBytes", summary.totalResponseBytes)
                    },
                )
                put("calls", buildJsonArray { result.calls.forEach { call -> add(callJson(call)) } })
            },
        ),
    )

    public fun writeCsv(result: NetworkCaptureResult, output: Path) = write(
        output,
        buildString {
            appendLine("call_id,method,url,outcome,status,protocol,duration_ms,request_bytes,response_bytes")
            result.calls.forEach { call ->
                val exchange = call.exchanges.lastOrNull()
                appendLine(listOf(call.callId, call.method, call.redactedUrl, call.outcome, exchange?.statusCode, exchange?.protocol, call.durationNs?.div(1_000_000.0), exchange?.requestBytes, exchange?.responseBytes).joinToString(",") { csv(it?.toString().orEmpty()) })
            }
        },
    )

    public fun writePartialHar(result: NetworkCaptureResult, output: Path) = write(
        output,
        json.encodeToString(
            buildJsonObject {
                put(
                    "log",
                    buildJsonObject {
                        put("version", "1.2")
                        put(
                            "creator",
                            buildJsonObject {
                                put("name", "AndroidPerformanceStudio")
                                put("version", "1")
                            },
                        )
                        put(
                            "entries",
                            buildJsonArray {
                                result.calls.forEach { call ->
                                    val exchange = call.exchanges.lastOrNull()
                                    add(
                                        buildJsonObject {
                                            put("startedDateTime", result.session.startedAt.plus(call.startedNs, ChronoUnit.NANOS).toString())
                                            put("time", call.durationNs?.div(1_000_000.0) ?: -1.0)
                                            put(
                                                "request",
                                                buildJsonObject {
                                                    put("method", call.method)
                                                    put("url", call.redactedUrl)
                                                    put("httpVersion", exchange?.protocol ?: "")
                                                    put("headers", headers(exchange?.requestHeaders.orEmpty()))
                                                    put("queryString", buildJsonArray { })
                                                    put("headersSize", -1)
                                                    put("bodySize", exchange?.requestBytes ?: -1)
                                                },
                                            )
                                            put(
                                                "response",
                                                buildJsonObject {
                                                    put("status", exchange?.statusCode ?: 0)
                                                    put("statusText", "")
                                                    put("httpVersion", exchange?.protocol ?: "")
                                                    put("headers", headers(exchange?.responseHeaders.orEmpty()))
                                                    put(
                                                        "content",
                                                        buildJsonObject {
                                                            put("size", exchange?.responseBytes ?: -1)
                                                            put("mimeType", "")
                                                        },
                                                    )
                                                    put("redirectURL", "")
                                                    put("headersSize", -1)
                                                    put("bodySize", exchange?.responseBytes ?: -1)
                                                },
                                            )
                                            put("cache", buildJsonObject { })
                                            put("timings", timings(exchange?.phases.orEmpty()))
                                            put(
                                                "_aps",
                                                buildJsonObject {
                                                    put("partial", true)
                                                    put("source", call.source.name)
                                                    put("bodiesCaptured", false)
                                                },
                                            )
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            },
        ),
    )

    public fun writeRawBundle(result: NetworkCaptureResult, summary: NetworkSummary, output: Path) {
        output.toAbsolutePath().parent?.let(Files::createDirectories)
        val temp = Files.createTempFile("aps-network", ".json")
        writeJson(result, summary, temp)
        ZipOutputStream(Files.newOutputStream(output)).use { zip ->
            zip.putNextEntry(ZipEntry("network-session.json"))
            Files.copy(temp, zip)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("manifest.txt"))
            zip.write("schema=1\nsession=${result.session.id}\nsource=${result.session.coverage.instrumentationMode}\ndropped=${result.session.coverage.droppedEvents}\nprivacy=request_and_response_bodies_not_captured\n".toByteArray())
            zip.closeEntry()
        }
        Files.deleteIfExists(temp)
    }

    private fun callJson(call: HttpCall): JsonObject = buildJsonObject {
        put("id", call.callId)
        put("method", call.method)
        put("url", call.redactedUrl)
        put("startedNs", call.startedNs)
        call.endedNs?.let { put("endedNs", it) }
        put("outcome", call.outcome.name)
        put("source", call.source.name)
        put(
            "exchanges",
            buildJsonArray {
                call.exchanges.forEach { exchange ->
                    add(
                        buildJsonObject {
                            put("index", exchange.exchangeIndex)
                            exchange.statusCode?.let { put("status", it) }
                            exchange.protocol?.let { put("protocol", it) }
                            exchange.requestBytes?.let { put("requestBytes", it) }
                            exchange.responseBytes?.let { put("responseBytes", it) }
                            put(
                                "phases",
                                buildJsonArray {
                                    exchange.phases.forEach { phase ->
                                        add(
                                            buildJsonObject {
                                                put("kind", phase.kind.name)
                                                put("startNs", phase.startNs)
                                                phase.endNs?.let { put("endNs", it) }
                                                put("confidence", phase.confidence.name)
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
    }

    private fun headers(values: Map<String, String>): JsonArray = buildJsonArray {
        values.forEach { (name, value) ->
            add(
                buildJsonObject {
                    put("name", name)
                    put("value", value)
                },
            )
        }
    }

    private fun timings(phases: List<NetworkPhase>): JsonObject = buildJsonObject {
        fun value(kind: NetworkPhaseKind): Double = phases.firstOrNull { it.kind == kind }?.durationNs?.div(1_000_000.0) ?: -1.0
        put("blocked", value(NetworkPhaseKind.DISPATCHER_QUEUE))
        put("dns", value(NetworkPhaseKind.DNS))
        put("connect", value(NetworkPhaseKind.CONNECT))
        put("ssl", value(NetworkPhaseKind.TLS))
        put("send", value(NetworkPhaseKind.REQUEST_BODY))
        put("wait", value(NetworkPhaseKind.SERVER_WAIT))
        put("receive", value(NetworkPhaseKind.RESPONSE_BODY))
    }

    private fun write(path: Path, content: String) {
        path.toAbsolutePath().parent?.let(Files::createDirectories)
        Files.writeString(path, content)
    }

    private fun csv(value: String): String = "\"${value.replace("\"","\"\"")}\""
}
