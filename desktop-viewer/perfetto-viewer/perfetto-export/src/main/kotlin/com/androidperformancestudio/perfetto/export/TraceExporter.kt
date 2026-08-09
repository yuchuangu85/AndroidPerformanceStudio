package com.androidperformancestudio.perfetto.export

import com.androidperformancestudio.contracts.CaptureArtifactJson
import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.perfetto.model.TraceSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TraceExporter {
    fun exportSessionPackage(
        session: TraceSession,
        outputFile: Path,
    ): StudioResult<Path> =
        try {
            ZipOutputStream(Files.newOutputStream(outputFile)).use { zip ->
                zip.putNextEntry(ZipEntry("session.json"))
                zip.write(buildSessionJson(session).toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("trace.pftrace"))
                Files.copy(session.traceFile, zip)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("metadata.properties"))
                zip.write(buildMetadataProperties(session).toByteArray())
                zip.closeEntry()
                session.artifact?.let { artifact ->
                    zip.putNextEntry(ZipEntry("capture-artifact.json"))
                    zip.write(CaptureArtifactJson.encode(artifact).toByteArray())
                    zip.closeEntry()
                }
            }
            StudioResult.Success(outputFile)
        } catch (e: Exception) {
            StudioResult.Failure(
                StudioError(
                    category = ErrorCategory.IO,
                    code = "EXPORT_FAILED",
                    message = "Failed to export session: ${e.message}",
                    cause = e,
                ),
            )
        }

    private fun buildSessionJson(session: TraceSession): String =
        Json { prettyPrint = true }
            .encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("id", session.id)
                    put("capturedAt", session.capturedAt.toString())
                    put("deviceModel", session.deviceModel)
                    put("androidSdk", session.androidSdk)
                    put("durationNanos", session.durationNanos)
                    put("fileSizeBytes", session.fileSizeBytes)
                    session.notes?.let { put("notes", it) }
                    put("isProtected", session.isProtected)
                    put(
                        "config",
                        buildJsonObject {
                            put("template", session.captureConfig.template.name)
                            session.captureConfig.targetPackage?.let { put("targetPackage", it) }
                            put("durationSeconds", session.captureConfig.durationSeconds)
                            put("bufferSizeKb", session.captureConfig.bufferSizeKb)
                            put(
                                "additionalCategories",
                                buildJsonArray {
                                    session.captureConfig.additionalCategories.forEach { add(JsonPrimitive(it)) }
                                },
                            )
                            session.captureConfig.customConfigText?.let { put("customConfigText", it) }
                        },
                    )
                },
            )

    private fun buildMetadataProperties(session: TraceSession): String =
        buildString {
            appendLine("session.id=${escapeProperty(session.id)}")
            appendLine("captured.at=${session.capturedAt}")
            appendLine("device.model=${escapeProperty(session.deviceModel)}")
            appendLine("device.sdk=${session.androidSdk}")
            appendLine("trace.template=${session.captureConfig.template.name}")
            appendLine("trace.duration.seconds=${session.captureConfig.durationSeconds}")
            appendLine("trace.buffer.kb=${session.captureConfig.bufferSizeKb}")
            session.artifact?.let { appendLine("artifact.sha256=${it.sha256.value}") }
            appendLine("exporter.version=1.0")
        }

    private fun escapeProperty(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("=", "\\=")
            .replace(":", "\\:")
}
