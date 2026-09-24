package com.androidperformancestudio.desktop

import com.androidperformancestudio.fixtures.SampleSnapshots
import com.androidperformancestudio.application.InspectorState
import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.CURRENT_PROTOCOL_VERSION
import com.androidperformancestudio.protocol.LEGACY_WINDOW_ID
import com.androidperformancestudio.protocol.ProtocolCodec
import com.androidperformancestudio.protocol.UiNode
import com.androidperformancestudio.protocol.ViewNode
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LargeCaptureArchiveSmokeTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `synthetic 10k hierarchy archive round trips without a screenshot`() {
        // Set this only for manual packaged-UI QA; the ordinary test writes into @TempDir.
        val archive = System.getenv("APS_SYNTHETIC_ARCHIVE_OUTPUT")
            ?.let(Path::of)
            ?: tempDir.resolve("synthetic-10k-hierarchy.apinspect")
        archive.parent?.let(Files::createDirectories)

        val service = CaptureArchiveService(CaptureArchiveCodec(), ProtocolCodec(supportedMajor = 1))
        val snapshot = SampleSnapshots.dashboard.copy(
            packageName = "com.androidperformancestudio.synthetic.qa",
            root = node(depth = 0, path = "root"),
            windows = emptyList(),
            defaultWindowId = null,
        )
        service.export(archive, "synthetic-qa", snapshot, screenshotPng = null, rawArtifacts = null)

        val imported = service.import(archive)
        val nodes = ArrayDeque<UiNode>().apply { add(imported.snapshot.root) }
        val ids = mutableSetOf<String>()
        while (nodes.isNotEmpty()) {
            val current = nodes.removeFirst()
            assertTrue(ids.add(current.id), "Duplicate synthetic node ID: ${current.id}")
            nodes.addAll(current.children)
        }
        assertEquals(10_000, ids.size)
        assertEquals(CURRENT_PROTOCOL_VERSION, imported.snapshot.protocolVersion)
        assertEquals(listOf(LEGACY_WINDOW_ID), imported.snapshot.windows.map { it.id })
        assertEquals(LEGACY_WINDOW_ID, imported.snapshot.defaultWindowId)
        assertNull(imported.screenshotPng)
        assertTrue(Files.size(archive) > 0)
        assertEquals(snapshot.packageName, CaptureArchiveCodec().read(archive).metadata.packageName)

        val rows = InspectorPresenter.present(InspectorState(snapshot = imported.snapshot)).rows
        assertEquals(10_000, rows.size)
        assertEquals(10_000, rows.mapTo(mutableSetOf()) { it.number }.size)
        assertEquals(10_000, HierarchyTreeState().displayRows(rows, hideInvisible = false).size)
        assertEquals(9_959, HierarchySelectionScrollPolicy.targetIndex(9_999, 0, 40))
    }

    private fun node(depth: Int, path: String): ViewNode = ViewNode(
        id = "synthetic-$path",
        className = if (depth == 4) "android.widget.TextView" else "android.widget.LinearLayout",
        bounds = if (depth == 0) Bounds(0, 0, 1080, 2400) else Bounds(0, 0, 1, 1),
        children = when (depth) {
            0 -> (0 until 9).map { node(depth + 1, "$path-$it") }
            in 1..3 -> (0 until 10).map { node(depth + 1, "$path-$it") }
            else -> emptyList()
        },
    )
}
