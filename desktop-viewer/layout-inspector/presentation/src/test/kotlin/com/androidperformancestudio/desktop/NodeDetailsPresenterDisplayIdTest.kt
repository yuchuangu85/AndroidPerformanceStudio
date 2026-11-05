package com.androidperformancestudio.desktop

import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.ViewNode
import com.androidperformancestudio.ui.UiLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NodeDetailsPresenterDisplayIdTest {
    private fun identityId(node: ViewNode): String {
        val sections = NodeDetailsPresenter.present(
            node = node,
            treeDepth = 1,
            language = UiLanguage.ENGLISH,
        )
        val identity = sections.first { it.title == "IDENTITY" }
        return identity.rows.single { it.label == "ID" }.value
    }

    @Test
    fun `multi window namespace prefix is kept for dump correlation`() {
        // The "window:<hash>" token matches the entry name in the raw
        // `cmd window dump-visible-window-views` ZIP, so the full id is displayed as-is.
        val id = identityId(
            ViewNode(id = "window:9223a26/root/7/1/3/0/0", className = "FrameLayout", bounds = Bounds(0, 0, 10, 10)),
        )

        assertEquals("window:9223a26/root/7/1/3/0/0", id)
    }

    @Test
    fun `plain agent path ids are displayed unchanged`() {
        val id = identityId(
            ViewNode(id = "root/2/5", className = "FrameLayout", bounds = Bounds(0, 0, 10, 10)),
        )

        assertEquals("root/2/5", id)
    }
}
