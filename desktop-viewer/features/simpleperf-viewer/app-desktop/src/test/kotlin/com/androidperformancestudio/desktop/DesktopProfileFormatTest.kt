package com.androidperformancestudio.desktop

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopProfileFormatTest {
    @Test
    fun `classifies every supported home import input`() {
        val root = Files.createTempDirectory("aps-open-format-")
        val session = root.resolve("session").also(Files::createDirectories)
        val archive = root.resolve("profile.apsession.zip").also { it.writeText("zip") }
        val perfData = root.resolve("capture.perf.data").also { it.writeText("perf") }
        val protobuf = root.resolve("capture.perf.trace").also { it.writeText("trace") }
        val unknown = root.resolve("notes.txt").also { it.writeText("text") }

        assertEquals(DesktopProfileFormat.SESSION_DIRECTORY, detectDesktopProfileFormat(session))
        assertEquals(DesktopProfileFormat.SESSION_PACKAGE, detectDesktopProfileFormat(archive))
        assertEquals(DesktopProfileFormat.PERF_DATA, detectDesktopProfileFormat(perfData))
        assertEquals(DesktopProfileFormat.SIMPLEPERF_PROTOBUF, detectDesktopProfileFormat(protobuf))
        assertEquals(DesktopProfileFormat.UNSUPPORTED, detectDesktopProfileFormat(unknown))
    }
}
