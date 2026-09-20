package com.androidperformancestudio.memory.analysis

import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.LeakCanaryLeak
import com.androidperformancestudio.memory.model.LeakCanaryReport
import com.androidperformancestudio.memory.model.LeakCanaryStatus
import com.androidperformancestudio.memory.model.LeakCanaryTraceElement
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.HeapAnalyzer
import shark.LeakingObjectFinder
import shark.MetadataExtractor
import shark.ObjectInspectors
import shark.OnAnalysisProgressListener
import java.nio.file.Path

/**
 * Host-side adapter around LeakCanary's Shark engine.
 *
 * The desktop viewer already captures and converts HPROF files. This adapter deliberately uses the
 * existing heuristic candidates as the input set for Shark so a single arbitrary heap dump does
 * not turn into a full-heap report containing thousands of unrelated paths.
 */
public class SharkLeakAnalyzer(
    private val progress: (Int) -> Unit = {},
) {
    public fun analyze(
        heapDump: HeapDump,
        hprofFile: Path,
    ): LeakCanaryReport {
        val candidateClassNames =
            (heapDump.leakSuspects.map { it.className } + heapDump.activityLeaks.map { it.className })
                .filter(String::isNotBlank)
                .toSet()
        if (candidateClassNames.isEmpty()) {
            return LeakCanaryReport(
                status = LeakCanaryStatus.NO_CANDIDATES,
                message = "No lifecycle or retention candidates were produced for Shark analysis.",
            )
        }

        val objectFinder =
            LeakingObjectFinder { graph ->
                graph.instances
                    .filter { candidateClassNames.contains(it.instanceClassName) }
                    .map { it.objectId }
                    .toSet()
            }
        val listener =
            OnAnalysisProgressListener { step ->
                progress(step.ordinal * PROGRESS_STEP_PERCENT)
            }

        @Suppress("DEPRECATION")
        fun runAnalysis(): shark.HeapAnalysis =
            HeapAnalyzer(listener).analyze(
                hprofFile.toFile(),
                objectFinder,
                emptyList(),
                true,
                ObjectInspectors.values().toList(),
                MetadataExtractor.NO_OP,
                null,
            )
        val analysis = runAnalysis()
        return when (analysis) {
            is HeapAnalysisSuccess ->
                LeakCanaryReport(
                    status = LeakCanaryStatus.COMPLETED,
                    analyzedClassCount = heapDump.classes.size,
                    analyzedObjectCount = heapDump.instances.size,
                    applicationLeaks =
                        analysis.applicationLeaks.flatMap { leak ->
                            leak.leakTraces.map { trace -> leak.toModel(trace) }
                        },
                    libraryLeaks =
                        analysis.libraryLeaks.flatMap { leak ->
                            leak.leakTraces.map { trace -> leak.toModel(trace) }
                        },
                    message =
                        if (analysis.applicationLeaks.isEmpty() && analysis.libraryLeaks.isEmpty()) {
                            "No Shark leak traces found."
                        } else {
                            null
                        },
                )
            is HeapAnalysisFailure ->
                LeakCanaryReport(
                    status = LeakCanaryStatus.FAILED,
                    analyzedClassCount = heapDump.classes.size,
                    analyzedObjectCount = heapDump.instances.size,
                    message = analysis.exception.message ?: "Shark analysis failed.",
                )
        }
    }

    private fun shark.ApplicationLeak.toModel(trace: shark.LeakTrace): LeakCanaryLeak =
        LeakCanaryLeak(
            signature = signature,
            shortDescription = shortDescription,
            leakingClassName = trace.leakingObject.className,
            retainedHeapByteSize = trace.retainedHeapByteSize,
            retainedObjectCount = trace.retainedObjectCount,
            gcRootType = trace.gcRootType.name,
            trace = trace.toModel(),
        )

    private fun shark.LibraryLeak.toModel(trace: shark.LeakTrace): LeakCanaryLeak =
        LeakCanaryLeak(
            signature = signature,
            shortDescription = shortDescription,
            leakingClassName = trace.leakingObject.className,
            retainedHeapByteSize = trace.retainedHeapByteSize,
            retainedObjectCount = trace.retainedObjectCount,
            gcRootType = trace.gcRootType.name,
            trace = trace.toModel(),
        )

    private fun shark.LeakTrace.toModel(): List<LeakCanaryTraceElement> =
        buildList {
            add(
                LeakCanaryTraceElement(
                    className = leakingObject.className,
                    leakingStatus = leakingObject.leakingStatus.name,
                    leakingStatusReason = leakingObject.leakingStatusReason,
                ),
            )
            referencePath.asReversed().forEach { reference ->
                add(
                    LeakCanaryTraceElement(
                        className = reference.originObject.className,
                        referenceName = reference.referenceDisplayName,
                        referenceType = reference.referenceType.name,
                        leakingStatus = reference.originObject.leakingStatus.name,
                        leakingStatusReason = reference.originObject.leakingStatusReason,
                    ),
                )
            }
        }

    private companion object {
        const val PROGRESS_STEP_PERCENT = 20
    }
}
