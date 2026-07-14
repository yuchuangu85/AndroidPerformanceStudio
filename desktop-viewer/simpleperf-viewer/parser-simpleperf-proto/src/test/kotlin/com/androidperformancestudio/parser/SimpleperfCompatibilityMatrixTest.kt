package com.androidperformancestudio.parser

import com.android.tools.profiler.proto.SimpleperfReport
import com.android.tools.profiler.proto.SimpleperfReport.Sample.CallChainEntry.ExecutionType
import com.androidperformancestudio.model.NormalizedProfileRecord
import com.androidperformancestudio.model.ProfileExecutionType
import com.androidperformancestudio.model.StudioResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SimpleperfCompatibilityMatrixTest {
    @Test
    fun `streams and normalizes representative Android and ABI matrix`() {
        compatibilityMatrix.forEach { fixture ->
            val normalized = mutableListOf<NormalizedProfileRecord>()
            val normalizer = SimpleperfProfileNormalizer()

            val result =
                SimpleperfRecordReader().read(ByteArrayInputStream(fixture.toProtobufStream())) { envelope ->
                    normalized += normalizer.normalize(envelope.record)
                }

            val summary = assertIs<StudioResult.Success<SimpleperfReadSummary>>(result).value
            val metadata = assertIs<NormalizedProfileRecord.Metadata>(normalized[0]).value
            val file = assertIs<NormalizedProfileRecord.File>(normalized[1]).value
            val sample = assertIs<NormalizedProfileRecord.Sample>(normalized[3]).value
            assertEquals(4, summary.recordCount, fixture.name)
            assertEquals(fixture.androidSdk.toString(), metadata.androidSdkVersion, fixture.name)
            assertEquals(fixture.libraryPath, file.path, fixture.name)
            assertEquals(fixture.executionType, sample.frames.single().executionType, fixture.name)
            assertEquals("hot_${fixture.abi}", sample.frames.single().symbolName, fixture.name)
        }
    }
}

private data class CompatibilityFixture(
    val name: String,
    val androidSdk: Int,
    val abi: String,
    val libraryPath: String,
    val protoExecutionType: ExecutionType,
    val executionType: ProfileExecutionType,
)

private fun CompatibilityFixture.toProtobufStream(): ByteArray {
    val records = listOf(metadataRecord(), fileRecord(), threadRecord(), sampleRecord())
    return ByteArrayOutputStream()
        .apply {
            write("SIMPLEPERF".encodeToByteArray())
            writeLittleEndian16(1)
            records.forEach { record ->
                val bytes = record.toByteArray()
                writeLittleEndian32(bytes.size)
                write(bytes)
            }
            writeLittleEndian32(0)
        }.toByteArray()
}

private fun CompatibilityFixture.metadataRecord(): SimpleperfReport.Record =
    SimpleperfReport.Record
        .newBuilder()
        .setMetaInfo(
            SimpleperfReport.MetaInfo
                .newBuilder()
                .addEventType("cpu-cycles")
                .setAndroidSdkVersion(androidSdk.toString())
                .setAndroidBuildType("user")
                .setAppType("profileable"),
        ).build()

private fun CompatibilityFixture.fileRecord(): SimpleperfReport.Record =
    SimpleperfReport.Record
        .newBuilder()
        .setFile(
            SimpleperfReport.File
                .newBuilder()
                .setId(1)
                .setPath(libraryPath)
                .addSymbol("hot_$abi"),
        ).build()

private fun threadRecord(): SimpleperfReport.Record =
    SimpleperfReport.Record
        .newBuilder()
        .setThread(
            SimpleperfReport.Thread
                .newBuilder()
                .setProcessId(100)
                .setThreadId(101)
                .setThreadName("RenderThread"),
        ).build()

private fun CompatibilityFixture.sampleRecord(): SimpleperfReport.Record =
    SimpleperfReport.Record
        .newBuilder()
        .setSample(
            SimpleperfReport.Sample
                .newBuilder()
                .setTime(1_000)
                .setThreadId(101)
                .setEventTypeId(0)
                .setEventCount(10)
                .addCallchain(
                    SimpleperfReport.Sample.CallChainEntry
                        .newBuilder()
                        .setFileId(1)
                        .setSymbolId(0)
                        .setVaddrInFile(0x100)
                        .setExecutionType(protoExecutionType),
                ),
        ).build()

private fun ByteArrayOutputStream.writeLittleEndian16(value: Int) {
    write(value and 0xff)
    write(value ushr 8 and 0xff)
}

private fun ByteArrayOutputStream.writeLittleEndian32(value: Int) {
    repeat(Int.SIZE_BYTES) { shift -> write(value ushr (shift * Byte.SIZE_BITS) and 0xff) }
}

private val compatibilityMatrix =
    listOf(
        CompatibilityFixture(
            name = "Android 10 arm64-v8a",
            androidSdk = 29,
            abi = "arm64-v8a",
            libraryPath = "/system/lib64/libui.so",
            protoExecutionType = ExecutionType.NATIVE_METHOD,
            executionType = ProfileExecutionType.NATIVE,
        ),
        CompatibilityFixture(
            name = "Android 12 armeabi-v7a",
            androidSdk = 31,
            abi = "armeabi-v7a",
            libraryPath = "/system/lib/libui.so",
            protoExecutionType = ExecutionType.INTERPRETED_JVM_METHOD,
            executionType = ProfileExecutionType.INTERPRETED_JVM,
        ),
        CompatibilityFixture(
            name = "Android 14 x86_64",
            androidSdk = 34,
            abi = "x86_64",
            libraryPath = "/apex/com.android.runtime/lib64/bionic/libc.so",
            protoExecutionType = ExecutionType.JIT_JVM_METHOD,
            executionType = ProfileExecutionType.JIT_JVM,
        ),
        CompatibilityFixture(
            name = "Android 16 arm64-v8a",
            androidSdk = 36,
            abi = "arm64-v8a",
            libraryPath = "/data/app/com.example/base.apk",
            protoExecutionType = ExecutionType.ART_METHOD,
            executionType = ProfileExecutionType.ART,
        ),
    )
