package com.androidperformancestudio.storage

import kotlin.test.Test
import kotlin.test.assertEquals

class FirefoxSymbolDisplayTest {
    @Test
    fun `opaque vendor hashes fall back to Firefox file and offset labels`() {
        assertEquals(
            "libGLESv2_adreno.so+0x20",
            firefoxCompatibleSymbolName(
                symbolName = "!!!0000!28254c066fd778faffa7894b1bd8b1!0c393b63cf!",
                filePath = "/vendor/lib64/egl/libGLESv2_adreno.so",
                virtualAddress = 0x20,
            ),
        )
    }

    @Test
    fun `normal and near match symbols remain unchanged`() {
        assertEquals("eglSwapBuffers", firefoxCompatibleSymbolName("eglSwapBuffers", "/vendor/libEGL.so", 1))
        assertEquals("!!!not-a-hash!", firefoxCompatibleSymbolName("!!!not-a-hash!", "/vendor/libEGL.so", 1))
    }
}
