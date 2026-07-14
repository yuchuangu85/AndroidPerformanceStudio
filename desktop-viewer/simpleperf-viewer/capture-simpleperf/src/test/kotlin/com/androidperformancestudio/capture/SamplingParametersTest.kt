package com.androidperformancestudio.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SamplingParametersTest {
    @Test
    fun `serializes app sampling parameters to stable adb arguments`() {
        val parameters =
            SamplingParameters(
                target = SimpleperfTarget.App("com.example.camera"),
                event = "cpu-clock",
                rate = SamplingRate.Frequency(1000),
                durationSeconds = 10.0,
                callGraph = CallGraphMode.DWARF,
                scope = EventScope.BOTH,
                outputPath = "/data/local/tmp/aps/perf.data",
            )

        val command = SimpleperfRecordCommand("serial-1", "simpleperf", parameters)

        assertEquals(
            listOf(
                "-s",
                "serial-1",
                "shell",
                "simpleperf",
                "record",
                "-e",
                "cpu-clock",
                "-f",
                "1000",
                "--duration",
                "10",
                "-g",
                "--app",
                "com.example.camera",
                "-o",
                "/data/local/tmp/aps/perf.data",
            ),
            command.adbArguments,
        )
    }

    @Test
    fun `serializes period frame pointer kernel scope and thread target`() {
        val parameters =
            SamplingParameters(
                target = SimpleperfTarget.Thread(333),
                event = "cpu-cycles",
                rate = SamplingRate.Period(4096),
                durationSeconds = null,
                callGraph = CallGraphMode.FRAME_POINTER,
                scope = EventScope.KERNEL,
            )

        val arguments = SimpleperfRecordCommand("serial-1", "simpleperf", parameters).adbArguments

        assertEquals(
            listOf(
                "-s",
                "serial-1",
                "shell",
                "simpleperf",
                "record",
                "-e",
                "cpu-cycles:k",
                "-c",
                "4096",
                "--call-graph",
                "fp",
                "-t",
                "333",
                "-o",
                "/data/local/tmp/aps/perf.data",
            ),
            arguments,
        )
    }

    @Test
    fun `validates command tokens at model boundaries`() {
        assertFailsWith<IllegalArgumentException> {
            SamplingParameters(
                target = SimpleperfTarget.ProcessName("surface flinger"),
                event = "cpu clock",
            )
        }
        assertFailsWith<IllegalArgumentException> { SamplingRate.Frequency(0) }
        assertFailsWith<IllegalArgumentException> {
            SamplingParameters(
                target = SimpleperfTarget.SystemWide,
                durationSeconds = -1.0,
            )
        }
    }

    @Test
    fun `renders a copyable command preview from the execution arguments`() {
        val command =
            SimpleperfRecordCommand(
                serial = "serial-1",
                simpleperfPath = "/data/local/tmp/aps/simpleperf",
                parameters =
                    SamplingParameters(
                        target = SimpleperfTarget.Process(321),
                        callGraph = CallGraphMode.NONE,
                    ),
            )

        assertEquals(
            commandOf("adb", command.adbArguments),
            command.preview(),
        )
    }
}
