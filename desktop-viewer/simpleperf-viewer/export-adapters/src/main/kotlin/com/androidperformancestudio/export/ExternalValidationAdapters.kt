package com.androidperformancestudio.export

import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.platform.toolchain.HostCancellationSignal
import com.androidperformancestudio.platform.toolchain.HostCommandOutput
import com.androidperformancestudio.platform.toolchain.HostCommandResult
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import com.androidperformancestudio.platform.toolchain.StudioHostProcessExecutor
import com.androidperformancestudio.storage.TopFunction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

fun interface ExternalProcessRunner {
    suspend fun run(
        request: HostProcessRequest,
        cancellationSignal: HostCancellationSignal,
    ): HostCommandResult
}

data class SimpleperfReportRow(
    val overheadPercent: Double,
    val sharedObject: String,
    val symbolName: String,
)

data class ExternalStatisticsComparison(
    val withinTolerance: Boolean,
    val maximumDeviationPercentagePoints: Double,
    val mismatchedSymbols: List<String>,
)

object ExternalStatisticsComparator {
    fun compare(
        local: List<TopFunction>,
        external: List<SimpleperfReportRow>,
        tolerancePercentagePoints: Double,
    ): ExternalStatisticsComparison {
        require(tolerancePercentagePoints >= 0) { "tolerancePercentagePoints must not be negative" }
        val totalExclusiveWeight = local.sumOf(TopFunction::exclusiveWeight).toDouble()
        val localPercentages =
            local
                .groupingBy(TopFunction::symbolName)
                .fold(0L) { weight, function -> weight + function.exclusiveWeight }
                .mapValues { (_, weight) -> weight.toPercentageOf(totalExclusiveWeight) }
        val externalPercentages =
            external
                .groupingBy(SimpleperfReportRow::symbolName)
                .fold(0.0) { percentage, row -> percentage + row.overheadPercent }
        val deviations =
            (localPercentages.keys + externalPercentages.keys).associateWith { symbol ->
                val localPercentage = localPercentages.getOrDefault(symbol, 0.0)
                val externalPercentage = externalPercentages.getOrDefault(symbol, 0.0)
                kotlin.math.abs(localPercentage - externalPercentage)
            }
        val mismatches =
            deviations
                .filterValues { it > tolerancePercentagePoints }
                .keys
                .sorted()
        return ExternalStatisticsComparison(
            withinTolerance = mismatches.isEmpty(),
            maximumDeviationPercentagePoints = deviations.values.maxOrNull() ?: 0.0,
            mismatchedSymbols = mismatches,
        )
    }
}

private fun Long.toPercentageOf(total: Double): Double = if (total == 0.0) 0.0 else this / total * PERCENT_MULTIPLIER

sealed interface ExternalValidationResult {
    data class Success(
        val output: HostCommandOutput,
        val reportRows: List<SimpleperfReportRow> = emptyList(),
        val artifact: Path? = null,
    ) : ExternalValidationResult

    data class Failure(
        val error: StudioError,
        val output: HostCommandOutput? = null,
    ) : ExternalValidationResult
}

class SimpleperfReportAdapter(
    private val simpleperfExecutable: Path,
    private val runner: ExternalProcessRunner = defaultExternalRunner(),
) {
    suspend fun generate(
        perfData: Path,
        cancellationSignal: HostCancellationSignal = HostCancellationSignal(),
        timeout: Duration = DEFAULT_EXTERNAL_TIMEOUT,
    ): ExternalValidationResult {
        require(simpleperfExecutable.isRegularFile()) { "simpleperf executable does not exist: $simpleperfExecutable" }
        require(perfData.isRegularFile()) { "perf.data does not exist: $perfData" }
        val result =
            runner.run(
                HostProcessRequest(
                    simpleperfExecutable,
                    listOf("report", "-i", perfData.toString(), "--sort", "symbol"),
                    timeout = timeout,
                ),
                cancellationSignal,
            )
        return result.toValidationResult { output -> parseSimpleperfReport(output.stdout.text) }
    }
}

class ReportHtmlAdapter(
    private val pythonExecutable: Path,
    private val reportHtmlScript: Path,
    private val runner: ExternalProcessRunner = defaultExternalRunner(),
) {
    suspend fun generate(
        perfData: Path,
        destinationHtml: Path,
        cancellationSignal: HostCancellationSignal = HostCancellationSignal(),
        timeout: Duration = DEFAULT_EXTERNAL_TIMEOUT,
    ): ExternalValidationResult {
        require(pythonExecutable.isRegularFile()) { "Python executable does not exist: $pythonExecutable" }
        require(reportHtmlScript.isRegularFile()) { "report_html.py does not exist: $reportHtmlScript" }
        require(perfData.isRegularFile()) { "perf.data does not exist: $perfData" }
        destinationHtml.parent?.let(Files::createDirectories)
        val result =
            runner.run(
                HostProcessRequest(
                    pythonExecutable,
                    listOf(reportHtmlScript.toString(), "-i", perfData.toString(), "-o", destinationHtml.toString()),
                    timeout = timeout,
                ),
                cancellationSignal,
            )
        val converted = result.toValidationResult()
        return if (converted is ExternalValidationResult.Success && destinationHtml.nonEmptyFile()) {
            converted.copy(artifact = destinationHtml)
        } else if (converted is ExternalValidationResult.Success) {
            error("report_html.py completed without a non-empty HTML report")
        } else {
            converted
        }
    }
}

data class ExternalOpenInstructions(
    val artifact: Path,
    val androidStudio: String,
    val perfetto: String,
)

fun externalOpenInstructions(protobuf: Path): ExternalOpenInstructions =
    ExternalOpenInstructions(
        artifact = protobuf,
        androidStudio = "Android Studio: Profiler > Import trace, then select ${protobuf.fileName}.",
        perfetto =
            "Perfetto UI: Open trace file and select ${protobuf.fileName}; " +
                "Simpleperf protobuf support depends on the viewer build.",
    )

private fun HostCommandResult.toValidationResult(
    rows: (HostCommandOutput) -> List<SimpleperfReportRow> = { emptyList() },
): ExternalValidationResult =
    when (this) {
        is HostCommandResult.Completed -> ExternalValidationResult.Success(output, rows(output))
        is HostCommandResult.Failed -> ExternalValidationResult.Failure(error, output)
    }

private fun parseSimpleperfReport(text: String): List<SimpleperfReportRow> =
    text
        .lineSequence()
        .mapNotNull { line ->
            val match = REPORT_ROW.matchEntire(line) ?: return@mapNotNull null
            val tail = match.groupValues[2].trim().split(Regex("\\s+"))
            if (tail.size < MINIMUM_REPORT_COLUMNS) {
                null
            } else {
                SimpleperfReportRow(match.groupValues[1].toDouble(), tail[tail.lastIndex - 1], tail.last())
            }
        }.toList()

private fun defaultExternalRunner(): ExternalProcessRunner {
    val runner = StudioHostProcessExecutor()
    return ExternalProcessRunner(runner::run)
}

private fun Path.nonEmptyFile(): Boolean = isRegularFile() && Files.size(this) > 0

private val REPORT_ROW = Regex("\\s*([0-9]+(?:\\.[0-9]+)?)%\\s+(.+)")
private const val MINIMUM_REPORT_COLUMNS = 3
private const val PERCENT_MULTIPLIER = 100
private val DEFAULT_EXTERNAL_TIMEOUT = 5.minutes
