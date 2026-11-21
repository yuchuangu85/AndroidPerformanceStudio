use crate::{FrameLimits, Request, Value, MAX_OPERATION_BYTES};
use std::collections::BTreeSet;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ArgumentKind {
    Bool,
    Int,
    String,
    List,
    Map,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ArgumentField {
    pub name: String,
    pub kind: ArgumentKind,
    pub required: bool,
    pub nullable: bool,
}
/// A consumer must name the schema for each operation it actually implements.
/// An accepted schema describes syntax, not permission to execute an operation.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OperationSchema {
    pub operation: String,
    pub fields: Vec<ArgumentField>,
}
impl OperationSchema {
    pub fn validate_request(&self, request: &Request, limits: FrameLimits) -> Result<(), String> {
        request.validate(limits)?;
        if self.operation.is_empty()
            || self.operation.len() > MAX_OPERATION_BYTES
            || self.operation != request.operation
        {
            return Err("request operation does not match handler schema".into());
        }
        if self.fields.len() > 64 {
            return Err("operation schema exceeds field limit".into());
        }
        let mut names = BTreeSet::new();
        for field in &self.fields {
            if field.name.is_empty()
                || field.name.len() > 64
                || !field
                    .name
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || b"_-".contains(&byte))
                || !names.insert(field.name.as_str())
            {
                return Err("operation schema contains an invalid or duplicate field".into());
            }
        }
        let args = request.args.as_map()?;
        if args.keys().any(|key| !names.contains(key.as_str())) {
            return Err("operation arguments contain an unknown field".into());
        }
        for field in &self.fields {
            match args.get(&field.name) {
                None if field.required => {
                    return Err(format!(
                        "missing required operation argument {}",
                        field.name
                    ))
                }
                None => continue,
                Some(Value::Null) if field.nullable => continue,
                Some(value) => {
                    let valid = matches!(
                        (field.kind, value),
                        (ArgumentKind::Bool, Value::Bool(_))
                            | (ArgumentKind::Int, Value::Int(_))
                            | (ArgumentKind::String, Value::String(_))
                            | (ArgumentKind::List, Value::List(_))
                            | (ArgumentKind::Map, Value::Map(_))
                    );
                    if !valid {
                        return Err(format!("operation argument {} has wrong type", field.name));
                    }
                }
            }
        }
        Ok(())
    }
}
