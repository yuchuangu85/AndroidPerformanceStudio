@file:Suppress("LongMethod", "MagicNumber", "MaxLineLength", "TooManyFunctions")

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

    public fun writeJson(result: NetworkCaptureResult, summary: NetworkSummary, output: Path) = write(output, json.encodeToString(reportJson(result, summary)))

    public fun writeCsv(result: NetworkCaptureResult, output: Path) = write(
        output,
        buildString {
            appendLine("call_id,instrumentation_id,method,url,outcome,status,protocol,connection_use,duration_ms,request_bytes,response_bytes")
            result.calls.forEach { call ->
                val exchange = call.exchanges.lastOrNull()
                appendLine(listOf(call.callId, call.instrumentationId, call.method, call.redactedUrl, call.outcome, exchange?.statusCode, exchange?.protocol, exchange?.connectionUse, call.durationNs?.div(1_000_000.0), exchange?.requestBytes, exchange?.responseBytes).joinToString(",") { csv(it?.toString().orEmpty()) })
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
                                                            put("size", exchange?.decodedResponseBytes ?: exchange?.responseBytes ?: -1)
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
                                                    put("redactionPolicyVersion", result.session.redactionPolicyVersion)
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
        ZipOutputStream(Files.newOutputStream(output)).use { zip ->
            zip.putNextEntry(ZipEntry("network-session.json"))
            zip.write(json.encodeToString(reportJson(result, summary)).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("raw-events.json"))
            zip.write(json.encodeToString(buildJsonArray { result.rawEvents.forEach { add(rawEventJson(it)) } }).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("manifest.txt"))
            zip.write("schema=2\nsession=${result.session.id}\nsource=${result.session.coverage.instrumentationMode}\nraw_events=${result.rawEvents.size}\nredaction_policy=${result.session.redactionPolicyVersion}\nprivacy=minimized_network_evidence\n".toByteArray())
            zip.closeEntry()
        }
    }

    private fun reportJson(result: NetworkCaptureResult, summary: NetworkSummary): JsonObject = buildJsonObject {
        put("schemaVersion", 2)
        put("session", sessionJson(result.session))
        put("summary", summaryJson(summary))
        put("calls", buildJsonArray { result.calls.forEach { add(callJson(it)) } })
        put("rawEvents", buildJsonArray { result.rawEvents.forEach { add(rawEventJson(it)) } })
    }

    private fun sessionJson(session: NetworkSession): JsonObject = buildJsonObject {
        put("id", session.id)
        session.deviceSerial?.let { put("deviceLocalId", networkDeviceLocalId(it)) }
        session.packageName?.let { put("packageName", it) }
        put("startedAt", session.startedAt.toString())
        session.endedAt?.let { put("endedAt", it.toString()) }
        put("status", session.status.name)
        put("sourceTimeDomain", session.sourceTimeDomain.name)
        put("sourceTimeOriginNs", session.sourceTimeOriginNs)
        put("redactionPolicyVersion", session.redactionPolicyVersion)
        session.sourceFormatVersion?.let { put("sourceFormatVersion", it) }
        session.sourceProducer?.let { put("sourceProducer", it) }
        session.sourceFingerprint?.let { put("sourceFingerprint", it) }
        put(
            "coverage",
            buildJsonObject {
                put("processIds", strings(session.coverage.processIds.map(Int::toString)))
                put("observedLibraries", strings(session.coverage.observedLibraries))
                put("observedInstrumentationIds", strings(session.coverage.observedInstrumentationIds))
                put("instrumentationMode", session.coverage.instrumentationMode.name)
                put("supportedEventKinds", strings(session.coverage.supportedEventKinds))
                put("knownLimitations", strings(session.coverage.knownLimitations))
                session.coverage.windowStartedNs?.let { put("windowStartedNs", it) }
                session.coverage.windowEndedNs?.let { put("windowEndedNs", it) }
            },
        )
        put(
            "completeness",
            buildJsonObject {
                put("status", session.completeness.status.name)
                put("droppedEvents", session.completeness.droppedEvents)
                put("sequenceGaps", session.completeness.sequenceGaps)
                put("unpairedEvents", session.completeness.unpairedEvents)
                put("skippedRecords", session.completeness.skippedRecords)
            },
        )
        session.clockMapping?.let { mapping ->
            put(
                "clockMapping",
                buildJsonObject {
                    put("sourceMonotonicReferenceNs", mapping.sourceMonotonicReferenceNs)
                    put("hostMonotonicReferenceNs", mapping.hostMonotonicReferenceNs)
                    put("wallClockReference", mapping.wallClockReference.toString())
                    put("errorBoundNs", mapping.errorBoundNs)
                },
            )
        }
        put("warnings", strings(session.warnings))
    }

    private fun summaryJson(summary: NetworkSummary): JsonObject = buildJsonObject {
        put("callCount", summary.callCount)
        put("completedCount", summary.completedCount)
        put("failureCount", summary.failureCount)
        put("cancelledCount", summary.cancelledCount)
        put("incompleteCount", summary.incompleteCount)
        put("httpStatusFamilies", buildJsonObject { summary.httpStatusFamilies.forEach { (key, value) -> put(key, value) } })
        put("requestBytes", summary.totalRequestBytes)
        put("responseBytes", summary.totalResponseBytes)
        put("decodedResponseBytes", summary.totalDecodedResponseBytes)
        summary.medianDurationMs?.let { put("medianDurationMs", it) }
        summary.p90DurationMs?.let { put("p90DurationMs", it) }
        summary.p95DurationMs?.let { put("p95DurationMs", it) }
        summary.cacheHitRate?.let { put("cacheHitRate", it) }
        put(
            "connectionReuse",
            buildJsonObject {
                put("new", summary.connectionReuse.newExchangeCount)
                put("reused", summary.connectionReuse.reusedExchangeCount)
                put("unknown", summary.connectionReuse.unknownExchangeCount)
                summary.connectionReuse.reuseRateAmongKnown?.let { put("rateAmongKnown", it) }
            },
        )
        put(
            "phaseSummaries",
            buildJsonArray {
                summary.phaseSummaries.forEach { phase ->
                    add(
                        buildJsonObject {
                            put("source", phase.source.name)
                            put("kind", phase.kind.name)
                            put("sampleCount", phase.sampleCount)
                            put("missingCount", phase.missingCount)
                            phase.medianDurationMs?.let { put("medianDurationMs", it) }
                            phase.p95DurationMs?.let { put("p95DurationMs", it) }
                        },
                    )
                }
            },
        )
        put("missingTimingCount", summary.missingTimingCount)
    }

    private fun callJson(call: HttpCall): JsonObject = buildJsonObject {
        put("id", call.callId)
        call.instrumentationId?.let { put("instrumentationId", it) }
        put("method", call.method)
        put("url", call.redactedUrl)
        put("startedNs", call.startedNs)
        call.endedNs?.let { put("endedNs", it) }
        put("outcome", call.outcome.name)
        put("source", call.source.name)
        put("exchanges", buildJsonArray { call.exchanges.forEach { add(exchangeJson(it)) } })
    }

    private fun exchangeJson(exchange: HttpExchange): JsonObject = buildJsonObject {
        put("index", exchange.exchangeIndex)
        exchange.connectionId?.let { put("connectionId", it) }
        put("connectionUse", exchange.connectionUse.name)
        exchange.statusCode?.let { put("status", it) }
        exchange.protocol?.let { put("protocol", it) }
        exchange.requestBytes?.let { put("requestBytes", it) }
        exchange.responseBytes?.let { put("responseBytes", it) }
        exchange.decodedResponseBytes?.let { put("decodedResponseBytes", it) }
        put("cacheDisposition", exchange.cacheDisposition.name)
        put("requestHeaders", map(exchange.requestHeaders))
        put("responseHeaders", map(exchange.responseHeaders))
        put("sourceAttributes", map(exchange.sourceAttributes))
        exchange.failure?.let { failure ->
            put(
                "failure",
                buildJsonObject {
                    put("type", failure.type)
                    failure.message?.let { put("message", it) }
                    failure.lastReliableEvent?.let { put("lastReliableEvent", it) }
                },
            )
        }
        exchange.tlsHandshake?.let { handshake ->
            put(
                "tlsHandshake",
                buildJsonObject {
                    handshake.tlsVersion?.let { put("tlsVersion", it) }
                    handshake.cipherSuite?.let { put("cipherSuite", it) }
                    put("confidence", handshake.confidence.name)
                },
            )
        }
        put(
            "phases",
            buildJsonArray {
                exchange.phases.forEach { phase ->
                    add(
                        buildJsonObject {
                            put("kind", phase.kind.name)
                            phase.startNs?.let { put("startNs", it) }
                            phase.endNs?.let { put("endNs", it) }
                            phase.reportedDurationNs?.let { put("reportedDurationNs", it) }
                            put("confidence", phase.confidence.name)
                            put("availability", phase.availability.name)
                            phase.parentKind?.let { put("parentKind", it.name) }
                        },
                    )
                }
            },
        )
    }

    private fun rawEventJson(event: RawNetworkEvent): JsonObject = buildJsonObject {
        put("sequence", event.sequence)
        put("callId", event.callId)
        event.instrumentationId?.let { put("instrumentationId", it) }
        put("kind", event.kind)
        put("sourceTimestampNs", event.sourceTimestampNs)
        put("relativeTimestampNs", event.relativeTimestampNs)
        event.method?.let { put("method", it) }
        event.redactedUrl?.let { put("url", it) }
        event.statusCode?.let { put("statusCode", it) }
        event.byteCount?.let { put("byteCount", it) }
        event.protocol?.let { put("protocol", it) }
        event.connectionId?.let { put("connectionId", it) }
        event.tlsVersion?.let { put("tlsVersion", it) }
        event.cipherSuite?.let { put("cipherSuite", it) }
        event.message?.let { put("message", it) }
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

    private fun strings(values: Iterable<String>): JsonArray = buildJsonArray { values.forEach(::add) }

    private fun map(values: Map<String, String>): JsonObject = buildJsonObject { values.forEach { (key, value) -> put(key, value) } }

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

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}
