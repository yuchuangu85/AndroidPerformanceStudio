package com.androidperformancestudio.arttrace

import com.androidperformancestudio.profileanalysis.CallStackFrame
import com.androidperformancestudio.profileanalysis.CallStackTable
import com.androidperformancestudio.profileanalysis.FlameFunctionId
import com.androidperformancestudio.profileanalysis.FrameImplementation
import com.androidperformancestudio.profileanalysis.WeightedCallStack

/**
 * Converts a parsed [ArtTraceAnalysis] into the canonical [CallStackTable] consumed by the flame
 * graph / call-tree / top-functions pipeline in `profile-analysis`.
 *
 * Each method-trace event becomes part of a per-thread call stack, replayed in time order. For every
 * interval `[T_prev, T_next)` during which the stack is constant, one [WeightedCallStack] is emitted
 * with `weight` equal to the interval duration in nanoseconds and `frameIdsRootToLeaf` set to that
 * stack. This interval resampling is what makes the projection correct: the projector adds the
 * weight to every frame on the path (giving inclusive/total time) and to the leaf (giving exclusive
 * self time) — exactly the semantics Android Studio reports for method tracing.
 */
object ArtTraceCallStackProjector {
    fun toCallStackTable(analysis: ArtTraceAnalysis): CallStackTable {
        val frames = LinkedHashMap<Long, CallStackFrame>()
        analysis.methods.values.forEach { method -> frames[method.methodId] = toFrame(method) }
        // Frames for events whose methods never appeared in the method table (defensive).
        analysis.events.forEach { event ->
            if (!frames.containsKey(event.methodId)) {
                frames[event.methodId] = toFrame(ArtMethod(event.methodId, "", "0x${event.methodId.toString(16)}", "", ""))
            }
        }

        val stacks = ArrayList<WeightedCallStack>()
        var sampleId = 0L
        analysis.threads.forEach { (tid, thread) ->
            val threadEvents = analysis.events.filter { event -> event.threadId == tid }
            if (threadEvents.isEmpty()) return@forEach
            val threadKey =
                if (thread.name.isBlank()) "tid $tid" else "${thread.name} (tid $tid)"
            val stack = ArrayDeque<Long>()
            var cursor = threadEvents.first().timeNanos
            threadEvents.forEach { event ->
                emitInterval(stack, cursor, event.timeNanos, threadKey, stacks, sampleId++)
                applyEvent(stack, event)
                cursor = event.timeNanos
            }
            emitInterval(stack, cursor, analysis.endTimeNanos, threadKey, stacks, sampleId++)
        }
        return CallStackTable(framesById = frames, stacks = stacks)
    }

    private fun emitInterval(
        stack: ArrayDeque<Long>,
        start: Long,
        end: Long,
        threadKey: String,
        stacks: MutableList<WeightedCallStack>,
        sampleId: Long,
    ) {
        if (end <= start || stack.isEmpty()) return
        stacks +=
            WeightedCallStack(
                sampleId = sampleId,
                timestampNanos = start,
                weight = end - start,
                threadKey = threadKey,
                category = null,
                subcategory = null,
                frameIdsRootToLeaf = stack.toList(),
            )
    }

    private fun applyEvent(stack: ArrayDeque<Long>, event: ArtTraceEvent) {
        when (event.action) {
            ArtTraceAction.ENTER -> stack.addLast(event.methodId)
            ArtTraceAction.EXIT, ArtTraceAction.UNROLL -> if (stack.isNotEmpty()) stack.removeLast()
        }
    }

    private fun toFrame(method: ArtMethod): CallStackFrame {
        val dottedClass = method.className.replace('/', '.').removeSuffix(";")
        val qualified = if (method.methodName.isBlank()) dottedClass else "$dottedClass.${method.methodName}"
        return CallStackFrame(
            frameId = method.methodId,
            functionId = FlameFunctionId(method.methodId),
            symbolName = qualified.ifBlank { "0x${method.methodId.toString(16)}" },
            resource = method.className,
            virtualAddress = method.methodId,
            implementation = FrameImplementation.MANAGED,
        )
    }
}
