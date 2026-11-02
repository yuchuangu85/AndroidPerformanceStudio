package com.androidperformancestudio.storage

import com.androidperformancestudio.profileanalysis.FlameFunctionId
import com.androidperformancestudio.storage.FirefoxSymbolDisplay.FunctionIdentityIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class FirefoxSymbolDisplayTest {
    @Test
    fun `missing native symbols display the captured binary and unsigned address`() {
        assertEquals(
            "[kernel.kallsyms]+0xfffffffffffffff0",
            firefoxCompatibleSymbolName("<unknown-symbol>", "[kernel.kallsyms]", -16),
        )
        assertEquals("libui.so+0x20", firefoxCompatibleSymbolName("", "/system/lib64/libui.so", 32))
    }

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

    @Test
    fun `function identities are independent of lookup order and Java string hash collisions`() {
        val first = FunctionIdentityIndex()
        val second = FunctionIdentityIndex()
        val loop = first.functionId("android.os.Looper.loopOnce", "boot.oat")
        first.functionId("other", "lib.so")
        second.functionId("earlier", "lib.so")

        assertEquals(loop, second.functionId("android.os.Looper.loopOnce", "boot.oat"))
        assertNotEquals(first.functionId("Aa", "lib.so"), first.functionId("BB", "lib.so"))
        assertNotEquals(loop, first.functionId("android.os.Looper.loopOnce", "other.oat"))
    }

    @Test
    fun `terminal function identity collisions fail instead of query dependent remapping`() {
        val identities = FunctionIdentityIndex { _, _ -> FlameFunctionId(7) }
        assertEquals(FlameFunctionId(7), identities.functionId("first", "lib.so"))
        assertEquals(FlameFunctionId(7), identities.functionId("first", "lib.so"))

        assertFailsWith<IllegalStateException> { identities.functionId("second", "lib.so") }
    }
}
