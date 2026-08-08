package com.androidperformancestudio.session.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.AwtWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.session.model.ProfilerSession
import com.androidperformancestudio.session.model.SessionSegment
import com.androidperformancestudio.session.model.SessionSegmentKind
import com.androidperformancestudio.ui.ProfilerCompactButton
import com.androidperformancestudio.ui.ProfilerMacOsToolbar
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui.button.HomeButton
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The unified Session workspace (final-phase): persist named Sessions, add captured profiler
 * artifacts as segments, view them on a simple timeline, and open a segment in its profiler.
 */
@Composable
fun FrameWindowScope.SessionMainPage(
    language: UiLanguage = UiLanguage.ENGLISH,
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    onBack: () -> Unit = {},
    onOpenSegment: (SessionSegment) -> Unit = {},
) {
    val controller = remember { SessionController() }
    val state by controller.state.collectAsState()
    var showAddSegmentDialog by remember { mutableStateOf(false) }

    ViewerTheme(darkTheme = darkTheme) {
        Column(Modifier.fillMaxSize()) {
            ProfilerMacOsToolbar {
                HomeButton(contentDescription = "Back to home", onClick = onBack)
                ProfilerCompactButton(
                    text = "New Session",
                    onClick = { controller.createSession("Session ${state.sessions.size + 1}") },
                )
                ProfilerCompactButton(
                    text = "Add Segment",
                    enabled = state.selectedSession != null,
                    onClick = { showAddSegmentDialog = true },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            SessionScreen(
                state = state,
                onSelectSession = controller::selectSession,
                onDeleteSession = controller::deleteSession,
                onOpenSegment = onOpenSegment,
                modifier = Modifier.weight(1f),
            )
        }
    }

    val selectedSession = state.selectedSession
    if (showAddSegmentDialog && selectedSession != null) {
        SegmentOpenFileDialog(
            parent = window,
            onCloseRequest = { file ->
                showAddSegmentDialog = false
                if (file != null) {
                    controller.addSegment(
                        sessionId = selectedSession.id,
                        segment = segmentFromFile(file),
                    )
                }
            },
        )
    }
}

@Composable
private fun SessionScreen(
    state: SessionState,
    onSelectSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onOpenSegment: (SessionSegment) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        // Left: session list.
        Column(
            modifier =
                Modifier
                    .width(240.dp)
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Sessions", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            if (state.sessions.isEmpty()) {
                Text("No sessions yet. Create one to start.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.sessions.forEach { session ->
                val selected = session.id == state.selectedSessionId
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer)
                            .clickable { onSelectSession(session.id) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(session.name, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = "${session.segments.size} segments",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text(
                        text = "✕",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { onDeleteSession(session.id) },
                    )
                }
            }
        }
        // Right: selected session timeline + segments.
        val session = state.selectedSession
        if (session == null) {
            Text(
                text = "Select a session to view its timeline.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            SelectedSessionDetail(
                session = session,
                onOpenSegment = onOpenSegment,
                modifier = Modifier.weight(1f).padding(8.dp),
            )
        }
    }
}

@Composable
private fun SelectedSessionDetail(
    session: ProfilerSession,
    onOpenSegment: (SessionSegment) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(session.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(
            text = session.createdAt.format() + (session.packageName?.let { " · $it" } ?: ""),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        if (session.segments.isEmpty()) {
            Text("No segments yet. Add a captured artifact.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        SessionTimelineBar(session)
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            session.segments.sortedBy { it.capturedAtEpochMillis }.forEach { segment ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${segment.kind} · ${segment.label}", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = listOfNotNull(segment.capturedAt.format(), segment.summary).joinToString(" · "),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    ProfilerCompactButton(text = "Open", onClick = { onOpenSegment(segment) })
                }
            }
        }
    }
}

/** A simple horizontal timeline: each segment is a colored block ordered by capture time. */
@Composable
private fun SessionTimelineBar(session: ProfilerSession) {
    val segments = session.segments.sortedBy { it.capturedAtEpochMillis }
    val min = segments.firstOrNull()?.capturedAtEpochMillis ?: return
    val max = segments.last().capturedAtEpochMillis
    val span = (max - min).coerceAtLeast(1)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(20.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEach { segment ->
            val width = ((segment.capturedAtEpochMillis - min) * 100 / span).toFloat()
            if (width > 0) Spacer(Modifier.width(width.dp))
            Text(
                text = "▮",
                color = kindColor(segment.kind),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private fun kindColor(kind: SessionSegmentKind): Color =
    when (kind) {
        SessionSegmentKind.HPROF, SessionSegmentKind.JAVA_HEAP -> Color(0xFF4CAF50)
        SessionSegmentKind.NATIVE_HEAP -> Color(0xFF2196F3)
        SessionSegmentKind.METHOD_TRACE -> Color(0xFFFF9800)
        SessionSegmentKind.SIMPLEPERF, SessionSegmentKind.PERFETTO -> Color(0xFF9C27B0)
        else -> Color(0xFF9E9E9E)
    }

@Composable
private fun SegmentOpenFileDialog(
    parent: Frame,
    onCloseRequest: (File?) -> Unit,
) {
    AwtWindow(
        create = {
            object : FileDialog(parent, "Add a captured artifact", FileDialog.LOAD) {
                override fun setVisible(value: Boolean) {
                    super.setVisible(value)
                    if (value) {
                        onCloseRequest(files.firstOrNull())
                    }
                }
            }
        },
        dispose = FileDialog::dispose,
    )
}

private fun segmentFromFile(file: File): SessionSegment {
    val name = file.name
    val kind =
        when {
            name.endsWith(".hprof", ignoreCase = true) -> SessionSegmentKind.HPROF
            name.endsWith(".trace", ignoreCase = true) -> SessionSegmentKind.METHOD_TRACE
            name.endsWith(".pftrace", ignoreCase = true) || name.endsWith(".perfetto-trace", ignoreCase = true) ->
                SessionSegmentKind.PERFETTO
            name.endsWith(".pb", ignoreCase = true) -> SessionSegmentKind.NATIVE_HEAP
            else -> SessionSegmentKind.PERFETTO
        }
    return SessionSegment(
        id = "seg-${System.currentTimeMillis()}",
        kind = kind,
        label = name,
        artifactPath = file.toPath().toString(),
        capturedAtEpochMillis = Instant.now().toEpochMilli(),
    )
}

private val SESSION_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private fun Instant.format(): String = SESSION_TIME_FORMAT.format(atZone(ZoneId.systemDefault()))
