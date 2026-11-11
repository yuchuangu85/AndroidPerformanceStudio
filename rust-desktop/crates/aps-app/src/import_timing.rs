//! Wall-clock cost of the last successfully published local import, not capture
//! duration or an artifact timestamp. Start after selection, finish after parsing
//! and the initial page/query is ready; failed/cancelled work never replaces it.
use aps_ui_foundation::{Language, TextKey};
use std::time::{Duration, Instant};

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct ImportTiming(Option<Duration>);

impl ImportTiming {
    pub(crate) fn completed(started: Instant) -> Self {
        Self::from_duration(started.elapsed())
    }

    pub(crate) fn from_duration(duration: Duration) -> Self {
        Self(Some(duration))
    }

    pub(crate) fn clear(&mut self) {
        self.0 = None;
    }

    pub(crate) fn text(self, language: Language) -> Option<String> {
        let duration = self.0?;
        let duration = if duration < Duration::from_millis(1) {
            "<1 ms".to_owned()
        } else if duration < Duration::from_secs(1) {
            format!("{} ms", duration.as_millis())
        } else {
            format!("{:.2} s", duration.as_secs_f64())
        };
        Some(language.format(TextKey::ImportTimingElapsed, &[("duration", &duration)]))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_and_cleared_timings_do_not_invent_a_duration() {
        let mut timing = ImportTiming::default();
        assert_eq!(timing.text(Language::English), None);
        timing = ImportTiming::from_duration(Duration::from_millis(1200));
        timing.clear();
        assert_eq!(timing.text(Language::SimplifiedChinese), None);
    }

    #[test]
    fn elapsed_cost_has_shared_precision_and_localized_labels() {
        for (duration, value) in [
            (Duration::ZERO, "<1 ms"),
            (Duration::from_micros(900), "<1 ms"),
            (Duration::from_millis(123), "123 ms"),
            (Duration::from_millis(1234), "1.23 s"),
            (Duration::from_secs(90), "90.00 s"),
        ] {
            let timing = ImportTiming::from_duration(duration);
            assert_eq!(
                timing.text(Language::English).unwrap(),
                format!("Import time: {value}")
            );
            assert_eq!(
                timing.text(Language::SimplifiedChinese).unwrap(),
                format!("导入耗时：{value}")
            );
        }
    }
}
