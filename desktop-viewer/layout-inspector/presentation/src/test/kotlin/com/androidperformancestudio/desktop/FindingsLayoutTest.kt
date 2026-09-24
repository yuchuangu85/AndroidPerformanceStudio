package com.androidperformancestudio.desktop

import com.androidperformancestudio.presentation.generated.resources.Res
import com.androidperformancestudio.presentation.generated.resources.finding_column_details
import com.androidperformancestudio.presentation.generated.resources.finding_severity_warning
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class FindingsLayoutTest {
    @Test
    fun `finding rows use compact typography to show more content`() {
        assertEquals(2f, FindingsLayoutTokens.VERTICAL_PADDING_DP)
        assertEquals(100f, FindingsLayoutTokens.SEVERITY_COLUMN_WIDTH_DP)
        assertEquals(96f, FindingsLayoutTokens.NODE_COLUMN_WIDTH_DP)
    }

    @Test
    fun `findings table labels and severity are localized without claiming description is evidence`() {
        assertEquals("DETAILS", localizedStringResource(Res.string.finding_column_details, UiLanguage.ENGLISH))
        assertEquals("描述", localizedStringResource(Res.string.finding_column_details, UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals("Warning", localizedStringResource(Res.string.finding_severity_warning, UiLanguage.ENGLISH))
        assertEquals("警告", localizedStringResource(Res.string.finding_severity_warning, UiLanguage.SIMPLIFIED_CHINESE))
    }

    @Test
    fun `default findings height exposes evidence in the commercial workspace`() {
        assertEquals(240f, FindingsLayout.DEFAULT_HEIGHT_DP)
        assertEquals(240f, FindingsLayout.fit(FindingsLayout.DEFAULT_HEIGHT_DP, 800f))
    }

    @Test
    fun `dragging upward grows findings and dragging downward shrinks it`() {
        assertEquals(280f, FindingsLayout.drag(240f, deltaDp = -40f, availableHeightDp = 800f))
        assertEquals(200f, FindingsLayout.drag(240f, deltaDp = 40f, availableHeightDp = 800f))
    }

    @Test
    fun `height clamps to minimum and half the available content`() {
        assertEquals(56f, FindingsLayout.drag(240f, deltaDp = 1_000f, availableHeightDp = 600f))
        assertEquals(300f, FindingsLayout.drag(240f, deltaDp = -1_000f, availableHeightDp = 600f))
    }

    @Test
    fun `fitting remembered height reacts to window shrink`() {
        assertEquals(200f, FindingsLayout.fit(400f, availableHeightDp = 400f))
        assertEquals(56f, FindingsLayout.fit(240f, availableHeightDp = 100f))
    }

    @Test
    fun `temporary window shrink preserves preferred findings height`() {
        val preferred = 260f
        assertEquals(200f, FindingsLayout.fit(preferred, 400f))
        assertEquals(preferred, FindingsLayout.fit(preferred, 800f))

        val workspace = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"))
        assertFalse(workspace.contains("findingsHeightDp = normalizedFindingsHeight"))
    }

    @Test
    fun `table header yields to a visible finding row at compact heights`() {
        assertEquals(
            FindingsLayout.MIN_HEIGHT_DP,
            PanelHeaderLayout.HEIGHT_DP + 1f + FindingsLayoutTokens.MIN_VISIBLE_ROW_HEIGHT_DP,
        )
        assertFalse(FindingsLayout.showTableHeader(56f, hasTimeline = false))
        assertFalse(FindingsLayout.showTableHeader(84f, hasTimeline = false))
        assertTrue(FindingsLayout.showTableHeader(85f, hasTimeline = false))
        assertFalse(FindingsLayout.showTableHeader(114f, hasTimeline = true))
        assertTrue(FindingsLayout.showTableHeader(115f, hasTimeline = true))
    }

    @Test
    fun `timeline strip yields to findings at the minimum panel height`() {
        assertFalse(FindingsLayout.showTimeline(56f, hasTimeline = true))
        assertFalse(FindingsLayout.showTimeline(85f, hasTimeline = true))
        assertTrue(FindingsLayout.showTimeline(86f, hasTimeline = true))
        assertFalse(FindingsLayout.showTimeline(200f, hasTimeline = false))
    }
}
