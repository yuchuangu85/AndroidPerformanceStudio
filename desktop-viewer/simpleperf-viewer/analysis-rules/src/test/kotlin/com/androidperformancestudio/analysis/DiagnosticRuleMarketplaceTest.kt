package com.androidperformancestudio.analysis

import com.androidperformancestudio.storage.DataQualitySummary
import com.androidperformancestudio.storage.ProfileOverview
import kotlin.test.Test
import kotlin.test.assertEquals

class DiagnosticRuleMarketplaceTest {
    @Test
    fun `installs replaces and removes rule packs`() {
        val rule = DiagnosticRule { emptyList() }
        val marketplace = DiagnosticRuleMarketplace()
        marketplace.install(DiagnosticRulePack("team.rules", "1", listOf(rule), "file"))
        marketplace.install(DiagnosticRulePack("team.rules", "2", listOf(rule), "file"))
        assertEquals("2", marketplace.installedPacks().single().version)
        assertEquals(0, marketplace.engine().analyze(snapshot()).size)
        assertEquals(true, marketplace.uninstall("team.rules"))
    }

    private fun snapshot() =
        AnalysisSnapshot(
            ProfileOverview(null, null, 0, 0, 0, 0, emptyList()),
            DataQualitySummary(0, 0, 0, 0, 0, 0, 0, emptyList()),
            emptyList(),
            emptyList(),
        )
}
