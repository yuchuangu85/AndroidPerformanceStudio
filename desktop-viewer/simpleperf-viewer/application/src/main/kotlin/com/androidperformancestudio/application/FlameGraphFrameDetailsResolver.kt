@file:Suppress("ReturnCount")

package com.androidperformancestudio.application

import com.androidperformancestudio.toolchain.AndroidLlvmTool
import com.androidperformancestudio.toolchain.AndroidLlvmToolLocator
import com.androidperformancestudio.toolchain.AndroidLlvmToolProvider
import com.androidperformancestudio.toolchain.JvmProcessRunner
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds

sealed interface FlameGraphFrameDetails {
    data class Source(
        val file: Path,
        val line: Int,
        val column: Int?,
        val text: List<String>,
    ) : FlameGraphFrameDetails

    data class Disassembly(
        val binary: Path,
        val address: Long,
        val text: List<String>,
    ) : FlameGraphFrameDetails

    data class SymbolFallback(
        val function: String,
        val resource: String,
        val address: Long,
        val libraryOffset: Long,
        val buildId: String?,
        val reason: String,
    ) : FlameGraphFrameDetails
}

data class FlameGraphFrameDetailsRequest(
    val sessionDirectory: Path,
    val function: String,
    val resource: String,
    val address: Long,
    val libraryOffset: Long,
    val buildId: String? = null,
)

sealed interface FlameGraphDetailsState {
    data object Closed : FlameGraphDetailsState

    data class Loading(
        val nodeId: com.androidperformancestudio.profileanalysis.FlameCallNodeId,
        val generation: ProfileGeneration,
    ) : FlameGraphDetailsState

    data class Ready(
        val details: FlameGraphFrameDetails,
    ) : FlameGraphDetailsState
}

fun interface FlameGraphProcessInvoker {
    suspend fun run(request: ProcessRequest): ProcessRunResult
}

fun interface FlameGraphFrameDetailsProvider {
    suspend fun resolve(request: FlameGraphFrameDetailsRequest): FlameGraphFrameDetails
}

class FlameGraphFrameDetailsResolver(
    private val toolProvider: AndroidLlvmToolProvider = AndroidLlvmToolLocator(),
    private val processInvoker: FlameGraphProcessInvoker = defaultProcessInvoker(),
) : FlameGraphFrameDetailsProvider {
    override suspend fun resolve(request: FlameGraphFrameDetailsRequest): FlameGraphFrameDetails {
        val binary = resolveArtifact(request) ?: return request.fallback(unsafeOrMissingReason(request))
        val buildCheck = verifyBuildId(binary, request)
        if (buildCheck != null) return buildCheck

        var lastFailure = "No symbol source location found"
        toolProvider.locate(AndroidLlvmTool.SYMBOLIZER)?.let { symbolizer ->
            when (val source = resolveSource(symbolizer, binary, request)) {
                is SourceAttempt.Found -> return source.details
                is SourceAttempt.Missed -> lastFailure = source.reason
            }
        } ?: run { lastFailure = "llvm-symbolizer was not found" }

        toolProvider.locate(AndroidLlvmTool.OBJDUMP)?.let { objdump ->
            when (val disassembly = resolveDisassembly(objdump, binary, request)) {
                is DisassemblyAttempt.Found -> return disassembly.details
                is DisassemblyAttempt.Missed -> lastFailure = disassembly.reason
            }
        } ?: run { lastFailure = "llvm-objdump was not found after $lastFailure" }

        return request.fallback(lastFailure)
    }

    private suspend fun verifyBuildId(
        binary: Path,
        request: FlameGraphFrameDetailsRequest,
    ): FlameGraphFrameDetails.SymbolFallback? {
        val expected = request.buildId?.normalizeBuildId()?.takeIf(String::isNotBlank) ?: return null
        val readelf = toolProvider.locate(AndroidLlvmTool.READELF) ?: return null
        val result =
            processInvoker.run(
                ProcessRequest(
                    executable = readelf,
                    arguments = listOf("-n", binary.toString()),
                    timeout = TOOL_TIMEOUT,
                ),
            )
        val actual =
            when (result) {
                is ProcessRunResult.Completed -> parseBuildId(result.output.stdout.text)
                is ProcessRunResult.Failed -> null
            }
        return if (actual != null && actual != expected) {
            request.fallback("Build ID mismatch: expected ${request.buildId}, found $actual")
        } else {
            null
        }
    }

    private suspend fun resolveSource(
        symbolizer: Path,
        binary: Path,
        request: FlameGraphFrameDetailsRequest,
    ): SourceAttempt {
        val result =
            processInvoker.run(
                ProcessRequest(
                    executable = symbolizer,
                    arguments = listOf("--obj=$binary", request.address.hex()),
                    timeout = TOOL_TIMEOUT,
                ),
            )
        return when (result) {
            is ProcessRunResult.Failed -> SourceAttempt.Missed(result.error.code)
            is ProcessRunResult.Completed -> parseSource(result.output.stdout.text)
        }
    }

    private fun parseSource(stdout: String): SourceAttempt {
        val location =
            stdout
                .lineSequence()
                .mapNotNull { line -> line.trim().parseSourceLocation() }
                .firstOrNull()
                ?: return SourceAttempt.Missed("Symbolizer did not return a concrete source location")
        val file = Path.of(location.file).normalize()
        val line = location.line
        val column = location.column
        if (line <= 0 || file.toString() == "??" || !file.isRegularFile()) {
            return SourceAttempt.Missed("Symbolizer source location is unavailable")
        }
        return SourceAttempt.Found(
            FlameGraphFrameDetails.Source(
                file = file,
                line = line,
                column = column,
                text = Files.readAllLines(file).take(MAX_SOURCE_LINES),
            ),
        )
    }

    private suspend fun resolveDisassembly(
        objdump: Path,
        binary: Path,
        request: FlameGraphFrameDetailsRequest,
    ): DisassemblyAttempt {
        val start = max(0L, request.address - DISASSEMBLY_BYTES_BEFORE)
        val stop = saturatedAdd(request.address, DISASSEMBLY_BYTES_AFTER)
        val result =
            processInvoker.run(
                ProcessRequest(
                    executable = objdump,
                    arguments =
                        listOf(
                            "--disassemble",
                            "--source",
                            "--start-address=${start.hex()}",
                            "--stop-address=${stop.hex()}",
                            binary.toString(),
                        ),
                    timeout = TOOL_TIMEOUT,
                ),
            )
        return when (result) {
            is ProcessRunResult.Failed -> DisassemblyAttempt.Missed(result.error.code)
            is ProcessRunResult.Completed -> {
                val lines =
                    result.output.stdout.text
                        .lines()
                        .filter(String::isNotBlank)
                if (lines.isEmpty()) {
                    DisassemblyAttempt.Missed("llvm-objdump returned no disassembly")
                } else {
                    DisassemblyAttempt.Found(FlameGraphFrameDetails.Disassembly(binary, request.address, lines))
                }
            }
        }
    }

    private fun resolveArtifact(request: FlameGraphFrameDetailsRequest): Path? {
        if (request.resource.hasUnsafeSegments()) return null
        val relative = request.resource.removePrefix("/").takeIf(String::isNotBlank) ?: return null
        val session = request.sessionDirectory.toAbsolutePath().normalize()
        return listOf(session.resolve("symbols"), session.resolve("binary_cache"))
            .asSequence()
            .mapNotNull { root -> secureArtifact(root, relative) }
            .firstOrNull()
    }

    private fun secureArtifact(
        root: Path,
        relative: String,
    ): Path? {
        if (!Files.isDirectory(root)) return null
        val rootRealPath = root.toRealPath()
        val candidate = root.resolve(relative).normalize()
        if (!candidate.isRegularFile()) return null
        val candidateRealPath = candidate.toRealPath()
        return candidate.takeIf { candidateRealPath.startsWith(rootRealPath) }
    }

    private fun unsafeOrMissingReason(request: FlameGraphFrameDetailsRequest): String =
        if (request.resource.hasUnsafeSegments()) {
            "Unsafe binary resource path: ${request.resource}"
        } else {
            "Resolved binary artifact was not found for ${request.resource}"
        }
}

private sealed interface SourceAttempt {
    data class Found(
        val details: FlameGraphFrameDetails.Source,
    ) : SourceAttempt

    data class Missed(
        val reason: String,
    ) : SourceAttempt
}

private sealed interface DisassemblyAttempt {
    data class Found(
        val details: FlameGraphFrameDetails.Disassembly,
    ) : DisassemblyAttempt

    data class Missed(
        val reason: String,
    ) : DisassemblyAttempt
}

private fun defaultProcessInvoker(): FlameGraphProcessInvoker {
    val runner = JvmProcessRunner()
    return FlameGraphProcessInvoker { request -> runner.run(request) }
}

private fun FlameGraphFrameDetailsRequest.fallback(reason: String): FlameGraphFrameDetails.SymbolFallback =
    FlameGraphFrameDetails.SymbolFallback(
        function = function,
        resource = resource,
        address = address,
        libraryOffset = libraryOffset,
        buildId = buildId,
        reason = reason,
    )

private fun String.hasUnsafeSegments(): Boolean = split('/', '\\').any { it == ".." }

private fun String.normalizeBuildId(): String = lowercase().filter { it in '0'..'9' || it in 'a'..'f' }

private data class SourceLocation(
    val file: String,
    val line: Int,
    val column: Int?,
)

private fun String.parseSourceLocation(): SourceLocation? {
    val lastColon = lastIndexOf(':')
    if (lastColon <= 0) return null
    val lastPart = substring(lastColon + 1).toIntOrNull() ?: return null
    val beforeLast = substring(0, lastColon)
    val secondColon = beforeLast.lastIndexOf(':')
    if (secondColon > 0) {
        val linePart = beforeLast.substring(secondColon + 1).toIntOrNull()
        if (linePart != null) {
            return SourceLocation(beforeLast.substring(0, secondColon), linePart, lastPart)
        }
    }
    return SourceLocation(beforeLast, lastPart, null)
}

private fun parseBuildId(stdout: String): String? =
    BUILD_ID
        .find(stdout)
        ?.groupValues
        ?.get(1)
        ?.normalizeBuildId()
        ?.takeIf(String::isNotBlank)

private fun Long.hex(): String = "0x${toString(HEX_RADIX)}"

private fun saturatedAdd(
    value: Long,
    delta: Long,
): Long = if (Long.MAX_VALUE - value < delta) Long.MAX_VALUE else value + delta

private val BUILD_ID = Regex("Build ID:\\s*([0-9A-Fa-f ]+)")
private val TOOL_TIMEOUT = 10.seconds
private const val HEX_RADIX = 16
private const val MAX_SOURCE_LINES = 10_000
private const val DISASSEMBLY_BYTES_BEFORE = 0x40L
private const val DISASSEMBLY_BYTES_AFTER = 0x80L
