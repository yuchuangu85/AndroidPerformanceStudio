package dev.agentperf.desktop

import dev.agentperf.application.TimelineChangeType
import dev.agentperf.application.TimelineDiff
import dev.agentperf.application.TimelineFrame
import dev.agentperf.application.TimelineNodeChange
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class TimelineHistoryJson(
    private val json: Json = defaultArchiveJson(),
) {
    fun encode(frames: List<TimelineFrame>): String = json.encodeToString(
        TimelineHistoryDto(
            frames = frames.map { frame ->
                TimelineFrameDto(
                    index = frame.index,
                    capturedAtEpochMillis = frame.capturedAtEpochMillis,
                    diffFromPrevious = frame.diffFromPrevious?.toDto(),
                )
            },
        ),
    )

    fun decode(encoded: String): List<TimelineFrame> =
        json.decodeFromString<TimelineHistoryDto>(encoded).frames.map { frame ->
            TimelineFrame(
                index = frame.index,
                capturedAtEpochMillis = frame.capturedAtEpochMillis,
                diffFromPrevious = frame.diffFromPrevious?.toModel(),
            )
        }

    private fun TimelineDiff.toDto(): TimelineDiffDto = TimelineDiffDto(
        previousCapturedAtEpochMillis = previousCapturedAtEpochMillis,
        currentCapturedAtEpochMillis = currentCapturedAtEpochMillis,
        addedNodes = addedNodes,
        removedNodes = removedNodes,
        boundsChangedNodes = boundsChangedNodes,
        changes = changes.map { it.toDto() },
    )

    private fun TimelineDiffDto.toModel(): TimelineDiff = TimelineDiff(
        previousCapturedAtEpochMillis = previousCapturedAtEpochMillis,
        currentCapturedAtEpochMillis = currentCapturedAtEpochMillis,
        addedNodes = addedNodes,
        removedNodes = removedNodes,
        boundsChangedNodes = boundsChangedNodes,
        changes = changes.map { it.toModel() },
    )

    private fun TimelineNodeChange.toDto(): TimelineNodeChangeDto = TimelineNodeChangeDto(
        type = type.name,
        windowId = windowId,
        nodeId = nodeId,
        nodeKey = nodeKey,
        className = className,
        changedProperties = changedProperties,
    )

    private fun TimelineNodeChangeDto.toModel(): TimelineNodeChange = TimelineNodeChange(
        type = TimelineChangeType.valueOf(type),
        windowId = windowId,
        nodeId = nodeId,
        nodeKey = nodeKey,
        className = className,
        changedProperties = changedProperties,
    )
}

@Serializable
private data class TimelineHistoryDto(
    val frames: List<TimelineFrameDto>,
)

@Serializable
private data class TimelineFrameDto(
    val index: Int,
    val capturedAtEpochMillis: Long,
    val diffFromPrevious: TimelineDiffDto? = null,
)

@Serializable
private data class TimelineDiffDto(
    val previousCapturedAtEpochMillis: Long,
    val currentCapturedAtEpochMillis: Long,
    val addedNodes: Int,
    val removedNodes: Int,
    val boundsChangedNodes: Int,
    val changes: List<TimelineNodeChangeDto> = emptyList(),
)

@Serializable
private data class TimelineNodeChangeDto(
    val type: String,
    val windowId: String,
    val nodeId: String,
    val nodeKey: String,
    val className: String,
    val changedProperties: List<String> = emptyList(),
)
