//! Deterministic two-sample BCa interval for the median difference.

pub fn median(values: &[f64]) -> Option<f64> {
    if values.is_empty() || values.iter().any(|value| !value.is_finite()) {
        return None;
    }
    let mut sorted = values.to_vec();
    sorted.sort_by(f64::total_cmp);
    let middle = sorted.len() / 2;
    Some(if sorted.len() % 2 == 0 {
        sorted[middle - 1] / 2.0 + sorted[middle] / 2.0
    } else {
        sorted[middle]
    })
}

pub fn bca_median_difference(
    before: &[f64],
    after: &[f64],
    iterations: usize,
    confidence: f64,
) -> Option<(f64, f64)> {
    let total_samples = before.len().checked_add(after.len())?;
    if before.len() < 3
        || after.len() < 3
        || total_samples > 20_000
        || total_samples.checked_mul(iterations)? > 5_000_000
    {
        return None;
    }
    let observed = median(after)? - median(before)?;
    if !observed.is_finite() {
        return None;
    }
    let mut rng = Random(0x6170_7362_6361_0001);
    let mut bootstrap = Vec::with_capacity(iterations);
    let mut left = vec![0.0; before.len()];
    let mut right = vec![0.0; after.len()];
    for _ in 0..iterations {
        for value in &mut left {
            *value = before[rng.index(before.len())];
        }
        for value in &mut right {
            *value = after[rng.index(after.len())];
        }
        bootstrap.push(median(&right)? - median(&left)?);
    }
    bootstrap.sort_by(f64::total_cmp);
    // Midrank ties avoid an infinite bias for constant (but non-identical) cohorts.
    let less = bootstrap.iter().filter(|value| **value < observed).count() as f64;
    let equal = bootstrap.iter().filter(|value| **value == observed).count() as f64;
    let proportion = ((less + equal / 2.0) / iterations as f64)
        .clamp(0.5 / iterations as f64, 1.0 - 0.5 / iterations as f64);
    let bias = normal_inverse(proportion);
    // Stratified jackknife: do not pool unbalanced cohort delete-one estimates.
    // Each cohort's influence is centered and scaled by its own sample size.
    let left_jackknife = leave_one_medians(before);
    let right_jackknife = leave_one_medians(after);
    let influences = |values: &[f64], sign: f64| {
        let n = values.len() as f64;
        let center = values.iter().sum::<f64>() / n;
        values
            .iter()
            .map(move |value| sign * (n - 1.0) * (center - value) / n)
            .collect::<Vec<_>>()
    };
    let jackknife: Vec<_> = influences(&left_jackknife, -1.0)
        .into_iter()
        .chain(influences(&right_jackknife, 1.0))
        .collect();
    let squared = jackknife.iter().map(|value| value.powi(2)).sum::<f64>();
    let cubed = jackknife.iter().map(|value| value.powi(3)).sum::<f64>();
    let acceleration = if squared > 0.0 {
        cubed / (6.0 * squared.powf(1.5))
    } else {
        0.0
    };
    let alpha = (1.0 - confidence) / 2.0;
    let adjusted = |probability| {
        let z = normal_inverse(probability);
        let denominator = 1.0 - acceleration * (bias + z);
        if denominator.abs() < 1e-12 {
            return None;
        }
        Some(normal_cdf(bias + (bias + z) / denominator).clamp(0.0, 1.0))
    };
    let low = quantile(&bootstrap, adjusted(alpha)?)?;
    let high = quantile(&bootstrap, adjusted(1.0 - alpha)?)?;
    (low.is_finite() && high.is_finite() && low <= high).then_some((low, high))
}

fn leave_one_medians(values: &[f64]) -> Vec<f64> {
    let mut sorted = values.to_vec();
    sorted.sort_by(f64::total_cmp);
    let n = sorted.len() - 1;
    (0..sorted.len())
        .map(|removed| {
            let at = |index: usize| sorted[index + usize::from(index >= removed)];
            if n % 2 == 0 {
                at(n / 2 - 1) / 2.0 + at(n / 2) / 2.0
            } else {
                at(n / 2)
            }
        })
        .collect()
}

fn quantile(values: &[f64], probability: f64) -> Option<f64> {
    let index = probability * values.len().saturating_sub(1) as f64;
    let lower = index.floor() as usize;
    let upper = index.ceil() as usize;
    Some(values.get(lower)? * (1.0 - index.fract()) + values.get(upper)? * index.fract())
}

struct Random(u64);
impl Random {
    fn index(&mut self, length: usize) -> usize {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        (self.0 % length as u64) as usize
    }
}

// Abramowitz-Stegun normal CDF; inverse by monotonic bounded bisection.
fn normal_cdf(value: f64) -> f64 {
    let x = value.abs();
    let t = 1.0 / (1.0 + 0.231_641_9 * x);
    let polynomial = t
        * (0.319_381_530
            + t * (-0.356_563_782
                + t * (1.781_477_937 + t * (-1.821_255_978 + t * 1.330_274_429))));
    let tail = (-x * x / 2.0).exp() / (2.0 * std::f64::consts::PI).sqrt() * polynomial;
    if value >= 0.0 { 1.0 - tail } else { tail }
}

fn normal_inverse(probability: f64) -> f64 {
    let (mut low, mut high) = (-9.0, 9.0);
    for _ in 0..80 {
        let middle = (low + high) / 2.0;
        if normal_cdf(middle) < probability {
            low = middle;
        } else {
            high = middle;
        }
    }
    (low + high) / 2.0
}
