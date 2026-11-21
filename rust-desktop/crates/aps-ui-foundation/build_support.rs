use aps_json::{Limits, Value, parse_slice};
use std::collections::{BTreeMap, BTreeSet};
use std::path::Path;

pub type Catalog = BTreeMap<String, String>;

pub fn parse_catalog(bytes: &[u8]) -> Result<Catalog, String> {
    let value = parse_slice(bytes, Limits::default()).map_err(|error| error.to_string())?;
    let Value::Object(entries) = value else {
        return Err("resource file must contain a JSON object".into());
    };
    entries
        .into_iter()
        .map(|(key, value)| {
            if !valid_key(&key) {
                return Err(format!("invalid resource key: {key:?}"));
            }
            let Value::String(text) = value else {
                return Err(format!("{key}: resource value must be a string"));
            };
            if text.trim().is_empty() {
                return Err(format!("{key}: empty resource value"));
            }
            placeholders(&text).map_err(|error| format!("{key}: {error}"))?;
            Ok((key, text))
        })
        .collect()
}

pub fn load_catalog(directory: &Path) -> Result<Catalog, String> {
    let mut files = std::fs::read_dir(directory)
        .map_err(|error| format!("{}: {error}", directory.display()))?
        .map(|entry| {
            entry
                .map(|entry| entry.path())
                .map_err(|error| error.to_string())
        })
        .collect::<Result<Vec<_>, _>>()?;
    files.sort();
    let mut catalog = Catalog::new();
    for file in files {
        if file.file_name().is_some_and(|name| name == ".DS_Store") {
            continue;
        }
        if !file.is_file() || file.extension().is_none_or(|extension| extension != "json") {
            return Err(format!(
                "{}: resources must be flat .json files",
                file.display()
            ));
        }
        let bytes = std::fs::read(&file).map_err(|error| error.to_string())?;
        let entries =
            parse_catalog(&bytes).map_err(|error| format!("{}: {error}", file.display()))?;
        for (key, text) in entries {
            if catalog.insert(key.clone(), text).is_some() {
                return Err(format!("{}: duplicate resource key {key}", file.display()));
            }
        }
    }
    if catalog.is_empty() {
        return Err(format!("{}: no resources", directory.display()));
    }
    Ok(catalog)
}

pub fn validate_catalogs(english: &Catalog, chinese: &Catalog) -> Result<(), String> {
    let missing: Vec<_> = english
        .keys()
        .filter(|key| !chinese.contains_key(*key))
        .collect();
    let extra: Vec<_> = chinese
        .keys()
        .filter(|key| !english.contains_key(*key))
        .collect();
    if !missing.is_empty() || !extra.is_empty() {
        return Err(format!(
            "locale key mismatch: missing={missing:?}, extra={extra:?}"
        ));
    }
    let mut variants = BTreeSet::new();
    for (key, text) in english {
        if variant(key) == "Self" {
            return Err(format!("reserved Rust resource identifier: {key}"));
        }
        if !variants.insert(variant(key)) {
            return Err(format!("resource keys collide as Rust identifiers: {key}"));
        }
        if placeholder_counts(text)? != placeholder_counts(&chinese[key])? {
            return Err(format!("{key}: locale placeholder mismatch"));
        }
    }
    Ok(())
}

pub fn variant(key: &str) -> String {
    key.split('_')
        .map(|part| format!("{}{}", part[..1].to_ascii_uppercase(), &part[1..]))
        .collect()
}

pub fn placeholders(text: &str) -> Result<BTreeSet<&str>, String> {
    Ok(placeholder_counts(text)?.into_keys().collect())
}

pub fn placeholder_counts(text: &str) -> Result<BTreeMap<&str, usize>, String> {
    let mut names = BTreeMap::new();
    let mut remaining = text;
    while let Some(start) = remaining.find(['{', '}']) {
        if remaining.as_bytes()[start] == b'}' {
            return Err("unmatched closing placeholder brace".into());
        }
        remaining = &remaining[start + 1..];
        let end = remaining.find('}').ok_or("unclosed placeholder")?;
        let name = &remaining[..end];
        if !valid_key(name) {
            return Err(format!("invalid placeholder: {name:?}"));
        }
        *names.entry(name).or_default() += 1;
        remaining = &remaining[end + 1..];
    }
    Ok(names)
}

fn valid_key(key: &str) -> bool {
    !key.is_empty()
        && key.as_bytes()[0].is_ascii_lowercase()
        && key.split('_').all(|part| {
            !part.is_empty()
                && part
                    .bytes()
                    .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit())
        })
}
