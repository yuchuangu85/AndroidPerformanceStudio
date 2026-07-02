package dev.agentperf.android.core

import android.content.Context
import android.net.LocalServerSocket
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.Executors

class AgentServer(
    context: Context,
    private val captureProvider: CaptureProvider = CaptureProvider {
        throw CaptureUnavailableException("CAPTURE_UNAVAILABLE", "Capture provider is not configured")
    },
) {
    private val applicationContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "agentperf-local-socket").apply { isDaemon = true }
    }

    fun start(): SessionDescriptor {
        val packageName = applicationContext.packageName
        val socketName = "agentperf.${packageName.replace('.', '_')}"
        val descriptor = SessionDescriptor(
            protocolMajor = 1,
            protocolMinor = 0,
            socketName = socketName,
            token = randomToken(),
        )
        persistSession(descriptor)
        val requestHandler = AgentRequestHandler(
            token = descriptor.token,
            captureProvider = captureProvider,
        )
        val server = LocalServerSocket(socketName)
        executor.execute {
            while (!Thread.currentThread().isInterrupted) {
                val client = server.accept()
                client.use { socket ->
                    val request = socket.inputStream.bufferedReader().readLine().orEmpty()
                    requestHandler.handle(request, socket.outputStream)
                }
            }
        }
        return descriptor
    }

    private fun persistSession(descriptor: SessionDescriptor) {
        val directory = File(applicationContext.filesDir, "agentperf").apply { mkdirs() }
        File(directory, "session.json").writeText(
            """
            {
              "protocolMajor": ${descriptor.protocolMajor},
              "protocolMinor": ${descriptor.protocolMinor},
              "socketName": "${descriptor.socketName}",
              "token": "${descriptor.token}"
            }
            """.trimIndent(),
        )
    }

    private fun randomToken(): String {
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }
}

data class SessionDescriptor(
    val protocolMajor: Int,
    val protocolMinor: Int,
    val socketName: String,
    val token: String,
)
