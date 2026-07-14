package com.androidperformancestudio.export

import com.androidperformancestudio.storage.CallTreeNode
import com.androidperformancestudio.storage.TopFunction
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile

class ReportExportService {
    fun exportJson(
        topFunctions: List<TopFunction>,
        callTree: List<CallTreeNode>,
        destination: Path,
    ) {
        destination.writeText(reportJson(topFunctions, callTree))
    }

    fun exportTopFunctionsCsv(
        topFunctions: List<TopFunction>,
        destination: Path,
    ) {
        val rows =
            buildList {
                add("symbol,file,inclusive_weight,exclusive_weight,sample_count,thread_count")
                topFunctions.forEach { function ->
                    add(
                        listOf(
                            function.symbolName,
                            function.filePath,
                            function.inclusiveWeight,
                            function.exclusiveWeight,
                            function.sampleCount,
                            function.threadCount,
                        ).joinToString(",") { it.toString().csvEscape() },
                    )
                }
            }
        destination.writeText(rows.joinToString("\n", postfix = "\n"))
    }

    fun exportCallTreeCsv(
        callTree: List<CallTreeNode>,
        destination: Path,
    ) {
        val rows =
            buildList {
                add("id,parent_id,depth,symbol,file,inclusive_weight,exclusive_weight,sample_count,thread_count")
                callTree.forEach { node ->
                    add(
                        listOf(
                            node.id,
                            node.parentId ?: "",
                            node.depth,
                            node.symbolName,
                            node.filePath,
                            node.inclusiveWeight,
                            node.exclusiveWeight,
                            node.sampleCount,
                            node.threadCount,
                        ).joinToString(",") { it.toString().csvEscape() },
                    )
                }
            }
        destination.writeText(rows.joinToString("\n", postfix = "\n"))
    }

    fun exportRawProtobuf(
        source: Path,
        destination: Path,
    ) {
        require(source.isRegularFile()) { "Raw protobuf does not exist: $source" }
        destination.parent?.createDirectories()
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
    }

    fun exportScreenshot(
        image: BufferedImage,
        destination: Path,
    ) {
        destination.parent?.createDirectories()
        require(ImageIO.write(image, "png", destination.toFile())) { "No PNG image writer is available" }
    }
}

private fun reportJson(
    topFunctions: List<TopFunction>,
    callTree: List<CallTreeNode>,
): String =
    buildString {
        append("{\n  \"schemaVersion\": 1,\n  \"topFunctions\": [")
        append(topFunctions.joinToString(",") { it.toJson() })
        append("],\n  \"callTree\": [")
        append(callTree.joinToString(",") { it.toJson() })
        append("]\n}\n")
    }

private fun TopFunction.toJson(): String =
    "{\"symbol\":${symbolName.jsonString()},\"file\":${filePath.jsonString()}," +
        "\"inclusiveWeight\":$inclusiveWeight,\"exclusiveWeight\":$exclusiveWeight," +
        "\"sampleCount\":$sampleCount,\"threadCount\":$threadCount}"

private fun CallTreeNode.toJson(): String =
    "{\"id\":$id,\"parentId\":${parentId ?: "null"},\"depth\":$depth," +
        "\"symbol\":${symbolName.jsonString()},\"file\":${filePath.jsonString()}," +
        "\"inclusiveWeight\":$inclusiveWeight,\"exclusiveWeight\":$exclusiveWeight," +
        "\"sampleCount\":$sampleCount,\"threadCount\":$threadCount}"

private fun String.jsonString(): String =
    buildString {
        append('"')
        this@jsonString.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else ->
                    if (character.code < CONTROL_CHARACTER_LIMIT) {
                        append("\\u%04x".format(character.code))
                    } else {
                        append(character)
                    }
            }
        }
        append('"')
    }

private fun String.csvEscape(): String = if (any(Char::requiresCsvQuotes)) quoteCsv() else this

private fun String.quoteCsv(): String = "\"${replace("\"", "\"\"")}\""

private fun Char.requiresCsvQuotes(): Boolean = this in CSV_QUOTED_CHARACTERS

private fun Path.writeText(content: String) {
    parent?.createDirectories()
    Files.writeString(this, content, StandardCharsets.UTF_8)
}

private const val CONTROL_CHARACTER_LIMIT = 0x20
private val CSV_QUOTED_CHARACTERS = setOf(',', '"', '\n', '\r')
