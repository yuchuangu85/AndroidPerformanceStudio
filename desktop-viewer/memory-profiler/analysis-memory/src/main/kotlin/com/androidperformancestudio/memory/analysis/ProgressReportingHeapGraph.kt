package com.androidperformancestudio.memory.analysis

import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.HeapInstance

/** Adds cancellation checkpoints to Shark's candidate scans, path traversal and retained-size reads. */
internal class ProgressReportingHeapGraph(
    private val delegate: HeapGraph,
    private val checkpoint: () -> Unit,
) : HeapGraph by delegate {
    private var operationsUntilCheckpoint = CHECKPOINT_INTERVAL

    override val instances: Sequence<HeapInstance>
        get() = delegate.instances.onEach { checkProgress() }

    override fun findObjectById(objectId: Long): HeapObject {
        checkProgress()
        return delegate.findObjectById(objectId)
    }

    override fun findObjectByIndex(objectIndex: Int): HeapObject {
        checkProgress()
        return delegate.findObjectByIndex(objectIndex)
    }

    private fun checkProgress() {
        operationsUntilCheckpoint--
        if (operationsUntilCheckpoint == 0) {
            operationsUntilCheckpoint = CHECKPOINT_INTERVAL
            checkpoint()
        }
    }

    private companion object {
        const val CHECKPOINT_INTERVAL = 256
    }
}
