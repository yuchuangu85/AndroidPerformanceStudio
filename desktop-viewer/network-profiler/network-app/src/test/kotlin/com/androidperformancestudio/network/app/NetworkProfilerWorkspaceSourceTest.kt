package com.androidperformancestudio.network.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkProfilerWorkspaceSourceTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/network/app/NetworkProfilerMainPage.kt"),
        )

    @Test
    fun `workspace uses shared compact chrome without changing network screen`() {
        assertTrue(source.contains("ProfilerMacOsToolbar"))
        assertTrue(source.contains("ProfilerCompactTextField"))
        assertTrue(source.contains("ProfilerToolbarStatus"))
        assertFalse(source.contains("import androidx.compose.material3.OutlinedTextField"))
        assertFalse(source.contains("import androidx.compose.material3.OutlinedButton"))
        assertFalse(source.contains("import androidx.compose.material3.Button"))
        assertTrue(source.contains("NetworkProfilerScreen("))
    }

    @Test
    fun `toolbar preserves capture import and export wiring with enabled predicates`() {
        assertButtonContains(
            "text = if (chinese) \"导入 HAR\" else \"Import HAR\"",
            "enabled = !state.capturing",
            "chooseHar(window)",
            "HarParser().parse(file.toPath())",
            "complete(",
        )
        assertButtonContains(
            "if (chinese) \"在线采集\" else \"Live Capture\"",
            "enabled = state.deviceSerial.isNotBlank() && state.packageName.isNotBlank()",
            "if (state.capturing) stop() else start()",
        )
        assertButtonContains(
            "text = \"JSON\"",
            "enabled = state.result != null",
            "exporter.writeJson(",
            "requireNotNull(state.summary)",
        )
        assertButtonContains(
            "text = \"HAR\"",
            "enabled = state.result != null",
            "exporter.writePartialHar(",
        )
        assertButtonContains(
            "text = \"CSV\"",
            "enabled = state.result != null",
            "exporter.writeCsv(",
        )
        assertButtonContains(
            "text = if (chinese) \"原始包\" else \"Raw Bundle\"",
            "enabled = state.result != null",
            "exporter.writeRawBundle(",
            "requireNotNull(state.summary)",
        )
    }

    @Test
    fun `toolbar preserves home navigation and capture field enablement`() {
        val homeBlock =
            source.substring(
                source.indexOf("ProfilerHomeButton("),
                source.indexOf("ProfilerCompactButton("),
            )
        assertTrue(homeBlock.contains("if (state.capturing)stop()"))
        assertTrue(homeBlock.contains("onBack()"))

        assertTextFieldContains(
            "label = if (chinese) \"设备序列号\" else \"Device serial\"",
            "onValueChange = { state = state.copy(deviceSerial = it) }",
            "enabled = !state.capturing",
        )
        assertTextFieldContains(
            "label = if (chinese) \"包名\" else \"Package\"",
            "onValueChange = { state = state.copy(packageName = it) }",
            "enabled = !state.capturing",
        )
    }

    private fun assertButtonContains(
        anchor: String,
        vararg invariants: String,
    ) {
        val anchorIndex = source.indexOf(anchor)
        assertTrue(anchorIndex >= 0, "Missing anchor: $anchor")
        val blockStart = source.lastIndexOf("ProfilerCompactButton(", anchorIndex)
        val blockEnd = source.indexOf("ProfilerCompactButton(", anchorIndex + anchor.length).let {
            if (it >= 0) it else source.indexOf("Spacer(", anchorIndex)
        }
        val block = source.substring(blockStart, blockEnd)

        invariants.forEach { invariant ->
            assertTrue(block.contains(invariant), "Missing `$invariant` near `$anchor`")
        }
    }

    private fun assertTextFieldContains(
        anchor: String,
        vararg invariants: String,
    ) {
        val anchorIndex = source.indexOf(anchor)
        assertTrue(anchorIndex >= 0, "Missing anchor: $anchor")
        val blockStart = source.lastIndexOf("ProfilerCompactTextField(", anchorIndex)
        val nextButton = source.indexOf("ProfilerCompactButton(", anchorIndex + anchor.length)
        val nextTextField = source.indexOf("ProfilerCompactTextField(", anchorIndex + anchor.length)
        val blockEnd = listOf(nextButton, nextTextField).filter { it >= 0 }.minOrNull() ?: source.length
        val block = source.substring(blockStart, blockEnd)

        invariants.forEach { invariant ->
            assertTrue(block.contains(invariant), "Missing `$invariant` near `$anchor`")
        }
    }
}
