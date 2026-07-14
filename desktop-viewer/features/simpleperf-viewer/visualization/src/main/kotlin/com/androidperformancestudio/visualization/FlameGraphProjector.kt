package com.androidperformancestudio.visualization

data class FlameGraphNode(
    val label: String,
    val id: Long = 0,
    val parentId: Long? = null,
    val depth: Int,
    val startWeight: Long,
    val endWeight: Long,
    val filePath: String = "",
    val path: List<String> = emptyList(),
    val highlighted: Boolean = false,
) {
    init {
        require(depth >= 0) { "depth must not be negative" }
        require(endWeight > startWeight) { "node must have a positive weight" }
    }
}

data class WeightViewport(
    val startWeight: Long,
    val endWeightExclusive: Long,
) {
    init {
        require(endWeightExclusive > startWeight) { "viewport must have a positive weight" }
    }
}

data class FlameRectangle(
    val nodeId: Long,
    val parentId: Long?,
    val label: String,
    val filePath: String,
    val path: List<String>,
    val highlighted: Boolean,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

data class FlameProjectionPage(
    val rectangles: List<FlameRectangle>,
    val nextNodeIndex: Int?,
)

data class FlameProjectionParameters(
    val viewport: WeightViewport,
    val widthPixels: Int,
    val rowHeightPixels: Float,
    val minimumNodeWidthPixels: Float,
)

object FlameGraphProjector {
    fun project(
        nodes: List<FlameGraphNode>,
        viewport: WeightViewport,
        widthPixels: Int,
        rowHeightPixels: Float,
        minimumNodeWidthPixels: Float,
    ): List<FlameRectangle> =
        projectPage(
            nodes = nodes,
            parameters =
                FlameProjectionParameters(
                    viewport = viewport,
                    widthPixels = widthPixels,
                    rowHeightPixels = rowHeightPixels,
                    minimumNodeWidthPixels = minimumNodeWidthPixels,
                ),
            startNodeIndex = 0,
            maximumRectangles = Int.MAX_VALUE,
        ).rectangles

    fun projectPage(
        nodes: List<FlameGraphNode>,
        parameters: FlameProjectionParameters,
        startNodeIndex: Int,
        maximumRectangles: Int,
    ): FlameProjectionPage {
        require(parameters.widthPixels > 0) { "widthPixels must be positive" }
        require(parameters.rowHeightPixels > 0) { "rowHeightPixels must be positive" }
        require(parameters.minimumNodeWidthPixels >= 0) { "minimumNodeWidthPixels must not be negative" }
        require(startNodeIndex in 0..nodes.size) { "startNodeIndex must identify a node or the end" }
        require(maximumRectangles > 0) { "maximumRectangles must be positive" }
        val viewportWeight = parameters.viewport.endWeightExclusive - parameters.viewport.startWeight
        val rectangles = ArrayList<FlameRectangle>(maximumRectangles.coerceAtMost(nodes.size - startNodeIndex))
        var nodeIndex = startNodeIndex
        while (nodeIndex < nodes.size && rectangles.size < maximumRectangles) {
            projectNode(
                node = nodes[nodeIndex],
                parameters = parameters,
                viewportWeight = viewportWeight,
            )?.let(rectangles::add)
            nodeIndex += 1
        }
        return FlameProjectionPage(
            rectangles = rectangles,
            nextNodeIndex = nodeIndex.takeIf { it < nodes.size },
        )
    }

    private fun projectNode(
        node: FlameGraphNode,
        parameters: FlameProjectionParameters,
        viewportWeight: Long,
    ): FlameRectangle? {
        val viewport = parameters.viewport
        val visibleStart = node.startWeight.coerceAtLeast(viewport.startWeight)
        val visibleEnd = node.endWeight.coerceAtMost(viewport.endWeightExclusive)
        if (visibleEnd <= visibleStart) return null
        val x = (visibleStart - viewport.startWeight).toFloat() / viewportWeight * parameters.widthPixels
        val width = (visibleEnd - visibleStart).toFloat() / viewportWeight * parameters.widthPixels
        return if (width >= parameters.minimumNodeWidthPixels) {
            FlameRectangle(
                nodeId = node.id,
                parentId = node.parentId,
                label = node.label,
                filePath = node.filePath,
                path = node.path,
                highlighted = node.highlighted,
                x = x,
                y = node.depth * parameters.rowHeightPixels,
                width = width,
                height = parameters.rowHeightPixels,
            )
        } else {
            null
        }
    }

    fun hitTest(
        rectangles: List<FlameRectangle>,
        x: Float,
        y: Float,
    ): FlameRectangle? =
        rectangles.lastOrNull { rectangle ->
            x >= rectangle.x &&
                x < rectangle.x + rectangle.width &&
                y >= rectangle.y &&
                y < rectangle.y + rectangle.height
        }

    fun focus(node: FlameGraphNode): WeightViewport = WeightViewport(node.startWeight, node.endWeight)
}
