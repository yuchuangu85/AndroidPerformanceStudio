package com.androidperformancestudio.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidProcessSelectionTest {
    private data class Process(val pid: Int, val name: String)

    @Test
    fun `listener replays current state and device switch rejects stale processes`() {
        val selection = AndroidProcessSelection(Process::pid)
        val events = mutableListOf<AndroidProcessSelectionSnapshot<Process>>()
        val subscription = selection.register(events::add)
        assertEquals(AndroidProcessSelectionSnapshot(), events.single())

        selection.selectDevice("device-a")
        assertFalse(selection.updateProcesses("device-b", listOf(Process(1, "stale"))))
        assertTrue(selection.updateProcesses("device-a", listOf(Process(42, "first"))))
        assertFalse(selection.selectProcess(43))
        assertTrue(selection.selectProcess(42))
        assertEquals(42, selection.snapshot.selectedPid)
        var replayed: AndroidProcessSelectionSnapshot<Process>? = null
        selection.register { replayed = it }.close()
        assertEquals(selection.snapshot, replayed)

        selection.selectDevice("device-b")
        assertEquals(AndroidProcessSelectionSnapshot(serial = "device-b"), selection.snapshot)
        assertFalse(selection.updateProcesses("device-a", listOf(Process(42, "late"))))
        assertFalse(selection.selectProcess(42))
        subscription.close()
        val eventCount = events.size
        selection.updateProcesses("device-b", listOf(Process(99, "new")))
        assertEquals(eventCount, events.size)
    }

    @Test
    fun `feature workspaces do not share their selected process`() {
        val frame = AndroidProcessSelection(Process::pid)
        val memory = AndroidProcessSelection(Process::pid)
        frame.selectDevice("device-a")
        memory.selectDevice("device-a")
        frame.updateProcesses("device-a", listOf(Process(42, "frame")))
        memory.updateProcesses("device-a", listOf(Process(42, "memory")))

        assertTrue(frame.selectProcess(42))
        assertEquals(42, frame.snapshot.selectedPid)
        assertEquals(null, memory.snapshot.selectedPid)
    }

    @Test
    fun `clearing the process keeps the device and discovered processes`() {
        val selection = AndroidProcessSelection(Process::pid)
        val events = mutableListOf<AndroidProcessSelectionSnapshot<Process>>()
        val subscription = selection.register(events::add)
        selection.selectDevice("device-a")
        selection.updateProcesses("device-a", listOf(Process(42, "first"), Process(43, "other")))
        assertTrue(selection.selectProcess(42))
        val eventsBeforeClear = events.size

        selection.clearProcess()

        assertEquals(null, selection.snapshot.selectedPid)
        assertEquals("device-a", selection.snapshot.serial)
        assertEquals(listOf(Process(42, "first"), Process(43, "other")), selection.snapshot.processes)
        assertEquals(eventsBeforeClear + 1, events.size)
        // Clearing an already empty choice does not publish again.
        selection.clearProcess()
        assertEquals(eventsBeforeClear + 1, events.size)
        subscription.close()
    }

    @Test
    fun `refresh retains valid selection and clears disappeared pid`() {
        val selection = AndroidProcessSelection(Process::pid, selectSingleProcess = true)
        selection.selectDevice("device-a")
        selection.updateProcesses("device-a", listOf(Process(42, "first")))
        assertEquals(42, selection.snapshot.selectedPid)
        selection.updateProcesses("device-a", listOf(Process(42, "first"), Process(43, "other")))
        assertEquals(42, selection.snapshot.selectedPid)
        selection.updateProcesses("device-a", listOf(Process(42, "replacement"), Process(43, "other")))
        assertEquals(null, selection.snapshot.selectedPid)
        selection.updateProcesses("device-a", listOf(Process(43, "other"), Process(44, "new")))
        assertEquals(null, selection.snapshot.selectedPid)
    }
}
