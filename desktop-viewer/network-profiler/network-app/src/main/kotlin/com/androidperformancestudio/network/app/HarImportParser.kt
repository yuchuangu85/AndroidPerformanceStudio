package com.androidperformancestudio.network.app

import com.androidperformancestudio.network.har.HarParser
import com.androidperformancestudio.network.har.NativeHarParser
import com.androidperformancestudio.network.model.NetworkCaptureResult
import java.nio.file.Path
import java.util.concurrent.CancellationException

/** No automatic rollout: SHADOW keeps the retained parser authoritative. */
internal enum class HarImportMode {
    SHADOW,
    NATIVE_EXPERIMENTAL,
    NATIVE_WITH_LEGACY_FALLBACK,
    ;

    companion object {
        fun configured(): HarImportMode = when (System.getProperty("aps.harEngine.mode")) {
            null, "shadow" -> SHADOW
            "native-experimental" -> NATIVE_EXPERIMENTAL
            "native-with-legacy-fallback" -> NATIVE_WITH_LEGACY_FALLBACK
            else -> error("Unsupported HAR import mode")
        }
    }
}

internal class HarImportParser(
    private val mode: HarImportMode = HarImportMode.SHADOW,
    private val executable: Path? = null,
) {
    @Suppress("ReturnCount")
    fun parse(path: Path, checkCancelled: () -> Unit = {}): NetworkCaptureResult {
        checkCancelled()
        if (mode == HarImportMode.SHADOW) return legacy(path, checkCancelled)
        try {
            requireNotNull(executable) { "Native HAR executable is not configured" }
            return NativeHarParser(executable).parse(path, checkCancelled)
        } catch (failure: IllegalArgumentException) {
            if (mode != HarImportMode.NATIVE_WITH_LEGACY_FALLBACK) throw failure
        }
        checkCancelled()
        val legacy = legacy(path, checkCancelled)
        return legacy.copy(session = legacy.session.copy(warnings = legacy.session.warnings + "Native HAR unavailable or rejected; used legacy parser."))
    }

    private fun legacy(path: Path, checkCancelled: () -> Unit): NetworkCaptureResult = try {
        HarParser().parse(path, checkCancelled)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Lenient JSON errors can embed source excerpts as well.
        throw IllegalArgumentException("HAR import rejected")
    }
}
