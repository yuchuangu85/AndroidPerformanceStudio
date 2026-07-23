package com.androidperformancestudio.perfetto.export

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.perfetto.model.TraceSession
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TraceExporter {

    fun exportSessionPackage(session: TraceSession, outputFile: Path): StudioResult<Path> = try {
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
        }
        StudioResult.Success(outputFile)
    } catch (e: Exception) {
        StudioResult.Failure(StudioError(
            category = ErrorCategory.IO, code = "EXPORT_FAILED",
            message = "Failed to export session: ${e.message}", cause = e,
        ))
    }

    private fun buildSessionJson(session: TraceSession): String = """{"id":"${session.id}","capturedAt":"${session.capturedAt}","deviceSerial":"${session.deviceSerial}","deviceModel":"${session.deviceModel}","androidSdk":${session.androidSdk},"durationNanos":${session.durationNanos},"fileSizeBytes":${session.fileSizeBytes},"config":{"template":"${session.captureConfig.template.name}","durationSeconds":${session.captureConfig.durationSeconds},"bufferSizeKb":${session.captureConfig.bufferSizeKb}}}"""

    private fun buildMetadataProperties(session: TraceSession): String = "session.id=${session.id}\ncaptured.at=${session.capturedAt}\ndevice.serial=${session.deviceSerial}\ndevice.model=${session.deviceModel}\ndevice.sdk=${session.androidSdk}\ntrace.template=${session.captureConfig.template.name}\ntrace.duration.seconds=${session.captureConfig.durationSeconds}\ntrace.buffer.kb=${session.captureConfig.bufferSizeKb}\nexporter.version=1.0\n"
}
