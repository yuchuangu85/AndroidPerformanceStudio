package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecentArtifactNavigationWiringTest {
    @Test
    fun `dashboard recent actions reach feature import flows instead of only opening tool pages`() {
        val app = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/DesktopAppMainPage.kt"))
        val home = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/AppHomePage.kt"))
        val layout = Files.readString(Path.of("../layout-inspector/presentation/src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"))
        val cpu = Files.readString(Path.of("../simpleperf-viewer/app-desktop/src/main/kotlin/com/androidperformancestudio/desktop/SimpleperfMainPage.kt"))

        assertTrue(home.contains("onOpenRecentArtifact(item)"))
        assertTrue(app.contains("navigator.openLayoutArchive(item.artifactPath)"))
        assertTrue(app.contains("navigator.openSimpleperfSession(item.artifactPath)"))
        assertTrue(app.contains("navigator.openPerfettoTrace(item.artifactPath)"))
        assertTrue(app.contains("navigator.openMemoryProfiler(item.artifactPath)"))
        assertTrue(app.contains("initialArchiveRequestId = navigator.layoutArchiveRequest?.requestId"))
        assertTrue(app.contains("initialSessionRequestId = navigator.simpleperfSessionRequest?.requestId"))
        assertTrue(layout.contains("initialArchiveFile?.let(openCaptureArchive)"))
        assertTrue(cpu.contains("initialSessionFile?.let { sessionOpener.open(it) }"))
    }

    @Test
    fun `file picker hands selected artifacts to existing import flows`() {
        val app = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/DesktopAppMainPage.kt"))

        // The fallback menu's file picker routes selected artifacts into the Perfetto and Memory
        // import flows (the Quick Actions row that used to host these has been removed).
        assertTrue(app.contains("""chooseHomeArtifact(window, localizedStringResource(Res.string.open_trace_file, language))"""))
        assertTrue(app.contains("""?.let { navigator.openPerfettoTrace(it) }"""))
        assertTrue(app.contains("""chooseHomeArtifact(window, localizedStringResource(Res.string.analyze_hprof, language))"""))
        assertTrue(app.contains("""?.let { navigator.openMemoryProfiler(it) }"""))
        assertTrue(app.contains("initialTraceRequestId = navigator.perfettoTraceRequestId"))
        assertTrue(app.contains("initialImportRequestId = navigator.memoryImportRequestId"))
    }
}
