package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BundledDeviceSimpleperfAssetsTest {
    @Test
    fun `loads deployable simpleperf assets for supported Android ABIs`() {
        val extractionRoot = Files.createTempDirectory("aps-bundled-simpleperf-")

        val assets = loadBundledDeviceSimpleperfAssets(extractionRoot).associateBy { it.abi }

        assertEquals(setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"), assets.keys)
        assets.forEach { (abi, asset) ->
            assertTrue(Files.isRegularFile(asset.executable), "$abi executable wasn't extracted")
            assertContentEquals(ELF_MAGIC, Files.readAllBytes(asset.executable).copyOf(ELF_MAGIC.size))
            assertEquals(asset.sha256, sha256(asset.executable))
        }
    }
}

private fun sha256(path: java.nio.file.Path): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(Files.readAllBytes(path))
        .joinToString("") { byte -> "%02x".format(byte) }

private val ELF_MAGIC = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
