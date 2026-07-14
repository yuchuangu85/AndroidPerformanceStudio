package com.androidperformancestudio.adb

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioResult
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AndroidGetpropParserTest {
    @Test
    fun `parses model abi sdk and android version from one getprop response`() {
        val output =
            """
            [ro.build.version.release]: [15]
            [ro.product.model]: [Pixel 9 Pro]
            [ro.product.cpu.abilist]: [arm64-v8a,armeabi-v7a,armeabi]
            [ro.build.version.sdk]: [35]
            """.trimIndent()

        val result = AndroidGetpropParser().parse("serial-1", output)

        assertEquals(
            AndroidDeviceProperties(
                serial = "serial-1",
                model = "Pixel 9 Pro",
                abis = listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
                sdkInt = 35,
                androidVersion = "15",
            ),
            assertIs<StudioResult.Success<AndroidDeviceProperties>>(result).value,
        )
    }

    @Test
    fun `falls back to legacy primary abi when abi list is unavailable`() {
        val output =
            """
            [ro.product.model]: [Legacy Phone]
            [ro.product.cpu.abi]: [armeabi-v7a]
            [ro.build.version.sdk]: [21]
            [ro.build.version.release]: [5.0]
            """.trimIndent()

        val result = AndroidGetpropParser().parse("legacy-1", output)

        assertEquals(
            listOf("armeabi-v7a"),
            assertIs<StudioResult.Success<AndroidDeviceProperties>>(result).value.abis,
        )
    }

    @Test
    fun `rejects incomplete or invalid required properties`() {
        val output =
            """
            [ro.product.model]: [Broken Phone]
            [ro.product.cpu.abilist]: [arm64-v8a]
            [ro.build.version.sdk]: [not-a-number]
            """.trimIndent()

        val result = assertIs<StudioResult.Failure>(AndroidGetpropParser().parse("broken-1", output))

        assertEquals(ErrorCategory.DATA_VALIDATION, result.error.category)
        assertEquals("ADB_DEVICE_PROPERTIES_INVALID", result.error.code)
        assertContains(result.error.message, "ro.build.version.sdk")
        assertContains(result.error.message, "ro.build.version.release")
    }
}
