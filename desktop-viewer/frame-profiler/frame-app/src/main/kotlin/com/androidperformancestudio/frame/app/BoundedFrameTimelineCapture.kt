package com.androidperformancestudio.frame.app

import com.androidperformancestudio.adb.SystemAdbLocator
import com.androidperformancestudio.frame.presentation.FrameProcessOption
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.platform.adb.AdbException
import com.androidperformancestudio.platform.adb.DefaultAdbClient
import com.androidperformancestudio.platform.perfetto.PerfettoCaptureDocument
import com.androidperformancestudio.platform.perfetto.PerfettoConfigComposer
import com.androidperformancestudio.platform.perfetto.PerfettoDataSource
import com.androidperformancestudio.platform.toolchain.SystemHostPlatformDetector
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal data class BoundedFrameTimelineCapture(
    val traceFile: Path,
    val startedAt: Instant,
    val androidApiLevel: Int,
)

internal fun interface BoundedFrameTimelineCaptureBackend {
    suspend fun capture(
        serial: String,
        process: FrameProcessOption,
        durationMillis: Long,
    ): FrameBackendResult<BoundedFrameTimelineCapture>
}

/** Captures Android 12+ FrameTimeline evidence without changing the Live Frame Observation path. */
internal class DesktopBoundedFrameTimelineCaptureBackend(
    private val adbLocator: () -> Path? = ::locateSystemAdb,
    private val adbClientFactory: (Path) -> AdbClient = ::DefaultAdbClient,
    private val captureRoot: Path = defaultCaptureRoot(),
) : BoundedFrameTimelineCaptureBackend {
    @Suppress("LongMethod", "ReturnCount")
    override suspend fun capture(
        serial: String,
        process: FrameProcessOption,
        durationMillis: Long,
    ): FrameBackendResult<BoundedFrameTimelineCapture> {
        require(durationMillis > 0) { "FrameTimeline capture duration must be positive." }
        require(process.pid > 0) { "FrameTimeline capture requires a positive process id." }
        val adb = adbLocator() ?: return FrameBackendResult.Failure(MISSING_ADB_MESSAGE)
        val client = adbClientFactory(adb)
        val sessionId = UUID.randomUUID().toString()
        val sessionDirectory = captureRoot.resolve(sessionId)
        val localConfig = sessionDirectory.resolve("frame-timeline.pbtxt")
        val localTrace = sessionDirectory.resolve("frame-timeline.pftrace")
        val remoteConfig = "/data/local/tmp/aps-frame-$sessionId.pbtxt"
        val remoteTrace = "/data/misc/perfetto-traces/aps-frame-$sessionId.pftrace"
        return try {
            val apiLevel =
                client
                    .shell(serial, listOf("getprop", "ro.build.version.sdk"), 5.seconds)
                    .stdout
                    .trim()
                    .toIntOrNull()
                    ?: return FrameBackendResult.Failure("Unable to read the device Android API level.")
            if (apiLevel < MINIMUM_FRAME_TIMELINE_API) {
                return FrameBackendResult.Failure(
                    "Bounded FrameTimeline Capture requires Android 12 (API $MINIMUM_FRAME_TIMELINE_API) or newer; " +
                        "the selected device is API $apiLevel.",
                )
            }

            Files.createDirectories(sessionDirectory)
            Files.writeString(localConfig, frameTimelineConfig(durationMillis))
            client.push(serial, localConfig, remoteConfig)
            val startedAt = Instant.now()
            client.shell(
                serial,
                listOf(
                    "sh",
                    "-c",
                    "cat $remoteConfig | perfetto --txt -c - -o $remoteTrace",
                ),
                durationMillis.milliseconds + CAPTURE_COMPLETION_GRACE,
            )
            client.pull(serial, remoteTrace, localTrace)
            if (!Files.isRegularFile(localTrace) || Files.size(localTrace) == 0L) {
                FrameBackendResult.Failure("Perfetto completed without producing FrameTimeline evidence.")
            } else {
                FrameBackendResult.Success(BoundedFrameTimelineCapture(localTrace, startedAt, apiLevel))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: AdbException) {
            FrameBackendResult.Failure(error.message ?: "ADB could not capture FrameTimeline evidence.")
        } catch (error: IOException) {
            FrameBackendResult.Failure(error.message ?: "FrameTimeline evidence could not be stored.")
        } finally {
            runCatching {
                client.shell(serial, listOf("rm", "-f", remoteConfig, remoteTrace), 5.seconds)
            }
        }
    }

    private fun frameTimelineConfig(durationMillis: Long): String =
        PerfettoConfigComposer.compose(
            PerfettoCaptureDocument(
                durationMillis = durationMillis,
                bufferSizeKb = FRAME_TIMELINE_BUFFER_SIZE_KB,
                dataSources = listOf(PerfettoDataSource("android.surfaceflinger.frametimeline")),
            ),
        )

    private companion object {
        const val MINIMUM_FRAME_TIMELINE_API = 31
        const val FRAME_TIMELINE_BUFFER_SIZE_KB = 32 * 1_024
        val CAPTURE_COMPLETION_GRACE = 30.seconds
        const val MISSING_ADB_MESSAGE =
            "Android SDK Platform Tools were not found. Configure ANDROID_HOME or ANDROID_SDK_ROOT."

        fun defaultCaptureRoot(): Path =
            Path.of(System.getProperty("user.home"), ".android-performance-studio", "frame-profiler", "captures")

        fun locateSystemAdb(): Path? {
            val platform = (SystemHostPlatformDetector().detect() as? StudioResult.Success)?.value ?: return null
            return (SystemAdbLocator(platform).locate() as? StudioResult.Success)?.value?.executable
        }
    }
}
