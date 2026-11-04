package com.androidperformancestudio.memory.analysis

import shark.LeakTraceObject
import shark.ObjectInspector

/** Shark 2.14 drops IDs from public traces but preserves inspector labels on each exact path node. */
internal object SharkObjectIdentity {
    private const val LABEL_PREFIX = "com.androidperformancestudio.memory.analysis.objectId="

    val inspector =
        ObjectInspector { reporter ->
            reporter.labels += "$LABEL_PREFIX${reporter.heapObject.objectId}"
        }

    fun objectId(node: LeakTraceObject): Long =
        node.labels
            .single { it.startsWith(LABEL_PREFIX) }
            .removePrefix(LABEL_PREFIX)
            .toLong()

    fun labels(node: LeakTraceObject): List<String> = node.labels.filterNot { it.startsWith(LABEL_PREFIX) }
}
