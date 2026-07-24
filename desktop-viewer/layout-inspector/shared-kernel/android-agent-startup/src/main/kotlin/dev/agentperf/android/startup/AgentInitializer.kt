package dev.agentperf.android.startup

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.startup.Initializer
import dev.agentperf.android.core.AgentRuntime
import dev.agentperf.android.core.AgentServer
import dev.agentperf.android.core.StartResult
import dev.agentperf.android.frame.FrameMetricsAgent
import dev.agentperf.android.view.ActivityCaptureProvider
import dev.agentperf.android.view.ResumedActivityTracker

class AgentInitializer : Initializer<StartResult> {
    override fun create(context: Context): StartResult {
        val applicationContext = context.applicationContext
        val debuggable = applicationContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return runtime(applicationContext).start(debuggable)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    private fun runtime(context: Context): AgentRuntime =
        processRuntime ?: synchronized(this) {
            processRuntime ?: AgentRuntime {
                val application = context.applicationContext as Application
                val tracker = ResumedActivityTracker(application)
                val frameMetricsAgent = FrameMetricsAgent(application)
                AgentServer(
                    context = context,
                    captureProvider = ActivityCaptureProvider(tracker),
                    requestExtensions = listOf(frameMetricsAgent),
                ).start()
            }.also { processRuntime = it }
        }

    private companion object {
        @Volatile
        var processRuntime: AgentRuntime? = null
    }
}
