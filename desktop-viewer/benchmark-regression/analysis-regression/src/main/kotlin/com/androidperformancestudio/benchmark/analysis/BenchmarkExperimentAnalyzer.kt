package com.androidperformancestudio.benchmark.analysis

import com.androidperformancestudio.benchmark.model.BaselineProfileComparison
import com.androidperformancestudio.benchmark.model.BaselineProfileStatus
import com.androidperformancestudio.benchmark.model.BenchmarkRun
import com.androidperformancestudio.benchmark.model.RegressionPolicy
import com.androidperformancestudio.benchmark.model.TraceArtifactPair

/**
 * Keeps the Baseline Profile experiment boundary explicit. A profile comparison is still a
 * benchmark regression report, but it also carries profile state and the trace pairs used to
 * inspect a change. This prevents a faster result from being reported without its compilation
 * and trace evidence.
 */
public class BenchmarkExperimentAnalyzer(
    private val regressionAnalyzer: RegressionAnalyzer = RegressionAnalyzer(),
) {
    public fun compareBaselineProfile(
        beforeProfile: BenchmarkRun,
        afterProfile: BenchmarkRun,
        policy: RegressionPolicy = RegressionPolicy(),
    ): BaselineProfileComparison {
        val report = regressionAnalyzer.compare(beforeProfile, afterProfile, policy)
        return BaselineProfileComparison(
            baselineRunId = beforeProfile.id,
            profiledRunId = afterProfile.id,
            report = report,
            baselineStatus = profileStatus(beforeProfile),
            profiledStatus = profileStatus(afterProfile),
            tracePairs = tracePairs(beforeProfile, afterProfile),
        )
    }

    private fun tracePairs(
        before: BenchmarkRun,
        after: BenchmarkRun,
    ): List<TraceArtifactPair> {
        val afterByIdentity = after.cases.associateBy { it.identity }
        return before.cases.mapNotNull { baselineCase ->
            val profiledCase = afterByIdentity[baselineCase.identity] ?: return@mapNotNull null
            if (baselineCase.traceArtifacts.isEmpty() && profiledCase.traceArtifacts.isEmpty()) return@mapNotNull null
            TraceArtifactPair(baselineCase.identity, baselineCase.traceArtifacts, profiledCase.traceArtifacts)
        }
    }

    private fun profileStatus(run: BenchmarkRun): BaselineProfileStatus {
        val statuses = run.cases.map { it.baselineProfile?.status ?: BaselineProfileStatus.NOT_REQUESTED }.toSet()
        return when {
            statuses == setOf(BaselineProfileStatus.VERIFIED) -> BaselineProfileStatus.VERIFIED
            statuses.all { it == BaselineProfileStatus.VERIFIED || it == BaselineProfileStatus.INSTALLED } ->
                BaselineProfileStatus.INSTALLED
            BaselineProfileStatus.MISSING in statuses -> BaselineProfileStatus.MISSING
            statuses == setOf(BaselineProfileStatus.NOT_REQUESTED) -> BaselineProfileStatus.NOT_REQUESTED
            else -> BaselineProfileStatus.UNKNOWN
        }
    }
}
