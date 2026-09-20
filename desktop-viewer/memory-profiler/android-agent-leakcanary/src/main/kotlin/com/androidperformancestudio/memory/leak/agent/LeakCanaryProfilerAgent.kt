@file:Suppress("LongMethod", "TooManyFunctions", "TooGenericExceptionCaught")

package com.androidperformancestudio.memory.leak.agent

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Process
import android.database.Cursor
import android.net.Uri
import com.androidperformancestudio.memory.leak.protocol.LEAK_CANARY_AGENT_PORT
import com.androidperformancestudio.memory.leak.protocol.LEAK_CANARY_AGENT_PROTOCOL_VERSION
import com.androidperformancestudio.memory.leak.protocol.LeakCanaryAgentCodec
import com.androidperformancestudio.memory.leak.protocol.LeakCanaryAgentCommand
import com.androidperformancestudio.memory.leak.protocol.LeakCanaryAgentEvent
import com.androidperformancestudio.memory.leak.protocol.LeakCanaryAgentResponse
import com.androidperformancestudio.memory.leak.protocol.LeakCanaryAgentSessionCodec
import com.androidperformancestudio.memory.leak.protocol.LeakCanaryAgentSessionDescriptor
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

public class LeakCanaryBridgeProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        LeakCanaryBridge.install(appContext)
        LeakCanaryProfiler.start(appContext)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}

public object LeakCanaryProfiler {
    private val started = AtomicBoolean()
    private var runtime: LeakCanaryRuntime? = null

    @JvmStatic
    public fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        runtime = LeakCanaryRuntime(context.applicationContext).also { it.start() }
    }
}

private class LeakCanaryRuntime(
    private val context: Context,
) : Application.ActivityLifecycleCallbacks {
    private val events = LeakEventBuffer(context.packageName)
    private val watcher = WeakObjectWatcher(events)
    private val server = LeakCanaryAgentServer(context, events, watcher)

    fun start() {
        (context as? Application)?.registerActivityLifecycleCallbacks(this)
            ?: throw IllegalStateException("Application context is not an Application")
        watcher.start()
        server.start()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        watchFragmentsReflectively(activity)
        watcher.watch(
            target = activity,
            kind = "ACTIVITY_DESTROYED",
            description = "${activity.javaClass.name} destroyed",
        )
    }

    private fun watchFragmentsReflectively(activity: Activity) {
        runCatching {
            val manager =
                activity.javaClass.methods
                    .firstOrNull { it.name == "getSupportFragmentManager" && it.parameterTypes.isEmpty() }
                    ?.invoke(activity) ?: return
            val fragments =
                manager.javaClass.methods
                    .firstOrNull { it.name == "getFragments" && it.parameterTypes.isEmpty() }
                    ?.invoke(manager) as? Iterable<*> ?: return
            fragments.filterNotNull().forEach { fragment ->
                val removing =
                    fragment.javaClass.methods
                        .firstOrNull { it.name == "isRemoving" && it.parameterTypes.isEmpty() }
                        ?.invoke(fragment) as? Boolean ?: false
                val detached =
                    fragment.javaClass.methods
                        .firstOrNull { it.name == "isDetached" && it.parameterTypes.isEmpty() }
                        ?.invoke(fragment) as? Boolean ?: false
                if (removing || detached) {
                    watcher.watch(
                        target = fragment,
                        kind = "FRAGMENT_DESTROYED",
                        description = "${fragment.javaClass.name} detached from ${activity.javaClass.name}",
                    )
                }
            }
        }
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
}

private class LeakEventBuffer(
    private val packageName: String,
) {
    private val lock = Any()
    private val capacity = 4_000
    private val events = ArrayDeque<LeakCanaryAgentEvent>(capacity)
    private var sequence = 0L
    private var dropped = 0L

    fun add(
        kind: String,
        className: String? = null,
        description: String? = null,
        watchId: String? = null,
        retained: Boolean? = null,
        source: String = "aps-bridge",
    ) {
        synchronized(lock) {
            sequence++
            if (events.size == capacity) {
                events.removeFirst()
                dropped++
            }
            events.addLast(
                LeakCanaryAgentEvent(
                    sequence = sequence,
                    kind = kind,
                    monotonicNs = System.nanoTime(),
                    packageName = packageName,
                    processName = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else null,
                    processId = Process.myPid(),
                    className = className,
                    description = description,
                    watchId = watchId,
                    retained = retained,
                    source = source,
                ),
            )
        }
    }

    fun beginSession(): String = synchronized(lock) {
        events.clear()
        dropped = 0L
        "session-${UUID.randomUUID()}"
    }

    fun poll(cursor: Long, maxEvents: Int): LeakCanaryPoll {
        synchronized(lock) {
            val boundedMax = maxEvents.coerceIn(1, 2_000)
            val selected = events.filter { it.sequence > cursor }.take(boundedMax)
            return LeakCanaryPoll(
                events = selected,
                droppedEvents = dropped,
                latestSequence = sequence,
            )
        }
    }
}

private data class LeakCanaryPoll(
    val events: List<LeakCanaryAgentEvent>,
    val droppedEvents: Long,
    val latestSequence: Long,
)

private class WeakObjectWatcher(
    private val events: LeakEventBuffer,
) {
    private data class Watched(
        val id: String,
        val reference: WeakReference<Any>,
        val className: String,
        val description: String,
        val kind: String,
        val startedAtNs: Long,
        var checks: Int = 0,
    )

    private val queue = ReferenceQueue<Any>()
    private val watched = ConcurrentHashMap<String, Watched>()
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "aps-leakcanary-watcher").apply { isDaemon = true }
        }
    private val lastGcNs = AtomicLong(0L)

    fun start() {
        executor.scheduleAtFixedRate(::check, 1L, 1L, TimeUnit.SECONDS)
    }

    fun watch(target: Any, kind: String, description: String) {
        val id = "watch-${UUID.randomUUID()}"
        val watchedObject =
            Watched(
                id = id,
                reference = WeakReference(target, queue),
                className = target.javaClass.name,
                description = description,
                kind = kind,
                startedAtNs = System.nanoTime(),
            )
        watched[id] = watchedObject
        LeakCanaryBridge.expectWeaklyReachable(target, description)
        events.add(
            kind = kind,
            className = watchedObject.className,
            description = description,
            watchId = id,
            retained = null,
        )
    }

    fun forceCheck() {
        check()
    }

    fun activeCount(): Int = watched.size

    private fun check() {
        val now = System.nanoTime()
        if (now - lastGcNs.get() > TimeUnit.SECONDS.toNanos(2) && lastGcNs.compareAndSet(lastGcNs.get(), now)) {
            System.gc()
            System.runFinalization()
        }
        while (true) {
            val reference = queue.poll() ?: break
            val entry = watched.values.firstOrNull { it.reference === reference }
            if (entry != null && watched.remove(entry.id) != null) {
                events.add(
                    kind = "OBJECT_COLLECTED",
                    className = entry.className,
                    description = entry.description,
                    watchId = entry.id,
                    retained = false,
                )
            }
        }
        watched.values.toList().forEach { entry ->
            if (now - entry.startedAtNs < TimeUnit.SECONDS.toNanos(2)) return@forEach
            if (entry.reference.get() == null) return@forEach
            entry.checks++
            if (entry.checks == 2) {
                watched.remove(entry.id)
                events.add(
                    kind = "OBJECT_RETAINED",
                    className = entry.className,
                    description = entry.description,
                    watchId = entry.id,
                    retained = true,
                )
            }
        }
    }
}

private object LeakCanaryBridge {
    private val availability: Boolean by lazy {
        runCatching { Class.forName("leakcanary.AppWatcher") }.isSuccess
    }

    fun install(context: Context) {
        if (!availability) return
        runCatching {
            val appWatcherClass = Class.forName("leakcanary.AppWatcher")
            val singleton = appWatcherClass.getField("INSTANCE").get(null)
            val method =
                appWatcherClass.methods.firstOrNull {
                    it.name == "manualInstall" && it.parameterTypes.size == 1
                }
            method?.invoke(singleton, context.applicationContext)
        }
    }

    fun expectWeaklyReachable(target: Any, description: String) {
        if (!availability) return
        runCatching {
            val appWatcherClass = Class.forName("leakcanary.AppWatcher")
            val singleton = appWatcherClass.getField("INSTANCE").get(null)
            val objectWatcher = appWatcherClass.getMethod("getObjectWatcher").invoke(singleton)
            val method =
                objectWatcher.javaClass.methods.firstOrNull {
                    it.name == "expectWeaklyReachable" && it.parameterTypes.size == 2
                } ?: return
            method.invoke(objectWatcher, target, description)
        }
    }

    fun isAvailable(): Boolean = availability
}

private class LeakCanaryAgentServer(
    private val context: Context,
    private val events: LeakEventBuffer,
    private val watcher: WeakObjectWatcher,
) {
    private val executor =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "aps-leakcanary-agent-client").apply { isDaemon = true }
        }
    private val started = AtomicBoolean()
    private var token: String = ""
    private var sessionId: String? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        token = randomToken()
        writeDescriptor()
        Thread(::serve, "aps-leakcanary-agent-server").apply { isDaemon = true; start() }
        events.add(
            kind = "AGENT_READY",
            description = "LeakCanary bridge ready",
            source = if (LeakCanaryBridge.isAvailable()) "leakcanary" else "aps-bridge",
        )
    }

    private fun serve() {
        runCatching {
            ServerSocket().use { server ->
                server.reuseAddress = true
                server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), LEAK_CANARY_AGENT_PORT))
                while (started.get()) {
                    executor.execute { client(server.accept()) }
                }
            }
        }
    }

    private fun client(socket: Socket) {
        socket.use {
            runCatching {
                val command = LeakCanaryAgentCodec.readCommand(it.getInputStream())
                LeakCanaryAgentCodec.writeResponse(it.getOutputStream(), handle(command))
            }.onFailure { error ->
                runCatching {
                    LeakCanaryAgentCodec.writeResponse(
                        it.getOutputStream(),
                        LeakCanaryAgentResponse(type = "ERROR", message = error.message ?: "Agent request failed"),
                    )
                }
            }
        }
    }

    private fun handle(command: LeakCanaryAgentCommand): LeakCanaryAgentResponse {
        if (command.protocolVersion != LEAK_CANARY_AGENT_PROTOCOL_VERSION) {
            return LeakCanaryAgentResponse(type = "ERROR", message = "Unsupported protocol version")
        }
        if (command.token != token) {
            return LeakCanaryAgentResponse(type = "ERROR", message = "Invalid session token")
        }
        return when (command.type) {
            "PING" -> response("PONG")
            "START_SESSION" -> {
                sessionId = events.beginSession()
                events.add(kind = "SESSION_STARTED", description = sessionId)
                response("ACK")
            }
            "FORCE_CHECK" -> {
                watcher.forceCheck()
                response("ACK")
            }
            "POLL" -> {
                watcher.forceCheck()
                val poll = events.poll(command.cursor, command.maxEvents)
                response("EVENTS", poll.events, poll.droppedEvents, poll.latestSequence)
            }
            "STOP_SESSION" -> {
                events.add(kind = "SESSION_STOPPED", description = sessionId)
                sessionId = null
                response("ACK")
            }
            else -> LeakCanaryAgentResponse(type = "ERROR", message = "Unknown command: ${command.type}")
        }
    }

    private fun response(
        type: String,
        eventList: List<LeakCanaryAgentEvent> = emptyList(),
        droppedEvents: Long = 0L,
        latestSequence: Long = 0L,
    ): LeakCanaryAgentResponse =
        LeakCanaryAgentResponse(
            type = type,
            packageName = context.packageName,
            processId = Process.myPid(),
            sessionId = sessionId,
            events = eventList,
            droppedEvents = droppedEvents,
            latestSequence = latestSequence,
            activeWatchCount = watcher.activeCount(),
            leakCanaryAvailable = LeakCanaryBridge.isAvailable(),
        )

    private fun writeDescriptor() {
        val directory = File(context.filesDir, "aps-leakcanary").apply { mkdirs() }
        val descriptor =
            LeakCanaryAgentSessionDescriptor(
                socketPort = LEAK_CANARY_AGENT_PORT,
                token = token,
                packageName = context.packageName,
                processName = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else null,
            )
        File(directory, "session.json").writeText(LeakCanaryAgentSessionCodec.encode(descriptor))
    }

    private fun randomToken(): String =
        ByteArray(32).also(SecureRandom()::nextBytes).joinToString(separator = "") { "%02x".format(it) }
}
