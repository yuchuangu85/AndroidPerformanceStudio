use super::*;
use std::sync::atomic::{AtomicU64, Ordering};

struct Reports(PathBuf);
impl Reports {
    fn new() -> Self {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let path = std::env::temp_dir().join(format!(
            "aps-compiler-reports-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&path).unwrap();
        Self(path)
    }
    fn write(&self, name: &str, contents: &str) {
        fs::write(self.0.join(name), contents).unwrap();
    }
    fn parse(&self, name: &str) -> Result<CompilerReport, CompilerReportError> {
        CompilerReport::parse(&self.0.join(name), &CancellationToken::new())
    }
    fn seed(&self) {
        self.write("app-composables.csv", "package,name,composable,skippable,restartable,readonly,\ncom.example.Feed,Feed,1,1,1,0,\n");
        self.write("app-composables.txt", "restartable skippable scheme(\"[androidx.compose.ui.UiComposable]\") fun Feed(\n  unstable state: FeedState\n  retained: RetainedState\n  stable modifier: Modifier? = @static Companion\n  unused stable title: String = @dynamic title\n)\n");
        self.write("app-classes.txt", "unstable class FeedState {\n  unstable val items: List<Item>\n  <runtime stability> = Unstable\n}\n");
    }
}
impl Drop for Reports {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

#[test]
fn folder_and_each_file_merge_only_same_prefix_siblings() {
    let reports = Reports::new();
    reports.seed();
    reports.write("other-composables.txt", "restartable fun Other()");
    for name in [
        "app-composables.csv",
        "app-composables.txt",
        "app-classes.txt",
    ] {
        let report = reports.parse(name).unwrap();
        assert_eq!(report.files.len(), 3);
        assert_eq!(report.functions.len(), 1);
        let function = &report.functions[0];
        assert_eq!(function.name, "Feed");
        assert_eq!(function.package_name.as_deref(), Some("com.example"));
        assert!(function.restartable && function.skippable && !function.readonly);
        assert_eq!(function.parameters.len(), 4);
        assert_eq!(
            function.parameters[0].stability,
            CompilerStability::Unstable
        );
        assert_eq!(function.parameters[1].stability, CompilerStability::Unknown);
        assert_eq!(function.parameters[2].type_name, "Modifier?");
        assert_eq!(function.parameters[3].type_name, "String");
        assert_eq!(report.classes[0].name, "FeedState");
        assert_eq!(
            report.classes[0].unstable_members,
            ["val items: List<Item>"]
        );
        assert!(report.warnings.is_empty());
    }
    let all = reports.parse("").unwrap();
    assert_eq!(all.files.len(), 4);
    assert_eq!(all.functions.len(), 2);
}

#[test]
fn overloads_pair_in_occurrence_order_and_modules_do_not_shadow() {
    let reports = Reports::new();
    reports.write(
        "app-composables.csv",
        "package,name,skippable\none.Feed,Feed,1\ntwo.Feed,Feed,0\n",
    );
    reports.write("app-composables.txt", "restartable fun Feed(\n unstable first: A\n)\nrestartable fun Feed(\n stable second: B\n)\nrestartable fun Unpaired()\n");
    reports.write("other-composables.txt", "fun Feed(\n runtime third: C\n)\n");
    let report = reports.parse("").unwrap();
    assert_eq!(report.functions.len(), 4);
    assert_eq!(report.functions[0].parameters[0].name, "first");
    assert!(report.functions[0].skippable);
    assert_eq!(report.functions[1].parameters[0].name, "second");
    assert!(!report.functions[1].skippable);
    assert_eq!(report.functions[2].name, "Unpaired");
    assert_eq!(report.functions[3].parameters[0].name, "third");
    assert!(matches!(
        report.match_function("Feed", None),
        CompilerFunctionMatch::Ambiguous(3)
    ));
}

#[test]
fn invalid_sibling_is_warned_but_empty_invalid_and_metrics_inputs_fail() {
    let reports = Reports::new();
    assert_eq!(
        reports.parse("").unwrap_err(),
        CompilerReportError::NoReports
    );
    assert_eq!(
        reports.parse("missing").unwrap_err(),
        CompilerReportError::Input
    );
    reports.write("app-module.json", "{}");
    assert_eq!(
        reports.parse("app-module.json").unwrap_err(),
        CompilerReportError::Unsupported
    );
    reports.write("app-composables.csv", "unsupported,columns\n1,2");
    assert_eq!(reports.parse("").unwrap_err(), CompilerReportError::NoData);
    reports.write("app-composables.txt", "restartable fun Feed()");
    let report = reports.parse("").unwrap();
    assert_eq!(report.files.len(), 1);
    assert_eq!(report.warnings.len(), 1);
    assert!(report.warnings[0].starts_with("app-composables.csv:"));
    reports.write("app-composables.txt", "not a compiler report");
    assert_eq!(reports.parse("").unwrap_err(), CompilerReportError::NoData);
}

#[test]
fn folder_import_does_not_recurse() {
    let reports = Reports::new();
    fs::create_dir(reports.0.join("release")).unwrap();
    reports.write("release/app-composables.txt", "fun Nested()");
    assert_eq!(
        reports.parse("").unwrap_err(),
        CompilerReportError::NoReports
    );
}

#[test]
fn csv_handles_bom_quoted_commas_escaped_quotes_and_aliases() {
    let reports = Reports::new();
    reports.write("app-composables.csv", "\u{feff}PackageName,Name,Restartable,Skippable,Readonly,SourceFile,SourceLine\nexample.Feed,Feed,yes,true,1,\"A,\"\"B.kt\",42\n");
    let report = reports.parse("").unwrap();
    let function = &report.functions[0];
    assert!(function.restartable && function.skippable && function.readonly);
    assert_eq!(function.source_file.as_deref(), Some("A,\"B.kt"));
    assert_eq!(function.source_line, Some(42));
    assert!(split_csv("\"unterminated").is_err());
}

#[test]
fn source_filename_matching_rejects_missing_mismatch_and_ambiguity() {
    let reports = Reports::new();
    reports.write(
        "app-composables.csv",
        "name,file\nFeed,/src/Feed.kt\nFeed,Other.kt\nOther,\n",
    );
    let report = reports.parse("").unwrap();
    assert!(matches!(
        report.match_function("Feed", Some("C:\\src\\Feed.kt")),
        CompilerFunctionMatch::Unique(_)
    ));
    assert!(matches!(
        report.match_function("Feed", Some("Missing.kt")),
        CompilerFunctionMatch::Missing
    ));
    assert!(matches!(
        report.match_function("Feed", None),
        CompilerFunctionMatch::Missing
    ));
    assert!(matches!(
        report.match_function("Other", None),
        CompilerFunctionMatch::Unique(_)
    ));
}

#[test]
fn cancellation_and_input_limits_fail_closed_even_with_valid_siblings() {
    let reports = Reports::new();
    reports.seed();
    let cancel = CancellationToken::new();
    cancel.cancel();
    assert_eq!(
        CompilerReport::parse(&reports.0, &cancel).unwrap_err(),
        CompilerReportError::Cancelled
    );
    let file = File::create(reports.0.join("oversized-composables.txt")).unwrap();
    file.set_len(MAX_FILE_BYTES as u64 + 1).unwrap();
    assert_eq!(reports.parse("").unwrap_err(), CompilerReportError::Limit);
    fs::remove_file(reports.0.join("oversized-composables.txt")).unwrap();
    reports.write("long-composables.txt", &"x".repeat(MAX_LINE_BYTES + 1));
    assert_eq!(reports.parse("").unwrap_err(), CompilerReportError::Limit);
    let mut records = MAX_RECORDS;
    assert_eq!(record(&mut records), Err(CompilerReportError::Limit));
}

#[test]
fn report_count_is_bounded_before_reading() {
    let reports = Reports::new();
    for index in 0..=MAX_REPORT_FILES {
        reports.write(&format!("{index}-composables.txt"), "fun Item()");
    }
    assert_eq!(reports.parse("").unwrap_err(), CompilerReportError::Limit);
}

#[test]
fn class_only_reports_keep_unknown_runtime_stability_and_distinct_records() {
    let reports = Reports::new();
    reports.write("app-classes.txt", "runtime class RuntimeState {\n}\nunknown class UnknownState {\n}\nstable class StableState {\n}\n");
    let report = reports.parse("").unwrap();
    assert!(report.functions.is_empty());
    assert_eq!(
        report
            .classes
            .iter()
            .map(|class| class.stability)
            .collect::<Vec<_>>(),
        [
            CompilerStability::Runtime,
            CompilerStability::Unknown,
            CompilerStability::Stable
        ]
    );
}

#[test]
fn csv_work_is_bounded_for_wide_headers_and_empty_names() {
    assert_eq!(
        split_csv(&",".repeat(MAX_FIELDS)).unwrap_err(),
        CompilerReportError::Limit
    );
    assert_eq!(
        split_csv(&"x".repeat(MAX_FIELD_BYTES + 1)).unwrap_err(),
        CompilerReportError::Limit
    );
    let mut records = MAX_RECORDS;
    assert_eq!(
        parse_csv(
            "name,other\n,value\n",
            &mut records,
            &CancellationToken::new()
        )
        .unwrap_err(),
        CompilerReportError::Limit
    );
}

#[test]
fn oversized_parameter_collections_and_values_fail_without_truncation() {
    let contents = format!(
        "fun Feed(\n{}\n)",
        "  unstable item: State\n".repeat(MAX_PARAMETERS + 1)
    );
    assert_eq!(
        parse_text(&contents, &mut 0, &CancellationToken::new()).unwrap_err(),
        CompilerReportError::Limit
    );
    let contents = format!(
        "fun Feed(\n  unstable item: {}\n)",
        "x".repeat(MAX_FIELD_BYTES + 1)
    );
    assert_eq!(
        parse_text(&contents, &mut 0, &CancellationToken::new()).unwrap_err(),
        CompilerReportError::Limit
    );
}
