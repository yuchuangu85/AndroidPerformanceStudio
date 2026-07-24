@file:Suppress("MaxLineLength", "ktlint:standard:max-line-length")

package com.androidperformancestudio.battery.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopBatteryBackendTest {
    @Test
    fun `parses package uid shared attribution and launcher`() {
        val targets = parsePackageUidList("package:a.one uid:10123\npackage:b.two uid:10123\npackage:c.three uid:10124", "a.one/.Main\n")
        assertEquals(3, targets.size)
        assertTrue(targets.first { it.packageName == "a.one" }.sharedUid)
        assertEquals("a.one/.Main", targets.first { it.packageName == "a.one" }.launcherComponent)
    }
}
