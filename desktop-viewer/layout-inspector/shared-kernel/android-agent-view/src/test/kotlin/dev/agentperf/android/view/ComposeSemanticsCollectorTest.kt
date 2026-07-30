package com.androidperformancestudio.android.view

import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.ComposeNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ComposeSemanticsCollectorTest {
    @Test
    fun `collects unmerged compose semantics as protocol nodes without compose dependency`() {
        val owner = FakeSemanticsOwner(
            root = FakeSemanticsNode(
                id = 1,
                rect = FakeRect(0f, 0f, 120f, 80f),
                config = FakeSemanticsConfiguration("TestTag" to "root"),
                children = listOf(
                    FakeSemanticsNode(
                        id = 2,
                        rect = FakeRect(10f, 20f, 110f, 60f),
                        config = FakeSemanticsConfiguration(
                            "Role" to "Button",
                            "Text" to listOf("Save"),
                        ),
                    ),
                ),
            ),
        )
        val composeView = FakeComposeView(owner)

        val node = ComposeSemanticsCollector.collect(
            composeView = composeView,
            path = "window:main/root/0/compose",
        )

        requireNotNull(node)
        assertEquals("window:main/root/0/compose/1", node.id)
        assertEquals("ComposeSemantics", node.className)
        assertEquals(Bounds(0, 0, 120, 80), node.bounds)
        assertEquals("root", node.text)
        val child = node.children.single() as ComposeNode
        assertEquals("window:main/root/0/compose/2", child.id)
        assertEquals("Button", child.semanticsRole)
        assertEquals("Save", child.text)
        assertEquals(Bounds(10, 20, 110, 60), child.bounds)
    }

    @Test
    fun `returns null when object is not a compose semantics owner`() {
        assertNull(ComposeSemanticsCollector.collect(Any(), "root/compose"))
    }
}

private class FakeComposeView(private val owner: FakeSemanticsOwner) {
    fun getSemanticsOwner(): FakeSemanticsOwner = owner
}

private class FakeSemanticsOwner(private val root: FakeSemanticsNode) {
    fun getUnmergedRootSemanticsNode(): FakeSemanticsNode = root
}

private class FakeSemanticsNode(
    private val id: Int,
    private val rect: FakeRect,
    private val config: FakeSemanticsConfiguration,
    private val children: List<FakeSemanticsNode> = emptyList(),
) {
    fun getId(): Int = id
    fun getBoundsInWindow(): FakeRect = rect
    fun getConfig(): FakeSemanticsConfiguration = config
    fun getChildren(): List<FakeSemanticsNode> = children
}

private class FakeRect(
    private val left: Float,
    private val top: Float,
    private val right: Float,
    private val bottom: Float,
) {
    fun getLeft(): Float = left
    fun getTop(): Float = top
    fun getRight(): Float = right
    fun getBottom(): Float = bottom
}

private class FakeSemanticsConfiguration(
    vararg pairs: Pair<String, Any?>,
) : Iterable<Map.Entry<FakeSemanticsPropertyKey, Any?>> {
    private val entries = pairs.associate { (name, value) -> FakeSemanticsPropertyKey(name) to value }

    override fun iterator(): Iterator<Map.Entry<FakeSemanticsPropertyKey, Any?>> = entries.entries.iterator()
}

private class FakeSemanticsPropertyKey(private val name: String) {
    fun getName(): String = name
}
