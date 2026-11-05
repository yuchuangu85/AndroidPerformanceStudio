@file:Suppress("MagicNumber", "TooManyFunctions")

package com.androidperformancestudio.memory.app

import com.androidperformancestudio.memory.analysis.FieldValue
import com.androidperformancestudio.memory.analysis.InstanceQueryDetail
import com.androidperformancestudio.memory.analysis.InstanceQueryRow
import com.androidperformancestudio.memory.analysis.ProguardMapping
import com.androidperformancestudio.memory.model.ActivityLeakEntry
import com.androidperformancestudio.memory.model.BitmapInstanceStats
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapHistogram
import com.androidperformancestudio.memory.model.HeapSegment
import com.androidperformancestudio.memory.model.HeapSummary
import com.androidperformancestudio.memory.model.HeapValueKind
import com.androidperformancestudio.memory.model.LeakSuspect
import com.androidperformancestudio.memory.model.MemoryWarning
import com.androidperformancestudio.memory.model.ObjectReference
import com.androidperformancestudio.memory.presentation.MemoryDominatorRow
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal class RustHeapSnapshotSource private constructor(
    private val process: RustHprofProcess,
    override val metadata: HeapSourceMetadata,
) : HeapSnapshotSource {
    override val query: HeapSnapshotQuery =
        object : HeapSnapshotQuery {
            override fun identity(objectId: Long): HeapObjectIdentity? =
                decode("identity", mapOf("objectId" to objectId)) { value ->
                    value?.let {
                        val row = WireObject(it)
                        val kind = row.string("kind")
                        if (kind !in setOf("INSTANCE", "OBJECT_ARRAY", "PRIMITIVE_ARRAY", "CLASS")) {
                            throw IOException("Invalid heap object kind: $kind")
                        }
                        HeapObjectIdentity(
                            row.string("className"),
                            row.string("groupingClassName"),
                            row.optionalString("heapName"),
                            row.long("shallowSize"),
                            kind,
                        )
                    }
                }

            override fun instancesPage(
                classNames: List<String>,
                heapName: String?,
                offset: Int,
                limit: Int,
                ordinaryOnly: Boolean,
                anchorObjectId: Long?,
            ): HeapInstancePage {
                validatePage(offset, limit)
                return decode(
                    "instances",
                    mapOf(
                        "classNames" to classNames,
                        "heapName" to heapName,
                        "offset" to offset,
                        "limit" to limit,
                        "ordinaryOnly" to ordinaryOnly,
                        "anchorObjectId" to anchorObjectId,
                    ),
                ) { value ->
                    val page = WireObject(value)
                    val rows = page.objects("rows", ::instance)
                    val total = page.count("total")
                    val actualOffset = page.count("offset")
                    if (rows.size > limit || rows.size.toLong() + actualOffset > maxOf(total, actualOffset).toLong()) {
                        throw IOException("Invalid Rust HPROF instance page bounds")
                    }
                    HeapInstancePage(total, rows, actualOffset)
                }
            }

            override fun detailOf(
                objectId: Long,
                arrayStart: Int,
                arrayLimit: Int,
            ): InstanceQueryDetail? {
                require(arrayStart >= 0 && arrayLimit in 1..200) { "Invalid Rust HPROF array page" }
                return decode(
                    "detail",
                    mapOf("objectId" to objectId, "arrayStart" to arrayStart, "arrayLimit" to arrayLimit),
                ) { value ->
                    value?.let {
                        detail(WireObject(it)).also { result ->
                            if (result.objectId != objectId) throw IOException("Rust HPROF detail object id mismatch")
                        }
                    }
                }
            }
        }

    override fun histogram(heapName: String?): HeapHistogram = histogram("histogram", heapName)

    fun rawHistogram(heapName: String? = null): HeapHistogram = histogram("rawHistogram", heapName)

    private fun histogram(
        op: String,
        heapName: String?,
    ): HeapHistogram =
        decode(op, mapOf("heapName" to heapName)) { value ->
            val histogram = WireObject(value)
            val summary = histogram.obj("summary")
            HeapHistogram(
                summary =
                    HeapSummary(
                        objectCount = summary.count("objectCount"),
                        shallowSize = summary.long("shallowSize"),
                        classCount = summary.count("classCount"),
                        heapSegments =
                            summary.objects("heapSegments") {
                                HeapSegment(it.string("name"), it.long("sizeBytes"), it.count("objectCount"))
                            },
                    ),
                classes = histogram.objects("classes", ::classStats),
            )
        }

    override fun dominatorsPage(
        offset: Int,
        limit: Int,
        parentObjectId: Long?,
    ): HeapDominatorPage {
        validatePage(offset, limit)
        return decode(
            "dominators",
            mapOf("offset" to offset, "limit" to limit, "parentObjectId" to parentObjectId),
        ) { value ->
            val page = WireObject(value)
            val rows =
                page.objects("rows") {
                    MemoryDominatorRow(
                        it.long("objectId"),
                        it.string("className"),
                        it.long("shallowSize"),
                        it.long("retainedSize"),
                        it.count("depth"),
                        it.optionalLong("parentObjectId"),
                    )
                }
            val total = page.count("total")
            if (rows.size > limit || rows.size.toLong() + offset > maxOf(total, offset).toLong()) {
                throw IOException("Invalid Rust HPROF dominator page bounds")
            }
            HeapDominatorPage(total, rows)
        }
    }

    override fun findings(): HeapFindings =
        decode("findings") { value ->
            val findings = WireObject(value)
            HeapFindings(
                leakSuspects =
                    findings.objects("leakSuspects") {
                        LeakSuspect(
                            className = it.string("className"),
                            reason = it.string("reason"),
                            retainedSize = it.optionalLong("retainedSize"),
                            instanceCount = it.count("instanceCount"),
                            referenceChain = it.objects("referenceChain", ::reference),
                            // v1 has no floating-point tag; the engine currently emits integer confidence (0).
                            confidence = it.optionalLong("confidence")?.toFloat() ?: 0f,
                            requiresManualVerification = it.boolean("requiresManualVerification"),
                            activityOrFragmentLeak = it.boolean("activityOrFragmentLeak"),
                        )
                    },
                bitmapInstances =
                    findings.objects("bitmapInstances") {
                        BitmapInstanceStats(
                            objectId = it.long("objectId"),
                            width = it.optionalInt("width"),
                            height = it.optionalInt("height"),
                            retainedSize = it.long("retainedSize"),
                            referenceChain = it.objects("referenceChain", ::reference),
                            estimatedPixelBytes = it.optionalLong("estimatedPixelBytes"),
                            javaSizeBytes = it.long("javaSizeBytes"),
                            nativeSizeBytes = it.optionalLong("nativeSizeBytes"),
                            className = it.string("className"),
                            bitmapId = it.optionalLong("bitmapId"),
                            bitmapSourceId = it.optionalLong("bitmapSourceId"),
                        )
                    },
                activityLeaks =
                    findings.objects("activityLeaks") {
                        ActivityLeakEntry(
                            it.string("className"),
                            it.count("liveInstanceCount"),
                            it.count("destroyedInstanceCount"),
                            it.optionalLong("retainedSize"),
                            it.objects("referenceChain", ::reference),
                        )
                    },
                lifecycleLeakObjects =
                    findings.objects("lifecycleLeakObjects") {
                        HeapLifecycleObject(it.long("objectId"), it.string("className"), it.optionalString("heapName"))
                    },
            )
        }

    private fun <T> decode(
        op: String,
        args: Map<String, Any?> = emptyMap(),
        decode: (Any?) -> T,
    ): T {
        val value = process.request(op, args)
        return try {
            decode(value)
        } catch (error: IOException) {
            throw process.invalidate(error)
        }
    }

    override fun close() = process.close()

    companion object {
        fun open(
            executable: Path,
            source: Path,
            sourceDigest: String,
            mapping: ProguardMapping? = null,
            cacheDirectory: Path? = null,
            graphStorage: String = "memory",
            graphDirectory: Path? = null,
        ): RustHeapSnapshotSource {
            val resolved = source.toRealPath()
            require(Files.isRegularFile(resolved) && Files.isReadable(resolved)) {
                "Rust HPROF source is not a readable regular file: $resolved"
            }
            val process = RustHprofProcess.start(executable, sourceDigest)
            return open(process, resolved, mapping, cacheDirectory, graphStorage, graphDirectory)
        }

        /** Shared with stream-based transport tests; the caller transfers child ownership. */
        @Suppress("TooGenericExceptionCaught")
        internal fun open(
            process: RustHprofProcess,
            source: Path,
            mapping: ProguardMapping? = null,
            cacheDirectory: Path? = null,
            graphStorage: String = "memory",
            graphDirectory: Path? = null,
        ): RustHeapSnapshotSource =
            try {
                require(graphStorage == "memory" || graphStorage == "disk") { "Invalid Rust HPROF graph storage: $graphStorage" }
                require(graphStorage != "disk" || graphDirectory != null) { "Disk Rust HPROF graph storage requires graphDirectory" }
                val response =
                    process.request(
                        "open",
                        buildMap {
                            put("path", source.toString())
                            put("sourceDigest", process.sourceDigest)
                            put("mapping", mapping?.obfuscatedToOriginal.orEmpty())
                            cacheDirectory?.let { put("cacheDir", it.toString()) }
                            if (graphStorage != "memory") put("graphStorage", graphStorage)
                            graphDirectory?.let { put("graphDir", it.toString()) }
                        },
                    )
                RustHeapSnapshotSource(process, metadata(WireObject(response)))
            } catch (error: Throwable) {
                process.close()
                throw error
            }

        private fun validatePage(
            offset: Int,
            limit: Int,
        ) {
            require(offset >= 0 && limit in 1..4096) { "Invalid Rust HPROF page" }
        }

        private fun metadata(row: WireObject): HeapSourceMetadata {
            val idSize = row.int("idSize")
            if (idSize != 4 && idSize != 8) throw IOException("Invalid Rust HPROF idSize")
            return HeapSourceMetadata(
                row.string("format"),
                idSize,
                row.long("timestampMillis"),
                row.count("classCount"),
                row.count("instanceCount"),
                row.count("objectArrayCount"),
                row.count("primitiveArrayCount"),
                row.count("rootCount"),
                row.objects("warnings") { MemoryWarning(it.string("message"), it.optionalLong("offset")) },
                row.strings("heaps"),
            )
        }

        private fun instance(row: WireObject): InstanceQueryRow =
            InstanceQueryRow(
                objectId = row.long("objectId"),
                index = row.count("index"),
                shallowSize = row.long("shallowSize"),
                retainedSize = row.optionalLong("retainedSize"),
                depth = row.optionalInt("depth"),
                reachable = row.boolean("reachable"),
                nativeSize = row.optionalLong("nativeSize"),
                shallowSizeKnown = row.boolean("shallowSizeKnown"),
                className = row.optionalString("className"),
                representedClassName = row.optionalString("representedClassName"),
            )

        private fun detail(row: WireObject): InstanceQueryDetail =
            InstanceQueryDetail(
                objectId = row.long("objectId"),
                className = row.string("className"),
                shallowSize = row.long("shallowSize"),
                retainedSize = row.optionalLong("retainedSize"),
                depth = row.optionalInt("depth"),
                isArray = row.boolean("isArray"),
                elementCount = row.optionalInt("elementCount"),
                arrayPageSize = row.count("arrayPageSize"),
                fields = row.objects("fields", ::field),
                referenceChain = row.objects("referenceChain", ::reference),
                references = row.objects("references", ::field),
                shallowSizeKnown = row.boolean("shallowSizeKnown"),
                nativeSize = row.optionalLong("nativeSize"),
                valueKind = row.valueKind(),
                representedClassName = row.optionalString("representedClassName"),
                stringPreview = row.optionalString("stringPreview"),
                retainedNativeSize = row.optionalLong("retainedNativeSize"),
            )

        private fun field(row: WireObject): FieldValue =
            FieldValue(
                name = row.string("name"),
                displayValue = row.string("displayValue"),
                targetObjectId = row.optionalLong("targetObjectId"),
                targetClassName = row.optionalString("targetClassName"),
                depth = row.optionalInt("depth"),
                valueKind = row.valueKind(),
                shallowSize = row.optionalLong("shallowSize"),
                retainedSize = row.optionalLong("retainedSize"),
                nativeSize = row.optionalLong("nativeSize"),
                retainedNativeSize = row.optionalLong("retainedNativeSize"),
                representedClassName = row.optionalString("representedClassName"),
                stringPreview = row.optionalString("stringPreview"),
                childCount = row.optionalInt("childCount"),
            )

        private fun reference(row: WireObject): ObjectReference =
            ObjectReference(row.string("fieldName"), row.long("targetObjectId"), row.string("targetClassName"))

        private fun classStats(row: WireObject): ClassStats =
            ClassStats(
                className = row.string("className"),
                instanceCount = row.count("instanceCount"),
                shallowSize = row.long("shallowSize"),
                retainedSize = row.optionalLong("retainedSize"),
                obfuscatedClassName = row.optionalString("obfuscatedClassName"),
                hierarchyDepth = row.optionalInt("hierarchyDepth"),
                nativeSize = row.optionalLong("nativeSize"),
                moduleName = row.optionalString("moduleName"),
                allocations = row.optionalLong("allocations"),
                deallocations = row.optionalLong("deallocations"),
                allocationsSize = row.optionalLong("allocationsSize"),
                deallocationsSize = row.optionalLong("deallocationsSize"),
                shallowSizeChange = row.optionalLong("shallowSizeChange"),
                allocationMethod = row.optionalString("allocationMethod"),
                allocationCallstack = row.optionalStrings("allocationCallstack"),
                totalCount = row.optionalLong("totalCount"),
                heapNames = row.strings("heapNames").toSet(),
                reachableShallowSize = row.optionalLong("reachableShallowSize"),
                reachableNativeSize = row.optionalLong("reachableNativeSize"),
            )
    }
}

/** Strict types and checked i64 -> Int conversion; absent nullable evidence stays null. */
private class WireObject(
    value: Any?,
) {
    private val values = value as? Map<*, *> ?: throw IOException("Expected Rust HPROF object")

    fun long(key: String): Long = values[key] as? Long ?: invalid(key)

    fun int(key: String): Int = checkedInt(key, long(key))

    fun count(key: String): Int = int(key).also { if (it < 0) invalid(key) }

    fun string(key: String): String = values[key] as? String ?: invalid(key)

    fun boolean(key: String): Boolean = values[key] as? Boolean ?: invalid(key)

    fun optionalLong(key: String): Long? = values[key]?.let { it as? Long ?: invalid(key) }

    fun optionalInt(key: String): Int? = optionalLong(key)?.let { checkedInt(key, it) }

    fun optionalString(key: String): String? = values[key]?.let { it as? String ?: invalid(key) }

    fun obj(key: String): WireObject = WireObject(values[key])

    fun <T> objects(
        key: String,
        decode: (WireObject) -> T,
    ): List<T> = list(key).map { decode(WireObject(it)) }

    fun strings(key: String): List<String> = list(key).map { it as? String ?: invalid(key) }

    fun optionalStrings(key: String): List<String> = if (values[key] == null) emptyList() else strings(key)

    fun valueKind(): HeapValueKind {
        val name = string("valueKind")
        return HeapValueKind.entries.firstOrNull { it.name == name } ?: invalid("valueKind")
    }

    private fun list(key: String): List<*> = values[key] as? List<*> ?: invalid(key)

    private fun checkedInt(
        key: String,
        value: Long,
    ): Int {
        if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) invalid(key)
        return value.toInt()
    }

    private fun invalid(key: String): Nothing = throw IOException("Invalid Rust HPROF field '$key'")
}
