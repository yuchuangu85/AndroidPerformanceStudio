package dev.agentperf.android.view

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import dev.agentperf.android.core.CaptureProvider
import dev.agentperf.android.core.CaptureUnavailableException
import dev.agentperf.protocol.CaptureFrame
import dev.agentperf.protocol.ProtocolCodec
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class ActivityCaptureProvider(
    private val activityTracker: ResumedActivityTracker,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val timeoutSeconds: Long = 5,
) : CaptureProvider {
    private val protocolCodec = ProtocolCodec(supportedMajor = 1)
    private val viewTreeCollector = ViewTreeCollector()

    override fun capture(): CaptureFrame {
        val activity = activityTracker.currentActivity()
            ?: throw CaptureUnavailableException("NO_ACTIVITY", "No resumed activity")
        val future = CompletableFuture<CaptureFrame>()
        mainHandler.post {
            beginCapture(activity, future)
        }
        return try {
            future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (error: TimeoutException) {
            throw CaptureUnavailableException("CAPTURE_TIMEOUT", "Timed out capturing the activity", error)
        } catch (error: ExecutionException) {
            val cause = error.cause
            if (cause is CaptureUnavailableException) throw cause
            throw CaptureUnavailableException(
                "CAPTURE_FAILED",
                cause?.message ?: "Activity capture failed",
                cause,
            )
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CaptureUnavailableException("CAPTURE_INTERRUPTED", "Activity capture was interrupted", error)
        }
    }

    private fun beginCapture(
        activity: Activity,
        future: CompletableFuture<CaptureFrame>,
    ) {
        try {
            val root = activity.window.decorView.rootView
            val width = root.width
            val height = root.height
            if (width <= 0 || height <= 0) {
                throw CaptureUnavailableException("NO_CONTENT", "Activity content has no measured size")
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PixelCopy.request(
                    activity.window,
                    bitmap,
                    { result ->
                        if (result == PixelCopy.SUCCESS) {
                            completeFrame(activity, bitmap, future)
                        } else {
                            future.completeExceptionally(
                                CaptureUnavailableException(
                                    "SCREENSHOT_FAILED",
                                    "PixelCopy failed with result $result",
                                ),
                            )
                        }
                    },
                    mainHandler,
                )
            } else {
                root.draw(Canvas(bitmap))
                completeFrame(activity, bitmap, future)
            }
        } catch (error: Throwable) {
            future.completeExceptionally(error)
        }
    }

    private fun completeFrame(
        activity: Activity,
        bitmap: Bitmap,
        future: CompletableFuture<CaptureFrame>,
    ) {
        try {
            val rootView = activity.window.decorView.rootView
            val snapshot = LiveSnapshotFactory.create(
                packageName = activity.packageName,
                widthPx = bitmap.width,
                heightPx = bitmap.height,
                density = rootView.resources.displayMetrics.density,
                capturedAtEpochMillis = System.currentTimeMillis(),
                root = viewTreeCollector.collect(rootView),
            )
            val screenshot = ByteArrayOutputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw CaptureUnavailableException("SCREENSHOT_FAILED", "PNG encoding failed")
                }
                output.toByteArray()
            }
            future.complete(
                CaptureFrame(
                    snapshotJson = protocolCodec.encodeSnapshot(snapshot),
                    screenshotPng = screenshot,
                ),
            )
        } catch (error: Throwable) {
            future.completeExceptionally(error)
        } finally {
            bitmap.recycle()
        }
    }
}
