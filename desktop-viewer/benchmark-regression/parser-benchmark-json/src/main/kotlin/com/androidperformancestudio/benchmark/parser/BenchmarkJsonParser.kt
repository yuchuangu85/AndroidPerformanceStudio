@file:Suppress("ComplexCondition", "MagicNumber", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "TooManyFunctions")

package com.androidperformancestudio.benchmark.parser

import com.androidperformancestudio.benchmark.model.BenchmarkBuild
import com.androidperformancestudio.benchmark.model.BenchmarkCase
import com.androidperformancestudio.benchmark.model.BenchmarkDevice
import com.androidperformancestudio.benchmark.model.BenchmarkMetric
import com.androidperformancestudio.benchmark.model.BenchmarkRun
import com.androidperformancestudio.benchmark.model.EvidenceConfidence
import com.androidperformancestudio.benchmark.model.MetricDirection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.Path

public class BenchmarkJsonParser(
    private val maxBytes: Long = 64L * 1024L * 1024L,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    public fun parse(file: Path): BenchmarkRun {
        require(Files.isRegularFile(file)) { "Benchmark JSON does not exist: $file" }
        require(Files.size(file) <= maxBytes) { "Benchmark JSON exceeds $maxBytes bytes" }
        val root = json.parseToJsonElement(Files.readString(file)).jsonObject
        val context = root.objectOrNull("context") ?: root.objectOrNull("device") ?: JsonObject(emptyMap())
        val warnings = mutableListOf<String>()
        val benchmarkElements =
            root.arrayOrNull("benchmarks")
                ?: root.arrayOrNull("results")
                ?: root.arrayOrNull("tests")
                ?: error("No benchmarks/results/tests array found in ${file.fileName}")
        val cases =
            benchmarkElements.mapIndexedNotNull { index, element ->
                runCatching { parseCase(element.jsonObject, file.parent) }
                    .onFailure { warnings += "Case #$index skipped: ${it.message}" }
                    .getOrNull()
            }
        require(cases.isNotEmpty()) { "No valid benchmark cases found in ${file.fileName}" }
        return BenchmarkRun(
            sourceFile = file.toAbsolutePath().normalize(),
            benchmarkDataVersion = root.int("version") ?: root.int("benchmarkDataVersion") ?: context.int("benchmarkDataVersion"),
            benchmarkLibraryVersion = root.string("benchmarkLibraryVersion") ?: context.string("benchmarkLibraryVersion"),
            device = parseDevice(context, root),
            build = parseBuild(context, root),
            cases = cases,
            warnings = warnings + unknownTopLevelWarnings(root),
        )
    }

    private fun parseCase(
        value: JsonObject,
        baseDir: Path?,
    ): BenchmarkCase {
        val rawName = value.string("name") ?: value.string("testName") ?: value.string("benchmarkName") ?: "unknown"
        val className = value.string("className") ?: rawName.substringBeforeLast('.', "UnknownBenchmark")
        val testName = value.string("testName") ?: rawName.substringAfterLast('.')
        val metricsObject = value.objectOrNull("metrics") ?: value.objectOrNull("measurements") ?: JsonObject(emptyMap())
        val metrics = metricsObject.mapNotNull { (name, metric) -> parseMetric(name, metric) }
        require(metrics.isNotEmpty()) { "No metrics found for $rawName" }
        val traces =
            buildList {
                value.arrayOrNull("tracePaths")?.forEach { it.jsonPrimitive.contentOrNull?.let { path -> add(resolve(baseDir, path)) } }
                value.string("tracePath")?.let { add(resolve(baseDir, it)) }
                value.arrayOrNull("profilerOutputs")?.forEach { output ->
                    val path = (output as? JsonObject)?.string("filePath") ?: output.jsonPrimitive.contentOrNull
                    path
                        ?.takeIf { it.endsWith(".trace") || it.endsWith(".perfetto-trace") || it.endsWith(".pftrace") }
                        ?.let { add(resolve(baseDir, it)) }
                }
            }.distinct()
        return BenchmarkCase(
            className = className,
            testName = testName,
            packageName = value.string("packageName") ?: value.string("targetPackage"),
            compilationMode = value.string("compilationMode") ?: value.objectOrNull("params")?.string("compilationMode"),
            startupMode = value.string("startupMode") ?: value.objectOrNull("params")?.string("startupMode"),
            iterationCount = value.int("repeatIterations") ?: value.int("iterationCount"),
            metrics = metrics,
            traceArtifacts = traces,
        )
    }

    private fun parseMetric(
        name: String,
        element: JsonElement,
    ): BenchmarkMetric? {
        if (element is JsonPrimitive && element.doubleOrNull != null) {
            val value = element.doubleOrNull ?: return null
            return BenchmarkMetric(
                name,
                inferUnit(name),
                inferDirection(name),
                listOf(value),
                value,
                value,
                value,
                EvidenceConfidence.PARTIAL,
            )
        }
        val metric = element as? JsonObject ?: return null
        val samples =
            sequenceOf("runs", "values", "samples", "measurements")
                .mapNotNull { metric.arrayOrNull(it) }
                .firstOrNull()
                ?.mapNotNull(::numericValue)
                .orEmpty()
        val minimum = metric.double("minimum") ?: metric.double("min") ?: samples.minOrNull()
        val median =
            metric.double("median") ?: metric.double("p50") ?: samples.sorted().let { values ->
                if (values.isEmpty()) {
                    null
                } else if (values.size % 2 ==
                    0
                ) {
                    (values[values.size / 2 - 1] + values[values.size / 2]) / 2
                } else {
                    values[values.size / 2]
                }
            }
        val maximum = metric.double("maximum") ?: metric.double("max") ?: samples.maxOrNull()
        if (minimum == null && median == null && maximum == null && samples.isEmpty()) return null
        val unit = metric.string("unit") ?: inferUnit(name)
        return BenchmarkMetric(
            name = name,
            unit = unit,
            direction = inferDirection(name),
            samples = samples,
            minimum = minimum,
            median = median,
            maximum = maximum,
            confidence = if (samples.isNotEmpty()) EvidenceConfidence.EXACT else EvidenceConfidence.PARTIAL,
            sourceFields = metric.mapValues { (_, value) -> value.toString() },
        )
    }

    private fun numericValue(element: JsonElement): Double? =
        when (element) {
            is JsonPrimitive -> element.doubleOrNull
            is JsonObject -> element.double("value") ?: element.double("measurement") ?: element.double("ns")
            else -> null
        }

    private fun parseDevice(
        context: JsonObject,
        root: JsonObject,
    ): BenchmarkDevice =
        BenchmarkDevice(
            model = context.string("deviceModel") ?: context.string("model") ?: root.string("deviceModel"),
            brand = context.string("deviceBrand") ?: context.string("brand"),
            apiLevel = context.int("apiLevel") ?: context.int("sdkVersion") ?: context.int("sdkInt"),
            osVersion = context.string("osVersion") ?: context.string("buildVersion"),
            abi =
                context.string("abi") ?: context
                    .arrayOrNull("supportedAbis")
                    ?.firstOrNull()
                    ?.jsonPrimitive
                    ?.contentOrNull,
            fingerprint = context.string("fingerprint") ?: context.string("buildFingerprint"),
            cpuCoreCount = context.int("cpuCoreCount"),
            physicalDevice = context.boolean("physicalDevice") ?: context.boolean("isPhysicalDevice"),
        )

    private fun parseBuild(
        context: JsonObject,
        root: JsonObject,
    ): BenchmarkBuild {
        val build = root.objectOrNull("build") ?: context.objectOrNull("build") ?: JsonObject(emptyMap())
        return BenchmarkBuild(
            targetPackage = build.string("targetPackage") ?: root.string("targetPackage"),
            versionName = build.string("versionName") ?: root.string("versionName"),
            versionCode = build.long("versionCode") ?: root.long("versionCode"),
            variant = build.string("variant") ?: root.string("variant"),
            gitCommit = build.string("gitCommit") ?: root.string("gitCommit"),
            gitBranch = build.string("gitBranch") ?: root.string("gitBranch"),
        )
    }

    private fun unknownTopLevelWarnings(root: JsonObject): List<String> {
        val known =
            setOf(
                "context",
                "device",
                "build",
                "benchmarks",
                "results",
                "tests",
                "version",
                "benchmarkDataVersion",
                "benchmarkLibraryVersion",
                "targetPackage",
                "versionName",
                "versionCode",
                "variant",
                "gitCommit",
                "gitBranch",
            )
        val unknown = root.keys - known
        return if (unknown.isEmpty()) emptyList() else listOf("Preserved unknown top-level fields: ${unknown.sorted().joinToString()}")
    }

    private fun inferUnit(name: String): String =
        when {
            name.endsWith("Ms", true) || "millisecond" in name.lowercase() -> "ms"
            name.endsWith("Ns", true) || "nanosecond" in name.lowercase() -> "ns"
            "byte" in name.lowercase() || name.endsWith("Kb", true) -> "bytes"
            "percent" in name.lowercase() || name.endsWith("Pct", true) -> "%"
            else -> "unit"
        }

    private fun inferDirection(name: String): MetricDirection {
        val lower = listOf("time", "duration", "latency", "overrun", "jank", "memory", "byte", "power", "energy")
        val higher = listOf("throughput", "fps", "framespersecond")
        val normalized = name.lowercase()
        return when {
            higher.any(normalized::contains) -> MetricDirection.HIGHER_IS_BETTER
            lower.any(normalized::contains) -> MetricDirection.LOWER_IS_BETTER
            else -> MetricDirection.UNKNOWN
        }
    }

    private fun resolve(
        baseDir: Path?,
        raw: String,
    ): Path {
        val path = Path.of(raw)
        return if (path.isAbsolute || baseDir == null) path.normalize() else baseDir.resolve(path).normalize()
    }
}

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull

private fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull

private fun JsonObject.double(name: String): Double? = this[name]?.jsonPrimitive?.doubleOrNull

private fun JsonObject.boolean(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull

private fun JsonObject.objectOrNull(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.arrayOrNull(name: String): JsonArray? = this[name] as? JsonArray
