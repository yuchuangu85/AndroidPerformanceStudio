package dev.agentperf.android.view

import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import dev.agentperf.protocol.Bounds
import dev.agentperf.protocol.UiNode
import dev.agentperf.protocol.ViewNode

class ViewTreeCollector {
    fun collect(root: View): ViewNode = collect(root, "root")

    private fun collect(view: View, path: String): ViewNode {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val children: List<UiNode> = if (view is ViewGroup) {
            (0 until view.childCount).map { index ->
                collect(view.getChildAt(index), "$path/$index")
            }
        } else {
            emptyList()
        }
        return ViewNode(
            id = resourceName(view) ?: path,
            className = view.javaClass.name,
            bounds = Bounds(
                left = location[0],
                top = location[1],
                right = location[0] + view.width,
                bottom = location[1] + view.height,
            ),
            visible = view.visibility == View.VISIBLE && view.isShown,
            alpha = view.alpha,
            children = children,
            resourceName = resourceName(view),
            text = (view as? android.widget.TextView)?.text?.toString(),
        )
    }

    private fun resourceName(view: View): String? {
        if (view.id == View.NO_ID) return null
        return try {
            view.resources.getResourceName(view.id)
        } catch (_: Resources.NotFoundException) {
            null
        }
    }
}
