package com.androidperformancestudio.network.app

import com.androidperformancestudio.network.har.HarParser
import com.androidperformancestudio.network.model.NetworkCaptureResult
import java.nio.file.Path
import java.util.concurrent.CancellationException

internal class HarImportParser {
    fun parse(path: Path, checkCancelled: () -> Unit = {}): NetworkCaptureResult = try {
        checkCancelled()
        HarParser().parse(path, checkCancelled)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Lenient JSON errors can embed source excerpts as well.
        throw IllegalArgumentException("HAR import rejected")
    }
}
