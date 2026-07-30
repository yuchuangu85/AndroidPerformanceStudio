package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AdbClasspathCompatibilityTest {
    @Test
    fun `desktop runtime contains one platform adb device ABI and no legacy ABI`() {
        val classLoader = checkNotNull(Thread.currentThread().contextClassLoader)
        val locations = classLoader.resourceLocations("com/androidperformancestudio/platform/adb/AdbDevice.class")
        val legacyLocations = classLoader.resourceLocations("com/androidperformancestudio/adb/AdbDevice.class")

        assertEquals(
            1,
            locations.size,
            "Conflicting AdbDevice classes found: $locations",
        )
        assertEquals(
            emptyList<java.net.URL>(),
            legacyLocations,
            "Legacy AdbDevice classes remain on the runtime classpath: $legacyLocations",
        )

        assertDoesNotThrow {
            val parserClass =
                classLoader.loadClass(
                    "com.androidperformancestudio.platform.adb.AdbDevicesParser",
                )
            val parser = parserClass.getDeclaredConstructor().newInstance()
            parserClass
                .getMethod("parse", String::class.java)
                .invoke(
                    parser,
                    "List of devices attached\nserial-1 device model:Pixel_8 transport_id:1",
                )
        }
    }

    private fun ClassLoader.resourceLocations(name: String): List<java.net.URL> =
        buildList {
            val resources = getResources(name)
            while (resources.hasMoreElements()) add(resources.nextElement())
        }
}
