package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class PresentationResourceClasspathTest {
    @Test
    fun `presentation resource accessors have unique runtime classes`() {
        assertSingleClass("com/androidperformancestudio/presentation/generated/resources/Res.class")
        assertSingleClass("com/androidperformancestudio/simpleperf/presentation/generated/resources/Res.class")
        assertSingleClass("com/androidperformancestudio/presentation/generated/resources/SimpleperfViewerRes.class")

        val accessorClass =
            classLoader.loadClass(
                "com.androidperformancestudio.presentation.generated.resources.SimpleperfViewerRes",
            )
        val accessor = accessorClass.getField("INSTANCE").get(null)
        val samplingTemplate =
            accessorClass
                .getMethod("getSp_settings_sampling_template")
                .invoke(accessor)

        assertNotNull(samplingTemplate)
    }

    private fun assertSingleClass(resourcePath: String) {
        val locations = classLoader.getResources(resourcePath).toList()

        assertEquals(
            1,
            locations.size,
            "Expected one runtime class for $resourcePath, found $locations",
        )
    }

    private companion object {
        val classLoader: ClassLoader = checkNotNull(Thread.currentThread().contextClassLoader)

        fun <T> java.util.Enumeration<T>.toList(): List<T> =
            buildList {
                while (hasMoreElements()) {
                    add(nextElement())
                }
            }
    }
}
