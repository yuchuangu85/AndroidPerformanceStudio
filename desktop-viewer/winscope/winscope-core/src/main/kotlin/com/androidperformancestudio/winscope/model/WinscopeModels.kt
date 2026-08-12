package com.androidperformancestudio.winscope.model

import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.time.Instant

@Serializable
enum class WinscopeSource(
    val displayName: String,
    val perfettoName: String?,
) {
    WINDOW_MANAGER("WindowManager", "android.windowmanager"),
    SURFACE_FLINGER("SurfaceFlinger", "android.surfaceflinger.layers"),
    TRANSACTIONS("Transactions", "android.surfaceflinger.transactions"),
    TRANSITIONS("Transitions", "com.android.wm.shell.transition"),
    EVENT_LOG("EventLog", "android.log"),
    INPUT("Input", "android.input.inputevent"),
    IME("IME", "android.inputmethod"),
    VIEW_CAPTURE("ViewCapture", "android.viewcapture"),
    PROTO_LOG("ProtoLog", "android.protolog"),
    SCREEN_RECORDING("Screen recording", null),
    SCREENSHOT("Screenshot", null),
}

enum class WinscopeCapturePreset(
    val bufferSizeKb: Int,
) {
    BALANCED(65_536),
    FULL_DETAIL(500_000),
}

enum class ProtoLogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    WTF,
}

data class WinscopeCaptureConfig(
    val preset: WinscopeCapturePreset = WinscopeCapturePreset.BALANCED,
    val durationSeconds: Int = 10,
    val requestedSources: Set<WinscopeSource> = DEFAULT_SOURCES,
    val protoLogLevel: ProtoLogLevel = ProtoLogLevel.WARN,
    val protoLogEnableAll: Boolean = false,
    val protoLogStacktraces: Boolean = false,
    val selectedDisplayId: Long? = null,
) {
    init {
        require(durationSeconds in 1..600) { "durationSeconds must be in [1, 600]" }
        require(requestedSources.isNotEmpty()) { "at least one Winscope source is required" }
        require(!protoLogStacktraces || WinscopeSource.PROTO_LOG in requestedSources) {
            "ProtoLog stack traces require ProtoLog"
        }
    }

    val containsSensitiveEvidence: Boolean
        get() =
            WinscopeSource.INPUT in requestedSources ||
                WinscopeSource.SCREEN_RECORDING in requestedSources ||
                protoLogStacktraces

    companion object {
        val DEFAULT_SOURCES: Set<WinscopeSource> =
            setOf(
                WinscopeSource.WINDOW_MANAGER,
                WinscopeSource.SURFACE_FLINGER,
                WinscopeSource.TRANSACTIONS,
                WinscopeSource.TRANSITIONS,
                WinscopeSource.EVENT_LOG,
                WinscopeSource.IME,
                WinscopeSource.VIEW_CAPTURE,
                WinscopeSource.PROTO_LOG,
            )

        val ALL_SOURCES: Set<WinscopeSource> = WinscopeSource.entries.toSet() - WinscopeSource.SCREENSHOT
    }
}

data class WinscopeDevice(
    val serial: String,
    val model: String,
    val androidSdk: Int,
    val buildType: String,
    val rootActive: Boolean,
    val rootAvailable: Boolean,
)

data class WinscopeLimitation(
    val source: WinscopeSource?,
    val code: String,
    val message: String,
)

data class WinscopeCapabilities(
    val device: WinscopeDevice,
    val registeredDataSources: Set<String>,
    val availableSources: Set<WinscopeSource>,
    val limitations: List<WinscopeLimitation>,
    val displays: List<WinscopeDisplay> = emptyList(),
) {
    val liveCaptureSupported: Boolean
        get() = device.androidSdk >= 35 && CORE_SOURCES.any(availableSources::contains)

    companion object {
        val CORE_SOURCES: Set<WinscopeSource> =
            setOf(WinscopeSource.WINDOW_MANAGER, WinscopeSource.SURFACE_FLINGER)
    }
}

data class WinscopeDisplay(
    val physicalId: Long,
    val name: String,
)

enum class WinscopeCompleteness {
    COMPLETE,
    PARTIAL,
    UNKNOWN,
}

data class WinscopeSession(
    val id: String,
    val traceFile: Path,
    val screenshotFile: Path? = null,
    val recordingFile: Path? = null,
    val capturedAt: Instant,
    val device: WinscopeDevice? = null,
    val requestedSources: Set<WinscopeSource>? = null,
    val availableSources: Set<WinscopeSource> = emptySet(),
    val limitations: List<WinscopeLimitation> = emptyList(),
    val completeness: WinscopeCompleteness = WinscopeCompleteness.UNKNOWN,
    val sensitive: Boolean = false,
    val managedFiles: Boolean = false,
    val isDump: Boolean = false,
    val annotations: List<WinscopeAnnotation> = emptyList(),
)

@Serializable
data class WinscopeAnnotation(
    val timestampNanos: Long,
    val label: String,
    val note: String = "",
)

data class WinscopeTraceBounds(
    val startNanos: Long,
    val endNanos: Long,
) {
    init {
        require(startNanos <= endNanos) { "trace start must not exceed end" }
    }
}

data class WinscopeTimelineEntry(
    val id: Long,
    val source: WinscopeSource,
    val timestampNanos: Long,
    val endNanos: Long? = null,
    val label: String = source.displayName,
    val severity: String? = null,
)

data class WinscopeTimeline(
    val bounds: WinscopeTraceBounds,
    val entries: Map<WinscopeSource, List<WinscopeTimelineEntry>>,
)

data class WinscopeRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

enum class WinscopeNodeChange {
    NONE,
    ADDED,
    MODIFIED,
    REMOVED,
}

data class WinscopeNode(
    val id: String,
    val parentId: String?,
    val name: String,
    val type: String,
    val visible: Boolean?,
    val z: Float,
    val bounds: WinscopeRect?,
    val opacity: Float? = null,
    val displayId: Int? = null,
    val change: WinscopeNodeChange = WinscopeNodeChange.NONE,
    val properties: List<WinscopeProperty> = emptyList(),
)

data class WinscopeProperty(
    val path: String,
    val value: String?,
    val previousValue: String? = null,
    val recorded: Boolean = true,
) {
    val changed: Boolean get() = recorded && value != previousValue
}

data class WinscopeState(
    val source: WinscopeSource,
    val timestampNanos: Long,
    val nodes: List<WinscopeNode>,
    val synchronized: Boolean = true,
    val warning: String? = null,
)

data class WinscopeLogRow(
    val id: Long,
    val source: WinscopeSource,
    val timestampNanos: Long,
    val columns: Map<String, String>,
)

data class WinscopeQueryResult(
    val columns: List<String>,
    val rows: List<List<String?>>,
    val timestamps: List<Long>,
)

enum class WinscopePhase {
    IDLE,
    PROBING,
    PREPARING,
    RECORDING,
    PULLING,
    OPENING,
    READY,
    FAILED,
}

data class WinscopeRuntimeState(
    val phase: WinscopePhase = WinscopePhase.IDLE,
    val session: WinscopeSession? = null,
    val message: String = "",
    val errorCode: String? = null,
)
