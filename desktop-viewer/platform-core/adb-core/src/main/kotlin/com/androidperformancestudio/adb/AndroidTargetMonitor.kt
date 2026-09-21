package com.androidperformancestudio.adb

import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbDevice
import com.androidperformancestudio.platform.toolchain.HostCancellationSignal
import com.androidperformancestudio.platform.toolchain.HostCommandResult
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Receives cached Android target discovery updates. Pages register once and refresh through
 * [AndroidTargetMonitor], rather than owning separate device, package, process, or thread polling.
 */
public interface AndroidTargetListener {
    public fun onDevices(result: AdbDevicesResult) = Unit

    public fun onTargetSnapshot(
        serial: String,
        result: StudioResult<AdbTargetSnapshot>,
    ) = Unit

    public fun onApplications(
        serial: String,
        result: StudioResult<List<AndroidPackage>>,
    ) = Unit

    public fun onProcesses(
        serial: String,
        result: StudioResult<List<AndroidProcess>>,
    ) = Unit

    public fun onThreads(
        serial: String,
        pid: Int,
        result: StudioResult<List<AndroidThread>>,
    ) = Unit
}

/** A closeable registration for one [AndroidTargetListener]. */
public fun interface AndroidTargetSubscription : AutoCloseable {
    override fun close()
}

/**
 * Shared ADB discovery boundary for devices, installed applications, processes, and threads.
 *
 * Each refresh updates the cached result and notifies every registered listener. New listeners can
 * request replay to immediately receive the latest known values without independently polling ADB.
 */
public class AndroidTargetMonitor internal constructor(
    private val discovery: AndroidTargetDiscovery,
) {
    private val listeners = CopyOnWriteArraySet<AndroidTargetListener>()
    private val devicesMutex = Mutex()
    private val targetMutexes = ConcurrentHashMap<String, Mutex>()
    private val threadMutexes = ConcurrentHashMap<AndroidThreadTarget, Mutex>()

    @Volatile
    private var devicesResult: AdbDevicesResult? = null
    private val targetsBySerial = ConcurrentHashMap<String, StudioResult<AdbTargetSnapshot>>()
    private val applicationsBySerial = ConcurrentHashMap<String, StudioResult<List<AndroidPackage>>>()
    private val processesBySerial = ConcurrentHashMap<String, StudioResult<List<AndroidProcess>>>()
    private val threadsByTarget = ConcurrentHashMap<AndroidThreadTarget, StudioResult<List<AndroidThread>>>()

    /** Registers a listener. Cached discovery results are replayed by default. */
    public fun register(
        listener: AndroidTargetListener,
        replay: Boolean = true,
    ): AndroidTargetSubscription {
        listeners += listener
        if (replay) replay(listener)
        return AndroidTargetSubscription { listeners -= listener }
    }

    /** Refreshes the connected-device snapshot once and publishes it to all listeners. */
    public suspend fun refreshDevices(
        cancellationSignal: HostCancellationSignal = HostCancellationSignal(),
    ): AdbDevicesResult {
        val result = devicesMutex.withLock { discovery.devices(cancellationSignal) }
        devicesResult = result
        listeners.forEach { it.onDevices(result) }
        return result
    }

    /** Refreshes installed applications and processes for [serial] once and publishes both updates. */
    public suspend fun refreshTargets(
        serial: String,
        cancellationSignal: HostCancellationSignal = HostCancellationSignal(),
    ): StudioResult<AdbTargetSnapshot> {
        val result =
            targetMutexes
                .getOrPut(serial, ::Mutex)
                .withLock { discovery.targets(serial, cancellationSignal) }
        targetsBySerial[serial] = result
        val applications = result.applications()
        val processes = result.processes()
        applicationsBySerial[serial] = applications
        processesBySerial[serial] = processes
        listeners.forEach { listener ->
            listener.onTargetSnapshot(serial, result)
            listener.onApplications(serial, applications)
            listener.onProcesses(serial, processes)
        }
        return result
    }

    /** Refreshes threads for [pid] on [serial] once and publishes the update. */
    public suspend fun refreshThreads(
        serial: String,
        pid: Int,
        cancellationSignal: HostCancellationSignal = HostCancellationSignal(),
    ): StudioResult<List<AndroidThread>> {
        val target = AndroidThreadTarget(serial, pid)
        val result =
            threadMutexes
                .getOrPut(target, ::Mutex)
                .withLock { discovery.threads(serial, pid, cancellationSignal) }
        threadsByTarget[target] = result
        listeners.forEach { it.onThreads(serial, pid, result) }
        return result
    }

    private fun replay(listener: AndroidTargetListener) {
        devicesResult?.let(listener::onDevices)
        targetsBySerial.forEach { (serial, result) -> listener.onTargetSnapshot(serial, result) }
        applicationsBySerial.forEach { (serial, result) -> listener.onApplications(serial, result) }
        processesBySerial.forEach { (serial, result) -> listener.onProcesses(serial, result) }
        threadsByTarget.forEach { (target, result) -> listener.onThreads(target.serial, target.pid, result) }
    }
}

/** Reuses one monitor per normalized ADB executable inside the desktop process. */
public object AndroidTargetMonitors {
    private val monitors = ConcurrentHashMap<Path, AndroidTargetMonitor>()

    public fun shared(adbExecutable: Path): AndroidTargetMonitor {
        val cacheKey = adbExecutable.toAbsolutePath().normalize()
        return monitors.getOrPut(cacheKey) { AndroidTargetMonitor(ProcessAndroidTargetDiscovery(adbExecutable)) }
    }

    /** Creates an isolated monitor when a caller needs a custom process invocation, such as tests. */
    public fun create(
        adbExecutable: Path,
        processInvocation: ProcessInvocation,
    ): AndroidTargetMonitor =
        AndroidTargetMonitor(
            ProcessAndroidTargetDiscovery(adbExecutable, processInvocation),
        )
}

internal interface AndroidTargetDiscovery {
    suspend fun devices(cancellationSignal: HostCancellationSignal): AdbDevicesResult

    suspend fun targets(
        serial: String,
        cancellationSignal: HostCancellationSignal,
    ): StudioResult<AdbTargetSnapshot>

    suspend fun threads(
        serial: String,
        pid: Int,
        cancellationSignal: HostCancellationSignal,
    ): StudioResult<List<AndroidThread>>
}

internal class ProcessAndroidTargetDiscovery(
    private val adbExecutable: Path,
    private val processInvocation: ProcessInvocation = { request, signal ->
        com.androidperformancestudio.platform.toolchain.StudioHostProcessExecutor().run(request, signal)
    },
) : AndroidTargetDiscovery {
    override suspend fun devices(cancellationSignal: HostCancellationSignal): AdbDevicesResult =
        when (val result = AdbDeviceRefresher(adbExecutable, processInvocation = processInvocation).refresh(cancellationSignal)) {
            is StudioResult.Failure -> result
            is StudioResult.Success ->
                StudioResult.Success(
                    result.value.map { device ->
                        if (device.state != com.androidperformancestudio.platform.adb.AdbDeviceState.ONLINE) {
                            device
                        } else {
                            resolveModel(device.serial, cancellationSignal)
                                ?.let { model -> device.copy(model = model) }
                                ?: device
                        }
                    },
                )
        }

    private suspend fun resolveModel(
        serial: String,
        cancellationSignal: HostCancellationSignal,
    ): String? {
        val request =
            HostProcessRequest(
                executable = adbExecutable,
                arguments = listOf("-s", serial, "shell", "getprop", "ro.product.model"),
            )
        return when (val result = processInvocation(request, cancellationSignal)) {
            is HostCommandResult.Completed -> result.output.stdout.text.trim().usableModel()
            is HostCommandResult.Failed -> null
        }
    }

    override suspend fun targets(
        serial: String,
        cancellationSignal: HostCancellationSignal,
    ): StudioResult<AdbTargetSnapshot> =
        AdbTargetCatalog(adbExecutable, processInvocation).refresh(serial, cancellationSignal)

    private fun String.usableModel(): String? =
        takeIf { value -> value.isNotBlank() && value.lowercase() !in UNUSABLE_MODELS }

    override suspend fun threads(
        serial: String,
        pid: Int,
        cancellationSignal: HostCancellationSignal,
    ): StudioResult<List<AndroidThread>> =
        AdbTargetCatalog(adbExecutable, processInvocation).listThreads(serial, pid, cancellationSignal)

    private companion object {
        val UNUSABLE_MODELS = setOf("unknown", "<unknown>", "null", "n/a", "na")
    }
}

internal data class AndroidThreadTarget(
    val serial: String,
    val pid: Int,
)

private fun StudioResult<AdbTargetSnapshot>.applications(): StudioResult<List<AndroidPackage>> =
    when (this) {
        is StudioResult.Failure -> this
        is StudioResult.Success -> StudioResult.Success(value.packages)
    }

private fun StudioResult<AdbTargetSnapshot>.processes(): StudioResult<List<AndroidProcess>> =
    when (this) {
        is StudioResult.Failure -> this
        is StudioResult.Success -> StudioResult.Success(value.processes)
    }

/** A process that belongs to a debuggable or profileable application package. */
public data class AndroidApplicationProcess(
    val pid: Int,
    val name: String,
    val user: String,
    val packageName: String,
)

/**
 * Matches running main and secondary processes to packages that shell tooling can profile.
 *
 * Keeping this rule here prevents each profiler from inventing a different selection policy.
 */
public fun AdbTargetSnapshot.profileableOrDebuggableProcesses(): List<AndroidApplicationProcess> {
    val eligiblePackages =
        packages
            .filter { it.debuggable || it.profileableByShell }
            .map { it.packageName }
    return processes
        .mapNotNull { process ->
            val packageName =
                eligiblePackages.firstOrNull { candidate ->
                    process.name == candidate || process.name.startsWith("$candidate:")
                } ?: return@mapNotNull null
            AndroidApplicationProcess(process.pid, process.name, process.user, packageName)
        }.sortedWith(compareBy<AndroidApplicationProcess> { it.name }.thenBy { it.pid })
}
