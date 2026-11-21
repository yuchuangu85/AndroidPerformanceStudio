#[path = "../build_support.rs"]
mod build_support;

use aps_ui_foundation::{FormatError, Language, TextKey};
use build_support::{
    Catalog, load_catalog, parse_catalog, placeholders, validate_catalogs, variant,
};
use std::{collections::BTreeSet, path::Path};

fn resources(locale: &str) -> Catalog {
    load_catalog(
        &Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("resources")
            .join(locale),
    )
    .unwrap()
}

#[test]
fn every_locale_has_exactly_the_generated_keys_and_values() {
    let english = resources("values");
    let chinese = resources("values-zh");
    validate_catalogs(&english, &chinese).unwrap();
    let generated: BTreeSet<_> = TextKey::ALL.iter().map(|key| key.as_str()).collect();
    assert_eq!(generated, english.keys().map(String::as_str).collect());
    assert_eq!(TextKey::ALL.len(), generated.len());
    for key in TextKey::ALL {
        assert_eq!(Language::English.text(*key), english[key.as_str()]);
        assert_eq!(
            Language::SimplifiedChinese.text(*key),
            chinese[key.as_str()]
        );
        let expected: BTreeSet<_> = key.parameters().iter().copied().collect();
        assert_eq!(expected, placeholders(&english[key.as_str()]).unwrap());
        assert_eq!(expected, placeholders(&chinese[key.as_str()]).unwrap());
        assert!(!variant(key.as_str()).is_empty());
    }
}

#[test]
fn missing_extra_and_mismatched_placeholders_are_rejected() {
    let en = parse_catalog(br#"{"message": "Hello {name}"}"#).unwrap();
    assert!(validate_catalogs(&en, &Catalog::new()).is_err());
    let extra = parse_catalog(br#"{"message": "Hello {name}", "extra": "Extra"}"#).unwrap();
    assert!(validate_catalogs(&en, &extra).is_err());
    let wrong_args = parse_catalog(br#"{"message": "Hello {other}"}"#).unwrap();
    assert!(validate_catalogs(&en, &wrong_args).is_err());
    let repeated = parse_catalog(br#"{"message": "Hello {name} {name}"}"#).unwrap();
    assert!(validate_catalogs(&en, &repeated).is_err());
    let reserved = parse_catalog(br#"{"self":"reserved identifier"}"#).unwrap();
    assert!(validate_catalogs(&reserved, &reserved).is_err());
    validate_catalogs(&en, &en).unwrap();
}

#[test]
fn invalid_duplicate_empty_and_non_string_entries_are_rejected() {
    for document in [
        r#"{"same":"first","same":"second"}"#,
        r#"{"not_a_string":42}"#,
        r#"{"empty":"  "}"#,
        r#"{"bad-key":"value"}"#,
        r#"{"invalid__key":"value"}"#,
        r#"{"message":"unclosed {name"}"#,
        r#"{"message":"unmatched }"}"#,
        r#"{"message":"invalid {name:}"}"#,
        r#"["not an object"]"#,
    ] {
        assert!(
            parse_catalog(document.as_bytes()).is_err(),
            "accepted {document}"
        );
    }
}

#[test]
fn duplicate_keys_across_resource_files_are_rejected() {
    let path = std::env::temp_dir().join(format!(
        "aps-locale-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos(),
    ));
    struct Directory(std::path::PathBuf);
    impl Drop for Directory {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }
    std::fs::create_dir(&path).unwrap();
    let directory = Directory(path);
    std::fs::write(directory.0.join("one.json"), br#"{"same":"first"}"#).unwrap();
    std::fs::write(directory.0.join("two.json"), br#"{"same":"second"}"#).unwrap();
    assert!(
        load_catalog(&directory.0)
            .unwrap_err()
            .contains("duplicate resource key")
    );
}

#[test]
fn formatter_rejects_missing_or_unknown_arguments() {
    let key = TextKey::ShellMigrationBuildVersion;
    assert_eq!(
        Language::English.try_format(key, &[]),
        Err(FormatError::ArgumentCount)
    );
    assert_eq!(
        Language::English.try_format(key, &[("unknown", &"0.1.0")]),
        Err(FormatError::ArgumentNames)
    );
    assert_eq!(
        Language::English.format(key, &[]),
        Language::English.text(key)
    );
    assert_eq!(
        Language::SimplifiedChinese.format(key, &[("version", &"0.1.0")]),
        "0.1.0 · 迁移构建"
    );
}

#[test]
fn translated_formats_preserve_argument_text_without_recursive_substitution() {
    let raw = "my::symbol/{source} <tag> 中文".to_owned();
    for language in Language::ALL {
        for key in TextKey::ALL {
            let args: Vec<(&str, &dyn std::fmt::Display)> = key
                .parameters()
                .iter()
                .map(|name| (*name, &raw as &dyn std::fmt::Display))
                .collect();
            let formatted = language.format(*key, &args);
            if args.is_empty() {
                assert_eq!(formatted, language.text(*key));
            } else {
                assert!(
                    formatted.contains(&raw),
                    "{} did not preserve arguments",
                    key.as_str()
                );
            }
        }
    }
}

#[test]
fn application_sources_no_longer_embed_bilingual_text_calls() {
    let app = Path::new(env!("CARGO_MANIFEST_DIR")).join("../aps-app/src");
    let mut directories = vec![app];
    while let Some(directory) = directories.pop() {
        for entry in std::fs::read_dir(directory).unwrap() {
            let path = entry.unwrap().path();
            if path.is_dir() {
                directories.push(path);
                continue;
            }
            if path.extension().is_none_or(|extension| extension != "rs") {
                continue;
            }
            let source = std::fs::read_to_string(&path).unwrap();
            let compact: String = source
                .chars()
                .filter(|character| !character.is_whitespace())
                .collect();
            assert!(
                !compact.contains(".text(\""),
                "{}: inline language text returned",
                path.display()
            );
            assert!(
                !compact.contains(".placeholder(\""),
                "{}: inline placeholder returned",
                path.display()
            );
        }
    }
}
