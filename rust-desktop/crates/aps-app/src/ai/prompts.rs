//! Context-aware prompt suggestions for the AI conversation composer.

use aps_ai::AnalysisRequest;
use aps_ui_foundation::{Language, TextKey};

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct PromptSuggestion {
    pub title: String,
    pub prompt: String,
}

fn suggestion(language: Language, title: TextKey, prompt: TextKey) -> PromptSuggestion {
    PromptSuggestion {
        title: language.text(title).to_owned(),
        prompt: language.text(prompt).to_owned(),
    }
}

/// Returns prompts for the currently bound report/evidence type. The returned
/// text is only a draft; it does not alter evidence or approve a run.
pub(crate) fn for_context(
    context: Option<&AnalysisRequest>,
    language: Language,
) -> Vec<PromptSuggestion> {
    let Some(context) = context else {
        return vec![
            suggestion(
                language,
                TextKey::AiChatPromptGeneralSummaryTitle,
                TextKey::AiChatPromptGeneralSummary,
            ),
            suggestion(
                language,
                TextKey::AiChatPromptGeneralNextStepsTitle,
                TextKey::AiChatPromptGeneralNextSteps,
            ),
        ];
    };

    if context.profiler == "memory" {
        if let Some(mode) = context.memory_phase3_mode() {
            let (title, prompt) = match mode {
                "memory-hprof-growth" => (
                    (
                        TextKey::AiChatPromptGrowthTitle,
                        TextKey::AiChatPromptGrowth,
                    ),
                    (
                        TextKey::AiChatPromptGrowthValidationTitle,
                        TextKey::AiChatPromptGrowthValidation,
                    ),
                ),
                "memory-lifecycle-rule" => (
                    (
                        TextKey::AiChatPromptLifecycleTitle,
                        TextKey::AiChatPromptLifecycle,
                    ),
                    (
                        TextKey::AiChatPromptLifecycleFixTitle,
                        TextKey::AiChatPromptLifecycleFix,
                    ),
                ),
                "memory-native-heap" => (
                    (
                        TextKey::AiChatPromptNativeTitle,
                        TextKey::AiChatPromptNative,
                    ),
                    (
                        TextKey::AiChatPromptNativeNextStepsTitle,
                        TextKey::AiChatPromptNativeNextSteps,
                    ),
                ),
                "memory-leakcanary-shark" => (
                    (TextKey::AiChatPromptSharkTitle, TextKey::AiChatPromptShark),
                    (
                        TextKey::AiChatPromptSharkValidationTitle,
                        TextKey::AiChatPromptSharkValidation,
                    ),
                ),
                _ => unreachable!("unknown memory phase 3 mode: {mode}"),
            };
            return vec![
                suggestion(language, title.0, prompt.0),
                suggestion(language, title.1, prompt.1),
                suggestion(
                    language,
                    TextKey::AiChatPromptEvidenceLimitsTitle,
                    TextKey::AiChatPromptEvidenceLimits,
                ),
            ];
        }
        return vec![
            suggestion(
                language,
                TextKey::AiChatPromptHprofRootCauseTitle,
                TextKey::AiChatPromptHprofRootCause,
            ),
            suggestion(
                language,
                TextKey::AiChatPromptHprofConfidenceTitle,
                TextKey::AiChatPromptHprofConfidence,
            ),
            suggestion(
                language,
                TextKey::AiChatPromptHprofNextStepsTitle,
                TextKey::AiChatPromptHprofNextSteps,
            ),
            suggestion(
                language,
                TextKey::AiChatPromptEvidenceLimitsTitle,
                TextKey::AiChatPromptEvidenceLimits,
            ),
        ];
    }

    if context.profiler == "source" {
        return vec![
            suggestion(
                language,
                TextKey::AiChatPromptSourceExplainTitle,
                TextKey::AiChatPromptSourceExplain,
            ),
            suggestion(
                language,
                TextKey::AiChatPromptSourceRiskTitle,
                TextKey::AiChatPromptSourceRisk,
            ),
            suggestion(
                language,
                TextKey::AiChatPromptSourceNextStepsTitle,
                TextKey::AiChatPromptSourceNextSteps,
            ),
        ];
    }

    vec![
        suggestion(
            language,
            TextKey::AiChatPromptGeneralSummaryTitle,
            TextKey::AiChatPromptGeneralSummary,
        ),
        suggestion(
            language,
            TextKey::AiChatPromptGeneralNextStepsTitle,
            TextKey::AiChatPromptGeneralNextSteps,
        ),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    fn request(profiler: &str, evidence_kind: Option<&str>) -> AnalysisRequest {
        AnalysisRequest {
            session_id: "session".into(),
            run_id: "run".into(),
            generation: 1,
            profiler: profiler.into(),
            scope: "scope".into(),
            evidence: evidence_kind
                .map(|kind| aps_ai::Evidence {
                    id: "evidence".into(),
                    kind: kind.into(),
                    summary: "summary".into(),
                    payload: aps_json::Value::Object(Default::default()),
                })
                .into_iter()
                .collect(),
            candidates: Vec::new(),
            user_question: None,
        }
    }

    #[test]
    fn memory_hprof_prompts_are_context_specific() {
        let prompts = for_context(
            Some(&request("memory", Some("memory-hprof-leak-findings"))),
            Language::English,
        );
        assert!(prompts.len() >= 3);
        assert!(prompts.iter().any(|item| item.prompt.contains("HPROF")));
    }

    #[test]
    fn native_phase_three_prompts_do_not_fall_back_to_hprof_copy() {
        let prompts = for_context(
            Some(&request("memory", Some("memory-native-heap"))),
            Language::SimplifiedChinese,
        );
        assert!(prompts.iter().any(|item| item.prompt.contains("Native")));
        assert!(
            !prompts
                .iter()
                .any(|item| item.prompt.contains("Java retained"))
        );
    }

    #[test]
    fn ordinary_context_has_safe_fallback_prompts() {
        let prompts = for_context(Some(&request("cpu", None)), Language::English);
        assert_eq!(prompts.len(), 2);
        assert!(prompts.iter().all(|item| !item.prompt.trim().is_empty()));
    }
}
