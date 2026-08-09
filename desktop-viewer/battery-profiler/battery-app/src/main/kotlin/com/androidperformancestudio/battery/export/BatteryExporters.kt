@file:Suppress(
    "MagicNumber",
    "MaxLineLength",
    "NestedBlockDepth",
    "ktlint:standard:max-line-length",
    "TooManyFunctions",
)

package com.androidperformancestudio.battery.export

import com.androidperformancestudio.battery.analysis.BatteryAnalysisResult
import com.androidperformancestudio.battery.model.AttributionScope
import com.androidperformancestudio.battery.model.BatteryExperimentResult
import com.androidperformancestudio.battery.model.BatteryRunDelta
import com.androidperformancestudio.battery.model.ResourceTimer
import com.androidperformancestudio.battery.model.batteryDeviceLocalId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

public class BatteryJsonExporter {
    public fun export(
        experiment: BatteryExperimentResult,
        analysis: BatteryAnalysisResult,
        output: Path,
    ) {
        output.toAbsolutePath().parent?.let(Files::createDirectories)
        Files.writeString(
            output,
            JSON.encodeToString(
                kotlinx.serialization.json.JsonObject
                    .serializer(),
                document(experiment, analysis),
            ),
        )
    }

    private fun document(
        experiment: BatteryExperimentResult,
        analysis: BatteryAnalysisResult,
    ) = buildJsonObject {
        put("schemaVersion", 1)
        put("sessionId", experiment.session.id)
        put("deviceLocalId", batteryDeviceLocalId(experiment.session.deviceSerial).value)
        put("packageName", experiment.session.packageName)
        put("uid", experiment.session.uid)
        put("attributionScope", experiment.session.attributionScope.name)
        put("captureMode", experiment.session.config.mode.name)
        put("capabilityLevel", experiment.session.capabilities.level.name)
        put("createdAt", experiment.session.createdAt.toString())
        put("runs", buildJsonArray { analysis.runs.forEach { add(run(it)) } })
        put("warnings", buildJsonArray { analysis.warnings.forEach { add(JsonPrimitive(it)) } })
    }

    private fun run(delta: BatteryRunDelta) =
        buildJsonObject {
            put("runId", delta.runId)
            put("iteration", delta.iteration)
            put("durationMs", delta.durationMs)
            put("networkBytes", delta.network.totalBytes)
            put("wakelocks", timers(delta.wakelocks))
            put("alarms", timers(delta.alarms))
            put("jobs", timers(delta.jobs))
            put("sensors", timers(delta.sensors))
            put(
                "energy",
                buildJsonArray {
                    delta.energy.forEach { energy ->
                        add(
                            buildJsonObject {
                                put("component", energy.component)
                                energy.energyMah?.let { put("energyMah", it) }
                                energy.energyUws?.let { put("energyUws", it) }
                                put("source", energy.source.name)
                                put("scope", energy.attributionScope.name)
                                put("confidence", energy.confidence.name)
                            },
                        )
                    }
                },
            )
            put("warnings", buildJsonArray { delta.warnings.forEach { add(JsonPrimitive(it)) } })
        }

    private fun timers(values: List<ResourceTimer>) =
        buildJsonArray {
            values.forEach { timer ->
                add(
                    buildJsonObject {
                        put("name", timer.name)
                        put("durationMs", timer.durationMs)
                        put("count", timer.count)
                        put("confidence", timer.confidence.name)
                    },
                )
            }
        }

    private companion object {
        val JSON = Json { prettyPrint = true }
    }
}

public class BatteryCsvExporter {
    public fun export(
        analysis: BatteryAnalysisResult,
        output: Path,
        attributionScope: AttributionScope = AttributionScope.UID,
    ) {
        output.toAbsolutePath().parent?.let(Files::createDirectories)
        Files.newBufferedWriter(output).use { writer ->
            writer.appendLine("schema_version,run,resource_type,name,duration_ms,count,bytes,energy_mah,energy_uws,source,scope,confidence")
            analysis.runs.forEach { run ->
                writeTimers(writer, run, "wakelock", run.wakelocks, attributionScope)
                writeTimers(writer, run, "alarm", run.alarms, attributionScope)
                writeTimers(writer, run, "job", run.jobs, attributionScope)
                writeTimers(writer, run, "sensor", run.sensors, attributionScope)
                writer.appendLine("1,${run.iteration},network,total,,,${run.network.totalBytes},,,,,")
                run.energy.forEach { energy ->
                    writer.appendLine(
                        listOf(
                            "1",
                            run.iteration,
                            "energy",
                            csv(energy.component),
                            "",
                            "",
                            "",
                            energy.energyMah ?: "",
                            energy.energyUws ?: "",
                            energy.source.name,
                            energy.attributionScope.name,
                            energy.confidence.name,
                        ).joinToString(","),
                    )
                }
            }
        }
    }

    private fun writeTimers(
        writer: Writer,
        run: BatteryRunDelta,
        kind: String,
        timers: List<ResourceTimer>,
        attributionScope: AttributionScope,
    ) {
        timers.forEach { timer ->
            writer.appendLine(
                listOf(
                    "1",
                    run.iteration,
                    kind,
                    csv(timer.name),
                    timer.durationMs,
                    timer.count,
                    "",
                    "",
                    "",
                    "UID_COUNTER",
                    attributionScope.name,
                    timer.confidence.name,
                ).joinToString(","),
            )
        }
    }

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}

public class BatteryRawBundleExporter {
    public fun export(
        experiment: BatteryExperimentResult,
        output: Path,
    ) {
        output.toAbsolutePath().parent?.let(Files::createDirectories)
        ZipOutputStream(Files.newOutputStream(output)).use { zip ->
            zip.writeEntry(
                "manifest.txt",
                "schemaVersion=1\nsessionId=${experiment.session.id}\npackageName=${experiment.session.packageName}\nuid=${experiment.session.uid}\n",
            )
            experiment.runs.forEach { run ->
                (listOf(run.baseline) + run.samples + run.finalSnapshot).forEach { snapshot ->
                    val prefix = "run-${run.iteration}/snapshot-${snapshot.sequence}"
                    zip.writeEntry("$prefix/checkin.txt", snapshot.rawEvidence.checkin)
                    zip.writeEntry("$prefix/report.txt", snapshot.rawEvidence.report)
                    zip.writeEntry("$prefix/battery.txt", snapshot.rawEvidence.battery)
                    snapshot.rawEvidence.history?.let { zip.writeEntry("$prefix/history.txt", it) }
                    if (snapshot.conditions.isNotEmpty()) {
                        zip.writeEntry(
                            "$prefix/conditions.txt",
                            snapshot.conditions.entries.joinToString("\n", postfix = "\n") { (key, value) -> "$key=$value" },
                        )
                    }
                }
            }
        }
    }

    private fun ZipOutputStream.writeEntry(
        name: String,
        content: String,
    ) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
