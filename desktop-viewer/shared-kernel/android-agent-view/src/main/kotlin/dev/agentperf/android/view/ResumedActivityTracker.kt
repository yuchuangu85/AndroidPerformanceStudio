package dev.agentperf.android.view

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

class ResumedActivityTracker(
    application: Application,
) : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var resumedActivity = WeakReference<Activity>(null)

    init {
        application.registerActivityLifecycleCallbacks(this)
    }

    fun currentActivity(): Activity? = resumedActivity.get()

    override fun onActivityResumed(activity: Activity) {
        resumedActivity = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumedActivity.get() === activity) {
            resumedActivity.clear()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
