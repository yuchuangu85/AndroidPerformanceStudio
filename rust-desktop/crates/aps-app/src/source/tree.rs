//! Directory-first presentation of the entire immutable source manifest.
//!
//! Expansion is opt-in: a new workspace begins collapsed. Searching reveals
//! matching files and their ancestors without changing the saved expansion set.

use aps_source::{SourceFile, SourceLanguage};
use std::collections::{BTreeMap, BTreeSet};

pub(super) const TREE_ROW_HEIGHT: f32 = 20.;
pub(super) const TREE_INDENT: f32 = 14.;

#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct TreeRow {
    /// Original manifest identity for files; normalized directory path otherwise.
    pub path: String,
    pub name: String,
    pub depth: usize,
    pub kind: TreeRowKind,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) enum TreeRowKind {
    Directory { expanded: bool },
    File { language: SourceLanguage },
}

/// Parents merge a selected file's ancestors into their expansion state so a
/// navigation arriving from another profiler is visible even after filtering.
pub(super) fn ancestor_directories(relative_path: &str) -> BTreeSet<String> {
    let segments = path_segments(relative_path);
    let mut path = String::new();
    segments
        .iter()
        .take(segments.len().saturating_sub(1))
        .map(|segment| {
            if !path.is_empty() {
                path.push('/');
            }
            path.push_str(segment);
            path.clone()
        })
        .collect()
}

/// `files` is the complete indexed manifest, never one bounded file page.
/// Paths stay manifest-owned; this projection neither reads source nor resolves
/// filesystem locations. Search is case-insensitive over the relative path.
pub(super) fn tree_rows(
    files: &[SourceFile],
    query: &str,
    expanded: &BTreeSet<String>,
) -> Vec<TreeRow> {
    let needle = query.replace('\\', "/").to_lowercase();
    let searching = !needle.trim().is_empty();
    let mut root = Directory::default();
    let mut search_ancestors = BTreeSet::new();
    for file in files {
        if searching
            && !file
                .relative_path
                .replace('\\', "/")
                .to_lowercase()
                .contains(&needle)
        {
            continue;
        }
        if searching {
            search_ancestors.extend(ancestor_directories(&file.relative_path));
        }
        root.add(file);
    }
    let mut rows = Vec::new();
    root.append_rows(0, expanded, &search_ancestors, &mut rows);
    rows
}

#[derive(Default)]
struct Directory<'a> {
    name: String,
    path: String,
    directories: BTreeMap<String, Directory<'a>>,
    files: Vec<&'a SourceFile>,
}

impl<'a> Directory<'a> {
    fn add(&mut self, file: &'a SourceFile) {
        let segments = path_segments(&file.relative_path);
        if segments.is_empty() {
            return;
        }
        let mut directory = self;
        for segment in &segments[..segments.len() - 1] {
            let path = if directory.path.is_empty() {
                (*segment).to_owned()
            } else {
                format!("{}/{segment}", directory.path)
            };
            directory = directory
                .directories
                .entry((*segment).to_owned())
                .or_insert_with(|| Directory {
                    name: (*segment).to_owned(),
                    path,
                    ..Default::default()
                });
        }
        directory.files.push(file);
    }

    fn append_rows(
        &self,
        depth: usize,
        expanded: &BTreeSet<String>,
        forced_expanded: &BTreeSet<String>,
        rows: &mut Vec<TreeRow>,
    ) {
        let mut directories: Vec<_> = self.directories.values().collect();
        directories.sort_by_cached_key(|directory| {
            (directory.name.to_lowercase(), directory.name.clone())
        });
        for directory in directories {
            let is_expanded =
                expanded.contains(&directory.path) || forced_expanded.contains(&directory.path);
            rows.push(TreeRow {
                path: directory.path.clone(),
                name: directory.name.clone(),
                depth,
                kind: TreeRowKind::Directory {
                    expanded: is_expanded,
                },
            });
            if is_expanded {
                directory.append_rows(depth + 1, expanded, forced_expanded, rows);
            }
        }
        let mut files = self.files.clone();
        files.sort_by_cached_key(|file| {
            let name = file_name(&file.relative_path);
            (name.to_lowercase(), name)
        });
        for file in files {
            rows.push(TreeRow {
                path: file.relative_path.clone(),
                name: file_name(&file.relative_path),
                depth,
                kind: TreeRowKind::File {
                    language: file.language,
                },
            });
        }
    }
}

fn path_segments(path: &str) -> Vec<&str> {
    path.split(['/', '\\'])
        .filter(|segment| !segment.trim().is_empty() && *segment != ".")
        .collect()
}

fn file_name(path: &str) -> String {
    path_segments(path)
        .last()
        .copied()
        .unwrap_or(path)
        .to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_contracts::SourceDigest;

    fn file(path: &str) -> SourceFile {
        SourceFile {
            relative_path: path.to_owned(),
            content_hash: SourceDigest::from_hex(&"0".repeat(64)).unwrap(),
            language: SourceLanguage::Kotlin,
            byte_length: 1,
            line_count: 1,
            module: String::new(),
        }
    }

    fn paths(rows: &[TreeRow]) -> Vec<&str> {
        rows.iter().map(|row| row.path.as_str()).collect()
    }

    #[test]
    fn new_tree_is_collapsed_with_root_files_and_directory_first_order() {
        let rows = tree_rows(
            &[
                file("z.kt"),
                file("Beta/Inner.kt"),
                file("alpha/Inner.kt"),
                file("A.kt"),
            ],
            "",
            &BTreeSet::new(),
        );
        assert_eq!(paths(&rows), ["alpha", "Beta", "A.kt", "z.kt"]);
        assert!(rows[..2].iter().all(|row| {
            row.depth == 0 && row.kind == TreeRowKind::Directory { expanded: false }
        }));
        assert_eq!(TREE_ROW_HEIGHT, 20.);
        assert_eq!(TREE_INDENT, 14.);
    }

    #[test]
    fn nested_expansion_keeps_paths_depths_and_language() {
        let files = [file("app/src/Beta.kt"), file("app/src/Alpha.kt")];
        let rows = tree_rows(
            &files,
            "",
            &BTreeSet::from(["app".into(), "app/src".into()]),
        );
        assert_eq!(
            paths(&rows),
            ["app", "app/src", "app/src/Alpha.kt", "app/src/Beta.kt"]
        );
        assert_eq!(
            rows.iter().map(|row| row.depth).collect::<Vec<_>>(),
            [0, 1, 2, 2]
        );
        assert_eq!(
            rows[2].kind,
            TreeRowKind::File {
                language: SourceLanguage::Kotlin
            }
        );
    }

    #[test]
    fn search_matches_whole_manifest_and_expands_only_matching_ancestors() {
        let mut files: Vec<_> = (0..150)
            .map(|index| file(&format!("ordinary/{index}.kt")))
            .collect();
        files.push(file("app/src/MainViewModel.kt"));
        let expanded = BTreeSet::new();
        let rows = tree_rows(&files, "MAINVIEW", &expanded);
        assert_eq!(paths(&rows), ["app", "app/src", "app/src/MainViewModel.kt"]);
        assert!(
            rows[..2]
                .iter()
                .all(|row| row.kind == TreeRowKind::Directory { expanded: true })
        );
        assert!(expanded.is_empty());
        assert_eq!(tree_rows(&files, "no matches", &expanded), []);
    }

    #[test]
    fn searching_directory_path_retains_its_files_and_whitespace_means_all() {
        let files = [file("app/src/One.kt"), file("test/Two.kt")];
        assert_eq!(
            paths(&tree_rows(&files, "APP/SRC", &BTreeSet::new())),
            ["app", "app/src", "app/src/One.kt"]
        );
        assert_eq!(
            tree_rows(&files, "   ", &BTreeSet::new()),
            tree_rows(&files, "", &BTreeSet::new())
        );
    }

    #[test]
    fn selection_ancestors_make_file_visible_with_normalized_separators() {
        let selected = "app\\src/main/Main.kt";
        let ancestors = ancestor_directories(selected);
        assert_eq!(
            ancestors,
            BTreeSet::from(["app".into(), "app/src".into(), "app/src/main".into()])
        );
        let rows = tree_rows(&[file(selected)], "", &ancestors);
        assert_eq!(rows.last().unwrap().path, selected);
        assert_eq!(rows.last().unwrap().name, "Main.kt");
        assert_eq!(rows.last().unwrap().depth, 3);
        assert!(ancestor_directories("Main.kt").is_empty());
        assert!(ancestor_directories("").is_empty());
    }

    #[test]
    fn case_collisions_use_original_name_as_stable_tiebreaker() {
        let files = [
            file("app/z.kt"),
            file("app/a.kt"),
            file("app/A.kt"),
            file("app/Zed/One.kt"),
        ];
        let rows = tree_rows(&files, "", &BTreeSet::from(["app".into()]));
        assert_eq!(
            paths(&rows),
            ["app", "app/Zed", "app/A.kt", "app/a.kt", "app/z.kt"]
        );
    }
}
