package dev.agentperf.android.view

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import dev.agentperf.protocol.WindowType

data class WindowRoot(
    val id: String,
    val title: String,
    val type: WindowType,
    val view: View,
)

fun interface WindowRootProvider {
    fun roots(activity: Activity): List<WindowRoot>
}

internal fun <T> processWindowRoots(
    sdkInt: Int,
    globalRoots: () -> List<T>,
    activityRoot: () -> T,
): List<T> =
    if (sdkInt >= Build.VERSION_CODES.Q) {
        globalRoots().ifEmpty { listOf(activityRoot()) }
    } else {
        listOf(activityRoot())
    }

internal object AndroidWindowRootProvider : WindowRootProvider {
    override fun roots(activity: Activity): List<WindowRoot> {
        val activityRoot = activity.window.decorView.rootView
        val roots = processWindowRoots(
            sdkInt = Build.VERSION.SDK_INT,
            globalRoots = {
                WindowInspector.getGlobalWindowViews()
                    .filter { it.isAttachedToWindow && it.width > 0 && it.height > 0 }
            },
            activityRoot = { activityRoot },
        )
        return roots.distinctBy { rootId(it) }.map { root ->
            val params = root.layoutParams as? WindowManager.LayoutParams
            WindowRoot(
                id = rootId(root),
                title = params?.title?.toString()
                    ?.takeIf(String::isNotBlank)
                    ?: root.javaClass.simpleName,
                type = windowType(root, activityRoot, params),
                view = root,
            )
        }
    }

    private fun rootId(view: View): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "window:${view.uniqueDrawingId}"
        } else {
            "window:activity"
        }

    private fun windowType(
        root: View,
        activityRoot: View,
        params: WindowManager.LayoutParams?,
    ): WindowType {
        if (root === activityRoot) return WindowType.ACTIVITY
        return when (params?.type) {
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL,
            WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG,
            -> WindowType.POPUP
            in WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW..
                WindowManager.LayoutParams.LAST_APPLICATION_WINDOW -> WindowType.DIALOG
            else -> WindowType.OTHER
        }
    }
}
