use crate::CallGraphMode;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum EventScope {
    User,
    Kernel,
    #[default]
    Both,
}

impl EventScope {
    pub const ALL: [Self; 3] = [Self::User, Self::Kernel, Self::Both];

    fn modifier(self) -> &'static str {
        match self {
            Self::User => ":u",
            Self::Kernel => ":k",
            Self::Both => "",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SamplingRate {
    Frequency(u32),
    Period(u64),
}

impl SamplingRate {
    pub fn value(self) -> u64 {
        match self {
            Self::Frequency(value) => u64::from(value),
            Self::Period(value) => value,
        }
    }

    pub fn is_period(self) -> bool {
        matches!(self, Self::Period(_))
    }

    fn argument(self) -> &'static str {
        match self {
            Self::Frequency(_) => "-f",
            Self::Period(_) => "-c",
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SamplingConfiguration {
    pub event: String,
    pub rate: SamplingRate,
    pub call_graph: CallGraphMode,
    pub scope: EventScope,
}

impl Default for SamplingConfiguration {
    fn default() -> Self {
        Self {
            event: "cpu-clock".into(),
            rate: SamplingRate::Frequency(99),
            call_graph: CallGraphMode::Dwarf,
            scope: EventScope::Both,
        }
    }
}

impl SamplingConfiguration {
    pub fn validate(&self) -> Result<(), String> {
        if self.event.is_empty()
            || self.event.len() > 256
            || !self.event.as_bytes()[0].is_ascii_alphanumeric()
            || !self
                .event
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || b"_:-./".contains(&byte))
        {
            return Err(
                "CPU event must be a bounded Simpleperf event token without shell syntax".into(),
            );
        }
        match self.rate {
            SamplingRate::Frequency(1..=4000) => Ok(()),
            SamplingRate::Period(value) if (1..=i64::MAX as u64).contains(&value) => Ok(()),
            _ => Err(
                "CPU frequency must be 1..4000 Hz, or period a positive signed 64-bit count".into(),
            ),
        }
    }

    /// Every token is validated or enum-owned before it reaches the remote shell.
    pub fn record_arguments(&self) -> Result<Vec<String>, String> {
        self.validate()?;
        let mut arguments = vec![
            "-e".into(),
            format!("{}{}", self.event, self.scope.modifier()),
            self.rate.argument().into(),
            self.rate.value().to_string(),
        ];
        arguments.extend(
            self.call_graph
                .arguments()
                .iter()
                .map(|value| (*value).to_owned()),
        );
        Ok(arguments)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SamplingTemplate {
    AppCpuBasic,
    UiThreadFocus,
    NativeHotspot,
    LowOverhead,
    SystemProcess,
}

impl SamplingTemplate {
    pub const ALL: [Self; 5] = [
        Self::AppCpuBasic,
        Self::UiThreadFocus,
        Self::NativeHotspot,
        Self::LowOverhead,
        Self::SystemProcess,
    ];
    pub const DURATION_SECONDS: u32 = 10;

    pub fn create(self, app_scoped: bool) -> SamplingConfiguration {
        let mut configuration = SamplingConfiguration {
            rate: SamplingRate::Frequency(1000),
            scope: if app_scoped {
                EventScope::User
            } else {
                EventScope::Both
            },
            ..SamplingConfiguration::default()
        };
        match self {
            Self::AppCpuBasic | Self::UiThreadFocus => {}
            Self::NativeHotspot => configuration.event = "cpu-cycles".into(),
            Self::LowOverhead => {
                configuration.rate = SamplingRate::Frequency(100);
                configuration.call_graph = CallGraphMode::FramePointer;
            }
            Self::SystemProcess => {
                configuration.rate = SamplingRate::Frequency(400);
                configuration.call_graph = CallGraphMode::FramePointer;
            }
        }
        configuration
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fmt::Write;

    #[test]
    fn sampling_contract_matches_executed_compose_oracle() {
        let mut output = String::new();
        for app_scoped in [true, false] {
            for (index, template) in SamplingTemplate::ALL.into_iter().enumerate() {
                let configuration = template.create(app_scoped);
                writeln!(
                    output,
                    "T\t{app_scoped}\t{index}\t{}\t{}",
                    SamplingTemplate::DURATION_SECONDS,
                    configuration.record_arguments().unwrap().join(" ")
                )
                .unwrap();
            }
        }
        let mut ordinal = 0;
        for event in ["cpu-clock", "cpu-cycles"] {
            for scope in EventScope::ALL {
                for rate in [SamplingRate::Frequency(99), SamplingRate::Period(4096)] {
                    for call_graph in CallGraphMode::ALL {
                        let configuration = SamplingConfiguration {
                            event: event.into(),
                            rate,
                            scope,
                            call_graph,
                        };
                        writeln!(
                            output,
                            "M\t{ordinal}\t{}",
                            configuration.record_arguments().unwrap().join(" ")
                        )
                        .unwrap();
                        ordinal += 1;
                    }
                }
            }
        }
        assert_eq!(
            output,
            include_str!("../tests/fixtures/compose-sampling.tsv")
        );
    }

    #[test]
    fn templates_match_compose_values_without_inventing_target_or_root_authority() {
        for app_scoped in [true, false] {
            for template in SamplingTemplate::ALL {
                let configuration = template.create(app_scoped);
                assert_eq!(
                    configuration.scope,
                    if app_scoped {
                        EventScope::User
                    } else {
                        EventScope::Both
                    }
                );
                let (event, frequency, call_graph) = match template {
                    SamplingTemplate::AppCpuBasic | SamplingTemplate::UiThreadFocus => {
                        ("cpu-clock", 1000, CallGraphMode::Dwarf)
                    }
                    SamplingTemplate::NativeHotspot => ("cpu-cycles", 1000, CallGraphMode::Dwarf),
                    SamplingTemplate::LowOverhead => {
                        ("cpu-clock", 100, CallGraphMode::FramePointer)
                    }
                    SamplingTemplate::SystemProcess => {
                        ("cpu-clock", 400, CallGraphMode::FramePointer)
                    }
                };
                assert_eq!(configuration.event, event);
                assert_eq!(configuration.rate, SamplingRate::Frequency(frequency));
                assert_eq!(configuration.call_graph, call_graph);
                configuration.validate().unwrap();
            }
        }
    }

    #[test]
    fn frequency_period_scope_and_callgraph_have_exact_argument_semantics() {
        for scope in EventScope::ALL {
            for rate in [SamplingRate::Frequency(99), SamplingRate::Period(4096)] {
                for call_graph in CallGraphMode::ALL {
                    let configuration = SamplingConfiguration {
                        event: "cpu-cycles".into(),
                        rate,
                        call_graph,
                        scope,
                    };
                    let mut expected = vec![
                        "-e".to_owned(),
                        format!("cpu-cycles{}", scope.modifier()),
                        rate.argument().into(),
                        rate.value().to_string(),
                    ];
                    expected.extend(
                        call_graph
                            .arguments()
                            .iter()
                            .map(|value| (*value).to_owned()),
                    );
                    assert_eq!(configuration.record_arguments().unwrap(), expected);
                }
            }
        }
    }

    #[test]
    fn invalid_event_and_rate_never_reach_shell_command_construction() {
        for event in [
            "",
            " cpu-clock",
            "cpu clock",
            "cpu-clock;id",
            "$(id)",
            "`id`",
            "cpu-clock\n",
            "-e",
            "event\u{0}",
            "事件",
        ] {
            let configuration = SamplingConfiguration {
                event: event.into(),
                ..SamplingConfiguration::default()
            };
            assert!(configuration.record_arguments().is_err(), "{event:?}");
        }
        for rate in [
            SamplingRate::Frequency(0),
            SamplingRate::Frequency(4001),
            SamplingRate::Period(0),
            SamplingRate::Period(i64::MAX as u64 + 1),
        ] {
            assert!(
                SamplingConfiguration {
                    rate,
                    ..SamplingConfiguration::default()
                }
                .record_arguments()
                .is_err()
            );
        }
        let configuration = SamplingConfiguration {
            event: "sched:sched_switch".into(),
            rate: SamplingRate::Period(i64::MAX as u64),
            ..SamplingConfiguration::default()
        };
        configuration.validate().unwrap();
    }
}
