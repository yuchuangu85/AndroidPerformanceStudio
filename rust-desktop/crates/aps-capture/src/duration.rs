use std::time::Duration;

pub const MAX_TIMED_CAPTURE: Duration = Duration::from_secs(300);
pub const MANUAL_CAPTURE_TIMEOUT: Duration = Duration::from_secs(24 * 60 * 60);
pub const MANUAL_LEASE_SECONDS: u32 = 30;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CaptureDuration {
    Timed(Duration),
    Manual,
}

impl CaptureDuration {
    pub fn parse(text: &str) -> Result<Self, String> {
        let text = text.trim();
        if text.is_empty() {
            return Ok(Self::Manual);
        }
        let seconds = text
            .parse::<f64>()
            .ok()
            .filter(|value| {
                value.is_finite() && *value > 0.0 && *value <= MAX_TIMED_CAPTURE.as_secs_f64()
            })
            .ok_or(
                "CPU duration must be blank for manual stop, or positive finite seconds up to 300",
            )?;
        let duration = Duration::try_from_secs_f64(seconds)
            .map_err(|_| "CPU duration is not representable")?;
        let value = Self::Timed(duration);
        value.validate()?;
        Ok(value)
    }

    pub fn validate(self) -> Result<(), String> {
        match self {
            Self::Timed(value) if value.is_zero() || value > MAX_TIMED_CAPTURE => {
                Err("CPU timed duration must be positive and at most 300 seconds".into())
            }
            _ => Ok(()),
        }
    }

    pub fn seconds_argument(self) -> Option<String> {
        match self {
            Self::Manual => None,
            Self::Timed(value) if value.subsec_nanos() == 0 => Some(value.as_secs().to_string()),
            Self::Timed(value) => Some(
                format!("{}.{:09}", value.as_secs(), value.subsec_nanos())
                    .trim_end_matches('0')
                    .to_owned(),
            ),
        }
    }

    /// Existing preferences store whole seconds only; manual/fractional drafts
    /// stay live instead of being silently rounded into those defaults.
    pub fn whole_seconds(self) -> Option<u32> {
        self.validate().ok()?;
        match self {
            Self::Timed(value) if value.subsec_nanos() == 0 => u32::try_from(value.as_secs()).ok(),
            _ => None,
        }
    }

    pub(crate) fn host_timeout(self) -> Duration {
        match self {
            Self::Timed(value) => value + Duration::from_secs(30),
            Self::Manual => MANUAL_CAPTURE_TIMEOUT,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fmt::Write;

    #[test]
    fn manual_and_fractional_contract_matches_executed_compose_oracle() {
        let mut output = String::new();
        for (index, text) in ["", "10.0", "0.25", "10.5", "1e-3", "1.000000001", "1e-9"]
            .into_iter()
            .enumerate()
        {
            let value = CaptureDuration::parse(text)
                .unwrap()
                .seconds_argument()
                .unwrap_or_else(|| "manual".to_owned());
            writeln!(output, "D\t{index}\t{value}").unwrap();
        }
        assert_eq!(
            output,
            include_str!("../tests/fixtures/compose-duration.tsv")
        );
    }

    #[test]
    fn manual_and_fractional_duration_arguments_are_canonical_and_safe() {
        for blank in ["", " ", "\t\n"] {
            assert_eq!(
                CaptureDuration::parse(blank).unwrap(),
                CaptureDuration::Manual
            );
            assert_eq!(
                CaptureDuration::parse(blank).unwrap().seconds_argument(),
                None
            );
        }
        for (text, expected) in [
            ("10.0", "10"),
            ("0.25", "0.25"),
            ("1e-3", "0.001"),
            ("1.000000001", "1.000000001"),
            ("300", "300"),
        ] {
            assert_eq!(
                CaptureDuration::parse(text)
                    .unwrap()
                    .seconds_argument()
                    .as_deref(),
                Some(expected)
            );
        }
        assert_eq!(
            CaptureDuration::parse("10.0").unwrap().whole_seconds(),
            Some(10)
        );
        assert_eq!(
            CaptureDuration::parse("0.25").unwrap().whole_seconds(),
            None
        );
        assert_eq!(CaptureDuration::Manual.whole_seconds(), None);
    }

    #[test]
    fn invalid_or_unrepresentable_duration_never_becomes_a_command_token() {
        for text in ["0", "-1", "NaN", "inf", "Infinity", "301", "1;id", "1e-20"] {
            assert!(CaptureDuration::parse(text).is_err(), "{text}");
        }
        assert!(CaptureDuration::Timed(Duration::ZERO).validate().is_err());
        assert_eq!(CaptureDuration::Timed(Duration::ZERO).whole_seconds(), None);
        assert!(
            CaptureDuration::Timed(Duration::from_secs(301))
                .validate()
                .is_err()
        );
        assert_eq!(
            CaptureDuration::Manual.host_timeout(),
            Duration::from_secs(86_400)
        );
    }
}
