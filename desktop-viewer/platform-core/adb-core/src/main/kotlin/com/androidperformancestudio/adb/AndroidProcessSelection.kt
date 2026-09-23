package com.androidperformancestudio.adb

import java.util.concurrent.CopyOnWriteArraySet

/** One feature's device-bound process choice. Process discovery is shared; selection is not global. */
public data class AndroidProcessSelectionSnapshot<P>(
    val serial: String? = null,
    val processes: List<P> = emptyList(),
    val selectedPid: Int? = null,
)

/**
 * Owns device/process selection and publishes immutable snapshots to UI listeners.
 *
 * Create one instance per feature workspace. This avoids one feature's choice changing another
 * feature while retaining the shared [AndroidTargetMonitor] as the discovery boundary.
 */
public class AndroidProcessSelection<P>(
    private val pidOf: (P) -> Int,
    private val selectSingleProcess: Boolean = false,
) {
    private val listeners = CopyOnWriteArraySet<(AndroidProcessSelectionSnapshot<P>) -> Unit>()

    @Volatile
    private var current = AndroidProcessSelectionSnapshot<P>()

    public val snapshot: AndroidProcessSelectionSnapshot<P>
        get() = current

    /** Replay the current choice immediately so a newly created screen can render without polling. */
    @Synchronized
    public fun register(listener: (AndroidProcessSelectionSnapshot<P>) -> Unit): AndroidTargetSubscription {
        listeners += listener
        listener(current)
        return AndroidTargetSubscription { listeners -= listener }
    }

    /** Changing device invalidates its process list and PID before the new target list arrives. */
    @Synchronized
    public fun selectDevice(serial: String?) {
        publish(AndroidProcessSelectionSnapshot(serial = serial))
    }

    /** Ignore a late discovery result from the previously selected device. */
    @Synchronized
    public fun updateProcesses(serial: String, processes: List<P>): Boolean {
        val previous = current
        if (previous.serial != serial) return false
        val copied = processes.toList()
        // PID alone is not stable across process restarts. A changed process descriptor
        // invalidates the choice; matching descriptors cannot prove the same process instance.
        val selected =
            previous.selectedPid?.takeIf { pid ->
                previous.processes.firstOrNull { pidOf(it) == pid }
                    ?.let { old -> copied.any { pidOf(it) == pid && it == old } } == true
            } ?: if (selectSingleProcess && copied.size == 1) pidOf(copied.single()) else null
        publish(AndroidProcessSelectionSnapshot(serial, copied, selected))
        return true
    }

    /** Only a PID from the current device's visible choices can be selected. */
    @Synchronized
    public fun selectProcess(pid: Int): Boolean {
        val previous = current
        if (previous.serial == null || previous.processes.none { pidOf(it) == pid }) return false
        publish(previous.copy(selectedPid = pid))
        return true
    }

    private fun publish(next: AndroidProcessSelectionSnapshot<P>) {
        if (next == current) return
        current = next
        listeners.forEach { it(next) }
    }
}
