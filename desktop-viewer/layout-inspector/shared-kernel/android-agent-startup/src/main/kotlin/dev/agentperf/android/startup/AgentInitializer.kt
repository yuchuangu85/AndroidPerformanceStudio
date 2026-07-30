package com.androidperformancestudio.android.startup

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.startup.Initializer
import com.androidperformancestudio.android.core.AgentRuntime
import com.androidperformancestudio.android.core.AgentServer
import com.androidperformancestudio.android.core.StartResult
import com.androidperformancestudio.android.frame.FrameMetricsAgent
import com.androidperformancestudio.android.startup.metrics.StartupMetricsAgent
import com.androidperformancestudio.android.view.ActivityCaptureProvider
import com.androidperformancestudio.android.view.ResumedActivityTracker

class AgentInitializer : Initializer<StartResult> {
    override fun create(context: Context): StartResult {
        val applicationContext = context.applicationContext
        val debuggable = applicationContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable) StartupMetricsAgent.initializerEntered(applicationContext as Application)
        return runtime(applicationContext).start(debuggable)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    private fun runtime(context: Context): AgentRuntime =
        processRuntime ?: synchronized(this) {
            processRuntime ?: AgentRuntime {
                val application = context.applicationContext as Application
                val tracker = ResumedActivityTracker(application)
                val frameMetricsAgent = FrameMetricsAgent(application)
                val startupMetricsAgent = StartupMetricsAgent.create(application)
                AgentServer(
                    context = context,
                    captureProvider = ActivityCaptureProvider(tracker),
                    requestExtensions = listOf(frameMetricsAgent, startupMetricsAgent),
                ).start()
            }.also { processRuntime = it }
        }

    private companion object {
        @Volatile
        var processRuntime: AgentRuntime? = null
    }
}
