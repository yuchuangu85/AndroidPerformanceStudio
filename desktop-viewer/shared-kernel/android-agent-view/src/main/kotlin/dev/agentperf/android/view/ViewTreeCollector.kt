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
    fun collect(root: View, windowId: String = ""): ViewNode =
        collectRecursive(root, if (windowId.isBlank()) "root" else "$windowId/root")

    private fun collectRecursive(view: View, path: String): ViewNode {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val viewChildren: List<UiNode> = if (view is ViewGroup) {
            (0 until view.childCount).map { index ->
                collectRecursive(view.getChildAt(index), "$path/$index")
            }
        } else {
            emptyList()
        }
        val composeSemantics = ComposeSemanticsCollector.collect(
            composeView = view,
            path = "$path/compose",
            screenOffsetX = location[0],
            screenOffsetY = location[1],
        )
        val children = listOfNotNull(composeSemantics) + viewChildren
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
        val attributes = ViewAttributes(
            visibility = ViewAttributeLabels.visibility(view.visibility),
            layoutBounds = Bounds(
                left = view.left,
                top = view.top,
                right = view.right,
                bottom = view.bottom,
            ),
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
            layoutParamsClass = layoutParams?.javaClass?.name,
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
        return attributes.copy(rawProperties = attributes.toRawProperties(alpha = view.alpha))
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

    private fun ViewAttributes.toRawProperties(alpha: Float): Map<String, String> =
        buildMap<String, String> {
            visibility?.let { put("misc:visibility", it) }
            layoutBounds?.let {
                put("layout:left", it.left.toString())
                put("layout:top", it.top.toString())
                put("layout:right", it.right.toString())
                put("layout:bottom", it.bottom.toString())
            }
            elevation?.let { put("drawing:elevation", it.toString()) }
            z?.let { put("drawing:z", it.toString()) }
            put("drawing:alpha", alpha.toString())
            translationX?.let { put("drawing:translationX", it.toString()) }
            translationY?.let { put("drawing:translationY", it.toString()) }
            translationZ?.let { put("drawing:translationZ", it.toString()) }
            rotation?.let { put("drawing:rotation", it.toString()) }
            rotationX?.let { put("drawing:rotationX", it.toString()) }
            rotationY?.let { put("drawing:rotationY", it.toString()) }
            scaleX?.let { put("drawing:scaleX", it.toString()) }
            scaleY?.let { put("drawing:scaleY", it.toString()) }
            pivotX?.let { put("drawing:pivotX", it.toString()) }
            pivotY?.let { put("drawing:pivotY", it.toString()) }
            padding?.let {
                put("padding:paddingLeft", it.left.toString())
                put("padding:paddingTop", it.top.toString())
                put("padding:paddingRight", it.right.toString())
                put("padding:paddingBottom", it.bottom.toString())
            }
            margin?.let {
                put("layoutParams:leftMargin", it.left.toString())
                put("layoutParams:topMargin", it.top.toString())
                put("layoutParams:rightMargin", it.right.toString())
                put("layoutParams:bottomMargin", it.bottom.toString())
            }
            layoutParamsClass?.let { put("layoutParams:class", it) }
            layoutWidth?.let { put("layoutParams:width", it.toString()) }
            layoutHeight?.let { put("layoutParams:height", it.toString()) }
            measuredWidth?.let { put("measurement:measuredWidth", it.toString()) }
            measuredHeight?.let { put("measurement:measuredHeight", it.toString()) }
            minWidth?.let { put("measurement:minWidth", it.toString()) }
            minHeight?.let { put("measurement:minHeight", it.toString()) }
            scrollX?.let { put("scrolling:scrollX", it.toString()) }
            scrollY?.let { put("scrolling:scrollY", it.toString()) }
            clipBounds?.let {
                put(
                    "drawing:clipBounds",
                    "Rect(${it.left}, ${it.top} - ${it.right}, ${it.bottom})",
                )
            }
            clipChildren?.let { put("drawing:clipChildren", it.toString()) }
            clipToPadding?.let { put("drawing:clipToPadding", it.toString()) }
            background?.let { put("drawing:background", it) }
            backgroundColor?.let { put("drawing:backgroundColor", it) }
            foreground?.let { put("drawing:foreground", it) }
            opaque?.let { put("drawing:opaque", it.toString()) }
            willNotDraw?.let { put("drawing:willNotDraw", it.toString()) }
            hardwareAccelerated?.let { put("drawing:hardwareAccelerated", it.toString()) }
            layerType?.let { put("drawing:layerType", it) }
            layoutRequested?.let { put("layout:layoutRequested", it.toString()) }
            enabled?.let { put("misc:enabled", it.toString()) }
            clickable?.let { put("misc:clickable", it.toString()) }
            longClickable?.let { put("misc:longClickable", it.toString()) }
            focusable?.let { put("focus:isFocusable", it.toString()) }
            focused?.let { put("focus:isFocused", it.toString()) }
            selected?.let { put("misc:selected", it.toString()) }
            contentDescription?.let { put("accessibility:getContentDescription()", it) }
        }.toSortedMap()
}
