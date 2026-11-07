//! Bounded trusted-host evidence construction. These helpers do not authorize
//! network access, read files, include local paths or create source candidates.
use aps_ai::{AnalysisRequest, Evidence};
use aps_json::{Limits, Value};

pub fn object(values: impl IntoIterator<Item = (&'static str, Value)>) -> Value {
    Value::Object(values.into_iter().map(|(k, v)| (k.to_owned(), v)).collect())
}

pub fn clipped(value: &str, max: usize) -> String {
    let mut end = value.len().min(max);
    while !value.is_char_boundary(end) {
        end -= 1;
    }
    value[..end].to_owned()
}

pub fn request(
    profiler: &str,
    scope: &str,
    summary: &str,
    payload: Value,
) -> Result<AnalysisRequest, String> {
    aps_json::to_vec(
        &payload,
        Limits {
            max_bytes: 32 * 1024,
            max_string_bytes: 2048,
            ..Limits::default()
        },
    )
    .map_err(|_| "Selected evidence exceeds the AI context budget")?;
    Ok(AnalysisRequest {
        session_id: format!("{profiler}-view"),
        run_id: format!("{profiler}-selection"),
        generation: 1,
        profiler: profiler.into(),
        scope: clipped(scope, 2048),
        evidence: vec![Evidence {
            id: "selection".into(),
            kind: "profiler-selection".into(),
            summary: clipped(summary, 2048),
            payload,
        }],
        candidates: Vec::new(),
        user_question: None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn utf8_and_payload_bounds_fail_closed() {
        assert_eq!(clipped("中a", 2), "");
        assert_eq!(clipped("中a", 3), "中");
        assert!(request("cpu", "scope", "summary", "x".repeat(40_000).into()).is_err());
    }
}
