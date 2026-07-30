@file:Suppress("TooManyFunctions")

package com.androidperformancestudio.android.startup.metrics

import android.app.Activity
import android.app.Application
import android.annotation.TargetApi
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.ViewTreeObserver
import android.view.Window
import com.androidperformancestudio.startup.agent.protocol.AgentEvidenceConfidence
import com.androidperformancestudio.startup.agent.protocol.AgentStartupMilestoneKind
import com.androidperformancestudio.startup.agent.protocol.AgentStartupResultCodec
import com.androidperformancestudio.android.core.AgentRequestExtension
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.WeakHashMap

public class StartupMetricsAgent private constructor(
    private val application: Application,
    private val store: StartupMetricsStore,
) : AgentRequestExtension,
    Application.ActivityLifecycleCallbacks {
    private val callbackThread = HandlerThread("agentperf-startup-metrics").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)
    private val frameListeners = WeakHashMap<Activity, Window.OnFrameMetricsAvailableListener>()
    private val drawListeners = WeakHashMap<Activity, ViewTreeObserver.OnDrawListener>()
    private val codec = AgentStartupResultCodec()

    init {
        application.registerActivityLifecycleCallbacks(this)
        store.add(AgentStartupMilestoneKind.AGENT_READY, SystemClock.elapsedRealtimeNanos())
    }

    override fun handle(
        command: String,
        arguments: List<String>,
        output: OutputStream,
    ): Boolean =
        when (command) {
            "STARTUP_CAPABILITIES" -> {
                codec.write(store.result(), output)
                true
            }
            "STARTUP_ARM" -> {
                val runId = arguments.singleOrNull()
                if (runId.isNullOrBlank()) writeError(output, "INVALID_RUN_ID", "Expected one non-empty run ID") else {
                    store.arm(runId)
                    codec.write(store.result(runId), output)
                }
                true
            }
            "STARTUP_RESULT" -> {
                val runId = arguments.singleOrNull()
                if (runId.isNullOrBlank()) writeError(output, "INVALID_RUN_ID", "Expected one non-empty run ID") else {
                    codec.write(store.result(runId), output)
                }
                true
            }
            "STARTUP_CLEAR" -> {
                val runId = arguments.singleOrNull()
                if (runId.isNullOrBlank()) writeError(output, "INVALID_RUN_ID", "Expected one non-empty run ID") else {
                    store.clear(runId)
                    codec.write(store.result(runId), output)
                }
                true
            }
            else -> false
        }

    override fun onActivityPreCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) {
        associateRun(activity)
        add(activity, AgentStartupMilestoneKind.ACTIVITY_PRE_CREATE)
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) {
        associateRun(activity)
        add(activity, AgentStartupMilestoneKind.ACTIVITY_CREATED)
        attachFirstFrame(activity)
    }

    override fun onActivityStarted(activity: Activity) {
        add(activity, AgentStartupMilestoneKind.ACTIVITY_STARTED)
    }

    override fun onActivityResumed(activity: Activity) {
        add(activity, AgentStartupMilestoneKind.ACTIVITY_RESUMED)
        attachFirstFrame(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        detachFirstFrame(activity)
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

    private fun associateRun(activity: Activity) {
        activity.intent?.getStringExtra(RUN_ID_EXTRA)?.takeIf(String::isNotBlank)?.let(store::associate)
    }

    private fun add(
        activity: Activity,
        kind: AgentStartupMilestoneKind,
    ) {
        store.add(kind, SystemClock.elapsedRealtimeNanos(), activityName = activity.javaClass.name)
    }

    private fun attachFirstFrame(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) attachFrameMetrics(activity) else attachDrawCallback(activity)
    }

    @TargetApi(Build.VERSION_CODES.N)
    private fun attachFrameMetrics(activity: Activity) {
        if (frameListeners.containsKey(activity)) return
        lateinit var listener: Window.OnFrameMetricsAvailableListener
        listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) {
                val intended = metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP).takeIf { it >= 0L }
                val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION).takeIf { it >= 0L }
                val completed = if (intended != null && total != null) intended + total else SystemClock.elapsedRealtimeNanos()
                store.add(
                    AgentStartupMilestoneKind.FIRST_FRAME,
                    completed,
                    activityName = activity.javaClass.name,
                )
                activity.window.removeOnFrameMetricsAvailableListener(listener)
                frameListeners.remove(activity)
            }
        }
        frameListeners[activity] = listener
        activity.window.addOnFrameMetricsAvailableListener(listener, callbackHandler)
    }

    private fun attachDrawCallback(activity: Activity) {
        if (drawListeners.containsKey(activity)) return
        val decorView = activity.window.decorView
        lateinit var listener: ViewTreeObserver.OnDrawListener
        listener = ViewTreeObserver.OnDrawListener {
            store.add(
                AgentStartupMilestoneKind.FIRST_DRAW_CALLBACK,
                SystemClock.elapsedRealtimeNanos(),
                AgentEvidenceConfidence.ESTIMATED,
                activity.javaClass.name,
            )
            decorView.post {
                if (decorView.viewTreeObserver.isAlive) decorView.viewTreeObserver.removeOnDrawListener(listener)
                drawListeners.remove(activity)
            }
        }
        drawListeners[activity] = listener
        decorView.viewTreeObserver.addOnDrawListener(listener)
    }

    private fun detachFirstFrame(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) detachFrameMetrics(activity)
        drawListeners.remove(activity)?.let { listener ->
            val observer = activity.window.decorView.viewTreeObserver
            if (observer.isAlive) observer.removeOnDrawListener(listener)
        }
    }

    @TargetApi(Build.VERSION_CODES.N)
    private fun detachFrameMetrics(activity: Activity) {
        frameListeners.remove(activity)?.let(activity.window::removeOnFrameMetricsAvailableListener)
    }

    private fun writeError(
        output: OutputStream,
        code: String,
        message: String,
    ) {
        output.write("ERROR $code $message\n".toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    public companion object {
        public const val RUN_ID_EXTRA: String = "com.androidperformancestudio.startup.RUN_ID"

        @Volatile
        private var processStore: StartupMetricsStore? = null

        public fun initializerEntered(application: Application) {
            store(application).add(AgentStartupMilestoneKind.INITIALIZER_ENTER, SystemClock.elapsedRealtimeNanos())
        }

        public fun create(application: Application): StartupMetricsAgent = StartupMetricsAgent(application, store(application))

        private fun store(application: Application): StartupMetricsStore =
            processStore ?: synchronized(this) {
                processStore ?: StartupMetricsStore(
                    packageName = application.packageName,
                    processId = Process.myPid(),
                    processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Application.getProcessName() else null,
                    apiLevel = Build.VERSION.SDK_INT,
                    processStartElapsedRealtimeNs =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) Process.getStartElapsedRealtime() * NANOS_PER_MILLISECOND else null,
                ).also { created ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        created.add(
                            AgentStartupMilestoneKind.PROCESS_START,
                            Process.getStartElapsedRealtime() * NANOS_PER_MILLISECOND,
                        )
                    }
                    processStore = created
                }
            }

        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
