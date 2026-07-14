package dev.agentperf.android.view

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

class LifecycleActivitySlot<T : Any> {
    @Volatile
    private var latest = WeakReference<T>(null)

    fun current(): T? = latest.get()

    fun onStarted(value: T) {
        latest = WeakReference(value)
    }

    fun onResumed(value: T) {
        latest = WeakReference(value)
    }

    fun onPaused(value: T) = Unit

    fun onDestroyed(value: T) {
        if (latest.get() === value) latest.clear()
    }
}

class ResumedActivityTracker(
    application: Application,
) : Application.ActivityLifecycleCallbacks {
    private val activities = LifecycleActivitySlot<Activity>()

    init {
        application.registerActivityLifecycleCallbacks(this)
    }

    fun currentActivity(): Activity? = activities.current()

    override fun onActivityResumed(activity: Activity) {
        activities.onResumed(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        activities.onPaused(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) {
        activities.onStarted(activity)
    }
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        activities.onDestroyed(activity)
    }
}
