package com.androidperformancestudio.platform.toolchain

import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.system.exitProcess

object StudioProcessFixture {
    @JvmStatic
    fun main(arguments: Array<String>) {
        when (arguments.firstOrNull()) {
            "flood" -> floodBothStreams()
            "exit" -> exitWithCode(arguments.getOrNull(1)?.toIntOrNull() ?: 1)
            "sleep" -> Thread.sleep(arguments.getOrNull(1)?.toLongOrNull() ?: DEFAULT_SLEEP_MILLIS)
            "write-pid-sleep" -> writePidAndSleep(Path.of(requireNotNull(arguments.getOrNull(1))))
            else -> error("Unknown fixture command")
        }
    }

    private fun floodBothStreams() {
        repeat(FLOOD_LINE_COUNT) { index ->
            System.out.println("stdout-$index-${"x".repeat(FLOOD_PAYLOAD_SIZE)}")
            System.err.println("stderr-$index-${"y".repeat(FLOOD_PAYLOAD_SIZE)}")
        }
    }

    private fun exitWithCode(exitCode: Int) {
        System.out.println("before-exit")
        System.err.println("exit-code-$exitCode")
        exitProcess(exitCode)
    }

    private fun writePidAndSleep(pidFile: Path) {
        // Model Windows file visibility: creation can be observed before the writer publishes all bytes.
        pidFile.writeText("")
        Thread.sleep(PID_FILE_VISIBILITY_DELAY_MILLIS)
        pidFile.writeText(ProcessHandle.current().pid().toString())
        Thread.sleep(DEFAULT_SLEEP_MILLIS)
    }

    private const val FLOOD_LINE_COUNT = 5_000
    private const val FLOOD_PAYLOAD_SIZE = 128
    private const val PID_FILE_VISIBILITY_DELAY_MILLIS = 100L
    private const val DEFAULT_SLEEP_MILLIS = 30_000L
}
