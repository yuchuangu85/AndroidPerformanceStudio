@file:Suppress("MagicNumber", "TooManyFunctions")

package com.androidperformancestudio.fixtures

import com.androidperformancestudio.model.NormalizedProfileRecord
import com.androidperformancestudio.model.NormalizedSample
import com.androidperformancestudio.model.ProfileExecutionType
import com.androidperformancestudio.model.ProfileFile
import com.androidperformancestudio.model.ProfileFrame
import com.androidperformancestudio.model.ProfileMetadata
import com.androidperformancestudio.model.ProfileThread
import com.androidperformancestudio.storage.SQLiteSampleStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

private const val PROCESS_ID = 7_777
private const val MAIN_THREAD_ID = 7_778
private const val RENDER_THREAD_ID = 7_779
private const val UI_FILE_ID = 1
private const val ODEX_FILE_ID = 2
private const val KERNEL_FILE_ID = 3
private const val SOURCELESS_FILE_ID = 4
private val INTENTIONAL_DIFFERENCES = listOf("Compose styling", "Android data terminology")

data class FirefoxCompatibilityRow(
    val id: String,
    val scenario: String,
    val upstreamBehavior: String,
    val androidStudioBehavior: String,
    val intentionalDifferences: List<String> = INTENTIONAL_DIFFERENCES,
)

object FirefoxFlameGraphFixtures {
    const val UPSTREAM_BASELINE_COMMIT = "9dd90d380ee711f209c4dcd89beec244eb6d3654"

    fun compatibilityMatrix(): List<FirefoxCompatibilityRow> =
        listOf(
            row("mixed", "Native and managed Android frames appear in one profile."),
            row("native", "Native shared-library frames remain selectable and transformable."),
            row("managed", "ART/JVM frames use the managed implementation category."),
            row("kernel", "Kernel frames survive projection and implementation filtering."),
            row("recursive", "Recursive callsites project without infinite expansion."),
            row("source-less", "Frames with binaries but no source still open details fallback."),
            row("million-sample", "The projection path is covered by the million-sample P0."),
            row("deep-stack", "Deep stacks remain queryable through the SQLite projection path."),
        )

    fun writeCompatibilitySession(root: Path): Path {
        val session = root.resolve("firefox-flame-compat").createDirectories()
        Files.write(session.resolve("simpleperf.protobuf"), byteArrayOf(0x0A, 0x00))
        session.resolve("session.properties").writeText(
            """
            formatVersion=1
            fixture=firefox-flame-graph-compatibility
            upstreamBaseline=$UPSTREAM_BASELINE_COMMIT
            """.trimIndent() + "\n",
        )
        writeArtifact(session, "symbols/system/lib64/libui.so", "debug symbols for libui fixture\n")
        writeArtifact(session, "binary_cache/system/lib64/source-less.so", "source-less binary fixture\n")
        SQLiteSampleStore.open(session.resolve("profile.sqlite")).use { store ->
            val result = store.importRecords(compatibilityRecords())
            check(result.importedSamples == 8L) { "Expected 8 imported compatibility samples, got $result" }
        }
        return session
    }

    private fun row(
        id: String,
        scenario: String,
    ): FirefoxCompatibilityRow =
        FirefoxCompatibilityRow(
            id = id,
            scenario = scenario,
            upstreamBehavior = "Firefox Profiler flame graph renders and drills into the stack.",
            androidStudioBehavior = "Android Performance Studio renders equivalent stack structure and details.",
        )
}

private fun writeArtifact(
    session: Path,
    relativePath: String,
    contents: String,
) {
    val file = session.resolve(relativePath)
    file.parent.createDirectories()
    file.writeText(contents)
}

private fun compatibilityRecords(): Sequence<NormalizedProfileRecord> = metadataRecords() + sampleRecords()

private fun metadataRecords(): Sequence<NormalizedProfileRecord> =
    sequenceOf(
        metadata(),
        NormalizedProfileRecord.File(
            ProfileFile(UI_FILE_ID, "/system/lib64/libui.so", uiSymbols(), emptyList()),
        ),
        NormalizedProfileRecord.File(
            ProfileFile(ODEX_FILE_ID, "/data/app/base.odex", odexSymbols(), emptyList()),
        ),
        NormalizedProfileRecord.File(
            ProfileFile(KERNEL_FILE_ID, "[kernel.kallsyms]", listOf("schedule"), emptyList()),
        ),
        NormalizedProfileRecord.File(
            ProfileFile(SOURCELESS_FILE_ID, "/system/lib64/source-less.so", sourceLessSymbols(), emptyList()),
        ),
        NormalizedProfileRecord.Thread(ProfileThread(PROCESS_ID, MAIN_THREAD_ID, "main")),
        NormalizedProfileRecord.Thread(ProfileThread(PROCESS_ID, RENDER_THREAD_ID, "RenderThread")),
    )

private fun sampleRecords(): Sequence<NormalizedProfileRecord> =
    sequenceOf(
        sample(1_000_000, MAIN_THREAD_ID, "main", 40, frame(UI_FILE_ID, 1, "renderFrame")),
        sample(2_000_000, MAIN_THREAD_ID, "main", 35, frame(UI_FILE_ID, 1, "renderFrame")),
        sample(3_000_000, RENDER_THREAD_ID, "RenderThread", 55, frame(UI_FILE_ID, 0, "runLoop")),
        sample(
            4_000_000,
            MAIN_THREAD_ID,
            "main",
            25,
            frame(ODEX_FILE_ID, 0, "android.os.Handler.dispatchMessage", ProfileExecutionType.ART),
        ),
        sample(
            5_000_000,
            MAIN_THREAD_ID,
            "main",
            20,
            frame(UI_FILE_ID, 1, "renderFrame"),
            frame(ODEX_FILE_ID, 0, "android.os.Handler.dispatchMessage", ProfileExecutionType.ART),
        ),
        sample(
            6_000_000,
            RENDER_THREAD_ID,
            "RenderThread",
            15,
            frame(KERNEL_FILE_ID, 0, "schedule", ProfileExecutionType.KERNEL),
        ),
        sample(
            7_000_000,
            RENDER_THREAD_ID,
            "RenderThread",
            10,
            frame(UI_FILE_ID, 2, "recursiveNative"),
            frame(UI_FILE_ID, 2, "recursiveNative"),
            frame(UI_FILE_ID, 0, "runLoop"),
        ),
        sample(
            8_000_000,
            MAIN_THREAD_ID,
            "main",
            5,
            frame(SOURCELESS_FILE_ID, 0, "sourceLessNative"),
        ),
    )

private fun metadata(): NormalizedProfileRecord.Metadata =
    NormalizedProfileRecord.Metadata(
        ProfileMetadata(
            eventTypes = listOf("cpu-cycles"),
            appPackageName = "org.mozilla.firefox.fixture",
            appType = "profileable",
            androidSdkVersion = "36",
            androidBuildType = "userdebug",
            traceOffCpu = false,
        ),
    )

private fun uiSymbols(): List<String> = listOf("runLoop", "renderFrame", "recursiveNative")

private fun odexSymbols(): List<String> = listOf("android.os.Handler.dispatchMessage")

private fun sourceLessSymbols(): List<String> = listOf("sourceLessNative")

private fun sample(
    timestampNanos: Long,
    threadId: Int,
    threadName: String,
    eventCount: Long,
    vararg frames: ProfileFrame,
): NormalizedProfileRecord.Sample =
    NormalizedProfileRecord.Sample(
        NormalizedSample(
            timestampNanos = timestampNanos,
            processId = PROCESS_ID,
            threadId = threadId,
            threadName = threadName,
            eventType = "cpu-cycles",
            eventCount = eventCount,
            frames = frames.toList(),
            unwindError = null,
        ),
    )

private fun frame(
    fileId: Int,
    symbolId: Int,
    symbolName: String,
    executionType: ProfileExecutionType = ProfileExecutionType.NATIVE,
): ProfileFrame =
    ProfileFrame(
        virtualAddress = 0x1000L + fileId * 0x100L + symbolId * 0x10L,
        fileId = fileId,
        symbolId = symbolId,
        filePath = filePath(fileId),
        symbolName = symbolName,
        executionType = executionType,
    )

private fun filePath(fileId: Int): String =
    when (fileId) {
        UI_FILE_ID -> "/system/lib64/libui.so"
        ODEX_FILE_ID -> "/data/app/base.odex"
        KERNEL_FILE_ID -> "[kernel.kallsyms]"
        SOURCELESS_FILE_ID -> "/system/lib64/source-less.so"
        else -> error("Unknown fixture file id $fileId")
    }
