@file:Suppress("MagicNumber", "MaxLineLength", "ReturnCount", "ktlint:standard:max-line-length")

package com.androidperformancestudio.application

import com.androidperformancestudio.storage.TopFunction
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories

data class BatchCaptureTarget(
    val id: String,
    val repeatCount: Int = 1,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(id.isNotBlank()) { "capture target id must not be blank" }
        require(repeatCount > 0) { "repeatCount must be positive" }
    }
}

data class BatchCapturePlan(
    val targets: List<BatchCaptureTarget>,
    val failFast: Boolean = false,
) {
    init {
        require(targets.isNotEmpty()) { "capture targets must not be empty" }
    }
}

data class BatchCaptureArtifact(
    val targetId: String,
    val iteration: Int,
    val sessionDirectory: Path?,
    val success: Boolean,
    val message: String,
)

class BatchCaptureExecutor(
    private val capture: suspend (BatchCaptureTarget, Int) -> BatchCaptureArtifact,
) {
    suspend fun execute(plan: BatchCapturePlan): List<BatchCaptureArtifact> {
        val results = mutableListOf<BatchCaptureArtifact>()
        for (target in plan.targets) {
            for (iteration in 1..target.repeatCount) {
                val result = capture(target, iteration)
                results += result
                if (plan.failFast && !result.success) return results
            }
        }
        return results
    }
}

data class BatchProfilePlan(
    val sessionDirectories: List<Path>,
    val failFast: Boolean = false,
) {
    init {
        require(sessionDirectories.isNotEmpty()) { "sessionDirectories must not be empty" }
    }
}

data class BatchProfileResult(
    val sessionDirectory: Path,
    val success: Boolean,
    val message: String,
    val report: ReportData? = null,
)

class BatchProfileExecutor(
    private val analyzer: suspend (Path) -> BatchProfileResult,
) {
    suspend fun execute(plan: BatchProfilePlan): List<BatchProfileResult> {
        val results = mutableListOf<BatchProfileResult>()
        for (directory in plan.sessionDirectories) {
            val result = analyzer(directory)
            results += result
            if (plan.failFast && !result.success) break
        }
        return results
    }
}

enum class CiRegressionClassification { REGRESSED, IMPROVED, STABLE, NEW, REMOVED }

data class CiFunctionComparison(
    val symbolName: String,
    val filePath: String,
    val baselineWeight: Long?,
    val currentWeight: Long?,
    val deltaWeight: Long?,
    val deltaPercent: Double?,
    val classification: CiRegressionClassification,
)

data class CiProfileComparison(
    val functions: List<CiFunctionComparison>,
    val regressionCount: Int,
)

object CiProfileComparator {
    fun compare(
        baseline: List<TopFunction>,
        current: List<TopFunction>,
        thresholdPercent: Double = 5.0,
    ): CiProfileComparison {
        require(thresholdPercent >= 0) { "thresholdPercent must not be negative" }
        val before = baseline.associateBy { it.symbolName to it.filePath }
        val after = current.associateBy { it.symbolName to it.filePath }
        val comparisons =
            (before.keys + after.keys)
                .map { key ->
                    val left = before[key]?.inclusiveWeight
                    val right = after[key]?.inclusiveWeight
                    val delta = if (left != null && right != null) right - left else null
                    val percent =
                        if (delta != null && left != null && left != 0L) {
                            delta.toDouble() / kotlin.math.abs(left) * 100.0
                        } else {
                            null
                        }
                    val classification =
                        when {
                            left == null -> CiRegressionClassification.NEW
                            right == null -> CiRegressionClassification.REMOVED
                            percent == null || kotlin.math.abs(percent) < thresholdPercent -> CiRegressionClassification.STABLE
                            delta != null && delta > 0 -> CiRegressionClassification.REGRESSED
                            else -> CiRegressionClassification.IMPROVED
                        }
                    CiFunctionComparison(key.first, key.second, left, right, delta, percent, classification)
                }.sortedByDescending { kotlin.math.abs(it.deltaWeight ?: 0) }
        return CiProfileComparison(comparisons, comparisons.count { it.classification == CiRegressionClassification.REGRESSED })
    }
}

class SymbolServerResolver(
    private val servers: List<URI>,
    private val cacheDirectory: Path,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) {
    fun resolve(
        buildId: String,
        fileName: String,
    ): Path? {
        require(BUILD_ID.matches(buildId)) { "buildId must be hexadecimal" }
        require(SYMBOL_FILE_NAME.matches(fileName) && fileName !in setOf(".", "..")) {
            "fileName must be a safe symbol basename"
        }
        val cached = cacheDirectory.resolve(buildId.lowercase()).resolve(fileName)
        if (Files.isRegularFile(cached)) return cached
        cached.parent.createDirectories()
        for (server in servers) {
            val resolved = server.resolve("${buildId.lowercase()}/$fileName")
            if (resolved.scheme == "file") {
                val source = Path.of(resolved)
                if (Files.isRegularFile(source)) {
                    Files.copy(source, cached, StandardCopyOption.REPLACE_EXISTING)
                    return cached
                }
            } else if (resolved.scheme in setOf("http", "https")) {
                val response =
                    httpClient.send(
                        HttpRequest.newBuilder(resolved).GET().build(),
                        HttpResponse.BodyHandlers.ofFile(cached),
                    )
                if (response.statusCode() in 200..299) return cached
                Files.deleteIfExists(cached)
            }
        }
        return null
    }

    private companion object {
        val BUILD_ID = Regex("[0-9a-fA-F]{8,128}")
        val SYMBOL_FILE_NAME = Regex("[A-Za-z0-9._+@-]{1,255}")
    }
}
