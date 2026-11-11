//! Optional, bounded static Compose compiler diagnostics. These are not runtime
//! recomposition, timing, or jank measurements and never authorize inspection.

use aps_platform::CancellationToken;
use std::collections::{BTreeMap, BTreeSet, VecDeque};
use std::fmt;
use std::fs::{self, File};
use std::io::Read;
use std::path::{Path, PathBuf};

const MAX_DIRECTORY_ENTRIES: usize = 10_000;
const MAX_REPORT_FILES: usize = 256;
const MAX_FILE_BYTES: usize = 8 * 1024 * 1024;
const MAX_TOTAL_BYTES: usize = 32 * 1024 * 1024;
const MAX_LINE_BYTES: usize = 64 * 1024;
const MAX_RECORDS: usize = 250_000;
const MAX_FIELDS: usize = 256;
const MAX_FIELD_BYTES: usize = 8192;
const MAX_PARAMETERS: usize = 512;

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum CompilerStability {
    Stable,
    Unstable,
    Runtime,
    Unknown,
}
impl CompilerStability {
    fn parse(value: &str) -> Self {
        match value.to_ascii_lowercase().as_str() {
            "stable" => Self::Stable,
            "unstable" => Self::Unstable,
            "runtime" => Self::Runtime,
            _ => Self::Unknown,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CompilerParameter {
    pub name: String,
    pub type_name: String,
    pub stability: CompilerStability,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct CompilerFunction {
    pub package_name: Option<String>,
    pub name: String,
    pub restartable: bool,
    pub skippable: bool,
    pub readonly: bool,
    pub parameters: Vec<CompilerParameter>,
    pub source_file: Option<String>,
    pub source_line: Option<u32>,
}

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct CompilerClass {
    pub name: String,
    pub stability: CompilerStability,
    pub unstable_members: Vec<String>,
}

#[derive(Clone, Debug)]
pub struct CompilerReport {
    pub source_directory: PathBuf,
    pub files: Vec<PathBuf>,
    pub functions: Vec<CompilerFunction>,
    pub classes: Vec<CompilerClass>,
    pub warnings: Vec<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CompilerReportError {
    Input,
    Unsupported,
    NoReports,
    NoData,
    InvalidFormat,
    Limit,
    Cancelled,
}
impl fmt::Display for CompilerReportError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Input => "Cannot read the selected Compose compiler reports",
            Self::Unsupported => {
                "Select a *-composables.txt, *-composables.csv, or *-classes.txt report"
            }
            Self::NoReports => {
                "No Compose compiler reports were found directly in the selected folder"
            }
            Self::NoData => "No readable Compose compiler function or class data was found",
            Self::InvalidFormat => "The Compose compiler report format is invalid or unsupported",
            Self::Limit => "Compose compiler reports exceed a bounded input limit",
            Self::Cancelled => "Compose compiler report import was cancelled",
        })
    }
}
impl std::error::Error for CompilerReportError {}

#[derive(Clone, Copy, Debug)]
pub enum CompilerFunctionMatch<'a> {
    Missing,
    Unique(&'a CompilerFunction),
    Ambiguous(usize),
}

impl CompilerReport {
    /// Select a module through any one of its reports, or all modules whose
    /// reports are directly in a selected directory. Subdirectories and APKs
    /// are never read. CSV metadata and text parameters are merged by name and
    /// occurrence order, separately for each module prefix.
    pub fn parse(
        selection: &Path,
        cancel: &CancellationToken,
    ) -> Result<Self, CompilerReportError> {
        check_cancel(cancel)?;
        let selection = selection
            .canonicalize()
            .map_err(|_| CompilerReportError::Input)?;
        let metadata = fs::metadata(&selection).map_err(|_| CompilerReportError::Input)?;
        let (directory, selected_prefix) = if metadata.is_dir() {
            (selection, None)
        } else if metadata.is_file() {
            let (prefix, _) = report_kind(&selection).ok_or(CompilerReportError::Unsupported)?;
            (
                selection
                    .parent()
                    .ok_or(CompilerReportError::Input)?
                    .to_owned(),
                Some(prefix),
            )
        } else {
            return Err(CompilerReportError::Input);
        };
        let mut groups: BTreeMap<String, Vec<(PathBuf, ReportKind)>> = BTreeMap::new();
        let mut count = 0;
        for (index, entry) in fs::read_dir(&directory)
            .map_err(|_| CompilerReportError::Input)?
            .enumerate()
        {
            check_cancel(cancel)?;
            if index >= MAX_DIRECTORY_ENTRIES {
                return Err(CompilerReportError::Limit);
            }
            let entry = entry.map_err(|_| CompilerReportError::Input)?;
            // Do not follow sibling symlinks or scan nested directories.
            if !entry
                .file_type()
                .map_err(|_| CompilerReportError::Input)?
                .is_file()
            {
                continue;
            }
            let path = entry.path();
            let Some((prefix, kind)) = report_kind(&path) else {
                continue;
            };
            if selected_prefix
                .as_ref()
                .is_some_and(|selected| selected != &prefix)
            {
                continue;
            }
            count += 1;
            if count > MAX_REPORT_FILES {
                return Err(CompilerReportError::Limit);
            }
            groups.entry(prefix).or_default().push((path, kind));
        }
        if count == 0 {
            return Err(CompilerReportError::NoReports);
        }
        let mut report = Self {
            source_directory: directory,
            files: Vec::new(),
            functions: Vec::new(),
            classes: Vec::new(),
            warnings: Vec::new(),
        };
        let mut total_bytes = 0;
        let mut records = 0;
        for files in groups.values_mut() {
            files.sort_by(|a, b| a.0.cmp(&b.0));
            let mut csv = Vec::new();
            let mut text = Vec::new();
            for (file, kind) in files {
                check_cancel(cancel)?;
                let parsed =
                    read_report(file, &mut total_bytes, cancel).and_then(|contents| match kind {
                        ReportKind::Csv => {
                            parse_csv(&contents, &mut records, cancel).map(Parsed::Csv)
                        }
                        ReportKind::Text => {
                            parse_text(&contents, &mut records, cancel).map(Parsed::Text)
                        }
                        ReportKind::Classes => {
                            parse_classes(&contents, &mut records, cancel).map(Parsed::Classes)
                        }
                    });
                match parsed {
                    Ok(parsed) => {
                        match parsed {
                            Parsed::Csv(functions) => csv = functions,
                            Parsed::Text(functions) => text = functions,
                            Parsed::Classes(classes) => report.classes.extend(classes),
                        }
                        report.files.push(file.clone());
                    }
                    Err(error @ (CompilerReportError::Limit | CompilerReportError::Cancelled)) => {
                        return Err(error);
                    }
                    Err(error) => report.warnings.push(format!(
                        "{}: {error}",
                        file.file_name().unwrap_or_default().to_string_lossy()
                    )),
                }
            }
            report.functions.extend(merge_functions(csv, text));
        }
        check_cancel(cancel)?;
        if report.functions.is_empty() && report.classes.is_empty() {
            return Err(CompilerReportError::NoData);
        }
        let mut seen = BTreeSet::new();
        report.classes.retain(|class| seen.insert(class.clone()));
        Ok(report)
    }

    /// Never silently select the first same-named overload or module. A known
    /// report source file must match an available runtime source filename.
    pub fn match_function(
        &self,
        name: &str,
        source_file: Option<&str>,
    ) -> CompilerFunctionMatch<'_> {
        let mut matches = self.functions.iter().filter(|function| {
            function.name == name
                && function.source_file.as_deref().is_none_or(|file| {
                    source_file.is_some_and(|source| file_name(source) == file_name(file))
                })
        });
        let Some(first) = matches.next() else {
            return CompilerFunctionMatch::Missing;
        };
        let others = matches.count();
        if others == 0 {
            CompilerFunctionMatch::Unique(first)
        } else {
            CompilerFunctionMatch::Ambiguous(others + 1)
        }
    }
}

fn file_name(path: &str) -> &str {
    path.rsplit(['/', '\\']).next().unwrap_or(path)
}
fn check_cancel(cancel: &CancellationToken) -> Result<(), CompilerReportError> {
    if cancel.is_cancelled() {
        Err(CompilerReportError::Cancelled)
    } else {
        Ok(())
    }
}
fn record(records: &mut usize) -> Result<(), CompilerReportError> {
    *records += 1;
    if *records > MAX_RECORDS {
        Err(CompilerReportError::Limit)
    } else {
        Ok(())
    }
}

#[derive(Clone, Copy)]
enum ReportKind {
    Csv,
    Text,
    Classes,
}
enum Parsed {
    Csv(Vec<CompilerFunction>),
    Text(Vec<CompilerFunction>),
    Classes(Vec<CompilerClass>),
}
fn report_kind(path: &Path) -> Option<(String, ReportKind)> {
    let name = path.file_name()?.to_str()?;
    [
        ("-composables.csv", ReportKind::Csv),
        ("-composables.txt", ReportKind::Text),
        ("-classes.txt", ReportKind::Classes),
    ]
    .into_iter()
    .find_map(|(suffix, kind)| {
        name.strip_suffix(suffix)
            .filter(|prefix| !prefix.is_empty())
            .map(|prefix| (prefix.to_owned(), kind))
    })
}

fn read_report(
    path: &Path,
    total: &mut usize,
    cancel: &CancellationToken,
) -> Result<String, CompilerReportError> {
    let file = File::open(path).map_err(|_| CompilerReportError::Input)?;
    let metadata = file.metadata().map_err(|_| CompilerReportError::Input)?;
    if !metadata.is_file() {
        return Err(CompilerReportError::Input);
    }
    if metadata.len() > MAX_FILE_BYTES as u64 {
        return Err(CompilerReportError::Limit);
    }
    let mut contents = Vec::new();
    let mut reader = file.take(MAX_FILE_BYTES as u64 + 1);
    let mut buffer = [0; 16 * 1024];
    loop {
        check_cancel(cancel)?;
        let length = reader
            .read(&mut buffer)
            .map_err(|_| CompilerReportError::Input)?;
        if length == 0 {
            break;
        }
        *total += length;
        if *total > MAX_TOTAL_BYTES || contents.len() + length > MAX_FILE_BYTES {
            return Err(CompilerReportError::Limit);
        }
        contents.extend_from_slice(&buffer[..length]);
    }
    let contents = String::from_utf8(contents).map_err(|_| CompilerReportError::InvalidFormat)?;
    if contents.lines().any(|line| line.len() > MAX_LINE_BYTES) {
        return Err(CompilerReportError::Limit);
    }
    Ok(contents.trim_start_matches('\u{feff}').to_owned())
}

fn merge_functions(
    csv: Vec<CompilerFunction>,
    text: Vec<CompilerFunction>,
) -> Vec<CompilerFunction> {
    // Keep original text order for unmatched entries, as well as n-th overload
    // pairing; do not collapse distinct same-named functions.
    let mut indexes: BTreeMap<String, VecDeque<usize>> = BTreeMap::new();
    for (index, function) in text.iter().enumerate() {
        indexes
            .entry(function.name.clone())
            .or_default()
            .push_back(index);
    }
    let mut text: Vec<Option<CompilerFunction>> = text.into_iter().map(Some).collect();
    let mut merged: Vec<_> = csv
        .into_iter()
        .map(|mut function| {
            if let Some(index) = indexes
                .get_mut(&function.name)
                .and_then(VecDeque::pop_front)
            {
                function.parameters = text[index].take().unwrap().parameters;
            }
            function
        })
        .collect();
    merged.extend(text.into_iter().flatten());
    merged
}

fn parse_csv(
    contents: &str,
    records: &mut usize,
    cancel: &CancellationToken,
) -> Result<Vec<CompilerFunction>, CompilerReportError> {
    let mut lines = contents.lines().filter(|line| !line.trim().is_empty());
    let header = split_csv(lines.next().ok_or(CompilerReportError::InvalidFormat)?)?
        .into_iter()
        .map(|value| value.trim().to_ascii_lowercase())
        .collect::<Vec<_>>();
    if !header.iter().any(|name| name == "name") {
        return Err(CompilerReportError::InvalidFormat);
    }
    // Resolve columns once. A wide header and many tiny rows must not make
    // each field lookup repeatedly scan the entire header.
    let column = |names: &[&str]| {
        names
            .iter()
            .find_map(|name| header.iter().position(|key| key == name))
    };
    let name_column = column(&["name"]).unwrap();
    let package_column = column(&["package", "packagename"]);
    let restartable_column = column(&["restartable"]);
    let skippable_column = column(&["skippable"]);
    let readonly_column = column(&["readonly"]);
    let file_column = column(&["file", "sourcefile"]);
    let line_column = column(&["line", "sourceline"]);
    let mut functions = Vec::new();
    for line in lines {
        check_cancel(cancel)?;
        // Charge every scanned row, including rows without a function name.
        record(records)?;
        let values = split_csv(line)?;
        let value = |column: Option<usize>| {
            column
                .and_then(|index| values.get(index))
                .map(|value| value.trim())
                .filter(|value| !value.is_empty())
        };
        let Some(qualified_name) = value(Some(name_column)) else {
            continue;
        };
        let name = qualified_name
            .rsplit('.')
            .next()
            .unwrap_or(qualified_name)
            .to_owned();
        let package_name = value(package_column)
            .filter(|package| *package != name)
            .map(|package| {
                package
                    .strip_suffix(&format!(".{name}"))
                    .unwrap_or(package)
                    .to_owned()
            });
        let flag = |column: Option<usize>| {
            value(column).is_some_and(|value| {
                matches!(value.to_ascii_lowercase().as_str(), "true" | "yes" | "1")
            })
        };
        functions.push(CompilerFunction {
            package_name,
            name,
            restartable: flag(restartable_column),
            skippable: flag(skippable_column),
            readonly: flag(readonly_column),
            source_file: value(file_column).map(str::to_owned),
            source_line: value(line_column).and_then(|value| value.parse().ok()),
            parameters: Vec::new(),
        });
    }
    Ok(functions)
}

fn split_csv(line: &str) -> Result<Vec<String>, CompilerReportError> {
    let mut values = Vec::new();
    let mut value = String::new();
    let mut quoted = false;
    let mut chars = line.chars().peekable();
    while let Some(character) = chars.next() {
        match character {
            '"' if quoted && chars.peek() == Some(&'"') => {
                chars.next();
                value.push('"');
            }
            '"' => quoted = !quoted,
            ',' if !quoted => {
                if value.len() > MAX_FIELD_BYTES || values.len() + 1 >= MAX_FIELDS {
                    return Err(CompilerReportError::Limit);
                }
                values.push(std::mem::take(&mut value));
            }
            _ => value.push(character),
        }
    }
    if quoted {
        return Err(CompilerReportError::InvalidFormat);
    }
    if value.len() > MAX_FIELD_BYTES {
        return Err(CompilerReportError::Limit);
    }
    values.push(value);
    Ok(values)
}

fn parse_text(
    contents: &str,
    records: &mut usize,
    cancel: &CancellationToken,
) -> Result<Vec<CompilerFunction>, CompilerReportError> {
    let mut functions = Vec::new();
    let mut current: Option<CompilerFunction> = None;
    for line in contents.lines() {
        check_cancel(cancel)?;
        if let Some((prefix, name)) = function_header(line) {
            if name.len() > MAX_FIELD_BYTES {
                return Err(CompilerReportError::Limit);
            }
            if let Some(previous) = current.take() {
                functions.push(previous);
            }
            record(records)?;
            current = Some(CompilerFunction {
                name: name.rsplit('.').next().unwrap_or(name).to_owned(),
                restartable: prefix.contains("restartable"),
                skippable: prefix.contains("skippable"),
                readonly: prefix.contains("readonly"),
                ..Default::default()
            });
        } else if line.trim() == ")" {
            if let Some(previous) = current.take() {
                functions.push(previous);
            }
        } else if let Some(function) = current.as_mut()
            && let Some(parameter) = parameter(line)
        {
            if function.parameters.len() >= MAX_PARAMETERS
                || parameter.name.len() > MAX_FIELD_BYTES
                || parameter.type_name.len() > MAX_FIELD_BYTES
            {
                return Err(CompilerReportError::Limit);
            }
            record(records)?;
            function.parameters.push(parameter);
        }
    }
    if let Some(previous) = current {
        functions.push(previous);
    }
    Ok(functions)
}

fn function_header(line: &str) -> Option<(&str, &str)> {
    for (index, _) in line.match_indices("fun") {
        if index > 0
            && line[..index]
                .chars()
                .next_back()
                .is_some_and(|value| value.is_alphanumeric() || value == '_')
        {
            continue;
        }
        let after = &line[index + 3..];
        if !after.starts_with(char::is_whitespace) {
            continue;
        }
        let after = after.trim_start();
        let name_end = after
            .find(|value: char| !identifier_char(value, true))
            .unwrap_or(after.len());
        let name = &after[..name_end];
        if valid_identifier(name, true) && after[name_end..].trim_start().starts_with('(') {
            return Some((&line[..index], name));
        }
    }
    None
}
fn identifier_char(value: char, qualified: bool) -> bool {
    value.is_ascii_alphanumeric()
        || value == '_'
        || (qualified && matches!(value, '.' | '$' | '<' | '>'))
}
fn valid_identifier(name: &str, qualified: bool) -> bool {
    name.starts_with(|value: char| value.is_ascii_alphabetic() || value == '_')
        && name.chars().all(|value| identifier_char(value, qualified))
}
fn parameter(line: &str) -> Option<CompilerParameter> {
    if !line.starts_with(char::is_whitespace) {
        return None;
    }
    let mut text = line.trim();
    if let Some(after) = text.strip_prefix("unused ") {
        text = after.trim_start();
    }
    let mut stability = CompilerStability::Unknown;
    if let Some((first, after)) = text.split_once(char::is_whitespace)
        && matches!(
            first.to_ascii_lowercase().as_str(),
            "stable" | "unstable" | "runtime" | "unknown"
        )
    {
        stability = CompilerStability::parse(first);
        text = after.trim_start();
    }
    let (name, type_name) = text.split_once(':')?;
    let name = name.trim();
    if !valid_identifier(name, false) {
        return None;
    }
    let type_name = type_name
        .split(" = ")
        .next()?
        .trim()
        .trim_end_matches([',', ')'])
        .trim();
    if type_name.is_empty() {
        return None;
    }
    Some(CompilerParameter {
        name: name.to_owned(),
        type_name: type_name.to_owned(),
        stability,
    })
}

fn parse_classes(
    contents: &str,
    records: &mut usize,
    cancel: &CancellationToken,
) -> Result<Vec<CompilerClass>, CompilerReportError> {
    let mut classes = Vec::new();
    let mut current: Option<CompilerClass> = None;
    for line in contents.lines() {
        check_cancel(cancel)?;
        let text = line.trim();
        let header = text
            .split_once(char::is_whitespace)
            .and_then(|(stability, after)| {
                if !matches!(
                    stability.to_ascii_lowercase().as_str(),
                    "stable" | "unstable" | "runtime" | "unknown"
                ) {
                    return None;
                }
                let after = after.trim_start().strip_prefix("class ")?.trim_start();
                let end = after
                    .find(|value: char| !identifier_char(value, true))
                    .unwrap_or(after.len());
                valid_identifier(&after[..end], true).then_some((stability, &after[..end]))
            });
        if let Some((stability, name)) = header {
            if name.len() > MAX_FIELD_BYTES {
                return Err(CompilerReportError::Limit);
            }
            if let Some(previous) = current.take() {
                classes.push(previous);
            }
            record(records)?;
            current = Some(CompilerClass {
                name: name.to_owned(),
                stability: CompilerStability::parse(stability),
                unstable_members: Vec::new(),
            });
        } else if text == "}" {
            if let Some(previous) = current.take() {
                classes.push(previous);
            }
        } else if let Some(class) = current.as_mut()
            && let Some(member) = text.strip_prefix("unstable ")
        {
            if member.len() > MAX_FIELD_BYTES || class.unstable_members.len() >= MAX_PARAMETERS {
                return Err(CompilerReportError::Limit);
            }
            record(records)?;
            class.unstable_members.push(member.to_owned());
        }
    }
    if let Some(previous) = current {
        classes.push(previous);
    }
    Ok(classes)
}

#[cfg(test)]
mod tests;
