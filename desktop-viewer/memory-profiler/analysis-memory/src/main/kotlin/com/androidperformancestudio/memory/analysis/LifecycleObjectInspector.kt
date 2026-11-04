package com.androidperformancestudio.memory.analysis

import shark.HeapObject.HeapInstance
import shark.ObjectInspector
import shark.ObjectReporter

/**
 * Bounded lifecycle evidence: a null Fragment manager alone does not establish a past lifecycle.
 * Unknown states remain labels so retained ObjectWatcher evidence can independently identify leaks.
 */
internal object LifecycleObjectInspector : ObjectInspector {
    private const val ACTIVITY = "android.app.Activity"
    private const val PLATFORM_FRAGMENT = "android.app.Fragment"
    private const val SUPPORT_FRAGMENT = "android.support.v4.app.Fragment"
    private const val ANDROIDX_FRAGMENT = "androidx.fragment.app.Fragment"
    private const val LIFECYCLE_REGISTRY = "androidx.lifecycle.LifecycleRegistry"
    private val lifecycleClasses = setOf(ACTIVITY, PLATFORM_FRAGMENT, SUPPORT_FRAGMENT, ANDROIDX_FRAGMENT)

    fun isLeakingObject(instance: HeapInstance): Boolean = evidence(instance)?.isLeaking == true

    override fun inspect(reporter: ObjectReporter) {
        val instance = reporter.heapObject.asInstance ?: return
        val evidence = evidence(instance) ?: return
        when (evidence.isLeaking) {
            true -> reporter.leakingReasons += evidence.reason
            false -> reporter.notLeakingReasons += evidence.reason
            null -> reporter.labels += evidence.reason
        }
    }

    private fun evidence(instance: HeapInstance): LifecycleEvidence? {
        val lifecycleClass =
            instance.instanceClass.classHierarchy
                .map { it.name }
                .firstOrNull { it in lifecycleClasses }
                ?: return null
        return when (lifecycleClass) {
            ACTIVITY -> {
                val destroyed = instance[ACTIVITY, "mDestroyed"]?.value?.asBoolean
                LifecycleEvidence(destroyed, "Activity.mDestroyed is ${destroyed ?: "unavailable"}")
            }
            ANDROIDX_FRAGMENT -> androidxFragmentEvidence(instance)
            else -> legacyFragmentEvidence(instance, lifecycleClass)
        }
    }

    private fun legacyFragmentEvidence(
        instance: HeapInstance,
        lifecycleClass: String,
    ): LifecycleEvidence {
        val managerId = instance[lifecycleClass, "mFragmentManager"]?.value?.asObjectId
        val retaining = instance[lifecycleClass, "mRetaining"]?.value?.asBoolean == true
        // Platform and old support Fragment.setIndex() establish these; fresh instances have -1 / null.
        val index = instance[lifecycleClass, "mIndex"]?.value?.asInt
        val who = instance[lifecycleClass, "mWho"]?.value?.asNonNullObjectId
        val hasHistory = (index != null && index >= 0) || who != null
        val leaking =
            when {
                managerId == null -> null
                managerId != 0L -> false
                retaining || !hasHistory -> null
                else -> true
            }
        return LifecycleEvidence(
            leaking,
            "Fragment.mFragmentManager=$managerId, lifecycle history=$hasHistory, mRetaining=$retaining",
        )
    }

    private fun androidxFragmentEvidence(instance: HeapInstance): LifecycleEvidence {
        val registry = instance.referencedInstance(ANDROIDX_FRAGMENT, "mLifecycleRegistry")
        val state =
            registry?.referencedInstance(LIFECYCLE_REGISTRY, "state")
                ?: registry?.referencedInstance(LIFECYCLE_REGISTRY, "mState")
        val stateName = state?.get("java.lang.Enum", "name")?.value?.readAsJavaString()
        if (stateName == "INITIALIZED") return initializedFragmentEvidence(instance)
        return LifecycleEvidence(
            isLeaking =
                when (stateName) {
                    "DESTROYED" -> true
                    "CREATED", "STARTED", "RESUMED" -> false
                    else -> null
                },
            reason = "Fragment.mLifecycleRegistry.state is ${stateName ?: "unavailable"}",
        )
    }

    private fun initializedFragmentEvidence(instance: HeapInstance): LifecycleEvidence {
        // androidx FragmentStateManager.detach() calls Fragment.initState(), which replaces the registry.
        // Fragment.initState() also saves mPreviousWho and clears mFragmentManager; fresh instances lack history.
        val previousWho = instance[ANDROIDX_FRAGMENT, "mPreviousWho"]?.value?.asNonNullObjectId
        val managerId = instance[ANDROIDX_FRAGMENT, "mFragmentManager"]?.value?.asObjectId
        val removed = previousWho != null && managerId == 0L
        return LifecycleEvidence(
            isLeaking = if (removed) true else null,
            reason = "Fragment.mLifecycleRegistry.state is INITIALIZED, removal history=$removed",
        )
    }

    private fun HeapInstance.referencedInstance(
        declaringClass: String,
        field: String,
    ): HeapInstance? {
        val objectId = this[declaringClass, field]?.value?.asNonNullObjectId ?: return null
        return graph.findObjectByIdOrNull(objectId)?.asInstance
    }

    private data class LifecycleEvidence(
        val isLeaking: Boolean?,
        val reason: String,
    )
}
