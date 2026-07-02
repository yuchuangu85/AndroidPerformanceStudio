package dev.agentperf.android.startup

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.startup.Initializer
import dev.agentperf.android.core.AgentRuntime
import dev.agentperf.android.core.AgentServer
import dev.agentperf.android.core.StartResult

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
                AgentServer(context).start()
            }.also { processRuntime = it }
        }

    private companion object {
        @Volatile
        var processRuntime: AgentRuntime? = null
    }
}
