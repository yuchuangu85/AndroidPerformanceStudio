package com.androidperformancestudio.screencast.session

import com.androidperformancestudio.platform.adb.AdbDeviceState
import com.androidperformancestudio.platform.adb.DefaultAdbClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Real-device smoke test: runs only when an online adb device is present;
 * otherwise it passes vacuously so CI without devices stays green.
 */
class RealDeviceSmokeTest {
    @Test
    fun `mirrors a real device when one is connected`() =
        runBlocking {
            val adbExecutable =
                Path.of(System.getenv("ANDROID_HOME") ?: "/home/ts/Android/Sdk")
                    .resolve("platform-tools/adb")
            val adb = DefaultAdbClient(adbExecutable)
            val serial =
                adb.listDevices()
                    .firstOrNull { it.state == AdbDeviceState.ONLINE }
                    ?.serial
            if (serial == null) {
                println("SKIP: no online device")
                return@runBlocking
            }
            println("Using device: $serial")
            // Exercise the FFmpeg path explicitly: the production app uses
            // FFmpegFrameDecoder via the assembly layer, so the smoke test
            // must cover the same decoder, not the jcodec default.
            val session = ScrcpySession(adb, serial, frameDecoder = FFmpegFrameDecoder())
            session.start()
            var frames = 0
            // Collect frames in a job we cancel explicitly: the shared flow
            // never completes, so a coroutineScope would wait forever.
            val frameCollector = launch {
                session.frames.collect { frames++ }
            }
            withTimeout(30_000) {
                while (true) {
                    val state = session.state.value
                    println("state: $state")
                    when (state) {
                        is ScrcpySessionState.Running -> break
                        is ScrcpySessionState.Failed -> error("Session failed: ${state.message}")
                        else -> delay(500)
                    }
                }
            }
            // Let some frames arrive.
            delay(15_000)
            frameCollector.cancel()
            println("frames received in 15s: $frames")
            // Decoding must produce visible frames on a live device.
            assertTrue(frames > 0, "expected decoded frames within 15s, got $frames")
            session.stop()
            delay(2_000)
            println("final state: ${session.state.value}")
        }
}
