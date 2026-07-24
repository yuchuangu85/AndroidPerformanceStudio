@file:Suppress("TooManyFunctions")

package dev.agentperf.android.frame

import android.app.Activity
import android.app.Application
import android.annotation.TargetApi
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import com.androidperformancestudio.frame.agent.protocol.AgentFrameBatch
import com.androidperformancestudio.frame.agent.protocol.AgentFrameBatchCodec
import com.androidperformancestudio.frame.agent.protocol.AgentExpectedDurationSource
import com.androidperformancestudio.frame.agent.protocol.AgentFrameSample
import com.androidperformancestudio.frame.agent.protocol.AgentFrameStages
import dev.agentperf.android.core.AgentRequestExtension
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.WeakHashMap

class FrameMetricsAgent(
    application: Application,
) : AgentRequestExtension {
    private val store = FrameMetricsStore()
    private val available = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
    private val collector =
        if (available) {
            AndroidFrameMetricsCollector(application, store).also(AndroidFrameMetricsCollector::start)
        } else {
            null
        }
    private val requestExtension = FrameMetricsRequestExtension(store, available)

    override fun handle(
        command: String,
        arguments: List<String>,
        output: OutputStream,
    ): Boolean = requestExtension.handle(command, arguments, output)
}

internal class FrameMetricsRequestExtension(
    private val store: FrameMetricsStore,
    private val available: Boolean,
    private val codec: AgentFrameBatchCodec = AgentFrameBatchCodec(),
) : AgentRequestExtension {
    override fun handle(
        command: String,
        arguments: List<String>,
        output: OutputStream,
    ): Boolean =
        when (command) {
            "FRAME_CURSOR" -> {
                if (available) {
                    codec.write(AgentFrameBatch(cursor = store.cursor()), output)
                } else {
                    writeError(output, "FRAME_UNAVAILABLE", "FrameMetrics requires Android 7.0 (API 24) or newer")
                }
                true
            }
            "FRAMES" -> {
                val cursor = arguments.singleOrNull()?.toLongOrNull()
                if (!available) {
                    writeError(output, "FRAME_UNAVAILABLE", "FrameMetrics requires Android 7.0 (API 24) or newer")
                } else if (cursor == null) {
                    writeError(output, "INVALID_FRAME_CURSOR", "Expected one numeric frame cursor")
                } else {
                    codec.write(store.after(cursor), output)
                }
                true
            }
            else -> false
        }

    private fun writeError(
        output: OutputStream,
        code: String,
        message: String,
    ) {
        output.write("ERROR $code $message\n".toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }
}

@TargetApi(Build.VERSION_CODES.N)
private class AndroidFrameMetricsCollector(
    private val application: Application,
    private val store: FrameMetricsStore,
) : Application.ActivityLifecycleCallbacks {
    private val callbackThread = HandlerThread("agentperf-frame-metrics")
    private lateinit var callbackHandler: Handler
    private val attachedWindows = WeakHashMap<Activity, Window>()
    private val listener =
        Window.OnFrameMetricsAvailableListener { window, metrics, droppedReports ->
            val activity = synchronized(attachedWindows) { attachedWindows.entries.firstOrNull { it.value === window }?.key }
            store.add(metrics.toAgentFrame(application.packageName, activity, window, droppedReports))
        }

    fun start() {
        callbackThread.start()
        callbackHandler = Handler(callbackThread.looper)
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        attach(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        attach(activity)
    }

    override fun onActivityStopped(activity: Activity) {
        detach(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        detach(activity)
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

    private fun attach(activity: Activity) {
        val window = activity.window
        val previous = synchronized(attachedWindows) { attachedWindows.put(activity, window) }
        if (previous !== window) {
            previous?.removeOnFrameMetricsAvailableListener(listener)
            window.addOnFrameMetricsAvailableListener(listener, callbackHandler)
        }
    }

    private fun detach(activity: Activity) {
        val window = synchronized(attachedWindows) { attachedWindows.remove(activity) }
        window?.removeOnFrameMetricsAvailableListener(listener)
    }
}

@TargetApi(Build.VERSION_CODES.N)
private fun FrameMetrics.toAgentFrame(
    packageName: String,
    activity: Activity?,
    window: Window,
    droppedReports: Int,
): AgentFrameSample {
    val intendedVsync = metric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
    val totalDuration = metric(FrameMetrics.TOTAL_DURATION)
    val deadline = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) metric(FrameMetrics.DEADLINE) else null
    val deadlineDuration =
        deadline
            ?.let { value -> if (intendedVsync != null && value > intendedVsync) value - intendedVsync else value }
            ?.takeIf { it > 0L }
    val refreshRateDuration =
        window.decorView.display
            ?.refreshRate
            ?.takeIf { it > 0f }
            ?.let { refreshRate -> (NANOS_PER_SECOND / refreshRate).toLong() }
    val expectedDuration = deadlineDuration ?: refreshRateDuration
    val firstDraw = metric(FrameMetrics.FIRST_DRAW_FRAME) == 1L
    return AgentFrameSample(
        sequence = -1L,
        packageName = packageName,
        activityName = activity?.javaClass?.name,
        windowId = "window:${System.identityHashCode(window)}",
        intendedVsyncNs = intendedVsync,
        actualVsyncNs = metric(FrameMetrics.VSYNC_TIMESTAMP),
        frameCompletedNs =
            if (intendedVsync != null && totalDuration != null) intendedVsync + totalDuration else null,
        expectedDurationNs = expectedDuration,
        expectedDurationSource =
            when {
                deadlineDuration != null -> AgentExpectedDurationSource.PLATFORM_DEADLINE
                refreshRateDuration != null -> AgentExpectedDurationSource.REFRESH_RATE
                else -> AgentExpectedDurationSource.UNKNOWN
            },
        totalDurationNs = totalDuration,
        stages =
            AgentFrameStages(
                inputNs = metric(FrameMetrics.INPUT_HANDLING_DURATION),
                animationNs = metric(FrameMetrics.ANIMATION_DURATION),
                layoutMeasureNs = metric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                drawNs = metric(FrameMetrics.DRAW_DURATION),
                syncNs = metric(FrameMetrics.SYNC_DURATION),
                commandIssueNs = metric(FrameMetrics.COMMAND_ISSUE_DURATION),
                swapBuffersNs = metric(FrameMetrics.SWAP_BUFFERS_DURATION),
                gpuNs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) metric(FrameMetrics.GPU_DURATION) else null,
            ),
        states =
            buildMap {
                put("capture", "FrameMetrics")
                if (firstDraw) put("firstDraw", "true")
                if (droppedReports > 0) put("droppedReports", droppedReports.toString())
            },
        eligibleForJank = !firstDraw,
    )
}

@TargetApi(Build.VERSION_CODES.N)
private fun FrameMetrics.metric(identifier: Int): Long? = getMetric(identifier).takeIf { it >= 0L }

private const val NANOS_PER_SECOND = 1_000_000_000.0
