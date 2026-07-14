package com.androidperformancestudio.desktop

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidSdkSimpleperfTest {
    @Test
    fun `finds host simpleperf from the newest side by side ndk`() {
        val sdk = Files.createTempDirectory("aps-android-sdk-")
        sdk.resolve("ndk/27.1.1/simpleperf/bin/darwin/x86_64/simpleperf").also {
            it.parent.createDirectories()
            it.writeText("older simpleperf")
        }
        val expected =
            sdk.resolve("ndk/28.0.2/simpleperf/bin/darwin/x86_64/simpleperf").also {
                it.parent.createDirectories()
                it.writeText("simpleperf")
            }

        assertEquals(expected, findAndroidNdkSimpleperf(listOf(sdk), osName = "Mac OS X"))
    }
}
