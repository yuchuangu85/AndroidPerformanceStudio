package com.androidperformancestudio.memory.analysis

import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.model.LeakCanaryLeak
import com.androidperformancestudio.memory.model.LeakCanaryReport
import com.androidperformancestudio.memory.model.LeakCanaryStatus
import com.androidperformancestudio.memory.model.LeakCanaryTraceElement
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.HeapAnalyzer
import shark.HeapGraph
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.MetadataExtractor
import shark.ObjectInspectors
import shark.OnAnalysisProgressListener
import shark.ReferenceMatcher
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Host-side adapter around LeakCanary's Shark engine.
 *
 * Candidates come from individual objects' lifecycle state and ObjectWatcher evidence in the raw
 * heap, never from histogram or class-level retention heuristics.
 * This is a bounded inspector / non-strong-reference matcher set, not Android appDefaults.
 */
public class SharkLeakAnalyzer(
    private val additionalReferenceMatchers: List<ReferenceMatcher> = emptyList(),
    private val progress: (Int) -> Unit = {},
) {
    public fun analyze(
        heapDump: HeapDump,
        hprofFile: Path,
    ): LeakCanaryReport {
        val startedAt = System.nanoTime()
        var candidateObjectCount = 0
        var analyzedClassCount = heapDump.classes.size
        var analyzedObjectCount = heapDump.instances.size
        var lastProgress = 0

        fun reportProgress(value: Int) {
            lastProgress = maxOf(lastProgress, value.coerceIn(0, MAX_PROGRESS))
            progress(lastProgress)
        }
        val listener =
            OnAnalysisProgressListener { step ->
                reportProgress(ANALYSIS_PROGRESS_START + step.ordinal * ANALYSIS_PROGRESS_STEP)
            }
        val report =
            try {
                reportProgress(0)
                hprofFile.toFile().openHeapGraph().use { rawGraph ->
                    val graph = ProgressReportingHeapGraph(rawGraph) { reportProgress(lastProgress) }
                    analyzedClassCount = graph.classCount
                    analyzedObjectCount = graph.objectCount
                    reportProgress(DISCOVERY_PROGRESS)
                    val candidates = LifecycleLeakingObjectFinder.findLeakingObjectIds(graph)
                    candidateObjectCount = candidates.size
                    reportProgress(ANALYSIS_PROGRESS_START)
                    analyzeCandidates(graph, hprofFile, candidates, listener)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: IOException) {
                failedReport(exception)
            } catch (exception: IllegalArgumentException) {
                failedReport(exception)
            } catch (exception: IllegalStateException) {
                failedReport(exception)
            } catch (exception: SecurityException) {
                failedReport(exception)
            }
        reportProgress(MAX_PROGRESS)
        return report.copy(
            analyzedClassCount = analyzedClassCount,
            analyzedObjectCount = analyzedObjectCount,
            candidateObjectCount = candidateObjectCount,
            analysisDurationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
        )
    }

    private fun analyzeCandidates(
        graph: HeapGraph,
        hprofFile: Path,
        candidates: Set<Long>,
        listener: OnAnalysisProgressListener,
    ): LeakCanaryReport {
        if (candidates.isEmpty()) {
            return LeakCanaryReport(
                status = LeakCanaryStatus.NO_CANDIDATES,
                message = "No object-specific lifecycle or ObjectWatcher candidates found.",
            )
        }
        val analysis =
            HeapAnalyzer(listener).analyze(
                heapDumpFile = hprofFile.toFile(),
                graph = graph,
                leakingObjectFinder = { candidates },
                referenceMatchers = additionalReferenceMatchers + SharkReferenceMatchers.nonStrongReferences,
                computeRetainedHeapSize = true,
                objectInspectors =
                    ObjectInspectors.jdkDefaults + LifecycleObjectInspector + SharkObjectIdentity.inspector,
                metadataExtractor = MetadataExtractor.NO_OP,
            )
        return when (analysis) {
            is HeapAnalysisSuccess -> analysis.toModel()
            is HeapAnalysisFailure -> {
                // Shark catches Throwable internally, including cancellation from our callbacks.
                val cause = analysis.exception.cause
                if (cause is CancellationException) throw cause
                failedReport(analysis.exception)
            }
        }
    }

    private fun failedReport(exception: Exception): LeakCanaryReport =
        LeakCanaryReport(
            status = LeakCanaryStatus.FAILED,
            message = exception.message ?: "Shark analysis failed.",
        )

    private fun HeapAnalysisSuccess.toModel(): LeakCanaryReport =
        LeakCanaryReport(
            status = LeakCanaryStatus.COMPLETED,
            applicationLeaks = applicationLeaks.flatMap { leak -> leak.leakTraces.map { leak.toModel(it) } },
            libraryLeaks = libraryLeaks.flatMap { leak -> leak.leakTraces.map { leak.toModel(it) } },
            message =
                if (applicationLeaks.isEmpty() && libraryLeaks.isEmpty()) {
                    "No Shark leak traces found within the supported lifecycle / ObjectWatcher scope."
                } else {
                    "Bounded lifecycle / ObjectWatcher analysis; " +
                        "Android framework/OEM leak matching is not included by default."
                },
        )

    private fun shark.ApplicationLeak.toModel(trace: shark.LeakTrace): LeakCanaryLeak =
        LeakCanaryLeak(
            signature = signature,
            shortDescription = shortDescription,
            leakingClassName = trace.leakingObject.className,
            retainedHeapByteSize = trace.retainedHeapByteSize,
            retainedObjectCount = trace.retainedObjectCount,
            gcRootType = trace.gcRootType.name,
            trace = trace.toModel(),
            leakingObjectId = SharkObjectIdentity.objectId(trace.leakingObject),
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
            leakingObjectId = SharkObjectIdentity.objectId(trace.leakingObject),
        )

    private fun shark.LeakTrace.toModel(): List<LeakCanaryTraceElement> =
        buildList {
            referencePath.forEachIndexed { index, reference ->
                add(
                    LeakCanaryTraceElement(
                        className = reference.originObject.className,
                        referenceName = reference.referenceDisplayName,
                        referenceType = reference.referenceType.name,
                        leakingStatus = reference.originObject.leakingStatus.name,
                        leakingStatusReason = reference.originObject.leakingStatusReason,
                        objectId = SharkObjectIdentity.objectId(reference.originObject),
                        labels = SharkObjectIdentity.labels(reference.originObject),
                        isSuspect = referencePathElementIsSuspect(index),
                    ),
                )
            }
            add(
                LeakCanaryTraceElement(
                    className = leakingObject.className,
                    leakingStatus = leakingObject.leakingStatus.name,
                    leakingStatusReason = leakingObject.leakingStatusReason,
                    objectId = SharkObjectIdentity.objectId(leakingObject),
                    labels = SharkObjectIdentity.labels(leakingObject),
                ),
            )
        }

    private companion object {
        const val DISCOVERY_PROGRESS = 5
        const val ANALYSIS_PROGRESS_START = 30
        const val ANALYSIS_PROGRESS_STEP = 6
        const val MAX_PROGRESS = 100
    }
}
