package com.androidperformancestudio.parser

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.toolchain.HostCancellationSignal
import com.androidperformancestudio.platform.toolchain.HostCommandResult
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import com.androidperformancestudio.platform.toolchain.StudioHostProcessExecutor
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.time.Duration.Companion.minutes

data class SimpleperfConversionRequest(
    val perfData: Path,
    val protobufTrace: Path,
    val symbolDirectory: Path? = null,
    val proguardMapping: Path? = null,
)

data class SimpleperfConversionResult(
    val protobufTrace: Path,
    val stdout: String,
    val stderr: String,
)

class SimpleperfReportConverter(
    private val processInvocation: ParserProcessInvocation = { request, signal ->
        StudioHostProcessExecutor().run(request, signal)
    },
) {
    suspend fun convert(
        simpleperf: HostSimpleperf,
        request: SimpleperfConversionRequest,
        cancellationSignal: HostCancellationSignal = HostCancellationSignal(),
    ): StudioResult<SimpleperfConversionResult> {
        val preconditionFailure = validateInput(request) ?: prepareOutput(request.protobufTrace)
        return if (preconditionFailure != null) {
            preconditionFailure
        } else {
            execute(simpleperf, request, cancellationSignal)
        }
    }

    private suspend fun execute(
        simpleperf: HostSimpleperf,
        request: SimpleperfConversionRequest,
        cancellationSignal: HostCancellationSignal,
    ): StudioResult<SimpleperfConversionResult> {
        val processRequest =
            HostProcessRequest(
                executable = simpleperf.executable,
                arguments = request.arguments(),
                timeout = 30.minutes,
            )
        return when (val result = processInvocation(processRequest, cancellationSignal)) {
            is HostCommandResult.Failed -> StudioResult.Failure(result.error)
            is HostCommandResult.Completed -> completed(request.protobufTrace, result)
        }
    }

    private fun validateInput(request: SimpleperfConversionRequest): StudioResult.Failure? =
        when {
            !request.perfData.isRegularFile() -> ioFailure("PERF_DATA_NOT_FOUND", "perf.data does not exist")
            request.symbolDirectory != null && !Files.isDirectory(request.symbolDirectory) ->
                ioFailure("SYMBOL_DIRECTORY_NOT_FOUND", "Symbol directory does not exist")
            request.proguardMapping != null && !request.proguardMapping.isRegularFile() ->
                ioFailure("PROGUARD_MAPPING_NOT_FOUND", "Proguard mapping file does not exist")
            else -> null
        }

    private fun prepareOutput(output: Path): StudioResult.Failure? =
        try {
            output.toAbsolutePath().parent?.let(Files::createDirectories)
            Files.deleteIfExists(output)
            null
        } catch (exception: IOException) {
            ioFailure("PROTOBUF_OUTPUT_PREPARE_FAILED", "Failed to prepare protobuf output", exception)
        }

    private fun completed(
        output: Path,
        result: HostCommandResult.Completed,
    ): StudioResult<SimpleperfConversionResult> =
        if (output.isRegularFile() && output.fileSize() > 0L) {
            StudioResult.Success(
                SimpleperfConversionResult(
                    protobufTrace = output,
                    stdout = result.output.stdout.text,
                    stderr = result.output.stderr.text,
                ),
            )
        } else {
            ioFailure(
                "PROTOBUF_OUTPUT_MISSING",
                "simpleperf completed without producing a non-empty protobuf trace",
            )
        }
}

private fun SimpleperfConversionRequest.arguments(): List<String> =
    buildList {
        addAll(listOf("report-sample", "--protobuf", "--show-callchain", "-i", perfData.toString()))
        addAll(listOf("-o", protobufTrace.toString()))
        symbolDirectory?.let { addAll(listOf("--symdir", it.toString())) }
        proguardMapping?.let { addAll(listOf("--proguard-mapping-file", it.toString())) }
    }

private fun ioFailure(
    code: String,
    message: String,
    cause: Throwable? = null,
): StudioResult.Failure = StudioResult.Failure(StudioError(ErrorCategory.IO, code, message, cause))
