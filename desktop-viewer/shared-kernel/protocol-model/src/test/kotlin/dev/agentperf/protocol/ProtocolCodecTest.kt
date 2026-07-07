package dev.agentperf.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProtocolCodecTest {
    private val codec = ProtocolCodec(supportedMajor = 1)

    @Test
    fun `declares named protocol versions for compatibility upgrades`() {
        assertEquals(ProtocolVersion(1, 0), PROTOCOL_VERSION_1_0)
        assertEquals(ProtocolVersion(1, 1), PROTOCOL_VERSION_1_1)
        assertEquals(PROTOCOL_VERSION_1_1, CURRENT_PROTOCOL_VERSION)
        assertEquals("1.0", PROTOCOL_VERSION_1_0.identifier)
        assertEquals("1.1", CURRENT_PROTOCOL_VERSION.identifier)
    }

    @Test
    fun `snapshot survives a JSON round trip`() {
        val snapshot = LayoutSnapshot(
            protocolVersion = ProtocolVersion(1, 0),
            packageName = "dev.agentperf.sample",
            capturedAtEpochMillis = 1_750_000_000_000,
            display = DisplayInfo(widthPx = 1080, heightPx = 2400, density = 3f),
            capabilities = AgentCapabilities(viewHierarchy = true, screenshots = true),
            root = ViewNode(
                id = "root",
                className = "android.widget.FrameLayout",
                bounds = Bounds(0, 0, 1080, 2400),
                attributes = ViewAttributes(
                    visibility = "VISIBLE",
                    elevation = 8f,
                    z = 10f,
                    translationX = 2f,
                    translationY = 3f,
                    rotation = 5f,
                    scaleX = 1f,
                    scaleY = 1f,
                    padding = EdgeInsets(left = 16, top = 24, right = 16, bottom = 24),
                    margin = EdgeInsets(left = 8, top = 8, right = 8, bottom = 8),
                    clipChildren = true,
                    clipToPadding = false,
                    background = "android.graphics.drawable.ColorDrawable",
                    backgroundColor = "#FF101820",
                    layerType = "HARDWARE",
                    hardwareAccelerated = true,
                    clickable = true,
                    enabled = true,
                ),
                children = listOf(
                    ComposeNode(
                        id = "compose-title",
                        className = "Text",
                        bounds = Bounds(24, 40, 320, 96),
                        text = "AgentPerf",
                    ),
                ),
            ),
        )

        val encoded = codec.encodeSnapshot(snapshot)

        assertTrue(encoded.contains("\"elevation\": 8.0"))
        assertFalse(encoded.contains("\"translationZ\""))
        assertEquals(snapshot, codec.decodeSnapshot(encoded))
    }

    @Test
    fun `unknown minor fields are ignored`() {
        val json = """
            {
              "protocolVersion": {"major": 1, "minor": 9},
              "packageName": "dev.agentperf.sample",
              "capturedAtEpochMillis": 42,
              "display": {"widthPx": 100, "heightPx": 200, "density": 2.0},
              "capabilities": {"viewHierarchy": true, "screenshots": false},
              "root": {
                "type": "view",
                "id": "root",
                "className": "View",
                "bounds": {"left": 0, "top": 0, "right": 100, "bottom": 200},
                "children": [],
                "futureNodeField": "ignored"
              },
              "futureSnapshotField": {"enabled": true}
            }
        """.trimIndent()

        val snapshot = codec.decodeSnapshot(json)

        assertEquals(ProtocolVersion(1, 9), snapshot.protocolVersion)
        assertEquals("root", snapshot.root.id)
        assertEquals(ViewAttributes(), (snapshot.root as ViewNode).attributes)
        assertEquals(LEGACY_WINDOW_ID, snapshot.effectiveDefaultWindowId)
        assertEquals(listOf(snapshot.root), snapshot.effectiveWindows.map { it.root })
    }

    @Test
    fun `multi window snapshot survives a JSON round trip`() {
        val mainRoot = ViewNode(
            id = "window:main/root",
            className = "DecorView",
            bounds = Bounds(0, 80, 1080, 2400),
        )
        val dialogRoot = ViewNode(
            id = "window:dialog/root",
            className = "DialogDecorView",
            bounds = Bounds(120, 700, 960, 1500),
        )
        val snapshot = LayoutSnapshot(
            protocolVersion = ProtocolVersion(1, 1),
            packageName = "dev.agentperf.sample",
            capturedAtEpochMillis = 42,
            display = DisplayInfo(1080, 2400, 3f),
            capabilities = AgentCapabilities(viewHierarchy = true, screenshots = true),
            root = mainRoot,
            windows = listOf(
                WindowSnapshot(
                    id = "window:main",
                    title = "MainActivity",
                    type = WindowType.ACTIVITY,
                    bounds = mainRoot.bounds,
                    root = mainRoot,
                ),
                WindowSnapshot(
                    id = "window:dialog",
                    title = "Confirm",
                    type = WindowType.DIALOG,
                    bounds = dialogRoot.bounds,
                    root = dialogRoot,
                ),
            ),
            defaultWindowId = "window:main",
        )

        assertEquals(snapshot, codec.decodeSnapshot(codec.encodeSnapshot(snapshot)))
        assertEquals("window:main", snapshot.effectiveDefaultWindowId)
    }

    @Test
    fun `unsupported major version is rejected without partial parsing`() {
        val json = """
            {
              "protocolVersion": {"major": 2, "minor": 0},
              "packageName": "dev.agentperf.sample",
              "capturedAtEpochMillis": 42,
              "display": {"widthPx": 100, "heightPx": 200, "density": 2.0},
              "capabilities": {},
              "root": {
                "type": "view",
                "id": "root",
                "className": "View",
                "bounds": {"left": 0, "top": 0, "right": 100, "bottom": 200}
              }
            }
        """.trimIndent()

        val error = assertThrows(UnsupportedProtocolVersionException::class.java) {
            codec.decodeSnapshot(json)
        }

        assertEquals(2, error.actualMajor)
    }
}
