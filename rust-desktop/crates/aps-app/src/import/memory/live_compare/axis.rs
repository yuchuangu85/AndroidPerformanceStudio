//! Shared numeric Y axis for desktop charts and standalone reports.
use super::meminfo::MetricUnit;

pub(super) struct AxisScale {
    pub maximum: u64,
    unit: MetricUnit,
}

impl AxisScale {
    pub fn new(maximum: u64, unit: MetricUnit) -> Self {
        // Keep zero/tiny charts readable in the displayed unit, not three 0.00 labels.
        Self {
            maximum: maximum.max(match unit {
                MetricUnit::Kib => 1024,
                MetricUnit::Count => 2,
            }),
            unit,
        }
    }

    /// Top, middle, bottom; fractions match the plotted grid lines exactly.
    pub fn ticks(&self) -> [(f32, String); 3] {
        [0.0, 0.5, 1.0].map(|position| {
            let raw = self.maximum as f64 * (1.0 - position as f64);
            let label = match self.unit {
                MetricUnit::Kib => format!("{:.2}", raw / 1024.0),
                MetricUnit::Count if raw.fract() == 0.0 => format!("{raw:.0}"),
                MetricUnit::Count => format!("{raw:.1}"),
            };
            (position, label)
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn memory_ticks_are_in_m_and_span_the_same_scale_as_the_curve() {
        let scale = AxisScale::new(8192, MetricUnit::Kib);
        assert_eq!(scale.maximum, 8192);
        assert_eq!(
            scale.ticks(),
            [
                (0.0, "8.00".into()),
                (0.5, "4.00".into()),
                (1.0, "0.00".into())
            ]
        );
    }
    #[test]
    fn empty_small_and_count_axes_keep_distinct_ticks() {
        for max in [0, 1, 64] {
            assert_eq!(
                AxisScale::new(max, MetricUnit::Kib).ticks().map(|(_, s)| s),
                ["1.00", "0.50", "0.00"]
            );
        }
        assert_eq!(
            AxisScale::new(3, MetricUnit::Count).ticks().map(|(_, s)| s),
            ["3", "1.5", "0"]
        );
        assert_eq!(AxisScale::new(0, MetricUnit::Count).maximum, 2);
    }
}
