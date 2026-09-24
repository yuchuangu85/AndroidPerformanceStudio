package com.androidperformancestudio.startup.presentation

import com.androidperformancestudio.startup.model.StartupCompilationEvidence
import com.androidperformancestudio.startup.model.StartupMetricEvidence
import com.androidperformancestudio.startup.presentation.generated.resources.Res
import com.androidperformancestudio.startup.presentation.generated.resources.baseline_profile_artifact
import com.androidperformancestudio.startup.presentation.generated.resources.compilation_failure_reason
import com.androidperformancestudio.startup.presentation.generated.resources.compilation_verified
import com.androidperformancestudio.startup.presentation.generated.resources.compiler_filter_after
import com.androidperformancestudio.startup.presentation.generated.resources.compiler_filter_before
import com.androidperformancestudio.startup.presentation.generated.resources.no
import com.androidperformancestudio.startup.presentation.generated.resources.none
import com.androidperformancestudio.startup.presentation.generated.resources.profile_source_declared
import com.androidperformancestudio.startup.presentation.generated.resources.profile_source_evidence
import com.androidperformancestudio.startup.presentation.generated.resources.profile_state_after
import com.androidperformancestudio.startup.presentation.generated.resources.profile_state_before
import com.androidperformancestudio.startup.presentation.generated.resources.requested_compilation_mode
import com.androidperformancestudio.startup.presentation.generated.resources.unavailable
import com.androidperformancestudio.startup.presentation.generated.resources.yes
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource

internal data class StartupEvidenceRow(
    val label: String,
    val value: String,
)

internal fun StartupMetricEvidence.statusLabel(language: UiLanguage): String =
    listOfNotNull(confidence.localizedLabel(language), unavailableReason?.takeIf(String::isNotBlank)).joinToString(" · ")

internal fun StartupCompilationEvidence.detailRows(language: UiLanguage): List<StartupEvidenceRow> {
    val unavailable = localizedStringResource(Res.string.unavailable, language)

    fun String?.present(): String = this?.takeIf(String::isNotBlank) ?: unavailable

    return listOf(
        StartupEvidenceRow(localizedStringResource(Res.string.requested_compilation_mode, language), requestedMode.name),
        StartupEvidenceRow(localizedStringResource(Res.string.compiler_filter_before, language), compilerFilterBefore.present()),
        StartupEvidenceRow(localizedStringResource(Res.string.compiler_filter_after, language), compilerFilterAfter.present()),
        StartupEvidenceRow(localizedStringResource(Res.string.profile_state_before, language), profileStateBefore.present()),
        StartupEvidenceRow(localizedStringResource(Res.string.profile_state_after, language), profileStateAfter.present()),
        StartupEvidenceRow(
            localizedStringResource(Res.string.compilation_verified, language),
            localizedStringResource(if (verified) Res.string.yes else Res.string.no, language),
        ),
        StartupEvidenceRow(localizedStringResource(Res.string.profile_source_evidence, language), profileSource.name),
        StartupEvidenceRow(
            localizedStringResource(Res.string.profile_source_declared, language),
            localizedStringResource(if (profileSourceDeclared) Res.string.yes else Res.string.no, language),
        ),
        StartupEvidenceRow(localizedStringResource(Res.string.baseline_profile_artifact, language), baselineProfileArtifact.present()),
        StartupEvidenceRow(
            localizedStringResource(Res.string.compilation_failure_reason, language),
            failureReason?.takeIf(String::isNotBlank)
                ?: localizedStringResource(if (verified) Res.string.none else Res.string.unavailable, language),
        ),
    )
}
