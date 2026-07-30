package dev.agentperf.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AndroidSdkDirectorySelectionTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `selected SDK directory is returned as a normalized absolute path`() {
        val sdkDirectory = Files.createDirectories(temporaryDirectory.resolve("nested/../android-sdk").normalize())

        assertEquals(
            sdkDirectory.toAbsolutePath().normalize().toString(),
            selectedAndroidSdkDirectoryPath(sdkDirectory.toFile()),
        )
    }

    @Test
    fun `selected regular file is not accepted as an SDK directory`() {
        val adbFile = Files.createFile(temporaryDirectory.resolve("adb"))

        assertNull(selectedAndroidSdkDirectoryPath(adbFile.toFile()))
    }
}
