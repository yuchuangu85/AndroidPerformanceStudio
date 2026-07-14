@file:Suppress("MatchingDeclarationName", "ktlint:standard:filename")

package com.androidperformancestudio.model

sealed interface CanonicalProfileRecord {
    data class Source(
        val value: ProfileSourceFact,
    ) : CanonicalProfileRecord

    data class Process(
        val value: ProfileProcessFact,
    ) : CanonicalProfileRecord

    data class Thread(
        val value: ProfileThreadFact,
    ) : CanonicalProfileRecord

    data class Sample(
        val value: ProfileSampleFact,
    ) : CanonicalProfileRecord

    data class Marker(
        val value: ProfileMarkerFact,
    ) : CanonicalProfileRecord

    data class Counter(
        val value: ProfileCounterFact,
    ) : CanonicalProfileRecord

    data class Slice(
        val value: ProfileSliceFact,
    ) : CanonicalProfileRecord

    data class Screenshot(
        val value: ProfileScreenshotFact,
    ) : CanonicalProfileRecord

    data class Legacy(
        val value: NormalizedProfileRecord,
    ) : CanonicalProfileRecord
}
