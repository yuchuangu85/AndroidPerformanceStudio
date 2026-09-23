package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class FindingsLayoutTest {
    @Test
    fun `finding rows use compact typography to show more content`() {
        assertEquals(2f, FindingsLayoutTokens.VERTICAL_PADDING_DP)
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
}
