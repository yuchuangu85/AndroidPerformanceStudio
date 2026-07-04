package dev.agentperf.android.view

import android.content.res.Resources
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import dev.agentperf.protocol.Bounds
import dev.agentperf.protocol.EdgeInsets
import dev.agentperf.protocol.UiNode
import dev.agentperf.protocol.ViewAttributes
import dev.agentperf.protocol.ViewNode
import java.util.Locale

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
        val resourceName = resourceName(view)
        return ViewNode(
            id = path,
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
            resourceName = resourceName,
            text = (view as? android.widget.TextView)?.text?.toString(),
            attributes = collectAttributes(view),
        )
    }

    private fun collectAttributes(view: View): ViewAttributes {
        val layoutParams = view.layoutParams
        val margins = layoutParams as? ViewGroup.MarginLayoutParams
        val clipBounds = view.clipBounds
        return ViewAttributes(
            visibility = ViewAttributeLabels.visibility(view.visibility),
            elevation = view.elevation,
            z = view.z,
            translationX = view.translationX,
            translationY = view.translationY,
            translationZ = view.translationZ,
            rotation = view.rotation,
            rotationX = view.rotationX,
            rotationY = view.rotationY,
            scaleX = view.scaleX,
            scaleY = view.scaleY,
            pivotX = view.pivotX,
            pivotY = view.pivotY,
            padding = EdgeInsets(
                left = view.paddingLeft,
                top = view.paddingTop,
                right = view.paddingRight,
                bottom = view.paddingBottom,
            ),
            margin = margins?.let {
                EdgeInsets(
                    left = it.leftMargin,
                    top = it.topMargin,
                    right = it.rightMargin,
                    bottom = it.bottomMargin,
                )
            },
            layoutWidth = layoutParams?.width,
            layoutHeight = layoutParams?.height,
            measuredWidth = view.measuredWidth,
            measuredHeight = view.measuredHeight,
            minWidth = view.minimumWidth,
            minHeight = view.minimumHeight,
            scrollX = view.scrollX,
            scrollY = view.scrollY,
            clipBounds = clipBounds?.let {
                Bounds(left = it.left, top = it.top, right = it.right, bottom = it.bottom)
            },
            clipChildren = (view as? ViewGroup)?.clipChildren,
            clipToPadding = (view as? ViewGroup)?.clipToPadding,
            background = view.background?.javaClass?.name,
            backgroundColor = (view.background as? ColorDrawable)?.color?.toArgbHex(),
            foreground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                view.foreground?.javaClass?.name
            } else {
                null
            },
            opaque = view.isOpaque,
            willNotDraw = view.willNotDraw(),
            hardwareAccelerated = view.isHardwareAccelerated,
            layerType = ViewAttributeLabels.layerType(view.layerType),
            layoutRequested = view.isLayoutRequested,
            enabled = view.isEnabled,
            clickable = view.isClickable,
            longClickable = view.isLongClickable,
            focusable = view.isFocusable,
            focused = view.isFocused,
            selected = view.isSelected,
            contentDescription = view.contentDescription?.toString(),
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

    private fun Int.toArgbHex(): String =
        String.format(Locale.US, "#%08X", this)
}
