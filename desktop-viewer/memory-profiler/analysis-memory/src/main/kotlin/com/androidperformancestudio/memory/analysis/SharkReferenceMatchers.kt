package com.androidperformancestudio.memory.analysis

import shark.IgnoredReferenceMatcher
import shark.ReferenceMatcher
import shark.ReferencePattern.InstanceFieldPattern
import shark.ReferencePattern.JavaLocalPattern

/** Non-owning referents and runtime bookkeeping; deliberately not a framework/OEM leak catalog. */
internal object SharkReferenceMatchers {
    val nonStrongReferences: List<ReferenceMatcher> =
        buildList {
            listOf(
                "java.lang.ref.WeakReference",
                "java.lang.ref.SoftReference",
                "java.lang.ref.PhantomReference",
                "leakcanary.KeyedWeakReference",
                "com.squareup.leakcanary.KeyedWeakReference",
            ).forEach { className -> add(IgnoredReferenceMatcher(InstanceFieldPattern(className, "referent"))) }

            // Port of REFERENCES and FINALIZER_WATCHDOG_DAEMON, not the OEM/library leak catalog:
            // square/leakcanary v2.14, shark-android/src/main/java/shark/AndroidReferenceMatchers.kt.
            listOf("java.lang.ref.Finalizer", "java.lang.ref.FinalizerReference").forEach { className ->
                listOf("prev", "element", "next").forEach { fieldName ->
                    add(IgnoredReferenceMatcher(InstanceFieldPattern(className, fieldName)))
                }
            }
            listOf("prev", "next").forEach { fieldName ->
                add(IgnoredReferenceMatcher(InstanceFieldPattern("sun.misc.Cleaner", fieldName)))
            }
            add(IgnoredReferenceMatcher(JavaLocalPattern("FinalizerWatchdogDaemon")))
        }
}
