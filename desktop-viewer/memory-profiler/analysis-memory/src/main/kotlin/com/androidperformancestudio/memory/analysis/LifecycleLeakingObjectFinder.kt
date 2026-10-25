package com.androidperformancestudio.memory.analysis

import shark.HeapGraph
import shark.KeyedWeakReferenceFinder
import shark.LeakingObjectFinder

/** Limits offline discovery to Activity / Fragment lifecycle evidence and retained watched objects. */
internal object LifecycleLeakingObjectFinder : LeakingObjectFinder {
    override fun findLeakingObjectIds(graph: HeapGraph): Set<Long> =
        buildSet {
            // Shark handles modern and legacy ObjectWatcher fields, cleared refs and retention time.
            addAll(KeyedWeakReferenceFinder.findLeakingObjectIds(graph).filter(graph::objectExists))
            graph.instances.forEach { instance ->
                if (LifecycleObjectInspector.isLeakingObject(instance)) {
                    add(instance.objectId)
                }
            }
        }
}
