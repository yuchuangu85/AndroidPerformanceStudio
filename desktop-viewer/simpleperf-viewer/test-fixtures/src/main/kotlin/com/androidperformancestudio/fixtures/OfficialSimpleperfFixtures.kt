package com.androidperformancestudio.fixtures

data class BinaryFixture(
    val resourcePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val upstreamRevision: String,
    val sourceUrl: String,
    val license: String,
)

object OfficialSimpleperfFixtures {
    val AOSP_PERF_DATA =
        BinaryFixture(
            resourcePath = "/simpleperf/aosp-perf.data",
            sizeBytes = 136_396L,
            sha256 = "cb3066f4050d84d3e204a37ca4c479113b7623b663c17a3ee8cae5a85b8238bf",
            upstreamRevision = "0913958dce781fb91c415e666623e46d3c17b3e1",
            sourceUrl =
                "https://android.googlesource.com/platform/system/extras/+/" +
                    "0913958dce781fb91c415e666623e46d3c17b3e1/simpleperf/testdata/perf.data",
            license = "Apache-2.0",
        )

    val GENERATED_GOLDEN_SESSION =
        BinaryFixture(
            resourcePath = "/sessions/golden.apsession.zip",
            sizeBytes = 4_047L,
            sha256 = "490c0d31b676316235326dc13ac2f392498e00467437c49119550541d9def98e",
            upstreamRevision = "V0.1 generated fixture",
            sourceUrl = "./gradlew :simpleperf-test-fixtures:generateSampleSession",
            license = "Project fixture",
        )
}
