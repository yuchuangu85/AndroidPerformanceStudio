//! Transport-independent v1 envelope for citeable, source-bound analysis facts.
//! Input acquisition, parser selection, and output destinations are host policy.
use aps_contracts::SourceDigest;
use serde_json::{Value, json};

pub const EVIDENCE_SCHEMA: &str = "aps-evidence-v1";
const MAX_EVIDENCE_BYTES: usize = 512 * 1024;

pub struct EvidenceItem {
    pub id: String,
    pub label: String,
    pub value: Value,
}

pub struct EvidenceBundle(Value);

impl EvidenceBundle {
    pub fn new(
        digest: SourceDigest,
        byte_length: u64,
        kind: &str,
        input_format: &str,
        items: Vec<EvidenceItem>,
    ) -> Result<Self, String> {
        if items.is_empty() {
            return Err("E_EVIDENCE_EMPTY".into());
        }
        let mut ids = std::collections::HashSet::new();
        if items
            .iter()
            .any(|item| item.id.is_empty() || !ids.insert(item.id.as_str()))
        {
            return Err("E_EVIDENCE_SCHEMA".into());
        }
        let items: Vec<_> = items
            .into_iter()
            .map(|i| json!({"id":i.id,"label":i.label,"value":i.value}))
            .collect();
        Ok(Self(json!({
            "schemaVersion":EVIDENCE_SCHEMA,
            "input":{"sourceDigest":digest.to_hex(),"byteLength":byte_length},
            "evidence":{"kind":kind,"inputFormat":input_format,"items":items},
        })))
    }

    /// Preserve existing section IDs and their deterministic map order.
    pub fn from_projection(
        digest: SourceDigest,
        byte_length: u64,
        projection: Value,
    ) -> Result<Self, String> {
        let Value::Object(mut sections) = projection else {
            return Err("E_EVIDENCE_SCHEMA".into());
        };
        let kind = sections.remove("kind").ok_or("E_EVIDENCE_SCHEMA")?;
        let format = sections.remove("inputFormat").ok_or("E_EVIDENCE_SCHEMA")?;
        let domain = kind.as_str().ok_or("E_EVIDENCE_SCHEMA")?;
        let items = sections
            .into_iter()
            .map(|(label, value)| EvidenceItem {
                id: format!("{domain}-{label}"),
                label,
                value,
            })
            .collect();
        Self::new(
            digest,
            byte_length,
            domain,
            format.as_str().ok_or("E_EVIDENCE_SCHEMA")?,
            items,
        )
    }

    pub fn into_json(self) -> Result<Value, String> {
        if serde_json::to_vec(&self.0)
            .map_err(|_| "E_EVIDENCE_ENCODING")?
            .len()
            > MAX_EVIDENCE_BYTES
        {
            return Err("E_EVIDENCE_LIMIT: analysis exceeded the output budget".into());
        }
        Ok(self.0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn digest() -> SourceDigest {
        SourceDigest::hash_reader(&b"source"[..], 6).unwrap()
    }

    #[test]
    fn legacy_sections_preserve_wire_shape_and_missing_values() {
        let bundle = EvidenceBundle::from_projection(
            digest(),
            6,
            json!({
                "kind":"frame","inputFormat":"test","coverage":null
            }),
        )
        .unwrap()
        .into_json()
        .unwrap();
        assert_eq!(
            bundle,
            json!({
                "schemaVersion":"aps-evidence-v1",
                "input":{"sourceDigest":digest().to_hex(),"byteLength":6},
                "evidence":{"kind":"frame","inputFormat":"test",
                    "items":[{"id":"frame-coverage","label":"coverage","value":null}]}
            })
        );
    }

    #[test]
    fn empty_duplicate_and_over_budget_publications_are_rejected() {
        let item = |value| EvidenceItem {
            id: "a".into(),
            label: "a".into(),
            value,
        };
        assert!(EvidenceBundle::new(digest(), 6, "test", "test", vec![]).is_err());
        assert!(
            EvidenceBundle::new(
                digest(),
                6,
                "test",
                "test",
                vec![item(Value::Null), item(Value::Null)]
            )
            .is_err()
        );
        let bundle = EvidenceBundle::new(
            digest(),
            6,
            "test",
            "test",
            vec![item("x".repeat(MAX_EVIDENCE_BYTES).into())],
        )
        .unwrap();
        assert!(
            bundle
                .into_json()
                .unwrap_err()
                .starts_with("E_EVIDENCE_LIMIT")
        );
    }
}
