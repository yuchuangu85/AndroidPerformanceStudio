@file:Suppress("CyclomaticComplexMethod", "LongParameterList", "ThrowsCount", "MagicNumber", "MaxLineLength")

package com.androidperformancestudio.application

import com.androidperformancestudio.platform.toolchain.NativeBinaryQuery
import com.androidperformancestudio.profileanalysis.CallStackDirection
import com.androidperformancestudio.profileanalysis.CallStackWeightType
import java.io.IOException
import java.nio.file.Path
import java.util.Collections

data class RustCpuProjectionQuery(
    val threadIds: Set<Int> = emptySet(),
    val eventTypes: Set<String> = emptySet(),
    val startNanosInclusive: Long? = null,
    val endNanosExclusive: Long? = null,
    val direction: CallStackDirection = CallStackDirection.FORWARD,
    val weightType: CallStackWeightType = CallStackWeightType.SAMPLES,
) {
    init {
        require(threadIds.all { it >= 0 })
        require(eventTypes.all { it.isNotBlank() && it != "-" && ',' !in it && '\n' !in it && '\r' !in it })
        require(startNanosInclusive == null || startNanosInclusive >= 0)
        require(endNanosExclusive == null || endNanosExclusive >= 0)
        require(startNanosInclusive == null || endNanosExclusive == null || startNanosInclusive < endNanosExclusive)
        require(weightType == CallStackWeightType.SAMPLES || weightType == CallStackWeightType.EVENTS) {
            "Native projection does not yet support traced-time weights"
        }
    }
}

/** IDs are deliberately native-namespace IDs, not Kotlin FlameCallNodeIds. */
data class RustCpuCallNode(
    val id: String,
    val parentId: String?,
    val depth: Int,
    val name: String,
    val resource: String,
    val inclusiveWeight: Long,
    val selfWeight: Long,
    val sampleCount: Long,
    val selfSampleCount: Long,
)

data class RustCpuProjectionPage(
    val sourceDigest: String,
    val query: RustCpuProjectionQuery,
    val total: Long,
    val offset: Long,
    val rows: List<RustCpuCallNode>,
)

class RustCpuProjectionClient(
    private val worker: NativeBinaryQuery = NativeBinaryQuery(),
) {
    suspend fun page(
        executable: Path,
        source: Path,
        sourceDigest: String,
        query: RustCpuProjectionQuery,
        offset: Long = 0,
        limit: Int = 256,
        isCancellationRequested: () -> Boolean = { false },
    ): RustCpuProjectionPage {
        require(offset >= 0 && limit in 1..512)
        val frozenQuery =
            query.copy(
                threadIds = Collections.unmodifiableSet(query.threadIds.toSet()),
                eventTypes = Collections.unmodifiableSet(query.eventTypes.toSet()),
            )
        val direction = if (frozenQuery.direction == CallStackDirection.FORWARD) "forward" else "inverted"
        val weight = if (frozenQuery.weightType == CallStackWeightType.SAMPLES) "samples" else "events"
        val payload =
            worker.execute(
                executable,
                "query",
                source,
                sourceDigest,
                listOf(
                    frozenQuery.threadIds
                        .sorted()
                        .joinToString(",")
                        .ifEmpty { "-" },
                    frozenQuery.eventTypes
                        .sorted()
                        .joinToString(",")
                        .ifEmpty { "-" },
                    frozenQuery.startNanosInclusive?.toString() ?: "-",
                    frozenQuery.endNanosExclusive?.toString() ?: "-",
                    direction,
                    weight,
                    offset.toString(),
                    limit.toString(),
                ),
                isCancellationRequested,
            )
        validatePageMode(payload, direction, weight, offset)
        val total = payload.nonnegative("total")
        val rows = payload.rows()
        val expectedCount = minOf((total - offset).coerceAtLeast(0), limit.toLong()).toInt()
        if (rows.size != expectedCount) throw IOException("Incomplete native projection page")
        val decoded = rows.map(::decodeNode)
        if (decoded.map { it.id }.toSet().size != decoded.size) throw IOException("Duplicate native call IDs")
        return RustCpuProjectionPage(sourceDigest, frozenQuery, total, offset, Collections.unmodifiableList(decoded))
    }
}

private fun validatePageMode(payload: Map<*, *>, direction: String, weight: String, offset: Long) {
    if (payload["direction"] != direction || payload["weightType"] != weight) throw IOException("Native projection mode mismatch")
    if (payload.nonnegative("offset") != offset) throw IOException("Native projection offset mismatch")
}

private fun Map<*, *>.rows(): List<*> = this["rows"] as? List<*> ?: throw IOException("Invalid native projection rows")

private fun decodeNode(value: Any?): RustCpuCallNode {
    val row = value as? Map<*, *> ?: throw IOException("Invalid native call node")
    if (!row.containsKey("parentId")) throw IOException("Missing native call ancestry")
    val id = row.identity("id")
    val parent = row["parentId"]?.let { row.identity("parentId") }
    val depth = row.nonnegative("depth")
    if (depth > MAX_NATIVE_DEPTH || (depth == 0L) != (parent == null) || parent == id) throw IOException("Invalid native call ancestry")
    val inclusive = row.nonnegative("inclusiveWeight")
    val self = row.nonnegative("selfWeight")
    val count = row.nonnegative("sampleCount")
    val selfCount = row.nonnegative("selfSampleCount")
    if (self > inclusive || selfCount > count) throw IOException("Invalid native call metrics")
    return RustCpuCallNode(id, parent, depth.toInt(), row.string("name"), row.string("resource"), inclusive, self, count, selfCount)
}

private const val MAX_NATIVE_DEPTH = 512

private fun Map<*, *>.nonnegative(key: String): Long =
    (this[key] as? Long)?.takeIf { it >= 0 } ?: throw IOException("Invalid native metric: $key")

private fun Map<*, *>.string(key: String): String = this[key] as? String ?: throw IOException("Invalid native field: $key")

private fun Map<*, *>.identity(key: String): String =
    string(key).takeIf { it.isNotBlank() && it.length <= 64 * 1024 }
        ?: throw IOException("Invalid native identity: $key")
